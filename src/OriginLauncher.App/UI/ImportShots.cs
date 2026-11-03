using System.IO;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.UI.Controls;
using static OriginLauncher.App.UI.ImportDemo;

namespace OriginLauncher.App.UI;

/// <summary>
/// CI screen render (RenderShots): the Import page in every state, driven by a made-up backend. There is
/// no scan, no plan against a real folder and nothing is written: the plans are built in memory here, and
/// "Import" only plays back canned progress.
///
/// Shot numbers: 17, 17b, 17c are the Home card (17c: hidden while Import is refused); 30 and 38 are Detect (found, scanning,
/// empty), 31 Choose what to import, 34/35/36/37 progress, cancel, done and failed, 39-41 single review, blocked and launching,
/// 42/42b the keyboard focus; 43-46 What to bring, a version and Review; 50-59 the smallest window (20-27 are the smallest-window set in RenderShots).
/// </summary>
internal static class ImportShots
{
    // The demo data (setups, plans, canned progress) is UI/ImportDemo.cs, shared with the Mac render.

    /// <summary>
    /// 17, 17b, 50, 51: the one-time first-run card in Home's banner slot, at the normal size and the smallest one. Runs early
    /// (RenderShots, right after 01-home) so Home is in its plain state: the card is never offered while a game runs.
    /// </summary>
    public static void RunHomeCard(MainWindow window, string outDir)
    {
        var (width, height) = (window.Width, window.Height);
        try
        {
            var many = BulkSetups();
            var one = new[] { many[0] };
            window.TourGoTo(0);
            RenderShots.Pump(400);
            window.TourImportPrompt.ShowQuestion(ImportPrompt.Question(many), many);
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "17-home-import-card");
            window.TourImportPrompt.ShowQuestion(ImportPrompt.Question(one), one);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "17b-home-import-card-one-setup");

            // 17c: a game is starting (or an update runs): Import would be refused, so the card stays out of Home.
            window.TourImportPrompt.SetRefusal("Wait for Minecraft to finish starting, then try again.");
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "17c-home-import-card-refused");
            window.TourImportPrompt.SetRefusal(null);
            RenderShots.Pump(200);

            window.Width = 980;
            window.Height = 600;
            RenderShots.Pump(600);
            window.TourImportPrompt.ShowQuestion(ImportPrompt.Question(many), many);
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "50-min-home-import-card");
            window.TourImportPrompt.ShowQuestion(ImportPrompt.Question(one), one);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "51-min-home-import-card-one-setup");
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(outDir, "import-card-error.txt"), ex.ToString());
        }
        finally
        {
            window.TourImportPrompt.Hide();
            window.Width = width;
            window.Height = height;
            RenderShots.Pump(400);
        }
    }

    public static void Run(MainWindow window, string outDir)
    {
        var (windowWidth, windowHeight) = (window.Width, window.Height);
        try
        {
            // 38: nothing found (no launcher installed). Pressed like a click on the Profiles page's Import button (the real click path).
            window.TourBackendFactory = () => new DemoBackend(Demo.Empty);
            window.TourShowProfiles(new Core.Models.LauncherSettings(), _ => 0, openForm: false);
            RenderShots.Pump(500);
            window.TourClickProfilesImport();
            RenderShots.Pump(900);
            RenderShots.Snap(window, outDir, "38-import-page-empty");
            window.TourBackendFactory = null;
            window.TourCloseImport();
            RenderShots.Pump(400);

            // 30a: still looking (the scan is held until the render lets go).
            Open(window, Demo.Scanning, 700);
            RenderShots.Snap(window, outDir, "30a-import-page-scanning");
            DemoBackend.Release();
            window.TourCloseImport();
            RenderShots.Pump(400);

            // 30: step 1, Source: a tile per launcher found (logo, folder, counts; a press picks that one launcher), then
            // the slim "Locate folder" rows and the launchers Origin can't read yet, named.
            var panel = Open(window, Demo.Bulk, 1500);
            RenderShots.Snap(window, outDir, "30-import-page-detect");
            panel.TourScroll(double.MaxValue);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "30b-import-page-detect-bottom");
            panel.TourScroll(0);

            // 63: step 2, how much: Modrinth picked (one setup on a version Origin can't run, one Forge setup).
            panel.TourChooseLauncher(SourceKind.Modrinth);
            RenderShots.Pump(600);
            RenderShots.Snap(window, outDir, "63-import-scope");

            // 61: Choose what to import, the grid over Modrinth only: a tick per thing, All versions first; the Forge
            // setup's columns say why. 61b: one version's tab, its mods ticked.
            panel.TourShowGrid();
            RenderShots.Pump(700);
            RenderShots.Snap(window, outDir, "61-import-grid");
            panel.TourGridTab("1.20.1");
            panel.TourGridTurnOn(SourceKind.Modrinth, GridColumn.Mods);
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "61b-import-grid-version");
            panel.TourGridTab(ImportGridModel.AllTab);
            RenderShots.Pump(300);
            File.WriteAllText(Path.Combine(outDir, "grid-cells.txt"), panel.TourGridDump());

            // 31: Pick single setups (the grid's link): the picked launcher's setups, a row each.
            panel.TourShowPick();
            RenderShots.Pump(700);
            RenderShots.Snap(window, outDir, "31-import-page-pick");
            panel.TourScroll(double.MaxValue);
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "31c-import-page-pick-bottom");
            panel.TourScroll(0);

            // 43: "What to bring" from the Pick page: every version ticked, what to bring once for all.
            panel.TourBring();
            RenderShots.Pump(600);
            RenderShots.Snap(window, outDir, "43-import-versions-all");
            // 43b: one version out (the select-all row is mixed) and screenshots switched off for every version.
            panel.TourSetVersion("1.20.1", false);
            panel.TourBringAll(ImportCategories.Screenshots, false);
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "43b-import-versions-mixed");
            // 43c: the bottom of the list: the versions that can't come over, each with its reason.
            panel.TourScroll(double.MaxValue);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "43c-import-versions-cannot");
            panel.TourScroll(0);
            panel.TourSetVersion("1.20.1", true);
            panel.TourBringAll(ImportCategories.Screenshots, true);
            RenderShots.Pump(200);

            // 45: one version: what it brings, its setups (a Forge one that can bring packs only), whose settings win.
            panel.TourOpenVersion("1.20.1");
            RenderShots.Pump(500);
            RenderShots.Snap(window, outDir, "45-import-version");
            // 32: whose settings.
            panel.TourOpenChooser("1.20.1");
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "32-import-page-pick-settings");
            panel.TourBack();
            RenderShots.Pump(300);
            panel.TourBack();
            RenderShots.Pump(300);
            // 45b: a version Origin can't run: the reason, and its setups (each can be opened to pick a version).
            panel.TourOpenVersion("1.21.5");
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "45b-import-version-unsupported");
            panel.TourBack();
            RenderShots.Pump(300);

            // 46: step 3, Review: what comes over, the profiles it makes, each version's setups, what is left out and why.
            panel.TourOpenReview();
            RenderShots.Pump(1200);
            RenderShots.Snap(window, outDir, "46-import-review");
            panel.TourScroll(520);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "46b-import-review-versions");
            panel.TourScroll(double.MaxValue);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "46c-import-review-left-out");
            panel.TourScroll(0);

            // 33: Details: the options, then what each setup brings.
            panel.TourOpenDetails();
            RenderShots.Pump(500);
            RenderShots.Snap(window, outDir, "33-import-page-details");
            panel.TourScroll(480);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "33b-import-page-details-groups");
            panel.TourBack(); // Review
            RenderShots.Pump(300);
            panel.TourBack(); // What to bring
            RenderShots.Pump(300);
            panel.TourBack(); // Pick single setups
            RenderShots.Pump(400);

            // 39: one setup on its own (the arrow on its row), opened right after the scrolled Details page (its list must
            // start at the top): a supported one, then one Origin cannot take as it is (pick a version).
            panel.TourOpenPickReview("Fabulously Optimized");
            RenderShots.Pump(1200);
            RenderShots.Snap(window, outDir, "39-import-single-review");
            panel.TourBack();
            RenderShots.Pump(400);
            panel.TourOpenPickReview("Snapshot fun");
            RenderShots.Pump(700);
            RenderShots.Snap(window, outDir, "39b-import-single-unsupported");
            window.TourCloseImport();
            RenderShots.Pump(400);

            // 46d: Import everything from Prism (three setups, so three profiles) lands on step 3 with the profiles listed;
            // 36: Import pressed: step 4, the report. Profiles first (one can't start), then came over / skipped / not
            // supported / failed, each line with its reason. 36e: the same with Fix Compatibility merged (the hook set).
            panel = Open(window, Demo.Bulk, 1500);
            panel.TourChooseLauncher(SourceKind.Prism);
            RenderShots.Pump(300);
            panel.TourFastImport(SourceKind.Prism);
            RenderShots.Pump(1200);
            RenderShots.Snap(window, outDir, "46d-import-confirm-profiles");
            panel.TourImportAll();
            RenderShots.Pump(2200);
            RenderShots.Snap(window, outDir, "36-import-page-done");
            panel.TourScroll(420);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "36f-import-page-done-report");
            panel.TourScroll(double.MaxValue);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "36d-import-page-done-bottom");
            panel.TourScroll(0);
            panel.TourOpenDetails(); // the footer holds Done and Play; Details is the link under the notes
            RenderShots.Pump(500);
            RenderShots.Snap(window, outDir, "36b-import-page-done-details");
            window.TourCloseImport();
            RenderShots.Pump(400);
            ImportHooks.FixCompatibility = (_, _) => Task.CompletedTask;
            try
            {
                panel = Open(window, Demo.Bulk, 1500);
                ImportEverything(panel, SourceKind.Prism);
                RenderShots.Pump(2200);
                panel.TourScroll(420);
                RenderShots.Pump(300);
                RenderShots.Snap(window, outDir, "36e-import-done-fix-compatibility");
            }
            finally
            {
                ImportHooks.FixCompatibility = null;
            }
            window.TourCloseImport();
            RenderShots.Pump(400);

            // 36c: Lunar only. The report lists only Lunar's setups (another launcher's were never part of it) and names
            // Lunar's setup on a version Origin can't run.
            panel = Open(window, Demo.Bulk, 1500);
            ImportEverything(panel, SourceKind.Lunar);
            RenderShots.Pump(2200);
            RenderShots.Snap(window, outDir, "36c-import-done-lunar");
            window.TourCloseImport();
            RenderShots.Pump(400);

            // 60: only one other launcher (Prism): step 1 shows its one tile. 60b: Import everything, importing;
            // 60c: done, with Play (the most played version) and Done.
            panel = Open(window, Demo.Bulk, 1500, oneLauncher: true);
            RenderShots.Snap(window, outDir, "60-import-detect-one-launcher");
            window.TourCloseImport();
            RenderShots.Pump(400);
            panel = Open(window, Demo.Progress, 1500, oneLauncher: true);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.Pump(1500);
            RenderShots.Snap(window, outDir, "60b-import-fast-progress");
            DemoBackend.Release();
            RenderShots.Pump(600);
            panel = Open(window, Demo.Bulk, 1500, oneLauncher: true);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.Pump(2200);
            RenderShots.Snap(window, outDir, "60c-import-fast-done");
            window.TourCloseImport();
            RenderShots.Pump(400);

            // 34: importing: the big percentage, the setup being copied and the setup list (Home's Play is off behind the page).
            panel = Open(window, Demo.Progress, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.Pump(1500);
            RenderShots.Snap(window, outDir, "34-import-page-progress");

            // 35: the "Stop importing?" confirmation (opens on Keep going).
            panel.TourAskCancel();
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "35-import-page-cancel-confirm");
            DemoBackend.Release();
            RenderShots.Pump(600);

            // 37: nothing changed (failed and rolled back).
            panel = Open(window, Demo.Failed, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.Pump(1800);
            RenderShots.Snap(window, outDir, "37-import-page-failed");

            // 40: blocked (Minecraft is still running from one of the setups): step 3 says why and Import stays off.
            panel = Open(window, Demo.Blocked, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.Pump(1500);
            RenderShots.Snap(window, outDir, "40-import-page-blocked");

            // 41: a game is starting: step 2's two ways on are off until it opens.
            panel = Open(window, Demo.Bulk, 1500);
            panel.TourChooseLauncher(SourceKind.Prism);
            panel.TourRefusal("Wait for Minecraft to finish starting.");
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "41-import-page-launching");

            // 42, 42b: keyboard focus, as Tab shows it: a version's tick box, then Review's footer primary button.
            window.TourCloseImport();
            RenderShots.Pump(400);
            panel = Open(window, Demo.Bulk, 1500);
            panel.TourChooseLauncher(SourceKind.Prism);
            panel.TourBring();
            RenderShots.Pump(500);
            var focusLog = new System.Text.StringBuilder();
            window.Activate(); // the render window opens without activation; keyboard focus needs an active window
            RenderShots.Pump(200);
            panel.TourFocusFirstRow();
            PressTab(window);
            RenderShots.Pump(300);
            var tickFrame = FocusFrame(window, panel.TourFirstTick(), "tick", focusLog);
            RenderShots.Snap(window, outDir, "42-import-page-focus-tick", adorners: true);
            tickFrame?.Invoke();
            panel.TourOpenReview();
            RenderShots.Pump(1200);
            panel.TourFocusFooterSecondary();
            PressTab(window);
            RenderShots.Pump(300);
            var footerFrame = FocusFrame(window, panel.TourFooterPrimary(), "footer primary", focusLog);
            RenderShots.Snap(window, outDir, "42b-import-page-focus-footer", adorners: true);
            footerFrame?.Invoke();
            File.WriteAllText(Path.Combine(outDir, "focus-checks.txt"), focusLog.ToString());

            // 52-59: the smallest window the launcher allows, for every page (50, 51: the Home card, RunHomeCard).
            window.TourCloseImport();
            window.Width = 980;
            window.Height = 600;
            RenderShots.Pump(600);
            panel = Open(window, Demo.Bulk, 1500);
            RenderShots.Snap(window, outDir, "52-min-import-detect");
            panel.TourChooseLauncher(SourceKind.Modrinth);
            RenderShots.Pump(500);
            RenderShots.Snap(window, outDir, "64-min-import-scope");
            panel.TourShowGrid();
            RenderShots.Pump(600);
            RenderShots.Snap(window, outDir, "62-min-import-grid");
            panel.TourShowPick();
            RenderShots.Pump(600);
            RenderShots.Snap(window, outDir, "58-min-import-pick");
            panel.TourBring();
            RenderShots.Pump(500);
            RenderShots.Snap(window, outDir, "54-min-import-versions");
            panel.TourOpenVersion("1.20.1");
            RenderShots.Pump(500);
            RenderShots.Snap(window, outDir, "55-min-import-version");
            panel.TourBack();
            panel.TourOpenReview();
            RenderShots.Pump(1200);
            RenderShots.Snap(window, outDir, "56-min-import-review");
            panel.TourBack();
            RenderShots.Pump(300);
            panel.TourBack();
            RenderShots.Pump(400);
            panel.TourOpenPickReview("Fabulously Optimized");
            RenderShots.Pump(1200);
            RenderShots.Snap(window, outDir, "53-min-import-single-review");
            window.TourCloseImport();
            RenderShots.Pump(400);
            panel = Open(window, Demo.Progress, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.Pump(1500);
            RenderShots.Snap(window, outDir, "59-min-import-progress");
            DemoBackend.Release();
            RenderShots.Pump(600);
            panel = Open(window, Demo.Bulk, 1500);
            ImportEverything(panel, SourceKind.Prism);
            RenderShots.Pump(2200);
            RenderShots.Snap(window, outDir, "57-min-import-done");
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(outDir, "import-error.txt"), ex.ToString());
        }
        finally
        {
            DemoBackend.Release();
            window.Width = windowWidth;
            window.Height = windowHeight;
            window.TourBackendFactory = null;
            window.TourCloseImport();
            RenderShots.Pump(300);
        }
    }

    private static ImportPanel Open(MainWindow window, Demo demo, int pumpMs, bool oneLauncher = false)
    {
        var panel = window.TourOpenImport(new DemoBackend(demo, oneLauncher));
        RenderShots.Pump(pumpMs);
        return panel;
    }

    // Steps 1-3 the way a player goes: the launcher's tile, Import everything, then Review's Import (once its plan is in).
    private static void ImportEverything(ImportPanel panel, SourceKind kind)
    {
        panel.TourChooseLauncher(kind);
        RenderShots.Pump(300);
        panel.TourFastImport(kind);
        RenderShots.Pump(1000);
        panel.TourImportAll();
    }

    /// <summary>
    /// After the real Tab: when the expected control holds keyboard focus, WPF has drawn its focus frame itself. When the
    /// CI window could not become active (no keyboard focus at all), the control's OWN FocusVisualStyle is drawn on it the
    /// way WPF's focus adorner does, so the frame can still be judged; focus-checks.txt says which happened. Returns the
    /// undo for a drawn frame.
    /// </summary>
    internal static Action? FocusFrame(MainWindow window, System.Windows.FrameworkElement? target, string what, System.Text.StringBuilder log)
    {
        var focused = System.Windows.Input.Keyboard.FocusedElement as System.Windows.FrameworkElement;
        if (target == null)
        {
            log.AppendLine($"{what}: target not found (focused: {focused?.GetType().Name ?? "nothing"})");
            return null;
        }
        var layer = System.Windows.Documents.AdornerLayer.GetAdornerLayer(target);
        if (target.IsKeyboardFocused && layer?.GetAdorners(target) is { Length: > 0 })
        {
            log.AppendLine($"{what}: real keyboard focus after Tab (window active: {window.IsActive}); WPF's own focus frame, from the adorner layer");
            return null;
        }
        // In CI the control does get real keyboard focus from the Tab (seen: "window active: True"), but WPF adds its focus
        // adorner only for a key press from the real keyboard device, so none is there to capture.
        log.AppendLine(target.IsKeyboardFocused
            ? $"{what}: real keyboard focus after Tab, but WPF drew no focus adorner for the simulated key; its FocusVisualStyle is drawn on it instead"
            : $"{what}: no keyboard focus in CI (window active: {window.IsActive}, focused: {focused?.GetType().Name ?? "nothing"}); its FocusVisualStyle is drawn on it instead");
        if (target.FocusVisualStyle == null || layer == null) return null;
        var adorner = new StyleAdorner(target, target.FocusVisualStyle);
        layer.Add(adorner);
        RenderShots.Pump(200);
        return () => layer.Remove(adorner);
    }

    /// <summary>What WPF's own focus adorner is: a Control wearing the element's FocusVisualStyle, laid over the element.</summary>
    private sealed class StyleAdorner : System.Windows.Documents.Adorner
    {
        private readonly System.Windows.Controls.Control _frame;

        public StyleAdorner(System.Windows.UIElement target, System.Windows.Style style) : base(target)
        {
            _frame = new System.Windows.Controls.Control { Style = style, Focusable = false, IsHitTestVisible = false };
            AddVisualChild(_frame);
        }

        protected override int VisualChildrenCount => 1;

        protected override System.Windows.Media.Visual GetVisualChild(int index) => _frame;

        protected override System.Windows.Size MeasureOverride(System.Windows.Size constraint)
        {
            _frame.Measure(AdornedElement.RenderSize);
            return AdornedElement.RenderSize;
        }

        protected override System.Windows.Size ArrangeOverride(System.Windows.Size finalSize)
        {
            _frame.Arrange(new System.Windows.Rect(finalSize));
            return finalSize;
        }
    }

    /// <summary>
    /// Presses Tab for real inside WPF (through the InputManager, as a key press would): the keyboard becomes the most
    /// recent input, so the focus frame shows the way a keyboard player sees it (a programmatic Focus() alone draws none).
    /// </summary>
    private static void PressTab(MainWindow window)
    {
        if (System.Windows.PresentationSource.FromVisual(window) is not { } source) return;
        var keyboard = System.Windows.Input.Keyboard.PrimaryDevice;
        System.Windows.Input.InputManager.Current.ProcessInput(new System.Windows.Input.KeyEventArgs(keyboard, source, 0, System.Windows.Input.Key.Tab)
        {
            RoutedEvent = System.Windows.Input.Keyboard.KeyDownEvent,
        });
    }

}
