using System.Diagnostics;
using System.Formats.Tar;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Install;
using OriginLauncher.App.Core.Performance;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Runtime;
using OriginLauncher.App.Core.Skins;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.Linux;

/// <summary>
/// `--self-test`: prints where the Linux launcher keeps its data, then runs each Linux seam once
/// and prints PASS/FAIL per check. Exit 0 only when every check passed. Safe to run on any Linux
/// machine: the real data folder is only printed, never touched (every check runs in a temp folder
/// with OriginPaths pointed at it), and the keyring check uses a throwaway item it deletes again.
/// The keyring check is skipped when no Secret Service answers, unless `--require-keyring` (CI
/// starts a real gnome-keyring for it). `--network` adds the checks that download: the pinned
/// Java 8 for 1.8.9 (about 41 MB) and Mojang's 1.8.9 and 1.21.1 file lists.
/// </summary>
internal static class SelfTest
{
    private static int _failed;

    public static async Task<int> RunAsync(bool network, bool requireKeyring)
    {
        Console.WriteLine($"Origin Launcher self-test: {RuntimeInformation.OSDescription}, process {RuntimeInformation.ProcessArchitecture}, machine {RuntimeInformation.OSArchitecture}");
        Console.WriteLine($"  data folder      {OriginPaths.DefaultRoot}");
        Console.WriteLine($"  bundled files    {AppContext.BaseDirectory}");
        Console.WriteLine($"  update asset     {UpdateService.ReleaseAssetName}");
        // CI's package-install jobs read this line: "package" from /opt via the deb/rpm, "appimage", or "folder".
        Console.WriteLine($"  install kind     {(UpdateService.AppImagePath != null ? "appimage" : UpdateService.PackageInstall ? "package" : "folder")}");
        Console.WriteLine($"  Mojang runtime   {MojangMetadata.RuntimePlatform}");
        // Which OpenSSL HTTPS uses: the system's, or the AppImage/tar.gz's own when the distro has no OpenSSL 3 (LINUX-1).
        Console.WriteLine($"  openssl          {OriginLauncher.App.Core.Net.LinuxOpenSsl.State}");
        // A copy that can't replace itself sees updates but only tells the player how to get them (LINUX-7).
        Console.WriteLine($"  update blocked   {OriginLauncher.App.Core.Updates.LinuxUpdateWords.Blocked(UpdateService.NotUpdatableReason, UpdateService.AppImagePath != null) ?? "no"}");
        // The optional system pieces this computer lacks, as Settings shows them (LINUX-14).
        var missing = OriginLauncher.App.Core.Launch.LinuxOptionalPieces.MissingHere();
        Console.WriteLine($"  optional pieces  {(missing.Count == 0 ? "all present" : "missing " + string.Join(", ", missing.Select(p => p.Name)))}");
        if (OriginLauncher.App.Core.Launch.LinuxOptionalPieces.Note(missing, OriginLauncher.App.Core.Launch.LinuxPackages.ThisComputer) is { } note)
            Console.WriteLine($"  settings note    {note}");

        var scratch = Path.Combine(Path.GetTempPath(), "origin-selftest-" + Guid.NewGuid().ToString("N"));
        OriginPaths.UseScratchRoot(scratch);
        try
        {
            Check("paths", Paths);
            Check("account key file", KeyFile);
            Check("keyring", () => Keyring(requireKeyring));
            Check("hard link", HardLink);
            Check("procfs + sysfs", Hardware);
            Check("gpu preference", () => { GpuPreference.PreferHighPerformanceGpu("/usr/bin/java"); return "no-op on Linux"; });
            Check("start at login", StartAtLogin);
            Check("prewarm", Prewarm);
            Check("skin decode", SkinDecode);
            Check("java 8 layout", JavaLayout);
            Check("update swap (tar.gz folder)", UpdateSwapFolder);
            Check("update swap (AppImage)", UpdateSwapAppImage);
            Check("update swap (deb/rpm, per-user copy)", UpdateSwapPackage);
            if (network)
            {
                await CheckAsync("java 8 download", JavaDownload).ConfigureAwait(false);
                await CheckAsync("mojang 1.8.9 files", Mojang189).ConfigureAwait(false);
                await CheckAsync("mojang 1.21.1 files", Mojang1211).ConfigureAwait(false);
            }
        }
        finally
        {
            try { Directory.Delete(scratch, recursive: true); } catch (IOException) { }
        }

        Console.WriteLine(_failed == 0 ? "SELF-TEST PASSED" : $"SELF-TEST FAILED ({_failed} check(s))");
        return _failed == 0 ? 0 : 1;
    }

    private static void Check(string name, Func<string> body)
    {
        try { Console.WriteLine($"PASS {name}: {body()}"); }
        catch (SkipException s) { Console.WriteLine($"SKIP {name}: {s.Message}"); }
        catch (Exception e) { _failed++; Console.WriteLine($"FAIL {name}: {e.GetType().Name}: {e.Message}"); }
    }

    private static async Task CheckAsync(string name, Func<Task<string>> body)
    {
        try { Console.WriteLine($"PASS {name}: {await body().ConfigureAwait(false)}"); }
        catch (Exception e) { _failed++; Console.WriteLine($"FAIL {name}: {e.GetType().Name}: {e.Message}"); }
    }

    private sealed class SkipException(string message) : Exception(message);

    private static void Require(bool ok, string what)
    {
        if (!ok) throw new InvalidOperationException(what);
    }

    private static string Paths()
    {
        var xdg = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
        var expectedBase = !string.IsNullOrEmpty(xdg) && Path.IsPathRooted(xdg)
            ? xdg
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".local", "share");
        Require(OriginPaths.DefaultRoot == Path.Combine(expectedBase, "OriginLauncher"), "data folder is not $XDG_DATA_HOME/OriginLauncher");
        Require(OriginPaths.Root.StartsWith(Path.GetTempPath(), StringComparison.Ordinal), "self-test is not on its scratch folder");
        Require(OriginPaths.Instances.StartsWith(OriginPaths.Root, StringComparison.Ordinal), "instances outside the data folder");
        Require(LinuxKeyStore.KeyFilePath.StartsWith(OriginPaths.Root, StringComparison.Ordinal), "key file outside the data folder");
        return $"instances {OriginPaths.Instances}, bundled jars {OriginPaths.BundledOriginClientDir}";
    }

    private static readonly byte[] Plain = Encoding.UTF8.GetBytes("{\"refresh_token\":\"self-test-not-a-real-token\"}");

    // The fallback a machine without a keyring uses: AccountStore's own Protect/Unprotect on the 0600 key file.
    private static string KeyFile()
    {
        LinuxKeyStore.KeyringDisabled = true;
        LinuxKeyStore.ForgetCached();
        try
        {
            var blob = AccountStore.ProtectBytes(Plain);
            Require(blob[0] == LinuxKeyStore.FileKind, "not sealed with the key file");
            Require(blob.AsSpan().IndexOf("self-test-not"u8) < 0, "plaintext visible in the sealed blob");
            var mode = File.GetUnixFileMode(LinuxKeyStore.KeyFilePath);
            Require(mode == (UnixFileMode.UserRead | UnixFileMode.UserWrite), $"key file mode is {Convert.ToString((int)mode, 8)}, not 600");
            LinuxKeyStore.ForgetCached();
            Require(AccountStore.UnprotectBytes(blob).AsSpan().SequenceEqual(Plain), "round trip changed the bytes");

            var tampered = (byte[])blob.Clone();
            tampered[^1] ^= 1;
            Require(Throws(() => AccountStore.UnprotectBytes(tampered)), "a changed byte was accepted");

            File.Delete(LinuxKeyStore.KeyFilePath);
            LinuxKeyStore.ForgetCached();
            Require(Throws(() => AccountStore.UnprotectBytes(blob)), "opened with no key file");
            Require(!File.Exists(LinuxKeyStore.KeyFilePath), "unsealing created a key file");
            return $"AES-256-GCM blob of {blob.Length} bytes, key file 0600 bound to /etc/machine-id, missing key = sign in again";
        }
        finally
        {
            LinuxKeyStore.KeyringDisabled = false;
            LinuxKeyStore.ForgetCached();
        }
    }

    // The real wiring against a real Secret Service, on a throwaway item.
    private static string Keyring(bool required)
    {
        var realAccount = LinuxKeyStore.Account;
        LinuxKeyStore.Account = "selftest-" + Guid.NewGuid().ToString("N");
        LinuxKeyStore.ForgetCached();
        try
        {
            if (!LinuxKeyStore.KeyringAvailableForTests())
            {
                if (required) throw new InvalidOperationException("no Secret Service answered (secret-tool missing, no session bus, or keyring locked)");
                throw new SkipException("no Secret Service on this machine: accounts use the key file");
            }
            Require(LinuxKeyStore.TryReadKey(LinuxKeyStore.KeyringKind) == null, "throwaway item already exists");
            var blob = AccountStore.ProtectBytes(Plain);
            Require(blob[0] == LinuxKeyStore.KeyringKind, "keyring answered but the blob was sealed with the key file: " + LinuxKeyStore.LastKeyringError);
            Require(blob.AsSpan().IndexOf("self-test-not"u8) < 0, "plaintext visible in the sealed blob");
            LinuxKeyStore.ForgetCached();
            Require(AccountStore.UnprotectBytes(blob).AsSpan().SequenceEqual(Plain), "round trip changed the bytes");

            LinuxKeyStore.DeleteKeyringItemForTests();
            LinuxKeyStore.ForgetCached();
            Require(Throws(() => AccountStore.UnprotectBytes(blob)), "opened with no key in the keyring");
            Require(LinuxKeyStore.TryReadKey(LinuxKeyStore.KeyringKind) == null, "unsealing created a key");
            return $"AES-256-GCM blob of {blob.Length} bytes, key read back from the Secret Service via secret-tool, missing key = sign in again";
        }
        finally
        {
            try { LinuxKeyStore.DeleteKeyringItemForTests(); } catch (CryptographicException) { }
            LinuxKeyStore.Account = realAccount;
            LinuxKeyStore.ForgetCached();
        }
    }

    private static bool Throws(Action action)
    {
        try { action(); return false; }
        catch (CryptographicException) { return true; }
    }

    private static string HardLink()
    {
        var dir = Path.Combine(OriginPaths.Root, "linktest");
        Directory.CreateDirectory(dir);
        var source = Path.Combine(dir, "source.jar");
        File.WriteAllText(source, "one");
        var mods = Path.Combine(dir, "mods");
        LaunchModFolder.Sync(mods, new Dictionary<string, string> { ["mod.jar"] = source }, linkable: _ => true);
        File.AppendAllText(source, "two");
        Require(File.ReadAllText(Path.Combine(mods, "mod.jar")) == "onetwo", "the launch folder got a copy, not a hard link");
        return "libc link(): the launch mods folder shares the jar, no copy";
    }

    private static string Hardware()
    {
        var ram = SystemInfo.GetTotalPhysicalMemoryMb();
        Require(ram >= 1024 && ram != 8192, $"implausible RAM {ram} MB (8192 is the fallback for an unreadable /proc/meminfo)");
        var cpu = SystemInfo.CpuName();
        Require(!string.IsNullOrWhiteSpace(cpu), "no CPU name in /proc/cpuinfo or the device tree");
        var cards = HardwareProbe.ReadCards("/sys/class/drm");
        return $"{ram} MB, {cpu}, {cards.Count} DRM card(s); {HardwareProbe.Describe(HardwareProbe.Current)}";
    }

    private static string StartAtLogin()
    {
        var realDir = InstallRegistry.AutostartDir;
        InstallRegistry.AutostartDir = Path.Combine(OriginPaths.Root, "autostart");
        try
        {
            Require(!InstallRegistry.IsStartWithWindows(), "already on");
            InstallRegistry.SetStartWithWindows(true, "/home/player/Origin Launcher/OriginLauncher");
            Require(InstallRegistry.IsStartWithWindows(), "turning it on wrote nothing");
            var entry = File.ReadAllText(InstallRegistry.DesktopFilePath);
            // As an AppImage the entry points at the AppImage file itself, so the expected line comes
            // from the same rule the launcher uses.
            var target = LinuxInstall.LoginTarget("/home/player/Origin Launcher/OriginLauncher", LinuxInstall.AppImage, OriginPaths.UserAppCopy, File.Exists);
            Require(entry.Contains("\nExec=" + InstallRegistry.ExecQuote(target) + "\n", StringComparison.Ordinal), "Exec line is wrong:\n" + entry);
            var validated = "desktop-file-validate not installed";
            if (File.Exists("/usr/bin/desktop-file-validate"))
            {
                var (code, output) = Run("/usr/bin/desktop-file-validate", InstallRegistry.DesktopFilePath);
                Require(code == 0 && !output.Contains("error", StringComparison.OrdinalIgnoreCase), "desktop-file-validate rejected it: " + output.Trim());
                validated = "passes desktop-file-validate";
            }
            InstallRegistry.SetStartWithWindows(false, "");
            Require(!File.Exists(InstallRegistry.DesktopFilePath), "turning it off left the entry");
            return $"XDG autostart entry written ({validated}), removed when off";
        }
        finally
        {
            InstallRegistry.AutostartDir = realDir;
        }
    }

    private static string Prewarm()
    {
        var root = Path.Combine(OriginPaths.Root, "prewarm");
        var version = "1.21.1";
        Write(Path.Combine(root, "versions", version, version + ".jar"), 4096);
        Write(Path.Combine(root, "libraries", "a", "lib.jar"), 4096);
        var mods = Path.Combine(root, "mods");
        Write(Path.Combine(mods, "mod.jar"), 4096);
        var result = LaunchPrewarm.Warm(root, mods, version, CancellationToken.None);
        Require(result.FilesRead == 3 && !result.Cancelled, $"read {result.FilesRead} of 3 jars");

        // The idle I/O class on its own thread, as the real warm-up thread gets it.
        (bool Entered, int Inside, int After) io = default;
        var thread = new Thread(() => io = LaunchPrewarm.BackgroundRoundTripForTests());
        thread.Start();
        thread.Join();
        Require(io.Entered && io.Inside == 3, $"ioprio idle class not applied (entered {io.Entered}, class {io.Inside})");
        Require(io.After != 3, "idle class not left again");

        LaunchPrewarm.Start(version, root, mods);
        LaunchPrewarm.Stop();
        return $"{result.Entries} entries, {result.FilesRead} jars, {result.BytesRead} bytes; warm-up thread runs in the idle I/O class";
    }

    private static void Write(string file, int bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        File.WriteAllBytes(file, new byte[bytes]);
    }

    // A 64x64 skin written by a different encoder (Python zlib, real CRCs, row y uses filter y % 5).
    // Pixel (x, y) = RGBA(x*4, y*4, (x+y)*2, (x+y)%7 == 0 ? 128 : 255). Same file as the tests' fixture.
    private static string SkinDecode()
    {
        var png = File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "skin-all-filters.png"));
        var bgra = SkinPng.DecodeBgra(png) ?? throw new InvalidOperationException("decoder returned null");
        for (var y = 0; y < 64; y++)
        for (var x = 0; x < 64; x++)
        {
            var o = (y * 64 + x) * 4;
            Require(bgra[o] == (byte)((x + y) * 2) && bgra[o + 1] == (byte)(y * 4) && bgra[o + 2] == (byte)(x * 4)
                    && bgra[o + 3] == ((x + y) % 7 == 0 ? 128 : 255), $"pixel {x},{y} wrong");
        }
        Require(SkinFace.FaceWithHat(bgra, 64, 64) != null, "no face from the skin");
        return "64x64, every filter, pixel-exact; face + hat built";
    }

    private static string JavaLayout()
    {
        var extracted = Path.Combine(OriginPaths.Root, "javatree");
        var java = Path.Combine(extracted, "jdk8u504-b01-jre", "bin", "java");
        Write(java, 16);
        Require(JavaRuntimeProvisioner.FindRuntimeRoot(extracted) == Path.Combine(extracted, "jdk8u504-b01-jre"), "runtime root not found");
        var arch = RuntimeInformation.OSArchitecture == Architecture.Arm64 ? "aarch64" : "x64";
        Require(TemurinJava8.Pin.Url.EndsWith($"_{arch}_linux_hotspot_8u504b01.tar.gz", StringComparison.Ordinal), $"pin is not the Linux {arch} tar.gz");
        return "bin/java found; pin " + Path.GetFileName(TemurinJava8.Pin.Url);
    }

    // The self-update of an unpacked tar.gz install: the release tar (one top folder, executable kept)
    // is staged by UpdateService.StageRelease, then the swap script (UpdateService.Linux.cs) waits for
    // the launcher's pid to exit, replaces the app folder, leaves no .previous/.new copy and starts it.
    private static string UpdateSwapFolder()
    {
        var root = Path.Combine(OriginPaths.Root, "update-folder");
        var marker = Path.Combine(root, "started");
        var app = Path.Combine(root, "Origin Launcher");
        FakeLauncher(Path.Combine(app, "OriginLauncher"), "old", marker);
        File.WriteAllText(Path.Combine(app, "only-in-old.txt"), "gone after the update");

        var release = Path.Combine(root, "release", "OriginLauncher-linux-x64");
        FakeLauncher(Path.Combine(release, "OriginLauncher"), "new", marker);
        var tar = Path.Combine(root, "download.zip"); // the shared code's local file name, whatever the content
        using (var file = File.Create(tar))
        using (var gzip = new GZipStream(file, CompressionLevel.Fastest))
            TarFile.CreateFromDirectory(release, gzip, includeBaseDirectory: true);
        var stage = Path.Combine(root, "stage");
        UpdateService.StageRelease(tar, stage, appImage: false);
        var staged = Path.Combine(stage, "OriginLauncher-linux-x64", "OriginLauncher");
        Require((File.GetUnixFileMode(staged) & UnixFileMode.UserExecute) != 0, "the tar lost the executable bit");

        var (code, output) = RunSwap(stage, app, "folder");
        Require(File.ReadAllText(Path.Combine(app, "OriginLauncher")).Contains("echo new", StringComparison.Ordinal), $"app folder not replaced (exit {code}): {output.Trim()}");
        Require(!File.Exists(Path.Combine(app, "only-in-old.txt")), "old files survived the swap");
        Require(!Directory.Exists(app + ".previous") && !Directory.Exists(app + ".new"), "old or half copy left behind");
        return "folder swapped after the launcher quit; started: " + WaitForMarker(marker);
    }

    // The self-update of an AppImage: the downloaded image is staged as one executable file, then
    // renamed over the old .AppImage in its own folder and started.
    private static string UpdateSwapAppImage()
    {
        var root = Path.Combine(OriginPaths.Root, "update-appimage");
        var marker = Path.Combine(root, "started");
        var image = Path.Combine(root, "Applications", "Origin Launcher.AppImage");
        FakeLauncher(image, "old", marker);
        var download = Path.Combine(root, "download.zip");
        FakeLauncher(download, "new", marker);
        File.SetUnixFileMode(download, UnixFileMode.UserRead | UnixFileMode.UserWrite); // as downloaded: not executable
        var stage = Path.Combine(root, "stage");
        UpdateService.StageRelease(download, stage, appImage: true);

        var (code, output) = RunSwap(stage, image, "appimage");
        Require(File.ReadAllText(image).Contains("echo new", StringComparison.Ordinal), $"AppImage not replaced (exit {code}): {output.Trim()}");
        Require((File.GetUnixFileMode(image) & UnixFileMode.UserExecute) != 0, "new AppImage is not executable");
        Require(!File.Exists(image + ".new"), "half copy left behind");
        return "AppImage replaced after the launcher quit; started: " + WaitForMarker(marker);
    }

    // The self-update of a .deb/.rpm install: the package folder (marker inside) is left alone and the
    // release tar becomes the per-user copy (OriginPaths.UserAppCopy), created on the first update.
    private static string UpdateSwapPackage()
    {
        var root = Path.Combine(OriginPaths.Root, "update-package");
        var marker = Path.Combine(root, "started");
        var package = Path.Combine(root, "opt", "origin-launcher");
        FakeLauncher(Path.Combine(package, "OriginLauncher"), "package", marker);
        File.WriteAllText(Path.Combine(package, LinuxInstall.PackageMarkerName), "deb\n");
        Require(LinuxInstall.IsPackageFolder(package), "the package-install marker is not recognised");
        var target = UpdateService.FolderSwapTarget(LinuxInstall.IsPackageFolder(package), package);
        Require(target == OriginPaths.UserAppCopy && target.StartsWith(OriginPaths.Root, StringComparison.Ordinal),
            $"a package install swaps {target}, not the per-user copy");
        Require(!Directory.Exists(target), "per-user copy exists before the first update");

        var release = Path.Combine(root, "release", "OriginLauncher-linux-x64");
        FakeLauncher(Path.Combine(release, "OriginLauncher"), "new", marker);
        var tar = Path.Combine(root, "download.zip");
        using (var file = File.Create(tar))
        using (var gzip = new GZipStream(file, CompressionLevel.Fastest))
            TarFile.CreateFromDirectory(release, gzip, includeBaseDirectory: true);
        var stage = Path.Combine(root, "stage");
        UpdateService.StageRelease(tar, stage, appImage: false);

        var (code, output) = RunSwap(stage, target, "folder", Path.Combine(package, "OriginLauncher"));
        Require(File.Exists(Path.Combine(target, "OriginLauncher"))
                && File.ReadAllText(Path.Combine(target, "OriginLauncher")).Contains("echo new", StringComparison.Ordinal),
            $"per-user copy not created (exit {code}): {output.Trim()}");
        Require(File.ReadAllText(Path.Combine(package, "OriginLauncher")).Contains("echo package", StringComparison.Ordinal), "the package folder was changed");
        Require(!Directory.Exists(target + ".new") && !Directory.Exists(target + ".previous"), "half copy left behind");
        return "package folder untouched, per-user copy created; started: " + WaitForMarker(marker);
    }

    private static (int Code, string Output) RunSwap(string stage, string target, string mode, string fallback = "")
    {
        var script = Path.Combine(Path.GetDirectoryName(stage)!, "apply-update.sh");
        File.WriteAllText(script, UpdateService.ApplyScript.ReplaceLineEndings("\n"));
        using var launcher = Process.Start(new ProcessStartInfo("/bin/sleep", "2") { UseShellExecute = false })!; // stands in for the running launcher
        var clock = Stopwatch.StartNew();
        var result = Run("/bin/sh", script, launcher.Id.ToString(), stage, target, mode, fallback);
        Require(clock.Elapsed >= TimeSpan.FromSeconds(1.5), "the swap did not wait for the launcher to quit");
        return result;
    }

    // A stand-in launcher: a shell script that records which version started.
    private static void FakeLauncher(string path, string version, string marker)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, $"#!/bin/sh\necho {version} > '{marker}'\n");
        File.SetUnixFileMode(path, (UnixFileMode)0b111_101_101);
    }

    private static string WaitForMarker(string marker)
    {
        for (var i = 0; i < 100; i++)
        {
            if (File.Exists(marker) && File.ReadAllText(marker).Trim() is { Length: > 0 } v)
            {
                Require(v == "new", $"the {v} launcher started, not the new one");
                return "the new launcher";
            }
            Thread.Sleep(100);
        }
        return "not seen within 10 s";
    }

    private static async Task<string> JavaDownload()
    {
        using var http = new HttpClient();
        var runtimes = Path.Combine(OriginPaths.Root, "runtimes");
        var java = await JavaRuntimeProvisioner.EnsureAsync(TemurinJava8.Pin, runtimes,
            (url, dest, fraction, ct) => JavaRuntimeProvisioner.HttpDownload(http, url, dest, fraction, ct),
            progress: null, CancellationToken.None).ConfigureAwait(false);
        Require(java != null, "download, hash or unpack failed");
        Require(java!.EndsWith("/bin/java", StringComparison.Ordinal), "unexpected java path " + java);
        var (code, output) = Run(java, "-version");
        Require(code == 0 && output.Contains("1.8.0_504", StringComparison.Ordinal), $"java -version exit {code}: {output.Trim()}");
        return $"{java} -> {output.Split('\n')[0].Trim()}";
    }

    // Rules applied per library before anything is merged: 1.8.9 lists LWJGL 2.9.4 for every OS but
    // osx and 2.9.2 for osx only, so Linux must get 2.9.4 (and its linux natives) and never 2.9.2.
    private static async Task<string> Mojang189()
    {
        var files = await RequiredFiles("1.8.9").ConfigureAwait(false);
        Require(files.Paths.Any(p => p.EndsWith("/lwjgl-2.9.4-nightly-20150209.jar", StringComparison.Ordinal)), "no LWJGL 2.9.4");
        Require(files.Paths.Any(p => p.Contains("lwjgl-platform-2.9.4-nightly-20150209-natives-linux", StringComparison.Ordinal)), "no LWJGL linux natives");
        Require(!files.Paths.Any(p => p.Contains("2.9.2-nightly", StringComparison.Ordinal)), "LWJGL 2.9.2 (osx only) leaked in");
        Require(!files.Paths.Any(p => p.Contains("natives-windows", StringComparison.Ordinal) || p.Contains("natives-osx", StringComparison.Ordinal)), "another OS's natives leaked in");
        return $"{files.Paths.Count} files; LWJGL 2.9.4 + linux natives, no 2.9.2; Java: {files.Java}";
    }

    // LWJGL 3 era: natives-linux libraries in, every other OS's out; Mojang's Java on x64 only.
    private static async Task<string> Mojang1211()
    {
        var files = await RequiredFiles("1.21.1").ConfigureAwait(false);
        var natives = files.Paths.Where(p => p.Contains("natives-linux", StringComparison.Ordinal)).ToList();
        Require(natives.Count > 0, "no natives-linux libraries");
        Require(!files.Paths.Any(p => p.Contains("natives-windows", StringComparison.Ordinal) || p.Contains("natives-macos", StringComparison.Ordinal)), "another OS's natives leaked in");
        var arm = natives.Count(p => p.Contains("arm64", StringComparison.Ordinal) || p.Contains("aarch64", StringComparison.Ordinal));
        if (RuntimeInformation.OSArchitecture != Architecture.Arm64)
            Require(!files.Skipped, "Mojang's linux Java was not listed");
        return $"{files.Paths.Count} files; {natives.Count} natives-linux ({arm} arm64); Java: {files.Java}";
    }

    private static async Task<(List<string> Paths, bool Skipped, string Java)> RequiredFiles(string version)
    {
        var set = await new MojangMetadata(new MetadataClient(new ImportMetadataHttp()))
            .GetRequiredSetAsync(version, CancellationToken.None).ConfigureAwait(false);
        Require(!set.Unavailable, "metadata unavailable");
        var java = set.JavaSkipped ? "skipped (Mojang has no " + MojangMetadata.RuntimePlatform + " build)" : set.RuntimeComponent ?? "?";
        return (set.Files.Select(f => f.RelPath).ToList(), set.JavaSkipped, java);
    }

    private static (int Code, string Output) Run(string tool, params string[] args)
    {
        var psi = new ProcessStartInfo(tool) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi)!;
        var stdout = p.StandardOutput.ReadToEndAsync();
        var stderr = p.StandardError.ReadToEndAsync();
        if (!p.WaitForExit(60_000)) { p.Kill(); return (-1, "timed out"); }
        return (p.ExitCode, stdout.Result + stderr.Result);
    }
}
