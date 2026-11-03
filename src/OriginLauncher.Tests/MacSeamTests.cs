#if ORIGIN_MAC
using System.Diagnostics;
using System.Formats.Tar;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Install;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Runtime;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The Mac side of each per-OS seam, on a real Mac (macos-15 CI: dotnet test -p:OriginPlatform=Mac
/// --filter MacSeamTests). Built only in that mode. Everything runs against throwaway Keychain items
/// and temp folders, never the player's real key or data folder.
/// </summary>
public sealed class MacSeamTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-macseam-" + Guid.NewGuid().ToString("N"));

    public MacSeamTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { }
    }

    [Fact]
    public void Account_tokens_seal_with_a_keychain_key_and_a_missing_key_means_sign_in_again()
    {
        var realAccount = MacKeychain.Account;
        MacKeychain.Account = "test-" + Guid.NewGuid().ToString("N");
        try
        {
            Assert.Null(MacKeychain.TryReadKey());

            var plain = Encoding.UTF8.GetBytes("{\"refresh\":\"secret-token-value\"}");
            var blob = AccountStore.PlatformProtect(plain);                  // creates the key on first use
            Assert.Equal(1, blob[0]);
            Assert.True(blob.AsSpan().IndexOf(plain.AsSpan(2, 12)) < 0);      // no plaintext in the blob
            Assert.NotEqual(blob, AccountStore.PlatformProtect(plain));       // a fresh nonce every time

            MacKeychain.ForgetCached();                                        // the next read really asks the Keychain
            Assert.Equal(32, MacKeychain.TryReadKey()!.Length);
            MacKeychain.ForgetCached();
            Assert.Equal(plain, AccountStore.PlatformUnprotect(blob));

            var tampered = (byte[])blob.Clone();
            tampered[^1] ^= 1;
            Assert.ThrowsAny<CryptographicException>(() => AccountStore.PlatformUnprotect(tampered));
            Assert.ThrowsAny<CryptographicException>(() => AccountStore.PlatformUnprotect(plain)); // not a blob at all

            MacKeychain.DeleteForTests();
            Assert.ThrowsAny<CryptographicException>(() => AccountStore.PlatformUnprotect(blob));
            Assert.Null(MacKeychain.TryReadKey());                             // unsealing never created a key
        }
        finally
        {
            MacKeychain.DeleteForTests();
            MacKeychain.Account = realAccount;
            MacKeychain.ForgetCached();
        }
    }

    [Fact]
    public void Hard_links_share_the_file()
    {
        var src = Path.Combine(_dir, "mod.jar");
        var link = Path.Combine(_dir, "linked.jar");
        File.WriteAllText(src, "one");

        Assert.True(LaunchModFolder.TryHardLinkForTests(link, src));
        File.AppendAllText(src, "two");
        Assert.Equal("onetwo", File.ReadAllText(link));
        Assert.False(LaunchModFolder.TryHardLinkForTests(link, src));         // never replaces an existing name
    }

    [Fact]
    public void Data_lives_in_application_support()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        Assert.Equal(Path.Combine(home, "Library", "Application Support", "OriginLauncher"), OriginPaths.DefaultRoot);
        Assert.Null(OriginPaths.MacBundleResources);                           // the test host is not an .app
    }

    [Fact]
    public void Mojang_files_are_the_mac_ones_for_this_cpu()
    {
        var appleSilicon = RuntimeInformation.OSArchitecture == Architecture.Arm64
                           || SystemInfo.SysctlInt64("sysctl.proc_translated") == 1;
        Assert.Equal(appleSilicon ? "mac-os-arm64" : "mac-os", MojangMetadata.RuntimePlatform);
    }

    [Fact]
    public void Sysctl_reads_memory_and_cpu()
    {
        Assert.True(SystemInfo.GetTotalPhysicalMemoryMb() >= 2048);
        Assert.False(string.IsNullOrWhiteSpace(SystemInfo.SysctlString("machdep.cpu.brand_string")));
        GpuPreference.PreferHighPerformanceGpu("/usr/bin/java");               // a no-op on a Mac, must not throw
    }

    [Fact]
    public void Start_at_login_is_a_valid_launch_agent()
    {
        var realDir = InstallRegistry.LaunchAgentsDir;
        InstallRegistry.LaunchAgentsDir = Path.Combine(_dir, "LaunchAgents");
        try
        {
            Assert.False(InstallRegistry.IsStartWithWindows());
            InstallRegistry.SetStartWithWindows(true, "/Applications/Origin Launcher & Co.app/Contents/MacOS/OriginLauncher");
            Assert.True(InstallRegistry.IsStartWithWindows());
            var plist = File.ReadAllText(InstallRegistry.AgentPlistPath);
            Assert.Contains("<key>RunAtLoad</key>", plist);
            Assert.Contains("&amp;", plist);                                   // XML-escaped, so a path can't break the file
            Assert.Equal(0, Run("/usr/bin/plutil", "-lint", InstallRegistry.AgentPlistPath));

            InstallRegistry.SetStartWithWindows(false, "");
            Assert.False(File.Exists(InstallRegistry.AgentPlistPath));
        }
        finally
        {
            InstallRegistry.LaunchAgentsDir = realDir;
        }
    }

    [Fact]
    public void Java8_unpacks_from_a_tar_gz_and_stays_executable()
    {
        var tree = Path.Combine(_dir, "tree", "jdk8u504-b01-jre", "Contents", "Home", "bin");
        Directory.CreateDirectory(tree);
        var java = Path.Combine(tree, "java");
        File.WriteAllText(java, "#!/bin/sh\necho fake\n");
        File.SetUnixFileMode(java, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        var archive = Path.Combine(_dir, "java.tar.gz");
        using (var file = File.Create(archive))
        using (var gzip = new GZipStream(file, CompressionLevel.Fastest))
            TarFile.CreateFromDirectory(Path.Combine(_dir, "tree"), gzip, includeBaseDirectory: false);

        var dest = Path.Combine(_dir, "out");
        JavaRuntimeProvisioner.ExtractArchive(archive, dest);
        var unpacked = JavaRuntimeProvisioner.JavaIn(Path.Combine(dest, "jdk8u504-b01-jre"));
        Assert.True(File.Exists(unpacked));
        Assert.True(File.GetUnixFileMode(unpacked).HasFlag(UnixFileMode.UserExecute));
        Assert.Contains("_mac_", TemurinJava8.Pin.Url);
        Assert.EndsWith(".tar.gz", TemurinJava8.Pin.Url);
    }

    private static int Run(string tool, params string[] args)
    {
        var psi = new ProcessStartInfo(tool) { RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi)!;
        p.WaitForExit(15000);
        return p.ExitCode;
    }
}
#endif
