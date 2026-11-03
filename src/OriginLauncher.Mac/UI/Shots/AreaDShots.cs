using System.Text;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Input;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Mac.UI.Controls;
using static OriginLauncher.App.UI.ImportDemo;

namespace OriginLauncher.Mac.UI.Shots;

/// <summary>
/// Render shots for Import from other launchers: the Mac twins of the WPF UI/ImportShots.cs (RunHomeCard and Run),
/// under the SAME file names, in the same order, with the same made-up data (UI/ImportDemo.cs, shared) driven through
/// the Import page's own code. No scan, no real folder, nothing written: the plans are built in memory and "Import"
/// plays back canned progress. The setups' folders are the Mac ones (ImportDemo.MacPaths), as a Mac player sees them.
/// Mac differences: 38 opens through the bar's Import button (the Profiles page and its Import button are another
/// area's port); 42/42b move focus the way Tab does (NavigationMethod.Tab, which draws the focus frame) after a real
/// headless Tab press, and focus-checks.txt says which one landed.
/// A step that throws is written to import-error.txt / import-card-error.txt and counted as a failure; the run goes on.
/// </summary>
internal static class AreaDShots
{
    private static string _dir = "";

    internal static void Run(MainWindow window, string dir)
    {
        _dir = dir;
        var macPaths = MacPaths;
        MacPaths = true;
        try
        {
            // The Windows run's size for these (its CI desktop caps the window at 1044 wide), and its version: the card
            // runs right after 01-home there, on the default version; the Import pages later, on 1.21.1.
            RenderShots.Resize(window, 1044, 740);
            window.SelectVersion(VersionCatalog.DefaultVersion);
            HomeCard(window);
            window.SelectVersion("1.21.1");
            Import(window);
        }
        finally
        {
            MacPaths = macPaths;
        }
    }

    private static void Snap(MainWindow window, string name) => RenderShots.Snap(window, name);

    // 17, 17b, 17c, 50, 51: the one-time first-run card over Home, at the normal size and the smallest one.
    private static void HomeCard(MainWindow window)
    {
        var (width, height) = ((int)window.Width, (int)window.Height);
        try
        {
            var many = BulkSetups();
            var one = new[] { many[0] };
            RenderShots.PumpFor(300);
            window.TourImportPrompt.ShowQuestion(ImportPrompt.Question(many), many);
            RenderShots.PumpFor(300);
            Snap(window, "17-home-import-card");
            window.TourImportPrompt.ShowQuestion(ImportPrompt.Question(one), one);
            RenderShots.PumpFor(200);
            Snap(window, "17b-home-import-card-one-setup");

            // 17c: a game is starting (or an update runs): Import would be refused, so the card stays out of Home.
            window.TourImportPrompt.SetRefusal("Wait for Minecraft to finish starting, then try again.");
            RenderShots.PumpFor(200);
            Snap(window, "17c-home-import-card-refused");
            window.TourImportPrompt.SetRefusal(null);

            RenderShots.Resize(window, 980, 600);
            RenderShots.PumpFor(300);
            window.TourImportPrompt.ShowQuestion(ImportPrompt.Question(many), many);
            RenderShots.PumpFor(300);
            Snap(window, "50-min-home-import-card");
            window.TourImportPrompt.ShowQuestion(ImportPrompt.Question(one), one);
            RenderShots.PumpFor(200);
            Snap(window, "51-min-home-import-card-one-setup");
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(_dir, "import-card-error.txt"), ex.ToString());
            RenderShots.Fail($"import card ({ex.GetType().Name}: {ex.Message})");
        }
        finally
        {
            window.TourImportPrompt.Hide();
            RenderShots.Resize(window, width, height);
        }
    }

    private static void Import(MainWindow window)
    {
        var (windowWidth, windowHeight) = ((int)window.Width, (int)window.Height);
        try
        {
            // 38: nothing found (no launcher installed), pressed like a click on the bar's Import button (the real path).
            window.TourBackendFactory = () => new DemoBackend(Demo.Empty);
            window.TourClickImport();
            RenderShots.PumpFor(900);
            Snap(window, "38-import-page-empty");
            window.TourBackendFactory = null;
            window.TourCloseImport();
            RenderShots.PumpFor(300);

            // 30a: still looking (the scan is held until the render lets go).
            Open(window, Demo.Scanning, 700);
            Snap(window, "30a-import-page-scanning");
            DemoBackend.Release();
            window.TourCloseImport();
            RenderShots.PumpFor(300);

            // 30: step 1, Source: a tile per launcher found, then the "Locate folder" rows and the launchers Origin
            // can't read yet, named.
            var panel = Open(window, Demo.Bulk, 1500);
            Snap(window, "30-import-page-detect");
            panel.TourScroll(double.MaxValue);
            RenderShots.PumpFor(300);
            Snap(window, "30b-import-page-detect-bottom");
            panel.TourScroll(0);

            // 63: step 2, how much: Modrinth picked (one setup on a version Origin can't run, one Forge setup).
            panel.TourChooseLauncher(SourceKind.Modrinth);
            RenderShots.PumpFor(600);
            Snap(window, "63-import-scope");

            // 61: Choose what to import, the grid over Modrinth only. 61b: one version's tab, its mods ticked.
            panel.TourShowGrid();
            RenderShots.PumpFor(700);
            Snap(window, "61-import-grid");
            panel.TourGridTab("1.20.1");
            panel.TourGridTurnOn(SourceKind.Modrinth, GridColumn.Mods);
            RenderShots.PumpFor(400);
            Snap(window, "61b-import-grid-version");
            panel.TourGridTab(ImportGridModel.AllTab);
            RenderShots.PumpFor(300);
            File.WriteAllText(Path.Combine(_dir, "grid-cells.txt"), panel.TourGridDump());

            // 31: Pick single setups: the picked launcher's setups, a row each.
            panel.TourShowPick();
            RenderShots.PumpFor(700);
            Snap(window, "31-import-page-pick");
            panel.TourScroll(double.MaxValue);
            RenderShots.PumpFor(400);
            Snap(window, "31c-import-page-pick-bottom");
            panel.TourScroll(0);

            // 43: "What to bring": every version ticked. 43b: one version out, screenshots off. 43c: the bottom.
            panel.TourBring();
            RenderShots.PumpFor(600);
            Snap(window, "43-import-versions-all");
            panel.TourSetVersion("1.20.1", false);
            panel.TourBringAll(ImportCategories.Screenshots, false);
            RenderShots.PumpFor(400);
            Snap(window, "43b-import-versions-mixed");
            panel.TourScroll(double.MaxValue);
            RenderShots.PumpFor(300);
            Snap(window, "43c-import-versions-cannot");
            panel.TourScroll(0);
            panel.TourSetVersion("1.20.1", true);
            panel.TourBringAll(ImportCategories.Screenshots, true);
            RenderShots.PumpFor(200);

            // 45: one version. 32: whose settings. 45b: a version Origin can't run.
            panel.TourOpenVersion("1.20.1");
            RenderShots.PumpFor(500);
            Snap(window, "45-import-version");
            panel.TourOpenChooser("1.20.1");
            RenderShots.PumpFor(400);
            Snap(window, "32-import-page-pick-settings");
            panel.TourBack();
            RenderShots.PumpFor(300);
            panel.TourBack();
            RenderShots.PumpFor(300);
            panel.TourOpenVersion("1.21.5");
            RenderShots.PumpFor(400);
            Snap(window, "45b-import-version-unsupported");
            panel.TourBack();
            RenderShots.PumpFor(300);

            // 46: step 3, Review (46b its versions, 46c what is left out).
            panel.TourOpenReview();
            RenderShots.PumpFor(1200);
            Snap(window, "46-import-review");
            panel.TourScroll(520);
            RenderShots.PumpFor(300);
            Snap(window, "46b-import-review-versions");
            panel.TourScroll(double.MaxValue);
            RenderShots.PumpFor(300);
            Snap(window, "46c-import-review-left-out");
            panel.TourScroll(0);

            // 33: Details: the options, then what each setup brings.
            panel.TourOpenDetails();
            RenderShots.PumpFor(500);
            Snap(window, "33-import-page-details");
            panel.TourScroll(480);
            RenderShots.PumpFor(300);
            Snap(window, "33b-import-page-details-groups");
            panel.TourBack(); // Review
            RenderShots.PumpFor(300);
            panel.TourBack(); // What to bring
            RenderShots.PumpFor(300);
            panel.TourBack(); // Pick single setups
            RenderShots.PumpFor(400);

            // 39: one setup on its own (its list must start at the top after the scrolled Details page); 39b: one
            // Origin cannot take as it is.
            panel.TourOpenPickReview("Fabulously Optimized");
            RenderShots.PumpFor(1200);
            Snap(window, "39-import-single-review");
            panel.TourBack();
            RenderShots.PumpFor(400);
            panel.TourOpenPickReview("Snapshot fun");
            RenderShots.PumpFor(700);
            Snap(window, "39b-import-single-unsupported");
            window.TourCloseImport();
            RenderShots.PumpFor(300);

            // 46d: Import everything from Prism lands on step 3 with the profiles listed; 36: the report (36f, 36d
            // scrolled, 36b Details).
            panel = Open(window, Demo.Bulk, 1500);
            panel.TourChooseLauncher(SourceKind.Prism);
            RenderShots.PumpFor(300);
            panel.TourFastImport(SourceKind.Prism);
            RenderShots.PumpFor(1200);
            Snap(window, "46d-import-confirm-profiles");
            panel.TourImportAll();
            RenderShots.PumpFor(2200);
            Snap(window, "36-import-page-done");
            panel.TourScroll(420);
            RenderShots.PumpFor(300);
            Snap(window, "36f-import-page-done-report");
            panel.TourScroll(double.MaxValue);
            RenderShots.PumpFor(300);
            Snap(window, "36d-import-page-done-bottom");
            panel.TourScroll(0);
            panel.TourOpenDetails();
            RenderShots.PumpFor(500);
            Snap(window, "36b-import-page-done-details");
            window.TourCloseImport();
            RenderShots.PumpFor(300);

            // 36e: the same report with Fix Compatibility on (a stand-in hook: nothing runs).
            ImportHooks.FixCompatibility = (_, _) => Task.CompletedTask;
            try
            {
                panel = Open(window, Demo.Bulk, 1500);
                ImportEverything(panel, SourceKind.Prism);
                RenderShots.PumpFor(2200);
                panel.TourScroll(420);
                RenderShots.PumpFor(300);
                Snap(window, "36e-import-done-fix-compatibility");
            }
            finally
            {
                ImportHooks.FixCompatibility = null;
            }
            window.TourCloseImport();
            RenderShots.PumpFor(300);

            // 36c: Lunar only.
            panel = Open(window, Demo.Bulk, 1500);
            ImportEverything(panel, SourceKind.Lunar);
            RenderShots.PumpFor(2200);
            Snap(window, "36c-import-done-lunar");
            window.TourCloseImport();
            RenderShots.PumpFor(300);

            // 60: only one other launcher; 60b importing; 60c done.
            Open(window, Demo.Bulk, 1500, oneLauncher: true);
            Snap(window, "60-import-detect-one-launcher");
            window.TourCloseImport();
            RenderShots.PumpFor(300);
            panel = Open(window, Demo.Progress, 1500, oneLauncher: true);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.PumpFor(1500);
            panel.TourShowLatestProgress();
            RenderShots.PumpFor(100);
            Snap(window, "60b-import-fast-progress");
            DemoBackend.Release();
            RenderShots.PumpFor(600);
            panel = Open(window, Demo.Bulk, 1500, oneLauncher: true);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.PumpFor(2200);
            Snap(window, "60c-import-fast-done");
            window.TourCloseImport();
            RenderShots.PumpFor(300);

            // 34: importing. 35: "Stop importing?".
            panel = Open(window, Demo.Progress, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.PumpFor(1500);
            panel.TourShowLatestProgress();
            RenderShots.PumpFor(100);
            Snap(window, "34-import-page-progress");
            panel.TourAskCancel();
            RenderShots.PumpFor(400);
            Snap(window, "35-import-page-cancel-confirm");
            DemoBackend.Release();
            RenderShots.PumpFor(600);

            // 37: nothing changed (failed and rolled back).
            panel = Open(window, Demo.Failed, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.PumpFor(1800);
            Snap(window, "37-import-page-failed");

            // 40: blocked (Minecraft still running from one of the setups).
            panel = Open(window, Demo.Blocked, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.PumpFor(1500);
            Snap(window, "40-import-page-blocked");

            // 41: a game is starting: step 2's two ways on are off until it opens.
            panel = Open(window, Demo.Bulk, 1500);
            panel.TourChooseLauncher(SourceKind.Prism);
            panel.TourRefusal("Wait for Minecraft to finish starting.");
            RenderShots.PumpFor(400);
            Snap(window, "41-import-page-launching");

            // 42, 42b: keyboard focus, as Tab shows it: a version's tick box, then Review's footer primary button.
            window.TourCloseImport();
            RenderShots.PumpFor(300);
            panel = Open(window, Demo.Bulk, 1500);
            panel.TourChooseLauncher(SourceKind.Prism);
            panel.TourBring();
            RenderShots.PumpFor(500);
            var focusLog = new StringBuilder();
            panel.TourFocusFirstRow();
            TabTo(window, panel.TourFirstTick(), "tick", focusLog);
            Snap(window, "42-import-page-focus-tick");
            panel.TourOpenReview();
            RenderShots.PumpFor(1200);
            panel.TourFocusFooterSecondary();
            TabTo(window, panel.TourFooterPrimary(), "footer primary", focusLog);
            Snap(window, "42b-import-page-focus-footer");
            File.WriteAllText(Path.Combine(_dir, "import-focus-checks.txt"), focusLog.ToString());

            // 52-59, 62, 64: the smallest window the launcher allows, for every page (50, 51: the Home card).
            window.TourCloseImport();
            RenderShots.Resize(window, 980, 600);
            RenderShots.PumpFor(300);
            panel = Open(window, Demo.Bulk, 1500);
            Snap(window, "52-min-import-detect");
            panel.TourChooseLauncher(SourceKind.Modrinth);
            RenderShots.PumpFor(500);
            Snap(window, "64-min-import-scope");
            panel.TourShowGrid();
            RenderShots.PumpFor(600);
            Snap(window, "62-min-import-grid");
            panel.TourShowPick();
            RenderShots.PumpFor(600);
            Snap(window, "58-min-import-pick");
            panel.TourBring();
            RenderShots.PumpFor(500);
            Snap(window, "54-min-import-versions");
            panel.TourOpenVersion("1.20.1");
            RenderShots.PumpFor(500);
            Snap(window, "55-min-import-version");
            panel.TourBack();
            panel.TourOpenReview();
            RenderShots.PumpFor(1200);
            Snap(window, "56-min-import-review");
            panel.TourBack();
            RenderShots.PumpFor(300);
            panel.TourBack();
            RenderShots.PumpFor(400);
            panel.TourOpenPickReview("Fabulously Optimized");
            RenderShots.PumpFor(1200);
            Snap(window, "53-min-import-single-review");
            window.TourCloseImport();
            RenderShots.PumpFor(300);
            panel = Open(window, Demo.Progress, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.PumpFor(1500);
            panel.TourShowLatestProgress();
            RenderShots.PumpFor(100);
            Snap(window, "59-min-import-progress");
            DemoBackend.Release();
            RenderShots.PumpFor(600);
            panel = Open(window, Demo.Bulk, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.PumpFor(2200);
            Snap(window, "57-min-import-done");
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(_dir, "import-error.txt"), ex.ToString());
            RenderShots.Fail($"import ({ex.GetType().Name}: {ex.Message})");
        }
        finally
        {
            DemoBackend.Release();
            window.TourBackendFactory = null;
            window.TourCloseImport();
            RenderShots.Resize(window, windowWidth, windowHeight);
            RenderShots.PumpFor(300);
        }
    }

    private static ImportPanel Open(MainWindow window, Demo demo, int pumpMs, bool oneLauncher = false)
    {
        var panel = window.TourOpenImport(new DemoBackend(demo, oneLauncher));
        RenderShots.PumpFor(pumpMs);
        return panel;
    }

    // Steps 1-3 the way a player goes: the launcher's tile, Import everything, then Review's Import (once its plan is in).
    private static void ImportEverything(ImportPanel panel, SourceKind kind)
    {
        panel.TourChooseLauncher(kind);
        RenderShots.PumpFor(300);
        panel.TourFastImport(kind);
        RenderShots.PumpFor(1000);
        panel.TourImportAll();
    }

    // A real Tab press through the headless keyboard first (the path a player takes); when it did not land on the
    // expected control, focus it the way Tab does (NavigationMethod.Tab draws its focus frame). The log says which.
    private static void TabTo(MainWindow window, Control? target, string what, StringBuilder log)
    {
        if (target == null)
        {
            log.AppendLine($"{what}: target not found");
            RenderShots.Fail($"focus {what} (not found)");
            return;
        }
        window.KeyPressQwerty(PhysicalKey.Tab, RawInputModifiers.None);
        window.KeyReleaseQwerty(PhysicalKey.Tab, RawInputModifiers.None);
        RenderShots.Pump();
        var focused = window.FocusManager?.GetFocusedElement();
        if (ReferenceEquals(focused, target))
        {
            log.AppendLine($"{what}: real Tab landed on it ({FrameState(target)})");
            return;
        }
        var ok = target.Focus(NavigationMethod.Tab);
        RenderShots.Pump();
        log.AppendLine($"{what}: Tab landed on {focused?.GetType().Name ?? "nothing"}; focused as Tab does: {ok} ({FrameState(target)})");
    }

    // The focus frame as the adorner layer holds it: a frame clipped to its control's bounds would not show at all
    // (the focus templates turn that clip off; Theme/Buttons.axaml).
    private static string FrameState(Control target)
    {
        var layer = Avalonia.Controls.Primitives.AdornerLayer.GetAdornerLayer(target);
        var frame = layer?.Children.FirstOrDefault();
        return frame == null
            ? "no focus frame"
            : $"focus frame {frame.Bounds.Width:0}x{frame.Bounds.Height:0}, clipped={Avalonia.Controls.Primitives.AdornerLayer.GetIsClipEnabled(frame)}";
    }
}
