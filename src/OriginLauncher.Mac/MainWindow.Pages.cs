using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Media;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.Mac.UI;
using OriginLauncher.Mac.UI.Pages;

namespace OriginLauncher.Mac;

// The Mods and Profiles tabs (twin of the WPF MainWindow.NavigateTo for those two pages): each page is built on its
// first visit, shown at once (screens open instantly: no per-open load before the first frame; the page is readable on
// its first frame and glides the last 10 px in from the side it was clicked from, AnimatePageIn), and re-reads what may
// have changed elsewhere right after that frame. Home shows the clear art and the numeral; every other page sits on it
// dimmed, under the bar's opaque surface.
public sealed partial class MainWindow
{
    internal enum LauncherPage { Home, Mods, Profiles, Settings }

    private LauncherPage _currentPage = LauncherPage.Home;
    private ModsPage? _modsPage;
    private ProfilesPage? _profilesPage;

    internal ModsPage ModsView => _modsPage ??= new ModsPage();

    internal ProfilesPage ProfilesView => _profilesPage ??= CreateProfilesPage();

    // Home says whether a running game uses a profile, so the Profiles page refuses to delete it
    // (WPF: new ProfilesPage(_homePage.IsProfileInUse)).
    private ProfilesPage CreateProfilesPage()
    {
        var page = new ProfilesPage(Home.IsProfileInUse);
        page.ImportRequested += (_, _) => OpenImportPanel(); // MainWindow.Import.cs
        // Home holds the version the Mods page shows: it follows a profile picked here now, not when Home is next shown.
        page.SelectionChanged += (_, _) => Home.RefreshProfile();
        return page;
    }

    // The version Home shows (the card's number), the one the Mods and Profiles pages follow.
    private string? HomeVersion => Home.CurrentVersion;

    /// <summary>
    /// Opens a page the way its tab does: the WPF MainWindow.NavigateTo rules. The launch cover is modal within Home (no
    /// navigating behind a starting game); the version picker is not (a tab click closes it and navigates). Settings
    /// always opens on its first section. Home shows the art; every other page sits on it dimmed, under the opaque bar.
    /// </summary>
    internal void ShowPage(LauncherPage page)
    {
        // The Import page is open (the tabs stay live): a running import asks "Stop importing?" first; otherwise the
        // page closes and the tab's page shows.
        if (!LeaveImportForTab()) return;
        var onHome = _currentPage == LauncherPage.Home;
        if (page != _currentPage && onHome && Home.HasLaunchCover)
        {
            SelectTab(NavHome);
            return;
        }
        if (onHome) Home.CloseVersionPicker();

        // The new tab is checked BEFORE the others clear: clearing first leaves none checked for a moment, and
        // KeepOneTab then checks Home, so Mods -> Profiles lit both Home and Profiles.
        SelectTab(TabOf(page));
        if (page == _currentPage && PageHost.Content != null) return;
        var direction = Math.Sign((int)page - (int)_currentPage);
        _currentPage = page;

        // Only what the page's FIRST frame must show happens before it appears: a page pointed at another version
        // than the one it last showed rebuilds now (it would otherwise flash the other version's list); Settings
        // opens on its first section (a scroll, no reads).
        var refreshed = false;
        Control content = page switch
        {
            LauncherPage.Mods => ModsView,
            LauncherPage.Profiles => ProfilesView,
            LauncherPage.Settings => Settings,
            _ => Home,
        };
        if (page == LauncherPage.Mods && ModsView.ShownVersion != HomeVersion)
        {
            ModsView.ShowVersion(HomeVersion);
            refreshed = true;
        }
        else if (page == LauncherPage.Profiles && ProfilesView.ShownVersion != HomeVersion)
        {
            ProfilesView.Show(HomeVersion);
            refreshed = true;
        }
        else if (page == LauncherPage.Settings)
            Settings.ShowFromTop();

        PageHost.Content = content;
        Stage.SetPage(home: page == LauncherPage.Home);
        SetBarSurface(solid: page != LauncherPage.Home);
        AnimatePageIn(direction == 0 ? 1 : direction);
        PlaceToasts(); // the strip gap is Home's only

        // The rest of the refresh (settings and file reads, row rebuilds) runs after this frame, and only if no newer
        // tab click came first.
        if (refreshed || page == LauncherPage.Settings) return;
        // Render shots and plan probes (a scratch root) show a version without saving it: re-reading the saved pick
        // on Home would swap the tour's version back.
        if (page == LauncherPage.Home && OriginPaths.IsScratchRoot) return;
        Avalonia.Threading.Dispatcher.UIThread.Post(() =>
        {
            if (_currentPage == page) RefreshShownPage(page);
        }, Avalonia.Threading.DispatcherPriority.Background);
    }

    private ToggleButton TabOf(LauncherPage page) => page switch
    {
        LauncherPage.Mods => NavMods,
        LauncherPage.Profiles => NavProfiles,
        LauncherPage.Settings => NavSettings,
        _ => NavHome,
    };

    // What a page re-reads each time it is shown (changes made elsewhere: a profile picked on Profiles, an account
    // signed in from the drawer, a jar added in Finder).
    private void RefreshShownPage(LauncherPage page)
    {
        switch (page)
        {
            case LauncherPage.Home:
                Home.RefreshAccountState();
                Home.RefreshProfile(); // a profile picked on the Profiles page
                break;
            case LauncherPage.Mods:
                ModsView.ShowVersion(HomeVersion);
                break;
            case LauncherPage.Profiles:
                ProfilesView.Show(HomeVersion);
                break;
        }
    }

    // The bar's surface: opaque on every page but Home, the nav colour at 88 % (BarGlass) over the art on Home; both
    // fade away with the bar under the launch screen (the WPF SetBarSurface / ApplyBarSurface / SetTopBarHidden, the
    // same durations and curve).
    private bool _barSolid;
    private bool _barHidden;

    private void SetBarSurface(bool solid)
    {
        _barSolid = solid;
        ApplyBarSurface(260);
    }

    private void ApplyBarSurface(int ms)
    {
        MotionFx.Run(BarSurface, OpacityProperty, BarSurface.Opacity, _barSolid && !_barHidden ? 1 : 0, ms);
        MotionFx.Run(BarGlass, OpacityProperty, BarGlass.Opacity, !_barSolid && !_barHidden ? 1 : 0, ms);
    }

    private void SetTopBarHidden(bool hidden)
    {
        TopBar.IsHitTestVisible = !hidden;
        MotionFx.Run(TopBar, OpacityProperty, TopBar.Opacity, hidden ? 0 : 1, hidden ? 200 : 320);
        _barHidden = hidden;
        ApplyBarSurface(hidden ? 200 : 320);
    }

    // The page's entrance (the WPF AnimatePageIn, the same numbers): opacity 0.85 -> 1 and 10 px from the side of the
    // tab clicked (direction +1 = a tab to the right) to 0, Motion.Base, quintic ease-out. Short and front-loaded, so the
    // page is readable on its first frame; a click during it restarts it from the new page (MotionFx replaces the run).
    private readonly TranslateTransform _pageShift = new();

    private void AnimatePageIn(int direction)
    {
        var ms = (int)UI.Res.Get<TimeSpan>("Motion.Base").TotalMilliseconds;
        MotionFx.Run(PageHost, OpacityProperty, 0.85, 1, ms);
        MotionFx.Run(PageHost, _pageShift, TranslateTransform.XProperty, direction * 10, 0, ms);
    }

    // ---- --motion-check (MotionCheck.cs): the page entrance, the bar and the art tier right now ----
    internal (double Opacity, double ShiftX) MotionPage => (PageHost.Opacity, _pageShift.X);
    internal (double Bar, double Surface, double Glass, bool TakesClicks) MotionBar =>
        (TopBar.Opacity, BarSurface.Opacity, BarGlass.Opacity, TopBar.IsHitTestVisible);
    internal int MotionArtTier => Stage.ShownTier;
    internal LauncherPage MotionCurrentPage => _currentPage;
    internal void TourSetTopBarHidden(bool hidden) => SetTopBarHidden(hidden);

    // ---- Render shots (AreaBShots): made-up rows and profiles, never the player's files or settings ----

    internal void TourGoTo(LauncherPage page)
    {
        if (!OriginPaths.IsScratchRoot) return;
        ShowPage(page);
        // Let the page's after-the-frame refresh run now, so a demo shown next is not replaced by it.
        Avalonia.Threading.Dispatcher.UIThread.RunJobs();
    }

    /// <summary>Render shots: Home as if <paramref name="profile"/> were selected (its Join card; null: none), no settings
    /// touched.</summary>
    internal void TourShowHomeWithProfile(ModProfile? profile)
    {
        TourGoTo(LauncherPage.Home);
        Home.TourShowProfile(profile);
    }

    internal void TourShowModsDemo(IReadOnlyList<ModEntry> mods, int carrying)
    {
        TourGoTo(LauncherPage.Mods);
        ModsView.TourShowDemo(mods, carrying);
    }

    internal void TourShowProfiles(LauncherSettings demo, Func<ModProfile, int> countMods, bool openForm)
    {
        TourGoTo(LauncherPage.Profiles);
        ProfilesView.ShowDemo(demo, countMods);
        if (openForm) ProfilesView.TourOpenForm();
    }
}
