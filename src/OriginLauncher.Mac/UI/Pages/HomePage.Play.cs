using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
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
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac.UI.Pages;

// Play, end to end (twin of the WPF UI/Pages/HomePage.xaml.cs launch flow): the version pick, the gates (import running,
// one game per account, the mandatory update), the sign-in, the install, the launch and the game's start and exit. Every
// decision is Core's (AccountLaunchBook, LaunchOverride, ProfileRules, LaunchHeap, LaunchProfileBuilder, VersionManager,
// GameHandoff, CrashDiagnoser, PlayFailure, ...); this file only puts their answers on screen, in the same order as the
// Windows page. Mac differences, each for a reason:
//  * Process.MainWindowHandle is always 0 on macOS, so the boot watch never "sees" the game's window: the game's own
//    handshake markers (GameHandoff) and its fallback ceilings lower the screen instead.
//  * A version that runs through Rosetta 2 on Apple Silicon, with Rosetta missing, fails with "Install Rosetta" on the
//    launch screen (PlayFailure.OffersRosettaInstall); the click runs MacRosetta.InstallAsync, then Play again.
//  * Bringing the game forward is NSRunningApplication's activate (MacGameWindow), not SetForegroundWindow.
public sealed partial class HomePage
{
    private readonly LauncherSettings _settings = SettingsStore.Load();
    private readonly VersionManager _versionManager = new();
    private StoredAccount? _selectedAccount;
    private string? _selectedVersion;

    // Several games at once, one per account; the newest launch action wins PER ACCOUNT (Core/GameFolders).
    private readonly AccountLaunchBook _launches = new();
    private readonly Dictionary<string, string?> _profileByKey = new();
    private LaunchTicket? _overlayTicket;

    private sealed record LaunchTarget(string Version, string? Server = null, string? AccountKey = null);

    private LaunchTarget? _failedLaunch;
    private readonly LaunchOverride _modOverride = new();

    private sealed class RunningGame
    {
        public required string Key { get; init; }
        public required string Version { get; init; }
        public required string AccountName { get; init; }
        public required string GameDir { get; init; }
        public required Process Process { get; init; }
        public string? LogPath { get; set; }
        public string? LastWhereLine { get; set; }
        public int MirrorId { get; set; }
        public required GameHandoff Handoff { get; init; }
        public HandoffStage Shown { get; set; }
        public bool StoppedByPlayer { get; set; }
        public DateTime StartedUtc { get; init; } = DateTime.UtcNow;
        public IDisposable? Configuration { get; init; }
        public VersionJvmOptions? LaunchedJvmArgs { get; init; }
    }

    private RunningGame? _sceneGame;

    /// <summary>The launch screen. Home owns it; MainWindow puts it above the top bar (it covers the whole window).</summary>
    internal LaunchLoadingOverlay LoadingOverlay { get; } = new();

    /// <summary>The version on screen changed (the art stage behind every page follows it).</summary>
    public event Action<string?>? VersionShown;

    // Wired once from the constructor (HomePage.axaml.cs).
    private void InitPlay()
    {
        LoadingOverlay.UpdateWaitCancelRequested += () => UpdateWaitCancelRequested?.Invoke();
        LoadingOverlay.CancelRequested += () =>
        {
            if (_overlayTicket != null) _overlayTicket.Source.Cancel();
            else if (_sceneGame != null) StopStartingGame(_sceneGame);
        };
        LoadingOverlay.RetryRequested += async () =>
        {
            if (_failedLaunch is not { } again) { LoadingOverlay.Hide(); return; }
            _failedLaunch = null;
            await LaunchAsync(again, LaunchRoute.Play);
            if (LoadingOverlay.ShowsFailure && _failedLaunch == null) LoadingOverlay.Hide();
        };
        LoadingOverlay.CloseRequested += () =>
        {
            _failedLaunch = null;
            LoadingOverlay.Hide();
            RefreshPlayButton();
        };
        // Mac only: the Rosetta failure's one-click fix. Apple's own installer (softwareupdate), then Play again.
        LoadingOverlay.RosettaRequested += async () =>
        {
            var result = await MacRosetta.InstallAsync();
            if (!result.Installed)
            {
                LoadingOverlay.ShowRosettaResult(result.Detail);
                return;
            }
            if (_failedLaunch is not { } again) { LoadingOverlay.Hide(); return; }
            _failedLaunch = null;
            await LaunchAsync(again, LaunchRoute.Play);
        };
        LoadingOverlay.PropertyChanged += (_, e) => { if (e.Property == IsVisibleProperty) OnSceneVisibilityChanged(); };
        VersionOverlay.PropertyChanged += (_, e) => { if (e.Property == IsVisibleProperty) OnSceneVisibilityChanged(); };
        VersionOverlay.VersionSelected += OnVersionSelected;
        ImportRunState.Changed += () => Dispatcher.UIThread.Post(UpdatePlayState);
        InitVersionSelection();
        RefreshAccountState();
    }

    // The strip steps aside while the picker or the launch screen is up (opacity only, so it never re-lays out). Under
    // the launch screen the numeral goes too and the window hides its bar (WPF HomePage.OnSceneVisibilityChanged +
    // ArtStage.BeginLaunch / EndLaunch + MainWindow.SetTopBarHidden).
    private void OnSceneVisibilityChanged()
    {
        var launching = LoadingOverlay.IsVisible;
        var away = launching || VersionOverlay.IsOpen;
        HomeCard.IsHitTestVisible = !away;
        HomeCard.Opacity = away ? 0 : 1;
        if (launching == _launchShown) return;
        _launchShown = launching;
        if (ArtStage.Instance is { } stage) stage.Numeral.Opacity = launching ? 0 : 1;
        LaunchSceneChanged?.Invoke(launching);
    }

    private bool _launchShown;

    /// <summary>The launch screen came up (true) or went away (false): the window hides or shows its bar.</summary>
    public event Action<bool>? LaunchSceneChanged;

    /// <summary>Play was refused for a mandatory update: the window asks once, and Update installs it.</summary>
    public event Action? UpdateRequiredRefused;

    /// <summary>Play failed because this account's saved Microsoft sign-in no longer works (its name): the window asks
    /// once, and "Sign in again" opens the sign-in card.</summary>
    public event Action<string>? SignInAgainRequested;

    public string? CurrentVersion => _selectedVersion;

    public bool HasLaunchCover => LoadingOverlay.IsVisible;

    public bool HasOpenScene => VersionOverlay.IsOpen || LoadingOverlay.IsVisible;

    /// <summary>MainWindow: a nav click while the picker is open closes it and carries on.</summary>
    public void CloseVersionPicker() => VersionOverlay.Dismiss();

    public bool IsProfileInUse(string profileId) =>
        _profileByKey.Any(entry => entry.Value == profileId && _launches.PhaseOf(entry.Key) != GamePhase.Idle);

    /// <summary>MainWindow, when Home comes back into view: the Profiles page may have switched the version.</summary>
    public void RefreshProfile()
    {
        var settings = SettingsStore.Load();
        if (settings.SelectedVersion is { } picked && picked != _selectedVersion && VersionCatalog.IsSupported(picked))
        {
            _selectedVersion = picked;
            _settings.SelectedVersion = picked;
            ShowVersionOnCard(picked);
            SocialHub.PresenceVersion = picked;
        }
        MyMods = settings.PlayWithExternalMods;
        ApplyProfile(settings); // a profile picked on the Profiles page: its Join card
        UpdatePlayState();
    }

    /// <summary>Friends -> Join / party warp: launch straight into a server on the selected account.</summary>
    public async Task JoinServerAsync(string version, string server)
    {
        if (SelectedKey() is { } key && _launches.PhaseOf(key) is GamePhase.Starting or GamePhase.Running)
        {
            Note(key, _launches.PhaseOf(key) == GamePhase.Starting
                ? "Minecraft is still starting on this account - try Join again in a moment"
                : "Minecraft is already open on this account - close it to join, or switch accounts", LaunchTone.Warning);
            FocusGame(key);
            return;
        }
        await LaunchAsync(new LaunchTarget(version, server), LaunchRoute.JoinServer);
    }

    /// <summary>Cancel on the self-update's "Waiting for Minecraft to close": MainWindow stops waiting.</summary>
    public event Action? UpdateWaitCancelRequested;

    // The launcher's self-update uses the same screen (no Cancel while it downloads: updates are mandatory; Cancel only
    // while the swap waits for Minecraft to close).
    public bool BeginUpdateScene(string newVersion)
    {
        if (_overlayTicket != null || LoadingOverlay.IsVisible) return false;
        LoadingOverlay.ShowUpdate(newVersion);
        return true;
    }

    public void ReportUpdate(UpdateService.UpdateProgress progress) => LoadingOverlay.ReportUpdate(progress);

    public void EndUpdateScene() => LoadingOverlay.Hide();

    /// <summary>Return / Enter on Home. Refused while a picker or the launch screen is open.</summary>
    public bool TryLaunchFromKeyboard()
    {
        if (!PlayButton.IsEnabled || VersionOverlay.IsOpen || LoadingOverlay.IsVisible) return false;
        _ = LaunchAsync(null, LaunchRoute.EnterKey);
        return true;
    }

    public void RefreshAccountState()
    {
        _selectedAccount = AccountStore.GetSelected(AccountStore.Load());
        AccountFolders.EnsureMigrated(SelectedKey());
        _modOverride.ContextChanged(SelectedKey(), _selectedVersion);
        if (_sceneGame != null && _sceneGame.Key != SelectedKey())
            ReleaseScene(_sceneGame, gameLoading: false);
        UpdatePlayState();
        SyncSocialMirror();
    }

    public void RefreshUpdateGate()
    {
        if (SelectedPhase() != GamePhase.Idle) return;
        if (UpdateService.UpdateRequired) SetStatus(UpdateRequiredWords.Status, StatusTone.Warning);
        else UpdatePlayState();
    }

    private string? SelectedKey() =>
        _selectedAccount != null
            ? InstanceLayout.KeyFor(_selectedAccount.Id)
            : SettingsStore.Load().OfflineTestMode ? InstanceLayout.OfflineKey : null;

    private GamePhase SelectedPhase() => SelectedKey() is { } key ? _launches.PhaseOf(key) : GamePhase.Idle;

    private const string ImportRunningText = "Importing - Play is back when it finishes";

    private void UpdatePlayState()
    {
        RefreshPlayButton();
        var key = SelectedKey();
        if (ImportRunState.IsApplying)
            SetStatus(ImportRunningText, StatusTone.Warning);
        else if (key != null && _launches.PhaseOf(key) != GamePhase.Idle && _launches.NoteOf(key) is { } note)
            SetStatus(note.Text, ToneOf(note.Tone));
        else if (key != null && _selectedVersion == null)
            SetStatus("Select a version to play", StatusTone.Neutral);
        else if (UpdateService.UpdateRequired)
            SetStatus(UpdateRequiredWords.Status, StatusTone.Warning);
        else if (_selectedAccount != null)
            SetStatus("Ready to play", StatusTone.Success);
        else if (key != null)
            SetStatus("Ready to play offline", StatusTone.Warning);
        else
            SetStatus("Sign in to play", StatusTone.Neutral);
    }

    private void RefreshPlayButton()
    {
        var key = SelectedKey();
        var phase = key != null ? _launches.PhaseOf(key) : GamePhase.Idle;
        ApplyPlayButton(AccountLaunchBook.ButtonFor(phase, canPlay: key != null, hasVersion: _selectedVersion != null));
        if (ImportRunState.IsApplying || _fixRunning) PlayButton.IsEnabled = false;
    }

    // One face at a time, the button's width never changing: Play, the spinning arc, or "Running".
    private void ApplyPlayButton(PlayButtonView view)
    {
        PlayButton.IsEnabled = view.Enabled;
        Avalonia.Automation.AutomationProperties.SetName(PlayButton, view.ShowsRunning ? "Switch to Minecraft" : "Play Minecraft");
        PlaySpinner.IsVisible = view.Spinner;
        PlaySpinner.Classes.Set("spinning", view.Spinner && !Motion.Off);
        // Opacity 0 (not hidden) keeps the Play face's width while the spinner stands in for it.
        PlayFace.IsVisible = !view.ShowsRunning || view.Spinner;
        PlayFace.Opacity = !view.Spinner && !view.ShowsRunning ? 1 : 0;
        RunningLabel.IsVisible = !view.Spinner && view.ShowsRunning;
        PlayButton.Classes.Set("busy", view.Spinner || view.ShowsRunning);
    }

    private void Note(string key, string text, LaunchTone tone)
    {
        _launches.SetNote(key, new LaunchNote(text, tone));
        if (SelectedKey() == key) SetStatus(text, ToneOf(tone));
    }

    private static StatusTone ToneOf(LaunchTone tone) => tone switch
    {
        LaunchTone.Success => StatusTone.Success,
        LaunchTone.Warning => StatusTone.Warning,
        LaunchTone.Error => StatusTone.Error,
        _ => StatusTone.Neutral,
    };

    private void FocusGame(string key)
    {
        if (_launches.GameOf(key) is RunningGame game) MacGameWindow.BringToFront(game.Process);
    }

    private void InitVersionSelection()
    {
        _selectedVersion = VersionCatalog.IsSupported(_settings.SelectedVersion)
            ? _settings.SelectedVersion
            : VersionCatalog.DefaultVersion;
        ShowVersionOnCard(_selectedVersion);
        SocialHub.PresenceVersion = _selectedVersion;
        MyMods = _settings.PlayWithExternalMods;
        ApplyProfile(_settings);
        UpdatePlayState();
    }

    // The picker's pick. Does exactly one thing: records the new version. No other setting moves.
    private void OnVersionSelected(string version)
    {
        _selectedVersion = version;
        _modOverride.ContextChanged(SelectedKey(), version);
        _settings.SelectedVersion = version;
        SettingsStore.TryUpdate(s => s.SelectedVersion = version);
        ShowVersionOnCard(version);
        SocialHub.PresenceVersion = version;
        ApplyProfile(SettingsStore.Load()); // a profile applies to its own version only: Join follows the pick
        UpdatePlayState();
    }

    /// <summary>Home on a version (the Import page's Play). A version Origin doesn't ship is ignored.</summary>
    public void ShowVersion(string version)
    {
        if (!VersionCatalog.IsSupported(version)) return;
        if (version != _selectedVersion) OnVersionSelected(version);
    }

    public void PlayVersion(string version)
    {
        if (!VersionCatalog.IsSupported(version)) return;
        ShowVersion(version);
        TryLaunchFromKeyboard();
    }

    private void ShowVersionOnCard(string? version)
    {
        if (version != null) SetVersion(version);
        else VersionButtonText.Text = "Pick one";
        VersionShown?.Invoke(version);
    }

    private void VersionButton_Click(object? sender, RoutedEventArgs e) => VersionOverlay.Show(_selectedVersion);

    private void MyModsSwitch_Click(object? sender, RoutedEventArgs e)
    {
        var on = MyModsSwitch.IsChecked == true;
        _settings.PlayWithExternalMods = on;
        SettingsStore.TryUpdate(s => s.PlayWithExternalMods = on);
    }

    private async void PlayButton_Click(object? sender, RoutedEventArgs e) => await LaunchAsync(null, LaunchRoute.Play);

    // ---- The Play-flow proof (`--play-plan`, PlayPlan.cs) ----
    // Set only by the headless proof, and honoured only in a scratch data folder: the launch runs every step for real and
    // hands over the built game process instead of starting it. Null in the launcher: nothing changes.
    internal Action<Process>? PlayPlanProbe { get; set; }

    /// <summary>Every stage line the launch screen showed, in order (the Play-flow proof prints them).</summary>
    internal event Action<string>? StageReported;

    /// <summary>The Play-flow proof: presses Play exactly as a click does and waits for the launch to settle.</summary>
    internal Task PressPlayForPlan() => LaunchAsync(null, LaunchRoute.Play);

    internal string StatusLine => StatusText.Text ?? "";

    private async Task LaunchAsync(LaunchTarget? target, LaunchRoute route)
    {
        if (ImportRunState.IsApplying)
        {
            SetStatus(ImportRunningText, StatusTone.Warning);
            return;
        }
        LaunchPrewarm.Stop();

        var freshSettings = SettingsStore.Load();
        var offlineTest = freshSettings.OfflineTestMode;
        var externalMods = freshSettings.PlayWithExternalMods;

        StoredAccount? account;
        string key;
        if (target?.AccountKey is { } wanted)
        {
            account = wanted == InstanceLayout.OfflineKey
                ? null
                : AccountStore.Load().FirstOrDefault(a => InstanceLayout.KeyFor(a.Id) == wanted);
            if (account == null && !(wanted == InstanceLayout.OfflineKey && offlineTest)) return;
            key = wanted;
        }
        else
        {
            account = _selectedAccount;
            if (account == null && !offlineTest) return;
            key = InstanceLayout.KeyFor(account?.Id);
        }
        if ((target?.Version ?? _selectedVersion) is not { } version) return;
        var accountName = account?.Gamertag ?? "Offline";

        if (_tryWithoutAddedModsOnce.Remove(key) && route == LaunchRoute.Retry) externalMods = false;

        switch (AccountLaunchBook.ActionFor(_launches.PhaseOf(key)))
        {
            case PlayAction.FocusGame:
                if (_launches.VersionOf(key) is { } playing && playing != version)
                    Note(key, $"{accountName} is already playing {playing} - close it first to play {version}", LaunchTone.Warning);
                FocusGame(key);
                return;
            case PlayAction.None when _launches.PhaseOf(key) == GamePhase.Starting:
                return;
        }
        if (_launches.PhaseOf(key) == GamePhase.Idle
            && AccountFolders.VersionPlayingOutsideLauncher(key) is { } openVersion)
        {
            Note(key, $"Minecraft {openVersion} is still open on {accountName} - close it first", LaunchTone.Warning);
            return;
        }

        // Mandatory updates: the poller's last answer, never a network wait in front of Play.
        if (UpdateService.UpdateRequired)
        {
            Note(key, UpdateRequiredWords.Status, LaunchTone.Warning);
            // The line alone was easy to miss (Windows' fix, v1.0.85): the click also opens the Update required prompt,
            // whose Update button installs it (MainWindow.Update.cs AskToUpdateAsync).
            UpdateRequiredRefused?.Invoke();
            return;
        }

        var profile = ProfileRules.ForLaunch(freshSettings.ModProfiles, freshSettings.SelectedProfileId, version, externalMods);
        var overrideSetKey = _modOverride.Accept(route, key, version, SelectedKey() == key ? StatusText.Text : null);
        if (_launches.BeginPrepare(key, version) is not { } ticket) return;
        using var launchRun = LaunchRunState.Enter();
        _profileByKey[key] = profile?.Id;
        _overlayTicket = ticket;
        _sceneGame = null;
        RefreshPlayButton();
        LoadingOverlay.Show(version, profile != null || target?.Server != null
            ? $"{accountName} - {target?.Server ?? profile!.Name}" : accountName);

        void Stage(string text)
        {
            if (!ReferenceEquals(_overlayTicket, ticket)) return;
            LoadingOverlay.ReportStage(text);
            StageReported?.Invoke(text);
        }

        var gameStarted = false;
        var provisioning = false;
        PlayFailure? failure = null;
        IncompatibleModsException? refused = null;

        try
        {
            MSession session;
            if (account != null)
            {
                Stage("Signing in");
                var signedIn = await MinecraftSessionBroker.ForPlayAsync(account.Id, ticket.Token);
                if (signedIn == null)
                {
                    Note(key, SignInAgain.UnreadableLine, LaunchTone.Error);
                    SignInAgainRequested?.Invoke(accountName);
                    return;
                }
                session = signedIn;
            }
            else
            {
                Stage("Offline session");
                session = MSession.CreateOfflineSession("OriginTester");
            }

            ticket.Token.ThrowIfCancellationRequested();

            var gameDir = await Task.Run(() => AccountFolders.PrepareGameDir(version, key), ticket.Token);
            var (heap, legacy) = await LaunchHeap.PlanAsync(freshSettings, version, gameDir, ticket.Token);
            if (heap.Warning != null) Stage(heap.Warning);
            var launchOption = LaunchProfileBuilder.Build(freshSettings, session, version, heap, legacy);
            var handoff = new GameHandoff();
            launchOption.ExtraJvmArguments = (launchOption.ExtraJvmArguments ?? Enumerable.Empty<MArgument>())
                .Append(new MArgument(handoff.JvmArgument)).ToList();
            if (target?.Server is { } server)
            {
                var join = JoinArguments.Build(version, server);
                if (join.Count > 0)
                    launchOption.ExtraGameArguments = launchOption.ExtraGameArguments
                        .Append(new MArgument(join.ToArray())).ToList();
            }

            // Progress<T> made on the UI thread marshals every stage back to it from the worker.
            var installProgress = new Progress<string>(Stage);
            provisioning = true;
            string? configurationId = null;
            var process = await Task.Run(
                () => _versionManager.InstallAndBuildProcessAsync(
                    version, key, launchOption, externalMods, profile?.Id, installProgress, ticket.Token, overrideSetKey,
                    profile?.ComponentPins, id => configurationId = id),
                ticket.Token);
            provisioning = false;

            GpuPreference.PreferHighPerformanceGpu(process.StartInfo.FileName);
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
            // The Play-flow proof stops here, with everything above done for real (scratch data folder only).
            if (PlayPlanProbe is { } probe && OriginPaths.IsScratchRoot)
            {
                probe(process);
                Note(key, "Play plan: stopped before the game starts", LaunchTone.Neutral);
                return;
            }

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
            };
            StartWithLifecycleCapture(game);
            handoff.ProcessStarted(DateTime.UtcNow);
            gameStarted = _launches.GameStarted(ticket, game);
            SyncSocialMirror();

            Note(key, $"Starting {version}", LaunchTone.Neutral);
            var runningMessage = target?.Server is { } joined
                ? $"Playing on {SocialText.DisplayServer(joined)} - {version}"
                : $"Running - {session.Username}";
            _ = WatchBootAsync(game, runningMessage);
        }
        catch (OperationCanceledException ex) when (PlayFailure.IsUserCancel(ex, ticket.Token))
        {
            Note(key, "Cancelled", LaunchTone.Neutral);
        }
        catch (MicrosoftAuthException ex) when (ex.RateLimited)
        {
            Note(key, MicrosoftAuthException.RateLimitedMessage, LaunchTone.Warning);
            WriteLaunchErrorLog(ex);
        }
        catch (OfflineInstallException ex)
        {
            Note(key, ex.Message, LaunchTone.Warning);
            WriteLaunchErrorLog(ex);
            if (provisioning) failure = PlayFailure.From(ex, version);
        }
        catch (IncompatibleModsException ex)
        {
            Note(key, ex.Message, LaunchTone.Error);
            _modOverride.Refuse(key, version, ex.SetKey, ex.Message);
            WriteLaunchErrorLog(ex);
            refused = ex;
        }
        catch (ConfigurationInUseException ex)
        {
            Note(key, ex.Message, LaunchTone.Warning);
        }
        catch (MicrosoftAuthException ex)
        {
            var needsSignIn = SignInAgain.Needed(ex);
            var upToDateMessage = needsSignIn ? SignInAgain.ExpiredLine : ex.Message;
            Note(key, await LaunchFailureTextAsync(upToDateMessage), LaunchTone.Error);
            // Straight to the fix: the prompt's "Sign in again" opens the sign-in card (re-adding refreshes the account).
            if (needsSignIn) SignInAgainRequested?.Invoke(accountName);
            WriteLaunchErrorLog(ex);
        }
        catch (RosettaRequiredException ex)
        {
            // Apple Silicon, a version that runs through Rosetta, and no Rosetta: the launch screen offers the fix.
            Note(key, $"Minecraft {version} needs Rosetta", LaunchTone.Warning);
            failure = PlayFailure.From(ex, version);
        }
        catch (Exception ex)
        {
            Note(key, await LaunchFailureTextAsync($"Launch failed: {DescribeError(ex)}"), LaunchTone.Error);
            WriteLaunchErrorLog(ex);
            if (provisioning) failure = PlayFailure.From(ex, version);
        }
        finally
        {
            if (!gameStarted) _launches.EndPrepare(ticket);
            if (ReferenceEquals(_overlayTicket, ticket))
            {
                _overlayTicket = null;
                if (gameStarted && _launches.GameOf(key) is RunningGame started)
                {
                    _sceneGame = started;
                    LoadingOverlay.EnterGameStart();
                    if (started.Key != SelectedKey()) ReleaseScene(started, gameLoading: false);
                }
                else if (failure != null)
                {
                    _failedLaunch = target is { } t ? t with { Version = version, AccountKey = key } : new LaunchTarget(version, AccountKey: key);
                    LoadingOverlay.ShowFailure(failure);
                }
                else
                {
                    LoadingOverlay.Hide();
                }
            }
            ticket.Source.Dispose();
            RefreshPlayButton();
        }
        if (refused != null && route is LaunchRoute.Play or LaunchRoute.EnterKey) _ = OfferFixAsync(refused, version, profile);
    }

    // The tail of one game's launch: the screen stays up until the game's own window shows its loading screen (the
    // handshake says so), or the process dies while starting (a crash: say so, then explain it).
    private async Task WatchBootAsync(RunningGame game, string runningMessage)
    {
        var process = game.Process;
        try
        {
            while (true)
            {
                bool exited;
                try { exited = process.HasExited; }
                catch { break; }

                if (exited)
                {
                    int exitCode;
                    try { exitCode = process.ExitCode; } catch { exitCode = -1; }
                    _launches.Exited(game.Key, game);
                    SyncSocialMirror();
                    ReleaseScene(game, gameLoading: false);
                    if (game.StoppedByPlayer)
                    {
                        Note(game.Key, "Cancelled", LaunchTone.Neutral);
                        return;
                    }
                    if (exitCode == 0)
                    {
                        Note(game.Key, "Ready", LaunchTone.Neutral);
                        return;
                    }
                    Note(game.Key, $"Minecraft closed while starting (exit code {exitCode}).", LaunchTone.Neutral);
                    Dispatcher.UIThread.Post(async () => await ShowCrashReportAsync(game, exitCode, crashedWhileStarting: true),
                        DispatcherPriority.Background);
                    return;
                }

                // No window check on a Mac: MainWindowHandle is always 0 there. The handshake (and its ceilings) decide.
                if (ApplyHandoff(game, runningMessage)) return;
                await Task.Delay(500);
            }
            ReleaseScene(game, gameLoading: false);
            if (_launches.WindowShown(game.Key, game))
                Note(game.Key, runningMessage, LaunchTone.Success);
        }
        finally
        {
            RefreshPlayButton();
        }
    }

    private void OnHandoff(RunningGame game)
    {
        if (!ReferenceEquals(_launches.GameOf(game.Key), game)) return;
        ApplyHandoff(game, runningMessage: null);
        RefreshPlayButton();
    }

    private bool ApplyHandoff(RunningGame game, string? runningMessage)
    {
        var view = game.Handoff.Poll(DateTime.UtcNow);
        if (view.Stage > game.Shown)
        {
            game.Shown = view.Stage;
            if (view.Stage == HandoffStage.Opening && ReferenceEquals(_sceneGame, game))
                LoadingOverlay.ReportStage("Loading Minecraft");
            if (view.Stage is HandoffStage.Opening or HandoffStage.Loading && !view.GameReady)
                Note(game.Key, $"Loading {game.Version}", LaunchTone.Neutral);
        }
        if (view.ReleaseScene)
            ReleaseScene(game, gameLoading: view.Stage >= HandoffStage.Loading);
        if (view.GameReady) CrashRetries.Clear(game.Key, game.Version);
        if (!view.GameReady || runningMessage == null) return false;
        if (_launches.WindowShown(game.Key, game))
            Note(game.Key, runningMessage, LaunchTone.Success);
        return true;
    }

    // Lowers the screen if it shows this game's start; the game comes forward once if the launcher is still in front.
    private void ReleaseScene(RunningGame game, bool gameLoading)
    {
        if (!ReferenceEquals(_sceneGame, game)) return;
        _sceneGame = null;
        LoadingOverlay.Hide();
        if (gameLoading && TopLevel.GetTopLevel(this) is Window { IsActive: true })
            MacGameWindow.BringToFront(game.Process);
    }

    private static void StopStartingGame(RunningGame game)
    {
        game.StoppedByPlayer = true;
        try { game.Process.Kill(entireProcessTree: true); }
        catch { /* already gone: the boot watch sees the exit either way */ }
    }

    // ---- the friends drawer's view of "the game" (SocialHub.Game): follows the selected account's game ----
    private RunningGame? _mirrored;

    private void SyncSocialMirror()
    {
        var key = SelectedKey();
        var want = key != null && _launches.PhaseOf(key) is GamePhase.Starting or GamePhase.Running
            ? _launches.GameOf(key) as RunningGame
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

    private static readonly CrashRetryGuard CrashRetries = new();
    private readonly HashSet<string> _tryWithoutAddedModsOnce = new();

    // Post-mortem (Core/Launch/CrashEngine via CrashDiagnoser): a normal close opens nothing; a crash opens the
    // crash report window, whose fix relaunches exactly the game that stopped.
    private async Task ShowCrashReportAsync(RunningGame game, int exitCode, bool crashedWhileStarting)
    {
        try
        {
            var saved = SettingsStore.Load();
            var ramMb = saved.RamMb;
            var customJvm = game.LaunchedJvmArgs?.Arguments;
            var totalMb = SystemInfo.GetTotalPhysicalMemoryMb();
            var key = game.Key;
            var fixTried = CrashRetries.AnyTried(key, game.Version, DateTime.UtcNow);
            var findings = await Task.Run(() => CrashDiagnoser.Run(game.GameDir, game.LogPath, exitCode,
                game.StoppedByPlayer, crashedWhileStarting, ramMb, totalMb, fixTried, game.StartedUtc, customJvm));
            var verdict = findings.Verdict;
            var idle = _launches.PhaseOf(key) is not (GamePhase.Starting or GamePhase.Running);
            // Not a crash: back to "Ready"; a crash in game: "Minecraft crashed" (GameExitStatus, all three launchers).
            if (idle && GameExitStatus.AfterVerdict(verdict.IsCrash, crashedWhileStarting) is { } after)
                Note(key, after.Text, after.Tone);
            if (!verdict.IsCrash) return;
            if (crashedWhileStarting)
            {
                if (idle)
                    Note(key, game.LogPath != null
                        ? $"Minecraft crashed while starting (exit code {exitCode}) - log saved to {game.LogPath}"
                        : $"Minecraft crashed while starting (exit code {exitCode}).", LaunchTone.Error);
                InstallVerification.Invalidate(OriginPaths.VersionRoot(game.Version));
            }

            var launcherVersion = UpdateService.CurrentVersion.ToString();
            var userName = Environment.UserName;
            string BuildReport() => CrashReportBundle.Build(new CrashReportBundle.Input(
                launcherVersion, game.Version, ramMb, totalMb, exitCode, verdict,
                findings.LogText, findings.CrashReportText, [userName, game.AccountName]));

            var window = new Windows.CrashReportWindow(verdict, game.LogPath, $"{game.AccountName} - Minecraft {game.Version}", BuildReport);
            if (TopLevel.GetTopLevel(this) is Window owner) await window.ShowDialog(owner);
            else return;
            await ApplyCrashChoiceAsync(game, window.ChosenFix, window.ChoseTryWithoutAddedMods);
        }
        catch (Exception ex)
        {
            // The crash screen never adds a second failure on top of the game's.
            Debug.WriteLine($"[HomePage] Crash analysis failed: {ex}");
        }
    }

    private async Task ApplyCrashChoiceAsync(RunningGame game, CrashFix? fix, bool tryWithoutAddedMods)
    {
        if (fix == null && !tryWithoutAddedMods) return;
        if (_launches.PhaseOf(game.Key) is GamePhase.Starting or GamePhase.Running) return;
        if (!CrashRetries.TryBegin(game.Key, game.Version, fix?.Kind ?? CrashFixKind.None, DateTime.UtcNow)) return;

        if (fix == null)
        {
            _tryWithoutAddedModsOnce.Add(game.Key);
        }
        else
        {
            switch (fix.Kind)
            {
                case CrashFixKind.RaiseMemory when fix.NewRamMb is int ram:
                    // As Manual, like Windows (CrashMemory.ApplyRaise): Auto launches ignore RamMb, so writing only the
                    // number left the relaunch on the same heap that just ran out.
                    if (!SettingsStore.TryUpdate(s => CrashMemory.ApplyRaise(s, ram), quiet: true))
                    {
                        // A busy settings.json: relaunching on the same heap would only run out again.
                        Note(game.Key, SettingsStore.BusyLine, LaunchTone.Error);
                        return;
                    }
                    CrashMemory.ApplyRaise(_settings, ram);
                    break;
                case CrashFixKind.DisableMods:
                    var failed = new List<(string, CrashRepairOutcome)>();
                    foreach (var jar in fix.Jars)
                    {
                        var result = CrashRepair.DisableBlamed(game.Version,
                            _profileByKey.GetValueOrDefault(game.Key), jar, global::OriginLauncher.App.Core.Mods.ModManager.IsManagedFile);
                        if (result.Outcome != CrashRepairOutcome.Disabled) failed.Add((jar, result.Outcome));
                    }
                    if (failed.Count > 0)
                    {
                        Note(game.Key, CrashRepair.FailureLine(game.Version, failed), LaunchTone.Error);
                        return;
                    }
                    break;
                case CrashFixKind.ResetJavaArgs:
                    var reset = RepairExecutor.ApplySettings(
                        RepairPlanner.ResetJavaArgs(game.Version, game.LaunchedJvmArgs, "Java refused to start the game with them."),
                        SettingsStore.Update);
                    if (!reset.AllOk)
                    {
                        Note(game.Key, reset.Failed.First().Error ?? "Couldn't reset the Java arguments.", LaunchTone.Error);
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

    private static void WriteLaunchErrorLog(Exception ex)
    {
        try
        {
            OriginPaths.EnsureScaffold();
            File.WriteAllText(Path.Combine(OriginPaths.Logs, $"launcher_error_{DateTime.Now:yyyyMMdd_HHmmss}.log"), ex.ToString());
        }
        catch { /* best-effort only */ }
    }

    // An out-of-date launcher is told to update instead of shown an error it can't act on.
    private static async Task<string> LaunchFailureTextAsync(string upToDateMessage)
    {
        await UpdateService.CheckAsync();
        return UpdateService.UpdateRequired
            ? "Launch failed - please update the launcher (click the update dot in the top-right). This is very likely already fixed in the newest version."
            : upToDateMessage;
    }

    private static string DescribeError(Exception ex)
    {
        var inner = ex;
        while (inner.InnerException != null) inner = inner.InnerException;
        var code = ex is System.Net.Http.HttpRequestException http && http.HttpRequestError != System.Net.Http.HttpRequestError.Unknown
            ? http.HttpRequestError.ToString()
            : inner.GetType().Name;
        var detail = ReferenceEquals(inner, ex) || inner.Message == ex.Message ? ex.Message : $"{ex.Message} - {inner.Message}";
        return $"{detail} [{code}]";
    }

    // Starts one game: its output goes to a per-launch log and the handshake; its exit comes back to Home. Nothing the
    // game does (exiting, crashing, a late burst of output) may ever take the launcher down: every callback is guarded,
    // and the log writer is disposed once, after both streams ended.
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
                if (game.Handoff.Observe(data))
                    Dispatcher.UIThread.Post(() => OnHandoff(game));
                lock (gate) { logWriter?.WriteLine(data); }
            }
            catch { /* never surface onto this thread */ }
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
                try { exitCode = process.ExitCode; } catch { exitCode = -1; }
                Dispatcher.UIThread.Post(() => OnGameExited(game, exitCode));
                game.Configuration?.Dispose();
                SettingsSync.AfterExit(game.Key, version);
            }
            catch { /* the game's exit must never crash the launcher */ }
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
        var wasRunning = _launches.PhaseOf(game.Key) == GamePhase.Running && ReferenceEquals(_launches.GameOf(game.Key), game);
        if (_launches.Exited(game.Key, game) && wasRunning)
        {
            // Neutral whatever the exit code: only the crash verdict may turn it red (GameExitStatus).
            var line = GameExitStatus.OnExit();
            Note(game.Key, line.Text, line.Tone);
        }
        SyncSocialMirror();
        RefreshPlayButton();
        if (wasRunning && exitCode != 0 && !game.StoppedByPlayer)
            Dispatcher.UIThread.Post(async () => await ShowCrashReportAsync(game, exitCode, crashedWhileStarting: false),
                DispatcherPriority.Background);
    }

    private static string SafeFileName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var safe = new string(name.Select(c => invalid.Contains(c) ? '_' : c).ToArray());
        return safe.Length == 0 ? "player" : safe;
    }

    // ---- The status line ----
    private enum StatusTone { Neutral, Success, Warning, Error }

    private Thickness? _statusRowIdleMargin;

    // An error wraps to at most two rows (Size.StatusLinesMax) with the whole text in its tooltip; every other tone
    // stays one trimmed line. Ready is the family accent; the rest keep their status colours.
    private void SetStatus(string text, StatusTone tone)
    {
        StatusText.Text = text;
        var isError = tone == StatusTone.Error;
        var line = Find<object>("Size.StatusLine") is double l ? l : 18;
        var linesMax = Find<object>("Size.StatusLinesMax") is double m ? m : line * 2;
        _statusRowIdleMargin ??= StatusRow.Margin;
        StatusText.TextWrapping = isError ? TextWrapping.Wrap : TextWrapping.NoWrap;
        StatusText.MaxHeight = isError ? linesMax : double.PositiveInfinity;
        StatusText.MinHeight = isError ? linesMax : 0;
        ToolTip.SetTip(StatusText, isError ? text : null);
        var space2 = Find<object>("Space.2") is double s ? s : 8;
        StatusRow.Margin = isError
            ? new Thickness(_statusRowIdleMargin.Value.Left, space2, _statusRowIdleMargin.Value.Right, _statusRowIdleMargin.Value.Bottom)
            : _statusRowIdleMargin.Value;
        StatusText.VerticalAlignment = isError ? VerticalAlignment.Top : VerticalAlignment.Center;
        StatusDot.VerticalAlignment = isError ? VerticalAlignment.Top : VerticalAlignment.Center;
        StatusDot.Margin = new Thickness(0, isError ? (line - StatusDot.Height) / 2 : 0, 8, 0);
        var brushKey = tone switch
        {
            StatusTone.Success => "Brush.Accent",
            StatusTone.Warning => "Brush.Warning",
            StatusTone.Error => "Brush.Danger",
            _ => "Brush.TextDim",
        };
        StatusDot.Fill = Find<IBrush>(brushKey);
    }

    // ---- Render shots only (UI/Shots/AreaAShots.cs): the same states, no game, no settings touched ----
    internal void TourShowRunning(string playerName)
    {
        ApplyPlayButton(AccountLaunchBook.ButtonFor(GamePhase.Running, canPlay: true, hasVersion: true));
        SetStatus($"Running - {playerName}", StatusTone.Success);
    }

    internal void TourShowReady()
    {
        ApplyPlayButton(AccountLaunchBook.ButtonFor(GamePhase.Idle, canPlay: true, hasVersion: true));
        SetStatus("Ready to play", StatusTone.Success);
    }

    /// <summary>Render shots: the status line only; Play keeps its own state (the WPF CompatShots sets just the status).</summary>
    internal void TourShowError(string text) => SetStatus(text, StatusTone.Error);

    internal void TourShowLaunch(string version) => LoadingOverlay.Show(version, "OriginPlayer - Survival SMP");

    internal void TourSelectVersion(string? version)
    {
        _selectedVersion = version;
        ShowVersionOnCard(version);
    }

    internal void TourReportLaunch(string stage) => LoadingOverlay.ReportStage(stage);
    internal void TourHideLaunch() => LoadingOverlay.Hide();
    internal void TourCancelLaunch() => LoadingOverlay.PressCancel();
    internal void TourFailLaunch(Exception error, string version) => LoadingOverlay.ShowFailure(PlayFailure.From(error, version));
    internal void TourShowVersions(int detailIndex) => VersionOverlay.TourOpen(detailIndex, _selectedVersion);
    internal void TourHideVersions() => VersionOverlay.TourClose();
    internal VersionSelectOverlay Versions => VersionOverlay;
}
