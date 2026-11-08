using System.Diagnostics;
using System.Text.RegularExpressions;
using CmlLib.Core.Auth;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.Linux;

/// <summary>
/// CI only, hidden: `--boot-test &lt;minecraft version&gt; [--root DIR] [--log FILE] [--timeout SECONDS]`.
/// One Play press through the launcher's own Core, the same calls the Windows launcher's Play makes
/// (Core/Launch/LaunchTimingHarness.cs is the Windows twin): an offline session, every launcher file
/// under DIR (a fresh temp folder by default, never the player's ~/.local/share/OriginLauncher),
/// the loader (Fabric, or Forge + OptiFine for 1.8.9), the bundled Origin Client jar, Origin's mods
/// and Java all installed by Core exactly as for a player, then the game started.
///
/// The game runs with ORIGIN_SMOKE=1, so Origin Client logs "[origin-smoke] title screen reached"
/// the first tick its title screen is up (the boot-smoke marker, OriginClientMod). That line comes
/// from Origin's own mod, so it also proves the bundled jar loaded. Exit 0 only when the title was
/// reached and no line said a mixin failed to apply; 1 otherwise (game exited, timeout, a mixin
/// failure, an install error). The game's console goes to FILE (default DIR/boot-test-&lt;version&gt;.log)
/// for tools/linux/check_boot.py, which re-reads it with boot-smoke's full rule set.
///
/// CI gives it a display (Xvfb + Mesa's software GL, .github/workflows/linux-launcher.yml); it opens
/// the game window there and kills the game once the title is up. Never run on Will's PC.
/// </summary>
internal static class BootTest
{
    private const string Ready = "[origin-smoke] title screen reached";
    // The first of boot-smoke's failure rules (tools/boot-smoke/smoke.py FAIL_PATTERNS); check_boot.py applies all of them.
    private static readonly Regex MixinFailed = new(@"Mixin apply(ing)? .*failed|InvalidInjection|Critical injection failure",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public static int Run(string version, string? root, string? logPath, TimeSpan timeout)
    {
        root ??= Path.Combine(Path.GetTempPath(), "origin-boot-test-" + Guid.NewGuid().ToString("N"));
        // Before anything reads OriginPaths.Root, so no path can ever land on the player's data.
        OriginPaths.UseScratchRoot(root);
        logPath = Path.GetFullPath(logPath ?? Path.Combine(OriginPaths.Root, $"boot-test-{version}.log"));
        Directory.CreateDirectory(Path.GetDirectoryName(logPath)!);
        Console.WriteLine($"boot-test {version}: data root {OriginPaths.Root}, game log {logPath}");

        var clock = Stopwatch.StartNew();
        var gate = new object();
        using var log = new StreamWriter(logPath, append: false) { AutoFlush = true };
        var ready = new ManualResetEventSlim();
        var mixinFailures = new List<string>();
        Process? process = null;
        try
        {
            OriginPaths.EnsureScaffold();
            // The offline test session (Settings -> Developer) is the only sign-in CI has.
            SettingsStore.Update(s => s.OfflineTestMode = true);
            var settings = SettingsStore.Load();
            var session = MSession.CreateOfflineSession("OriginTester");
            var option = LaunchProfileBuilder.Build(settings, session, version);
            var key = InstanceLayout.OfflineKey;
            var gameDir = AccountFolders.PrepareGameDir(version, key);

            // Stage lines only (byte counters carry "(n/m)" and would flood the CI log).
            var seen = new HashSet<string>();
            var progress = new SyncProgress(text =>
            {
                if (text.Contains('(')) return;
                lock (gate) if (seen.Add(text)) Console.WriteLine($"  [{clock.Elapsed.TotalSeconds,6:0.0}s] {text}");
            });
            process = new VersionManager()
                .InstallAndBuildProcessAsync(version, key, option, settings.PlayWithExternalMods, null, progress)
                .GetAwaiter().GetResult();
            if (settings.SyncSettingsAcrossVersions) SettingsSync.BeforeLaunch(key, version);
            SkipAccessibilityOnboarding(gameDir);
            Console.WriteLine($"  [{clock.Elapsed.TotalSeconds,6:0.0}s] installed; java {process.StartInfo.FileName}");
            PrintMods(version);

            process.StartInfo.UseShellExecute = false;
            process.StartInfo.RedirectStandardOutput = true;
            process.StartInfo.RedirectStandardError = true;
            process.StartInfo.Environment["ORIGIN_SMOKE"] = "1";
            void OnLine(string? line)
            {
                if (line == null) return;
                lock (gate)
                {
                    log.WriteLine(line);
                    if (MixinFailed.IsMatch(line) && mixinFailures.Count < 20) mixinFailures.Add(line.Trim());
                }
                if (line.Contains(Ready, StringComparison.Ordinal)) ready.Set();
            }
            process.OutputDataReceived += (_, e) => OnLine(e.Data);
            process.ErrorDataReceived += (_, e) => OnLine(e.Data);
            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            Console.WriteLine($"  [{clock.Elapsed.TotalSeconds,6:0.0}s] game started (pid {process.Id})");

            var deadline = Stopwatch.StartNew();
            while (!ready.IsSet && !process.HasExited && deadline.Elapsed < timeout)
                ready.Wait(250);
            // A moment more for anything the title's first frames log (a late mixin applies on first use).
            if (ready.IsSet) Thread.Sleep(3000);

            int failed;
            lock (gate) failed = mixinFailures.Count;
            if (ready.IsSet && failed == 0)
            {
                Console.WriteLine($"BOOT-TEST PASSED {version}: title screen reached {deadline.Elapsed.TotalSeconds:0}s after the game started " +
                                  $"({clock.Elapsed.TotalSeconds:0}s after Play), zero mixin-apply failures");
                return 0;
            }
            var why = ready.IsSet ? $"{failed} mixin-apply failure line(s)"
                : process.HasExited ? $"the game exited (code {process.ExitCode}) before the title screen"
                : $"no title screen within {timeout.TotalSeconds:0}s";
            Console.WriteLine($"BOOT-TEST FAILED {version}: {why}");
            lock (gate) foreach (var f in mixinFailures) Console.WriteLine("  " + f);
            return 1;
        }
        catch (Exception ex)
        {
            // What the player's launch screen says for this failure (PlayFailure), then the full error for the log.
            var shown = PlayFailure.From(ex, version);
            Console.WriteLine($"  launch screen: {shown.Title}: {shown.Message}");
            Console.WriteLine($"BOOT-TEST FAILED {version}: {ex}");
            return 1;
        }
        finally
        {
            if (process != null)
            {
                try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
                try { process.WaitForExit(10_000); } catch (InvalidOperationException) { }
            }
        }
    }

    // What Core put in the version's shared mods folder: the proof the loader's mods and Origin's jar were installed.
    /// <summary>A brand-new game folder opens vanilla's one-time accessibility onboarding screen (1.19.4+)
    /// instead of the title, so the title marker never comes (run 41582275097: 1.8.9 passed, every newer
    /// version sat on that screen for 1200 s). Seeds the answer a returning player already has, exactly like
    /// tools/boot-smoke/smoke.py does for boot-smoke; the test's own temp game folder only.</summary>
    private static void SkipAccessibilityOnboarding(string gameDir)
    {
        var path = Path.Combine(gameDir, "options.txt");
        var lines = File.Exists(path)
            ? File.ReadAllLines(path).Where(l => !l.StartsWith("onboardAccessibility:", StringComparison.Ordinal)).ToList()
            : new List<string> { "pauseOnLostFocus:false" };
        lines.Add("onboardAccessibility:false");
        File.WriteAllText(path, string.Join('\n', lines) + "\n");
        Console.WriteLine($"  seeded {path} (onboardAccessibility:false)");
    }

    private static void PrintMods(string version)
    {
        var mods = OriginPaths.ModsDir(version);
        var jars = Directory.Exists(mods) ? Directory.GetFiles(mods, "*.jar").Select(Path.GetFileName).Order().ToList() : [];
        Console.WriteLine($"  mods folder {mods}: {jars.Count} jar(s)");
        foreach (var j in jars) Console.WriteLine("    " + j);
    }

    // Progress<T> posts to the thread pool with no ordering; this reports inline, in order.
    private sealed class SyncProgress(Action<string> report) : IProgress<string>
    {
        public void Report(string value) => report(value);
    }
}
