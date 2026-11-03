using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Mac.UI.Controls;
using OriginLauncher.Mac.UI.Pages;

namespace OriginLauncher.Mac;

public sealed partial class MainWindow : Window
{
    internal HomePage Home { get; } = new();

    // The tabs' slide (the fit rule). A render transform, so moving them never re-runs layout.
    private readonly TranslateTransform NavTabsShift = new();

    public MainWindow()
    {
        InitializeComponent();
        NavTabs.RenderTransform = NavTabsShift;
        PageHost.Content = Home;
        Stage.SetPage(home: true);
        Stage.Show(VersionCatalog.DefaultVersion);
        Home.SetVersion(VersionCatalog.DefaultVersion);

        TopBar.SizeChanged += (_, _) => FitTopBar();
        WebsiteButton.SizeChanged += (_, _) => FitTopBar();
        NavTabs.SizeChanged += (_, _) => FitTopBar();
        BarActions.SizeChanged += (_, _) => FitTopBar();

        // One page at a time: the bar's tabs behave as a radio group (only Home is built on the Mac so far, so the
        // other tabs fall back to Home until their pages are ported).
        foreach (var tab in new[] { NavHome, NavMods, NavProfiles, NavSettings })
            tab.IsCheckedChanged += (s, _) => { if (s is ToggleButton { IsChecked: false }) KeepOneTab(); };
        NavHome.Click += (_, _) => ShowPage(LauncherPage.Home);
        NavMods.Click += (_, _) => ShowPage(LauncherPage.Mods);
        NavProfiles.Click += (_, _) => ShowPage(LauncherPage.Profiles);
        NavSettings.Click += (_, _) => SelectTab(NavHome);
        InitAccounts();
    }

    private void SelectTab(ToggleButton tab)
    {
        foreach (var t in new[] { NavHome, NavMods, NavProfiles, NavSettings }) t.IsChecked = t == tab;
    }

    private void KeepOneTab()
    {
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

    /// <summary>Shows a version on Home: the art, the numeral and the version card (the picker calls this once it exists).</summary>
    internal void SelectVersion(string version)
    {
        Stage.Show(version);
        Home.SetVersion(version);
    }

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
        var gap = Res<double>("Space.5");
        var wordmark = Bounds.Width >= WordmarkMinWindow;
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
        var (top, bottom) = OriginLauncher.App.UI.Controls.NumeralBand.Band(h, Res<double>("Size.Bar"), Home.StripTop);
        return $"window={w:0}x{h:0} band={top:0.#}..{bottom:0.#} (middle {(top + bottom) / 2:0.#}) "
            + $"ink x={r.Left:0.#} y={r.Top:0.#} w={r.Width:0.#} h={r.Height:0.#} (middle x {r.Left + r.Width / 2:0.#} y {r.Top + r.Height / 2:0.#}) "
            + $"airAbove={r.Top - top:0.#} airBelow={bottom - r.Bottom:0.#} inkShareOfWidth={r.Width / w:0.000}";
    }

    private T Res<T>(string key) => this.TryFindResource(key, out var v) && v is T t ? t : default!;
}
