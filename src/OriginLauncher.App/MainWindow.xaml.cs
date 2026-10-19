using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.App.UI.Pages;

namespace OriginLauncher.App;

public partial class MainWindow : Window
{
    private readonly HomePage _homePage = new();
    private readonly SettingsPage _settingsPage = new();
    private readonly ModsPage _modsPage = new();
    private readonly ProfilesPage _profilesPage;
    private readonly AccountSwitcherPanel _accountPanel = new();
    private readonly FriendsPanel _friendsPanel = new();
    private bool _accountPanelOpen;
    private bool _signInPanelOpen;
    private bool _friendsOpen;
    private bool _importPanelOpen;
    private ImportPanel? _importPanel;
    private bool _promptChecking;
    private bool _syncingNavigation;
    private LauncherPage _currentPage = LauncherPage.Home;

    private enum LauncherPage
    {
        Home,
        Mods,
        Profiles,
        Settings
    }

    // Throttles the on-focus update re-check so window focus-flapping can't spam
    // GitHub's (unauthenticated, 60/hr) releases API.
    private DateTime _lastFocusCheck = DateTime.MinValue;

    public MainWindow()
    {
        InitializeComponent();
        // Needs Home to say whether a game is using a profile (no deleting it then).
        _profilesPage = new ProfilesPage(_homePage.IsProfileInUse);
        PageHost.Content = _homePage;
        // Opening animation over the already-built Home; ~0.85 s, skippable (StartupIntro).
        Intro.Play(this);

        _accountPanel.CloseRequested += (_, _) => SetAccountPanelOpen(false);
        _accountPanel.AccountsChanged += (_, _) =>
        {
            _homePage.RefreshAccountState();
            // Every account has its own game folders, mods included: the Mods
            // page follows the selected account.
            if (_currentPage == LauncherPage.Mods) _modsPage.ShowVersion(_homePage.CurrentVersion);
            // Friends belong to the selected account: sign in again as it.
            SocialHub.Service.ResetAccount();
            // A player who just signed in for the first time can now be offered the one-time import card.
            _ = MaybeShowImportPromptAsync();
        };
        _accountPanel.AccountSelected += (_, _) => SetAccountPanelOpen(false);
        _accountPanel.AddAccountRequested += (_, _) => OpenSignInPanel();
        AccountPanelHost.Content = _accountPanel;

        // The scrim is up exactly while the account panel or the sign-in card
        // is (including their exit animations): Home's banner pauses under it.
        Scrim.IsVisibleChanged += (_, _) => _homePage.SetChromeOverlayOpen(Scrim.IsVisible);

        NavHome.IsChecked = true;

        _profilesPage.ImportRequested += (_, _) => OpenImportPanel();
        _homePage.ImportCard.ImportRequested += (_, _) => OpenImportPanel();
        ContentRendered += async (_, _) => await AfterFirstRenderAsync();

        InitSocial();

        // Push-to-update: light the corner badge whenever a newer release
        // appears, and start the poll loop (startup + every 10 minutes).
        UpdateService.AvailableChanged += (_, _) => Dispatcher.Invoke(() =>
        {
            UpdateBadge.Visibility = Visibility.Visible;
            _homePage.RefreshUpdateGate();
        });
        _ = PollForUpdatesAsync();

        // Re-check the instant the launcher regains focus, so a release Will
        // just published is caught immediately instead of on the next poll tick
        // (throttled — see _lastFocusCheck).
        Activated += async (_, _) =>
        {
            var now = DateTime.UtcNow;
            if (now - _lastFocusCheck < TimeSpan.FromSeconds(30)) return;
            _lastFocusCheck = now;
            await UpdateService.CheckAsync();
        };
    }

    // Poll loop lives for the app's lifetime; process shutdown ends it. A short
    // interval keeps the corner badge close to "instant" after a publish; the
    // hard gate is the fresh re-check on the Play click, which never waits.
    private async Task PollForUpdatesAsync()
    {
        while (true)
        {
            await UpdateService.CheckAsync();
            await Task.Delay(TimeSpan.FromMinutes(2));
        }
    }

    private async void UpdateBadge_Click(object sender, RoutedEventArgs e)
    {
        if (UpdateService.Available is not { } update) return;

        UpdateBadge.IsEnabled = false;
        UpdateBadge.ToolTip = "Updating...";

        // Show the download on Home's full-page loading scene (a real
        // percentage fill), unless a game launch is mid-flight there — then
        // the button tooltip alone carries the progress.
        if (_accountPanelOpen) SetAccountPanelOpen(false);
        NavigateTo(LauncherPage.Home);
        var onScene = _currentPage == LauncherPage.Home
                      && _homePage.BeginUpdateScene(update.Version.ToString());
        var progress = new Progress<UpdateService.UpdateProgress>(p =>
        {
            UpdateBadge.ToolTip = p.Describe();
            if (onScene) _homePage.ReportUpdate(p);
        });

        try
        {
            // Downloads + stages the new build, then shuts this process down;
            // the swap script relaunches the updated launcher.
            await UpdateService.DownloadAndRestartAsync(progress);
        }
        catch (Exception ex)
        {
            if (onScene) _homePage.EndUpdateScene();
            UpdateBadge.IsEnabled = true;
            UpdateBadge.ToolTip = $"Update failed: {ex.Message} — click to retry";
        }
    }

    // ---- Origin social (docs/SOCIAL.md) ---------------------------------------

    private void InitSocial()
    {
        _friendsPanel.CloseRequested += (_, _) => SetFriendsOpen(false);
        _friendsPanel.AddAccountRequested += (_, _) =>
        {
            SetFriendsOpen(false);
            SetAccountPanelOpen(true);
        };
        _friendsPanel.JoinRequested += request => _ = JoinAsync(request);
        FriendsPanelHost.Content = _friendsPanel;

        Toasts.Opened += OnToastOpened;
        Toasts.MoreClicked += () => SetFriendsOpen(true);
        Toasts.ActionClicked += toast => _ = OnToastActionAsync(toast);

        var social = SocialHub.Service;
        social.Changed += snapshot => Dispatcher.BeginInvoke(() => ApplySocial(snapshot));
        social.ToastRaised += toast => Dispatcher.BeginInvoke(() => ShowSocialToast(toast));
        ApplySocial(social.Snapshot);

        // Sign-in and the first sync start once the first frame is on screen,
        // all on the thread pool — never on the cold-start path.
        ContentRendered += (_, _) => social.Start();

        // The sync slows to once a minute after 5 minutes in the background.
        Activated += (_, _) => social.SetAppActive(true);
        Deactivated += (_, _) => social.SetAppActive(false);
        StateChanged += (_, _) =>
        {
            if (WindowState == WindowState.Minimized) social.SetAppActive(false);
        };
    }

    private void ApplySocial(SocialSnapshot snapshot)
    {
        _friendsPanel.Apply(snapshot);
        var attention = snapshot.AttentionCount;
        FriendsBadge.Visibility = attention > 0 ? Visibility.Visible : Visibility.Collapsed;
        FriendsButton.ToolTip = attention > 0 ? $"Friends — {attention} new" : "Friends";
    }

    private void ShowSocialToast(SocialToast toast)
    {
        // Requests and invites are right there in an open drawer.
        if (_friendsOpen && toast.Kind is SocialToastKind.FriendRequest or SocialToastKind.PartyInvite) return;
        Toasts.Show(toast);
    }

    private void OnToastOpened(SocialToast toast)
    {
        SetFriendsOpen(true);
        if (toast.Kind == SocialToastKind.Message && toast.Uuid != null)
            _friendsPanel.OpenConversation(toast.Uuid);
    }

    private async Task OnToastActionAsync(SocialToast toast)
    {
        var social = SocialHub.Service;
        switch (toast.Kind)
        {
            case SocialToastKind.Warp:
                var leader = toast.Uuid != null ? social.Snapshot.NameOf(toast.Uuid) : "your party leader";
                await JoinAsync(new JoinRequest(leader, toast.Version, toast.Server));
                break;
            case SocialToastKind.PartyInvite when toast.PartyId != null:
                ReportToastAction(await social.AcceptPartyAsync(toast.PartyId));
                break;
            case SocialToastKind.FriendRequest when toast.Uuid != null:
                ReportToastAction(await social.RespondAsync(toast.Uuid, true));
                break;
        }
    }

    private void ReportToastAction(SocialActionResult result)
    {
        if (result.Ok || string.IsNullOrEmpty(result.Error)) return;
        SetFriendsOpen(true);
        _friendsPanel.ShowNotice(result.Error, error: true);
    }

    /// <summary>
    /// Join a friend's (or the party leader's) server: launch THEIR Minecraft
    /// version straight into it, through Home's normal launch path. A version
    /// Origin doesn't ship is explained instead.
    /// </summary>
    private async Task JoinAsync(JoinRequest request)
    {
        var block = JoinArguments.Check(request.Version, request.Server, VersionCatalog.IsSupported);
        if (block != JoinBlock.None)
        {
            var why = JoinArguments.Explain(block, request.Who, request.Version);
            if (_friendsOpen) _friendsPanel.ShowNotice(why, error: true);
            else Toasts.Show(new SocialToast(SocialToastKind.Notice, "Can't join", why));
            return;
        }

        if (_signInPanelOpen) CloseSignInPanel();
        if (_accountPanelOpen) SetAccountPanelOpen(false);
        if (_friendsOpen) SetFriendsOpen(false);
        NavigateTo(LauncherPage.Home);
        if (_currentPage != LauncherPage.Home) return;
        await _homePage.JoinServerAsync(request.Version!, request.Server!);
    }

    private void FriendsButton_Click(object sender, RoutedEventArgs e) => SetFriendsOpen(!_friendsOpen);

    private bool AnyDrawerOpen => _accountPanelOpen || _signInPanelOpen || _friendsOpen || _importPanelOpen;

    private void SetFriendsOpen(bool open)
    {
        if (open == _friendsOpen) return;
        if (open && (_signInPanelOpen || _importPanelOpen)) return; // finish (or cancel) signing in or importing first
        if (open && _accountPanelOpen) SetAccountPanelOpen(false);

        _friendsOpen = open;
        SocialHub.Service.SetDrawerOpen(open);
        _friendsPanel.SetShown(open);
        Toasts.Margin = new Thickness(0, 0, open ? FriendsDrawer.Width + 20 : 20, 20);

        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var duration = (Duration)FindResource(open ? "Motion.Scene" : "Motion.Exit");

        if (open)
        {
            Scrim.Visibility = Visibility.Visible;
            FriendsDrawer.Visibility = Visibility.Visible;
        }
        FriendsDrawer.IsHitTestVisible = open;

        var slide = new DoubleAnimation(open ? 0 : FriendsDrawer.Width, duration) { EasingFunction = ease };
        slide.Completed += (_, _) =>
        {
            if (!_friendsOpen)
            {
                FriendsDrawer.Visibility = Visibility.Collapsed;
                if (!AnyDrawerOpen) Scrim.Visibility = Visibility.Collapsed;
            }
        };
        FriendsDrawerTransform.BeginAnimation(TranslateTransform.XProperty, slide, HandoffBehavior.SnapshotAndReplace);

        Scrim.IsHitTestVisible = open;
        Scrim.BeginAnimation(OpacityProperty, new DoubleAnimation(open ? 0.5 : 0.0, duration),
            HandoffBehavior.SnapshotAndReplace);

        if (open)
            Dispatcher.BeginInvoke(() => FriendsPanelHost.MoveFocus(new TraversalRequest(FocusNavigationDirection.First)));
    }

    // ---- render mode (CI screenshots, UI/RenderShots.cs) ----------------------------

    internal FriendsPanel TourFriendsPanel => _friendsPanel;

    internal bool TourFriendsOpen => _friendsOpen;

    internal void TourSetFriendsOpen(bool open) => SetFriendsOpen(open);

    internal void TourShowToast(SocialToast toast) => Toasts.Show(toast);

    internal void TourClearToasts() => Toasts.Clear();

    internal void TourShowRunning(string playerName) => _homePage.TourShowRunning(playerName);

    internal void TourShowLaunch(string version) => _homePage.TourShowLaunch(version);

    internal void TourReportLaunch(string stage) => _homePage.TourReportLaunch(stage);

    internal void TourHideLaunch() => _homePage.TourHideLaunch();

    internal void TourShowVersions(int detailIndex) => _homePage.TourShowVersions(detailIndex);

    internal void TourHideVersions() => _homePage.TourHideVersions();

    internal void TourSetAccountOpen(bool open)
    {
        if (!Core.OriginPaths.IsScratchRoot) return;
        SetAccountPanelOpen(open);
    }

    /// <summary>Render mode: 0 = Home, 1 = Mods, 2 = Settings.</summary>
    internal void TourGoTo(int page)
    {
        if (!Core.OriginPaths.IsScratchRoot) return;
        switch (page)
        {
            case 1: NavMods.IsChecked = true; break;
            case 2: NavSettings.IsChecked = true; break;
            default: NavHome.IsChecked = true; break;
        }
    }

    // The Profiles page with made-up profiles (never the real settings), the
    // new-profile form open or not.
    internal void TourShowProfiles(Core.Models.LauncherSettings demo, Func<Core.Profiles.ModProfile, int> countMods, bool openForm)
    {
        NavigateTo(LauncherPage.Profiles);
        _profilesPage.ShowDemo(demo, countMods);
        if (openForm) _profilesPage.TourOpenForm();
    }

    // Home with a made-up selected profile (its row and, with a server, Join).
    internal void TourShowHomeWithProfile(Core.Profiles.ModProfile profile)
    {
        NavigateTo(LauncherPage.Home);
        _homePage.TourShowProfile(profile);
    }

    private void OpenSignInPanel()
    {
        if (_signInPanelOpen) return;

        _accountPanel.SetAddAccountEnabled(false);

        var signIn = new MicrosoftSignInPanel();
        signIn.SignInSucceeded += (_, result) =>
        {
            // Done: close the card and the switcher. Otherwise (saved for
            // later, or couldn't save) the card stays up with the reason until
            // the player presses OK — it never just vanishes.
            if (_accountPanel.CompleteSignIn(result) is { } outcome)
            {
                signIn.ShowMessage(outcome.Title, outcome.Message);
                return;
            }
            CloseSignInPanel();
            SetAccountPanelOpen(false);
        };
        signIn.SignInFailed += (_, message) =>
        {
            _accountPanel.ShowSignInError(message);
            signIn.ShowMessage("Couldn't add this account", message);
        };
        signIn.Cancelled += (_, _) => CloseSignInPanel();

        SignInPanelHost.Content = signIn;
        SetSignInPanelOpen(true);
    }

    private void CloseSignInPanel()
    {
        SetSignInPanelOpen(false);
        _accountPanel.SetAddAccountEnabled(true);
        // Dispose the WebView2 before dropping the reference — see the comment
        // on MicrosoftSignInPanel.Dispose(); without this, adding a second
        // account can find the first sign-in's browser process/user data
        // folder still locked and fail to load.
        (SignInPanelHost.Content as MicrosoftSignInPanel)?.Dispose();
        SignInPanelHost.Content = null;
    }

    private void RootGrid_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed)
            DragMove();
    }

    private void MinimizeButton_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState.Minimized;
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }

    private void NavHome_Checked(object sender, RoutedEventArgs e)
    {
        NavigateTo(LauncherPage.Home);
    }

    private void NavMods_Checked(object sender, RoutedEventArgs e)
    {
        NavigateTo(LauncherPage.Mods);
    }

    private void NavProfiles_Checked(object sender, RoutedEventArgs e)
    {
        NavigateTo(LauncherPage.Profiles);
    }

    private void NavSettings_Checked(object sender, RoutedEventArgs e)
    {
        NavigateTo(LauncherPage.Settings);
    }

    private void Nav_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_syncingNavigation) return;
        SetNavigationSelection(_currentPage);
    }

    private void NavigateTo(LauncherPage page)
    {
        if (_syncingNavigation) return;

        // The version picker and launch cover are modal within Home. Keep the
        // left rail and its keyboard shortcuts from navigating behind either
        // scene and preserving a hidden modal until the player returns.
        if (page != _currentPage && _currentPage == LauncherPage.Home && _homePage.HasOpenScene)
        {
            SetNavigationSelection(_currentPage);
            return;
        }

        SetNavigationSelection(page);
        if (page == _currentPage && PageHost.Content != null) return;

        var direction = Math.Sign((int)page - (int)_currentPage);
        _currentPage = page;

        object content = page switch
        {
            LauncherPage.Home => _homePage,
            LauncherPage.Mods => _modsPage,
            LauncherPage.Profiles => _profilesPage,
            _ => _settingsPage
        };

        if (page == LauncherPage.Home)
        {
            _homePage.RefreshAccountState();
            _homePage.RefreshProfile(); // a profile picked on the Profiles page
        }
        else if (page == LauncherPage.Mods)
            _modsPage.ShowVersion(_homePage.CurrentVersion);
        else if (page == LauncherPage.Profiles)
            _profilesPage.Show(_homePage.CurrentVersion);

        PageHost.Content = content;
        AnimatePageIn(direction == 0 ? 1 : direction);
    }

    private void SetNavigationSelection(LauncherPage page)
    {
        _syncingNavigation = true;
        NavHome.IsChecked = page == LauncherPage.Home;
        NavMods.IsChecked = page == LauncherPage.Mods;
        NavProfiles.IsChecked = page == LauncherPage.Profiles;
        NavSettings.IsChecked = page == LauncherPage.Settings;
        _syncingNavigation = false;
    }

    private void AnimatePageIn(int direction)
    {
        PageHost.BeginAnimation(OpacityProperty, null);
        PageTransform.BeginAnimation(TranslateTransform.XProperty, null);

        if (!SystemParameters.ClientAreaAnimation)
        {
            PageHost.Opacity = 1;
            PageTransform.X = 0;
            return;
        }

        var duration = (Duration)FindResource("Motion.Scene");
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        PageHost.BeginAnimation(OpacityProperty,
            new DoubleAnimation(0.72, 1, duration) { EasingFunction = ease },
            HandoffBehavior.SnapshotAndReplace);
        PageTransform.BeginAnimation(TranslateTransform.XProperty,
            new DoubleAnimation(direction * 14, 0, duration) { EasingFunction = ease },
            HandoffBehavior.SnapshotAndReplace);
    }

    private void AccountButton_Click(object sender, RoutedEventArgs e)
    {
        if (!_accountPanelOpen && _friendsOpen) SetFriendsOpen(false);
        SetAccountPanelOpen(!_accountPanelOpen);
    }

    private void Scrim_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (_importPanelOpen) { _ = _importPanel?.RequestClose(); }
        else if (_signInPanelOpen) CloseSignInPanel();
        else if (_friendsOpen) SetFriendsOpen(false);
        else SetAccountPanelOpen(false);
    }

    private void SetAccountPanelOpen(bool open)
    {
        _accountPanelOpen = open;
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var duration = (Duration)FindResource(open ? "Motion.Scene" : "Motion.Exit");

        if (open)
        {
            _accountPanel.Reload();
            Scrim.Visibility = Visibility.Visible;
            AccountPanel.Visibility = Visibility.Visible;
        }
        AccountPanel.IsHitTestVisible = open;

        var panelAnimation = new DoubleAnimation(open ? 0 : AccountPanel.Width, duration)
        {
            EasingFunction = ease
        };
        panelAnimation.Completed += (_, _) =>
        {
            if (!_accountPanelOpen)
            {
                AccountPanel.Visibility = Visibility.Collapsed;
                if (!AnyDrawerOpen) Scrim.Visibility = Visibility.Collapsed;
            }
        };
        AccountPanelTransform.BeginAnimation(TranslateTransform.XProperty, panelAnimation,
            HandoffBehavior.SnapshotAndReplace);

        Scrim.IsHitTestVisible = open;
        Scrim.BeginAnimation(
            OpacityProperty,
            new DoubleAnimation(open ? 0.5 : 0.0, duration),
            HandoffBehavior.SnapshotAndReplace);

        if (open)
            Dispatcher.BeginInvoke(() => AccountPanelHost.MoveFocus(
                new TraversalRequest(FocusNavigationDirection.First)));
    }

    private void SetSignInPanelOpen(bool open)
    {
        _signInPanelOpen = open;
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var duration = (Duration)FindResource(open ? "Motion.Scene" : "Motion.Exit");

        if (open)
        {
            SignInPanel.Visibility = Visibility.Visible;
            Scrim.Visibility = Visibility.Visible;
        }

        SignInPanel.IsHitTestVisible = open;
        var fade = new DoubleAnimation(open ? 1.0 : 0.0, duration);
        fade.Completed += (_, _) =>
        {
            if (!_signInPanelOpen)
            {
                SignInPanel.Visibility = Visibility.Collapsed;
                if (!AnyDrawerOpen) Scrim.Visibility = Visibility.Collapsed;
            }
        };
        SignInPanel.BeginAnimation(OpacityProperty, fade, HandoffBehavior.SnapshotAndReplace);
        SignInPanelScale.BeginAnimation(
            ScaleTransform.ScaleXProperty,
            new DoubleAnimation(open ? 1.0 : 0.97, duration) { EasingFunction = ease },
            HandoffBehavior.SnapshotAndReplace);
        SignInPanelScale.BeginAnimation(
            ScaleTransform.ScaleYProperty,
            new DoubleAnimation(open ? 1.0 : 0.97, duration) { EasingFunction = ease },
            HandoffBehavior.SnapshotAndReplace);
    }

    // ---- import from another Minecraft launcher (UI/Controls/ImportPanel) -------------

    // Opens the import card (the Profiles page's Import button, the first-run card, `--import`). The scan
    // starts here, when the card opens - never at launcher startup. The backend is the real one unless
    // the CI screen render passes a made-up one.
    private void OpenImportPanel(IImportBackend? backend = null)
    {
        if (_importPanelOpen || _signInPanelOpen) return;
        if (_accountPanelOpen) SetAccountPanelOpen(false);
        if (_friendsOpen) SetFriendsOpen(false);
        _homePage.ImportCard.Hide();

        var panel = new ImportPanel(backend ?? TourBackendFactory?.Invoke() ?? new LauncherImportBackend())
        {
            ConfirmHost = ImportConfirm,
        };
        // WIRING (import-wiring agent): feed the shared busy states in here once they exist on this branch:
        //   LaunchRunState.Changed -> panel.SetLaunchBusy(...), UpdateRunState.Changed -> panel.SetUpdateBusy(...),
        // and the window's close button asks `await panel.RequestClose()` (true = go ahead and close).
        panel.CloseRequested += (_, _) => CloseImportPanel();
        panel.Imported += (_, _) =>
        {
            // The import changed the selected version and added a profile: Home picks both up.
            _homePage.RefreshProfile();
        };
        _importPanel = panel;
        ImportPanelHost.Content = panel;
        SetImportPanelOpen(true);
        panel.Start();
    }

    private void CloseImportPanel()
    {
        if (!_importPanelOpen) return;
        SetImportPanelOpen(false);
        _importPanel?.Dispose(); // cancels a scan or plan in flight; the drawer's content stays until it has slid out
        _importPanel = null;
    }

    // Slides the Import drawer in and out exactly like the Friends drawer (SetFriendsOpen): the same ease, Motion.Scene in
    // and Motion.Exit out, the shared Scrim 0 <-> 0.5, the toasts shift left of it. The page behind is switched off while
    // it is open, so the keyboard cannot reach it either.
    private void SetImportPanelOpen(bool open)
    {
        _importPanelOpen = open;
        Toasts.Margin = new Thickness(0, 0, open ? ImportDrawer.Width + 20 : 20, 20);
        PageHost.IsEnabled = !open;

        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var duration = (Duration)FindResource(open ? "Motion.Scene" : "Motion.Exit");

        if (open)
        {
            Scrim.Visibility = Visibility.Visible;
            ImportDrawer.Visibility = Visibility.Visible;
        }
        ImportDrawer.IsHitTestVisible = open;

        var slide = new DoubleAnimation(open ? 0 : ImportDrawer.Width, duration) { EasingFunction = ease };
        slide.Completed += (_, _) =>
        {
            if (!_importPanelOpen)
            {
                ImportDrawer.Visibility = Visibility.Collapsed;
                ImportPanelHost.Content = null;
                if (!AnyDrawerOpen) Scrim.Visibility = Visibility.Collapsed;
            }
        };
        ImportDrawerTransform.BeginAnimation(TranslateTransform.XProperty, slide, HandoffBehavior.SnapshotAndReplace);

        var scrimOn = open || AnyDrawerOpen;
        Scrim.IsHitTestVisible = scrimOn;
        Scrim.BeginAnimation(OpacityProperty, new DoubleAnimation(scrimOn ? 0.5 : 0.0, duration), HandoffBehavior.SnapshotAndReplace);

        if (open)
            Dispatcher.BeginInvoke(() => ImportPanelHost.MoveFocus(new TraversalRequest(FocusNavigationDirection.First)));
    }

    // After the first window is up: tell the player about an import that was rolled back at start (the
    // recovery itself ran on the thread pool), open the import card for `--import`, or - once, and only
    // when another launcher is found and the player could import - offer it.
    private async Task AfterFirstRenderAsync()
    {
        if (await ImportStartup.Recovery.ConfigureAwait(true) > 0)
            Toasts.Show(new SocialToast(SocialToastKind.Notice, "Import", "An interrupted import was undone."));

        if (App.IsRenderMode) return;
        if (App.StartWithImport)
        {
            await Task.Delay(1200); // let the opening animation finish
            OpenImportPanel();
            return;
        }
        await Task.Delay(3000); // never compete with startup
        await MaybeShowImportPromptAsync();
    }

    private async Task MaybeShowImportPromptAsync()
    {
        if (_promptChecking || App.IsRenderMode || App.StartWithImport) return;
        _promptChecking = true;
        try
        {
            // Off the UI thread: the settings read, the account check and the quick scan.
            var found = await Task.Run<List<DetectedInstance>?>(async () =>
            {
                if (Core.SettingsStore.Load().ImportPromptSeen) return null;
                var canImport = Core.GameFolders.Launcher.AccountFolders.SelectedKey() != null;
                if (!canImport) return null; // the import would only say "sign in first": keep the one-time card for later
                var scan = await new LauncherImportBackend().ScanAsync(null, CancellationToken.None);
                return ImportPrompt.ShouldShow(alreadySeen: false, openedWithImportFlag: false, canImport, scan.Instances.Count)
                    ? scan.Instances : null;
            });
            if (found == null || _importPanelOpen) return;

            // The card sits in Home's banner slot (it shows when Home does).
            _homePage.ImportCard.ShowQuestion(ImportPromptCard.QuestionFor(found));
            // Shown once: set the moment it appears, so it never comes back.
            _ = Task.Run(() => Core.SettingsStore.Update(s => s.ImportPromptSeen = true));
        }
        catch (Exception)
        {
            // a failed look for other launchers just means no card
        }
        finally
        {
            _promptChecking = false;
        }
    }

    // ---- render mode (CI): the import card with a made-up backend, never the real machine ----

    internal ImportPanel TourOpenImport(IImportBackend backend)
    {
        if (_importPanelOpen) CloseImportPanel();
        OpenImportPanel(backend);
        return _importPanel!;
    }

    internal void TourCloseImport() => CloseImportPanel();

    // CI screen render: the backend a real click on Import gets (never the real machine).
    internal Func<IImportBackend>? TourBackendFactory { get; set; }

    internal void TourClickProfilesImport() => _profilesPage.TourClickImport();

    internal ImportPromptCard TourImportPrompt => _homePage.ImportCard;

    private void MainWindow_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            if (_importPanelOpen) _importPanel?.HandleEscape();
            else if (_signInPanelOpen) CloseSignInPanel();
            else if (_accountPanelOpen) SetAccountPanelOpen(false);
            else if (_friendsOpen)
            {
                if (!_friendsPanel.HandleEscape()) SetFriendsOpen(false);
            }
            else return;
            e.Handled = true;
            return;
        }

        // The import card is modal: no page shortcuts, and Enter belongs to it.
        if (_importPanelOpen) return;

        // A Home scene owns the keyboard while it is open. In particular,
        // Ctrl+1/2/3/4 must not navigate behind the version picker and leave it
        // invisibly open until the player returns to Home.
        if (_currentPage == LauncherPage.Home && _homePage.HasOpenScene)
            return;

        if ((Keyboard.Modifiers & ModifierKeys.Control) != 0)
        {
            var target = e.Key switch
            {
                Key.D1 or Key.NumPad1 => LauncherPage.Home,
                Key.D2 or Key.NumPad2 => LauncherPage.Mods,
                Key.D3 or Key.NumPad3 => LauncherPage.Profiles,
                Key.D4 or Key.NumPad4 => LauncherPage.Settings,
                _ => (LauncherPage?)null
            };
            if (target is { } page)
            {
                NavigateTo(page);
                e.Handled = true;
            }
            return;
        }

        if (e.Key == Key.Enter && _currentPage == LauncherPage.Home
            && !_accountPanelOpen && !_signInPanelOpen && !_friendsOpen
            && Keyboard.FocusedElement is not TextBoxBase
            && Keyboard.FocusedElement is not Selector
            && Keyboard.FocusedElement is not ButtonBase)
        {
            e.Handled = _homePage.TryLaunchFromKeyboard();
        }
    }
}
