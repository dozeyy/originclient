using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core.Auth;
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
    private readonly AccountSwitcherPanel _accountPanel = new();
    private readonly FriendsPanel _friendsPanel = new();
    private bool _accountPanelOpen;
    private bool _signInPanelOpen;
    private bool _friendsOpen;
    private bool _syncingNavigation;
    private LauncherPage _currentPage = LauncherPage.Home;

    private enum LauncherPage
    {
        Home,
        Mods,
        Settings
    }

    // Throttles the on-focus update re-check so window focus-flapping can't spam
    // GitHub's (unauthenticated, 60/hr) releases API.
    private DateTime _lastFocusCheck = DateTime.MinValue;

    public MainWindow()
    {
        InitializeComponent();
        PageHost.Content = _homePage;
        // Home is already built underneath, so the intro adds no wait of its own.
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
        };
        _accountPanel.AccountSelected += (_, _) => SetAccountPanelOpen(false);
        _accountPanel.AddAccountRequested += (_, _) => OpenSignInPanel();
        AccountPanelHost.Content = _accountPanel;

        // The scrim is up exactly while the account panel or the sign-in card
        // is (including their exit animations): Home's banner pauses under it.
        Scrim.IsVisibleChanged += (_, _) => _homePage.SetChromeOverlayOpen(Scrim.IsVisible);

        NavHome.IsChecked = true;

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

    private bool AnyDrawerOpen => _accountPanelOpen || _signInPanelOpen || _friendsOpen;

    private void SetFriendsOpen(bool open)
    {
        if (open == _friendsOpen) return;
        if (open && _signInPanelOpen) return; // finish (or cancel) signing in first
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

    internal void TourSetFriendsOpen(bool open) => SetFriendsOpen(open);

    internal void TourShowToast(SocialToast toast) => Toasts.Show(toast);

    internal void TourClearToasts() => Toasts.Clear();

    internal void TourShowRunning(string playerName) => _homePage.TourShowRunning(playerName);

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
            _ => _settingsPage
        };

        if (page == LauncherPage.Home)
            _homePage.RefreshAccountState();
        else if (page == LauncherPage.Mods)
            _modsPage.ShowVersion(_homePage.CurrentVersion);

        PageHost.Content = content;
        AnimatePageIn(direction == 0 ? 1 : direction);
    }

    private void SetNavigationSelection(LauncherPage page)
    {
        _syncingNavigation = true;
        NavHome.IsChecked = page == LauncherPage.Home;
        NavMods.IsChecked = page == LauncherPage.Mods;
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
        if (_signInPanelOpen) CloseSignInPanel();
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

    private void MainWindow_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            if (_signInPanelOpen) CloseSignInPanel();
            else if (_accountPanelOpen) SetAccountPanelOpen(false);
            else if (_friendsOpen)
            {
                if (!_friendsPanel.HandleEscape()) SetFriendsOpen(false);
            }
            else return;
            e.Handled = true;
            return;
        }

        // A Home scene owns the keyboard while it is open. In particular,
        // Ctrl+1/2/3 must not navigate behind the version picker and leave it
        // invisibly open until the player returns to Home.
        if (_currentPage == LauncherPage.Home && _homePage.HasOpenScene)
            return;

        if ((Keyboard.Modifiers & ModifierKeys.Control) != 0)
        {
            var target = e.Key switch
            {
                Key.D1 or Key.NumPad1 => LauncherPage.Home,
                Key.D2 or Key.NumPad2 => LauncherPage.Mods,
                Key.D3 or Key.NumPad3 => LauncherPage.Settings,
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
