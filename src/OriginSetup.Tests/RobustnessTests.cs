using System;
using System.IO;
using System.Threading;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// The 2026-11-12 installer pass: one Setup at a time, failures worded for what really happened, and a launcher that
// antivirus emptied out never reported as installed.
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
