using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Mac.UI;
using OriginLauncher.Mac.UI.Controls;
using OriginLauncher.Mac.UI.Pages;

namespace OriginLauncher.Mac;

public sealed partial class MainWindow : Window
{
    internal HomePage Home { get; } = new();
    internal SettingsPage Settings { get; } = new();

    // The tabs' slide (the fit rule). A render transform, so moving them never re-runs layout.
    private readonly TranslateTransform NavTabsShift = new();

    public MainWindow()
    {
        InitializeComponent();
        NavTabs.RenderTransform = NavTabsShift;
        PageHost.Content = Home;
        Stage.SetPage(home: true);
        // The art follows Home's pick (the saved version, then every pick in the picker). The pick made while Home was
        // built (before this subscription) is shown here once.
        Stage.Show(Home.CurrentVersion ?? VersionCatalog.DefaultVersion);
        Home.VersionShown += v => { if (v != null) Stage.Show(v); };
        // The launch screen covers the whole window, the bar included (the WPF MainWindow hosts it above TopBar).
        Root.Children.Add(Home.LoadingOverlay);
        // Toasts above everything, bottom-centre; the launcher's own confirm dialog above them (both WPF MainWindow twins).
        Root.Children.Add(Toasts);
        Root.Children.Add(Confirm);
        Home.HomeCard.SizeChanged += (_, _) => PlaceToasts();
        // The launch screen owns the whole window: the bar fades out under it and takes no clicks (WPF SetTopBarHidden).
        Home.LaunchSceneChanged += hidden =>
        {
            TopBar.IsHitTestVisible = !hidden;
            TopBar.Opacity = hidden ? 0 : 1;
        };
        // The opening, over everything; it removes itself when done (none in render shots: motion is off).
        var intro = new StartupIntro();
        Root.Children.Add(intro);
        intro.Play(this);

        TopBar.SizeChanged += (_, _) => FitTopBar();
        WebsiteButton.SizeChanged += (_, _) => FitTopBar();
        NavTabs.SizeChanged += (_, _) => FitTopBar();
        BarActions.SizeChanged += (_, _) => FitTopBar();

        // One page at a time: the bar's tabs behave as a radio group (MainWindow.Pages.cs ShowPage).
        foreach (var tab in new[] { NavHome, NavMods, NavProfiles, NavSettings })
            tab.IsCheckedChanged += (s, _) => { if (s is ToggleButton { IsChecked: false }) KeepOneTab(); };
        NavHome.Click += (_, _) => ShowPage(LauncherPage.Home);
        NavMods.Click += (_, _) => ShowPage(LauncherPage.Mods);
        NavProfiles.Click += (_, _) => ShowPage(LauncherPage.Profiles);
        NavSettings.Click += (_, _) => ShowPage(LauncherPage.Settings);
        InitAccounts();
        HookImport(); // MainWindow.Import.cs: the Import page, its button, the first-run card, closing mid-import
        InitSocial(); // MainWindow.Social.cs: the Friends drawer and the social toasts' actions
    }

    // ---- Toasts and the confirm dialog ----
    internal SocialToastHost Toasts { get; } = new()
    {
        Width = 360, HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Bottom,
    };

    /// <summary>The one confirm dialog (newest prompt wins); every page asks through it.</summary>
    internal ConfirmOverlay Confirm { get; } = new();

    // The toasts' bottom edge sits Space.2 above the top of Home's strip (version card, Play, the status line), so a
    // toast never covers them; on any other page Space.6 above the window's edge (WPF MainWindow.PlaceToasts).
    private void PlaceToasts()
    {
        var onHome = ReferenceEquals(PageHost.Content, Home);
        var bottom = onHome
            ? Home.HomeCard.Bounds.Height + Home.HomeCard.Margin.Bottom + this.ThemeDouble("Space.2")
            : this.ThemeDouble("Space.6");
        // The stack centres in what the Friends drawer leaves free.
        Toasts.Margin = new Thickness(0, 0, _friendsOpen ? FriendsDrawer.Width : 0, bottom);
    }

    internal void TourShowToast(OriginLauncher.App.Core.Social.SocialToast toast) => Toasts.Show(toast);

    internal void TourClearToasts() => Toasts.Clear();

    /// <summary>Render shots: open a page the way its tab does (0 Home, 1 Mods, 2 Profiles, 3 Settings).</summary>
    internal void TourNavigate(int page) => ShowPage((LauncherPage)page);

    // The new tab is checked first: unchecking Home while nothing else is checked would make KeepOneTab check it again,
    // leaving two tabs lit (Home stayed bold on Settings).
    private void SelectTab(ToggleButton tab)
    {
        tab.IsChecked = true;
        foreach (var t in new[] { NavHome, NavMods, NavProfiles, NavSettings }) if (t != tab) t.IsChecked = false;
    }

    private void KeepOneTab()
    {
        if (_importOpen) return; // the Import page lights no tab (MainWindow.Import.cs)
        if (NavHome.IsChecked != true && NavMods.IsChecked != true && NavProfiles.IsChecked != true && NavSettings.IsChecked != true)
            NavHome.IsChecked = true;
    }

    // ---- Window ----
    // Drag anywhere on empty background (the WPF window's drag-anywhere handler). Controls take the press first.
    private void Root_PointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (e.Source == Root && e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) BeginMoveDrag(e);
    }

    private void Minimize_Click(object? sender, EventArgs e) => WindowState = WindowState.Minimized;
    private void Close_Click(object? sender, EventArgs e) => Close();

    /// <summary>Render shots: shows a version on Home (the art, the numeral and the version card) without saving the pick.</summary>
    internal void SelectVersion(string version) => Home.TourSelectVersion(version);

    // ---- Friends dot ----
    internal void SetFriendsBadge(bool on) => FriendsBadge.IsVisible = on;

    // ---- The top bar's fit rule (the WPF MainWindow.TopBar_LayoutChanged, the same numbers) ----
    // The tabs are centred on the window. They slide left (a render transform, so moving them never re-runs layout)
    // only when the actions on the right would come within Space.5 of SETTINGS. When even the fully slid tabs would
    // still touch the actions, Store drops to its bag alone; the decision uses the width the actions would have WITH
    // the label, so it cannot flip back and forth. Below 1000 px the brand shows the logo alone.
    // Mac difference: the bar spans the whole window and the brand and actions carry the insets (traffic lights on the
    // left), so the brand's right edge and the actions' left edge are read from their bounds, margins included.
    private const double WordmarkMinWindow = 1000;
    private bool _storeLabelHidden;
    private double _storeLabelExtra;

    private void FitTopBar()
    {
        double width = TopBar.Bounds.Width, tabs = NavTabs.Bounds.Width;
        if (width <= 0 || tabs <= 0) return;
        var gap = this.ThemeDouble("Space.5");
        // The bar spans the whole Mac window, so its width is the window's (the window's own Bounds lagged a resize).
        var wordmark = width >= WordmarkMinWindow;
        if (BarWordmark.IsVisible != wordmark)
        {
            BarWordmark.IsVisible = wordmark;
            return; // the brand's size change runs this again with the new width
        }
        if (_storeLabelHidden && _storeLabelExtra <= 0)
        {
            StoreLabel.IsVisible = true;
            _storeLabelHidden = false;
            return;
        }
        if (!_storeLabelHidden)
        {
            if (StoreLabel.Bounds.Width <= 0) return; // not laid out yet
            _storeLabelExtra = StoreLabel.Bounds.Width + StoreLabel.Margin.Left;
        }
        var brandRight = WebsiteButton.Bounds.Right;
        var actions = width - BarActions.Bounds.Left;   // the actions plus the bar's right inset
        var actionsWithLabel = actions + (_storeLabelHidden ? _storeLabelExtra : 0);
        var hide = brandRight + gap + tabs + gap + actionsWithLabel > width;
        if (hide != _storeLabelHidden && (hide || _storeLabelExtra > 0))
        {
            _storeLabelHidden = hide;
            StoreLabel.IsVisible = !hide;
            return; // the size change this causes runs this again and slides the tabs
        }
        double tabsRight = (width + tabs) / 2, tabsLeft = (width - tabs) / 2;
        var overlap = tabsRight + gap - (width - actions);
        var room = tabsLeft - (brandRight + gap);
        NavTabsShift.X = overlap > 0 ? -Math.Min(overlap, Math.Max(0, room)) : 0;
    }

    /// <summary>Render shots: the numbers the fit rule just used (twin of the WPF TourTopBarFit).</summary>
    internal string TopBarFit() =>
        $"topbar={TopBar.Bounds.Width:0} brand={WebsiteButton.Bounds.Width:0} tabs={NavTabs.Bounds.Width:0} actions={BarActions.Bounds.Width:0} "
        + $"labelExtra={_storeLabelExtra:0} labelsHidden={_storeLabelHidden} tabShift={NavTabsShift.X:0} wordmark={BarWordmark.IsVisible}";

    /// <summary>Render shots: the Home numeral's ink rectangle against the free band under the bar (twin of TourNumeralFit).</summary>
    internal string NumeralFit()
    {
        if (Stage.Numeral.InkRect is not { } r) return "numeral not laid out";
        double w = Stage.Bounds.Width, h = Stage.Bounds.Height;
        var (top, bottom) = OriginLauncher.App.UI.Controls.NumeralBand.Band(h, this.ThemeDouble("Size.Bar"), Home.StripTop);
        return $"window={w:0}x{h:0} band={top:0.#}..{bottom:0.#} (middle {(top + bottom) / 2:0.#}) "
            + $"ink x={r.Left:0.#} y={r.Top:0.#} w={r.Width:0.#} h={r.Height:0.#} (middle x {r.Left + r.Width / 2:0.#} y {r.Top + r.Height / 2:0.#}) "
            + $"airAbove={r.Top - top:0.#} airBelow={bottom - r.Bottom:0.#} inkShareOfWidth={r.Width / w:0.000}";
    }
}
