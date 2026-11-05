using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Text.Json;
using System.Reflection;
using System.Text.Json.Serialization;
using OriginLauncher.App.Core.Import.Launcher;

namespace OriginLauncher.App.Core.Updates;

// Push-to-update pipeline, launcher side. The GitHub Actions workflow
// (.github/workflows/launcher-release.yml) publishes a release with a
// OriginLauncher-win-x64.zip asset whenever a launcher-v* tag is pushed to
// main (see docs/RELEASING.md).
// This service polls that feed, exposes the newest release, and applies it:
// download -> stage -> swap-and-restart via a tiny script (a running exe
// can't overwrite itself on Windows; a Mac swaps the whole app bundle).
// The download and the stage happen in the background the moment a release is offered (UpdateService.Background.cs,
// rules in UpdatePrefetch; DECISIONS 2026-11-03), so the Update click usually only re-checks the stage and swaps.
// The per-OS parts live in one file per OS, picked by each csproj (UpdateService.Windows.cs /
// UpdateService.Mac.cs / UpdateService.Linux.cs): which release asset this OS downloads, the swap script, and how the
// app quits (the UI toolkit's own shutdown). Checking, gating, downloading and verifying are shared.
//
// Update policy (Will): updates are MANDATORY — a launcher older than the
// latest published release must update before it can launch the game.
// HomePage enforces that via UpdateRequired. Three deliberate softenings:
//  - A download that fails its integrity check (UpdateVerifier) never blocks play: the update is ungated,
//    the file discarded, and it is retried quietly with back-off (UpdateGate). A release whose feed entry carries no
//    checksum at all (no .sha256 asset, no usable digest) is refused up front the same way, before it is offered.
//    A first sighting that does carry one is offered (and gates Play) before it is downloaded, because the
//    download is what verifies it; a failure there ungates it at once, so a bad release never blocks play twice.
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
public static partial class UpdateService
{
    private const string Owner = "dozeyy";
    private const string Repo = "originclient";
    private const string TagPrefix = "launcher-v";
    // The Windows asset. It stays named here because the installer's ReleaseFeed must agree with it
    // (tools/release/check_consistency.py reads both); each OS file says which asset that OS takes (ReleaseAssetName).
    private const string AssetName = "OriginLauncher-win-x64.zip";
    // The release pipeline publishes each zip's SHA-256 next to it as ReleaseAssetName + ".sha256"
    // (sha256sum format); see UpdateVerifier and FindRelease below.
    // A stalled connection (no bytes for this long) aborts the download so the player can retry.
    private static readonly TimeSpan StallTimeout = TimeSpan.FromSeconds(60);
    // One poll of the release feed (a small JSON answer) gives up after this.
    private static readonly TimeSpan FeedTimeout = TimeSpan.FromSeconds(20);

    private static readonly Version DevVersion = new(1, 0, 0, 0);
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        // A host that never completes the handshake fails in 10 s (NET-3). No overall Timeout here: this client also
        // downloads the update zip, which StallTimeout guards instead; the feed request has its own cap (FeedTimeout).
        var http = new HttpClient(new SocketsHttpHandler { ConnectTimeout = TimeSpan.FromSeconds(10) });
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
    // The zip that sits verified on disk from a quiet retry (the badge click then skips the download), with the
    // hash it was verified against: it is hashed again right before it is extracted (it may sit there for hours).
    private sealed record VerifiedZip(Version Version, string Sha256);
    private static VerifiedZip? _verifiedZip; // always under lock (Gate)

    /// <summary>
    /// A newer release this copy can't install where it sits (Mac: a translocated copy, or a folder it can't write to),
    /// with the player-facing reason. It never gates Play. Always null on Windows.
    /// </summary>
    public static BlockedUpdate? Blocked { get; private set; }

    // The release feed: GitHub's latest release. Only the Mac's --update-proof switch points it elsewhere (a loopback
    // fake release in CI; UpdateProof refuses any other host). Nothing a player can reach changes it.
    internal static string FeedUrl { get; set; } = $"https://api.github.com/repos/{Owner}/{Repo}/releases/latest";

    /// <summary>Raised (possibly off the UI thread) the first time a given newer release is seen, or found uninstallable here.</summary>
    public static event EventHandler? AvailableChanged;

    /// <summary>
    /// False in a screenshot run (`--render-shots`, and any other run on a scratch data root): made-up data only, so
    /// the release feed is never asked, nothing is downloaded and nothing is applied. A live check there once put the
    /// real "UPDATING ORIGIN LAUNCHER" screen into the shots. Every check and apply path below starts with this.
    /// </summary>
    public static bool Enabled => !OriginPaths.IsScratchRoot || ProofFeed;

    /// <summary>
    /// Set only by the Mac's `--update-proof` (UpdateProof.cs), after it checked the feed is a loopback IP: that run
    /// sits on a scratch root on purpose and must still check, download and swap against its fake local release.
    /// </summary>
    internal static bool ProofFeed { get; set; }

    /// <summary>
    /// Polls the release feed once. Never throws: an unreachable feed fails
    /// open (see policy note above).
    /// </summary>
    public static async Task CheckAsync()
    {
        if (!Enabled) return;

        // Once per process, off the UI thread, before any download of this run can start and before the
        // feed is first evaluated (so a failed swap is counted before anything can be offered again).
        if (Interlocked.Exchange(ref _startupCleaned, 1) == 0)
            await Task.Run(() => { CheckLastSwap(); CleanStaleDownloads(null); }).ConfigureAwait(false);

        try
        {
            // Conditional, rate-aware request (FeedPoll): a 304 doesn't count against GitHub's hourly limit, and a 403/429
            // holds further requests off until GitHub's own time while each poll re-evaluates the last answer.
            var body = await AskFeedAsync();
            if (body == null) return;
            var release = JsonSerializer.Deserialize<Release>(body, FeedJson);

            var tag = release?.TagName;
            if (tag == null || !tag.StartsWith(TagPrefix, StringComparison.OrdinalIgnoreCase))
                return;
            if (!Version.TryParse(tag[TagPrefix.Length..], out var remote))
                return;

            var asset = release!.Assets?.FirstOrDefault(a =>
                string.Equals(a.Name, ReleaseAssetName, StringComparison.OrdinalIgnoreCase));
            if (asset?.DownloadUrl == null)
                return;

            var hashAsset = release.Assets?.FirstOrDefault(a =>
                string.Equals(a.Name, ReleaseAssetName + ".sha256", StringComparison.OrdinalIgnoreCase));
            // No sidecar and no usable digest: the download could only be refused, so never offer or gate on it.
            var hasHash = hashAsset?.DownloadUrl != null || UpdateVerifier.ParseDigest(asset.Digest) != null;

            UpdateGate.Decision decision;
            lock (Gate) decision = Gate.EvaluateRelease(remote, CurrentVersion, hasHash, DateTime.UtcNow);
            if (decision == UpdateGate.Decision.Refuse)
            {
                Log($"update {remote}: release published no SHA-256, not offered; retrying quietly later");
                return;
            }
            if (decision is UpdateGate.Decision.Skip or UpdateGate.Decision.Wait)
            {
                // Already offered: a background download that waited (game running, metered, back-off) may go now.
                if (Available is { } offered && !TakeLateDelta(offered, remote, release.Assets) && Available is { } current)
                    StartBackground(current); // the offer as it is now (it may have just taken its small update)
                return;
            }

            // This copy can't replace itself where it sits (a Mac app run from a translocated copy, or from a folder this
            // user can't write to): it is never offered and never gates Play, because no click could install it. The player
            // is told instead (Blocked: move Origin to Applications) and nothing is downloaded. Every poll checks again, so a
            // start from the right folder offers it normally. Windows always answers null at once (no thread hop); the
            // Mac's write probe runs on the pool, never the UI thread, and resumes here on the caller's context.
            if (await InstallBlockedHereAsync() is { } blockedBecause)
            {
                // A Quiet decision holds the gate's one quiet slot; this ends it with the usual back-off.
                if (decision == UpdateGate.Decision.Quiet) lock (Gate) Gate.VerifyFailed(remote, DateTime.UtcNow);
                if (Blocked?.Version != remote)
                {
                    Blocked = new BlockedUpdate(remote, blockedBecause);
                    Log($"update {remote}: can't be installed here, not offered: {blockedBecause}");
                    AvailableChanged?.Invoke(null, EventArgs.Empty);
                }
                return;
            }
            if (Blocked != null)
            {
                // It can install here now (the folder became writable): the move-to-Applications chip goes away.
                Blocked = null;
                Log($"update {remote}: can be installed here again");
                AvailableChanged?.Invoke(null, EventArgs.Empty);
            }

            var update = new AvailableUpdate(remote, asset.DownloadUrl, hashAsset?.DownloadUrl, asset.Digest,
                FindDelta(release.Assets));
            if (decision == UpdateGate.Decision.Quiet)
            {
                // An earlier download of this release failed its check: never put it in front of the player
                // (or gate Play on it) again until it has been fetched and verified in the background.
                _ = QuietRetryAsync(update);
                return;
            }
            if (TryPublish(update)) StartBackground(update);
        }
        catch
        {
            // Fail open: offline / rate-limited / repo not public. The next
            // poll retries; play is never blocked on a failed check.
        }
    }

    // The release's small update was uploaded after this version was offered (UPG-3, UpdatePrefetch.MayTakeLateDelta):
    // the offer takes it, so the background download and the click fetch only the changed files. A full-zip download
    // already running for it is stopped; the next poll starts it again from the delta (not right here: the stopped
    // download deletes its stage on the way out and must be gone first). True when one was stopped.
    private static bool TakeLateDelta(AvailableUpdate offered, Version remote, List<ReleaseAsset>? assets)
    {
        if (offered.Delta != null || !UpdatePrefetch.Same(offered.Version, remote)) return false;
        if (FindDelta(assets) is not { } late) return false;
        Fetch? stop = null;
        lock (Gate)
        {
            if (!ReferenceEquals(Available, offered) || !Prefetch.MayTakeLateDelta(remote)) return false;
            Available = offered with { Delta = late };
            if (_fetch != null && UpdatePrefetch.Same(_fetch.Version, remote)) stop = _fetch;
        }
        Log($"update {remote}: its small update appeared after it was offered; using it" +
            (stop != null ? " (the full download stops and restarts from it)" : ""));
        stop?.Cancel.Cancel();
        return stop != null;
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

    // The small update (Core/Updates/UpdateDelta.cs) for exactly this installed version, when this OS takes deltas
    // (DeltaUpdates, per OS file) and the release has one with a hash (its .sha256 sidecar, or GitHub's digest). A
    // delta without a hash is simply ignored, never refused: the full zip is still there and is used instead.
    // Dev builds (1.0.0.0) never match a published delta.
    private static ReleaseFile? FindDelta(List<ReleaseAsset>? assets)
    {
        if (!DeltaUpdates || assets == null || IsDevBuild) return null;
        var delta = assets.FirstOrDefault(a => UpdateDelta.IsDeltaFor(a.Name, ReleaseZipPrefix, CurrentVersion));
        if (delta?.Name == null || delta.DownloadUrl == null) return null;
        var hash = assets.FirstOrDefault(a => string.Equals(a.Name, delta.Name + ".sha256", StringComparison.OrdinalIgnoreCase));
        if (hash?.DownloadUrl == null && UpdateVerifier.ParseDigest(delta.Digest) == null) return null;
        return new ReleaseFile(delta.Name, delta.DownloadUrl, hash?.DownloadUrl, delta.Digest);
    }

    // The full release zip of an offered update, in the same shape as a delta so one download path serves both.
    private static ReleaseFile FullZip(AvailableUpdate update) =>
        new(ReleaseAssetName, update.DownloadUrl, update.Sha256Url, update.DigestSha256);

    // One zip (and one .part) per version, so two downloads of different releases never share a file.
    private static string ZipPathFor(Version version) =>
        Path.Combine(OriginPaths.Updates, $"{ReleaseZipPrefix}-{version}.zip");

    // The small update's download (UpdateVerifier.DeleteStaleDownloads knows this name too). It only lives while
    // TryStageFromDeltaAsync runs; a kill mid-way leaves it for the stale-download cleanup.
    private static string DeltaPathFor(Version version) =>
        Path.Combine(OriginPaths.Updates, $"{ReleaseZipPrefix}-{version}.delta.zip");

    // The installed launcher folder the swap script copies the stage over (UpdateService.Windows.cs), and the folder a
    // delta copies the unchanged files from.
    private static string InstalledAppDir => AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);

    // Removes zips/.part files of versions nobody is fetching or offering (quit before applying, killed
    // mid-download) and their stage folders. Never throws; a locked file is skipped. Keeps the version being fetched,
    // the one offered, the one verified on disk, and the background download's / the click's (UpdatePrefetch.Keep).
    private static void CleanStaleDownloads(Version? fetching)
    {
        try
        {
            Version?[] keep;
            lock (Gate) keep = [fetching, Available?.Version, _verifiedZip?.Version, .. Prefetch.Keep()];
            var n = UpdateVerifier.DeleteStaleDownloads(OriginPaths.Updates, ReleaseZipPrefix, keep);
            // The stage folders too: a superseded background stage, or the one the last swap already copied from.
            n += UpdateVerifier.DeleteStaleStages(OriginPaths.Updates, keep);
            if (n > 0) Log($"removed {n} stale update download file(s) or stage folder(s)");
        }
        catch (Exception) { /* housekeeping only */ }
    }

    private static int _startupCleaned;

    // Did the swap the last start quit for take effect (Core/Updates/SwapAttempt)? Still older = it failed after
    // the quit: release Play now, retry quietly later.
    private static void CheckLastSwap()
    {
        var outcome = SwapAttempt.Check(OriginPaths.UpdateSwapMarker, CurrentVersion, out var attempted);
        if (outcome == SwapAttempt.Outcome.Failed && attempted != null)
        {
            lock (Gate)
            {
                Gate.SwapFailed(attempted, DateTime.UtcNow);
                // A check that raced ahead of this one may already have offered it: take that offer back too.
                if (Available != null && UpdateGate.Normalize(Available.Version) <= UpdateGate.Normalize(attempted)) Available = null;
            }
            Log($"update {attempted}: the swap after the last restart did not take effect (still {CurrentVersion}); Play stays open, retrying quietly later");
        }
        else if (outcome == SwapAttempt.Outcome.Applied)
        {
            Log($"update: now running {CurrentVersion}");
        }
    }

    // Quiet retry after a failed check (see UpdateGate): download + verify with no UI, publish only on success.
    // Any failure just extends the back-off; nothing is shown and Play is never touched.
    private static async Task QuietRetryAsync(AvailableUpdate update)
    {
        if (!Enabled) return;
        try
        {
            var zipPath = ZipPathFor(update.Version);
            Directory.CreateDirectory(Path.GetDirectoryName(zipPath)!);
            CleanStaleDownloads(update.Version);
            // The full zip only: this quiet path runs after a failed verification, and the zip it verifies is reused
            // by the badge click below without any download. (A click with nothing verified on disk tries the delta.)
            var sha256 = await DownloadVerifiedAsync(update.Version, FullZip(update), zipPath, null, CancellationToken.None).ConfigureAwait(false);
            lock (Gate) Gate.Verified(update.Version);
            if (TryPublish(update))
            {
                lock (Gate) _verifiedZip = new VerifiedZip(update.Version, sha256);
                Log($"update {update.Version}: quiet retry verified, offering it now");
                StartBackground(update); // stages it from the zip just verified, so the click only swaps
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
    /// <paramref name="stopWaiting"/> is the player's Cancel while the swap waits for Minecraft to close
    /// (UpdateStage.WaitingForGame): it throws OperationCanceledException, which is NOT a failure (the gate counts
    /// nothing) and keeps the verified stage, so the next Update click only re-checks it and swaps.
    /// <paramref name="playAfter"/> is the Play a required update refused ("Update and play"): noted right before the
    /// quit, and the next start launches it (PlayAfterUpdate; TakePlayAfterUpdate). Null for the top-bar chip.
    /// </summary>
    public static async Task DownloadAndRestartAsync(IProgress<UpdateProgress>? progress = null,
        CancellationToken stopWaiting = default, PlayAfterUpdate? playAfter = null)
    {
        if (!Enabled) throw new InvalidOperationException("Updates are off in a screenshot run.");
        var update = Available ?? throw new InvalidOperationException("No update is available.");

        // From here until the process is gone no import may open or apply (Core/Import/Launcher/UpdateRunState):
        // the restart below would kill it half done. A failed update releases it; a successful one never does.
        var run = UpdateRunState.Enter();
        var restarting = false;
        try
        {
            await DownloadStageAndRestartAsync(update, progress, () => restarting = true, stopWaiting, playAfter);
        }
        catch (OperationCanceledException) when (stopWaiting.IsCancellationRequested)
        {
            // The player went back to Home while a game was still open: postponed, not failed (DownloadStageAndRestart
            // already handed the stage back). Play stays gated exactly as before the click.
            throw;
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
                Prefetch.InstallFailed(update.Version); // background downloads and cleanup may touch it again
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

    private static async Task DownloadStageAndRestartAsync(AvailableUpdate update, IProgress<UpdateProgress>? progress, Action markRestarting,
        CancellationToken stopWaiting, PlayAfterUpdate? playAfter)
    {
        var updatesRoot = OriginPaths.Updates;
        Directory.CreateDirectory(updatesRoot);
        var stageDir = StageDirFor(update.Version);

        // Usually the background download already staged it (or is still on it: the click joins that one). Otherwise
        // the click stages it itself exactly as before, and records that stage the same way.
        var snapshot = await TakeBackgroundStageAsync(update, progress);
        for (var attempt = 1; ; attempt++)
        {
            if (snapshot == null)
            {
                await StageAsync(update, stageDir, progress, CancellationToken.None);
                snapshot = await Task.Run(() => StageSnapshot.Take(stageDir));
            }

            // Never swap under a running game this launcher started: the restart would leave it without its crash window
            // and settings sync. The player sees "Waiting for Minecraft to close"; the swap follows the moment it does.
            try
            {
                await WaitForGamesToCloseAsync(progress, stopWaiting);
            }
            catch (OperationCanceledException) when (stopWaiting.IsCancellationRequested)
            {
                // Cancel while waiting: the verified stage stays, recorded as if the background had staged it.
                lock (Gate)
                {
                    Prefetch.InstallPostponed(update.Version);
                    _stagedSnapshot = snapshot;
                }
                Log($"update {update.Version}: the player stopped waiting for Minecraft to close; the update stays ready");
                throw;
            }

            // The stage is re-hashed AFTER the wait (it can last hours), right before the swap: a file an antivirus
            // removed or anything else that changed meanwhile must never reach the install folder half old, half new.
            progress?.Report(new UpdateProgress(UpdateStage.Installing, 0, null));
            var matches = await Task.Run(() => StageSnapshot.Matches(stageDir, snapshot));
            var step = UpdatePrefetch.CheckBeforeSwap(matches, attempt);
            if (step == UpdatePrefetch.PreSwap.Swap) break;
            if (step == UpdatePrefetch.PreSwap.Fail)
                throw new IOException("the staged update kept changing on disk");
            Log($"update {update.Version}: the staged update changed on disk before the swap; downloading it again");
            ForgetBackgroundStage(update.Version);
            snapshot = null;
        }

        // An import may have been started while this downloaded (or still be running from before): the restart
        // waits for it, then shuts down on this same UI-thread turn, so none can begin in between.
        await UpdateRunState.PrepareToRestartAsync(() => progress?.Report(new UpdateProgress(UpdateStage.FinishingImport, 0, null)));

        // Remembered before the swap can start: if it fails after this process is gone, the next start knows
        // (SwapAttempt) and opens Play instead of gating it on the same update again.
        var marker = OriginPaths.UpdateSwapMarker;
        SwapAttempt.Record(marker, update.Version);
        // "Update and play": the next start continues the refused Play. Best effort: a note that can't be written only
        // costs the player one more click on Play.
        var playMarker = OriginPaths.PlayAfterUpdateMarker;
        if (playAfter != null)
        {
            try { PlayAfterUpdateMarker.Record(playMarker, playAfter, update.Version, DateTime.UtcNow); }
            catch (Exception ex) { Log($"update {update.Version}: couldn't note the Play to continue ({ex.Message})"); }
        }
        // The OS file writes and starts the swap script, which waits for this process to exit.
        try
        {
            StartApplyScript(stageDir, updatesRoot);
        }
        catch
        {
            SwapAttempt.Clear(marker); // no swap was started: this is an ordinary failure of this run
            PlayAfterUpdateMarker.Clear(playMarker);
            throw;
        }
        progress?.Report(new UpdateProgress(UpdateStage.Restarting, 0, null));
        markRestarting();
        ShutdownApp();
    }

    /// <summary>
    /// On start: the Play that "Update and play" left for this launcher, or null (none, stale, or the swap failed).
    /// Removes the note either way. Never in a screenshot or proof run (Enabled), which must not start a game.
    /// </summary>
    public static PlayAfterUpdate? TakePlayAfterUpdate() =>
        Enabled && !OriginPaths.IsScratchRoot
            ? PlayAfterUpdateMarker.Take(OriginPaths.PlayAfterUpdateMarker, CurrentVersion, DateTime.UtcNow)
            : null;

    // One stage folder per version (UpdateVerifier.DeleteStaleStages knows this shape).
    private static string StageDirFor(Version version) => Path.Combine(OriginPaths.Updates, version.ToString());

    // Fills stageDir with the new release, verified, for the click or the background download. Three ways, in order:
    //  1. A quiet retry already fetched and verified this very zip, possibly hours ago: hash it again before it is
    //     extracted. A swapped or damaged file is deleted and fails like any bad download.
    //  2. The small update (delta) for this installed version, when the release has one. It either stages a fully
    //     verified copy of the new release or answers false with the stage emptied; it never throws (except to cancel).
    //  3. The full zip, downloaded and verified exactly as before. Only this path's failures reach the gate.
    // Throws on any failure (cancelled: OperationCanceledException); the caller decides what that failure means.
    // Holds the version's stage lock throughout, so an earlier decision to delete this folder (TryDeleteStage) either
    // finished before or re-checks after and leaves it alone.
    private static async Task StageAsync(AvailableUpdate update, string stageDir, IProgress<UpdateProgress>? progress, CancellationToken ct)
    {
        var stageLock = StageLock(update.Version);
        await stageLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            await FillStageAsync(update, stageDir, progress, ct).ConfigureAwait(false);
        }
        finally
        {
            stageLock.Release();
        }
    }

    private static async Task FillStageAsync(AvailableUpdate update, string stageDir, IProgress<UpdateProgress>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(OriginPaths.Updates);
        CleanStaleDownloads(update.Version);
        if (Directory.Exists(stageDir))
            Directory.Delete(stageDir, recursive: true);
        Directory.CreateDirectory(stageDir);

        progress?.Report(new UpdateProgress(UpdateStage.Downloading, 0, null));
        var zipPath = ZipPathFor(update.Version);
        VerifiedZip? verifiedEarlier;
        lock (Gate) { verifiedEarlier = _verifiedZip; _verifiedZip = null; }
        var stagedFromDelta = false;
        if (verifiedEarlier?.Version == update.Version && File.Exists(zipPath))
            await UpdateVerifier.EnsureFileMatchesAsync(zipPath, verifiedEarlier.Sha256, ct).ConfigureAwait(false);
        else if (await TryStageFromDeltaAsync(update, stageDir, progress, ct).ConfigureAwait(false))
            stagedFromDelta = true;
        else
            await DownloadVerifiedAsync(update.Version, FullZip(update), zipPath, progress, ct).ConfigureAwait(false);

        progress?.Report(new UpdateProgress(UpdateStage.Installing, 0, null));
        // Off the UI thread so the loading scene keeps animating while ~250 MB unpacks.
        if (!stagedFromDelta)
        {
            ct.ThrowIfCancellationRequested();
            await Task.Run(() =>
            {
                // Linux stages an AppImage / package download its own way; everything else is a zip, unpacked per OS
                // (a Mac .app needs its symlinks and exec bits kept).
                var staged = false;
                StageReleaseOverride(zipPath, stageDir, ref staged);
                if (!staged) ExtractUpdate(zipPath, stageDir);
                File.Delete(zipPath);
            }).ConfigureAwait(false);
        }
    }

    // A release that is not a zip stages itself here (Linux: the tar.gz or the AppImage, UpdateService.Linux.cs).
    // Windows and the Mac don't implement it, so for them the call compiles away and the zip is extracted.
    static partial void StageReleaseOverride(string download, string stageDir, ref bool staged);

    // The small update (Core/Updates/UpdateDelta.cs). True when stageDir now holds the new release with every file
    // checked against the delta's manifest. Every failure on the way (no delta in this release, the download, its
    // SHA-256, a file that drifted in the installed folder, a delta made for another version) is logged and answers
    // false with the stage emptied, and the caller downloads the full zip exactly as it always did. So a delta can
    // make an update faster, never make it fail, and never counts against the update gate.
    private static async Task<bool> TryStageFromDeltaAsync(AvailableUpdate update, string stageDir, IProgress<UpdateProgress>? progress, CancellationToken ct)
    {
        if (update.Delta is not { } delta) return false;
        var deltaPath = DeltaPathFor(update.Version);
        try
        {
            try
            {
                await DownloadVerifiedAsync(update.Version, delta, deltaPath, progress, ct).ConfigureAwait(false);
            }
            catch (Exception) when (!ct.IsCancellationRequested)
            {
                // DownloadVerifiedAsync logged why and deleted its .part; nothing was staged yet.
                Log($"update {update.Version}: downloading the full update instead");
                return false;
            }

            progress?.Report(new UpdateProgress(UpdateStage.Installing, 0, null));
            var installedDir = InstalledAppDir;
            var from = CurrentVersion;
            var failure = "";
            var staged = await Task.Run(() =>
                UpdateDelta.TryStage(deltaPath, installedDir, stageDir, from, update.Version, out failure)).ConfigureAwait(false);
            Log(staged
                ? $"update {update.Version}: staged from {delta.AssetName}, every file verified"
                : $"update {update.Version}: {delta.AssetName} not used ({failure}); downloading the full update instead");
            return staged;
        }
        finally
        {
            try { File.Delete(deltaPath); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }

    // Fetches the expected SHA-256, downloads the file (the full zip or a delta) and checks it (UpdateVerifier policy:
    // refuse a mismatch, a short file or a release that published no hash). Returns the SHA-256 it verified against.
    // The file only ever appears at path once verified: it is written as path.part and every failure path deletes
    // that. Any failure throws; for the full zip MainWindow shows "Update failed ... click to retry", and nothing was
    // extracted or applied (a delta's failure is caught by TryStageFromDeltaAsync).
    // The byte loop runs off the UI thread (ConfigureAwait(false)); progress still lands on the UI
    // thread because Progress<T> captured it. Reports are capped at ~10/s (each is a dispatcher hop).
    // ct stops it on purpose (the launcher is closing, a game is starting, a newer release superseded it): that throws
    // OperationCanceledException, never the "stalled" IOException, so the caller can tell the two apart.
    private static async Task<string> DownloadVerifiedAsync(Version version, ReleaseFile file, string path, IProgress<UpdateProgress>? progress, CancellationToken ct)
    {
        var partPath = path + ".part";
        try
        {
            ct.ThrowIfCancellationRequested();
            var expected = await ResolveExpectedHashAsync(file).ConfigureAwait(false);
            ct.ThrowIfCancellationRequested();

            using var stall = CancellationTokenSource.CreateLinkedTokenSource(ct);
            stall.CancelAfter(StallTimeout);
            using var response = await Http.GetAsync(file.DownloadUrl, HttpCompletionOption.ResponseHeadersRead, stall.Token).ConfigureAwait(false);
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
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                throw new IOException("the connection stalled");
            }

            UpdateVerifier.ThrowIfRefused(verdict);
            File.Move(partPath, path, overwrite: true);
            progress?.Report(new UpdateProgress(UpdateStage.Downloading, total ?? 0, total ?? 0));
            return expected;
        }
        catch (Exception ex)
        {
            try { File.Delete(partPath); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            Log($"update {version}: {file.AssetName} not applied: {ex.GetType().Name}: {ex.Message}");
            throw;
        }
    }

    // The sidecar .sha256 asset first, then the release API's own asset digest. No hash at all, or a SIDECAR that is
    // listed but unreadable or malformed, is a verification failure: a file we cannot verify is not applied
    // (and, like any verification failure, never blocks play).
    private static async Task<string> ResolveExpectedHashAsync(ReleaseFile file)
    {
        if (file.Sha256Url != null)
        {
            // 404/403, a timeout, a reset or a garbled body all surface as UpdateVerificationException.
            return await UpdateVerifier.FetchSidecarAsync(
                ct => Http.GetStringAsync(file.Sha256Url, ct), file.AssetName, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
        }
        // GitHub's own digest is the fallback: anything but a clean "sha256:<64 hex>" (another algorithm, empty,
        // missing, a format change) is "no hash", which is refused before the download starts.
        return UpdateVerifier.RequireHash(UpdateVerifier.ParseDigest(file.DigestSha256));
    }

    private static readonly object LogGate = new();

    // The release feed's request state (ETag, last answer, back-off). Always under lock (Feed).
    private static readonly FeedPoll Feed = new();
    private static readonly JsonSerializerOptions FeedJson = new(JsonSerializerDefaults.Web);

    // One poll of the feed: the release JSON to evaluate (new, unchanged, or the last one while backing off), or null.
    private static async Task<string?> AskFeedAsync()
    {
        string? ifNoneMatch;
        lock (Feed)
        {
            if (!Feed.MayAsk(DateTime.UtcNow)) return Feed.Body;
            ifNoneMatch = Feed.IfNoneMatch;
        }
        using var request = new HttpRequestMessage(HttpMethod.Get, FeedUrl);
        if (ifNoneMatch != null) request.Headers.TryAddWithoutValidation("If-None-Match", ifNoneMatch);
        // Capped (NET-3): a feed that never answers fails this poll in FeedTimeout instead of HttpClient's 100 s, and
        // CheckAsync fails open as for any other failed poll.
        using var cap = new CancellationTokenSource(FeedTimeout);
        using var response = await Http.SendAsync(request, cap.Token);
        var status = (int)response.StatusCode;
        var text = status == 200 ? await response.Content.ReadAsStringAsync(cap.Token) : null;
        lock (Feed)
        {
            var body = Feed.Answered(status, response.Headers.ETag?.ToString(), text, RetryAfterOf(response),
                HeaderOf(response, "X-RateLimit-Remaining"), HeaderOf(response, "X-RateLimit-Reset"), DateTime.UtcNow);
            if (status is 403 or 429)
                Log($"update check: the release feed refused ({status}); asking again after {Feed.NotBeforeUtc:HH:mm} UTC");
            return body;
        }
    }

    private static TimeSpan? RetryAfterOf(HttpResponseMessage response)
    {
        var header = response.Headers.RetryAfter;
        if (header?.Delta is { } delta) return delta;
        if (header?.Date is { } date) return date - DateTimeOffset.UtcNow;
        return null;
    }

    private static string? HeaderOf(HttpResponseMessage response, string name) =>
        response.Headers.TryGetValues(name, out var values) ? values.FirstOrDefault() : null;

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

    public sealed record BlockedUpdate(Version Version, string Reason);

    /// <summary>
    /// An offered release: its full zip (DownloadUrl + hash) and, when the release has one for this installed version,
    /// the small update that carries only the changed files (<see cref="Delta"/>; tried first, the full zip is the fallback).
    /// </summary>
    public sealed record AvailableUpdate(Version Version, string DownloadUrl, string? Sha256Url = null, string? DigestSha256 = null,
        ReleaseFile? Delta = null);

    /// <summary>One downloadable release asset with the ways to check it: its .sha256 sidecar and/or GitHub's digest.</summary>
    public sealed record ReleaseFile(string AssetName, string DownloadUrl, string? Sha256Url, string? DigestSha256);

    public enum UpdateStage { Downloading, Installing, Restarting, FinishingImport, WaitingForGame }

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
                $"Downloading - {f * 100:0}% - {Mb(ReceivedBytes)} of {Mb(TotalBytes!.Value)} MB",
            UpdateStage.Downloading when ReceivedBytes > 0 => $"Downloading - {Mb(ReceivedBytes)} MB",
            UpdateStage.Downloading => "Downloading",
            UpdateStage.Installing => "Installing",
            UpdateStage.FinishingImport => "Finishing your import first...",
            UpdateStage.WaitingForGame => "Waiting for Minecraft to close",
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
