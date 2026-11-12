using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// The 2026-11-12 installer pass: one Setup at a time, failures worded for what really happened, and a launcher that
// antivirus emptied out never reported as installed.
[Collection("download")] // stages through FakeServer like UninstallTests
public class RobustnessTests
{
    [Fact]
    public void ASecondSetupHandsOverToTheFirst()
    {
        var name = @"Local\OriginSetupTest." + Guid.NewGuid().ToString("N");
        using var first = SingleInstance.TryClaim(TimeSpan.Zero, name);
        Assert.NotNull(first);
        using var shown = new ManualResetEventSlim();
        first!.OnSecondCopy(() => shown.Set());

        SingleInstance? second = null;
        var t = new Thread(() => second = SingleInstance.TryClaim(TimeSpan.Zero, name)); // a mutex is per thread
        t.Start();
        t.Join();

        Assert.Null(second);
        Assert.True(shown.Wait(TimeSpan.FromSeconds(5)), "the running Setup was not asked to come forward");
    }

    [Fact]
    public void TheNextSetupRunsOnceTheFirstHasClosed()
    {
        var name = @"Local\OriginSetupTest." + Guid.NewGuid().ToString("N");
        SingleInstance.TryClaim(TimeSpan.Zero, name)!.Dispose();
        SingleInstance? again = null;
        var t = new Thread(() => { again = SingleInstance.TryClaim(TimeSpan.Zero, name); again?.Dispose(); });
        t.Start();
        t.Join();
        Assert.NotNull(again);
    }

    // An elevated Setup's mutex is out of a normal copy's reach. Stand-in: a mutex whose DACL denies everyone, so
    // opening it fails with access denied exactly as it does across the elevation boundary.
    [Fact]
    public void ASetupThatCantReachTheRunningOnesLockStillHandsOver()
    {
        var name = @"Local\OriginSetupTest." + Guid.NewGuid().ToString("N");
        Assert.True(ConvertStringSecurityDescriptorToSecurityDescriptorW("D:(D;;GA;;;WD)", 1, out var sd, IntPtr.Zero));
        var sa = new SecurityAttributes { Length = Marshal.SizeOf<SecurityAttributes>(), Descriptor = sd };
        var locked = CreateMutexW(ref sa, false, name);
        try
        {
            Assert.NotEqual(IntPtr.Zero, locked);
            Assert.Throws<UnauthorizedAccessException>(() => new Mutex(false, name)); // the case being covered
            Assert.Null(SingleInstance.TryClaim(TimeSpan.Zero, name));
        }
        finally
        {
            CloseHandle(locked);
            LocalFree(sd);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes
    {
        public int Length;
        public IntPtr Descriptor;
        public bool Inherit;
    }

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(string sddl, int revision, out IntPtr sd, IntPtr size);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateMutexW(ref SecurityAttributes sa, bool initialOwner, string name);
    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr mem);

    // The in-place uninstall (temp hand-off failed) runs as the uninstall.exe inside the folder it removes. That exe
    // can't go while it runs; the exit script removes it and the folder. It must not turn the result into "left".
    [Fact]
    public void TheRunningUninstallerIsNotCountedAsLeftOver()
    {
        foreach (var ownsFolder in new[] { true, false })
        {
            var dir = ownsFolder ? Path.Combine(TestKit.Temp("inplace"), "Origin") : TestKit.Temp("inplace");
            var prev = Installer.LauncherRunning;
            Installer.LauncherRunning = () => false;
            try
            {
                var setupExe = Path.Combine(TestKit.Temp("inplace-setup"), "OriginLauncher-Setup.exe");
                File.WriteAllBytes(setupExe, new byte[] { (byte)'M', (byte)'Z', 0, 0 }); // copied in as uninstall.exe
                var staged = Installer.Stage(dir, TestKit.Zip(TestKit.MakeLauncherTree()), null, _ => { }, CancellationToken.None,
                                             new FakeServer().Client(), setupExePath: setupExe);
                Installer.Commit(staged, dir, createdFolder: ownsFolder);
            }
            finally { Installer.LauncherRunning = prev; }
            var self = Path.Combine(dir, InstallLocation.UninstallerExe);
            Assert.True(File.Exists(self));

            using (new FileStream(self, FileMode.Open, FileAccess.Read, FileShare.Read)) // held like a running exe
            {
                var plan = UninstallPlan.For(dir, TestKit.Temp("inplace-data"));
                Assert.True(plan.Execute(keep: self), $"scope {plan.Scope}");
            }
            Assert.True(File.Exists(self));
            Assert.Equal(new[] { self }, Directory.GetFileSystemEntries(dir));
        }
    }

    [Fact]
    public void ACancelIsNotAProblemPage()
    {
        Assert.Null(InstallFailure.Describe(new OperationCanceledException(), updating: false));
        Assert.Null(InstallFailure.Describe(new AggregateException(new TaskCanceledExceptionStandIn()), updating: true));
    }

    private sealed class TaskCanceledExceptionStandIn : OperationCanceledException { }

    [Fact]
    public void AFailedUpdateSaysUpdatedNotInstalled()
    {
        var update = InstallFailure.Describe(new SetupException("Origin Launcher is still open. Close it and try again."), updating: true)!;
        Assert.Equal("Origin wasn't updated", update.Title);
        Assert.True(update.CanRetry);
        var fresh = InstallFailure.Describe(new SetupException("x"), updating: false)!;
        Assert.Equal("Origin wasn't installed", fresh.Title);
    }

    [Fact]
    public void AFullDriveIsNamedAsSuch()
    {
        // ERROR_DISK_FULL (0x70) and ERROR_HANDLE_DISK_FULL (0x27) as HRESULTs, also wrapped.
        var full = new IOException("There is not enough space on the disk.", unchecked((int)0x80070070));
        Assert.Equal(InstallFailure.DiskFull, InstallFailure.Describe(full, updating: false)!.Body);
        Assert.True(InstallFailure.IsDiskFull(new InvalidOperationException("wrap", new IOException("x", unchecked((int)0x80070027)))));
        // A locked file is not a full drive, and the advice is not "turn your antivirus off".
        var locked = InstallFailure.Describe(new IOException("in use", unchecked((int)0x80070020)), updating: false)!;
        Assert.Equal(InstallFailure.FileInUse, locked.Body);
        Assert.DoesNotContain("pause", locked.Body, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void DownloadProblemsKeepTheirOwnWords()
    {
        var f = InstallFailure.Describe(new DownloadException(DownloadException.Dropped), updating: true)!;
        Assert.Equal("Couldn't download Origin", f.Title);
        Assert.Equal(DownloadException.Dropped, f.Body);
        Assert.Equal("Something went wrong", InstallFailure.Describe(new InvalidOperationException(), updating: false)!.Title);
        Assert.False(InstallFailure.Describe(new InvalidDataException(), updating: false)!.CanRetry);
    }

    [Fact]
    public void UnpackedFilesThatVanishAreFound()
    {
        var tree = TestKit.MakeLauncherTree();
        var zip = LauncherZip.Open(TestKit.Zip(tree));
        Assert.Empty(Installer.MissingFiles(zip.Files, tree));

        File.Delete(Path.Combine(tree, InstallLocation.LauncherExe)); // quarantined
        File.WriteAllBytes(Path.Combine(tree, "Bundled", "OriginClient", "originclient-1.21.1.jar"), new byte[3]); // emptied
        var missing = Installer.MissingFiles(zip.Files, tree);
        Assert.Contains(InstallLocation.LauncherExe, missing);
        Assert.Contains("Bundled/OriginClient/originclient-1.21.1.jar", missing);
        Assert.Equal(2, missing.Count);
    }
}
