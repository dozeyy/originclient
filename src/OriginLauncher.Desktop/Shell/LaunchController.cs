using System.Diagnostics;
using Avalonia.Threading;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Performance;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Pages;
using OriginLauncher.Desktop.Shots;
using OriginLauncher.Desktop.Windows;

namespace OriginLauncher.Desktop.Shell;

// Play, ported from the Windows launcher's HomePage launch code (UI/Pages/HomePage.xaml.cs): sign-in, the install,
// the launch cover, the running game, the boot watch and the crash window. One game per account; the newest launch
// action cancels only the same account's in-flight one (Core AccountLaunchBook). A game closing or crashing never
// closes the launcher, and Play works again afterwards (the book returns the account to Idle on every exit path).
// Render runs (ShotRunner.Active) start nothing real: the Tour* methods put the screens into each state instead.
public sealed class LaunchController
{
    private const string ImportRunningText = "Importing - Play is back when it finishes";

    private readonly MainWindow _window;
    private readonly HomePage _home;
    private readonly Lazy<VersionManager> _versions = new(() => new VersionManager());
    private readonly LaunchOverride _modOverride = new();
    private readonly Dictionary<string, string?> _profileByKey = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> _noteVersion = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> _tryWithoutAddedModsOnce = new(StringComparer.Ordinal);
    private static readonly CrashRetryGuard CrashRetries = new();

    // The launch the cover is showing until its game process is up (then _sceneGame), and the cover itself.
    private LaunchTicket? _overlayTicket;
    private RunningGame? _sceneGame;
    private LaunchCover? _cover;
    private LaunchTarget? _failedLaunch;
    private RunningGame? _mirrored;

    // A launch for a version, optionally straight into a server and/or for a given account (the crash window's
    // retry relaunches the account that crashed, whoever is selected now). Never saved as the pick.
    private sealed record LaunchTarget(string Version, string? Server = null, string? AccountKey = null);

    // One started game. Several can be alive at once, one per account.
    private sealed class RunningGame
    {
        public required string Key { get; init; }
        public required string Version { get; init; }
        public required string AccountName { get; init; }
        public required string GameDir { get; init; }
        public required Process Process { get; init; }
        public required GameHandoff Handoff { get; init; }
        public string? LogPath { get; set; }
        public string? LastWhereLine { get; set; }
        public int MirrorId { get; set; }
        public HandoffStage Shown { get; set; }
        public bool StoppedByPlayer { get; set; }
        public DateTime StartedUtc { get; init; } = DateTime.UtcNow;
        public IDisposable? Configuration { get; init; }
        // The version's own Java arguments exactly as this launch used them: the crash window judges THESE.
        public VersionJvmOptions? LaunchedJvmArgs { get; init; }
        // The heap this launch really gave Java (CrashMemory.ForLaunch), never the stored slider value: on Auto RamMb
        // is only the slider's suggestion, so the crash check judged (and raised) the wrong number. Windows' twin.
        public CrashMemory.Facts Memory { get; init; }
    }

    public LaunchController(MainWindow window, HomePage home)
    {
        _window = window;
        _home = home;
        // A new account or version ends the "start anyway" permission (Core/Compat/LaunchOverride).
        LauncherState.SettingsChanged += ContextChanged;
        LauncherState.AccountsChanged += () =>
        {
            ContextChanged();
            // Switched away while this account's game is still starting: lower its cover (the game carries on).
            if (_sceneGame != null && _sceneGame.Key != HomePage.SelectedKey()) ReleaseScene(_sceneGame);
            SyncSocialMirror();
        };
        ImportRunState.Changed += () => Dispatcher.UIThread.Post(Raise);
    }

    public AccountLaunchBook Book { get; } = new();

    /// <summary>Profiles page: a profile can't be deleted or edited while a game of it is starting or running.</summary>
    public bool IsProfileInUse(string profileId) =>
        _profileByKey.Any(entry => entry.Value == profileId && Book.PhaseOf(entry.Key) != GamePhase.Idle);

    /// <summary>Play is held off (an import or a compatibility fix is running).</summary>
    public bool Blocked { get; set; }

    /// <summary>Why Play is held off, in words for the status line; null when it isn't.</summary>
    public string? BlockedText => ImportRunState.IsApplying ? ImportRunningText : null;

    public event Action? Changed;

    private void Raise() => Changed?.Invoke();

    private void ContextChanged() => _modOverride.ContextChanged(HomePage.SelectedKey(), LauncherState.SelectedVersion);

    // ---- Status notes ----

    /// <summary>A status-line note for an account. While idle it shows only for the version it was written for.</summary>
    public void Note(string key, string text, LaunchTone tone, string? version = null)
    {
        Book.SetNote(key, new LaunchNote(text, tone));
        _noteVersion[key] = version ?? LauncherState.SelectedVersion;
        Raise();
    }

    /// <summary>A note on the selected account (Fix Compatibility's progress lines).</summary>
    public void NoteSelected(string text, LaunchTone tone)
    {
        if (HomePage.SelectedKey() is { } key) Note(key, text, tone);
    }

    /// <summary>The last note of an idle account, when it was written for this version; null otherwise.</summary>
    public LaunchNote? IdleNote(string key, string version) =>
        _noteVersion.TryGetValue(key, out var v) && v == version ? Book.NoteOf(key) : null;

    // ---- Entry points ----

    /// <summary>Play: launches the selected version, or brings the account's open game forward.</summary>
    public Task PlayOrFocusAsync(string version) => LaunchAsync(new LaunchTarget(version), LaunchRoute.Play);

    /// <summary>The first start after "Update and play": the Play the update refused, as it was (same account, version
    /// and server), with no second click (JL-5; the WPF HomePage's twin).</summary>
    public Task ResumeAfterUpdateAsync(PlayAfterUpdate play) =>
        LaunchAsync(new LaunchTarget(play.Version, play.Server, play.AccountKey),
            play.Server != null ? LaunchRoute.JoinServer : LaunchRoute.Play);

    /// <summary>Enter on Home (and the import page's Play).</summary>
    public Task PlayFromKeyboardAsync(string version) => LaunchAsync(new LaunchTarget(version), LaunchRoute.EnterKey);

    /// <summary>Launch a version, optionally straight into a server (Join, a friend's party warp).</summary>
    public Task PlayAsync(string version, string? server)
    {
        if (server == null) return PlayOrFocusAsync(version);
        if (HomePage.SelectedKey() is { } key && Book.PhaseOf(key) is GamePhase.Starting or GamePhase.Running)
        {
            Note(key, Book.PhaseOf(key) == GamePhase.Starting
                ? "Minecraft is still starting on this account - try Join again in a moment"
                : "Minecraft is already open on this account - close it to join, or switch accounts", LaunchTone.Warning);
            return Task.CompletedTask;
        }
        return LaunchAsync(new LaunchTarget(version, server), LaunchRoute.JoinServer);
    }

    private async Task LaunchAsync(LaunchTarget target, LaunchRoute route)
    {
        if (ShotRunner.Active) return; // render runs show states through the Tour* methods; nothing real starts
        var selected = HomePage.SelectedKey();
        if (ImportRunState.IsApplying)
        {
            if (selected != null) Note(selected, ImportRunningText, LaunchTone.Warning);
            return;
        }
        if (Blocked || Overlays.FixCompatibility.Running) return;
        LaunchPrewarm.Stop(); // the disk belongs to this launch now

        // One fresh read for every launch-gating setting: other pages can change them between clicks.
        var freshSettings = SettingsStore.Load();
        var offlineTest = freshSettings.OfflineTestMode;
        var externalMods = freshSettings.PlayWithExternalMods;

        StoredAccount? account;
        string key;
        if (target.AccountKey is { } wanted)
        {
            account = wanted == InstanceLayout.OfflineKey
                ? null
                : AccountStore.Load().FirstOrDefault(a => InstanceLayout.KeyFor(a.Id) == wanted);
            if (account == null && !(wanted == InstanceLayout.OfflineKey && offlineTest)) return;
            key = wanted;
        }
        else
        {
            account = AccountStore.GetSelected(AccountStore.Load());
            if (AccountLaunchBook.SignedOutPlayOpensSignIn(account != null, offlineTest)) { if (!_window.DialogOpen) SignInPanel.Open(_window); return; }
            key = InstanceLayout.KeyFor(account?.Id);
        }
        var version = target.Version;
        var accountName = account?.Gamertag ?? "Offline";

        // The crash window's "Try once without my mods": this one relaunch leaves the player's own mods out.
        // The line Home shows once that try is running (ModSuspects: which of the player's mods to check first).
        string? withoutModsLine = null;
        if (_tryWithoutAddedModsOnce.Remove(key, out var tryLine) && route == LaunchRoute.Retry)
        {
            externalMods = false;
            withoutModsLine = tryLine;
        }

        // One game per account: an open game is explained instead of a second copy.
        switch (AccountLaunchBook.ActionFor(Book.PhaseOf(key)))
        {
            case PlayAction.FocusGame:
                if (Book.VersionOf(key) is { } playing && playing != version)
                    Note(key, $"{accountName} is already playing {playing} - close it first to play {version}", LaunchTone.Warning, version);
                FocusGame(key);
                return;
            case PlayAction.None when Book.PhaseOf(key) == GamePhase.Starting:
                return;
        }
        if (Book.PhaseOf(key) == GamePhase.Idle && AccountFolders.VersionPlayingOutsideLauncher(key) is { } openVersion)
        {
            Note(key, $"Minecraft {openVersion} is still open on {accountName} - close it first", LaunchTone.Warning, version);
            return;
        }
        // Mandatory updates (Will's rule): the background poller's answer, no network wait in front of Play.
        if (UpdateService.UpdateRequired)
        {
            Note(key, UpdateRequiredWords.Status, LaunchTone.Warning, version);
            // The line alone was easy to miss (Windows' fix, v1.0.85): also the Update required prompt, whose Update
            // button installs it.
            _ = UpdatePrompt.AskAsync(_window, new PlayAfterUpdate(key, version, target.Server)); // "Update and play"
            return;
        }

        var profile = ProfileRules.ForLaunch(freshSettings.ModProfiles, freshSettings.SelectedProfileId, version, externalMods);
        // Play and Enter may start a refused mod set anyway, only while the status line still shows the refusal.
        var overrideSetKey = _modOverride.Accept(route, key, version, selected == key ? _home.StatusText : null);
        if (Book.BeginPrepare(key, version) is not { } ticket) return;
        // An import must not start while this launch is still being prepared (released on every exit).
        using var launchRun = LaunchRunState.Enter();
        _profileByKey[key] = profile?.Id;
        _overlayTicket = ticket;
        _sceneGame = null;
        _failedLaunch = null;
        Raise();
        var cover = OpenCover();
        cover.Show(version, profile != null || target.Server != null
            ? $"{accountName} - {target.Server ?? profile!.Name}" : accountName);

        void Stage(string text)
        {
            if (ReferenceEquals(_overlayTicket, ticket)) cover.ReportStage(text);
        }

        var gameStarted = false;
        var provisioning = false;
        PlayFailure? failure = null;
        IncompatibleModsException? refused = null;

        try
        {
            MSession session;
            // Microsoft couldn't be reached: the saved session plays singleplayer, and Home says so (NET-1).
            var playingOffline = false;
            if (account != null)
            {
                Stage("Signing in");
                // Off the UI thread: unsealing the saved session goes through the desktop keyring (Core LinuxKeyStore runs
                // secret-tool, 15 s limit per run), which can sit on an unlock prompt; the launch screen keeps animating.
                var signedIn = await Task.Run(() => MinecraftSessionBroker.ForPlayAsync(account.Id, ticket.Token), ticket.Token);
                if (signedIn == null)
                {
                    Note(key, SignInAgain.UnreadableLine, LaunchTone.Error, version);
                    _ = AskToSignInAgainAsync(accountName);
                    return;
                }
                session = signedIn.Session;
                playingOffline = signedIn.Offline;
            }
            else
            {
                Stage("Offline session");
                session = MSession.CreateOfflineSession("OriginTester");
            }
            ticket.Token.ThrowIfCancellationRequested();

            // A first Play on this account copies the player's packs: the launch screen says so while it runs.
            var seedStage = new Progress<string>(Stage);
            var gameDir = await Task.Run(() => AccountFolders.PrepareGameDir(version, key, seedStage, ticket.Token), ticket.Token);
            // An older configuration's Sodium/Iris files left behind by a game that ended with no launcher listening go back
            // to the shipped set's before anything reads or edits them (MULTI-3): provisioning seeds iris.properties and
            // repairs NUL configs next, and those edits belong to the shipped set, not to the old configuration's saved
            // copy. This launch's own configuration goes in later (ActiveConfiguration.Begin).
            await Task.Run(() => ConfigOverlay.RestoreLeftover(gameDir, OriginPaths.ConfigOverlays(version, key)));
            var seedNotice = AccountFolders.TakeSeedNotice(gameDir);
            var (heap, legacy) = await LaunchHeap.PlanAsync(freshSettings, version, gameDir, ticket.Token);
            if (heap.Warning != null) Stage(heap.Warning);
            var launchOption = LaunchProfileBuilder.Build(freshSettings, session, version, heap, legacy);
            // The startup handshake's token, last so nothing overrides it.
            var handoff = new GameHandoff();
            launchOption.ExtraJvmArguments = (launchOption.ExtraJvmArguments ?? Enumerable.Empty<MArgument>())
                .Append(new MArgument(handoff.JvmArgument)).ToList();
            if (target.Server is { } server)
            {
                var join = JoinArguments.Build(version, server);
                if (join.Count > 0)
                    launchOption.ExtraGameArguments = launchOption.ExtraGameArguments
                        .Append(new MArgument(join.ToArray())).ToList();
            }

            var installProgress = new Progress<string>(Stage);
            provisioning = true;
            string? configurationId = null;
            var manager = _versions.Value;
            var process = await Task.Run(
                () => manager.InstallAndBuildProcessAsync(
                    version, key, launchOption, externalMods, profile?.Id, installProgress, ticket.Token, overrideSetKey,
                    profile?.ComponentPins, id => configurationId = id),
                ticket.Token);
            provisioning = false;

            GpuPreference.PreferHighPerformanceGpu(process.StartInfo.FileName);
            GpuPreference.ApplyOffload(process.StartInfo); // a hybrid laptop's discrete GPU (GpuPreference.Linux.cs)
            ShaderCache.Apply(process.StartInfo, freshSettings.ShaderCacheNvidia, freshSettings.ShaderCacheAmd);
            if (freshSettings.SyncSettingsAcrossVersions)
            {
                Stage("Syncing settings");
                await Task.Run(() => SettingsSync.BeforeLaunch(key, version));
            }
            await Task.Run(() => PresetWriter.ApplyToNewInstance(gameDir, version, HardwareProbe.Recommended));
            await Task.Run(() => FirstRunOptions.Seed(gameDir, version)); // after the preset: see FirstRunOptions
            ticket.Token.ThrowIfCancellationRequested();

            Stage("Starting Minecraft");
            var configuration = profile != null && configurationId != null
                ? ActiveConfiguration.Begin(profile.Id, configurationId, gameDir, OriginPaths.ConfigOverlays(version, key))
                : null;
            var game = new RunningGame
            {
                Key = key,
                Version = version,
                AccountName = accountName,
                GameDir = gameDir,
                Process = process,
                Handoff = handoff,
                Configuration = configuration,
                LaunchedJvmArgs = freshSettings.JvmArgsFor(version) is { } jvm
                    ? new VersionJvmOptions { Arguments = jvm.Arguments, ReplacePresets = jvm.ReplacePresets }
                    : null,
                Memory = CrashMemory.ForLaunch(heap),
            };
            StartWithLifecycleCapture(game);
            handoff.ProcessStarted(DateTime.UtcNow);
            gameStarted = Book.GameStarted(ticket, game);
            SyncSocialMirror();

            Note(key, $"Starting {version}", LaunchTone.Neutral, version);
            var runningMessage = target.Server is { } joined
                ? $"Playing on {SocialText.DisplayServer(joined)} - {version}"
                : $"Running - {session.Username}";
            // It started without the player's mods: say so, and which of them to check first (one of them caused the
            // crash), instead of a plain "Running" that leaves the next Play to crash the same way.
            runningMessage = global::OriginLauncher.App.Core.Auth.Sessions.MinecraftSessions.PlayingLine(runningMessage, playingOffline, withoutModsLine);
            if (seedNotice != null) runningMessage = $"{runningMessage}. {seedNotice}";
            runningMessage = LaunchedMods.WithNotices(runningMessage, LaunchedMods.NoticesFor(gameDir));
            _ = WatchBootAsync(game, runningMessage);
        }
        catch (OperationCanceledException ex) when (PlayFailure.IsUserCancel(ex, ticket.Token))
        {
            Note(key, "Cancelled", LaunchTone.Neutral, version);
        }
        catch (MicrosoftAuthException ex) when (ex.RateLimited)
        {
            Note(key, MicrosoftAuthException.RateLimitedMessage, LaunchTone.Warning, version);
            WriteLaunchErrorLog(ex);
        }
        catch (Exception ex) when (ex is OfflineInstallException or OriginLauncher.App.Core.Loaders.RequiredModMissingException or JavaPathUnreadableException)
        {
            Note(key, ex.Message, LaunchTone.Warning, version);
            WriteLaunchErrorLog(ex);
            if (provisioning) failure = PlayFailure.From(ex, version);
        }
        catch (IncompatibleModsException ex)
        {
            Note(key, ex.Message, LaunchTone.Error, version);
            _modOverride.Refuse(key, version, ex.SetKey, ex.Message);
            WriteLaunchErrorLog(ex);
            refused = ex;
        }
        catch (ConfigurationInUseException ex)
        {
            Note(key, ex.Message, LaunchTone.Warning, version);
        }
        catch (MicrosoftAuthException ex)
        {
            var needsSignIn = SignInAgain.Needed(ex);
            var upToDate = needsSignIn ? SignInAgain.ExpiredLine : ex.Message;
            Note(key, await LaunchFailureTextAsync(ex, upToDate), LaunchTone.Error, version);
            // Straight to the fix: the prompt's "Sign in again" opens the sign-in card (re-adding refreshes the account).
            if (needsSignIn) _ = AskToSignInAgainAsync(accountName);
            WriteLaunchErrorLog(ex);
        }
        catch (Exception ex)
        {
            Note(key, await LaunchFailureTextAsync(ex, $"Launch failed: {DescribeError(ex)}"), LaunchTone.Error, version);
            WriteLaunchErrorLog(ex);
            if (provisioning) failure = PlayFailure.From(ex, version);
        }
        finally
        {
            if (!gameStarted) Book.EndPrepare(ticket);
            if (ReferenceEquals(_overlayTicket, ticket))
            {
                _overlayTicket = null;
                if (gameStarted && Book.GameOf(key) is RunningGame started)
                {
                    _sceneGame = started;
                    cover.EnterGameStart();
                    if (started.Key != HomePage.SelectedKey()) ReleaseScene(started);
                }
                else if (failure != null)
                {
                    _failedLaunch = target with { Version = version, AccountKey = key };
                    cover.ShowFailure(failure);
                }
                else
                {
                    CloseCover();
                }
            }
            ticket.Source.Dispose();
            Raise();
        }
        if (refused != null && route is LaunchRoute.Play or LaunchRoute.EnterKey)
            _ = Overlays.FixCompatibility.OfferAsync(_window, refused, version, profile);
    }

    // ---- The cover ----

    private LaunchCover OpenCover()
    {
        if (_cover == null)
        {
            _cover = new LaunchCover();
            _cover.CancelRequested += () =>
            {
                if (_overlayTicket != null) _overlayTicket.Source.Cancel();
                else if (_sceneGame != null) StopStartingGame(_sceneGame);
            };
            // A failed install: Retry runs the same launch again, Close lowers the screen. Files stay as they were.
            _cover.RetryRequested += async () =>
            {
                if (_failedLaunch is not { } again) { CloseCover(); return; }
                _failedLaunch = null;
                await LaunchAsync(again, LaunchRoute.Play);
                // Refused before it began (update required, an import running): the status line says why.
                if (_cover is { ShowsFailure: true } && _failedLaunch == null) CloseCover();
            };
            _cover.CloseRequested += () =>
            {
                _failedLaunch = null;
                CloseCover();
                Raise();
            };
        }
        if (!_window.CoverOpen) _window.OpenCover(_cover);
        return _cover;
    }

    private void CloseCover()
    {
        if (_window.CoverOpen) _window.CloseCover();
    }

    // The launch screen lowers. When it lowers because the game's own loading screen is up and the launcher is still the
    // active window, the game's window is brought to the front once (WPF HomePage.ReleaseScene): no loop, and never
    // when the player has moved on to something else.
    private void ReleaseScene(RunningGame game, bool gameLoading = false)
    {
        if (!ReferenceEquals(_sceneGame, game)) return;
        _sceneGame = null;
        CloseCover();
        if (gameLoading && _window.IsActive) TryBringForward(game);
    }

    /// <summary>The Play button's "Running" click (twin of the WPF FocusGame): that account's game window comes to the
    /// front, restored if minimised. When the desktop won't say which window it is, a toast says where the game is, so
    /// the click is never silent.</summary>
    private void FocusGame(string key)
    {
        if (Book.GameOf(key) is RunningGame game && !TryBringForward(game))
            _window.Toast("Minecraft is already open - switch to it from your taskbar");
    }

    private static bool TryBringForward(RunningGame game)
    {
        try { return App.Core.Desktop.LinuxGameWindow.BringToFront(game.Process.Id); }
        catch (InvalidOperationException) { return false; } // the process just exited
    }

    private static void StopStartingGame(RunningGame game)
    {
        game.StoppedByPlayer = true;
        try { game.Process.Kill(entireProcessTree: true); }
        catch (Exception) { /* already gone: the boot watch sees the exit either way */ }
    }

    // ---- The running game ----

    // Linux has no portable top-level window probe, so the handshake (Core/Launch/GameHandoff) is the signal: the
    // game's own stage lines release the cover, and its ready ceiling releases it for a silent build.
    private async Task WatchBootAsync(RunningGame game, string runningMessage)
    {
        var process = game.Process;
        try
        {
            while (true)
            {
                bool exited;
                try { exited = process.HasExited; }
                catch (Exception) { break; }

                if (exited)
                {
                    int exitCode;
                    try { exitCode = process.ExitCode; } catch (Exception) { exitCode = -1; }
                    Book.Exited(game.Key, game);
                    SyncSocialMirror();
                    ReleaseScene(game);
                    if (game.StoppedByPlayer) { Note(game.Key, "Cancelled", LaunchTone.Neutral, game.Version); return; }
                    if (exitCode == 0) { Note(game.Key, "Ready", LaunchTone.Neutral, game.Version); return; }
                    Note(game.Key, "Minecraft closed while starting.", LaunchTone.Neutral, game.Version);
                    Dispatcher.UIThread.Post(() => _ = ShowCrashReportAsync(game, exitCode, crashedWhileStarting: true),
                        DispatcherPriority.Background);
                    return;
                }
                if (ApplyHandoff(game, runningMessage)) return;
                await Task.Delay(500);
            }
            ReleaseScene(game);
            if (Book.WindowShown(game.Key, game)) Note(game.Key, runningMessage, LaunchTone.Success, game.Version);
        }
        finally
        {
            Raise();
        }
    }

    private void OnHandoff(RunningGame game)
    {
        if (!ReferenceEquals(Book.GameOf(game.Key), game)) return;
        ApplyHandoff(game, runningMessage: null);
        Raise();
    }

    private bool ApplyHandoff(RunningGame game, string? runningMessage)
    {
        var view = game.Handoff.Poll(DateTime.UtcNow);
        if (view.Stage > game.Shown)
        {
            game.Shown = view.Stage;
            if (view.Stage == HandoffStage.Opening && ReferenceEquals(_sceneGame, game)) _cover?.ReportStage("Loading Minecraft");
            if (view.Stage is HandoffStage.Opening or HandoffStage.Loading && !view.GameReady)
                Note(game.Key, $"Loading {game.Version}", LaunchTone.Neutral, game.Version);
        }
        if (view.ReleaseScene) ReleaseScene(game, gameLoading: view.Stage >= HandoffStage.Loading);
        if (view.GameReady) CrashRetries.Clear(game.Key, game.Version); // it booted: the next crash starts a fresh chain
        if (!view.GameReady || runningMessage == null) return false;
        if (Book.WindowShown(game.Key, game)) Note(game.Key, runningMessage, LaunchTone.Success, game.Version);
        return true;
    }

    // The selected account's game is mirrored into Origin social (friends see what you play).
    private void SyncSocialMirror()
    {
        var key = HomePage.SelectedKey();
        var want = key != null && Book.PhaseOf(key) is GamePhase.Starting or GamePhase.Running
            ? Book.GameOf(key) as RunningGame
            : null;
        if (ReferenceEquals(want, _mirrored)) return;
        if (_mirrored is { MirrorId: > 0 } previous)
        {
            SocialHub.Game.Exited(previous.MirrorId);
            previous.MirrorId = 0;
        }
        _mirrored = want;
        if (want != null)
        {
            want.MirrorId = SocialHub.Game.Started(want.Version);
            if (want.LastWhereLine is { } line) SocialHub.Game.Observe(want.MirrorId, line);
        }
    }

    private void OnGameWhere(RunningGame game, string line)
    {
        game.LastWhereLine = line;
        if (ReferenceEquals(_mirrored, game) && game.MirrorId > 0) SocialHub.Game.Observe(game.MirrorId, line);
    }

    // The game's output goes to its own log file (the crash window reads it); every callback here is
    // exception-proof, because they run on the process's reader threads.
    private void StartWithLifecycleCapture(RunningGame game)
    {
        var process = game.Process;
        var version = game.Version;
        OriginPaths.EnsureScaffold();
        var logPath = Path.Combine(OriginPaths.Logs, $"{version}_{SafeFileName(game.AccountName)}_{DateTime.Now:yyyyMMdd_HHmmss}.log");
        game.LogPath = logPath;

        // Best-effort and capped at 50 MB (GameLogFile): a mod spamming errors can no longer fill the disk.
        var logWriter = GameLogFile.TryOpen(logPath);
        var gate = new object();
        var openStreams = 2;

        void OnData(string? data)
        {
            try
            {
                if (data == null)
                {
                    if (Interlocked.Decrement(ref openStreams) == 0)
                        lock (gate) { logWriter?.Dispose(); logWriter = null; }
                    return;
                }
                if (RunningGameTracker.ParseConnect(data) != null || RunningGameTracker.IsSingleplayerStart(data))
                    Dispatcher.UIThread.Post(() => OnGameWhere(game, data));
                if (game.Handoff.Observe(data)) Dispatcher.UIThread.Post(() => OnHandoff(game));
                lock (gate) { logWriter?.WriteLine(data); }
            }
            catch (Exception) { /* never surface onto a reader thread */ }
        }

        process.StartInfo.RedirectStandardOutput = true;
        process.StartInfo.RedirectStandardError = true;
        process.StartInfo.UseShellExecute = false;
        process.EnableRaisingEvents = true;
        process.OutputDataReceived += (_, args) => OnData(args.Data);
        process.ErrorDataReceived += (_, args) => OnData(args.Data);
        process.Exited += (_, _) =>
        {
            try
            {
                int exitCode;
                try { exitCode = process.ExitCode; } catch (Exception) { exitCode = -1; }
                Dispatcher.UIThread.Post(() => OnGameExited(game, exitCode));
                game.Configuration?.Dispose();
                SettingsSync.AfterExit(game.Key, version);
            }
            catch (Exception) { /* the game's exit must never crash the launcher */ }
        };

        try
        {
            process.Start();
            SettingsSync.TrackGame(game.Key, version, process);
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
        }
        catch
        {
            lock (gate) { logWriter?.Dispose(); logWriter = null; }
            game.Configuration?.Dispose();
            throw;
        }
    }

    private void OnGameExited(RunningGame game, int exitCode)
    {
        var wasRunning = Book.PhaseOf(game.Key) == GamePhase.Running && ReferenceEquals(Book.GameOf(game.Key), game);
        if (Book.Exited(game.Key, game) && wasRunning)
        {
            // Neutral whatever the exit code: only the crash verdict may turn it red (GameExitStatus).
            var line = GameExitStatus.OnExit();
            Note(game.Key, line.Text, line.Tone, game.Version);
        }
        SyncSocialMirror();
        Raise();
        if (wasRunning && exitCode != 0 && !game.StoppedByPlayer)
            Dispatcher.UIThread.Post(() => _ = ShowCrashReportAsync(game, exitCode, crashedWhileStarting: false),
                DispatcherPriority.Background);
    }

    // ---- Crashes ----

    private async Task ShowCrashReportAsync(RunningGame game, int exitCode, bool crashedWhileStarting)
    {
        try
        {
            var ramMb = game.Memory.UsedMb; // the heap this launch really used, not the stored slider value
            var customJvm = game.LaunchedJvmArgs?.Arguments;
            var totalMb = SystemInfo.GetTotalPhysicalMemoryMb();
            var key = game.Key;
            var fixTried = CrashRetries.AnyTried(key, game.Version, DateTime.UtcNow);
            var findings = await Task.Run(() => CrashDiagnoser.Run(game.GameDir, game.LogPath, exitCode,
                game.StoppedByPlayer, crashedWhileStarting, ramMb, totalMb, fixTried, game.StartedUtc, customJvm));
            var verdict = findings.Verdict;
            var idle = Book.PhaseOf(key) is not (GamePhase.Starting or GamePhase.Running);
            // Not a crash: back to "Ready"; a crash in game: "Minecraft crashed" (GameExitStatus, all three launchers).
            if (idle && GameExitStatus.AfterVerdict(verdict.IsCrash, crashedWhileStarting) is { } after)
                Note(key, after.Text, after.Tone, game.Version);
            if (!verdict.IsCrash) return;
            // The line stays plain words (the crash window has the exit code and the log); a boot crash re-checks
            // the install next time only when a bad file could be the cause (CrashVerdict.InstallFilesSuspect).
            if (crashedWhileStarting && verdict.InstallFilesSuspect)
                InstallVerification.Invalidate(OriginPaths.VersionRoot(game.Version));

            var launcherVersion = UpdateService.CurrentVersion.ToString();
            var user = Environment.UserName;
            string BuildReport() => CrashReportBundle.Build(new CrashReportBundle.Input(
                launcherVersion, game.Version, ramMb, totalMb, exitCode, verdict,
                findings.LogText, findings.CrashReportText, [user, game.AccountName]));

            var window = new CrashReportWindow(verdict, game.LogPath, $"{game.AccountName} - Minecraft {game.Version}", BuildReport);
            await window.ShowDialog(_window);
            var tryLine = window.ChoseTryWithoutAddedMods
                ? await Task.Run(() => ModSuspects.LineFor(game.GameDir, verdict, findings.Evidence.Mods))
                : null;
            await ApplyCrashChoiceAsync(game, window.ChosenFix, tryLine);
        }
        catch (Exception ex)
        {
            Trace.WriteLine($"[Launch] Crash analysis failed: {ex}");
        }
    }

    // tryWithoutAddedMods: non-null when the player chose "Try once without my mods"; it is the Home line for that try.
    private async Task ApplyCrashChoiceAsync(RunningGame game, CrashFix? fix, string? tryWithoutAddedMods)
    {
        if (fix == null && tryWithoutAddedMods == null) return;
        if (Book.PhaseOf(game.Key) is GamePhase.Starting or GamePhase.Running) return;
        if (!CrashRetries.TryBegin(game.Key, game.Version, fix?.Kind ?? CrashFixKind.None, DateTime.UtcNow)) return;

        if (fix == null)
        {
            _tryWithoutAddedModsOnce[game.Key] = tryWithoutAddedMods!;
        }
        else
        {
            switch (fix.Kind)
            {
                case CrashFixKind.RaiseMemory when fix.NewRamMb is int ram:
                    // For this version only, like Windows (CrashMemory.ApplyRaise, KC-10): Auto stays Auto and every
                    // other version keeps its own heap.
                    if (!LauncherState.Write(s => CrashMemory.ApplyRaise(s, game.Version, ram)))
                    {
                        // A busy settings.json: relaunching on the same heap would only run out again.
                        Note(game.Key, SettingsStore.BusyLine, LaunchTone.Error, game.Version);
                        return;
                    }
                    break;
                case CrashFixKind.DisableMods:
                    var failed = new List<(string, CrashRepairOutcome)>();
                    foreach (var jar in fix.Jars)
                    {
                        var result = CrashRepair.DisableBlamed(game.Version, _profileByKey.GetValueOrDefault(game.Key), jar,
                            App.Core.Mods.ModManager.IsManagedFile);
                        if (result.Outcome != CrashRepairOutcome.Disabled) failed.Add((jar, result.Outcome));
                    }
                    if (failed.Count > 0)
                    {
                        Note(game.Key, CrashRepair.FailureLine(game.Version, failed), LaunchTone.Error, game.Version);
                        return; // relaunching with the cause still in place would only crash again
                    }
                    break;
                case CrashFixKind.ResetJavaArgs:
                    var reset = RepairExecutor.ApplySettings(
                        RepairPlanner.ResetJavaArgs(game.Version, game.LaunchedJvmArgs, "Java refused to start the game with them."),
                        SettingsStore.Update);
                    LauncherState.ReloadSettings();
                    if (!reset.AllOk)
                    {
                        Note(game.Key, reset.Failed.First().Error ?? "Couldn't reset the Java arguments.", LaunchTone.Error, game.Version);
                        return;
                    }
                    break;
                case CrashFixKind.RepairFiles:
                    InstallVerification.Invalidate(OriginPaths.VersionRoot(game.Version));
                    break;
                default:
                    return;
            }
        }
        await LaunchAsync(new LaunchTarget(game.Version, AccountKey: game.Key), LaunchRoute.Retry);
    }

    // ---- Errors ----

    private static void WriteLaunchErrorLog(Exception ex)
    {
        try
        {
            OriginPaths.EnsureScaffold();
            File.WriteAllText(Path.Combine(OriginPaths.Logs, $"launcher_error_{DateTime.Now:yyyyMMdd_HHmmss}.log"), ex.ToString());
        }
        catch (Exception) { /* best-effort */ }
    }

    // Play failed on an expired sign-in: ask once, and "Sign in again" goes straight to the sign-in card (adding an
    // account that is already there refreshes it; nothing has to be removed). The WPF MainWindow's twin.
    private async Task AskToSignInAgainAsync(string accountName)
    {
        if (ShotRunner.Active) return;
        if (await Kit.Confirm.AskAsync(_window, SignInAgain.Title, SignInAgain.Prompt(accountName), SignInAgain.Button,
                SignInAgain.Later))
            SignInPanel.Open(_window);
    }

    // A failure on an outdated launcher is very likely already fixed: say so instead of the raw error.
    // The re-check is skipped for "no connection" and capped otherwise (Core/Updates/LaunchFailureText).
    private static Task<string> LaunchFailureTextAsync(Exception ex, string upToDateMessage) =>
        LaunchFailureText.ForAsync(ex, upToDateMessage,
            "Launch failed - please update the launcher (click the update dot in the top-right). This is very likely already fixed in the newest version.",
            UpdateService.CheckAsync, () => UpdateService.UpdateRequired);

    private static string DescribeError(Exception ex)
    {
        var inner = ex;
        while (inner.InnerException != null) inner = inner.InnerException;
        var code = ex is HttpRequestException http && http.HttpRequestError != HttpRequestError.Unknown
            ? http.HttpRequestError.ToString()
            : inner.GetType().Name;
        var detail = ReferenceEquals(inner, ex) || inner.Message == ex.Message ? ex.Message : $"{ex.Message} - {inner.Message}";
        return $"{detail} [{code}]";
    }

    private static string SafeFileName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var safe = new string(name.Select(c => invalid.Contains(c) ? '_' : c).ToArray());
        return safe.Length == 0 ? "player" : safe;
    }

    // ---- Render runs ----

    /// <summary>Shots: the launch cover up for a version, as a launch shows it.</summary>
    public LaunchCover TourCover(string version, string caption)
    {
        var cover = OpenCover();
        cover.Show(version, caption);
        return cover;
    }

    /// <summary>Shots: the selected account's game running ("Running - name").</summary>
    public void TourRunning(string name, IReadOnlyList<string>? notices = null)
    {
        if (HomePage.SelectedKey() is not { } key) return;
        var version = LauncherState.SelectedVersion;
        if (Book.BeginPrepare(key, version) is not { } ticket) return;
        var fake = new object();
        Book.GameStarted(ticket, fake);
        Book.WindowShown(key, fake);
        Note(key, LaunchedMods.WithNotices($"Running - {name}", notices ?? []), LaunchTone.Success, version);
    }

    /// <summary>Shots: Play refused for these mods (the status line in red, as a refused launch leaves it).</summary>
    public void TourRefuse(IncompatibleModsException refused)
    {
        if (HomePage.SelectedKey() is { } key) Note(key, refused.Message, LaunchTone.Error);
    }
}
