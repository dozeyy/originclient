using System.Diagnostics;
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

namespace OriginLauncher.Mac;

/// <summary>
/// `--self-test`: prints where the Mac launcher keeps its data, then runs each Mac seam once and
/// prints PASS/FAIL per check. Exit 0 only when every check passed. Safe to run on any Mac: the
/// real data folder is only printed, never touched (every check runs in a temp folder with
/// OriginPaths pointed at it), and the Keychain check uses a throwaway item it deletes again.
/// `--network` adds the checks that download: the pinned Java 8 for 1.8.9 (about 44 MB) and
/// Mojang's 1.8.9 file list.
/// </summary>
internal static class SelfTest
{
    private static int _failed;

    public static async Task<int> RunAsync(bool network)
    {
        Console.WriteLine($"Origin Launcher self-test: macOS {Environment.OSVersion.Version}, process {RuntimeInformation.ProcessArchitecture}, machine {RuntimeInformation.OSArchitecture}");
        Console.WriteLine($"  data folder      {OriginPaths.DefaultRoot}");
        Console.WriteLine($"  bundled files    {OriginPaths.MacBundleResources ?? "(not running from an .app: next to the executable)"}");
        Console.WriteLine($"  update asset     {UpdateService.ReleaseAssetName}");
        Console.WriteLine($"  Mojang runtime   {MojangMetadata.RuntimePlatform}");

        var scratch = Path.Combine(Path.GetTempPath(), "origin-selftest-" + Guid.NewGuid().ToString("N"));
        OriginPaths.UseScratchRoot(scratch);
        try
        {
            Check("paths", Paths);
            Check("keychain", Keychain);
            Check("hard link", HardLink);
            Check("sysctl", Sysctl);
            Check("gpu preference", () => { GpuPreference.PreferHighPerformanceGpu("/usr/bin/java"); return "no-op on a Mac"; });
            Check("start at login", StartAtLogin);
            Check("prewarm", Prewarm);
            Check("skin decode", SkinDecode);
            Check("java 8 layout", JavaLayout);
            Check("update swap", UpdateSwap);
            Check("update swap failure", UpdateSwapFailure);
            Check("update guard", UpdateGuard);
            Check("update unzip", UpdateUnzip);
            if (network)
            {
                await CheckAsync("java 8 download", JavaDownload).ConfigureAwait(false);
                await CheckAsync("mojang 1.8.9 files", Mojang189).ConfigureAwait(false);
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
        catch (Exception e) { _failed++; Console.WriteLine($"FAIL {name}: {e.GetType().Name}: {e.Message}"); }
    }

    private static async Task CheckAsync(string name, Func<Task<string>> body)
    {
        try { Console.WriteLine($"PASS {name}: {await body().ConfigureAwait(false)}"); }
        catch (Exception e) { _failed++; Console.WriteLine($"FAIL {name}: {e.GetType().Name}: {e.Message}"); }
    }

    private static void Require(bool ok, string what)
    {
        if (!ok) throw new InvalidOperationException(what);
    }

    private static string Paths()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        Require(OriginPaths.DefaultRoot == Path.Combine(home, "Library", "Application Support", "OriginLauncher"), "data folder is not in Application Support");
        Require(OriginPaths.Root.StartsWith(Path.GetTempPath(), StringComparison.Ordinal), "self-test is not on its scratch folder");
        Require(OriginPaths.Instances.StartsWith(OriginPaths.Root, StringComparison.Ordinal), "instances outside the data folder");
        return $"instances {OriginPaths.Instances}, bundled jars {OriginPaths.BundledOriginClientDir}";
    }

    // The real wiring: AccountStore's own Protect/Unprotect (AES-GCM, key in the Keychain), on a throwaway item.
    private static string Keychain()
    {
        var realAccount = MacKeychain.Account;
        MacKeychain.Account = "selftest-" + Guid.NewGuid().ToString("N");
        try
        {
            Require(MacKeychain.TryReadKey() == null, "throwaway item already exists");
            var plain = Encoding.UTF8.GetBytes("{\"refresh_token\":\"self-test-not-a-real-token\"}");
            var blob = AccountStore.ProtectBytes(plain);
            Require(blob.AsSpan().IndexOf("self-test-not"u8) < 0, "plaintext visible in the sealed blob");
            MacKeychain.ForgetCached();
            Require(AccountStore.UnprotectBytes(blob).AsSpan().SequenceEqual(plain), "round trip changed the bytes");

            var tampered = (byte[])blob.Clone();
            tampered[^1] ^= 1;
            Require(Throws(() => AccountStore.UnprotectBytes(tampered)), "a changed byte was accepted");

            MacKeychain.DeleteForTests();
            Require(Throws(() => AccountStore.UnprotectBytes(blob)), "opened with no key in the Keychain");
            Require(MacKeychain.TryReadKey() == null, "unsealing created a key");
            return $"AES-256-GCM blob of {blob.Length} bytes, key read back from the login Keychain, missing key = sign in again";
        }
        finally
        {
            try { MacKeychain.DeleteForTests(); } catch (CryptographicException) { }
            MacKeychain.Account = realAccount;
            MacKeychain.ForgetCached();
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

    private static string Sysctl()
    {
        var ram = SystemInfo.GetTotalPhysicalMemoryMb();
        Require(ram >= 2048, $"implausible RAM {ram} MB");
        var cpu = SystemInfo.SysctlString("machdep.cpu.brand_string");
        Require(!string.IsNullOrWhiteSpace(cpu), "no CPU name");
        return $"{ram} MB, {cpu}, Rosetta={SystemInfo.SysctlInt64("sysctl.proc_translated") == 1}; {HardwareProbe.Describe(HardwareProbe.Current)}";
    }

    private static string StartAtLogin()
    {
        var realDir = InstallRegistry.LaunchAgentsDir;
        InstallRegistry.LaunchAgentsDir = Path.Combine(OriginPaths.Root, "LaunchAgents");
        try
        {
            Require(!InstallRegistry.IsStartWithWindows(), "already on");
            InstallRegistry.SetStartWithWindows(true, "/Applications/Origin Launcher.app/Contents/MacOS/OriginLauncher");
            Require(InstallRegistry.IsStartWithWindows(), "turning it on wrote nothing");
            var (lint, output) = Run("/usr/bin/plutil", "-lint", InstallRegistry.AgentPlistPath);
            Require(lint == 0, "plutil rejected the plist: " + output.Trim());
            var plist = File.ReadAllText(InstallRegistry.AgentPlistPath);
            Require(plist.Contains("<string>/usr/bin/open</string>") && plist.Contains("/Applications/Origin Launcher.app"), "plist does not open the app bundle");
            InstallRegistry.SetStartWithWindows(false, "");
            Require(!File.Exists(InstallRegistry.AgentPlistPath), "turning it off left the plist");
            return "LaunchAgent plist passes plutil -lint, opens the .app at login, removed when off";
        }
        finally
        {
            InstallRegistry.LaunchAgentsDir = realDir;
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
        LaunchPrewarm.Start(version, root, mods); // the background thread, without a priority change on a Mac
        LaunchPrewarm.Stop();
        return $"{result.Entries} entries, {result.FilesRead} jars, {result.BytesRead} bytes";
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
        var java = Path.Combine(extracted, "jdk8u504-b01-jre", "Contents", "Home", "bin", "java");
        Write(java, 16);
        Require(JavaRuntimeProvisioner.FindRuntimeRoot(extracted) == Path.Combine(extracted, "jdk8u504-b01-jre"), "runtime root not found");
        Require(TemurinJava8.Pin.Url.EndsWith("_x64_mac_hotspot_8u504b01.tar.gz", StringComparison.Ordinal), "pin is not the Mac x64 tar.gz");
        return "Contents/Home/bin/java found; pin " + Path.GetFileName(TemurinJava8.Pin.Url);
    }

    // The self-update's swap script (UpdateService.Mac.cs) on a fake bundle: waits for the launcher's
    // pid to exit, moves the new .app into place, leaves no .previous copy and reopens the app.
    private static string UpdateSwap()
    {
        var root = Path.Combine(OriginPaths.Root, "update");
        var marker = Path.Combine(root, "reopened");
        var app = Path.Combine(root, "Applications", "Origin Launcher.app");
        FakeBundle(app, "old", marker);
        var stage = Path.Combine(root, "stage");
        FakeBundle(Path.Combine(stage, "Origin Launcher.app"), "new", marker);
        var script = Path.Combine(root, "apply-update.sh");
        File.WriteAllText(script, UpdateService.ApplyScript);

        var failed = Path.Combine(root, "apply-failed-test");
        File.WriteAllText(failed, ""); // a stale marker from an earlier failure: a good swap clears it

        using var launcher = Process.Start(new ProcessStartInfo("/bin/sleep", "2") { UseShellExecute = false })!; // stands in for the running launcher
        var clock = Stopwatch.StartNew();
        var (code, output) = Run("/bin/sh", script, launcher.Id.ToString(), stage, app, failed);
        Require(clock.Elapsed >= TimeSpan.FromSeconds(1.5), "the swap did not wait for the launcher to quit");
        Require(code == 0 && File.ReadAllText(Path.Combine(app, "Contents", "version")) == "new", $"bundle not replaced (exit {code}): {output.Trim()}");
        Require(!Directory.Exists(app + ".previous") && !Directory.Exists(app + ".incoming"), "old or incoming bundle left behind");
        Require(!File.Exists(failed), "the failure marker survived a good swap");
        Require(WaitFor(marker), "the new app was not reopened");
        return "bundle swapped after the launcher quit, nothing left behind, reopened";
    }

    // A swap that can't happen (here: the stage holds no app) still reopens the OLD app, leaves it
    // untouched and writes the marker that makes the next try refuse before the launcher quits.
    private static string UpdateSwapFailure()
    {
        var root = Path.Combine(OriginPaths.Root, "update-fail");
        var marker = Path.Combine(root, "reopened");
        var app = Path.Combine(root, "Applications", "Origin Launcher.app");
        FakeBundle(app, "old", marker);
        var stage = Path.Combine(root, "stage");
        Directory.CreateDirectory(stage);
        var script = Path.Combine(root, "apply-update.sh");
        File.WriteAllText(script, UpdateService.ApplyScript);
        var failed = UpdateService.FailedMarker(stage, root);

        var (code, _) = Run("/bin/sh", script, "999999", stage, app, failed);
        Require(code != 0, "an impossible swap reported success");
        Require(File.ReadAllText(Path.Combine(app, "Contents", "version")) == "old", "the old app was touched");
        Require(File.Exists(failed), "no failure marker");
        Require(WaitFor(marker), "the old app was not reopened");
        return $"old app kept and reopened, marker {Path.GetFileName(failed)} written";
    }

    // Refused while the launcher is still open, where UpdateGate counts it.
    private static string UpdateGuard()
    {
        var root = Path.Combine(OriginPaths.Root, "update-guard");
        var ok = Path.Combine(root, "Applications", "Origin Launcher.app");
        Directory.CreateDirectory(ok);
        UpdateService.EnsureCanReplace(ok);

        Require(ThrowsIo(() => UpdateService.EnsureCanReplace("/private/var/folders/x/AppTranslocation/ABC/d/Origin Launcher.app")), "a translocated app was accepted");

        var locked = Path.Combine(root, "ReadOnly");
        var lockedApp = Path.Combine(locked, "Origin Launcher.app");
        Directory.CreateDirectory(lockedApp);
        File.SetUnixFileMode(locked, UnixFileMode.UserRead | UnixFileMode.UserExecute);
        try
        {
            Require(ThrowsIo(() => UpdateService.EnsureCanReplace(lockedApp)), "an app in a read-only folder was accepted");
        }
        finally
        {
            File.SetUnixFileMode(locked, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
        return "writable folder accepted; translocated and read-only folder refused before quitting";
    }

    private static bool ThrowsIo(Action action)
    {
        try { action(); return false; }
        catch (IOException) { return true; }
    }

    private static bool WaitFor(string file)
    {
        for (var i = 0; i < 100 && !File.Exists(file); i++) Thread.Sleep(100);
        return File.Exists(file);
    }

    // A real signed .app, zipped the way a release will be (ditto), through the launcher's own update
    // unpacking: symlinks and exec bits must survive and the signature must still verify.
    private static string UpdateUnzip()
    {
        var root = Path.Combine(OriginPaths.Root, "update-zip");
        var app = Path.Combine(root, "build", "Origin Launcher.app");
        var contents = Path.Combine(app, "Contents");
        Directory.CreateDirectory(Path.Combine(contents, "MacOS"));
        File.WriteAllText(Path.Combine(contents, "Info.plist"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>CFBundleExecutable</key><string>OriginLauncher</string>
              <key>CFBundleIdentifier</key><string>org.originclient.selftest.zip</string>
              <key>CFBundlePackageType</key><string>APPL</string>
            </dict></plist>
            """);
        // This very self-test binary as the app's main executable: a real Mach-O to sign.
        File.Copy(Environment.ProcessPath!, Path.Combine(contents, "MacOS", "OriginLauncher"));
        // A framework laid out the way real ones are: Versions/Current and the top-level names are symlinks.
        var fw = Path.Combine(contents, "Frameworks", "Probe.framework");
        var fwA = Path.Combine(fw, "Versions", "A");
        Directory.CreateDirectory(Path.Combine(fwA, "Resources"));
        File.Copy(Environment.ProcessPath!, Path.Combine(fwA, "Probe")); // any Mach-O does; this one is at hand
        File.WriteAllText(Path.Combine(fwA, "Resources", "Info.plist"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>CFBundleExecutable</key><string>Probe</string>
              <key>CFBundleIdentifier</key><string>org.originclient.selftest.probe</string>
              <key>CFBundlePackageType</key><string>FMWK</string>
            </dict></plist>
            """);
        File.CreateSymbolicLink(Path.Combine(fw, "Versions", "Current"), "A");
        File.CreateSymbolicLink(Path.Combine(fw, "Probe"), "Versions/Current/Probe");
        File.CreateSymbolicLink(Path.Combine(fw, "Resources"), "Versions/Current/Resources");

        var (sign, signOut) = Run("/usr/bin/codesign", "--force", "--deep", "--sign", "-", app);
        Require(sign == 0, "ad-hoc signing failed: " + signOut.Trim());
        var zip = Path.Combine(root, "update.zip");
        var (zipped, zipOut) = Run("/usr/bin/ditto", "-c", "-k", "--keepParent", app, zip);
        Require(zipped == 0, "ditto zip failed: " + zipOut.Trim());

        var stage = Path.Combine(root, "stage");
        Directory.CreateDirectory(stage);
        UpdateService.ExtractUpdate(zip, stage);
        var staged = Path.Combine(stage, "Origin Launcher.app");
        var current = new DirectoryInfo(Path.Combine(staged, "Contents", "Frameworks", "Probe.framework", "Versions", "Current"));
        Require(current.LinkTarget == "A", "the framework's Versions/Current symlink did not survive");
        var mode = File.GetUnixFileMode(Path.Combine(staged, "Contents", "MacOS", "OriginLauncher"));
        Require(mode.HasFlag(UnixFileMode.UserExecute), "the app's executable lost its exec bit");
        var (verify, verifyOut) = Run("/usr/bin/codesign", "--verify", "--deep", "--strict", "--verbose=2", staged);
        Require(verify == 0, "signature broken after unpacking: " + verifyOut.Trim());
        return "ditto zip -> launcher unpack: symlinks + exec bit kept, codesign --verify --deep --strict passes";
    }

    private static void FakeBundle(string app, string version, string marker)
    {
        var contents = Path.Combine(app, "Contents");
        Directory.CreateDirectory(Path.Combine(contents, "MacOS"));
        File.WriteAllText(Path.Combine(contents, "version"), version);
        File.WriteAllText(Path.Combine(contents, "Info.plist"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>CFBundleExecutable</key><string>fake</string>
              <key>CFBundleIdentifier</key><string>org.originclient.selftest</string>
              <key>CFBundlePackageType</key><string>APPL</string>
            </dict></plist>
            """);
        var exe = Path.Combine(contents, "MacOS", "fake");
        File.WriteAllText(exe, $"#!/bin/sh\ntouch '{marker}'\n");
        File.SetUnixFileMode(exe, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
    }

    private static async Task<string> JavaDownload()
    {
        using var http = new HttpClient();
        var runtimes = Path.Combine(OriginPaths.Root, "runtimes");
        var java = await JavaRuntimeProvisioner.EnsureAsync(TemurinJava8.Pin, runtimes,
            (url, dest, fraction, ct) => JavaRuntimeProvisioner.HttpDownload(http, url, dest, fraction, ct),
            progress: null, CancellationToken.None).ConfigureAwait(false);
        Require(java != null, "download, hash or unpack failed");
        Require(java!.EndsWith("/Contents/Home/bin/java", StringComparison.Ordinal), "unexpected java path " + java);
        // An x64 Java runs natively on Intel and through Rosetta on Apple Silicon (1.8.9 needs it there too).
        if (RuntimeInformation.OSArchitecture == Architecture.Arm64 && Run("/usr/bin/arch", "-x86_64", "/usr/bin/true").Code != 0)
            return $"{java} (verified + unpacked; not run: Rosetta is not installed here)";
        var (code, output) = Run(java, "-version");
        Require(code == 0 && output.Contains("1.8.0_504", StringComparison.Ordinal), $"java -version exit {code}: {output.Trim()}");
        return $"{java} -> {output.Split('\n')[0].Trim()}";
    }

    // Rules applied per library before anything is merged: 1.8.9 lists LWJGL 2.9.4 for every OS but
    // osx and 2.9.2 for osx only, so a Mac must get 2.9.2 (and its osx natives) and never 2.9.4.
    private static async Task<string> Mojang189()
    {
        var set = await new MojangMetadata(new MetadataClient(new ImportMetadataHttp()))
            .GetRequiredSetAsync("1.8.9", CancellationToken.None).ConfigureAwait(false);
        Require(!set.Unavailable, "metadata unavailable");
        var files = set.Files.Select(f => f.RelPath).ToList();
        Require(files.Any(p => p.EndsWith("/lwjgl-2.9.2-nightly-20140822.jar", StringComparison.Ordinal)), "no LWJGL 2.9.2");
        Require(files.Any(p => p.Contains("lwjgl-platform-2.9.2-nightly-20140822-natives-osx", StringComparison.Ordinal)), "no LWJGL osx natives");
        Require(!files.Any(p => p.Contains("2.9.4-nightly", StringComparison.Ordinal)), "LWJGL 2.9.4 leaked in");
        Require(!files.Any(p => p.Contains("natives-windows", StringComparison.Ordinal)), "Windows natives leaked in");
        return $"{files.Count} files; LWJGL 2.9.2 + osx natives, no 2.9.4; Java: {(set.JavaSkipped ? "skipped (Mojang has no " + MojangMetadata.RuntimePlatform + " build)" : set.RuntimeComponent)}";
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
