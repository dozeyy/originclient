using Avalonia.Controls;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.Mac.UI;
using OriginLauncher.Mac.UI.Pages;

namespace OriginLauncher.Mac;

// The Mods and Profiles tabs (twin of the WPF MainWindow.NavigateTo for those two pages): each page is built on its
// first visit, shown at once (screens open instantly: no entrance animation, no per-open load before the first frame),
// and re-reads what may have changed elsewhere right after that frame. Home shows the clear art and the numeral;
// every other page sits on it dimmed, under the bar's opaque surface.
public sealed partial class MainWindow
{
    internal enum LauncherPage { Home, Mods, Profiles, Settings }

    private LauncherPage _currentPage = LauncherPage.Home;
    private ModsPage? _modsPage;
    private ProfilesPage? _profilesPage;

    internal ModsPage ModsView => _modsPage ??= new ModsPage();

    internal ProfilesPage ProfilesView => _profilesPage ??= CreateProfilesPage();

    // Merge point with the Play flow (claude/mac-screens-a): Home says whether a running game uses a profile, so the
    // Profiles page refuses to delete it (WPF: new ProfilesPage(_homePage.IsProfileInUse)). Until Home carries that,
    // no game started from this window is tracked here and nothing is in use.
    internal Func<string, bool> ProfileInUse { get; set; } = _ => false;

    private ProfilesPage CreateProfilesPage() => new(id => ProfileInUse(id));

    // The version Home shows (the card's number). Merge point: Home.CurrentVersion once the picker lands.
    private string? HomeVersion => Home.VersionButtonText.Text is { Length: > 0 } v ? v : null;

    internal void ShowPage(LauncherPage page)
    {
        // The new tab is checked BEFORE the others clear: clearing first leaves none checked for a moment, and
        // KeepOneTab then checks Home, so Mods -> Profiles lit both Home and Profiles.
        var tabs = new[] { (NavHome, LauncherPage.Home), (NavMods, LauncherPage.Mods), (NavProfiles, LauncherPage.Profiles), (NavSettings, LauncherPage.Settings) };
        foreach (var (tab, p) in tabs) if (p == page) tab.IsChecked = true;
        foreach (var (tab, p) in tabs) if (p != page) tab.IsChecked = false;
        if (page == _currentPage && PageHost.Content != null) return;
        _currentPage = page;

        // Only what the page's FIRST frame must show happens before it appears: a page pointed at another version
        // than the one it last showed rebuilds now (it would otherwise flash the other version's list).
        var refreshed = false;
        Control content = page switch
        {
            LauncherPage.Mods => ModsView,
            LauncherPage.Profiles => ProfilesView,
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

        PageHost.Content = content;
        Stage.SetPage(home: page == LauncherPage.Home);
        SetBarSurface(solid: page != LauncherPage.Home);

        // The rest of the refresh (settings and file reads, row rebuilds) runs after this frame, and only if no newer
        // tab click came first.
        if (refreshed || page == LauncherPage.Home) return;
        Avalonia.Threading.Dispatcher.UIThread.Post(() =>
        {
            if (_currentPage != page) return;
            if (page == LauncherPage.Mods) ModsView.ShowVersion(HomeVersion);
            else ProfilesView.Show(HomeVersion);
        }, Avalonia.Threading.DispatcherPriority.Background);
    }

    // The bar's surface: opaque on every page but Home, the nav colour at 88 % (BarGlass) over the art on Home.
    private void SetBarSurface(bool solid)
    {
        MotionFx.Run(BarSurface, OpacityProperty, BarSurface.Opacity, solid ? 1 : 0, 260);
        MotionFx.Run(BarGlass, OpacityProperty, BarGlass.Opacity, solid ? 0 : 1, 260);
    }

    // ---- Render shots (AreaBShots): made-up rows and profiles, never the player's files or settings ----

    internal void TourGoTo(LauncherPage page)
    {
        if (!OriginPaths.IsScratchRoot) return;
        ShowPage(page);
        // Let the page's after-the-frame refresh run now, so a demo shown next is not replaced by it.
        Avalonia.Threading.Dispatcher.UIThread.RunJobs();
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
