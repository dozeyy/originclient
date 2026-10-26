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
    // Settings, Mods and Profiles are built only when first needed, and otherwise warmed a moment after
    // the opening animation (WarmPagesAtIdle): their XAML load and file/registry reads used to run before
    // the first frame. Always go through the *View accessors.
    private SettingsPage? _settingsPage;
    private ModsPage? _modsPage;
    private ProfilesPage? _profilesPage;
    private SettingsPage SettingsView => _settingsPage ??= new SettingsPage();
    private ModsPage ModsView => _modsPage ??= new ModsPage();
    private ProfilesPage ProfilesView => _profilesPage ??= CreateProfilesPage();
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
        Core.StartupTrace.Mark("MainWindow ctor: fields (HomePage etc.) built");
        InitializeComponent();
        Core.StartupTrace.Mark("MainWindow.InitializeComponent");
        PageHost.Content = _homePage;
        Core.StartupTrace.Mark("Home placed");
        // Opening animation over the already-built Home; ~0.85 s, skippable (StartupIntro).
        Intro.Play(this);

        // Home's profile chip opens Profiles; its launch screen covers the bar too.
        _homePage.LaunchSceneChanged += SetTopBarHidden;
        RefreshAccountChip();

        _accountPanel.CloseRequested += (_, _) => SetAccountPanelOpen(false);
        _accountPanel.AccountsChanged += (_, _) =>
        {
            _homePage.RefreshAccountState();
            RefreshAccountChip();
            // Every account has its own game folders, mods included: the Mods
            // page follows the selected account.
            if (_currentPage == LauncherPage.Mods) ModsView.ShowVersion(_homePage.CurrentVersion);
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

        // The first-run card: "Import everything" opens the page and runs it once the scan is in; "Choose" opens the
        // page on its pick screen.
        _homePage.ImportCard.ImportRequested += (_, _) => OpenImportPanel(intent: ImportPanel.Intent.Everything);
        _homePage.ImportCard.ChooseRequested += (_, _) => OpenImportPanel(intent: ImportPanel.Intent.Choose);
        HookImportWiring(); // MainWindow.ImportWiring.cs: closing mid-import, recovery refresh, run-state refusals, the page behind the panel
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

    private ProfilesPage CreateProfilesPage()
    {
        // Needs Home to say whether a game is using a profile (no deleting it then).
        var page = new ProfilesPage(_homePage.IsProfileInUse);
        page.ImportRequested += (_, _) => OpenImportPanel();
        return page;
    }

    // One page per idle slot, so no single hitch is longer than one page's build.
    private void WarmPagesAtIdle()
    {
        foreach (var warm in new Action[] { () => _ = SettingsView, () => _ = ModsView, () => _ = ProfilesView })
            Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.ApplicationIdle, warm);
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

    /// <summary>The one short word on the update badge: "Updating..." off the loading scene, or the failure.</summary>
    private void ShowUpdateNote(string text, string brushKey)
    {
        UpdateRetryText.Text = text;
        UpdateRetryText.Foreground = (Brush)FindResource(brushKey);
        UpdateRetryText.Visibility = Visibility.Visible;
    }

    private async void UpdateBadge_Click(object sender, RoutedEventArgs e)
    {
        if (UpdateService.Available is not { } update) return;
        // An import running now (or started while this downloads) is waited for before the restart, and no new
        // import can open or apply once the download is under way (Core/Import/Launcher/UpdateRunState).

        UpdateBadge.IsEnabled = false;
        UpdateRetryText.Visibility = Visibility.Collapsed;
        System.Windows.Automation.AutomationProperties.SetName(UpdateBadge, "Install update");

        // Show the download on Home's full-page loading scene (a real
        // percentage fill), unless a game launch is mid-flight there - then
        // the badge's own "Updating..." label carries it.
        if (_accountPanelOpen) SetAccountPanelOpen(false);
        NavigateTo(LauncherPage.Home);
        var onScene = _currentPage == LauncherPage.Home
                      && _homePage.BeginUpdateScene(update.Version.ToString());
        if (!onScene) ShowUpdateNote("Updating...", "Brush.TextDim");
        var toldAboutImport = false;
        var progress = new Progress<UpdateService.UpdateProgress>(p =>
        {
            if (p.Stage == UpdateService.UpdateStage.FinishingImport && !toldAboutImport)
            {
                toldAboutImport = true;
                ToastFinishingImportBeforeUpdate(); // MainWindow.ImportWiring.cs
            }
            if (onScene) _homePage.ReportUpdate(p);
        });

        try
        {
            // Downloads + stages the new build, then shuts this process down;
            // the swap script relaunches the updated launcher.
            await UpdateService.DownloadAndRestartAsync(progress);
        }
        catch (Exception ex) when (ex is UpdateVerificationException || UpdateService.Available == null)
        {
            // The download failed its integrity check, or failed twice in a row for any other reason: the update
            // was discarded and is out of the gate (UpdateService/UpdateGate), so the player launches what they
            // have; a quiet retry offers it again only once it verifies. Nothing blocking is shown.
            if (onScene) _homePage.EndUpdateScene();
            UpdateBadge.IsEnabled = true;
            UpdateBadge.Visibility = Visibility.Collapsed;
            _homePage.RefreshUpdateGate();
        }
        catch (Exception)
        {
            if (onScene) _homePage.EndUpdateScene();
            UpdateBadge.IsEnabled = true;
            // Visible, not a hover label: the badge says it failed and that a click retries.
            ShowUpdateNote("Update failed - retry", "Brush.Warning");
            System.Windows.Automation.AutomationProperties.SetName(UpdateBadge, "Update failed, retry");
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
        FriendsButton.ToolTip = attention > 0 ? $"Friends - {attention} new" : "Friends";
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

    private bool AnyDrawerOpen => _accountPanelOpen || _signInPanelOpen || _friendsOpen; // Import is a page now, not a drawer

    private void SetFriendsOpen(bool open)
    {
        if (open == _friendsOpen) return;
        if (open && (_signInPanelOpen || _importPanelOpen)) return; // finish (or cancel) signing in or importing first
        if (open && _accountPanelOpen) SetAccountPanelOpen(false);

        _friendsOpen = open;
        SocialHub.Service.SetDrawerOpen(open);
        _friendsPanel.SetShown(open);
        Toasts.Margin = new Thickness(0, 0, open ? FriendsDrawer.Width : 0, 104); // bottom-centre of what the drawer leaves free

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

    internal void TourShowReady(bool hover) => _homePage.TourShowReady(hover);

    internal void TourEndReady() => _homePage.TourEndReady();

    internal void TourShowLaunch(string version) => _homePage.TourShowLaunch(version);
    internal void TourShowMyMods(bool on) => _homePage.TourShowMyMods(on);

    internal void TourReportLaunch(string stage) => _homePage.TourReportLaunch(stage);

    internal void TourHideLaunch() => _homePage.TourHideLaunch();

    internal void TourCancelLaunch() => _homePage.TourCancelLaunch();

    internal void TourFailLaunch(Exception error, string version) => _homePage.TourFailLaunch(error, version);

    internal void TourShowVersions(int detailIndex) => _homePage.TourShowVersions(detailIndex);

    internal void TourHideVersions() => _homePage.TourHideVersions();

    // Render mode only: the version Home shows now (the selector shots check a pick reached it).
    internal string? TourHomeVersion => _homePage.CurrentVersion;

    internal void TourSetAccountOpen(bool open)
    {
        if (!Core.OriginPaths.IsScratchRoot) return;
        SetAccountPanelOpen(open);
    }

    /// <summary>Render mode: the pill as it reads for a made-up player (no account is read, written or signed in).</summary>
    internal void TourShowAccount(string? name, string uuid, bool offlineTestMode)
    {
        if (!Core.OriginPaths.IsScratchRoot) return;
        ShowAccountChip(name, uuid, offlineTestMode);
    }

    /// <summary>Render mode: the account drawer's rows for made-up players (the real list is not touched).</summary>
    internal void TourShowAccountRows(IReadOnlyList<Core.Models.MinecraftAccount> rows)
    {
        if (!Core.OriginPaths.IsScratchRoot) return;
        _accountPanel.TourShowRows(rows);
    }

    /// <summary>Render mode: the pill's measured geometry for the shot log (the head's inset in the pill, the name cut or not).</summary>
    internal string TourAccountFit()
    {
        var button = AccountButton;
        var head = AccountHeadHost.Visibility == Visibility.Visible
            ? AccountFace.TranslatePoint(new Point(0, 0), button)
            : (Point?)null;
        var headText = head is { } h
            ? $"head={AccountFace.ActualWidth:0.#}x{AccountFace.ActualHeight:0.#} at x={h.X:0.#} y={h.Y:0.#} (inset below {button.ActualHeight - h.Y - AccountFace.ActualHeight:0.#})"
            : "head=none (signed out)";
        return $"pill={button.ActualWidth:0.#}x{button.ActualHeight:0.#} {headText} name={AccountName.ActualWidth:0.#} cut={AccountNameIsTrimmed()} "
            + $"storeLabel={StoreLabel.Visibility} wordmark={BarWordmark.Visibility} {TourTopBarFit()}";
    }

    internal Button TourAccountButton => AccountButton;
    internal Button TourStoreButton => StoreButton;

    /// <summary>Render mode: the account pill as under the mouse (a render has no mouse): the hover fill, edge and ink.</summary>
    internal void TourHover(Button button, bool on)
    {
        if (!Core.OriginPaths.IsScratchRoot) return;
        if (!on)
        {
            button.ClearValue(BackgroundProperty);
            button.ClearValue(BorderBrushProperty);
            button.ClearValue(ForegroundProperty);
            return;
        }
        var pill = ReferenceEquals(button, AccountButton) && !ReferenceEquals(button.Style, FindResource("Button.AccountPill.SignedOut"));
        button.Background = new SolidColorBrush((Color)FindResource(pill ? "Color.CardAlt" : "Color.GhostHover"));
        if (pill) button.BorderBrush = new SolidColorBrush((Color)FindResource("Color.StrokeHover"));
        button.Foreground = (Brush)FindResource("Brush.Text");
    }

    /// <summary>Render mode: 0 = Home, 1 = Mods, 2 = Settings.</summary>
    // Render mode only: the Mods page with made-up rows (and a jar carried over the zone).
    internal void TourShowModsDemo(IReadOnlyList<Core.Mods.ModEntry> mods, int carrying)
    {
        TourGoTo(1);
        ModsView.TourShowDemo(mods, carrying);
    }

    /// <summary>Render mode: show or hide the "update waiting" badge, the widest state of the top bar.</summary>
    internal void TourShowUpdateBadge(bool show) =>
        UpdateBadge.Visibility = show ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>
    /// Render mode: lay the window's content out at <paramref name="size"/> (the OS caps a real window at the desktop's
    /// size, 1044x788 on the CI desktop) and run the top bar's own fit rule there, so the shot shows what a window
    /// that wide really does: the labels back, or not. The rule can toggle the labels and then slide the tabs, so
    /// it runs until it has settled; the caller snaps with laidOut so nothing re-measures at the capped size.
    /// </summary>
    internal void TourLayoutAt(Size size)
    {
        var root = (FrameworkElement)Content;
        // Not UpdateLayout (it also re-measures the window at the capped size) and not a plain Measure (skipped at a
        // size already used, so a label the rule just showed or hid would keep its old size): invalidate the whole
        // tree, lay it out at the wide size by hand, and run the rule, until it stops changing anything.
        for (var i = 0; i < 4; i++)
        {
            UI.RenderShots.Invalidate(root);
            root.Measure(size);
            root.Arrange(new Rect(size));
            TopBar_LayoutChanged(this, null!);
        }
        // A manual layout raises no SizeChanged, so what hangs on it (Home's density and the numeral's band) is run by hand.
        _homePage.TourSettleDensity();
        UI.RenderShots.Invalidate(root);
        root.Measure(size);
        root.Arrange(new Rect(size));
        _homePage.TourReportStripTop();
        Stage.TourRelayout();
        // The numeral's glyphs were just moved: arrange once more so the picture shows them there.
        UI.RenderShots.Invalidate(root);
        root.Measure(size);
        root.Arrange(new Rect(size));
    }

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
        ProfilesView.ShowDemo(demo, countMods);
        if (openForm) ProfilesView.TourOpenForm();
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

    // Render-shots only: the sign-in card as a player sees it while Microsoft's page loads, or with an outcome. The
    // panel skips its browser in a scratch-root run (MicrosoftSignInPanel), so nothing loads and nothing goes online.
    internal void TourShowSignIn(string? title, string? message)
    {
        var signIn = new MicrosoftSignInPanel();
        SignInPanelHost.Content = signIn;
        if (title != null && message != null) signIn.ShowMessage(title, message);
        SetSignInPanelOpen(true);
    }

    internal void TourCloseSignIn()
    {
        SetSignInPanelOpen(false);
        (SignInPanelHost.Content as MicrosoftSignInPanel)?.Dispose();
        SignInPanelHost.Content = null;
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

    // The tabs stay centred on the window until the right-hand actions (update, Store, import,
    // friends, account, window buttons) would come within Space.5 of SETTINGS, as at
    // 980 px with an update waiting; then they slide left just enough, never past the brand.
    // A render transform, so moving them never re-runs layout.
    //
    // When even the fully slid tabs would still touch the actions (Store carries a word), it drops to icon-only: its
    // label and arrow collapse, and come back as soon as the full width fits again. The decision uses the width the
    // actions would have WITH the label (_linkLabelExtra), so it cannot flip back and forth. Below WordmarkMinWindow
    // the brand lockup shows the logo alone (it is still the Website link); that depends on the window width only.
    private const double WordmarkMinWindow = 1000;
    private bool _linkLabelsHidden;
    private double _linkLabelExtra;

    private void SetLinkLabels(bool shown)
    {
        _linkLabelsHidden = !shown;
        var v = shown ? Visibility.Visible : Visibility.Collapsed;
        StoreLabel.Visibility = StoreArrow.Visibility = v;
    }

    /// <summary>Render mode: show the hover arrows as if the mouse were over the Website and Store buttons (a render has no mouse).</summary>
    internal void TourShowLinkHints(bool show)
    {
        foreach (var holder in new[] { WebsiteArrow, StoreArrow })
            foreach (var path in holder.Children.OfType<System.Windows.Shapes.Path>())
                if (show) path.Opacity = 0.8; else path.ClearValue(OpacityProperty);
    }

    /// <summary>Render mode: the space between the content of neighbouring buttons in the right-hand row.</summary>
    internal string TourLinkGaps()
    {
        Button[] row = [UpdateBadge, StoreButton, ImportButton];
        var parts = new List<string>();
        for (var i = 0; i + 1 < row.Length; i++)
        {
            if (row[i].Visibility != Visibility.Visible || row[i + 1].Visibility != Visibility.Visible) continue;
            // Content edge to content edge: the box gap (their margins) plus the padding either side.
            var a = row[i].TranslatePoint(new Point(row[i].ActualWidth, 0), BarActions).X - row[i].Padding.Right;
            var b = row[i + 1].TranslatePoint(new Point(0, 0), BarActions).X + row[i + 1].Padding.Left;
            parts.Add($"{row[i].Name}->{row[i + 1].Name} {b - a:0.#}");
        }
        return string.Join("; ", parts);
    }

    /// <summary>Render mode: the numbers the top bar's fit rule just used, for the shot log.</summary>
    internal string TourTopBarFit() =>
        $"topbar={TopBar.ActualWidth:0} brand={WebsiteButton.ActualWidth:0} tabs={NavTabs.ActualWidth:0} actions={BarActions.ActualWidth:0} "
        + $"labelExtra={_linkLabelExtra:0} labelsHidden={_linkLabelsHidden} tabShift={NavTabsShift.X:0}";

    // The Home numeral's measured geometry (UI/RenderShots.cs): the ink rectangle against the free band under the bar.
    internal string TourNumeralFit()
    {
        var (ink, top, bottom) = Stage.NumeralGeometry;
        if (ink is not { } r) return "numeral not laid out";
        return $"window={Stage.ActualWidth:0}x{Stage.ActualHeight:0} band={top:0.#}..{bottom:0.#} (middle {(top + bottom) / 2:0.#}) "
            + $"ink x={r.Left:0.#} y={r.Top:0.#} w={r.Width:0.#} h={r.Height:0.#} (middle x {r.Left + r.Width / 2:0.#} y {r.Top + r.Height / 2:0.#}) "
            + $"airAbove={r.Top - top:0.#} airBelow={bottom - r.Bottom:0.#} inkShareOfWidth={r.Width / Stage.ActualWidth:0.000}";
    }

    // The bar's translucent Home surface on or off, for the render tour's cost measurement only.
    internal void TourSetBarGlass(bool on)
    {
        BarGlass.BeginAnimation(OpacityProperty, null);
        BarGlass.Opacity = on ? 1 : 0;
    }

    private void TopBar_LayoutChanged(object sender, SizeChangedEventArgs e)
    {
        double width = TopBar.ActualWidth, tabs = NavTabs.ActualWidth;
        if (width <= 0 || tabs <= 0) return;
        var gap = (double)FindResource("Space.5");
        var wordmark = ((FrameworkElement)Content).ActualWidth >= WordmarkMinWindow ? Visibility.Visible : Visibility.Collapsed;
        if (BarWordmark.Visibility != wordmark)
        {
            BarWordmark.Visibility = wordmark;
            return; // the brand's size change runs this again with the new width
        }
        if (_linkLabelsHidden && _linkLabelExtra <= 0)
        {
            // Hidden before their size was ever known: show them again so they can be measured, then decide.
            SetLinkLabels(true);
            return;
        }
        if (!_linkLabelsHidden)
        {
            if (StoreLabel.ActualWidth <= 0) return; // not laid out yet
            _linkLabelExtra = StoreLabel.ActualWidth + StoreLabel.Margin.Left;
        }
        var actionsWithLabels = BarActions.ActualWidth + (_linkLabelsHidden ? _linkLabelExtra : 0);
        var hide = WebsiteButton.ActualWidth + gap + tabs + gap + actionsWithLabels > width;
        if (hide != _linkLabelsHidden && (hide || _linkLabelExtra > 0))
        {
            SetLinkLabels(!hide);
            return; // the size change this causes runs this again and slides the tabs
        }
        double tabsRight = (width + tabs) / 2, tabsLeft = (width - tabs) / 2;
        double overlap = tabsRight + gap - (width - BarActions.ActualWidth);
        double room = tabsLeft - (WebsiteButton.ActualWidth + gap);
        NavTabsShift.X = overlap > 0 ? -Math.Min(overlap, Math.Max(0, room)) : 0;
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

        // The import page is open (the tabs stay live): a running import asks "Stop importing?" first; otherwise the
        // page closes and the tab's page shows.
        if (_importPanelOpen)
        {
            if (_importPanel is { IsApplying: true })
            {
                SetNavigationSelection(null);
                _ = _importPanel.RequestClose();
                return;
            }
            CloseImportPanel();
        }

        // The launch cover is modal within Home: no navigating behind a starting game. The version
        // picker is NOT: a nav click while it is open closes it and navigates (Will 2026-10-20: the
        // click used to be swallowed and the tab stuck highlighted).
        if (page != _currentPage && _currentPage == LauncherPage.Home && _homePage.HasLaunchCover)
        {
            SetNavigationSelection(_currentPage);
            return;
        }
        if (_currentPage == LauncherPage.Home) _homePage.CloseVersionPicker();

        SetNavigationSelection(page);
        if (page == _currentPage && PageHost.Content != null) return;

        var direction = Math.Sign((int)page - (int)_currentPage);
        _currentPage = page;

        object content = page switch
        {
            LauncherPage.Home => _homePage,
            LauncherPage.Mods => ModsView,
            LauncherPage.Profiles => ProfilesView,
            _ => SettingsView
        };

        if (page == LauncherPage.Home)
        {
            _homePage.RefreshAccountState();
            _homePage.RefreshProfile(); // a profile picked on the Profiles page
        }
        else if (page == LauncherPage.Mods)
            ModsView.ShowVersion(_homePage.CurrentVersion);
        else if (page == LauncherPage.Profiles)
            ProfilesView.Show(_homePage.CurrentVersion);
        else
            SettingsView.ShowFromTop(); // always opens on the first section

        PageHost.Content = content;
        // Home shows the art; every other page sits on it dimmed, numeral away.
        Stage.SetPage(page == LauncherPage.Home);
        SetBarSurface(page != LauncherPage.Home);
        AnimatePageIn(direction == 0 ? 1 : direction);
    }

    // The top-bar account pill: the selected account's face and name, "Offline" (with a warning dot) in offline
    // test mode, "Sign in" (a ghost control with the account icon) when nobody can play. Reads the account list
    // only; it never changes an account, a sign-in or a stored token.
    private void RefreshAccountChip()
    {
        var selected = Core.Accounts.AccountStore.GetSelected(Core.Accounts.AccountStore.Load());
        ShowAccountChip(selected?.Gamertag, selected?.Id ?? "", Core.SettingsStore.Load().OfflineTestMode);
    }

    private void ShowAccountChip(string? name, string uuid, bool offlineTestMode)
    {
        var offline = name == null && offlineTestMode;
        var signedOut = name == null && !offline;
        AccountButton.Style = (Style)FindResource(signedOut ? "Button.AccountPill.SignedOut" : "Button.AccountPill");
        AccountName.Text = name ?? (offline ? "Offline" : "Sign in");
        // The player's own head; no account (offline) = the default head. Signed out shows the icon instead.
        AccountFace.Uuid = uuid;
        AccountHeadHost.Visibility = signedOut ? Visibility.Collapsed : Visibility.Visible;
        SignInIcon.Visibility = signedOut ? Visibility.Visible : Visibility.Collapsed;
        OfflineDot.Visibility = offline ? Visibility.Visible : Visibility.Collapsed;
        // The full name, shown only when the pill had to cut it (AccountButton_ToolTipOpening).
        AccountButton.ToolTip = name;
    }

    // A content tooltip, not a hover label: the pill shows the whole name unless it was cut with "...".
    private void AccountButton_ToolTipOpening(object sender, ToolTipEventArgs e)
    {
        if (!AccountNameIsTrimmed()) e.Handled = true;
    }

    private bool AccountNameIsTrimmed()
    {
        if (AccountName.ActualWidth <= 0 || string.IsNullOrEmpty(AccountName.Text)) return false;
        var text = new FormattedText(AccountName.Text, System.Globalization.CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
            new Typeface(AccountName.FontFamily, AccountName.FontStyle, AccountName.FontWeight, AccountName.FontStretch),
            AccountName.FontSize, AccountName.Foreground, VisualTreeHelper.GetDpi(AccountName).PixelsPerDip);
        return text.WidthIncludingTrailingWhitespace > AccountName.ActualWidth + 0.5;
    }

    // The bar's surface: opaque on every page but Home, color.nav at 88 % (BarGlass) over the art on Home. Both fade
    // away with the bar while the launch screen covers Home.
    private bool _barSolid;
    private bool _barHidden;

    private void SetBarSurface(bool solid)
    {
        _barSolid = solid;
        ApplyBarSurface(260);
    }

    private void ApplyBarSurface(int ms)
    {
        BarSurface.BeginAnimation(OpacityProperty, UI.Motion.To(_barSolid && !_barHidden ? 1 : 0, ms));
        BarGlass.BeginAnimation(OpacityProperty, UI.Motion.To(!_barSolid && !_barHidden ? 1 : 0, ms));
    }

    // Under the launch screen the bar fades away and stops taking clicks; it
    // returns with the scene's exit.
    private void SetTopBarHidden(bool hidden)
    {
        TopBar.IsHitTestVisible = !hidden;
        TopBar.BeginAnimation(OpacityProperty, UI.Motion.To(hidden ? 0 : 1, hidden ? 200 : 320, UI.Motion.Out));
        _barHidden = hidden;
        ApplyBarSurface(hidden ? 200 : 320);
    }

    private void StoreButton_Click(object sender, RoutedEventArgs e) =>
        Core.Promos.ExternalLink.Open(Core.Promos.OriginLinks.Store);

    private void ImportButton_Click(object sender, RoutedEventArgs e) => OpenImportPanel();

    private void WebsiteButton_Click(object sender, RoutedEventArgs e) =>
        Core.Promos.ExternalLink.Open(Core.Promos.OriginLinks.Website);


    // null = no tab lit (the Import page is not one of the four).
    private void SetNavigationSelection(LauncherPage? page)
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
    private void OpenImportPanel(IImportBackend? backend = null, ImportPanel.Intent intent = ImportPanel.Intent.Detect)
    {
        if (_importPanelOpen || _signInPanelOpen) return;
        if (ImportBlockedByUpdate()) return; // MainWindow.ImportWiring.cs: an update is downloading / restarting
        if (_accountPanelOpen) SetAccountPanelOpen(false);
        if (_friendsOpen) SetFriendsOpen(false);
        _homePage.ImportCard.Hide();
        MarkImportPromptSeen(); // any way in (Profiles, the Home card, --import, a second launch): the card never offers it again

        var panel = new ImportPanel(backend ?? TourBackendFactory?.Invoke() ?? new LauncherImportBackend());
        // A launch starting or an update running turns Import off: the panel learns the sentence now and on every
        // change (MainWindow.ImportWiring.cs PushImportRefusal); the window's close asks it as an IImportCloseHost.
        panel.SetRefusal(ImportHooks.Refusal());
        panel.CloseRequested += (_, _) => CloseImportPanel();
        // "Play" on the done screen (ImportLogic.DoneAction): close the page, select the one profile the import made when
        // there is one (the import itself never changes the Home selection), then show Home on its version and start it,
        // so the game starts WITH the imported mods. "Open Profiles": several came over, the player picks there.
        panel.PlayRequested += (_, play) =>
        {
            CloseImportPanel();
            if (play.ProfileId is { } id)
                Core.SettingsStore.Update(s => Core.Import.Launcher.ImportSettingsRules.SelectImported(s, id, play.Version));
            NavigateTo(LauncherPage.Home);
            _homePage.RefreshProfile();
            _homePage.PlayVersion(play.Version);
        };
        panel.OpenProfilesRequested += (_, _) =>
        {
            CloseImportPanel();
            NavigateTo(LauncherPage.Profiles);
        };
        panel.Imported += (_, _) =>
        {
            // The import wrote game files only (no launcher setting): Home re-reads its state, and a Profiles page
            // sitting behind the import page rebuilds now (its mod counts may have changed).
            _homePage.RefreshProfile();
            if (_currentPage == LauncherPage.Profiles) ProfilesView.Show(_homePage.CurrentVersion);
        };
        _importPanel = panel;
        ImportPageHost.Content = panel;
        SetImportPanelOpen(true);
        panel.Start(intent);
    }

    private void CloseImportPanel()
    {
        if (!_importPanelOpen) return;
        SetImportPanelOpen(false);
        _importPanel?.Dispose(); // cancels a scan or plan in flight; the drawer's content stays until it has slid out
        _importPanel = null;
    }

    // Shows / hides the Import page: it takes the page slot (the page behind is hidden, still loaded), the art behind
    // dims like on every non-Home page, no tab is lit, and the account and friends buttons are inert (the import goes
    // into the selected account). The page fades in over Motion.Scene and out over Motion.Exit.
    private void SetImportPanelOpen(bool open)
    {
        _importPanelOpen = open;
        OnImportPanelOpenChanged(open); // MainWindow.ImportWiring.cs: the page behind goes inert / comes back, focus restored

        var duration = (Duration)FindResource(open ? "Motion.Scene" : "Motion.Exit");
        if (open)
        {
            PageHost.Visibility = Visibility.Hidden;
            ImportPageHost.Visibility = Visibility.Visible;
            SetNavigationSelection(null);
            Stage.SetPage(false);
            SetBarSurface(true);
        }
        else
        {
            PageHost.Visibility = Visibility.Visible;
            SetNavigationSelection(_currentPage);
            Stage.SetPage(_currentPage == LauncherPage.Home);
            SetBarSurface(_currentPage != LauncherPage.Home);
        }

        var fade = new DoubleAnimation(open ? 1 : 0, duration);
        fade.Completed += (_, _) =>
        {
            if (!_importPanelOpen)
            {
                ImportPageHost.Visibility = Visibility.Collapsed;
                ImportPageHost.Content = null;
            }
        };
        ImportPageHost.BeginAnimation(OpacityProperty, fade, HandoffBehavior.SnapshotAndReplace);

        if (open)
            Dispatcher.BeginInvoke(() => ImportPageHost.MoveFocus(new TraversalRequest(FocusNavigationDirection.First)));
    }

    // After the first window is up: tell the player about an import that was rolled back at start (the
    // recovery itself ran on the thread pool), open the import card for `--import`, or - once, and only
    // when another launcher is found and the player could import - offer it.
    private async Task AfterFirstRenderAsync()
    {
        Core.StartupTrace.Mark("ContentRendered (first frame)");
        Core.StartupTrace.Flush();
        await ShowRecoveryResultAsync(); // MainWindow.ImportWiring.cs: the toast, and Home/Profiles refreshed after a rollback

        if (App.IsRenderMode) return;
        if (App.StartWithImport)
        {
            await Task.Delay(1200); // let the opening animation finish
            OpenImportPanel();
            WarmPagesAtIdle();
            return;
        }
        await Task.Delay(1500); // past the opening animation: no frame competes with building the other pages
        WarmPagesAtIdle();
        await Task.Delay(1500); // never compete with startup (1.5 s already waited above)
        await MaybeShowImportPromptAsync();
    }

    private async Task MaybeShowImportPromptAsync()
    {
        if (_promptChecking || App.IsRenderMode || App.StartWithImport) return;
        _promptChecking = true;
        try
        {
            // Off the UI thread: the settings read, the account check and the quick scan (MainWindow.ImportWiring.cs).
            var prompt = await ComputeImportPromptQuestionAsync();
            if (prompt is not { } found || _importPanelOpen) return;

            // The card sits in Home's banner slot (it shows when Home does), and stays out while Import would be refused
            // (a game starting, an update): never offer a click that lands on a refusal.
            _homePage.ImportCard.SetRefusal(ImportHooks.Refusal()); // and again on every change (PushImportRefusal)
            _homePage.ImportCard.ShowQuestion(found.Question, found.Setups);
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

    internal ImportPanel TourOpenImport(IImportBackend backend, ImportPanel.Intent intent = ImportPanel.Intent.Detect)
    {
        if (_importPanelOpen) CloseImportPanel();
        OpenImportPanel(backend, intent);
        return _importPanel!;
    }

    internal void TourCloseImport() => CloseImportPanel();

    // CI screen render: the backend a real click on Import gets (never the real machine).
    internal Func<IImportBackend>? TourBackendFactory { get; set; }

    internal void TourClickProfilesImport() => ProfilesView.TourClickImport();

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

        // The launch cover owns the keyboard while it is open. (The version picker closes
        // itself in NavigateTo, so Ctrl+1/2/3/4 never leave it invisibly open.)
        if (_currentPage == LauncherPage.Home && _homePage.HasLaunchCover)
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

        if (e.Key == Key.Enter && _currentPage == LauncherPage.Home && !_importPanelOpen
            && !_accountPanelOpen && !_signInPanelOpen && !_friendsOpen
            && Keyboard.FocusedElement is not TextBoxBase
            && Keyboard.FocusedElement is not Selector
            && Keyboard.FocusedElement is not ButtonBase)
        {
            e.Handled = _homePage.TryLaunchFromKeyboard();
        }
    }
}
