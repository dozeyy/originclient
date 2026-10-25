using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Promos;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;

namespace OriginLauncher.App.UI;

/// <summary>
/// CI-only: `OriginLauncher.App.exe --render-shots &lt;folder&gt;` opens the real
/// main window with made-up social data (Core/Social/SocialDemo) and saves
/// pictures of Home (also its Play button in the "Running" state, and the launch
/// progress screen) and the Friends drawer, so launcher UI changes are looked
/// at before they ship (.github/workflows/launcher-shots.yml). Nothing is
/// signed in to and the social service never touches the network.
/// </summary>
public static class RenderShots
{
    // Made-up rows for the Mods page shots (the scratch instance has no mods of its own).
    private static readonly Core.Mods.ModEntry[] DemoMods =
    {
        new("XaerosWorldMap_1.39.2_Fabric_1.21.jar", "Xaero's World Map", true, false),
        new("litematica-fabric-1.21.1-0.19.50.jar", "Litematica", true, false),
        new("appleskin-fabric-mc1.21-3.0.5.jar", "AppleSkin", false, false),
        new("origin-client-1.21.1.jar", "Origin Client", true, true),
        new("sodium-fabric-0.6.13+mc1.21.1.jar", "Sodium", true, true),
        new("iris-fabric-1.8.8+mc1.21.1.jar", "Iris Shaders", true, true),
    };

    private static readonly Core.Mods.ModEntry[] DemoModsDropped =
        new[] { new Core.Mods.ModEntry("zoomify-2.14.2+1.21.1.jar", "Zoomify", true, false) }.Concat(DemoMods).ToArray();

    // In-memory icons for some of the made-up rows (never the network); the rest keep the tinted generated icon,
    // so one shot shows both. Pixel-art sizes (16, 32) and a big one (96) exercise both scaling modes.
    private static IReadOnlyDictionary<string, byte[]> BuildDemoIcons() => new Dictionary<string, byte[]>
    {
        ["XaerosWorldMap_1.39.2_Fabric_1.21.jar"] = DemoPng(32, Color.FromRgb(0x2E, 0x7D, 0x4F), Color.FromRgb(0x9B, 0xE0, 0x6A)),
        ["appleskin-fabric-mc1.21-3.0.5.jar"] = DemoPng(16, Color.FromRgb(0x9E, 0x2B, 0x2B), Color.FromRgb(0xF2, 0x7A, 0x7A)),
        ["sodium-fabric-0.6.13+mc1.21.1.jar"] = DemoPng(96, Color.FromRgb(0x1F, 0x5F, 0x8F), Color.FromRgb(0x8F, 0xD0, 0xF5)),
    };

    // A diagonal-banded square with a bright inner block: obviously "an icon", obviously not a generated glyph.
    private static byte[] DemoPng(int size, Color dark, Color light)
    {
        var px = new byte[size * size * 4];
        for (var y = 0; y < size; y++)
            for (var x = 0; x < size; x++)
            {
                var inner = x >= size / 4 && x < size * 3 / 4 && y >= size / 4 && y < size * 3 / 4;
                var c = inner ? light : ((x + y) / Math.Max(2, size / 8) % 2 == 0 ? dark : Color.FromRgb((byte)(dark.R / 2), (byte)(dark.G / 2), (byte)(dark.B / 2)));
                var i = (y * size + x) * 4;
                px[i] = c.B; px[i + 1] = c.G; px[i + 2] = c.R; px[i + 3] = 255;
            }
        var bmp = BitmapSource.Create(size, size, 96, 96, PixelFormats.Bgra32, null, px, size * 4);
        var enc = new PngBitmapEncoder();
        enc.Frames.Add(BitmapFrame.Create(bmp));
        using var ms = new MemoryStream();
        enc.Save(ms);
        return ms.ToArray();
    }

    public static void Run(string outDir)
    {
        Core.Mods.Icons.ModIconSource.DemoIcons = BuildDemoIcons();
        // `--social`: only the Friends / party / chat surfaces, every state (UI/SocialShots.cs).
        if (Environment.GetCommandLineArgs().Contains("--social"))
        {
            SocialShots.Run(outDir);
            return;
        }
        Directory.CreateDirectory(outDir);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        SocialHub.PlayersOnlineOff = true;
        SocialHub.Service.ShowDemo(SocialDemo.Online(now));

        var window = new MainWindow
        {
            ShowActivated = false,
            WindowStartupLocation = WindowStartupLocation.Manual,
            Left = 0,
            Top = 0,
        };
        window.Show();
        Pump(2500); // first layout, fonts, the players pill's idle fetch

        Snap(window, outDir, "01-home");

        // Play as a signed-in player sees it (the run above has no account), at rest and hovered.
        window.TourShowReady(hover: false);
        Pump(300);
        Snap(window, outDir, "01b-home-ready");
        window.TourShowReady(hover: true);
        Pump(300);
        Snap(window, outDir, "01c-home-play-hover");
        window.TourEndReady();
        Pump(300);

        // The My mods dot on Home: off (a ring), then on (filled in the family colour).
        window.TourShowMyMods(false);
        Pump(200);
        Snap(window, outDir, "01d-home-my-mods-off");
        window.TourShowMyMods(true);
        Pump(200);
        Snap(window, outDir, "01e-home-my-mods-on");

        // The one-time import card in Home's banner slot, at the normal and the smallest window (before anything makes Home "running").
        ImportShots.RunHomeCard(window, outDir);

        window.TourSetFriendsOpen(true);
        Pump(600);
        Snap(window, outDir, "02-friends");

        window.TourFriendsPanel.OpenConversation(SocialDemo.Steve);
        Pump(600);
        Snap(window, outDir, "03-chat");

        window.TourFriendsPanel.OpenConversation(SocialSnapshot.PartyKey);
        Pump(600);
        Snap(window, outDir, "04-party-chat");

        window.TourFriendsPanel.HandleEscape();
        window.TourSetFriendsOpen(false);
        Pump(400);
        foreach (var toast in SocialDemo.Toasts()) window.TourShowToast(toast);
        Pump(800);
        Snap(window, outDir, "05-toasts");
        window.TourClearToasts();

        SocialHub.Service.ShowDemo(new SocialSnapshot
        {
            Status = SocialStatus.NotSignedIn,
            StatusMessage = "Sign in with Microsoft to use friends.",
        });
        window.TourSetFriendsOpen(true);
        Pump(600);
        Snap(window, outDir, "06-signed-out");

        // The Play press: the plain launch progress screen, first with a stage
        // that has no count (the sliding block), then one with real counts (the fill).
        window.TourSetFriendsOpen(false);
        Pump(400);
        window.TourShowLaunch("1.21.1");
        Pump(500);
        Snap(window, outDir, "08-launch-a-preparing");
        window.TourReportLaunch("Downloading libraries (240/600)");
        Pump(900);
        Snap(window, outDir, "08-launch-b-counted");
        window.TourHideLaunch();
        Pump(500);

        // Mod profiles, with made-up profiles (the real settings are never touched):
        // the Profiles page, its new-profile form, then Home with a profile that
        // has a server (profile row + the Join button under Play).
        var demoProfiles = new LauncherSettings
        {
            ModProfiles =
            {
                new ModProfile { Id = "0a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Survival SMP", Version = "1.21.1", Icon = "Pickaxe", ServerName = "Survival SMP", ServerAddress = "play.example.net" },
                new ModProfile { Id = "1a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "PvP practice", Version = "1.8.9", Icon = "Sword", Tint = "Wine", ServerName = "Practice", ServerAddress = "pvp.example.org:25570" },
                new ModProfile { Id = "2a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Singleplayer shaders", Version = "1.20.1", Tint = "Plum", Icon = "Camera" },
                new ModProfile { Id = "3a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Skyblock with a much longer profile name", Version = "1.20.1", Tint = "Moss", Icon = "Map" },
            },
            SelectedProfileId = "0a1b2c3d4e5f60718293a4b5c6d7e8f9",
            SelectedVersion = "1.21.1",
        };
        Func<ModProfile, int> demoCount = p => p.Name.StartsWith("PvP", StringComparison.Ordinal) ? 1 : p.HasServer ? 6 : 3;
        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        Pump(700);
        Snap(window, outDir, "09-profiles");
        SnapScrolledPage(window, outDir, "09b-profiles-scrolled");
        window.TourShowProfiles(demoProfiles, demoCount, openForm: true);
        Pump(500);
        Snap(window, outDir, "10-profiles-form");
        window.TourShowHomeWithProfile(demoProfiles.ModProfiles[0]);
        Pump(700);
        Snap(window, outDir, "11-home-profile");

        // The selected account's game is already open: Play reads "Running"
        // and brings that window forward (one game per account).
        window.TourSetFriendsOpen(false);
        window.TourShowRunning("Steve");
        Pump(600);
        Snap(window, outDir, "07-play-running");

        if (Pages.HomePage.HomeBannerOn) SnapBanners(window, outDir);

        // Import from another launcher: every state of the card, with a made-up backend.
        ImportShots.Run(window, outDir);

        // The other pages and overlays, so a restyle is checked on all of them.
        window.TourHideLaunch();
        window.TourGoTo(0);
        Pump(400);
        window.TourShowVersions(-1);
        Pump(500);
        Snap(window, outDir, "10-versions-grid");
        window.TourShowVersions(1);
        Pump(500);
        Snap(window, outDir, "11-versions-detail");
        window.TourHideVersions();
        Pump(300);
        window.TourSetAccountOpen(true);
        Pump(500);
        Snap(window, outDir, "12-accounts");
        window.TourSetAccountOpen(false);
        Pump(300);
        window.TourGoTo(1);
        Pump(800);
        Snap(window, outDir, "13-mods");
        window.TourShowModsDemo(DemoMods, 0);
        Pump(600);
        Snap(window, outDir, "13b-mods-list");
        SnapScrolledPage(window, outDir, "13e-mods-scrolled");
        window.TourShowModsDemo(DemoMods, 1);
        Pump(600);
        Snap(window, outDir, "13c-mods-dragover");
        // Just dropped: Zoomify lands at the top of the list (the page's own landing animation).
        window.TourShowModsDemo(DemoModsDropped, -1);
        Pump(250);
        Snap(window, outDir, "13d-mods-dropped");
        CompatShots.Run(window, outDir, DemoMods); // Phase 5.1: Replace / Keep old row + Home blocked-launch line
        window.TourGoTo(2);
        Pump(800);
        Snap(window, outDir, "14-settings");
        // The lower sections (JVM arguments, Origin Client, Startup, Developer) are below the fold.
        SnapSettingsScrolled(window, outDir, "14b-settings-middle", "14c-settings-bottom");
        SnapSettingsSync(window, outDir, "14d-settings-sync");
        // The rail bug (2026-10-25): the first click on "Origin Client" lit "Developer". Press the short
        // last sections the way a player does; each shot must show its own item lit and its section in view.
        SnapSettingsRailClick(window, outDir, "SecClient", "14f-settings-click-client");
        SnapSettingsRailClick(window, outDir, "SecStartup", "14g-settings-click-startup");
        // Click an item, then press End by hand: the click stops deciding and the first section in view is lit.
        SnapSettingsKeyEnd(window, outDir, "SecStartup", "14h-settings-key-end");

        // Hover labels: the nav rail, the window's Close, an icon inside a page (UI/TooltipShots.cs).
        TooltipShots.Run(window, outDir, demoProfiles, demoCount);

        // Website and Store with their words and the hover arrow's room: only a wide window has the width for them
        // (the narrower ones drop them to icons). Laid out in-process at 1440 and 1280 wide, with and without the
        // update badge; the top bar's own fit rule runs at that width (MainWindow.TourLayoutAt).
        foreach (var (w, h) in new[] { (1440, 900), (1280, 800), (1180, 740) })
            foreach (var update in new[] { false, true })
            {
                window.TourShowUpdateBadge(update);
                window.TourGoTo(0);
                Pump(400);
                var wide = new Size(w, h);
                window.TourLayoutAt(wide);
                var shot = $"15e-topbar-{w}x{h}{(update ? "-update" : "")}";
                File.AppendAllText(Path.Combine(outDir, "topbar-fit.txt"),
                    $"{shot}: {window.TourTopBarFit()}; gaps {window.TourLinkGaps()}{Environment.NewLine}");
                Snap(window, outDir, shot, size: wide, laidOut: true);
                if (!update)
                {
                    // The hover arrows shown (a render has no mouse): they must sit inside the button's own padding.
                    window.TourShowLinkHints(true);
                    Snap(window, outDir, shot + "-hint", size: wide, laidOut: true);
                    window.TourShowLinkHints(false);
                }
                Pump(300);
            }
        window.TourShowUpdateBadge(false);

        // The account pill, the Store entry and the account drawer in every state (UI/AccountShots.cs).
        AccountShots.Run(window, outDir);

        // The Home numeral in its band (bar bottom to strip top) at the three reference sizes: the shot plus the measured
        // ink against the band (numeral-fit.txt), so centring and air are numbers, not an eyeball.
        foreach (var (w, h) in new[] { (960, 600), (1180, 740), (1920, 1080) })
        {
            window.TourGoTo(0);
            Pump(400);
            var sized = new Size(w, h);
            window.TourLayoutAt(sized);
            File.AppendAllText(Path.Combine(outDir, "numeral-fit.txt"), $"{w}x{h}: {window.TourNumeralFit()}{Environment.NewLine}");
            Snap(window, outDir, $"15f-home-numeral-{w}x{h}", size: sized, laidOut: true);
        }
        MeasureBarCost(window, outDir);

        // The top bar (Website and Store sit in it) at 1180 px, then at the narrowest, with and without an update waiting.
        window.Width = 1180;
        window.Height = 740;
        window.TourGoTo(0);
        Pump(600);
        Snap(window, outDir, "15-topbar-1044"); // the CI desktop caps a real window at 1044 wide; the true 1180 shot is 15e-topbar-1180x740
        window.TourShowUpdateBadge(true);
        Pump(300);
        Snap(window, outDir, "15b-topbar-1044-update");
        window.TourShowUpdateBadge(false);
        window.Width = 960;
        window.Height = 600;
        Pump(600);
        Snap(window, outDir, "15c-topbar-960");
        window.TourShowUpdateBadge(true);
        Pump(300);
        Snap(window, outDir, "15d-topbar-960-update");
        window.TourShowUpdateBadge(false);
        Pump(200);

        // The smallest window the launcher allows (what 125-150 % scaling leaves on
        // a 1080p screen): the same screens again, to prove nothing clips.
        window.Width = 980;
        window.Height = 600;
        window.TourSetAccountOpen(false);
        window.TourGoTo(0);
        Pump(600);
        Snap(window, outDir, "20-min-home");
        window.TourShowVersions(-1);
        Pump(500);
        Snap(window, outDir, "21-min-versions-grid");
        window.TourShowVersions(1);
        Pump(500);
        Snap(window, outDir, "22-min-versions-detail");
        window.TourHideVersions();
        Pump(300);
        window.TourGoTo(1);
        Pump(800);
        Snap(window, outDir, "23-min-mods");
        window.TourShowModsDemo(DemoMods, 0);
        Pump(600);
        Snap(window, outDir, "23b-min-mods-list");
        SnapScrolledPage(window, outDir, "23c-min-mods-scrolled");
        window.TourGoTo(2);
        Pump(800);
        Snap(window, outDir, "24-min-settings");
        SnapSettingsScrolled(window, outDir, "24b-min-settings-middle", "24c-min-settings-bottom");
        SnapSettingsSync(window, outDir, "24d-min-settings-sync");

        // The Profiles page and Home with a profile (profile row + Join under Play), at the smallest
        // window too, the second time with the toasts up: the Play card, Play and Join must all fit
        // under the top bar and above the bottom edge, and the toasts must leave them uncovered.
        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        Pump(700);
        Snap(window, outDir, "25-min-profiles");
        SnapScrolledPage(window, outDir, "25b-min-profiles-scrolled");
        window.TourShowHomeWithProfile(demoProfiles.ModProfiles[0]);
        Pump(700);
        Snap(window, outDir, "26-min-home-profile");
        foreach (var toast in SocialDemo.Toasts()) window.TourShowToast(toast);
        window.TourShowToast(new SocialToast(SocialToastKind.FriendRequest, "Friend request", "OjoTheCat wants to be friends", Uuid: SocialDemo.Ojo));
        Pump(700);
        Snap(window, outDir, "27-min-home-profile-toasts");
        window.TourClearToasts();

        SnapCrashWindows(outDir);
        window.Close();
    }

    /// <summary>
    /// What the Home bar's translucent surface costs to draw: the Home window rendered repeatedly with the surface on and
    /// off (alternating, so drift hits both alike), median and best in bar-cost.txt. A render here is the CPU rasteriser,
    /// so it is an upper bound on a GPU frame; what matters is the difference between the two rows.
    /// </summary>
    private static void MeasureBarCost(MainWindow window, string dir)
    {
        window.TourGoTo(0);
        Pump(600);
        window.UpdateLayout();
        var root = (FrameworkElement)window.Content;
        int w = (int)root.ActualWidth, h = (int)root.ActualHeight;
        var on = new List<double>();
        var off = new List<double>();
        for (var i = 0; i < 43; i++)
        {
            var glass = i % 2 == 0;
            window.TourSetBarGlass(glass);
            var watch = System.Diagnostics.Stopwatch.StartNew();
            var bitmap = new RenderTargetBitmap(w, h, 96, 96, PixelFormats.Pbgra32);
            bitmap.Render(root);
            watch.Stop();
            if (i >= 3) (glass ? on : off).Add(watch.Elapsed.TotalMilliseconds); // the first rounds warm the caches
        }
        window.TourSetBarGlass(true);
        static double Median(List<double> v) { var s = v.OrderBy(x => x).ToList(); return s[s.Count / 2]; }
        File.WriteAllText(Path.Combine(dir, "bar-cost.txt"),
            $"Home {w}x{h}, {on.Count} renders with the bar surface, {off.Count} without (CPU rasteriser, ms per render)" + Environment.NewLine
            + $"with surface:    median {Median(on):0.00}  best {on.Min():0.00}" + Environment.NewLine
            + $"without surface: median {Median(off):0.00}  best {off.Min():0.00}" + Environment.NewLine
            + $"difference of medians: {Median(on) - Median(off):0.00} ms" + Environment.NewLine);
    }

    /// <summary>
    /// The visible page scrolled well past the top bar (the page's longest scroller, to 60 %), so a shot proves content
    /// goes UNDER the bar's surface and never collides with the nav (Will's 24c note). No scroller to move = no shot.
    /// </summary>
    private static void SnapScrolledPage(Window window, string dir, string name)
    {
        ScrollViewer? best = null;
        void Walk(DependencyObject parent)
        {
            for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
            {
                var child = VisualTreeHelper.GetChild(parent, i);
                if (child is ScrollViewer sv && sv.IsVisible && sv.ScrollableHeight > 1
                    && (best == null || sv.ScrollableHeight > best.ScrollableHeight)) best = sv;
                Walk(child);
            }
        }
        Walk(window);
        if (best == null) return;
        best.ScrollToVerticalOffset(best.ScrollableHeight * 0.6);
        Pump(400);
        Snap(window, dir, name);
        best.ScrollToVerticalOffset(0);
        Pump(200);
    }

    /// <summary>Settings scrolled to its middle and its end, so the sections below the first screen are in the shots too.</summary>
    private static void SnapSettingsScrolled(Window window, string dir, string middleName, string bottomName)
    {
        var scroller = FindVisual<Pages.SettingsPage>(window)?.Scroller;
        if (scroller == null) return;
        var max = scroller.ScrollableHeight;
        if (max < 1) return;
        scroller.ScrollToVerticalOffset(max / 2);
        Pump(400);
        Snap(window, dir, middleName);
        scroller.ScrollToVerticalOffset(max);
        Pump(400);
        Snap(window, dir, bottomName);
        scroller.ScrollToVerticalOffset(0);
        Pump(200);
    }

    /// <summary>Settings scrolled to the Settings Sync row, so that row is in the cut-off check at both window sizes (then back to the top).</summary>
    private static void SnapSettingsSync(Window window, string dir, string name)
    {
        var page = FindVisual<Pages.SettingsPage>(window);
        if (page == null) return;
        page.TourScrollToSync();
        Pump(400);
        Snap(window, dir, name);
        page.TourScrollToTop();
        Pump(200);
    }

    /// <summary>Press a rail item, then End on the keyboard, and snap: the item lit must follow the page, not the old click.</summary>
    private static void SnapSettingsKeyEnd(Window window, string dir, string sectionName, string name)
    {
        var page = FindVisual<Pages.SettingsPage>(window);
        if (page == null) return;
        page.TourClickRail(sectionName);
        Pump(400);
        page.TourPressEnd();
        Pump(500);
        Snap(window, dir, name);
        page.ShowFromTop();
        Pump(200);
    }

    /// <summary>Press one Settings rail item and snap the result, then go back to the top.</summary>
    private static void SnapSettingsRailClick(Window window, string dir, string sectionName, string name)
    {
        var page = FindVisual<Pages.SettingsPage>(window);
        if (page == null) return;
        page.TourClickRail(sectionName);
        Pump(500);
        Snap(window, dir, name);
        page.ShowFromTop();
        Pump(200);
    }

    /// <summary>The two crash dialogs with made-up details (the themed layout; the in-code fallback is covered by src/OriginLauncher.UiTests).</summary>
    private static void SnapCrashWindows(string dir)
    {
        try
        {
            var error = new Windows.CrashWindow(
                "Origin Launcher - crash report\nVersion : 1.0.0\nType    : fatal\n\n----- error -----\nSystem.InvalidOperationException: Example failure\n   at Origin.Example.Run()",
                @"C:\Users\Player\AppData\Local\OriginLauncher\logs\crash-example.txt", fatal: true);
            error.Show();
            Pump(400);
            Snap(error, dir, "40-crash-window", size: new Size(error.Width, error.Height));
            error.Close();

            var analysis = new Core.Launch.CrashAnalysis(
                [new Core.Launch.CrashCulprit("examplemod", "examplemod-1.2.3.jar", true), new Core.Launch.CrashCulprit("origin-hud", null, false)],
                "Minecraft stopped while loading.", "java.lang.NullPointerException: example\n\tat examplemod.Init.run(Init.java:42)");
            var report = new Windows.CrashReportWindow(analysis, null, "OriginPlayer - Minecraft 1.21.1");
            report.Show();
            Pump(400);
            Snap(report, dir, "41-crash-report-window");
            report.Close();

            // The most common cause: out of memory, with its one-click fix.
            var oom = Core.Launch.CrashClassifier.Classify(new Core.Launch.CrashAnalysis([], "r", null),
                "java.lang.OutOfMemoryError: Java heap space", 1, 4096, 16384);
            var oomWindow = new Windows.CrashReportWindow(new Core.Launch.CrashAnalysis([], "r", null), null,
                "OriginPlayer - Minecraft 1.21.1", oom, () => "report");
            oomWindow.Show();
            Pump(400);
            Snap(oomWindow, dir, "42-crash-report-memory");
            oomWindow.Close();
        }
        catch (Exception e)
        {
            File.WriteAllText(Path.Combine(dir, "crash-windows-error.txt"), e.ToString());
        }
    }

    /// <summary>
    /// The home banner card by card at 2x — every tip, the feed's own cards,
    /// and a card at the feed's longest allowed title/subtitle — to show that
    /// card text always fits whole. A failure here is written to
    /// banner-error.txt and doesn't cost the other screens.
    /// </summary>
    private static void SnapBanners(Window window, string dir)
    {
        try
        {
            var banner = FindVisual<Controls.PromoBanner>(window)
                         ?? throw new InvalidOperationException("home banner not found");
            var slides = PromoRotation.Playable(PromoFeedLoader.BuiltIn().Feed, discordReady: false).ToList();
            var tips = slides.FindIndex(p => p.Kind == PromoKind.Tips);
            for (var t = 0; t < PromoTips.All.Count && tips >= 0; t++)
            {
                banner.TourShowCard(slides, tips, t);
                Pump(500);
                SnapElement(window, banner, dir, $"07-banner-tip-{PromoTips.All[t].Id}");
            }
            for (var i = 0; i < slides.Count; i++)
            {
                if (slides[i].Kind != PromoKind.Link) continue;
                banner.TourShowCard(slides, i, 0);
                Pump(500);
                SnapElement(window, banner, dir, $"07-banner-{slides[i].Id}");
            }

            var longest = new HousePromo("longest", PromoKind.Link, "ANNOUNCEMENT",
                // Exactly PromoFeedParser.MaxTitleLength / MaxSubtitleLength characters.
                "Origin 2.0 is here with brand new menus, maps and more cosmetics",
                "Everything you asked for in one update: smoother shaders, a faster launcher and new capes to get",
                null, new Uri("https://originclient.org/"));
            banner.TourShowCard(slides.Append(longest).ToList(), slides.Count, 0);
            Pump(500);
            SnapElement(window, banner, dir, "07-banner-longest-allowed");
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(dir, "banner-error.txt"), ex.ToString());
        }
    }

    private static T? FindVisual<T>(DependencyObject parent) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T match) return match;
            if (FindVisual<T>(child) is { } found) return found;
        }
        return null;
    }

    // One element at 2x on the window's background. Drawn through a
    // VisualBrush so its position in the window doesn't offset the picture.
    private static void SnapElement(Window window, FrameworkElement element, string dir, string name)
    {
        window.UpdateLayout();
        var size = new Rect(0, 0, element.ActualWidth, element.ActualHeight);
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.DrawRectangle(window.Background, null, size);
            dc.DrawRectangle(new VisualBrush(element) { Stretch = Stretch.None, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top },
                null, size);
        }
        var bitmap = new RenderTargetBitmap((int)Math.Ceiling(size.Width * 2), (int)Math.Ceiling(size.Height * 2),
            192, 192, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(dir, name + ".png"));
        encoder.Save(file);
    }

    /// <summary>Runs the dispatcher for a while so layout, bindings and animations settle.</summary>
    internal static void Pump(int milliseconds)
    {
        var frame = new DispatcherFrame();
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(milliseconds) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            frame.Continue = false;
        };
        timer.Start();
        Dispatcher.PushFrame(frame);
    }

    /// <summary>
    /// Saves the window's content as a PNG at <paramref name="dpi"/> (96 = 100 %, 120 = 125 %, 144 = 150 %),
    /// then runs <see cref="ClipCheck"/> on what was drawn. For a DPI above 96 the caller first gives the
    /// window's visual tree that DPI (<see cref="SetTreeDpi"/>), so layout rounding and text are laid out
    /// for it exactly as on a scaled monitor; this only rasterizes at the matching pixel density.
    /// </summary>
    /// <param name="adorners">Also draw the window's adorner layer (it sits outside window.Content): WPF's own keyboard
    /// focus frame lives there, so the focus shots (ImportShots 42/42b) pass true.</param>
    internal static void Snap(Window window, string dir, string name, double dpi = 96, Size? size = null, bool adorners = false, bool laidOut = false)
    {
        // laidOut: the caller already laid the content out at `size` (MainWindow.TourLayoutAt); a window layout pass would undo it.
        if (!laidOut) window.UpdateLayout();
        var root = (FrameworkElement)window.Content;
        // WPF caps a window at the desktop's size (SM_CXMAXTRACK: 1044x788 on the 1024x768 CI desktop), so a
        // bigger window is laid out here instead: the window's content measured and arranged at that size, which
        // is exactly the layout a window of that client size gets (it is chromeless: client = window).
        if (size is { } s && (root.ActualWidth + 0.5 < s.Width || root.ActualHeight + 0.5 < s.Height))
        {
            root.Measure(s);
            root.Arrange(new Rect(s));
        }
        var scale = dpi / 96.0;
        var bitmap = new RenderTargetBitmap((int)Math.Round(root.ActualWidth * scale), (int)Math.Round(root.ActualHeight * scale),
            dpi, dpi, PixelFormats.Pbgra32);
        // The window's own background sits behind its content.
        var backed = new DrawingVisual();
        using (var dc = backed.RenderOpen())
        {
            dc.DrawRectangle(window.Background, null, new Rect(0, 0, root.ActualWidth, root.ActualHeight));
        }
        bitmap.Render(backed);
        bitmap.Render(root);
        if (adorners && System.Windows.Documents.AdornerLayer.GetAdornerLayer(root) is { } layer) bitmap.Render(layer);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using (var file = File.Create(Path.Combine(dir, name + ".png")))
            encoder.Save(file);
        ClipCheck(root, dir, name);
        root.InvalidateMeasure(); // hand the content back to the window's own size
    }

    /// <summary>
    /// Gives the whole window the DPI of a monitor scaled to <paramref name="scale"/> (1.25 = 125 %), the
    /// way per-monitor DPI does when the window moves to such a monitor: WPF lays out in device-independent
    /// pixels, rounds every edge to the device pixel grid of that DPI (UseLayoutRounding) and shapes text
    /// for it. Re-measures everything so the new rounding applies. 1.0 puts it back.
    /// </summary>
    internal static void SetTreeDpi(Window window, double scale)
    {
        VisualTreeHelper.SetRootDpi(window, new DpiScale(scale, scale));
        Invalidate(window);
        window.UpdateLayout();
    }

    internal static void Invalidate(DependencyObject d)
    {
        if (d is UIElement e)
        {
            e.InvalidateMeasure();
            e.InvalidateArrange();
            e.InvalidateVisual();
        }
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(d); i++) Invalidate(VisualTreeHelper.GetChild(d, i));
    }

    /// <summary>
    /// Finds everything the picture loses at an edge it shouldn't, in numbers (a cut "..." still looks
    /// like dots in a picture; the numbers can't hide it):
    /// <list type="bullet">
    /// <item>CUT: an element its parent gave LESS room than it needs (WPF arranges it at full size and clips it
    /// to the slot: its layout clip is smaller than its own size), when that clip loses something visible inside
    /// it: an icon (any Shape), the letters of a text, an image, or a filled or outlined box (a button's tile,
    /// a toggle's knob). Empty space past a MinWidth is not a loss. A clip the XAML asks for (ClipToBounds,
    /// a scroll viewport) is not a loss either: it is never smaller than the element it clips. Nor is a picture
    /// set to cover its box (Stretch UniformToFill), which crops by design.</item>
    /// <item>CUT past a ClipToBounds element: something visible that reaches outside an ancestor that clips to its
    /// own bounds (pushed out by a negative margin, an overhang or a static transform). Something mid-animation is
    /// skipped: it is moving, not laid out.</item>
    /// <item>TRIMMED: a one-line text drawn with an ellipsis because it doesn't fit. Long names and status/server
    /// lines may trim (the tooltip has the rest): those are warnings in trim-checks.txt. A button's own label and a
    /// header title (Text.Heading / Text.Title) must never trim: those count as cut.</item>
    /// </list>
    /// Each cut goes to clip-errors.txt, which fails the launcher-shots workflow; clip-checks.txt counts what was checked.
    /// </summary>
    internal static void ClipCheck(FrameworkElement root, string dir, string name)
    {
        var inkCount = 0;
        var hits = new List<string>();
        var trims = new List<string>();
        // The edges above the current element that cut what crosses them, in root space: a slot too small for its
        // element (Slot), or an element that clips its content to its own bounds (ClipToBounds), which catches
        // content pushed past it by a negative margin, an overhang or a static transform.
        var clips = new List<(Rect Rect, FrameworkElement By, bool Slot)>();
        var reported = new HashSet<FrameworkElement>();
        var h1 = Application.Current.TryFindResource("Text.Heading");
        var h2 = Application.Current.TryFindResource("Text.Title");

        // floor: the first entry of `clips` that applies. Inside a scroll viewport, content past an outer edge is
        // just scrolled out of view (the viewport can reach it), so only edges inside the viewport count there.
        void Walk(DependencyObject d, int floor)
        {
            for (var i = 0; i < VisualTreeHelper.GetChildrenCount(d); i++)
            {
                var child = VisualTreeHelper.GetChild(d, i);
                if (child is UIElement { IsVisible: false }) continue;
                var pushed = 0;
                if (child is FrameworkElement fe)
                {
                    var toRoot = fe.TransformToAncestor(root);
                    if (!CoverFill(fe)
                        && System.Windows.Controls.Primitives.LayoutInformation.GetLayoutClip(fe) is { } clip
                        && (clip.Bounds.Width < fe.RenderSize.Width - 0.5 || clip.Bounds.Height < fe.RenderSize.Height - 0.5))
                    {
                        clips.Add((toRoot.TransformBounds(clip.Bounds), fe, true));
                        pushed++;
                    }
                    if (fe.ClipToBounds)
                    {
                        clips.Add((toRoot.TransformBounds(new Rect(fe.RenderSize)), fe, false));
                        pushed++;
                    }
                    if (Ink(fe) is { } ink)
                    {
                        inkCount++;
                        var inkInRoot = toRoot.TransformBounds(ink);
                        foreach (var (rect, by, slot) in clips.Skip(floor))
                        {
                            if ((!slot && ReferenceEquals(by, fe)) || Inside(inkInRoot, rect) || !reported.Add(by)) continue;
                            hits.Add(slot
                                ? $"{name}: CUT {Describe(by)} needs {by.RenderSize.Width:0.#}x{by.RenderSize.Height:0.#} "
                                  + $"but its slot is {rect.Width:0.#}x{rect.Height:0.#}; it cuts {Describe(fe)}"
                                : $"{name}: CUT {Describe(fe)} reaches past {Describe(by)}, which clips to its "
                                  + $"{rect.Width:0.#}x{rect.Height:0.#} bounds (ClipToBounds)");
                        }
                    }
                    if (fe is TextBlock tb && Trimmed(tb) is { } fullWidth)
                    {
                        // A button's own label: its string content, or a TextBlock given as its content.
                        var label = Ancestor<System.Windows.Controls.Primitives.ButtonBase>(tb) is { } button
                                    && (button.Content is string c && c == tb.Text || ReferenceEquals(button.Content, tb));
                        var title = tb.Name == "HeaderTitle" || ReferenceEquals(tb.Style, h1) || ReferenceEquals(tb.Style, h2);
                        var what = $"'{tb.Text}' needs {fullWidth:0.#} px, has {tb.ActualWidth:0.#} ({Describe(tb)})";
                        if (label || title) hits.Add($"{name}: TRIMMED {(label ? "button label" : "header title")} {what}");
                        else trims.Add($"WARN {name}: {what}");
                    }
                }
                Walk(child, child is ScrollContentPresenter ? clips.Count : floor);
                clips.RemoveRange(clips.Count - pushed, pushed);
            }
        }
        Walk(root, 0);
        File.AppendAllText(Path.Combine(dir, "clip-checks.txt"),
            $"{name}: {inkCount} visible pieces checked, {hits.Count} cut off, {trims.Count} allowed trims{Environment.NewLine}");
        if (hits.Count > 0) File.AppendAllLines(Path.Combine(dir, "clip-errors.txt"), hits);
        if (trims.Count > 0) File.AppendAllLines(Path.Combine(dir, "trim-checks.txt"), trims);
    }

    // A picture asked to cover its box and crop the rest (Stretch UniformToFill: the home banner's preview art)
    // is bigger than its slot on purpose.
    private static bool CoverFill(FrameworkElement fe) => fe switch
    {
        Image i => i.Stretch == Stretch.UniformToFill,
        System.Windows.Shapes.Shape s => s.Stretch == Stretch.UniformToFill,
        Viewbox v => v.Stretch == Stretch.UniformToFill,
        _ => false,
    };

    private static bool Inside(Rect ink, Rect slot) =>
        ink.Left >= slot.Left - 0.5 && ink.Top >= slot.Top - 0.5 && ink.Right <= slot.Right + 0.5 && ink.Bottom <= slot.Bottom + 0.5;

    private static T? Ancestor<T>(DependencyObject start) where T : DependencyObject
    {
        for (var d = VisualTreeHelper.GetParent(start); d != null; d = VisualTreeHelper.GetParent(d))
            if (d is T t) return t;
        return null;
    }

    // One line of a TextBlock's text, shaped the way that TextBlock draws it (its own TextFormattingMode:
    // Display in this window, which snaps glyphs to whole pixels and measures differently from Ideal).
    private static FormattedText ShapeText(TextBlock tb) =>
        new(tb.Text, System.Globalization.CultureInfo.CurrentUICulture, tb.FlowDirection,
            new Typeface(tb.FontFamily, tb.FontStyle, tb.FontWeight, tb.FontStretch), tb.FontSize, Brushes.Black,
            null, TextOptions.GetTextFormattingMode(tb), VisualTreeHelper.GetDpi(tb).PixelsPerDip);

    private static bool OneLine(TextBlock tb) =>
        tb.TextWrapping == TextWrapping.NoWrap && tb.Inlines.Count <= 1 && !string.IsNullOrEmpty(tb.Text);

    // The full width of a trimmed one-line text, or null when it isn't trimmed.
    private static double? Trimmed(TextBlock tb)
    {
        if (tb.TextTrimming == TextTrimming.None || !OneLine(tb)) return null;
        var full = ShapeText(tb).WidthIncludingTrailingWhitespace;
        return full > tb.ActualWidth - tb.Padding.Left - tb.Padding.Right + 0.5 ? full : null;
    }

    // What of an element is visible, in its own space, or null when it draws nothing of its own. Text: its
    // letters, not its box (a TextBlock with a MinWidth wider than its slot only loses empty space: "1.21.11"
    // in the 230 px version dropdown has MinWidth 200). A box: only when it is filled or outlined.
    private static Rect? Ink(FrameworkElement fe)
    {
        var all = new Rect(fe.RenderSize);
        // A picture set to cover its box is cropped by design, and a mid-animation transform (the launch bar's
        // sweep, the banner art's slow zoom) is motion, not layout: neither has edges to lose.
        if (CoverFill(fe) || fe.RenderTransform is { HasAnimatedProperties: true }) return null;
        switch (fe)
        {
            case System.Windows.Shapes.Shape or Image:
                return all;
            case TextBlock tb when string.IsNullOrEmpty(tb.Text):
                return null;
            case TextBlock tb when OneLine(tb):
                var text = ShapeText(tb);
                var lineHeight = double.IsNaN(tb.LineHeight) ? text.Height : Math.Max(text.Height, tb.LineHeight);
                var w = Math.Min(all.Width, text.WidthIncludingTrailingWhitespace + tb.Padding.Left + tb.Padding.Right);
                var h = Math.Min(all.Height, lineHeight + tb.Padding.Top + tb.Padding.Bottom);
                var x = tb.TextAlignment switch { TextAlignment.Right => all.Width - w, TextAlignment.Center => (all.Width - w) / 2, _ => 0 };
                return new Rect(x, 0, w, h);
            case TextBlock:
                return all;
            case Border b when Paints(b.Background) || (Paints(b.BorderBrush) && b.BorderThickness != default):
                return all;
            case Panel p when Paints(p.Background):
                return all;
            default:
                return null;
        }
    }

    private static bool Paints(Brush? brush) => brush switch
    {
        null => false,
        SolidColorBrush solid => solid.Color.A > 0 && solid.Opacity > 0,
        _ => brush.Opacity > 0,
    };

    // "Path in Button 'Close friends' (Social.Icon)": enough to find it in the XAML.
    private static string Describe(FrameworkElement fe)
    {
        var what = fe is TextBlock t ? $"TextBlock '{t.Text}'" : fe.GetType().Name + (string.IsNullOrEmpty(fe.Name) ? "" : $" '{fe.Name}'");
        for (var d = VisualTreeHelper.GetParent(fe); d != null; d = VisualTreeHelper.GetParent(d))
        {
            if (d is System.Windows.Controls.Primitives.ButtonBase b)
            {
                var label = System.Windows.Automation.AutomationProperties.GetName(b);
                if (string.IsNullOrEmpty(label)) label = b.Content as string ?? b.Name;
                return $"{what} in {b.GetType().Name} '{label}'";
            }
        }
        return what + (fe.Parent is FrameworkElement p ? $" in {p.GetType().Name} '{p.Name}'" : "");
    }
}
