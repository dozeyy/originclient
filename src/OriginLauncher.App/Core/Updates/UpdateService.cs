using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Net.Http.Json;
using System.Reflection;
using System.Text.Json.Serialization;
using System.Windows;
using OriginLauncher.App.Core.Import.Launcher;

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
// HomePage enforces that via UpdateRequired. Three deliberate softenings:
//  - A download that fails its integrity check (UpdateVerifier) never blocks play: the update is ungated,
//    the file discarded, and it is retried quietly with back-off (UpdateGate); only a verified one can be forced.
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
    // The release pipeline publishes the zip's SHA-256 next to it (sha256sum format); see UpdateVerifier.
    private const string HashAssetName = AssetName + ".sha256";
    // A stalled connection (no bytes for this long) aborts the download so the player can retry.
    private static readonly TimeSpan StallTimeout = TimeSpan.FromSeconds(60);

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
    public static bool UpdateRequired { get { lock (Gate) return Gate.Required(IsDevBuild); } }

    // The gate's state machine (Core/Updates/UpdateGate.cs). Always touched under lock (Gate): the poll loop,
    // the focus check, Play and the badge click all reach it from different continuations.
    private static readonly UpdateGate Gate = new();
    // The version whose zip sits verified on disk from a quiet retry (the badge click then skips the download).
    private static Version? _verifiedZipVersion;

    /// <summary>Raised (possibly off the UI thread) the first time a given newer release is seen.</summary>
    public static event EventHandler? AvailableChanged;

    /// <summary>
    /// Polls the release feed once. Never throws: an unreachable feed fails
    /// open (see policy note above).
    /// </summary>
    public static async Task CheckAsync()
    {
        // Once per process, off the UI thread, before any download of this run can start.
        if (Interlocked.Exchange(ref _startupCleaned, 1) == 0)
            await Task.Run(() => CleanStaleDownloads(null)).ConfigureAwait(false);

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

            UpdateGate.Decision decision;
            lock (Gate) decision = Gate.Evaluate(remote, CurrentVersion, DateTime.UtcNow);
            if (decision is UpdateGate.Decision.Skip or UpdateGate.Decision.Wait)
                return;

            var hashAsset = release.Assets?.FirstOrDefault(a =>
                string.Equals(a.Name, HashAssetName, StringComparison.OrdinalIgnoreCase));
            var update = new AvailableUpdate(remote, asset.DownloadUrl, hashAsset?.DownloadUrl, asset.Digest);
            if (decision == UpdateGate.Decision.Quiet)
            {
                // An earlier download of this release failed its check: never put it in front of the player
                // (or gate Play on it) again until it has been fetched and verified in the background.
                _ = QuietRetryAsync(update);
                return;
            }
            TryPublish(update);
        }
        catch
        {
            // Fail open: offline / rate-limited / repo not public. The next
            // poll retries; play is never blocked on a failed check.
        }
    }

    // False (and nothing changes) when this version or a newer one is already offered: a late finisher
    // (a quiet retry of X completing after Y was offered) must never overwrite the newer offer.
    private static bool TryPublish(AvailableUpdate update)
    {
        lock (Gate)
        {
            if (!Gate.TryPublish(update.Version)) return false;
            Available = update;
        }
        AvailableChanged?.Invoke(null, EventArgs.Empty);
        return true;
    }

    // One zip (and one .part) per version, so two downloads of different releases never share a file.
    private const string ZipPrefix = "OriginLauncher-win-x64";
    private static string ZipPathFor(Version version) =>
        Path.Combine(OriginPaths.Root, "updates", $"{ZipPrefix}-{version}.zip");

    // Removes zips/.part files of versions nobody is fetching or offering (quit before applying, killed
    // mid-download). Never throws; a locked file is skipped. Keeps the version being fetched, the one offered
    // and the one verified on disk.
    private static void CleanStaleDownloads(Version? fetching)
    {
        try
        {
            Version? offered, verified;
            lock (Gate) { offered = Available?.Version; verified = _verifiedZipVersion; }
            var n = UpdateVerifier.DeleteStaleDownloads(
                Path.Combine(OriginPaths.Root, "updates"), ZipPrefix, fetching, offered, verified);
            if (n > 0) Log($"removed {n} stale update download file(s)");
        }
        catch (Exception) { /* housekeeping only */ }
    }

    private static int _startupCleaned;

    // Quiet retry after a failed check (see UpdateGate): download + verify with no UI, publish only on success.
    // Any failure just extends the back-off; nothing is shown and Play is never touched.
    private static async Task QuietRetryAsync(AvailableUpdate update)
    {
        try
        {
            var zipPath = ZipPathFor(update.Version);
            Directory.CreateDirectory(Path.GetDirectoryName(zipPath)!);
            CleanStaleDownloads(update.Version);
            await DownloadVerifiedAsync(update, zipPath, null).ConfigureAwait(false);
            lock (Gate) Gate.Verified(update.Version);
            if (TryPublish(update))
            {
                _verifiedZipVersion = update.Version;
                Log($"update {update.Version}: quiet retry verified, offering it now");
            }
            else
            {
                // A newer release was offered while this one downloaded: drop the now-useless zip.
                Log($"update {update.Version}: quiet retry verified but a newer release is already offered; dropped");
                try { File.Delete(zipPath); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
        }
        catch (Exception)
        {
            // DownloadVerifiedAsync already logged why.
            lock (Gate) Gate.VerifyFailed(update.Version, DateTime.UtcNow);
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

        // From here until the process is gone no import may open or apply (Core/Import/Launcher/UpdateRunState):
        // the restart below would kill it half done. A failed update releases it; a successful one never does.
        var run = UpdateRunState.Enter();
        var restarting = false;
        try
        {
            await DownloadStageAndRestartAsync(update, progress, () => restarting = true);
        }
        catch (Exception ex)
        {
            // Mandate 4 (never broken): no failure here may block play for good. A failed integrity check
            // ungates at once; any other failure (antivirus on the zip, disk full, extract/stage errors, a
            // stalled connection, HTTP errors) ungates on the second one in a row (UpdateGate). Either way
            // the retry is quiet with back-off. When this ungated Play, Available is null on return, which
            // is how the caller knows to show nothing blocking.
            bool ungated;
            lock (Gate)
            {
                ungated = ex is UpdateVerificationException
                    ? Gate.VerifyFailed(update.Version, DateTime.UtcNow)
                    : Gate.DownloadFailed(update.Version, DateTime.UtcNow);
                if (ungated) Available = null;
            }
            Log($"update {update.Version}: {(ungated ? "gate released, retrying quietly later" : "failed once, Play still gated until the next failure")}");
            throw;
        }
        finally
        {
            if (!restarting) run.Dispose();
        }
    }

    private static async Task DownloadStageAndRestartAsync(AvailableUpdate update, IProgress<UpdateProgress>? progress, Action markRestarting)
    {
        var updatesRoot = Path.Combine(OriginPaths.Root, "updates");
        Directory.CreateDirectory(updatesRoot);
        CleanStaleDownloads(update.Version);
        var stageDir = Path.Combine(updatesRoot, update.Version.ToString());
        if (Directory.Exists(stageDir))
            Directory.Delete(stageDir, recursive: true);
        Directory.CreateDirectory(stageDir);

        progress?.Report(new UpdateProgress(UpdateStage.Downloading, 0, null));
        var zipPath = ZipPathFor(update.Version);
        // A quiet retry may already have fetched and verified this very zip.
        if (_verifiedZipVersion != update.Version || !File.Exists(zipPath))
            await DownloadVerifiedAsync(update, zipPath, progress);
        _verifiedZipVersion = null;

        progress?.Report(new UpdateProgress(UpdateStage.Installing, 0, null));
        // Off the UI thread so the loading scene keeps animating while ~126 MB
        // unpacks; the continuation returns here (UI thread) for Shutdown().
        await Task.Run(() =>
        {
            ZipFile.ExtractToDirectory(zipPath, stageDir, overwriteFiles: true);
            File.Delete(zipPath);
        });

        // An import may have been started while this downloaded (or still be running from before): the restart
        // waits for it, then shuts down on this same UI-thread turn, so none can begin in between.
        await UpdateRunState.PrepareToRestartAsync(() => progress?.Report(new UpdateProgress(UpdateStage.FinishingImport, 0, null)));

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
        markRestarting();
        Application.Current.Shutdown();
    }

    // Fetches the expected SHA-256, downloads the zip and checks it (UpdateVerifier policy: refuse a
    // mismatch or a short file, accept a release that published no hash). The zip only ever appears at
    // zipPath once verified: it is written as zipPath.part and every failure path deletes that. Any
    // failure throws; MainWindow shows "Update failed ... click to retry", and nothing was extracted
    // or applied.
    // The byte loop runs off the UI thread (ConfigureAwait(false)); progress still lands on the UI
    // thread because Progress<T> captured it. Reports are capped at ~10/s (each is a dispatcher hop).
    private static async Task DownloadVerifiedAsync(AvailableUpdate update, string zipPath, IProgress<UpdateProgress>? progress)
    {
        var partPath = zipPath + ".part";
        try
        {
            var expected = await ResolveExpectedHashAsync(update).ConfigureAwait(false);

            using var stall = new CancellationTokenSource(StallTimeout);
            using var response = await Http.GetAsync(update.DownloadUrl, HttpCompletionOption.ResponseHeadersRead, stall.Token).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            var total = response.Content.Headers.ContentLength;
            await using var source = await response.Content.ReadAsStreamAsync(stall.Token).ConfigureAwait(false);

            var lastReport = Stopwatch.StartNew();
            UpdateVerdict verdict;
            try
            {
                verdict = await UpdateVerifier.CopyAndVerifyAsync(source, partPath, total, expected, received =>
                {
                    stall.CancelAfter(StallTimeout); // bytes are flowing: restart the stall clock
                    if (lastReport.ElapsedMilliseconds >= 100)
                    {
                        lastReport.Restart();
                        progress?.Report(new UpdateProgress(UpdateStage.Downloading, received, total));
                    }
                }, stall.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                throw new IOException("the connection stalled");
            }

            switch (verdict)
            {
                case UpdateVerdict.Mismatch:
                    throw new UpdateVerificationException("the download was damaged (it failed its integrity check)");
                case UpdateVerdict.Truncated:
                    throw new UpdateVerificationException("the download was cut short");
                case UpdateVerdict.OkUnverified:
                    Log($"update {update.Version}: release published no SHA-256, applying unverified (older release)");
                    break;
            }
            File.Move(partPath, zipPath, overwrite: true);
            progress?.Report(new UpdateProgress(UpdateStage.Downloading, total ?? 0, total ?? 0));
        }
        catch (Exception ex)
        {
            try { File.Delete(partPath); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            Log($"update {update.Version}: not applied, will retry later: {ex.GetType().Name}: {ex.Message}");
            throw;
        }
    }

    // The sidecar .sha256 asset first, then the release API's own asset digest, else null (older release).
    // A SIDECAR that is listed but unreadable or malformed is a verification failure, never "no hash": an
    // update we cannot verify is not applied (and, like any verification failure, never blocks play).
    private static async Task<string?> ResolveExpectedHashAsync(AvailableUpdate update)
    {
        if (update.Sha256Url != null)
        {
            // 404/403, a timeout, a reset or a garbled body all surface as UpdateVerificationException.
            return await UpdateVerifier.FetchSidecarAsync(
                ct => Http.GetStringAsync(update.Sha256Url, ct), AssetName, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
        }
        // GitHub's own digest is only a fallback and only ever helps: anything but a clean "sha256:<64 hex>"
        // (another algorithm, empty, missing, a format change) is "no hash", never a mismatch.
        return UpdateVerifier.ParseDigest(update.DigestSha256);
    }

    private static readonly object LogGate = new();

    private static void Log(string message)
    {
        try
        {
            lock (LogGate)
            {
                Directory.CreateDirectory(OriginPaths.Logs);
                var path = Path.Combine(OriginPaths.Logs, "update.log");
                if (File.Exists(path) && new FileInfo(path).Length > 256 * 1024) File.Delete(path);
                File.AppendAllText(path, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}{Environment.NewLine}");
            }
        }
        catch (Exception)
        {
            // a courtesy
        }
    }

    public sealed record AvailableUpdate(Version Version, string DownloadUrl, string? Sha256Url = null, string? DigestSha256 = null);

    public enum UpdateStage { Downloading, Installing, Restarting, FinishingImport }

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
            UpdateStage.FinishingImport => "Finishing your import first...",
            _ => "Restarting"
        };

        private static string Mb(long bytes) => (bytes / 1_048_576d).ToString("0");
    }

    private sealed record Release(
        [property: JsonPropertyName("tag_name")] string? TagName,
        [property: JsonPropertyName("assets")] List<ReleaseAsset>? Assets);

    private sealed record ReleaseAsset(
        [property: JsonPropertyName("name")] string? Name,
        [property: JsonPropertyName("browser_download_url")] string? DownloadUrl,
        [property: JsonPropertyName("digest")] string? Digest = null);
}
