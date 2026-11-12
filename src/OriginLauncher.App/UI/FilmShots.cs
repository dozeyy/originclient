using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;
using static OriginLauncher.App.UI.ImportDemo;

namespace OriginLauncher.App.UI;

/// <summary>
/// Dev only: `--render-shots &lt;dir&gt; --film`. The launcher states the walkthrough film follows (origin-client-ad, v3), in
/// the order a first-time player meets them, all at ONE window size (1180x740, the default) and at 3x density
/// (3540x2220 px), so a frame stays sharp when the film's camera fills a 1440p frame with the window and pushes in.
/// The same made-up data as the normal shots run (SocialDemo, ImportDemo, SelectorFakeInstalls); nothing real is read
/// or written. film.json lists every picture with what it shows, plus the Settings scroll offsets.
/// </summary>
internal static class FilmShots
{
    private static readonly Size WindowSize = new(1180, 740);
    private const double Scale = 3.0;

    public static void Run(MainWindow window, string outDir)
    {
        Directory.CreateDirectory(outDir);
        var log = new StringBuilder("{\n  \"window\": [1180, 740],\n  \"scale\": 3,\n  \"shots\": [\n");
        var first = true;
        void Shot(string name, string what, Func<FrameworkElement, System.Windows.Media.Visual?>? also = null)
        {
            window.TourLayoutAt(WindowSize);
            if (CardShots.Find<VersionSelectOverlay>(window) is { IsOpen: true } picker)
                for (var i = 0; i < 3; i++)
                {
                    picker.TourRelayout();
                    window.TourLayoutAt(WindowSize);
                }
            RenderShots.Snap(window, outDir, name, 96 * Scale, WindowSize, laidOut: true, also: also);
            log.Append(first ? "" : ",\n").Append($"    {{\"name\": \"{name}\", \"what\": \"{what}\"}}");
            first = false;
        }
        void Ready()
        {
            window.TourShowAccount("OriginPlayer", SocialDemo.Me, false);
            window.TourShowReady(hover: false);
            RenderShots.Pump(250);
        }

        window.Width = WindowSize.Width;
        window.Height = WindowSize.Height;
        window.TourGoTo(0);
        RenderShots.Pump(600);
        RenderShots.SetTreeDpi(window, Scale);
        RenderShots.Pump(400);
        var startVersion = window.TourHomeVersion;

        // 1-2: Home as a signed-in first-time player sees it, the one bring-over button, then the transfer.
        var button = window.TourBringOver;
        Ready();
        button.Offer(BringOverLunar());
        RenderShots.Pump(400);
        Shot("f01-home-bring-over", "Home, signed in, ready, the bring-over button offering Lunar");
        button.TourStart(SourceKind.Lunar);
        RenderShots.Pump(250);
        Shot("f02-bring-checking", "bring-over pressed: checking what can come over");
        foreach (var p in new[] { 0.12, 0.34, 0.57, 0.8, 0.97 })
        {
            button.Report(new HomeImportProgress(HomeImportStep.Copying, p));
            RenderShots.Pump(200);
            Shot($"f02-bring-copy-{(int)Math.Round(p * 100):00}", $"bring-over copying, {p:P0}");
        }
        // Done with nothing left behind: the engine's own words for a clean transfer.
        button.Finish(HomeImport.Outcome(new BulkImportResult { RunId = "film", State = ImportState.Committed },
            ImportPrompt.ShortName(SourceKind.Lunar)));
        RenderShots.Pump(300);
        Shot("f02-bring-done", "bring-over done");
        button.TourHide();
        RenderShots.Pump(300);
        Ready();
        Shot("f02-home-plain", "Home after the transfer (the button gone for good)");

        // 3: the version picker over fake installs (SelectorShots' fixture): the family grid, 1.21 opened, its dropdown,
        // 1.21.1 picked, then Home on 1.21.1.
        var overlay = CardShots.Find<VersionSelectOverlay>(window);
        var scratch = Path.Combine(Path.GetTempPath(), "origin-film-shots-" + Environment.ProcessId);
        if (overlay != null)
        {
            var fake = SelectorFakeInstalls.Build(scratch, SelectorShots.FakeStates);
            fake.RefreshAllAsync().GetAwaiter().GetResult();
            var real = overlay.Installs;
            overlay.Installs = fake;
            try
            {
                var family = IndexOf("1.21");
                window.TourShowVersions(-1);
                RenderShots.Pump(600);
                Shot("f03-versions-grid", "Choose a version: the family cards");
                window.TourShowVersions(family);
                RenderShots.Pump(600);
                Shot("f03-versions-1.21", "the 1.21 family opened into its panel");
                if (overlay.TourDropdown(true) is { } combo)
                {
                    RenderShots.Pump(500);
                    window.TourLayoutAt(WindowSize);
                    overlay.TourDropdown(true);
                    Shot("f03-versions-1.21-open", "its release dropdown open", root => SelectorShots.PopupInPlace(combo, root));
                    // The list scrolled to its end (the wheel a player turns to reach 1.21.1): 1.21.1 in view.
                    if (combo.Template.FindName("Popup", combo) is System.Windows.Controls.Primitives.Popup { Child: { } child }
                        && CardShots.Find<ScrollViewer>(child) is { } list)
                    {
                        list.ScrollToEnd();
                        RenderShots.Pump(300);
                        Shot("f03-versions-1.21-open-end", "the release list scrolled to its end", root => SelectorShots.PopupInPlace(combo, root));
                    }
                    overlay.TourDropdown(false);
                    RenderShots.Pump(200);
                }
                var pick = VersionCatalog.Groups[family].Versions.ToList().IndexOf("1.21.1");
                overlay.ChooseRelease(pick);
                RenderShots.Pump(400);
                if (overlay.IsOpen) Shot("f03-versions-1.21.1", "1.21.1 chosen in the dropdown");
                overlay.HandleKey(Key.Escape, null);
                RenderShots.Pump(600);
                if (overlay.IsOpen) window.TourHideVersions();
                RenderShots.Pump(300);
            }
            finally
            {
                overlay.Installs = real;
            }
        }
        window.TourSelectVersion("1.21.1");
        RenderShots.Pump(700);
        Ready();
        Shot("f03-home-1.21.1", "Home on 1.21.1, ready");
        window.TourShowReady(hover: true);
        RenderShots.Pump(250);
        Shot("f07-home-play-hover", "Home on 1.21.1, Play hovered");
        Ready();

        // 4: Mods: the list, a jar dragged over, dropped (it lands at the top).
        window.TourGoTo(1);
        RenderShots.Pump(800);
        window.TourShowModsDemo(RenderShots.DemoModsList, 0);
        RenderShots.Pump(600);
        Shot("f04-mods-list", "Mods page, the player's mods for 1.21.1");
        window.TourShowModsDemo(RenderShots.DemoModsList, 1);
        RenderShots.Pump(600);
        Shot("f04-mods-dragover", "a jar dragged over the drop zone");
        window.TourShowModsDemo(RenderShots.DemoModsDroppedList, -1);
        RenderShots.Pump(400);
        Shot("f04-mods-dropped", "dropped: Zoomify lands at the top");

        // 5: Settings, scrolled from the top (Settings Sync) down to Recommended for this PC, in small even steps.
        window.TourGoTo(2);
        RenderShots.Pump(800);
        var settings = CardShots.Find<Pages.SettingsPage>(window);
        var offsets = new List<double>();
        if (settings != null)
        {
            settings.TourScrollToTop();
            RenderShots.Pump(300);
            Shot("f05-settings-00", "Settings at the top: Settings Sync");
            offsets.Add(0);
            var target = settings.TourOffsetOfRecommended();
            const int steps = 24;
            for (var i = 1; i <= steps; i++)
            {
                var y = target * i / steps;
                settings.Scroller.ScrollToVerticalOffset(y);
                RenderShots.Pump(120);
                window.TourLayoutAt(WindowSize);
                Shot($"f05-settings-{i:00}", $"Settings scrolled {y:0.#} DIP");
                offsets.Add(settings.Scroller.VerticalOffset);
            }
            settings.TourScrollToTop();
        }

        // 6: Friends: the drawer over Home on 1.21.1.
        window.TourGoTo(0);
        RenderShots.Pump(600);
        Ready();
        window.TourSetFriendsOpen(true);
        RenderShots.Pump(700);
        Shot("f06-home-friends", "the friends drawer open over Home: requests, the party, friends online");
        window.TourSetFriendsOpen(false);
        RenderShots.Pump(500);

        // 7: Play: the launch screen for 1.21.1, preparing, then counted to full, then starting.
        window.TourShowLaunch("1.21.1");
        RenderShots.Pump(500);
        Shot("f07-launch-preparing", "Play pressed: Preparing Minecraft 1.21.1");
        foreach (var n in new[] { 60, 180, 300, 420, 540, 600 })
        {
            window.TourReportLaunch($"Downloading libraries ({n}/600)");
            RenderShots.Pump(500);
            Shot($"f07-launch-{n * 100 / 600:000}", $"launch counted {n}/600");
        }
        window.TourReportLaunch("Starting Minecraft");
        RenderShots.Pump(500);
        Shot("f07-launch-starting", "Starting Minecraft");
        window.TourHideLaunch();
        RenderShots.Pump(300);

        RenderShots.SetTreeDpi(window, 1.0);
        window.TourSelectVersion(startVersion);
        log.Append("\n  ],\n  \"settingsScroll\": [").Append(string.Join(", ", offsets.Select(o => o.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture))))
            .Append("]\n}\n");
        File.WriteAllText(Path.Combine(outDir, "film.json"), log.ToString());

        // The startup intro (the logo on the bg) at the same size and density.
        try
        {
            var intro = new StartupIntro();
            intro.Mark.Opacity = 1;
            intro.MarkScale.ScaleX = 1;
            intro.MarkScale.ScaleY = 1;
            var host = new Window
            {
                Width = WindowSize.Width, Height = WindowSize.Height, WindowStyle = WindowStyle.None, ResizeMode = ResizeMode.NoResize,
                ShowActivated = false, WindowStartupLocation = WindowStartupLocation.Manual, Left = 0, Top = 0, Content = intro,
            };
            host.Show();
            RenderShots.SetTreeDpi(host, Scale);
            RenderShots.Pump(400);
            RenderShots.Snap(host, outDir, "f00-startup-intro", 96 * Scale, WindowSize);
            host.Close();
        }
        catch (Exception e)
        {
            File.WriteAllText(Path.Combine(outDir, "startup-intro-error.txt"), e.ToString());
        }
        try { Directory.Delete(scratch, true); } catch { /* best effort */ }
    }

    private static int IndexOf(string key)
    {
        for (var i = 0; i < VersionCatalog.Groups.Count; i++)
            if (VersionCatalog.Groups[i].Key == key) return i;
        return 0;
    }
}
