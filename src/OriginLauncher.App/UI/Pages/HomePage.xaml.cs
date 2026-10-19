using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.App.UI.Pages;

public partial class HomePage : UserControl
{
    private readonly LauncherSettings _settings;
    private readonly VersionManager _versionManager = new();
    private bool _syncingToggles;
    private StoredAccount? _selectedAccount;

    // The version the player has chosen in the grid picker. Source of truth for
    // Home (the old Mojang-populated ComboBox is gone); persisted to settings on
    // change. Coerced to a version Origin actually ships if a stale one is read.
    private string? _selectedVersion;

    // Several games at once, one per account (Core/GameFolders/AccountLaunchBook):
    // every account's launch is preparing, starting, running or idle on its
    // own. The newest launch action wins PER ACCOUNT (CLAUDE.md hard
    // requirement, now per account): a new launch cancels only the same
    // account's preparation, never another account's preparation or game.
    private readonly AccountLaunchBook _launches = new();

    // Which mod profile (Core/Profiles) each account's newest launch used, so
    // the Profiles page can refuse to delete one while a game is using it.
    // Only meaningful while that account's phase isn't Idle (IsProfileInUse).
    private readonly Dictionary<string, string?> _profileByKey = new();

    // The selected profile when it applies to the selected version and has a
    // server: what the "Join ..." button under Play launches into.
    private ModProfile? _joinProfile;

    // The launch the loading scene is showing (the newest one started); its
    // Cancel aborts exactly that launch. Null while the scene is down.
    private LaunchTicket? _overlayTicket;

    // A launch for a given version, optionally straight into a server
    // (Friends → Join, party warp) and/or for a given account (the crash
    // window's retry relaunches the account that crashed, whoever is selected
    // now). Version may differ from the selected one; it is NOT saved as the pick.
    private sealed record LaunchTarget(string Version, string? Server = null, string? AccountKey = null);

    // One started game. Several can be alive at once — one per account.
    private sealed class RunningGame
    {
        public required string Key { get; init; }
        public required string Version { get; init; }
        public required string AccountName { get; init; }
        public required string GameDir { get; init; }
        public required System.Diagnostics.Process Process { get; init; }
        public string? LogPath { get; set; }
        // The last "joined a server / opened a world" line from its output, so
        // the friends drawer's party warp can follow whichever account is selected.
        public string? LastWhereLine { get; set; }
        // Its id in SocialHub.Game while it is the mirrored (selected account's) game.
        public int MirrorId { get; set; }
        // The startup handshake with this game (Core/Launch/GameHandoff).
        public required GameHandoff Handoff { get; init; }
        // The furthest handshake stage Home has already acted on.
        public HandoffStage Shown { get; set; }
        // The player stopped it from the progress screen: its exit is not a crash.
        public bool StoppedByPlayer { get; set; }
    }

    // The game whose start the progress screen is showing, once its process is
    // up (before that the screen belongs to _overlayTicket). Null when the
    // screen shows nothing of a game's.
    private RunningGame? _sceneGame;

    // The players-online pill: fetched once the first frame is up, then every 5 min.
    private readonly DispatcherTimer _playersTimer = new(DispatcherPriority.Background) { Interval = PlayersOnline.Refresh };
    private bool _playersStarted;

    private const double CompactBelowHeight = 600;
    private bool _compact;

    /// <summary>
    /// Regular spacing (16 px gaps, 24 px card padding, a 66 px version button) or compact (8 px gaps,
    /// 16 px padding, 56 px button) by how tall the page is. Resize-driven, no animation.
    /// </summary>
    private void ApplyDensity(double pageHeight)
    {
        var compact = pageHeight < CompactBelowHeight;
        if (compact == _compact) return;
        _compact = compact;

        var gap = (double)FindResource(compact ? "Space.2" : "Space.3");
        // The page's bottom margin gives 8 px back too: at 980x600 with a profile row and Join, the card and
        // banner came to 488 px in a 486 px slot and lost a pixel of their top and bottom edges.
        var bottom = compact ? 32 : 40;
        HomeRoot.Margin = new Thickness(32, 16, 32, bottom);
        // The version picker and the launch screen cancel the page margin to fill the whole page: they follow it.
        VersionOverlay.Margin = LoadingOverlay.Margin = new Thickness(-32, -16, -32, -bottom);
        HomeCardBorder.Padding = new Thickness((double)FindResource(compact ? "Space.3" : "Space.4"));
        VersionButton.Height = compact ? 56 : 66;
        VersionButton.Padding = compact ? new Thickness(16, 6, 16, 6) : new Thickness(16, 10, 16, 10);
        VersionButton.Margin = new Thickness(0, 0, 0, gap);
        ProfileRow.Margin = new Thickness(0, 0, 0, gap);
        ModsRowBorder.Margin = new Thickness(0, 0, 0, gap);
        // Play to Join stays 16 px at every size: the two buttons are a pair.
        JoinButton.Margin = new Thickness(0, (double)FindResource("Space.3"), 0, 0);
        StatusRow.Margin = new Thickness(0, gap, 0, 0);
        PromoBanner.Margin = new Thickness(0, gap, 0, 0);
    }

    public HomePage()
    {
        InitializeComponent();
        // The Play card (with a profile row and Join) is taller than the smallest window leaves room for:
        // below this page height every gap on it halves, so nothing touches the top bar or the bottom edge.
        SizeChanged += (_, e) => ApplyDensity(e.NewSize.Height);
        _settings = SettingsStore.Load();
        LoadingOverlay.CancelRequested += (_, _) =>
        {
            if (_overlayTicket != null) _overlayTicket.Source.Cancel();
            else if (_sceneGame != null) StopStartingGame(_sceneGame);
        };
        VersionOverlay.VersionSelected += OnVersionSelected;
        // While an import writes to the game folders, Play and Join stay off (Core/Import/Launcher/ImportRunState).
        ImportRunState.Changed += () => Dispatcher.BeginInvoke(() => RefreshPlayButton());
        VersionOverlay.LaunchRequested += OnLaunchRequested;
        // The home banner pauses under either scene (and, in ad mode, its
        // native WebView2 would otherwise draw on top of them).
        VersionOverlay.IsVisibleChanged += (_, _) => PromoBanner.SetHomeSceneOpen(HasOpenScene);
        LoadingOverlay.IsVisibleChanged += (_, _) => PromoBanner.SetHomeSceneOpen(HasOpenScene);
        InitVersionSelection();
        RefreshAccountState();
        Loaded += (_, _) => StartPlayersOnline();
        Loaded += (_, _) => PlayIntroOnce();
    }

    // Once per launcher run: Home is the first thing it shows.
    private static bool s_introPlayed;

    // The launcher opening (docs/MOTION.md "intro"): the big mark assembles,
    // then the wordmark and the Play card settle in under it. Home is fully
    // interactive on its first frame — this is opacity and transforms only,
    // nothing waits on it — and every animation ends on the resting value
    // (FillBehavior.Stop), so it can never leave Home half-shown. Reduced
    // motion (Windows' animation setting) zeroes Motion.Intro: no intro.
    private void PlayIntroOnce()
    {
        if (s_introPlayed) return;
        s_introPlayed = true;
        var intro = (Duration)FindResource("Motion.Intro");
        if (!SystemParameters.ClientAreaAnimation || !intro.HasTimeSpan || intro.TimeSpan <= TimeSpan.Zero) return;
        var settle = ((Duration)FindResource("Motion.Settle")).TimeSpan;
        HomeMark.Align(intro);
        Settle(HomeLockupText, rise: 6, delay: TimeSpan.FromMilliseconds(160), settle);
        Settle(HomeCard, rise: 10, delay: TimeSpan.FromMilliseconds(220), settle);
    }

    private static void Settle(UIElement element, double rise, TimeSpan delay, TimeSpan length)
    {
        var shift = new TranslateTransform();
        element.RenderTransform = shift;
        element.BeginAnimation(OpacityProperty, OriginMark.Hold(0, 1, delay, length));
        shift.BeginAnimation(TranslateTransform.YProperty, OriginMark.Hold(rise, 0, delay, length));
    }

    private void StartPlayersOnline()
    {
        if (_playersStarted) return;
        _playersStarted = true;
        _playersTimer.Tick += async (_, _) => await RefreshPlayersOnlineAsync();
        _playersTimer.Start();
        Dispatcher.InvokeAsync(() => _ = RefreshPlayersOnlineAsync(), DispatcherPriority.ApplicationIdle);
    }

    // Network off the UI thread; any failure just hides the pill until the next try.
    private async Task RefreshPlayersOnlineAsync()
    {
        int? count = SocialHub.OnlineCountOverride;
        if (count == null)
        {
            try
            {
                count = await Task.Run(() =>
                    PlayersOnline.FetchAsync(SocialHttp.SendAsync, SocialEndpoints.BaseUrl, CancellationToken.None));
            }
            catch
            {
                count = null;
            }
        }

        if (count is { } n)
        {
            PlayersText.Text = SocialText.PlayersOnline(n);
            PlayersPill.Visibility = Visibility.Visible;
        }
        else
        {
            PlayersPill.Visibility = Visibility.Collapsed;
        }
    }

    /// <summary>
    /// Friends → Join / party warp: launch <paramref name="version"/> straight
    /// into <paramref name="server"/> on the selected account. The newest
    /// launch action wins, so that account's launch still being prepared is
    /// replaced; its game that is already open can't be (one game per
    /// account), so that one is brought forward and explained instead.
    /// </summary>
    public async Task JoinServerAsync(string version, string server)
    {
        if (SelectedKey() is { } key && _launches.PhaseOf(key) is GamePhase.Starting or GamePhase.Running)
        {
            Note(key, _launches.PhaseOf(key) == GamePhase.Starting
                ? "Minecraft is still starting on this account — try Join again in a moment"
                : "Minecraft is already open on this account — close it to join, or switch accounts", LaunchTone.Warning);
            FocusGame(key);
            return;
        }
        await LaunchAsync(new LaunchTarget(version, server));
    }

    // The version the player currently has selected. Held in memory (and mirrored
    // to settings on an explicit pick); the newest-supported default is shown but
    // not persisted until chosen, matching the previous behavior.
    public string? CurrentVersion => _selectedVersion;

    /// <summary>
    /// The Profiles page: true while a game this launcher started (or is still
    /// preparing) uses mod profile <paramref name="profileId"/>. Such a profile
    /// can't be deleted — its game is running.
    /// </summary>
    public bool IsProfileInUse(string profileId) =>
        _profileByKey.Any(entry => entry.Value == profileId && _launches.PhaseOf(entry.Key) != GamePhase.Idle);

    /// <summary>
    /// MainWindow, when Home comes back into view: pick up what the Profiles
    /// page changed — a newly selected profile also switched the version in
    /// settings, and the profile row and its join button follow the selection.
    /// </summary>
    public void RefreshProfile()
    {
        var settings = SettingsStore.Load();
        // Home is the only other writer of SelectedVersion and keeps its memory
        // in step, so a difference here came from selecting a profile.
        if (settings.SelectedVersion is { } picked && picked != _selectedVersion && VersionCatalog.IsSupported(picked))
        {
            _selectedVersion = picked;
            _settings.SelectedVersion = picked;
            SetVersionButtonText(picked);
            SocialHub.PresenceVersion = picked;
        }
        ApplyProfile(settings);
        UpdatePlayState();
    }

    // Shows the selected profile on Home. It only APPLIES to its own version
    // (ProfileRules.Active): on any other the row says so and Play launches
    // with the version's normal mods.
    private void ApplyProfile(LauncherSettings settings)
    {
        var selected = settings.ModProfiles.FirstOrDefault(p => p.Id == settings.SelectedProfileId && ProfileRules.IsUsable(p));
        var active = ProfileRules.Active(settings.ModProfiles, settings.SelectedProfileId, _selectedVersion);

        ProfileRow.Visibility = selected != null ? Visibility.Visible : Visibility.Collapsed;
        ProfileNameText.Text = selected == null ? "" : active != null ? selected.Name : $"{selected.Name} · {selected.Version} only";
        ProfileNameText.ToolTip = selected == null ? null
            : active != null ? "Play uses this profile's mods (change it on the Profiles page)"
            : $"This profile is for {selected.Version}, so {_selectedVersion} uses your normal mods";

        _joinProfile = active is { HasServer: true } ? active : null;
        JoinButton.Visibility = _joinProfile != null ? Visibility.Visible : Visibility.Collapsed;
        if (_joinProfile != null)
        {
            JoinButton.Content = ProfileRules.JoinLabel(_joinProfile);
            System.Windows.Automation.AutomationProperties.SetName(JoinButton, JoinButton.Content.ToString());
        }
    }

    private async void JoinButton_Click(object sender, RoutedEventArgs e)
    {
        if (_joinProfile is { HasServer: true } profile)
            await JoinServerAsync(profile.Version, profile.ServerAddress!);
    }

    public bool HasOpenScene => VersionOverlay.IsOpen
        || LoadingOverlay.Visibility == Visibility.Visible;

    /// <summary>MainWindow: the account panel / sign-in card covers Home — the banner pauses.</summary>
    public void SetChromeOverlayOpen(bool open) => PromoBanner.SetChromeOverlayOpen(open);

    /// <summary>
    /// Covers Home with the loading scene in self-update mode (MainWindow's
    /// update button). Returns false — and shows nothing — while a launch is
    /// using the scene; the caller then reports progress on the button alone.
    /// No end call: success shuts the app down, failure calls EndUpdateScene.
    /// </summary>
    public bool BeginUpdateScene(string newVersion)
    {
        if (_overlayTicket != null || LoadingOverlay.Visibility == Visibility.Visible)
            return false;
        LoadingOverlay.ShowUpdate(newVersion);
        return true;
    }

    public void ReportUpdate(UpdateService.UpdateProgress progress) =>
        LoadingOverlay.ReportUpdate(progress);

    public void EndUpdateScene() => LoadingOverlay.Hide();

    // Keyboard/default-action hook owned by MainWindow. It refuses while a
    // picker or launch cover is open, so Enter can never click through a scene.
    public bool TryLaunchFromKeyboard()
    {
        if (!PlayButton.IsEnabled || VersionOverlay.IsOpen
            || LoadingOverlay.Visibility == Visibility.Visible)
            return false;

        _ = LaunchAsync();
        return true;
    }

    // Called by MainWindow whenever the account switcher panel adds or
    // selects an account, so Home reflects it without needing to navigate away and back.
    public void RefreshAccountState()
    {
        _selectedAccount = AccountStore.GetSelected(AccountStore.Load());
        // The first account ever selected inherits the old one-folder-per-
        // version game data (a no-op once that is done — see AccountFolders).
        AccountFolders.EnsureMigrated(SelectedKey());
        // Switched to another account while this one's game is still starting:
        // the scene is that game's, not this account's Home — lower it (the
        // game carries on; the boot watch still follows it).
        if (_sceneGame != null && _sceneGame.Key != SelectedKey())
            ReleaseScene(_sceneGame, gameLoading: false);
        UpdatePlayState();
        SyncSocialMirror();
        // Origin+ members never see the banner's ad network: re-check for this account.
        PromoBanner.NotifyAccountChanged();
    }

    /// <summary>Called by MainWindow when the update badge lights up, so the
    /// status line explains why Play is about to refuse.</summary>
    public void RefreshUpdateGate()
    {
        if (UpdateService.UpdateRequired && SelectedPhase() == GamePhase.Idle)
        {
            SetStatus("Update required", StatusTone.Warning);
        }
    }

    // The selected account's folder key (offline test mode's when nobody is
    // signed in and it is on), or null when nobody can play. Read fresh (not
    // the cached _settings): the offline toggle lives on the Settings page,
    // which persists to disk; a page switch must pick the change up.
    private string? SelectedKey() =>
        _selectedAccount != null
            ? InstanceLayout.KeyFor(_selectedAccount.Id)
            : SettingsStore.Load().OfflineTestMode ? InstanceLayout.OfflineKey : null;

    private GamePhase SelectedPhase() =>
        SelectedKey() is { } key ? _launches.PhaseOf(key) : GamePhase.Idle;

    // The Play button AND the status pill for the selected account: its own
    // game state ("Running · Steve" while its game is open), or the plain
    // signed-in line when it has none.
    private void UpdatePlayState()
    {
        RefreshPlayButton();
        var key = SelectedKey();
        if (key != null && _launches.PhaseOf(key) != GamePhase.Idle && _launches.NoteOf(key) is { } note)
            SetStatus(note.Text, ToneOf(note.Tone));
        else if (_selectedAccount != null)
            SetStatus(_selectedAccount.Gamertag, StatusTone.Success);
        else if (key != null)
            SetStatus("Offline", StatusTone.Warning);
        else
            SetStatus("Sign in to play", StatusTone.Neutral);
    }

    // Only the button: Play / spinner / Running for the selected account's
    // phase (AccountLaunchBook.ButtonFor). Leaves the status line alone, so a
    // specific message a launch just set isn't clobbered.
    private void RefreshPlayButton()
    {
        var key = SelectedKey();
        var phase = key != null ? _launches.PhaseOf(key) : GamePhase.Idle;
        ApplyPlayButton(AccountLaunchBook.ButtonFor(phase, canPlay: key != null, hasVersion: _selectedVersion != null));
        // Join follows Play: usable only when Play itself could start a launch.
        JoinButton.IsEnabled = key != null && phase == GamePhase.Idle && _selectedVersion != null;
        if (ImportRunState.IsApplying)
        {
            PlayButton.IsEnabled = false;
            JoinButton.IsEnabled = false;
            PlayButton.ToolTip = "An import is running. Play comes back when it finishes.";
        }
    }

    private void ApplyPlayButton(PlayButtonView view)
    {
        PlayButton.IsEnabled = view.Enabled;
        PlayButton.ToolTip = view.ToolTip;
        System.Windows.Automation.AutomationProperties.SetName(PlayButton,
            view.ShowsRunning ? "Switch to Minecraft" : "Play Minecraft");
        SetPlayLaunching(view.Spinner);
        PlayLabel.Visibility = !view.Spinner && !view.ShowsRunning ? Visibility.Visible : Visibility.Collapsed;
        RunningLabel.Visibility = !view.Spinner && view.ShowsRunning ? Visibility.Visible : Visibility.Collapsed;
    }

    // A status line for one account: remembered for it (switching back shows
    // it again) and shown now when that account is the selected one.
    private void Note(string key, string text, LaunchTone tone)
    {
        _launches.SetNote(key, new LaunchNote(text, tone));
        if (SelectedKey() == key)
            SetStatus(text, ToneOf(tone));
    }

    private static StatusTone ToneOf(LaunchTone tone) => tone switch
    {
        LaunchTone.Success => StatusTone.Success,
        LaunchTone.Warning => StatusTone.Warning,
        LaunchTone.Error => StatusTone.Error,
        _ => StatusTone.Neutral,
    };

    // The Play button's "Running" click: that account's game window comes to
    // the front (restored if minimised).
    private void FocusGame(string key)
    {
        if (_launches.GameOf(key) is RunningGame game)
            UI.GameWindow.BringToFront(game.Process);
    }

    // No network round-trip anymore: the grid is a fixed catalog of the versions
    // Origin ships, so selection is instant (better cold-start too). Coerce a
    // stale/unsupported persisted value to the newest supported version.
    private void InitVersionSelection()
    {
        _selectedVersion = VersionCatalog.IsSupported(_settings.SelectedVersion)
            ? _settings.SelectedVersion
            : VersionCatalog.DefaultVersion;
        SetVersionButtonText(_selectedVersion);
        SocialHub.PresenceVersion = _selectedVersion;
        SyncExternalModsToggle();
        ApplyProfile(_settings);
        UpdatePlayState();
    }

    // Raised by the grid picker when the player confirms a specific version.
    // Does exactly one thing: records the new version. No other setting moves.
    private void OnVersionSelected(string version)
    {
        _selectedVersion = version;
        _settings.SelectedVersion = version;
        SettingsStore.Update(s => s.SelectedVersion = version);
        SetVersionButtonText(version);
        SocialHub.PresenceVersion = version;
        UpdatePlayState();
    }

    private void SetVersionButtonText(string? version) =>
        VersionButtonText.Text = version ?? "Select a version";

    private void VersionButton_Click(object sender, RoutedEventArgs e) =>
        VersionOverlay.Show(_selectedVersion);

    // Reflect the persisted value into the switch without re-triggering the
    // Checked/Unchecked save handlers (the _syncingToggles guard).
    private void SyncExternalModsToggle()
    {
        _syncingToggles = true;
        ExternalModsToggle.IsChecked = _settings.PlayWithExternalMods;
        _syncingToggles = false;
    }

    private void ExternalModsToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_syncingToggles) return;
        SaveExternalMods(true);
    }

    private void ExternalModsToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_syncingToggles) return;
        SaveExternalMods(false);
    }

    private void ExternalModsLabel_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        ExternalModsToggle.IsChecked = ExternalModsToggle.IsChecked != true;
        e.Handled = true;
    }

    private void SaveExternalMods(bool value)
    {
        _settings.PlayWithExternalMods = value;
        SettingsStore.Update(s => s.PlayWithExternalMods = value);
    }

    private async void PlayButton_Click(object sender, RoutedEventArgs e) => await LaunchAsync();

    // The version picker's Launch button: adopt the chosen version, then launch
    // through the exact same path as Play.
    private async void OnLaunchRequested(string version)
    {
        OnVersionSelected(version);
        await LaunchAsync();
    }

    private async Task LaunchAsync(LaunchTarget? target = null)
    {
        // Enter, a friend's Join and the version picker all land here: none of them may start a game mid-import.
        if (ImportRunState.IsApplying)
        {
            SetStatus("An import is running. Play comes back when it finishes.", StatusTone.Warning);
            return;
        }
        // One fresh read for every launch-gating setting: offline test mode
        // and the external-mods switch can both change on other pages between
        // clicks, and this launch must honour what's on disk NOW.
        var freshSettings = SettingsStore.Load();
        var offlineTest = freshSettings.OfflineTestMode;
        var externalMods = freshSettings.PlayWithExternalMods;

        // Whose game: the selected account, or the one the target names (the
        // crash window's retry relaunches the account that crashed).
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

        // One game per account: an open game is brought forward instead of a
        // second copy (the same Microsoft session can't play twice anyway).
        switch (AccountLaunchBook.ActionFor(_launches.PhaseOf(key)))
        {
            case PlayAction.FocusGame:
                if (_launches.VersionOf(key) is { } playing && playing != version)
                    Note(key, $"{accountName} is already playing {playing} — close it first to play {version}", LaunchTone.Warning);
                FocusGame(key);
                return;
            case PlayAction.None when _launches.PhaseOf(key) == GamePhase.Starting:
                return; // its window is about to appear
        }
        // Still one game per account when this launcher didn't start it (it
        // was restarted while the game kept running): a second copy would
        // share that game's folder.
        if (_launches.PhaseOf(key) == GamePhase.Idle
            && AccountFolders.VersionPlayingOutsideLauncher(key) is { } openVersion)
        {
            Note(key, $"Minecraft {openVersion} is still open on {accountName} — close it first", LaunchTone.Warning);
            return;
        }

        // Mandatory updates (Will's rule): a launcher older than the latest
        // published release must update before it can launch the game.
        // Use the result the background poller already has rather than blocking
        // the click on a fresh GitHub round-trip. MainWindow re-checks the feed
        // every 2 minutes and on window focus, so this is at most ~2 minutes
        // stale — and a release published inside that window still blocks the
        // NEXT launch. Trading that for an immediately-responsive Play button is
        // the right side of the deal; the mandatory-update rule is still
        // enforced, just not with a network wait in front of it.
        if (UpdateService.UpdateRequired)
        {
            Note(key, "Update required", LaunchTone.Warning);
            return;
        }

        // Newest launch action wins, per account: this cancels only this
        // account's earlier preparation (if any). The loading scene's Cancel
        // aborts this one.
        // The mod profile this launch uses (null = plain launch): the selected
        // profile, only when it belongs to this version and personal mods are on.
        var profile = ProfileRules.ForLaunch(freshSettings.ModProfiles, freshSettings.SelectedProfileId, version, externalMods);
        if (_launches.BeginPrepare(key, version) is not { } ticket) return;
        // An import must not start while this launch is still being prepared (the game process does not exist
        // yet, so the "is a game running" probe cannot see it). Held until this method ends - a `using`, so every
        // exit (started, cancelled, failed, any exception) releases it; from the process start on, the running
        // game is what blocks an import (Core/Import/Launcher/LaunchRunState).
        using var launchRun = LaunchRunState.Enter();
        _profileByKey[key] = profile?.Id;
        _overlayTicket = ticket;
        _sceneGame = null; // the scene is this launch's now, whatever it showed before
        RefreshPlayButton();
        LoadingOverlay.Show(version);

        // Only the launch the scene is showing reports into it — an older
        // launch still finishing for another account stays quiet.
        void Stage(string text)
        {
            if (ReferenceEquals(_overlayTicket, ticket)) LoadingOverlay.ReportStage(text);
        }

        // Set once the game process has actually started: from then on the
        // account's phase belongs to the game (WatchBootAsync, the Exited
        // handler) and the finally below must not reset it.
        var gameStarted = false;

        try
        {
            MSession session;
            if (account != null)
            {
                Stage("Signing in");
                // Through the broker: it reuses this account's saved Minecraft
                // session until shortly before it expires (Mojang rate-limits
                // sign-ins), signs in only when there is none or Mojang refuses
                // it, serialises sign-ins with Origin social's and bumps last-used.
                var signedIn = await MinecraftSessionBroker.ForPlayAsync(account.Id, ticket.Token);
                if (signedIn == null)
                {
                    Note(key, "Your sign-in is no longer valid. Add this account again in Accounts.", LaunchTone.Error);
                    return;
                }
                session = signedIn;
            }
            else
            {
                // Offline test mode (Settings -> Developer): Microsoft's
                // app-registration approval is pending, so real sign-in is
                // unavailable — a local session lets every other part of the
                // pipeline (provisioning, loaders, the in-game UI) be tested.
                Stage("Offline session");
                session = MSession.CreateOfflineSession("OriginTester");
            }

            ticket.Token.ThrowIfCancellationRequested();

            // freshSettings, NOT the cached _settings: _settings is loaded once when
            // this page is constructed (launcher start) and is only good for initial
            // UI state. Building the launch from it meant JVM arguments, RAM and
            // resolution edited in Settings did nothing until the launcher was
            // restarted -- and a stale conflicting entry (e.g. ZGC left over from an
            // earlier edit, on top of the G1 preset) kept aborting the JVM with
            // "Multiple garbage collectors selected" no matter what the player
            // changed. Same read the external-mods/offline switches already use.
            var launchOption = LaunchProfileBuilder.Build(freshSettings, session, version);
            // The startup handshake's token, last so nothing overrides it.
            var handoff = new GameHandoff();
            launchOption.ExtraJvmArguments = (launchOption.ExtraJvmArguments ?? Enumerable.Empty<MArgument>())
                .Append(new MArgument(handoff.JvmArgument)).ToList();
            if (target?.Server is { } server)
            {
                // Straight into the friend's server: Quick Play on 1.20+, the
                // old --server/--port pair before it (JoinArguments).
                var join = JoinArguments.Build(version, server);
                if (join.Count > 0)
                    launchOption.ExtraGameArguments = launchOption.ExtraGameArguments
                        .Append(new MArgument(join.ToArray())).ToList();
            }

            // This account's own game folder for the version: migrated from the
            // old layout, or — its first time on this version — created with
            // the settings of the account that played it last (AccountFolders).
            var gameDir = await Task.Run(() => AccountFolders.PrepareGameDir(version, key), ticket.Token);

            var installProgress = new Progress<string>(Stage);
            // Run provisioning on a worker thread. Everything inside it that is
            // synchronous — the mods-folder sweeps, the corrupt-config scan, the
            // jar copies, CmlLib's file-queueing loop and its natives unzip —
            // used to execute on the UI thread, because none of the awaits in
            // that path leave the WPF dispatcher. That is what made the loading
            // overlay stutter and the window look hung while it worked.
            // installProgress was constructed on the UI thread, so Progress<T>
            // still marshals every stage update back correctly.
            var process = await Task.Run(
                () => _versionManager.InstallAndBuildProcessAsync(
                    version, key, launchOption, externalMods, profile?.Id, installProgress, ticket.Token),
                ticket.Token);

            // Hint hybrid-GPU laptops onto the discrete GPU. Always applied now
            // that the Graphics/Performance launch-mode toggle is gone — the
            // high-performance GPU is simply the right default for a game.
            GpuPreference.PreferHighPerformanceGpu(process.StartInfo.FileName);

            // Quality-neutral shader-stutter reducer: keep the driver's compiled
            // shader cache so Iris packs don't recompile (and hitch) each launch.
            ShaderCache.Apply(process.StartInfo, freshSettings.ShaderCacheNvidia, freshSettings.ShaderCacheAmd);

            // Settings Sync: bring in what the player changed in this account's
            // other versions, then write it into this one. After provisioning
            // (which seeds/sanitizes config files) and before the game reads
            // them. Never throws — a sync problem just means an unsynced launch.
            if (freshSettings.SyncSettingsAcrossVersions)
            {
                Stage("Syncing settings");
                await Task.Run(() => SettingsSync.BeforeLaunch(key, version));
            }

            // Last gate before the game actually starts — a cancel that
            // landed after provisioning finished must not still launch.
            ticket.Token.ThrowIfCancellationRequested();

            Stage("Starting Minecraft");
            var game = new RunningGame
            {
                Key = key,
                Version = version,
                AccountName = accountName,
                GameDir = gameDir,
                Process = process,
                Handoff = handoff,
            };
            StartWithLifecycleCapture(game);
            handoff.ProcessStarted(DateTime.UtcNow);
            // The launcher is the foreground app right now; let the game take
            // the focus when its window appears over the progress screen.
            UI.GameWindow.AllowForeground(process);
            // Synchronous since the cancellation check above, so the ticket is
            // still this account's newest action.
            gameStarted = _launches.GameStarted(ticket, game);
            SyncSocialMirror();

            // The process is up but the game window isn't — the Play button
            // keeps spinning (via WatchBootAsync) until Minecraft's window
            // actually appears, or the boot dies early, which restores the
            // button and surfaces the crash.
            Note(key, $"Starting {version}", LaunchTone.Neutral);
            var runningMessage = target?.Server is { } joined
                ? $"Playing on {SocialText.DisplayServer(joined)} · {version}"
                : $"Running · {session.Username}";
            _ = WatchBootAsync(game, runningMessage);
        }
        catch (OperationCanceledException)
        {
            // Player hit Cancel on the scene (or a newer launch for this
            // account superseded this one) — not an error, just back to Home.
            Note(key, "Cancelled", LaunchTone.Neutral);
        }
        catch (MicrosoftAuthException ex) when (ex.RateLimited)
        {
            // Microsoft said "slow down" (429): the broker already holds this
            // account's sign-ins off; no update check, no raw HTTP text.
            Note(key, MicrosoftAuthException.RateLimitedMessage, LaunchTone.Warning);
            WriteLaunchErrorLog(ex);
        }
        catch (MicrosoftAuthException ex)
        {
            // ex.Message is always a plain sentence (raw HTTP detail only goes to the log).
            var upToDateMessage = ex.Stage == "token_refresh"
                ? "Your session expired — remove and re-add this account in the account switcher."
                : ex.Message;
            Note(key, await LaunchFailureTextAsync(ex, upToDateMessage), LaunchTone.Error);
            System.Diagnostics.Debug.WriteLine($"[HomePage] Launch failed (auth): {ex}");
            WriteLaunchErrorLog(ex);
        }
        catch (Exception ex)
        {
            Note(key, await LaunchFailureTextAsync(ex, $"Launch failed: {DescribeError(ex)}"), LaunchTone.Error);
            System.Diagnostics.Debug.WriteLine($"[HomePage] Launch failed: {ex}");
            WriteLaunchErrorLog(ex);
        }
        finally
        {
            // A preparation that never started a game hands the account back
            // to idle — unless a newer launch for it already took over.
            if (!gameStarted) _launches.EndPrepare(ticket);
            // The scene belongs to the newest launch; only that one lowers it.
            // A launch that started its game hands the scene to that game: it
            // stays up through Minecraft's own start until the game takes over
            // (WatchBootAsync / OnHandoff lower it).
            if (ReferenceEquals(_overlayTicket, ticket))
            {
                _overlayTicket = null;
                if (gameStarted && _launches.GameOf(key) is RunningGame started)
                {
                    _sceneGame = started;
                    LoadingOverlay.EnterGameStart();
                    // The player switched account while it was preparing: that
                    // switch ran before the scene had a game, so lower it now.
                    if (started.Key != SelectedKey()) ReleaseScene(started, gameLoading: false);
                }
                else
                {
                    LoadingOverlay.Hide();
                }
            }
            // The book no longer holds this ticket (EndPrepare/GameStarted or a
            // newer BeginPrepare released it), and the scene's Cancel can't
            // reach it any more — nothing can call Cancel on a disposed source.
            // (That was the old "Play does nothing after the first launch" bug,
            // crash_20260712_113213.log.)
            ticket.Source.Dispose();
            // Not the full UpdatePlayState() — that would overwrite the status
            // line the try/catch above just set. Only re-sync the button.
            RefreshPlayButton();
        }
    }

    // Owns the tail of one game's launch: keeps the progress screen up and the
    // Play button spinning after the game process starts, until the game's
    // window is up showing its own loading screen (Core/Launch/GameHandoff:
    // that lowers the screen; its title screen makes the account Running) or the process
    // dies during boot (crash — surface it). The game's markers act the
    // moment they arrive (OnHandoff); this poll only watches for the window,
    // the exit and the handshake's fallback ceilings, so the scene and the
    // button can never be left waiting forever. Runs on the UI context; the
    // polls are cheap and non-blocking.
    private async Task WatchBootAsync(RunningGame game, string runningMessage)
    {
        var process = game.Process;
        var windowSeen = false;
        try
        {
            while (true)
            {
                bool exited;
                try { exited = process.HasExited; }
                catch { break; } // process handle gone — nothing more to learn

                if (exited)
                {
                    int exitCode;
                    try { exitCode = process.ExitCode; } catch { exitCode = -1; }
                    // Back to Play right away (the Exited handler, arriving
                    // later, then finds nothing left to do).
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
                    Note(game.Key, game.LogPath != null
                        ? $"Minecraft crashed while starting (exit code {exitCode}) — log saved to {game.LogPath}"
                        : $"Minecraft crashed while starting (exit code {exitCode}).", LaunchTone.Error);
                    // A boot crash is the one case where a bad file on disk is a
                    // live suspect, so drop the "already verified" stamp and make
                    // the next launch re-hash the whole install. This is the
                    // safety net that lets normal launches skip verification.
                    Core.Versions.InstallVerification.Invalidate(OriginPaths.VersionRoot(game.Version));
                    // Queued (not awaited) so this watch finishes (button
                    // restored) first — the crash window's "retry" path
                    // launches the account again.
                    _ = Dispatcher.BeginInvoke(async () => await ShowCrashReportAsync(game), DispatcherPriority.Background);
                    return;
                }

                if (!windowSeen)
                {
                    try
                    {
                        process.Refresh();
                        windowSeen = process.MainWindowHandle != IntPtr.Zero;
                    }
                    catch { break; }
                    if (windowSeen) game.Handoff.WindowSeen(DateTime.UtcNow);
                }

                if (ApplyHandoff(game, runningMessage)) return;
                await Task.Delay(500);
            }
            // The process handle stopped answering: assume the game is running
            // rather than reporting a failure that didn't happen.
            ReleaseScene(game, gameLoading: false);
            if (_launches.WindowShown(game.Key, game))
                Note(game.Key, runningMessage, LaunchTone.Success);
        }
        finally
        {
            RefreshPlayButton();
        }
    }

    // A handshake marker arrived on the game's output (UI thread): act now,
    // not at the next poll.
    private void OnHandoff(RunningGame game)
    {
        if (!ReferenceEquals(_launches.GameOf(game.Key), game)) return;
        ApplyHandoff(game, runningMessage: null);
        RefreshPlayButton();
    }

    // Moves Home along with the game's start: the screen's line, its exit, and
    // the account going Running. True once the game counts as ready (the boot
    // watch is then done). runningMessage null: leave the Running line to the
    // boot watch, which knows it (it re-polls within half a second).
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
        if (!view.GameReady || runningMessage == null) return false;
        if (_launches.WindowShown(game.Key, game))
            Note(game.Key, runningMessage, LaunchTone.Success);
        return true;
    }

    // Lowers the progress screen if it is showing this game's start.
    // gameLoading: the game's window is up drawing its own Origin loading
    // screen, so if the player is still looking at the launcher (it is the
    // active window), the game's window is brought to the front once. Once: no
    // loop, and never when the player has moved on to something else (Windows
    // would refuse it then anyway).
    private void ReleaseScene(RunningGame game, bool gameLoading)
    {
        if (!ReferenceEquals(_sceneGame, game)) return;
        _sceneGame = null;
        LoadingOverlay.Hide();
        if (gameLoading && Window.GetWindow(this) is { IsActive: true })
            UI.GameWindow.BringToFront(game.Process);
    }

    // The loading scene's Cancel after the game process started: stop that
    // game. Its exit then reads "Cancelled" (not a crash) in the boot watch,
    // which also lowers the scene.
    private static void StopStartingGame(RunningGame game)
    {
        game.StoppedByPlayer = true;
        try { game.Process.Kill(entireProcessTree: true); }
        catch { /* already gone: the boot watch sees the exit either way */ }
    }

    // ---- the friends drawer's view of "the game" (SocialHub.Game) -------------
    //
    // Core/Social's RunningGameTracker follows ONE game: the party leader's warp
    // needs the server that game is on. With several games open it follows the
    // SELECTED account's game — the account the friends drawer is signed in as.
    // Switching accounts re-points it (and replays where that game is), so the
    // tracker itself never needs to know about accounts.
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
            if (want.LastWhereLine is { } line)
                SocialHub.Game.Observe(want.MirrorId, line);
        }
    }

    // A game joined a server or opened a world (UI thread).
    private void OnGameWhere(RunningGame game, string line)
    {
        game.LastWhereLine = line;
        if (ReferenceEquals(_mirrored, game) && game.MirrorId > 0)
            SocialHub.Game.Observe(game.MirrorId, line);
    }

    // Post-mortem for a boot-time crash: name the culprit mod(s) when the
    // evidence supports it, offer "retry with only Origin's mods". Analysis
    // reads files + jars, so it runs off the UI thread. Several games can be
    // open, so the window says whose game (account + version) crashed, and the
    // retry relaunches exactly that one.
    private async Task ShowCrashReportAsync(RunningGame game)
    {
        try
        {
            var analysis = await Task.Run(() => CrashAnalyzer.Analyze(game.GameDir, game.LogPath));
            var window = new UI.Windows.CrashReportWindow(analysis, game.LogPath,
                $"{game.AccountName} · Minecraft {game.Version}")
            {
                Owner = Window.GetWindow(this)
            };
            window.ShowDialog();
            if (window.RetryWithoutExternalMods)
            {
                SaveExternalMods(false);
                SyncExternalModsToggle();
                await LaunchAsync(new LaunchTarget(game.Version, AccountKey: game.Key));
            }
        }
        catch (Exception ex)
        {
            // The crash screen must never add a second failure on top of the
            // game's — fall back to the status line that's already set.
            System.Diagnostics.Debug.WriteLine($"[HomePage] Crash analysis failed: {ex}");
        }
    }

    // Persists the full exception under /logs/ for diagnostics. Must never
    // throw itself — an error writer that can crash the catch block would
    // turn a failed launch into a crashed launcher (the previous hardcoded
    // debug path did exactly that on any machine but the dev box).
    private static void WriteLaunchErrorLog(Exception ex)
    {
        try
        {
            OriginPaths.EnsureScaffold();
            File.WriteAllText(
                Path.Combine(OriginPaths.Logs, $"launcher_error_{DateTime.Now:yyyyMMdd_HHmmss}.log"),
                ex.ToString());
        }
        catch
        {
            // Best-effort only.
        }
    }

    // Update-aware failure text. A launcher older than the latest release is
    // almost always failing on a bug that's already fixed upstream — that's
    // exactly how the login_with_xbox / SSL auth failure got resolved: by
    // updating. So on any launch failure, re-check the feed (the pre-launch
    // gate fails open on a transient, so an out-of-date build can slip through
    // to here) and, when this build is genuinely out of date, tell the user to
    // update instead of showing a raw error they can't act on. Dev builds and
    // up-to-date builds fall through to the real message + error code so it can
    // actually be reported. CheckAsync never throws (it fails open internally).
    private static async Task<string> LaunchFailureTextAsync(Exception ex, string upToDateMessage)
    {
        await UpdateService.CheckAsync();
        return UpdateService.UpdateRequired
            ? "Launch failed — please update the launcher (click the update dot in the top-right). This is very likely already fixed in the newest version."
            : upToDateMessage;
    }

    // A concise, reportable error string for an up-to-date build: the top-level
    // message plus the innermost cause (for wrapped failures like a TLS reset,
    // that inner message is the part that matters), tagged with a short code the
    // user can quote. Full detail still goes to the launcher_error log.
    private static string DescribeError(Exception ex)
    {
        var inner = ex;
        while (inner.InnerException != null) inner = inner.InnerException;

        var code = ex is System.Net.Http.HttpRequestException http
                   && http.HttpRequestError != System.Net.Http.HttpRequestError.Unknown
            ? http.HttpRequestError.ToString()
            : inner.GetType().Name;

        var detail = ReferenceEquals(inner, ex) || inner.Message == ex.Message
            ? ex.Message
            : $"{ex.Message} — {inner.Message}";

        return $"{detail} [{code}]";
    }

    // Starts one game: redirects its stdout/stderr to a per-launch log file
    // (named for the version AND the account — several games can run at once)
    // and reports its exit back to Home — the crash-diagnostics foundation the
    // boot watcher and CrashAnalyzer build on. Sets game.LogPath.
    private void StartWithLifecycleCapture(RunningGame game)
    {
        var process = game.Process;
        var version = game.Version;
        OriginPaths.EnsureScaffold();
        var logPath = Path.Combine(OriginPaths.Logs,
            $"{version}_{SafeFileName(game.AccountName)}_{DateTime.Now:yyyyMMdd_HHmmss}.log");
        game.LogPath = logPath;

        StreamWriter? logWriter = null;
        try { logWriter = new StreamWriter(logPath, append: false) { AutoFlush = true }; }
        catch { /* logging is best-effort — a failed log file must never block a launch */ }

        // Hard rule: the launched game is fully independent of the launcher.
        // Nothing the child process does — exiting, crashing, or a late burst of
        // stdout/stderr as it closes — may EVER take the launcher down. This used
        // to be the "launcher closes when the game closes, and Play won't work
        // again" bug: the Exited handler disposed logWriter, but stdout/stderr
        // callbacks (which run on their own threads and can fire AFTER Exited
        // while buffered output drains) then wrote to the disposed writer, throwing
        // ObjectDisposedException on a ThreadPool thread — and with no global
        // handler (App.xaml.cs), an unhandled background-thread exception kills the
        // whole process. stdout and stderr also shared one non-thread-safe writer.
        //
        // Fix: serialize every write, dispose the writer exactly ONCE and only
        // after BOTH streams have signalled end-of-stream (null Data) so no write
        // can race the dispose, and wrap every callback so an exception can never
        // escape onto a background thread.
        var gate = new object();
        var openStreams = 2;

        void OnData(string? data)
        {
            try
            {
                if (data == null)
                {
                    // stdout or stderr reached EOF; dispose once both have.
                    if (Interlocked.Decrement(ref openStreams) == 0)
                        lock (gate) { logWriter?.Dispose(); logWriter = null; }
                    return;
                }
                // Origin social: the party leader's warp needs the server this
                // game is on, which only its own console output says. Only the
                // rare matching lines cross over to the UI thread.
                if (RunningGameTracker.ParseConnect(data) != null || RunningGameTracker.IsSingleplayerStart(data))
                    Dispatcher.BeginInvoke(() => OnGameWhere(game, data));
                // The startup handshake: only this launch's token counts.
                if (game.Handoff.Observe(data))
                    Dispatcher.BeginInvoke(() => OnHandoff(game));
                lock (gate) { logWriter?.WriteLine(data); }
            }
            catch { /* best-effort logging; never surface onto this thread */ }
        }

        process.StartInfo.RedirectStandardOutput = true;
        process.StartInfo.RedirectStandardError = true;
        process.StartInfo.UseShellExecute = false;
        process.EnableRaisingEvents = true;

        process.OutputDataReceived += (_, args) => OnData(args.Data);
        process.ErrorDataReceived += (_, args) => OnData(args.Data);
        process.Exited += (_, _) =>
        {
            // Runs on a ThreadPool thread — must be exception-proof end to end.
            try
            {
                int exitCode;
                try { exitCode = process.ExitCode; } catch { exitCode = -1; }

                Dispatcher.BeginInvoke(() => OnGameExited(game, exitCode));

                // Settings Sync: take in what the player changed this session.
                // Checks the switch itself and never throws; already on a pool
                // thread, so its file work never touches the UI.
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
            // Start failed — release the log handle and let PlayButton_Click's
            // catch report it, rather than leaking the writer.
            lock (gate) { logWriter?.Dispose(); logWriter = null; }
            throw;
        }
    }

    // A game process ended (UI thread). The account goes back to Play; an
    // exit after the game was up is reported (a boot crash is the boot
    // watcher's to report — it has the details).
    private void OnGameExited(RunningGame game, int exitCode)
    {
        var wasRunning = _launches.PhaseOf(game.Key) == GamePhase.Running
                         && ReferenceEquals(_launches.GameOf(game.Key), game);
        if (_launches.Exited(game.Key, game) && wasRunning)
        {
            Note(game.Key,
                exitCode == 0 ? "Ready" : $"Minecraft exited with code {exitCode}",
                exitCode == 0 ? LaunchTone.Neutral : LaunchTone.Error);
        }
        SyncSocialMirror();
        RefreshPlayButton();
    }

    private static string SafeFileName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var safe = new string(name.Select(c => invalid.Contains(c) ? '_' : c).ToArray());
        return safe.Length == 0 ? "player" : safe;
    }

    // Starts/stops the Play button's spinning launch arc (which label shows
    // around it is ApplyPlayButton's call). Same clock pattern as LaunchLoadingOverlay: a fresh RotateTransform +
    // AnimationClock per run, stopped and dropped on restore so nothing keeps
    // ticking behind a static button.
    private AnimationClock? _playSpinClock;

    /// <summary>
    /// Render mode only (UI/RenderShots.cs): show the Play button the way it
    /// looks while the selected account's game is open, without a game.
    /// </summary>
    internal void TourShowRunning(string playerName)
    {
        ApplyPlayButton(AccountLaunchBook.ButtonFor(GamePhase.Running, canPlay: true, hasVersion: true));
        SetStatus($"Running · {playerName}", StatusTone.Success);
    }

    /// <summary>
    /// Render mode only (UI/RenderShots.cs): the launch progress screen,
    /// without preparing or starting anything.
    /// </summary>
    internal void TourShowLaunch(string version) => LoadingOverlay.Show(version);

    internal void TourReportLaunch(string stage) => LoadingOverlay.ReportStage(stage);

    internal void TourHideLaunch() => LoadingOverlay.Hide();

    internal void TourShowVersions(int detailIndex) => VersionOverlay.TourOpen(detailIndex);

    internal void TourHideVersions() => VersionOverlay.TourClose();

    /// <summary>Render mode only: Home as if <paramref name="profile"/> were selected (no settings are touched).</summary>
    internal void TourShowProfile(ModProfile profile)
    {
        _selectedVersion = profile.Version;
        SetVersionButtonText(profile.Version);
        ApplyProfile(new LauncherSettings { ModProfiles = { profile }, SelectedProfileId = profile.Id });
        JoinButton.IsEnabled = true;
    }

    private enum StatusTone
    {
        Neutral,
        Success,
        Warning,
        Error
    }

    private void SetStatus(string text, StatusTone tone)
    {
        StatusText.Text = text;
        var brushKey = tone switch
        {
            StatusTone.Success => "Brush.Success",
            StatusTone.Warning => "Brush.Warning",
            StatusTone.Error => "Brush.Danger",
            _ => "Brush.Accent"
        };
        StatusDot.Fill = (Brush)FindResource(brushKey);
    }

    private void SetPlayLaunching(bool launching)
    {
        if (launching && _playSpinClock != null) return; // already spinning

        PlaySpinner.Visibility = launching ? Visibility.Visible : Visibility.Collapsed;

        if (launching)
        {
            var transform = new RotateTransform();
            PlaySpinner.RenderTransform = transform;
            var spin = new DoubleAnimation(0, 360, new Duration(TimeSpan.FromSeconds(1.1)))
            {
                RepeatBehavior = RepeatBehavior.Forever
            };
            _playSpinClock = spin.CreateClock();
            transform.ApplyAnimationClock(RotateTransform.AngleProperty, _playSpinClock);
        }
        else
        {
            _playSpinClock?.Controller?.Stop();
            _playSpinClock = null;
        }
    }
}
