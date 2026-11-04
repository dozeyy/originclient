using System.IO;
using System.Net.NetworkInformation;
using System.Runtime.ExceptionServices;
using OriginLauncher.App.Core.Import.Launcher;

namespace OriginLauncher.App.Core.Updates;

// The background download (DECISIONS 2026-11-03: "Launcher updates download in the background the moment they appear
// and fetch only the files that changed; anything odd falls back to the full verified download"). Shared by all three
// launchers; the rules live in UpdatePrefetch (pure, tested), this file is the glue:
//  - When a release is offered (CheckAsync / the quiet retry), StageAsync runs in the background with no UI: the small
//    update (delta) first where this OS has one, the full zip as the fallback, each checked against its SHA-256, then
//    unpacked into the stage folder and recorded (StageSnapshot).
//  - The Update click / restart prompt (DownloadAndRestartAsync) then re-hashes that stage and swaps. A click during the
//    download joins it and shows its progress. Anything odd (the download failed, the stage changed on disk) and the
//    click downloads it itself, exactly as before.
//  - It never blocks Play: a background failure that is not the release's own fault only backs off (the click still
//    works); a release that fails its SHA-256 check is withdrawn and released from the gate at once, the same rule as a
//    failed click. Offline, metered connections and running games just wait for a later poll.
//  - A game start pauses it, closing the launcher cancels it (the .part is deleted; a kill leaves it for the next
//    start's cleanup).
public static partial class UpdateService
{
    // The state machine. Always touched under lock (Gate), like the gate itself.
    private static readonly UpdatePrefetch Prefetch = new();

    // The one background download in flight, so a click can join it and a game start, a newer release or the exit can
    // stop it. Done completes after its outcome is recorded under the lock, with the failure (null on success).
    private sealed class Fetch(Version version)
    {
        public Version Version { get; } = version;
        public ProgressRelay Relay { get; } = new();
        public CancellationTokenSource Cancel { get; } = new();
        public Task<Exception?> Done { get; set; } = Task.FromResult<Exception?>(null);
    }

    private static Fetch? _fetch; // always under lock (Gate)
    private static IReadOnlyList<StageSnapshot.Entry>? _stagedSnapshot; // the background stage's record, under lock (Gate)
    private static string? _lastWaitReason; // logged once per change, not every poll

    /// <summary>
    /// Background downloads on (the default). The Mac's `--update-proof` turns them off so its run proves the click path
    /// on its own (a background download racing the proof's click would make its bad-hash case flaky).
    /// </summary>
    internal static bool BackgroundDownloads { get; set; } = true;

    // Dev builds (1.0.0.0, a local build Will opens himself) see the badge but never download in the background: every
    // local start would otherwise pull a whole release it will never install.
    private static bool BackgroundAllowed => Enabled && BackgroundDownloads && !IsDevBuild;

    // A game this launcher started is starting or still running.
    private static bool GameRunning => LaunchRunState.IsStarting || SettingsSync.AnyGameRunning;

    // Kicks the background download of the offered release unless it is already handled. Never throws, never blocks the
    // caller: the network and metered probes run on the pool.
    private static void StartBackground(AvailableUpdate update)
    {
        if (!BackgroundAllowed || Blocked != null) return;
        lock (Gate)
        {
            if (Prefetch.Installing != null) return;
            if (Prefetch.Current != UpdatePrefetch.Phase.Idle && UpdatePrefetch.Same(Prefetch.Version, update.Version)) return;
        }
        _ = Task.Run(() => StartBackgroundCore(update));
    }

    private static void StartBackgroundCore(AvailableUpdate update)
    {
        try
        {
            HookOnce();
            var conditions = new UpdatePrefetch.Conditions(GameRunning, NetworkInterface.GetIsNetworkAvailable(), IsMetered());
            Fetch? superseded = null;
            Version? dropped;
            UpdatePrefetch.Start start;
            lock (Gate)
            {
                // Withdrawn or superseded while this hopped to the pool: nothing to do for it.
                if (Available == null || !UpdatePrefetch.Same(Available.Version, update.Version)) return;
                start = Prefetch.TryStart(update.Version, conditions, DateTime.UtcNow, out dropped);
                if (dropped != null)
                {
                    if (_fetch != null && UpdatePrefetch.Same(_fetch.Version, dropped)) superseded = _fetch;
                    _fetch = null;
                    _stagedSnapshot = null;
                }
                if (start == UpdatePrefetch.Start.Go)
                {
                    var fetch = _fetch = new Fetch(update.Version);
                    fetch.Done = Task.Run(() => RunFetchAsync(fetch, update));
                }
            }
            if (superseded != null)
            {
                superseded.Cancel.Cancel(); // its own failure path deletes its stage
                Log($"update {superseded.Version}: background download stopped, {update.Version} is newer");
            }
            else if (dropped != null)
            {
                TryDeleteStage(StageDirFor(dropped));
            }

            if (start == UpdatePrefetch.Start.NotNow)
            {
                var reason = conditions.GameRunning ? "a game is running" : !conditions.Online ? "offline"
                    : conditions.Metered ? "the connection is metered" : null;
                if (reason != null && reason != _lastWaitReason)
                    Log($"update {update.Version}: background download waits ({reason}); Update still works");
                _lastWaitReason = reason;
            }
        }
        catch (Exception e)
        {
            Log($"update {update.Version}: background download not started: {e.GetType().Name}: {e.Message}");
        }
    }

    // Runs one background download to the end and records what it means under the lock BEFORE Done completes, so a
    // click that awaits it, or the next poll, always sees the outcome. Returns the failure (null when staged).
    private static async Task<Exception?> RunFetchAsync(Fetch fetch, AvailableUpdate update)
    {
        var version = update.Version;
        var stageDir = StageDirFor(version);
        Exception? error = null;
        IReadOnlyList<StageSnapshot.Entry>? snapshot = null;
        try
        {
            Log($"update {version}: downloading in the background");
            await StageAsync(update, stageDir, fetch.Relay, fetch.Cancel.Token).ConfigureAwait(false);
            snapshot = await Task.Run(() => StageSnapshot.Take(stageDir, fetch.Cancel.Token)).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            error = e;
        }

        var raise = false;
        var deleteStage = false;
        lock (Gate)
        {
            if (_fetch == fetch) _fetch = null;
            var cancelled = fetch.Cancel.IsCancellationRequested;
            if (error == null)
            {
                if (Prefetch.Staged(version))
                {
                    _stagedSnapshot = snapshot;
                    _lastWaitReason = null;
                    Log($"update {version}: downloaded and verified in the background, ready to install");
                }
                else
                {
                    deleteStage = !IsKept(version); // superseded or withdrawn while it downloaded
                }
            }
            else
            {
                var joined = Prefetch.Joined && UpdatePrefetch.Same(Prefetch.Version, version);
                var wait = Prefetch.Failed(version, cancelled, DateTime.UtcNow);
                if (!joined)
                {
                    deleteStage = !IsKept(version);
                    if (cancelled)
                    {
                        Log($"update {version}: background download stopped (it starts again on a later check)");
                    }
                    else if (error is UpdateVerificationException)
                    {
                        // The release itself failed its check (or published no usable hash): the same rule as a failed
                        // click, applied before the player clicks. Out of the gate at once, retried quietly with back-off.
                        Gate.VerifyFailed(version, DateTime.UtcNow);
                        if (Available != null && UpdatePrefetch.Same(Available.Version, version))
                        {
                            Available = null;
                            raise = true;
                        }
                        Log($"update {version}: background download failed its check; gate released, retrying quietly later");
                    }
                    else
                    {
                        Log($"update {version}: background download failed, trying again in {wait?.TotalMinutes:0} min (Update still works)");
                    }
                }
                // Joined: the click owns this failure (it counts it on the gate, or downloads it itself).
            }
        }
        if (deleteStage) TryDeleteStage(stageDir);
        if (raise) AvailableChanged?.Invoke(null, EventArgs.Empty);
        // Cancel is never disposed: a click or the exit hook may still call Cancel() on it after this returns.
        return error;
    }

    /// <summary>
    /// The click's use of the background download: true when <paramref name="stageDir"/> now holds this version, staged in
    /// the background and unchanged since (re-hashed here). False sends the click to its own download. A joined download
    /// that failed its SHA-256 check rethrows that failure, so the click counts it exactly like its own.
    /// </summary>
    private static async Task<bool> TakeBackgroundStageAsync(AvailableUpdate update, string stageDir, IProgress<UpdateProgress>? progress)
    {
        UpdatePrefetch.Click plan;
        Fetch? join = null, superseded = null;
        Version? dropped;
        IReadOnlyList<StageSnapshot.Entry>? snapshot = null;
        lock (Gate)
        {
            plan = Prefetch.BeginInstall(update.Version, out dropped);
            if (dropped != null)
            {
                if (_fetch != null && UpdatePrefetch.Same(_fetch.Version, dropped)) superseded = _fetch;
                _fetch = null;
                _stagedSnapshot = null;
            }
            if (plan == UpdatePrefetch.Click.JoinDownload) join = _fetch;
            if (plan == UpdatePrefetch.Click.SwapStaged) snapshot = _stagedSnapshot;
        }
        superseded?.Cancel.Cancel();

        if (join != null)
        {
            Log($"update {update.Version}: Update clicked during the background download; waiting for it");
            join.Relay.Target = progress;
            progress?.Report(new UpdateProgress(UpdateStage.Downloading, 0, null));
            var error = await join.Done;
            join.Relay.Target = null;
            if (error is UpdateVerificationException) ExceptionDispatchInfo.Throw(error);
            if (error == null)
            {
                lock (Gate)
                    snapshot = Prefetch.Current == UpdatePrefetch.Phase.Staged && UpdatePrefetch.Same(Prefetch.Version, update.Version)
                        ? _stagedSnapshot : null;
            }
            else
            {
                Log($"update {update.Version}: the background download failed ({error.GetType().Name}); downloading it now");
            }
        }

        if (snapshot != null)
        {
            progress?.Report(new UpdateProgress(UpdateStage.Installing, 0, null));
            if (await Task.Run(() => StageSnapshot.Matches(stageDir, snapshot)))
            {
                Log($"update {update.Version}: installing the update downloaded in the background");
                return true;
            }
            Log($"update {update.Version}: the update staged in the background changed on disk; downloading it again");
        }
        lock (Gate)
        {
            Prefetch.FallBackToClickDownload(update.Version);
            _stagedSnapshot = null;
        }
        return false;
    }

    // The swap waits while a game this launcher started is starting or running (UpdatePrefetch.BeforeSwap), checking
    // every 2 s. Stays on the caller's (UI) context: the restart's last steps must run there.
    private static async Task WaitForGamesToCloseAsync(IProgress<UpdateProgress>? progress)
    {
        var told = false;
        while (UpdatePrefetch.BeforeSwap(GameRunning) == UpdatePrefetch.SwapStep.WaitForGame)
        {
            if (!told)
            {
                told = true;
                progress?.Report(new UpdateProgress(UpdateStage.WaitingForGame, 0, null));
                Log("update: waiting for Minecraft to close before installing");
            }
            await Task.Delay(TimeSpan.FromSeconds(2));
        }
    }

    // Under lock (Gate): the click is installing this version, or another background download of it holds the stage.
    private static bool IsKept(Version version) => Prefetch.Keep().Any(v => UpdatePrefetch.Same(v, version));

    private static void TryDeleteStage(string stageDir)
    {
        try
        {
            if (Directory.Exists(stageDir)) Directory.Delete(stageDir, recursive: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Locked: the next stage's cleanup (CleanStaleDownloads) removes it.
        }
    }

    // A game start pauses the background download at once (the next poll after the game closes starts it again); the
    // launcher closing cancels it and gives it a moment to delete its .part. Hooked on the first background start, so a
    // screenshot run never subscribes.
    private static int _hooked;

    private static void HookOnce()
    {
        if (Interlocked.Exchange(ref _hooked, 1) != 0) return;
        LaunchRunState.Changed += () =>
        {
            if (!LaunchRunState.IsStarting) return;
            StopBackground("a game is starting");
        };
        AppDomain.CurrentDomain.ProcessExit += (_, _) =>
        {
            var stopped = StopBackground(null);
            try { stopped?.Wait(TimeSpan.FromSeconds(1)); } catch (Exception) { /* exiting anyway */ }
        };
    }

    // Cancels the background download unless the click is waiting on it. Returns its task (to wait on), or null.
    private static Task<Exception?>? StopBackground(string? why)
    {
        Fetch? fetch;
        lock (Gate) fetch = _fetch != null && !Prefetch.Joined ? _fetch : null;
        if (fetch == null) return null;
        fetch.Cancel.Cancel();
        if (why != null) Log($"update {fetch.Version}: background download paused, {why} (resumes on a later check)");
        return fetch.Done;
    }

    // Metered connections wait for a later poll. Each OS reads its own (UpdateService.Windows.cs: the network cost;
    // UpdateService.Linux.cs: NetworkManager); the Mac has no .NET-reachable answer, so it counts as unmetered.
    private static bool IsMetered()
    {
        var metered = false;
        ReadMeteredOverride(ref metered);
        return metered;
    }

    static partial void ReadMeteredOverride(ref bool metered);

    // Forwards the background download's progress to whoever joined it (the click's loading scene); nobody otherwise.
    private sealed class ProgressRelay : IProgress<UpdateProgress>
    {
        private volatile IProgress<UpdateProgress>? _target;

        public IProgress<UpdateProgress>? Target
        {
            get => _target;
            set => _target = value;
        }

        public void Report(UpdateProgress value) => _target?.Report(value);
    }
}
