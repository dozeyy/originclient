using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Net.Http.Json;
using System.Reflection;
using System.Text.Json.Serialization;
using System.Windows;

namespace OriginLauncher.App.Core.Updates;

// Push-to-update pipeline, launcher side. The GitHub Actions workflow
// (.github/workflows/launcher-release.yml) publishes a release with a
// OriginLauncher-win-x64.zip asset whenever a launcher-v* tag is pushed to
// main (see docs/RELEASING.md).
// This service polls that feed, exposes the newest release, and applies it:
// download -> stage -> swap-and-restart via a tiny cmd script (a running exe
// can't overwrite itself on Windows).
//
// Update policy (Will): updates are MANDATORY — a launcher older than the
// latest published release must update before it can launch the game.
// HomePage enforces that via UpdateRequired. Two deliberate softenings:
//  - If the feed is unreachable (offline, rate-limited), the check fails
//    OPEN: we can't tell "no update" from "no internet", and blocking all
//    offline play on a feed hiccup would be worse than a stale client.
//  - Dev builds (assembly version 1.0.0.0, i.e. anything not stamped by CI's
//    -p:Version) see the badge but skip the mandatory gate, otherwise every
//    local F5 run would be blocked by the newest release.
//
// NOTE: the unauthenticated releases API only works on a PUBLIC repository —
// on a private repo it 404s and every check fails open (no updates ever
// surface). Keep the repo public or move the release feed somewhere public.
public static class UpdateService
{
    private const string Owner = "dozeyy";
    private const string Repo = "originclient";
    private const string TagPrefix = "launcher-v";
    private const string AssetName = "OriginLauncher-win-x64.zip";

    private static readonly Version DevVersion = new(1, 0, 0, 0);
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var http = new HttpClient();
        // GitHub's API rejects requests without a User-Agent.
        http.DefaultRequestHeaders.UserAgent.ParseAdd("OriginLauncher/1.0 (+will@willhenry.me)");
        return http;
    }

    public static Version CurrentVersion { get; } =
        Assembly.GetExecutingAssembly().GetName().Version ?? DevVersion;

    /// <summary>True for local builds not stamped by CI (-p:Version).</summary>
    public static bool IsDevBuild => CurrentVersion == DevVersion;

    /// <summary>The newest release above the running version, once a check has found one.</summary>
    public static AvailableUpdate? Available { get; private set; }

    /// <summary>Mandatory-update gate: true when a newer release is known and this is a CI-stamped build.</summary>
    public static bool UpdateRequired => Available != null && !IsDevBuild;

    /// <summary>Raised (possibly off the UI thread) the first time a given newer release is seen.</summary>
    public static event EventHandler? AvailableChanged;

    /// <summary>
    /// Polls the release feed once. Never throws: an unreachable feed fails
    /// open (see policy note above).
    /// </summary>
    public static async Task CheckAsync()
    {
        try
        {
            var release = await Http.GetFromJsonAsync<Release>(
                $"https://api.github.com/repos/{Owner}/{Repo}/releases/latest");

            var tag = release?.TagName;
            if (tag == null || !tag.StartsWith(TagPrefix, StringComparison.OrdinalIgnoreCase))
                return;
            if (!Version.TryParse(tag[TagPrefix.Length..], out var remote))
                return;

            var asset = release!.Assets?.FirstOrDefault(a =>
                string.Equals(a.Name, AssetName, StringComparison.OrdinalIgnoreCase));
            if (asset?.DownloadUrl == null)
                return;

            if (Normalize(remote) <= Normalize(CurrentVersion))
                return;
            if (Available != null && Normalize(Available.Version) >= Normalize(remote))
                return;

            Available = new AvailableUpdate(remote, asset.DownloadUrl);
            AvailableChanged?.Invoke(null, EventArgs.Empty);
        }
        catch
        {
            // Fail open: offline / rate-limited / repo not public. The next
            // poll retries; play is never blocked on a failed check.
        }
    }

    /// <summary>
    /// Downloads the available update, stages it, spawns the swap script, and
    /// shuts the app down; the script waits for this process to exit, copies
    /// the staged files over the install directory, and relaunches.
    /// Progress is reported in bytes so the player sees a real fill, not a
    /// spinner, through the ~126 MB download.
    /// </summary>
    public static async Task DownloadAndRestartAsync(IProgress<UpdateProgress>? progress = null)
    {
        var update = Available ?? throw new InvalidOperationException("No update is available.");

        var updatesRoot = Path.Combine(OriginPaths.Root, "updates");
        Directory.CreateDirectory(updatesRoot);
        var stageDir = Path.Combine(updatesRoot, update.Version.ToString());
        if (Directory.Exists(stageDir))
            Directory.Delete(stageDir, recursive: true);
        Directory.CreateDirectory(stageDir);

        progress?.Report(new UpdateProgress(UpdateStage.Downloading, 0, null));
        var zipPath = Path.Combine(updatesRoot, AssetName);
        await DownloadToFileAsync(update.DownloadUrl, zipPath, progress);

        progress?.Report(new UpdateProgress(UpdateStage.Installing, 0, null));
        // Off the UI thread so the loading scene keeps animating while ~126 MB
        // unpacks; the continuation returns here (UI thread) for Shutdown().
        await Task.Run(() =>
        {
            ZipFile.ExtractToDirectory(zipPath, stageDir, overwriteFiles: true);
            File.Delete(zipPath);
        });

        var appDir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
        var exePath = Environment.ProcessPath
                      ?? Path.Combine(appDir, "OriginLauncher.App.exe");
        var pid = Environment.ProcessId;

        // robocopy instead of xcopy: copies new + changed files including
        // ones that appeared in this release, and its success exit codes
        // (0-7) don't abort the script. Paths are pre-trimmed of trailing
        // backslashes so the quoted arguments can't swallow a quote.
        var script = Path.Combine(updatesRoot, "apply-update.cmd");
        File.WriteAllText(script, $"""
@echo off
setlocal
:wait
tasklist /FI "PID eq {pid}" 2>nul | find "{pid}" >nul
if not errorlevel 1 (
  timeout /t 1 /nobreak >nul
  goto wait
)
robocopy "{stageDir}" "{appDir}" /E /IS /IT >nul
start "" "{exePath}"
endlocal
""");

        Process.Start(new ProcessStartInfo
        {
            FileName = script,
            UseShellExecute = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            CreateNoWindow = true
        });
        progress?.Report(new UpdateProgress(UpdateStage.Restarting, 0, null));
        Application.Current.Shutdown();
    }

    // The byte loop runs off the UI thread (ConfigureAwait(false)); progress
    // still lands on the UI thread because Progress<T> captured it. Reports
    // are capped at ~10/s — each one is a dispatcher hop.
    private static async Task DownloadToFileAsync(string url, string path, IProgress<UpdateProgress>? progress)
    {
        using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        var total = response.Content.Headers.ContentLength;
        await using var source = await response.Content.ReadAsStreamAsync().ConfigureAwait(false);
        await using var file = new FileStream(path, FileMode.Create, FileAccess.Write,
            FileShare.None, bufferSize: 1 << 16, useAsync: true);

        var buffer = new byte[1 << 16];
        long received = 0;
        var lastReport = Stopwatch.StartNew();
        int read;
        while ((read = await source.ReadAsync(buffer).ConfigureAwait(false)) > 0)
        {
            await file.WriteAsync(buffer.AsMemory(0, read)).ConfigureAwait(false);
            received += read;
            if (lastReport.ElapsedMilliseconds >= 100)
            {
                lastReport.Restart();
                progress?.Report(new UpdateProgress(UpdateStage.Downloading, received, total));
            }
        }
        progress?.Report(new UpdateProgress(UpdateStage.Downloading, received, total ?? received));
    }

    // System.Version treats unset components as -1, which makes "1.0.5" and
    // "1.0.5.0" unequal; pad to four components before comparing.
    private static Version Normalize(Version v) => new(
        Math.Max(v.Major, 0), Math.Max(v.Minor, 0),
        Math.Max(v.Build, 0), Math.Max(v.Revision, 0));

    public sealed record AvailableUpdate(Version Version, string DownloadUrl);

    public enum UpdateStage { Downloading, Installing, Restarting }

    /// <summary>One progress tick. TotalBytes is null when the server sent no
    /// Content-Length (then only the byte count is known, not a fraction).</summary>
    public sealed record UpdateProgress(UpdateStage Stage, long ReceivedBytes, long? TotalBytes)
    {
        /// <summary>0..1 while downloading with a known size; null otherwise.</summary>
        public double? Fraction => Stage == UpdateStage.Downloading && TotalBytes is > 0
            ? Math.Clamp((double)ReceivedBytes / TotalBytes.Value, 0, 1)
            : null;

        /// <summary>Player-facing line, e.g. "Downloading — 42% · 53 of 126 MB".</summary>
        public string Describe() => Stage switch
        {
            UpdateStage.Downloading when Fraction is { } f =>
                $"Downloading — {f * 100:0}% · {Mb(ReceivedBytes)} of {Mb(TotalBytes!.Value)} MB",
            UpdateStage.Downloading when ReceivedBytes > 0 => $"Downloading — {Mb(ReceivedBytes)} MB",
            UpdateStage.Downloading => "Downloading",
            UpdateStage.Installing => "Installing",
            _ => "Restarting"
        };

        private static string Mb(long bytes) => (bytes / 1_048_576d).ToString("0");
    }

    private sealed record Release(
        [property: JsonPropertyName("tag_name")] string? TagName,
        [property: JsonPropertyName("assets")] List<ReleaseAsset>? Assets);

    private sealed record ReleaseAsset(
        [property: JsonPropertyName("name")] string? Name,
        [property: JsonPropertyName("browser_download_url")] string? DownloadUrl);
}
