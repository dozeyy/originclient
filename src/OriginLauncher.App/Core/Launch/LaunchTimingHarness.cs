using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// CI only (`--launch-timing <root> <version> <out.json>`, .github/workflows/launch-time.yml):
/// ONE Play press timed from the launcher's side, the same calls HomePage.LaunchAsync makes
/// (offline session, no window), with every launcher file under &lt;root&gt; instead of
/// %LocalAppData%. A fresh &lt;root&gt; is a player's first launch (everything downloads); the
/// same &lt;root&gt; again, in a new process, is a returning player opening the launcher and
/// pressing Play. The game process is started like a real launch and watched until its log
/// reaches Fabric's mod list (CI's Windows runner has no OpenGL, so it can't open a window),
/// then killed. Writes the marks (ms since Play) and every HTTP request made, then exits.
/// </summary>
public static class LaunchTimingHarness
{
    private sealed record Request(string Url, long StartMs, long Ms, int Status);

    public static int Run(string root, string version, string outJson)
    {
        OriginPaths.UseScratchRoot(root);
        OriginPaths.EnsureScaffold();
        // The offline test session (Settings -> Developer) is the only sign-in CI has.
        SettingsStore.Update(s => s.OfflineTestMode = true);

        var clock = Stopwatch.StartNew();
        var marks = new List<(string Name, long Ms)>();
        var requests = new List<Request>();
        var gate = new object();
        void Mark(string name) { lock (gate) marks.Add((name, clock.ElapsedMilliseconds)); }

        using var http = HttpWatch.Start(clock, r => { lock (gate) requests.Add(r); });
        LaunchTimeline.Sink = Mark;
        string? error = null;
        int? exitCode = null;
        var gameLines = new List<string>();
        try
        {
            // HomePage.LaunchAsync, minus the UI and the Microsoft sign-in.
            var settings = SettingsStore.Load();
            var session = MSession.CreateOfflineSession("OriginTester");
            var option = LaunchProfileBuilder.Build(settings, session, version);
            // The game's own boot marks (OriginBootLog reads this in the prod jar too).
            option.ExtraJvmArguments = (option.ExtraJvmArguments ?? Enumerable.Empty<MArgument>())
                .Append(new MArgument("-Dorigin.bootLog=true")).ToList();
            var key = InstanceLayout.OfflineKey;
            Mark("options");
            AccountFolders.PrepareGameDir(version, key);
            Mark("game-dir");

            var firstFile = 0;
            var progress = new Progress<string>(text =>
            {
                if (text.Contains('(') && Interlocked.Exchange(ref firstFile, 1) == 0) Mark("files-first-progress");
            });
            var process = new VersionManager()
                .InstallAndBuildProcessAsync(version, key, option, settings.PlayWithExternalMods, null, progress)
                .GetAwaiter().GetResult();
            Mark("prepared");
            if (settings.SyncSettingsAcrossVersions)
            {
                SettingsSync.BeforeLaunch(key, version);
                Mark("settings-sync");
            }

            // The game's console, read here instead of the launcher's log file.
            process.StartInfo.UseShellExecute = false;
            process.StartInfo.RedirectStandardOutput = true;
            process.StartInfo.RedirectStandardError = true;
            var done = new ManualResetEventSlim();
            void OnLine(string? line)
            {
                if (line == null) return;
                lock (gate)
                {
                    if (gameLines.Count < 400) gameLines.Add(line);
                    if (gameLines.Count == 1) marks.Add(("game-first-line", clock.ElapsedMilliseconds));
                }
                if (line.Contains("Loading Minecraft") && line.Contains("Fabric Loader")) Mark("game-fabric-start");
                if (line.Contains("Loading ") && line.Contains(" mods:")) { Mark("game-mods-listed"); }
                var boot = line.IndexOf("[OriginBoot] mark ", StringComparison.Ordinal);
                if (boot >= 0) Mark("game-" + line[(boot + 18)..].Split(' ')[0]);
                if (line.Contains("Backend library:")) { Mark("game-window"); done.Set(); }
            }
            process.OutputDataReceived += (_, e) => OnLine(e.Data);
            process.ErrorDataReceived += (_, e) => OnLine(e.Data);
            process.Start();
            Mark("process-start");
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            // The window line, the game ending on its own (no GL here), or 3 minutes.
            var deadline = Stopwatch.StartNew();
            while (!done.IsSet && !process.HasExited && deadline.Elapsed < TimeSpan.FromMinutes(3))
                done.Wait(100);
            Mark("game-stop");
            if (process.HasExited) exitCode = process.ExitCode;
            else { try { process.Kill(entireProcessTree: true); } catch { } }
            process.WaitForExit(10_000);
        }
        catch (Exception ex)
        {
            error = ex.ToString();
        }
        finally
        {
            LaunchTimeline.Sink = null;
        }

        lock (gate)
        {
            var report = new
            {
                version,
                error,
                gameExitCode = exitCode,
                marks = marks.Select(m => new { name = m.Name, ms = m.Ms }),
                requests = requests.OrderBy(r => r.StartMs)
                    .Select(r => new { url = r.Url, start = r.StartMs, ms = r.Ms, status = r.Status }),
                gameLog = gameLines,
            };
            File.WriteAllText(outJson, JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true }));
        }
        return error == null ? 0 : 1;
    }

    /// <summary>
    /// Every HttpClient request in this process (CmlLib's, Fabric's, Modrinth's), from .NET's
    /// own HTTP diagnostics events: url, start (ms since Play), duration and status. Listens
    /// only while the harness runs.
    /// </summary>
    private sealed class HttpWatch : IObserver<DiagnosticListener>, IObserver<KeyValuePair<string, object?>>, IDisposable
    {
        private readonly Stopwatch _clock;
        private readonly Action<Request> _sink;
        private readonly Dictionary<HttpRequestMessage, long> _open = new(ReferenceEqualityComparer.Instance);
        private readonly List<IDisposable> _subs = new();

        private HttpWatch(Stopwatch clock, Action<Request> sink) { _clock = clock; _sink = sink; }

        public static HttpWatch Start(Stopwatch clock, Action<Request> sink)
        {
            var watch = new HttpWatch(clock, sink);
            lock (watch._subs) watch._subs.Add(DiagnosticListener.AllListeners.Subscribe(watch));
            return watch;
        }

        public void OnNext(DiagnosticListener listener)
        {
            if (listener.Name == "HttpHandlerDiagnosticListener")
                lock (_subs) _subs.Add(listener.Subscribe(this));
        }

        public void OnNext(KeyValuePair<string, object?> e)
        {
            var payload = e.Value;
            if (payload == null) return;
            var request = payload.GetType().GetProperty("Request")?.GetValue(payload) as HttpRequestMessage;
            if (request == null) return;
            if (e.Key == "System.Net.Http.HttpRequestOut.Start")
            {
                lock (_open) _open[request] = _clock.ElapsedMilliseconds;
            }
            else if (e.Key == "System.Net.Http.HttpRequestOut.Stop")
            {
                long start;
                lock (_open)
                {
                    if (!_open.Remove(request, out start)) return;
                }
                var response = payload.GetType().GetProperty("Response")?.GetValue(payload) as HttpResponseMessage;
                _sink(new Request(request.RequestUri?.ToString() ?? "?", start,
                    _clock.ElapsedMilliseconds - start, response == null ? 0 : (int)response.StatusCode));
            }
        }

        public void OnCompleted() { }
        public void OnError(Exception error) { }

        public void Dispose()
        {
            lock (_subs) foreach (var s in _subs) s.Dispose();
        }
    }
}
