using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
using System.Net.Http.Json;
using System.Reflection;
using System.Security.Cryptography;
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
//
// Fast updates (Will, 2026-11-03):
//  - Background download: the moment a release is first seen it is downloaded and verified quietly (one fetch at a
//    time, a newer release supersedes an older one's fetch). Clicking Update while that runs joins it and shows its
//    progress; clicking after it finished only installs and restarts. Nothing about the badge or the Play gate changes.
//  - Changed files only (Windows): the release also publishes a file list (DeltaManifest, with its own .sha256); the
//    files that differ from this install are fetched out of the release zip with Range requests (DeltaStager) and the
//    rest is copied from the install folder, each file checked against the list's SHA-256. Any problem there falls
//    back silently to the full verified zip download below; integrity is never weaker than the full download.
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
    // A release that is downloaded and verified, ready to install: either the full zip (with the hash it was verified
    // against) or a complete stage folder built from changed files (with the file list it was checked against). One
    // that sat on disk (a background fetch can finish hours before the click) is checked again right before install.
    private sealed record Prepared(Version Version, string? ZipPath, string? ZipSha256, string? StageDir, DeltaManifest? Manifest);
    private static Prepared? _prepared; // always under lock (Gate)

    // The one download in flight (background or clicked), so a click joins it instead of starting a second one.
    // Joined = the badge click is waiting on it: the click then owns its outcome (the gate counts the failure the same
    // way as before), and nothing may cancel it. Quiet = the gate's back-off retry of a release whose check failed:
    // it is published only once this fetch has verified.
    private sealed class Fetch(AvailableUpdate update, bool quiet, bool joined)
    {
        public AvailableUpdate Update { get; } = update;
        public bool Quiet { get; } = quiet;
        public bool Joined { get; set; } = joined;
        public ProgressRelay Relay { get; } = new();
        public CancellationTokenSource Cancel { get; } = new();
        public TaskCompletionSource<Prepared> Done { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }
    private static Fetch? _fetch; // always under lock (Gate)

    // A background fetch that failed for an ordinary reason (offline, disk, a stall) is tried again on a later poll:
    // 2 min, doubling to 30 min. The player can always click Update meanwhile (a normal, counted download).
    private static Version? _backgroundFailedVersion;
    private static int _backgroundFailures;
    private static DateTime _backgroundRetryAtUtc;
    private static readonly TimeSpan BackgroundFirstRetry = TimeSpan.FromMinutes(2);
    private static readonly TimeSpan BackgroundMaxRetry = TimeSpan.FromMinutes(30);

    /// <summary>
    /// Background downloads on (the default). The Mac's `--update-proof` turns it off so its run proves the click path
    /// deterministically (a background fetch racing the proof's own click would make "bad-hash" flaky).
    /// </summary>
    internal static bool Prefetch { get; set; } = true;

    // Dev builds (1.0.0.0, a local build Will opens himself) see the badge but never download in the background: every
    // local start would otherwise pull a whole release it will never install.
    private static bool PrefetchAllowed => Enabled && Prefetch && (!IsDevBuild || ProofFeed);

    /// <summary>
    /// A newer release this copy can't install where it sits (Mac: a translocated copy, or a folder it can't write to),
    /// with the player-facing reason. It never gates Play. Always null on Windows.
    /// </summary>
    public static BlockedUpdate? Blocked { get; private set; }

    // The release feed: GitHub's latest release. Only the Mac's --update-proof switch points it elsewhere (a loopback
    // fake release in CI; UpdateProof refuses any other host). Nothing a player can reach changes it.
    internal static string FeedUrl { get; set; } = $"https://api.github.com/repos/{Owner}/{Repo}/releases/latest";

    /// <summary>Raised (possibly off the UI thread) the first time a given newer release is seen, or found uninstallable here,
    /// or when a background download that failed its check withdraws the offer (Available is null then).</summary>
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
            var release = await Http.GetFromJsonAsync<Release>(FeedUrl);

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
            // The changed-files list and its sidecar (Windows only; DeltaManifestName is null elsewhere). Both or neither:
            // a list without its hash is never used.
            string? listUrl = null, listHashUrl = null;
            if (DeltaManifestName is { } listName)
            {
                listUrl = release.Assets?.FirstOrDefault(a => string.Equals(a.Name, listName, StringComparison.OrdinalIgnoreCase))?.DownloadUrl;
                listHashUrl = release.Assets?.FirstOrDefault(a => string.Equals(a.Name, listName + ".sha256", StringComparison.OrdinalIgnoreCase))?.DownloadUrl;
                if (listUrl == null || listHashUrl == null) listUrl = listHashUrl = null;
            }

            UpdateGate.Decision decision;
            lock (Gate) decision = Gate.EvaluateRelease(remote, CurrentVersion, hasHash, DateTime.UtcNow);
            if (decision == UpdateGate.Decision.Refuse)
            {
                Log($"update {remote}: release published no SHA-256, not offered; retrying quietly later");
                return;
            }
            if (decision == UpdateGate.Decision.Skip)
            {
                // Already offered: make sure its background download is done or under way (a failed one retries here).
                if (Available is { } offered && UpdateGate.Normalize(offered.Version) == UpdateGate.Normalize(remote))
                    StartFetch(offered, quiet: false);
                return;
            }
            if (decision == UpdateGate.Decision.Wait)
                return;

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

            var update = new AvailableUpdate(remote, asset.DownloadUrl, hashAsset?.DownloadUrl, asset.Digest, listUrl, listHashUrl);
            if (decision == UpdateGate.Decision.Quiet)
            {
                // An earlier download of this release failed its check: never put it in front of the player
                // (or gate Play on it) again until it has been fetched and verified in the background. A fetch that
                // can't start now (a click is installing another release) ends the gate's quiet slot with the usual back-off.
                if (!StartFetch(update, quiet: true)) lock (Gate) Gate.VerifyFailed(remote, DateTime.UtcNow);
                return;
            }
            // First sighting: offered at once (badge, Play gate) and downloaded in the background straight away.
            if (TryPublish(update)) StartFetch(update, quiet: false);
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
    private static string ZipPathFor(Version version) =>
        Path.Combine(OriginPaths.Updates, $"{ReleaseZipPrefix}-{version}.zip");

    // Removes zips/.part files and stage folders of versions nobody is fetching, offering or holding ready (quit
    // before applying, killed mid-download, superseded, or the stage of the update this launcher already runs).
    // Never throws; a locked file is skipped.
    private static void CleanStaleDownloads(Version? fetching)
    {
        try
        {
            Version?[] keep;
            lock (Gate) keep = [fetching, _fetch?.Update.Version, Available?.Version, _prepared?.Version];
            var n = UpdateVerifier.DeleteStaleDownloads(OriginPaths.Updates, ReleaseZipPrefix, keep)
                    + UpdateVerifier.DeleteStaleStages(OriginPaths.Updates, keep);
            if (n > 0) Log($"removed {n} stale update download(s)");
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

    private static bool Same(Version? a, Version? b) =>
        a != null && b != null && UpdateGate.Normalize(a) == UpdateGate.Normalize(b);

    // Starts the background download of this release unless it is already ready or on its way. One fetch at a time:
    // an older background fetch is cancelled for a newer release; a fetch the badge click is waiting on is never
    // cancelled (the newer one starts on a later poll). Returns false when nothing was started for it.
    private static bool StartFetch(AvailableUpdate update, bool quiet)
    {
        // The quiet retry after a failed check ran on every build before background downloads existed: it still does.
        if (!Enabled || (!quiet && !PrefetchAllowed)) return false;
        Fetch fetch;
        Fetch? superseded = null;
        Prepared? stale = null;
        lock (Gate)
        {
            if (_prepared != null && Same(_prepared.Version, update.Version)) return true;
            if (_fetch is { } running)
            {
                if (Same(running.Update.Version, update.Version)) return true;
                if (running.Joined || UpdateGate.Normalize(running.Update.Version) > UpdateGate.Normalize(update.Version)) return false;
                superseded = running;
            }
            // An ordinary background failure backs off (the quiet path has the gate's own back-off).
            if (!quiet && Same(_backgroundFailedVersion, update.Version) && DateTime.UtcNow < _backgroundRetryAtUtc) return false;
            if (_prepared != null) { stale = _prepared; _prepared = null; }
            fetch = _fetch = new Fetch(update, quiet, joined: false);
        }
        superseded?.Cancel.Cancel();
        if (stale != null) Discard(stale);
        _ = Task.Run(() => RunFetchAsync(fetch));
        return true;
    }

    // Runs one fetch to the end and settles what it means, under the lock, BEFORE its task completes: a click that
    // awaits it, or the next poll, always sees the outcome already recorded.
    private static async Task RunFetchAsync(Fetch fetch)
    {
        Prepared? result = null;
        Exception? error = null;
        try
        {
            result = await FetchAsync(fetch.Update, fetch.Relay, fetch.Cancel.Token).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            error = e;
        }

        var version = fetch.Update.Version;
        var raise = false;
        Prepared? unused = null;
        lock (Gate)
        {
            if (_fetch == fetch) _fetch = null;
            if (error == null)
            {
                if (fetch.Joined)
                {
                    // The click takes it straight from the task.
                }
                else if (fetch.Quiet)
                {
                    Gate.Verified(version);
                    if (Gate.TryPublish(version))
                    {
                        Available = fetch.Update;
                        _prepared = result;
                        raise = true;
                        Log($"update {version}: quiet retry verified, offering it now (ready to install)");
                    }
                    else
                    {
                        unused = result;
                        Log($"update {version}: quiet retry verified but a newer release is already offered; dropped");
                    }
                }
                else if (Available != null && Same(Available.Version, version))
                {
                    _prepared = result;
                    _backgroundFailedVersion = null;
                    _backgroundFailures = 0;
                    Log($"update {version}: downloaded and verified in the background, ready to install");
                }
                else
                {
                    unused = result; // superseded or withdrawn while it downloaded
                }
            }
            else if (!fetch.Joined)
            {
                var now = DateTime.UtcNow;
                if (fetch.Quiet)
                {
                    // The quiet retry's rule (unchanged): any failure, even a cancel, ends its slot with the back-off.
                    Gate.VerifyFailed(version, now);
                }
                else if (fetch.Cancel.IsCancellationRequested)
                {
                    // Superseded by a newer release: nothing failed.
                }
                else if (error is UpdateVerificationException)
                {
                    // The release itself is bad (or unverifiable): the same rule as a failed check on click, applied
                    // before the player even clicks. Out of the gate at once, retried quietly with back-off.
                    Gate.VerifyFailed(version, now);
                    if (Available != null && Same(Available.Version, version))
                    {
                        Available = null;
                        raise = true;
                    }
                    Log($"update {version}: background download failed its check; gate released, retrying quietly later");
                }
                else
                {
                    // Offline, disk, a stall: nothing about the release. Try again later; the click still works now.
                    _backgroundFailures = Same(_backgroundFailedVersion, version) ? _backgroundFailures + 1 : 1;
                    _backgroundFailedVersion = version;
                    var wait = Math.Min(BackgroundFirstRetry.TotalMinutes * Math.Pow(2, _backgroundFailures - 1), BackgroundMaxRetry.TotalMinutes);
                    _backgroundRetryAtUtc = now + TimeSpan.FromMinutes(wait);
                    Log($"update {version}: background download failed, trying again in {wait:0} min (Update still works)");
                }
            }
        }
        if (unused != null) Discard(unused);
        if (raise) AvailableChanged?.Invoke(null, EventArgs.Empty);
        if (error == null)
        {
            fetch.Done.SetResult(result!);
        }
        else
        {
            fetch.Done.SetException(error);
            _ = fetch.Done.Task.Exception; // observed: only a joined click awaits it
        }
        // Cancel is never disposed: a superseding StartFetch may still call Cancel() on it after this returns.
    }

    // One release, downloaded and verified: changed files only when the release has a file list (Windows), else (or
    // when that path can't be used) the full zip. Throws like the full download always did: an
    // UpdateVerificationException for a bad or unverifiable release, anything else for an ordinary failure.
    private static async Task<Prepared> FetchAsync(AvailableUpdate update, IProgress<UpdateProgress> progress, CancellationToken ct)
    {
        Directory.CreateDirectory(OriginPaths.Updates);
        CleanStaleDownloads(update.Version);
        progress.Report(new UpdateProgress(UpdateStage.Downloading, 0, null));

        string expected;
        try
        {
            expected = await ResolveExpectedHashAsync(update).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            Log($"update {update.Version}: not applied, will retry later: {ex.GetType().Name}: {ex.Message}");
            throw;
        }

        if (update.FileListUrl != null && update.FileListSha256Url != null)
        {
            var staged = await TryChangedFilesAsync(update, expected, progress, ct).ConfigureAwait(false);
            if (staged != null) return staged;
        }

        var zipPath = ZipPathFor(update.Version);
        await DownloadVerifiedAsync(update, expected, zipPath, progress, ct).ConfigureAwait(false);
        return new Prepared(update.Version, zipPath, expected, null, null);
    }

    // The changed-files path (DeltaStager). Null = not usable this time (logged), so the caller downloads the full
    // zip; only a cancel escapes. The stage is built in "<version>.delta-part" and renamed to "<version>" only once
    // every file in it has verified, so a half-built stage is never mistaken for a ready one.
    private static async Task<Prepared?> TryChangedFilesAsync(AvailableUpdate update, string zipSha256, IProgress<UpdateProgress> progress, CancellationToken ct)
    {
        var stageDir = StageDirFor(update.Version);
        var partDir = stageDir + UpdateVerifier.StagePartSuffix;
        try
        {
            DeleteFolder(partDir);
            var listSha = await UpdateVerifier.FetchSidecarAsync(
                c => Http.GetStringAsync(update.FileListSha256Url, c), DeltaManifestName!, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
            var json = await GetSmallFileAsync(update.FileListUrl!, DeltaManifest.MaxBytes, ct).ConfigureAwait(false);
            if (!string.Equals(Convert.ToHexString(SHA256.HashData(json)), listSha, StringComparison.OrdinalIgnoreCase))
                throw new DeltaUnavailableException("the file list failed its SHA-256");
            var manifest = DeltaManifest.Parse(json, ReleaseAssetName, zipSha256);

            var reportGate = new object();
            var lastReport = Stopwatch.StartNew();
            var result = await DeltaStager.StageAsync(Http, update.DownloadUrl, manifest, AppContext.BaseDirectory, partDir,
                (received, total) =>
                {
                    lock (reportGate)
                    {
                        if (received != 0 && received != total && lastReport.ElapsedMilliseconds < 100) return;
                        lastReport.Restart();
                    }
                    progress.Report(new UpdateProgress(UpdateStage.Downloading, received, total));
                }, StallTimeout, ct).ConfigureAwait(false);

            DeleteFolder(stageDir);
            Directory.Move(partDir, stageDir);
            Log($"update {update.Version}: changed files only: fetched {result.FetchedFiles} file(s), {result.FetchedBytes / 1_048_576d:0.0} MB " +
                $"in {result.Requests} request(s), reused {result.ReusedFiles} (the full download is {manifest.ZipSize / 1_048_576d:0} MB)");
            return new Prepared(update.Version, null, null, stageDir, manifest);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            DeleteFolder(partDir);
            throw;
        }
        catch (Exception e)
        {
            DeleteFolder(partDir);
            Log($"update {update.Version}: changed-files download not used ({e.GetType().Name}: {e.Message}); downloading the whole update");
            return null;
        }
    }

    // The file list: small, so read whole, but never more than `max` bytes whatever the server says.
    private static async Task<byte[]> GetSmallFileAsync(string url, int max, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        if (response.Content.Headers.ContentLength > max) throw new DeltaUnavailableException("the file list is too big");
        await using var body = await response.Content.ReadAsStreamAsync(timeout.Token).ConfigureAwait(false);
        using var copy = new MemoryStream();
        var buffer = new byte[1 << 16];
        int read;
        while ((read = await body.ReadAsync(buffer, timeout.Token).ConfigureAwait(false)) > 0)
        {
            copy.Write(buffer, 0, read);
            if (copy.Length > max) throw new DeltaUnavailableException("the file list is too big");
        }
        return copy.ToArray();
    }

    private static string StageDirFor(Version version) => Path.Combine(OriginPaths.Updates, version.ToString());

    private static void DeleteFolder(string dir)
    {
        try { if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }

    // A ready release nobody will install (superseded, or a newer one was offered): its files go.
    private static void Discard(Prepared prepared)
    {
        if (prepared.ZipPath != null)
            try { File.Delete(prepared.ZipPath); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        if (prepared.StageDir != null) DeleteFolder(prepared.StageDir);
    }

    /// <summary>
    /// Installs the available update and restarts: straight to install when the background download already has it,
    /// joining that download (and showing its progress) while it still runs, or downloading it now. Then it stages it,
    /// spawns the swap script, and shuts the app down; the script waits for this process to exit, copies the staged
    /// files over the install directory, and relaunches. Progress is reported in bytes so the player sees a real fill.
    /// </summary>
    public static async Task DownloadAndRestartAsync(IProgress<UpdateProgress>? progress = null)
    {
        if (!Enabled) throw new InvalidOperationException("Updates are off in a screenshot run.");
        var update = Available ?? throw new InvalidOperationException("No update is available.");

        // From here until the process is gone no import may open or apply (Core/Import/Launcher/UpdateRunState):
        // the restart below would kill it half done. A failed update releases it; a successful one never does.
        var run = UpdateRunState.Enter();
        var restarting = false;
        try
        {
            var (prepared, satOnDisk) = await TakeOrFetchAsync(update, progress);
            await InstallAndRestartAsync(prepared, satOnDisk, progress, () => restarting = true);
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

    // The click's way to the release: the ready one (it sat on disk, so it is re-checked before install), the running
    // download (joined: its progress is shown from where it is), or a new download the click waits on.
    private static async Task<(Prepared Prepared, bool SatOnDisk)> TakeOrFetchAsync(AvailableUpdate update, IProgress<UpdateProgress>? progress)
    {
        Fetch fetch;
        var start = false;
        Fetch? superseded = null;
        Prepared? stale = null;
        lock (Gate)
        {
            if (_prepared != null && Same(_prepared.Version, update.Version))
            {
                var ready = _prepared;
                _prepared = null;
                return (ready, true);
            }
            if (_fetch is { } running && Same(running.Update.Version, update.Version))
            {
                running.Joined = true;
                fetch = running;
            }
            else
            {
                if (_fetch is { Joined: false } other) superseded = other;
                if (_prepared != null) { stale = _prepared; _prepared = null; }
                fetch = _fetch = new Fetch(update, quiet: false, joined: true);
                start = true;
            }
        }
        superseded?.Cancel.Cancel();
        if (stale != null) Discard(stale);
        if (progress != null) fetch.Relay.Attach(progress);
        if (start) _ = Task.Run(() => RunFetchAsync(fetch));
        return (await fetch.Done.Task, false);
    }

    private static async Task InstallAndRestartAsync(Prepared prepared, bool satOnDisk, IProgress<UpdateProgress>? progress, Action markRestarting)
    {
        var updatesRoot = OriginPaths.Updates;
        var stageDir = StageDirFor(prepared.Version);

        if (prepared.Manifest != null)
        {
            // Changed-files stage: already complete. One that waited on disk is checked file by file first.
            progress?.Report(new UpdateProgress(UpdateStage.Installing, 0, null));
            if (satOnDisk)
                await Task.Run(() => DeltaStager.VerifyStagedAsync(prepared.Manifest, stageDir, CancellationToken.None));
        }
        else
        {
            var zipPath = prepared.ZipPath!;
            // A zip that waited on disk (possibly for hours) is hashed again before it is extracted. A swapped or
            // damaged file is deleted and fails like any bad download.
            if (satOnDisk)
                await UpdateVerifier.EnsureFileMatchesAsync(zipPath, prepared.ZipSha256!, CancellationToken.None).ConfigureAwait(false);

            progress?.Report(new UpdateProgress(UpdateStage.Installing, 0, null));
            // Off the UI thread so the loading scene keeps animating while the zip unpacks; the continuation returns
            // here (UI thread) for Shutdown().
            await Task.Run(() =>
            {
                if (Directory.Exists(stageDir))
                    Directory.Delete(stageDir, recursive: true);
                Directory.CreateDirectory(stageDir);
                // Linux stages an AppImage / package download its own way; everything else is a zip, unpacked per OS
                // (a Mac .app needs its symlinks and exec bits kept).
                var staged = false;
                StageReleaseOverride(zipPath, stageDir, ref staged);
                if (!staged) ExtractUpdate(zipPath, stageDir);
                File.Delete(zipPath);
            });
        }

        // An import may have been started while this downloaded (or still be running from before): the restart
        // waits for it, then shuts down on this same UI-thread turn, so none can begin in between.
        await UpdateRunState.PrepareToRestartAsync(() => progress?.Report(new UpdateProgress(UpdateStage.FinishingImport, 0, null)));

        // Remembered before the swap can start: if it fails after this process is gone, the next start knows
        // (SwapAttempt) and opens Play instead of gating it on the same update again.
        var marker = OriginPaths.UpdateSwapMarker;
        SwapAttempt.Record(marker, prepared.Version);
        // The OS file writes and starts the swap script, which waits for this process to exit.
        try
        {
            StartApplyScript(stageDir, updatesRoot);
        }
        catch
        {
            SwapAttempt.Clear(marker); // no swap was started: this is an ordinary failure of this run
            throw;
        }
        progress?.Report(new UpdateProgress(UpdateStage.Restarting, 0, null));
        markRestarting();
        ShutdownApp();
    }

    // A release that is not a zip stages itself here (Linux: the tar.gz or the AppImage, UpdateService.Linux.cs).
    // Windows and the Mac don't implement it, so for them the call compiles away and the zip is extracted.
    static partial void StageReleaseOverride(string download, string stageDir, ref bool staged);

    // Downloads the zip and checks it against `expected` (UpdateVerifier policy: refuse a mismatch or a short file).
    // The zip only ever appears at zipPath once verified: it is written as zipPath.part and every failure path deletes
    // that. Any failure throws; nothing was extracted or applied.
    // The byte loop runs off the UI thread (ConfigureAwait(false)); progress still lands on the UI
    // thread because Progress<T> captured it. Reports are capped at ~10/s (each is a dispatcher hop).
    private static async Task DownloadVerifiedAsync(AvailableUpdate update, string expected, string zipPath, IProgress<UpdateProgress> progress, CancellationToken ct)
    {
        var partPath = zipPath + ".part";
        try
        {
            using var stall = CancellationTokenSource.CreateLinkedTokenSource(ct);
            stall.CancelAfter(StallTimeout);
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
                        progress.Report(new UpdateProgress(UpdateStage.Downloading, received, total));
                    }
                }, stall.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                throw new IOException("the connection stalled");
            }

            UpdateVerifier.ThrowIfRefused(verdict);
            File.Move(partPath, zipPath, overwrite: true);
            progress.Report(new UpdateProgress(UpdateStage.Downloading, total ?? 0, total ?? 0));
        }
        catch (Exception ex)
        {
            try { File.Delete(partPath); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            if (!ct.IsCancellationRequested)
                Log($"update {update.Version}: not applied, will retry later: {ex.GetType().Name}: {ex.Message}");
            throw;
        }
    }

    // Hands one fetch's progress to whoever watches it: nobody while it runs in the background, the badge click's
    // progress once it joins (starting with the latest report, so a joined download shows where it already is).
    private sealed class ProgressRelay : IProgress<UpdateProgress>
    {
        private readonly object _lock = new();
        private IProgress<UpdateProgress>? _target;
        private UpdateProgress? _last;

        public void Attach(IProgress<UpdateProgress> target)
        {
            UpdateProgress? last;
            lock (_lock) { _target = target; last = _last; }
            if (last != null) target.Report(last);
        }

        public void Report(UpdateProgress value)
        {
            IProgress<UpdateProgress>? target;
            lock (_lock) { _last = value; target = _target; }
            target?.Report(value);
        }
    }

    // The sidecar .sha256 asset first, then the release API's own asset digest. No hash at all, or a SIDECAR that is
    // listed but unreadable or malformed, is a verification failure: an update we cannot verify is not applied
    // (and, like any verification failure, never blocks play).
    private static async Task<string> ResolveExpectedHashAsync(AvailableUpdate update)
    {
        if (update.Sha256Url != null)
        {
            // 404/403, a timeout, a reset or a garbled body all surface as UpdateVerificationException.
            return await UpdateVerifier.FetchSidecarAsync(
                ct => Http.GetStringAsync(update.Sha256Url, ct), ReleaseAssetName, TimeSpan.FromSeconds(30)).ConfigureAwait(false);
        }
        // GitHub's own digest is the fallback: anything but a clean "sha256:<64 hex>" (another algorithm, empty,
        // missing, a format change) is "no hash", which is refused before the download starts.
        return UpdateVerifier.RequireHash(UpdateVerifier.ParseDigest(update.DigestSha256));
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

    public sealed record BlockedUpdate(Version Version, string Reason);

    public sealed record AvailableUpdate(Version Version, string DownloadUrl, string? Sha256Url = null, string? DigestSha256 = null,
        string? FileListUrl = null, string? FileListSha256Url = null);

    public enum UpdateStage { Downloading, Installing, Restarting, FinishingImport }

    /// <summary>One progress tick. TotalBytes is null when the server sent no
    /// Content-Length (then only the byte count is known, not a fraction).</summary>
    public sealed record UpdateProgress(UpdateStage Stage, long ReceivedBytes, long? TotalBytes)
    {
        /// <summary>0..1 while downloading with a known size; null otherwise.</summary>
        public double? Fraction => Stage == UpdateStage.Downloading && TotalBytes is > 0
            ? Math.Clamp((double)ReceivedBytes / TotalBytes.Value, 0, 1)
            : null;

        /// <summary>Player-facing line, e.g. "Downloading - 42% - 53 of 126 MB" (a changed-files update shows its own,
        /// smaller size: "Downloading - 40% - 36 of 90 MB", or "2.4 of 6.1 MB" under 10 MB).</summary>
        public string Describe() => Stage switch
        {
            UpdateStage.Downloading when Fraction is { } f =>
                $"Downloading - {f * 100:0}% - {Mb(ReceivedBytes, TotalBytes!.Value)} of {Mb(TotalBytes!.Value, TotalBytes.Value)} MB",
            UpdateStage.Downloading when ReceivedBytes > 0 => $"Downloading - {Mb(ReceivedBytes, ReceivedBytes)} MB",
            UpdateStage.Downloading => "Downloading",
            UpdateStage.Installing => "Installing",
            UpdateStage.FinishingImport => "Finishing your import first...",
            _ => "Restarting"
        };

        // Whole MB, or one decimal when the total is under 10 MB (a small changed-files update would read "0 of 2 MB").
        private static string Mb(long bytes, long scale) => (bytes / 1_048_576d).ToString(scale < 10 * 1_048_576L ? "0.0" : "0");
    }

    private sealed record Release(
        [property: JsonPropertyName("tag_name")] string? TagName,
        [property: JsonPropertyName("assets")] List<ReleaseAsset>? Assets);

    private sealed record ReleaseAsset(
        [property: JsonPropertyName("name")] string? Name,
        [property: JsonPropertyName("browser_download_url")] string? DownloadUrl,
        [property: JsonPropertyName("digest")] string? Digest = null);
}
