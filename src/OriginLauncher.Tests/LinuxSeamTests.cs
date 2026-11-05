#if ORIGIN_LINUX
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Install;
using OriginLauncher.App.Core.Performance;
using OriginLauncher.App.Core.Profiles;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The Linux side of each per-OS seam, on real Linux (linux-launcher.yml: dotnet test
/// -p:OriginPlatform=Linux --filter LinuxSeamTests). Built only in that mode. Everything runs in
/// temp folders with a throwaway key file; the keyring is switched off here (the self-test covers
/// it against a real Secret Service), so no test touches the runner's real keyring or data.
/// </summary>
public sealed class LinuxSeamTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-linuxseam-" + Guid.NewGuid().ToString("N"));
    private readonly string[] _realMachineIdFiles = LinuxKeyStore.MachineIdFiles;

    public LinuxSeamTests()
    {
        Directory.CreateDirectory(_dir);
        LinuxKeyStore.KeyringDisabled = true;
        LinuxKeyStore.KeyFilePath = Path.Combine(_dir, "accounts", "account.key");
        LinuxKeyStore.ForgetCached();
    }

    public void Dispose()
    {
        LinuxKeyStore.KeyringDisabled = false;
        LinuxKeyStore.KeyFilePath = null!;
        LinuxKeyStore.MachineIdFiles = _realMachineIdFiles;
        LinuxKeyStore.ForgetCached();
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { }
    }

    private static readonly byte[] Plain = Encoding.UTF8.GetBytes("{\"refresh_token\":\"test-not-a-real-token\"}");

    [Fact]
    public void Account_tokens_seal_with_a_0600_key_file_and_never_in_plaintext()
    {
        var blob = AccountStore.ProtectBytes(Plain);

        Assert.Equal(LinuxKeyStore.FileKind, blob[0]);
        Assert.True(blob.AsSpan().IndexOf("test-not-a-real"u8) < 0);
        Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(LinuxKeyStore.KeyFilePath));
        LinuxKeyStore.ForgetCached();
        Assert.Equal(Plain, AccountStore.UnprotectBytes(blob));
    }

    [Fact]
    public void A_changed_byte_or_another_machine_id_means_sign_in_again()
    {
        var blob = AccountStore.ProtectBytes(Plain);
        var tampered = (byte[])blob.Clone();
        tampered[^1] ^= 1;
        Assert.ThrowsAny<CryptographicException>(() => AccountStore.UnprotectBytes(tampered));

        // The same key file on a machine with another machine-id (the data folder copied elsewhere).
        var otherId = Path.Combine(_dir, "machine-id");
        File.WriteAllText(otherId, "0123456789abcdef0123456789abcdef\n");
        LinuxKeyStore.MachineIdFiles = [otherId];
        LinuxKeyStore.ForgetCached();
        Assert.ThrowsAny<CryptographicException>(() => AccountStore.UnprotectBytes(blob));
    }

    [Fact]
    public void A_missing_key_file_opens_nothing_and_unsealing_never_creates_one()
    {
        var blob = AccountStore.ProtectBytes(Plain);
        File.Delete(LinuxKeyStore.KeyFilePath);
        LinuxKeyStore.ForgetCached();

        Assert.ThrowsAny<CryptographicException>(() => AccountStore.UnprotectBytes(blob));
        Assert.False(File.Exists(LinuxKeyStore.KeyFilePath));
    }

    [Fact]
    public void A_loosened_key_file_is_closed_again_before_use()
    {
        AccountStore.ProtectBytes(Plain);
        File.SetUnixFileMode(LinuxKeyStore.KeyFilePath, (UnixFileMode)0b110_100_100); // 0644
        LinuxKeyStore.ForgetCached();

        AccountStore.ProtectBytes(Plain);
        Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(LinuxKeyStore.KeyFilePath));
    }

    [Fact]
    public void A_keyring_blob_with_no_keyring_is_sign_in_again()
    {
        var blob = AccountStore.ProtectBytes(Plain);
        blob[0] = LinuxKeyStore.KeyringKind;
        Assert.ThrowsAny<CryptographicException>(() => AccountStore.UnprotectBytes(blob));
        // No keyring at all is not a LOCKED keyring: Play says sign in again here, never "unlock it".
        Assert.IsNotType<KeyStoreLockedException>(Record.Exception(() => AccountStore.UnprotectBytes(blob)));
    }

    [Fact]
    public void The_launch_mods_folder_hard_links_the_jar()
    {
        var source = Path.Combine(_dir, "source.jar");
        File.WriteAllText(source, "one");
        var mods = Path.Combine(_dir, "mods");
        LaunchModFolder.Sync(mods, new Dictionary<string, string> { ["mod.jar"] = source }, linkable: _ => true);
        File.AppendAllText(source, "two");
        Assert.Equal("onetwo", File.ReadAllText(Path.Combine(mods, "mod.jar")));
    }

    [Fact]
    public void Xdg_directories_follow_the_spec()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var real = Environment.GetEnvironmentVariable("XDG_TEST_DIR");
        try
        {
            Environment.SetEnvironmentVariable("XDG_TEST_DIR", null);
            Assert.Equal(Path.Combine(home, ".local", "share"), OriginPaths.XdgDir("XDG_TEST_DIR", ".local", "share"));
            Environment.SetEnvironmentVariable("XDG_TEST_DIR", "relative/dir"); // the spec: a relative value is ignored
            Assert.Equal(Path.Combine(home, ".local", "share"), OriginPaths.XdgDir("XDG_TEST_DIR", ".local", "share"));
            Environment.SetEnvironmentVariable("XDG_TEST_DIR", _dir);
            Assert.Equal(_dir, OriginPaths.XdgDir("XDG_TEST_DIR", ".local", "share"));
        }
        finally
        {
            Environment.SetEnvironmentVariable("XDG_TEST_DIR", real);
        }
        Assert.EndsWith("/OriginLauncher", OriginPaths.DefaultRoot, StringComparison.Ordinal);
    }

    [Fact]
    public void Start_at_login_writes_and_removes_an_autostart_entry()
    {
        var real = InstallRegistry.AutostartDir;
        InstallRegistry.AutostartDir = Path.Combine(_dir, "autostart");
        try
        {
            Assert.False(InstallRegistry.IsStartWithWindows());
            InstallRegistry.SetStartWithWindows(true, "/home/p/Origin Launcher/OriginLauncher");
            Assert.True(InstallRegistry.IsStartWithWindows());
            var entry = File.ReadAllText(InstallRegistry.DesktopFilePath);
            Assert.StartsWith("[Desktop Entry]\n", entry, StringComparison.Ordinal);
            Assert.Contains("\nExec=\"/home/p/Origin Launcher/OriginLauncher\"\n", entry, StringComparison.Ordinal);
            InstallRegistry.SetStartWithWindows(false, "");
            Assert.False(File.Exists(InstallRegistry.DesktopFilePath));
        }
        finally
        {
            InstallRegistry.AutostartDir = real;
        }
    }

    // UPG-5: the autostart entry follows the launcher when it is moved or renamed; off stays off.
    [Fact]
    public void Start_at_login_follows_the_launcher_when_it_moves()
    {
        var real = InstallRegistry.AutostartDir;
        InstallRegistry.AutostartDir = Path.Combine(_dir, "autostart-move");
        try
        {
            InstallRegistry.RefreshStartAtLogin("/home/p/Apps/OriginLauncher");
            Assert.False(InstallRegistry.IsStartWithWindows());

            InstallRegistry.SetStartWithWindows(true, "/home/p/Downloads/OriginLauncher");
            InstallRegistry.RefreshStartAtLogin("/home/p/Apps/OriginLauncher");
            var entry = File.ReadAllText(InstallRegistry.DesktopFilePath);
            Assert.Contains("\nExec=\"/home/p/Apps/OriginLauncher\"\n", entry, StringComparison.Ordinal);
            Assert.DoesNotContain("Downloads", entry, StringComparison.Ordinal);
        }
        finally
        {
            InstallRegistry.AutostartDir = real;
        }
    }

    [Fact]
    public void An_appimage_counts_only_when_this_process_runs_from_its_own_mount()
    {
        var image = Path.Combine(_dir, "Origin.AppImage");
        File.WriteAllText(image, "");
        var mount = Path.Combine(_dir, ".mount_ab12");
        var ours = Path.Combine(mount, "usr", "lib", "origin-launcher") + "/";

        Assert.Equal(image, LinuxInstall.ResolveAppImage(image, mount, ours));
        Assert.Equal(image, LinuxInstall.ResolveAppImage(image, mount + "/", ours.TrimEnd('/')));
        // A tar.gz or package build started from a terminal inside another AppImage (Cursor, VS Code)
        // inherits that app's APPIMAGE and APPDIR: never ours, so never swapped over or put in the login entry.
        Assert.Null(LinuxInstall.ResolveAppImage(image, Path.Combine(_dir, ".mount_cursor"), ours));
        Assert.Null(LinuxInstall.ResolveAppImage(image, mount, "/opt/origin-launcher/"));
        Assert.Null(LinuxInstall.ResolveAppImage(image, null, ours));
        Assert.Null(LinuxInstall.ResolveAppImage(image, "relative/mount", ours));
        Assert.Null(LinuxInstall.ResolveAppImage(Path.Combine(_dir, "gone.AppImage"), mount, ours));
        Assert.Null(LinuxInstall.ResolveAppImage(null, mount, ours));
    }

    [Fact]
    public void Login_starts_the_package_launcher_for_a_package_or_its_updated_copy()
    {
        var package = Path.Combine(_dir, "opt", "origin-launcher");
        Directory.CreateDirectory(package);
        File.WriteAllText(Path.Combine(package, LinuxInstall.PackageMarkerName), "deb\n");
        var userCopy = Path.Combine(_dir, "data", "OriginLauncher", "app");
        var tarFolder = Path.Combine(_dir, "Origin Launcher");
        static bool Present(string _) => true;
        static bool Missing(string _) => false;

        // A package build, or the per-user copy its self-update installed: /usr/bin/origin-launcher picks the newer one.
        Assert.Equal(LinuxInstall.PackageLauncher, LinuxInstall.LoginTarget(Path.Combine(package, "OriginLauncher"), null, userCopy, Present));
        Assert.Equal(LinuxInstall.PackageLauncher, LinuxInstall.LoginTarget(Path.Combine(userCopy, "OriginLauncher"), null, userCopy + "/", Present));
        // The package was removed but its copy stayed: start the copy itself.
        Assert.Equal(Path.Combine(userCopy, "OriginLauncher"), LinuxInstall.LoginTarget(Path.Combine(userCopy, "OriginLauncher"), null, userCopy, Missing));
        // A tar.gz folder starts itself; an AppImage starts the image file.
        Assert.Equal(Path.Combine(tarFolder, "OriginLauncher"), LinuxInstall.LoginTarget(Path.Combine(tarFolder, "OriginLauncher"), null, userCopy, Present));
        Assert.Equal("/home/p/Origin.AppImage", LinuxInstall.LoginTarget(Path.Combine(package, "OriginLauncher"), "/home/p/Origin.AppImage", userCopy, Present));
    }

    [Theory]
    [InlineData("/opt/origin/OriginLauncher", "\"/opt/origin/OriginLauncher\"")]
    [InlineData("/home/a b/100%/x", "\"/home/a b/100%%/x\"")]
    [InlineData("/home/$USER/`x`", "\"/home/\\\\$USER/\\\\`x\\\\`\"")]
    [InlineData("/home/q\"uote", "\"/home/q\\\\\"uote\"")]
    [InlineData("/home/back\\slash", "\"/home/back\\\\\\\\slash\"")]
    public void Exec_lines_quote_like_the_desktop_entry_spec(string path, string expected) =>
        Assert.Equal(expected, InstallRegistry.ExecQuote(path));

    [Fact]
    public void Ram_comes_from_proc_meminfo()
    {
        var mb = SystemInfo.GetTotalPhysicalMemoryMb();
        var kb = long.Parse(SystemInfo.ProcField("/proc/meminfo", "MemTotal")!.Split(' ')[0]);
        Assert.Equal((int)(kb / 1024), mb);
    }

    // ARM servers and VMs (GitHub's arm64 runners) have no "model name" and no device tree.
    [Theory]
    [InlineData("0x41", "0xd0c", "Arm Neoverse-N1")]
    [InlineData("0x41", "0xD4F", "Arm Neoverse-V2")]
    [InlineData("0x41", "0xfff", "Arm CPU")]
    [InlineData("0x61", "0x022", "ARM CPU")]
    [InlineData(null, null, null)]
    public void Arm_cores_are_named_from_their_ids(string? implementer, string? part, string? expected) =>
        Assert.Equal(expected, SystemInfo.ArmCoreName(implementer, part));

    [Fact]
    public void The_gpu_counted_is_the_display_one_then_discrete_then_vram()
    {
        // A hybrid laptop: Intel drives the display, the RTX only runs offloaded launches.
        Assert.Equal(("Intel Graphics", (long?)null, (bool?)true), HardwareProbe.PickGpu([
            new HardwareProbe.DrmCard(0x8086, 0x46a6, BootVga: true, null, null),
            new HardwareProbe.DrmCard(0x10de, 0x2520, BootVga: false, null, "NVIDIA GeForce RTX 3060 Laptop GPU"),
        ]));
        // A desktop with the NVIDIA card driving the display.
        Assert.Equal(("NVIDIA GeForce RTX 4070", (long?)null, (bool?)false), HardwareProbe.PickGpu([
            new HardwareProbe.DrmCard(0x10de, 0x2786, BootVga: true, null, "NVIDIA GeForce RTX 4070"),
        ]));
        // Nothing flagged: discrete beats integrated; an AMD APU's 512 MB carve-out is integrated.
        Assert.Equal(("AMD Radeon GPU", (long?)16368, (bool?)false), HardwareProbe.PickGpu([
            new HardwareProbe.DrmCard(0x1002, 0x1638, BootVga: false, 512L * 1024 * 1024, null),
            new HardwareProbe.DrmCard(0x1002, 0x744c, BootVga: false, 16368L * 1024 * 1024, null),
        ]));
        Assert.Equal(("Intel Arc GPU", (long?)null, (bool?)false), HardwareProbe.PickGpu([new HardwareProbe.DrmCard(0x8086, 0x56a0, true, null, null)]));
        Assert.Equal(((string?)null, (long?)null, (bool?)null), HardwareProbe.PickGpu([]));
    }

    [Fact]
    public void Gpu_cards_are_read_from_a_sysfs_tree()
    {
        var drm = Path.Combine(_dir, "drm");
        var device = Path.Combine(_dir, "pci", "0000:03:00.0");
        Directory.CreateDirectory(device);
        File.WriteAllText(Path.Combine(device, "vendor"), "0x1002\n");
        File.WriteAllText(Path.Combine(device, "device"), "0x744c\n");
        File.WriteAllText(Path.Combine(device, "boot_vga"), "1\n");
        File.WriteAllText(Path.Combine(device, "mem_info_vram_total"), (16L * 1024 * 1024 * 1024).ToString() + "\n");
        Directory.CreateDirectory(Path.Combine(drm, "card1"));
        Directory.CreateSymbolicLink(Path.Combine(drm, "card1", "device"), device);
        Directory.CreateDirectory(Path.Combine(drm, "card1-DP-1")); // a connector, not a card

        var card = Assert.Single(HardwareProbe.ReadCards(drm));
        Assert.Equal(new HardwareProbe.DrmCard(0x1002, 0x744c, true, 16L * 1024 * 1024 * 1024, null), card);
    }

    // A dismissed keyring unlock looks like "no key" to `secret-tool lookup`; `search` (no unlock) still lists the
    // locked item, and a listed item is never overwritten with a new key.
    [Fact]
    public void A_listed_keyring_item_is_seen_even_without_its_secret()
    {
        const string locked = "[/org/freedesktop/secrets/collection/login/7]\nlabel = Origin Launcher account key\n"
                              + "created = 2026-11-01 10:00:00\nmodified = 2026-11-01 10:00:00\nschema = org.freedesktop.Secret.Generic\n"
                              + "attribute.account = account-key\nattribute.service = org.originclient.launcher\n";
        Assert.True(LinuxKeyStore.SearchListsItem(locked));
        Assert.True(LinuxKeyStore.SearchListsItem("attribute.service = org.originclient.launcher"));
        Assert.False(LinuxKeyStore.SearchListsItem(""));
        Assert.False(LinuxKeyStore.SearchListsItem("\n"));
    }

    [Fact]
    public void A_hybrid_laptop_starts_the_game_on_its_discrete_gpu()
    {
        var intel = new HardwareProbe.DrmCard(0x8086, 0x9a49, true, null, null);
        var nvidia = new HardwareProbe.DrmCard(0x10de, 0x2520, false, null, "NVIDIA GeForce RTX 3060 Laptop GPU");
        var radeon = new HardwareProbe.DrmCard(0x1002, 0x73df, false, 12L * 1024 * 1024 * 1024, null);

        // NVIDIA's own driver: prime-run's variables; nouveau (driver not loaded) and AMD: Mesa's DRI_PRIME.
        Assert.Equal(new[] { ("__NV_PRIME_RENDER_OFFLOAD", "1"), ("__GLX_VENDOR_LIBRARY_NAME", "nvidia"), ("__VK_LAYER_NV_optimus", "NVIDIA_only") },
            GpuPreference.OffloadEnvironment([intel, nvidia], nvidiaDriverLoaded: true));
        Assert.Equal(new[] { ("DRI_PRIME", "1") }, GpuPreference.OffloadEnvironment([intel, nvidia], nvidiaDriverLoaded: false));
        Assert.Equal(new[] { ("DRI_PRIME", "1") }, GpuPreference.OffloadEnvironment([intel, radeon], nvidiaDriverLoaded: false));
    }

    [Fact]
    public void A_one_gpu_machine_or_a_discrete_display_gpu_is_left_alone()
    {
        var intel = new HardwareProbe.DrmCard(0x8086, 0x9a49, true, null, null);
        var nvidiaDisplay = new HardwareProbe.DrmCard(0x10de, 0x2684, true, null, "NVIDIA GeForce RTX 4090");
        var apu = new HardwareProbe.DrmCard(0x1002, 0x164e, false, 512L * 1024 * 1024, null);
        Assert.Empty(GpuPreference.OffloadEnvironment([intel], nvidiaDriverLoaded: false));
        Assert.Empty(GpuPreference.OffloadEnvironment([nvidiaDisplay, apu], nvidiaDriverLoaded: true)); // desktop: the dGPU already drives the screen
        Assert.Empty(GpuPreference.OffloadEnvironment([intel, apu], nvidiaDriverLoaded: false)); // no discrete card beside it
        Assert.Empty(GpuPreference.OffloadEnvironment([], nvidiaDriverLoaded: false));
    }

    [Fact]
    public void Mojang_platform_names_are_linux()
    {
        Assert.Equal(RuntimeInformation.OSArchitecture == Architecture.Arm64 ? "linux-arm64" : "linux", MojangMetadata.RuntimePlatform);
    }
}
#endif
