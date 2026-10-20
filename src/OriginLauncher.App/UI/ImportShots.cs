using System.IO;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.UI.Controls;

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
    // Bulk: the normal scan, plan and a successful apply. Progress: the apply stops part way and waits. Failed: the
    // apply rolls back with a reason. Blocked: the plan says Minecraft is running. Empty: nothing found (no launcher
    // installed). Scanning: the scan waits until the render lets go.
    private enum Demo { Bulk, Progress, Failed, Blocked, Empty, Scanning }

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

            // 30: Detect: a tile per launcher found (logo, folder, counts), then the slim "Locate folder" rows.
            var panel = Open(window, Demo.Bulk, 1500);
            RenderShots.Snap(window, outDir, "30-import-page-detect");
            panel.TourScroll(double.MaxValue);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "30b-import-page-detect-bottom");
            panel.TourScroll(0);
            // 30c: one launcher taken out of the import (its tile dims, the button counts less).
            panel.TourToggleLauncher(SourceKind.Lunar);
            RenderShots.Pump(500);
            RenderShots.Snap(window, outDir, "30c-import-page-detect-unticked");
            panel.TourToggleLauncher(SourceKind.Lunar);
            RenderShots.Pump(300);

            // 31: Choose what to import: a card per launcher, a row per setup.
            panel.TourShowPick();
            RenderShots.Pump(700);
            RenderShots.Snap(window, outDir, "31-import-page-pick");
            panel.TourToggleLauncher(SourceKind.Modrinth);
            RenderShots.Pump(500);
            RenderShots.Snap(window, outDir, "31b-import-page-pick-unticked");
            panel.TourToggleLauncher(SourceKind.Modrinth);
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
            // 32: whose settings (1.20.1 has two setups with about the same playtime).
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

            // 46: Review: what comes over, what needs a look, each version's setups, what is left out and why.
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
            panel.TourBack(); // Choose what to import
            RenderShots.Pump(400);

            // 39: one setup on its own (the arrow on its row): a supported one, then one Origin cannot take as it is (pick a version).
            panel.TourOpenPickReview("Survival 1.21.1");
            RenderShots.Pump(1200);
            RenderShots.Snap(window, outDir, "39-import-single-review");
            panel.TourBack();
            RenderShots.Pump(400);
            panel.TourOpenPickReview("Better MC");
            RenderShots.Pump(700);
            RenderShots.Snap(window, outDir, "39b-import-single-unsupported");
            window.TourCloseImport();
            RenderShots.Pump(400);

            // 44: one launcher only (Lunar: every other tile taken out), then 36c: Lunar imported. The Done page
            // lists only Lunar's setups (another launcher's were never part of it).
            panel = Open(window, Demo.Bulk, 1500);
            foreach (var kind in new[] { SourceKind.OfficialLauncher, SourceKind.Prism, SourceKind.Modrinth, SourceKind.CurseForge })
                panel.TourToggleLauncher(kind);
            RenderShots.Pump(600);
            RenderShots.Snap(window, outDir, "44-import-page-detect-lunar-only");
            panel.TourImportNow();
            RenderShots.Pump(2200);
            RenderShots.Snap(window, outDir, "36c-import-done-lunar");
            window.TourCloseImport();
            RenderShots.Pump(400);

            // 34: importing: the big percentage, the setup being copied and the setup list (Home's Play is off behind the page).
            panel = Open(window, Demo.Progress, 1500);
            panel.TourImportNow();
            RenderShots.Pump(1500);
            RenderShots.Snap(window, outDir, "34-import-page-progress");

            // 35: the "Stop importing?" confirmation (opens on Keep going).
            panel.TourAskCancel();
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "35-import-page-cancel-confirm");
            DemoBackend.Release();
            RenderShots.Pump(600);

            // 36: done (preview of the new profiles), its lower half, then its details.
            panel = Open(window, Demo.Bulk, 1500);
            panel.TourImportNow();
            RenderShots.Pump(2200);
            RenderShots.Snap(window, outDir, "36-import-page-done");
            panel.TourScroll(double.MaxValue);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "36d-import-page-done-bottom");
            panel.TourScroll(0);
            panel.TourSecondary();
            RenderShots.Pump(500);
            RenderShots.Snap(window, outDir, "36b-import-page-done-details");

            // 37: nothing changed (failed and rolled back).
            panel = Open(window, Demo.Failed, 1500);
            panel.TourImportNow();
            RenderShots.Pump(1800);
            RenderShots.Snap(window, outDir, "37-import-page-failed");

            // 40: blocked (Minecraft is still running from one of the setups): the press ends at Review, which says why.
            panel = Open(window, Demo.Blocked, 1500);
            panel.TourImportNow();
            RenderShots.Pump(1500);
            RenderShots.Snap(window, outDir, "40-import-page-blocked");

            // 41: a game is starting: Import is off until it opens.
            panel = Open(window, Demo.Bulk, 1500);
            panel.TourRefusal("Wait for Minecraft to finish starting.");
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "41-import-page-launching");

            // 42, 42b: keyboard focus, as Tab shows it: a version's tick box, then Review's footer primary button.
            window.TourCloseImport();
            RenderShots.Pump(400);
            panel = Open(window, Demo.Bulk, 1500);
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
            panel.TourOpenPickReview("Survival 1.21.1");
            RenderShots.Pump(1200);
            RenderShots.Snap(window, outDir, "53-min-import-single-review");
            window.TourCloseImport();
            RenderShots.Pump(400);
            panel = Open(window, Demo.Progress, 1500);
            panel.TourImportNow();
            RenderShots.Pump(1500);
            RenderShots.Snap(window, outDir, "59-min-import-progress");
            DemoBackend.Release();
            RenderShots.Pump(600);
            panel = Open(window, Demo.Bulk, 1500);
            panel.TourImportNow();
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

    private static ImportPanel Open(MainWindow window, Demo demo, int pumpMs)
    {
        var panel = window.TourOpenImport(new DemoBackend(demo));
        RenderShots.Pump(pumpMs);
        return panel;
    }

    /// <summary>
    /// After the real Tab: when the expected control holds keyboard focus, WPF has drawn its focus frame itself. When the
    /// CI window could not become active (no keyboard focus at all), the control's OWN FocusVisualStyle is drawn on it the
    /// way WPF's focus adorner does, so the frame can still be judged; focus-checks.txt says which happened. Returns the
    /// undo for a drawn frame.
    /// </summary>
    private static Action? FocusFrame(MainWindow window, System.Windows.FrameworkElement? target, string what, System.Text.StringBuilder log)
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

    /// <summary>
    /// A believable Windows folder per launcher, so the tiles' path line reads as it will on a real PC. Made up and never
    /// read: the demo backend answers every question itself.
    /// </summary>
    private static string DemoFolder(SourceKind kind, string name) => kind switch
    {
        SourceKind.Prism => $@"C:\Users\Will\AppData\Roaming\PrismLauncher\instances\{name}\minecraft",
        SourceKind.Modrinth => $@"C:\Users\Will\AppData\Roaming\ModrinthApp\profiles\{name}",
        SourceKind.CurseForge => $@"C:\Users\Will\curseforge\minecraft\Instances\{name}",
        SourceKind.Lunar => $@"C:\Users\Will\.lunarclient\offline\{name}",
        _ => $@"C:\Users\Will\AppData\Roaming\.minecraft\versions\{name}",
    };

    private static DetectedInstance Instance(string name, SourceKind kind, string mc, long playSeconds,
        LoaderKind loader = LoaderKind.Fabric, int lastPlayedDaysAgo = 1, string? notSure = null) => new()
    {
        Kind = kind, Name = name, GameDir = DemoFolder(kind, name), McVersion = mc, Loader = loader,
        PlaytimeSeconds = playSeconds > 0 ? playSeconds : null,
        LastPlayed = DateTimeOffset.UtcNow.AddDays(-lastPlayedDaysAgo),
        // The detector's own verdict, as EvidenceJudge would write it for a setup whose evidence disagrees.
        Confidence = notSure == null ? Confidence.Exact : Confidence.Inferred,
        VersionCertain = notSure == null, UncertainReason = notSure,
    };

    /// <summary>
    /// "Import everything" demo data: 5 launchers (the Minecraft Launcher and Lunar among them), 14 setups on 5 Minecraft
    /// versions. 1.21.1 has a clear settings winner; 1.20.1 has an unclear one and a Forge setup that can only bring its
    /// packs; 1.8.9 is Forge and Lunar; three setups are on 1.21.5, which Origin does not have; one (Vanilla+) is "not
    /// sure" of its version. Feather is installed with nothing in it; Badlion and LabyMod are not installed.
    /// </summary>
    private static List<DetectedInstance> BulkSetups() => new()
    {
        Instance("Survival 1.21.1", SourceKind.Prism, "1.21.1", 41 * 3600, lastPlayedDaysAgo: 2),
        Instance("Skyblock", SourceKind.Prism, "1.20.1", 38 * 3600, lastPlayedDaysAgo: 9),
        Instance("Creative testing", SourceKind.Prism, "1.21.1", 6 * 3600, lastPlayedDaysAgo: 14),
        Instance("Fabulously Optimized", SourceKind.Modrinth, "1.20.1", 30 * 3600, lastPlayedDaysAgo: 4),
        Instance("Create: Above and Beyond", SourceKind.Modrinth, "1.20.1", 3 * 3600, LoaderKind.Forge, 60),
        Instance("Snapshot fun", SourceKind.Modrinth, "1.21.5", 2 * 3600, lastPlayedDaysAgo: 40),
        Instance("1.8.9 PvP", SourceKind.CurseForge, "1.8.9", 22 * 3600, LoaderKind.Forge, 20),
        Instance("Better MC", SourceKind.CurseForge, "1.21.5", 5 * 3600, LoaderKind.Fabric, 70),
        Instance("Vanilla+", SourceKind.CurseForge, "1.21.1", 1 * 3600, LoaderKind.Fabric, 100,
            "The launcher says 1.21.1, but the game last ran 1.21.4."),
        Instance("Latest release", SourceKind.OfficialLauncher, "1.21.1", 4 * 3600, LoaderKind.None, 3),
        Instance("fabric-loader-1.19.2", SourceKind.OfficialLauncher, "1.19.2", 9 * 3600, LoaderKind.Fabric, 200),
        Instance("Lunar 1.21.1", SourceKind.Lunar, "1.21.1", 8 * 3600, LoaderKind.None, 5),
        Instance("Lunar 1.8.9", SourceKind.Lunar, "1.8.9", 50 * 3600, LoaderKind.None, 1),
        Instance("Lunar 1.21.5", SourceKind.Lunar, "1.21.5", 1 * 3600, LoaderKind.None, 30),
    };

    /// <summary>The launchers the demo "has installed" (Feather installed, nothing in it).</summary>
    private static readonly SourceKind[] DemoInstalled =
    {
        SourceKind.OfficialLauncher, SourceKind.Lunar, SourceKind.Feather, SourceKind.Prism, SourceKind.Modrinth, SourceKind.CurseForge,
    };

    private sealed class DemoBackend : IImportBackend
    {
        private static TaskCompletionSource<bool> _hold = new();
        private readonly Demo _demo;

        public DemoBackend(Demo demo)
        {
            _demo = demo;
            if (demo is Demo.Progress or Demo.Scanning) _hold = new TaskCompletionSource<bool>();
        }

        /// <summary>Lets a held apply finish (so the render does not leave a task waiting).</summary>
        public static void Release() => _hold.TrySetResult(true);

        public IReadOnlyList<string> SupportedTargets { get; } = new[] { "1.21.1", "1.20.1", "1.20", "1.19.2", "1.8.9" };

        public string ImportsRoot => Path.Combine("demo", "imports");

        public async Task<ScanResult> ScanAsync(IProgress<ImportProgress>? progress, CancellationToken ct)
        {
            if (_demo == Demo.Scanning)
                using (ct.Register(() => _hold.TrySetResult(true))) await _hold.Task;
            var scan = new ScanResult();
            if (_demo != Demo.Empty) scan.Instances.AddRange(BulkSetups());
            return scan;
        }

        public Task<IReadOnlySet<SourceKind>?> GetInstalledLaunchersAsync(CancellationToken ct) =>
            Task.FromResult<IReadOnlySet<SourceKind>?>(_demo == Demo.Empty ? new HashSet<SourceKind>() : new HashSet<SourceKind>(DemoInstalled));

        /// <summary>Made-up counts (never the disk): the same numbers on every render.</summary>
        public Task<IReadOnlyDictionary<string, SetupContents>> GetContentsAsync(IReadOnlyList<DetectedInstance> setups, CancellationToken ct)
        {
            var map = new Dictionary<string, SetupContents>(StringComparer.Ordinal);
            foreach (var setup in setups)
            {
                var n = setup.Name.Length;
                map[setup.SetupId] = new SetupContents(
                    Mods: setup.Loader == LoaderKind.None ? 0 : 8 + n % 9, Worlds: 1 + n % 5, Screenshots: n * 3 % 40,
                    ResourcePacks: 1 + n % 3, ShaderPacks: n % 2, Servers: true, Settings: true);
            }
            return Task.FromResult<IReadOnlyDictionary<string, SetupContents>>(map);
        }

        public Task<FolderInspection> InspectFolderAsync(string folder, CancellationToken ct) =>
            Task.FromResult(new FolderInspection { Message = FolderInspection.NotAnInstance });

        public Task<ImportPlan> PlanAsync(DetectedInstance source, string? targetVersion, ImportOptions options,
            IProgress<ImportProgress>? progress, CancellationToken ct)
        {
            var target = targetVersion ?? source.McVersion ?? "1.21.1";
            return Task.FromResult(BuildPlan(source, target, options, SetupRole.Full, $"{source.SourceLabel}: {source.Name}",
                "0123456789abcdef0123456789abcdef", _demo == Demo.Blocked));
        }

        /// <summary>
        /// One setup's made-up plan. The role shapes it the way the real engine will: Full takes everything,
        /// ModsAndPacks leaves the settings files alone, PacksOnly brings no mods (they are Forge) and no settings.
        /// </summary>
        private static ImportPlan BuildPlan(DetectedInstance source, string target, ImportOptions options, SetupRole role,
            string profileName, string importId, bool blocked)
        {
            var plan = new ImportPlan
            {
                ImportId = importId, Source = source, TargetVersion = target, Options = options,
                ProfileId = "fedcba9876543210fedcba9876543210", ProfileName = profileName,
            };
            if (blocked)
                plan.Blocked.Add("Close Minecraft first: it is still running from one of your setups.");

            ModDecision Mod(string file, string name, ModOutcome outcome, string? reason = null) =>
                new(file, null, name, null, outcome, reason, outcome is ModOutcome.Copied or ModOutcome.CopiedDisabled ? file : null, "demo");
            plan.Mods.AddRange(new[]
            {
                Mod("xaeros-minimap-24.2.jar", "Xaero's Minimap", ModOutcome.Copied),
                Mod("journeymap-1.21.1.jar", "JourneyMap", ModOutcome.Copied),
                Mod("waystones-fabric.jar", "Waystones", ModOutcome.Copied),
                Mod("jade-15.jar", "Jade", ModOutcome.Copied),
                Mod("appleskin-3.0.jar", "AppleSkin", ModOutcome.Copied),
                Mod("create-fabric.jar", "Create", ModOutcome.CopiedDisabled, "needs Fabric Kotlin"),
                Mod("sodium-fabric-0.6.13.jar", "Sodium", ModOutcome.BuiltIn, "Origin includes Sodium 0.6.13"),
                Mod("lithium-fabric-0.14.jar", "Lithium", ModOutcome.BuiltIn, "Origin includes Lithium 0.14.3"),
                Mod("iris-1.8.8.jar", "Iris", ModOutcome.BuiltIn, "Origin includes Iris 1.8.8"),
                Mod("fabric-api-0.116.jar", "Fabric API", ModOutcome.BuiltIn, "Origin includes Fabric API"),
                Mod("oldmap-1.20.1.jar", "Old Map Mod", ModOutcome.WrongMinecraft, "built for Minecraft 1.20.1, this is " + target),
            });
            if (role == SetupRole.PacksOnly)
            {
                var forge = plan.Mods.Select(m => m with
                {
                    Outcome = ModOutcome.WrongLoader, DestFileName = null, Reason = ForgeNote,
                }).ToList();
                plan.Mods.Clear();
                plan.Mods.AddRange(forge);
            }
            foreach (var m in plan.Mods)
            {
                var label = m.DisplayName ?? m.FileName;
                if (m.Outcome == ModOutcome.Copied)
                    plan.Lines.Add(new SummaryLine(SummarySection.Imported, PlanItemKind.Mod, label, null, 600_000));
                else if (m.Outcome == ModOutcome.CopiedDisabled)
                    plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Mod, label, "Comes over turned off - " + m.Reason, 4_200_000));
                else
                    plan.Lines.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.Mod, $"{label} ({m.FileName})", m.Reason));
            }
            foreach (var (name, kind) in new[]
                     {
                         ("options.txt", PlanItemKind.Setting), ("servers.dat", PlanItemKind.Setting), ("iris.properties", PlanItemKind.Setting),
                         ("sodium-options.json", PlanItemKind.Setting), ("Faithful 64x.zip", PlanItemKind.ResourcePack),
                         ("Complementary Reimagined.zip", PlanItemKind.ShaderPack),
                     })
            {
                if (role != SetupRole.Full && kind == PlanItemKind.Setting) continue;
                plan.Ops.Add(new PlannedOp { Kind = OpKind.CopyFile, Item = kind, Label = name, Dest = Path.Combine("demo", name), Bytes = 40_000 });
                plan.Lines.Add(new SummaryLine(SummarySection.Imported, kind, name));
            }
            if (role == SetupRole.Full)
            {
                plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.ResourcePack, "Old Textures",
                    "Pack file missing - skipped"));
                plan.Lines.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.Setting, "authlib-injector.cfg",
                    "Skipped for safety: it looks like a login or account file."));
                plan.SkippedForSafety.Add("authlib-injector.cfg");
            }
            plan.GameFiles = new GameFilePlanStats(612, 380_000_000, 14, 96_000_000, 0, false, false);
            for (var i = 0; i < 3; i++)
                plan.Lines.Add(new SummaryLine(SummarySection.ReusedGameFiles, PlanItemKind.GameFile, $"libraries/demo/{i}.jar", null, 1_000_000));
            plan.Caches.Add(new CachePlanItem(CacheKind.DistantHorizons, CacheScope.SinglePlayer, "New World", "overworld", true, null, 220_000_000));
            plan.Lines.Add(new SummaryLine(SummarySection.ReusedCaches, PlanItemKind.Cache, "Distant Horizons: New World (overworld)", null, 220_000_000));
            plan.Created = 3; plan.Replaced = 1; plan.Kept = 0; plan.Unchanged = 0;
            return plan;
        }

        // ---- "import everything": built from BulkSetups() with the real VersionPicker, so the grouping, the
        // unclear-winner choice and the roles behave the way the engine will. Nothing is read or written. ----

        private const string ForgeNote = BulkRules.ForgeNote;

        private static readonly string[] DemoTargets = { "1.21.1", "1.20.1", "1.20", "1.19.2", "1.8.9" };

        public Task<BulkImportPlan> PlanBulkAsync(BulkSelection selection, ImportOptions options,
            IProgress<BulkPlanProgress>? progress, CancellationToken ct)
        {
            var plan = new BulkImportPlan { RunId = "bulk0123456789abcdef0123456789ab" };
            if (_demo == Demo.Blocked) plan.Blocked.Add("Close Minecraft first: it is still running from one of your setups.");
            var all = BulkSetups();
            var chosen = all.Where(selection.Includes).ToList();
            var byVersion = new List<(string Version, List<DetectedInstance> Setups)>();
            var done = 0;
            // Like the engine: every setup found appears once; the ones the player took out are listed as left out.
            foreach (var setup in all.Where(selection.IsUnticked))
                plan.Skipped.Add(new SkippedSetup(setup, "You left this setup out.", SkipKind.Unticked));
            // Another launcher's setups (an import of one launcher): listed by the engine, never shown by the drawer.
            foreach (var setup in all.Where(x => !selection.Includes(x) && !selection.IsUnticked(x)))
                plan.Skipped.Add(new SkippedSetup(setup, "Another launcher was picked for this import.", SkipKind.NotSelected));
            foreach (var setup in chosen)
            {
                ct.ThrowIfCancellationRequested();
                var version = setup.McVersion ?? "";
                if (!DemoTargets.Contains(version))
                {
                    var skipped = new SkippedSetup(setup, BulkRules.UnsupportedReason(version), SkipKind.Unsupported);
                    plan.Skipped.Add(skipped);
                    progress?.Report(new BulkPlanProgress(++done, chosen.Count, setup.Name, Skipped: skipped));
                    continue;
                }
                var index = byVersion.FindIndex(b => b.Version == version);
                if (index < 0) { byVersion.Add((version, new List<DetectedInstance>())); index = byVersion.Count - 1; }
                byVersion[index].Setups.Add(setup);
            }
            foreach (var (version, setups) in byVersion)
            {
                var group = BuildGroup(plan.RunId, version, setups, options, null);
                plan.Groups.Add(group);
                foreach (var planned in group.Setups)
                    progress?.Report(new BulkPlanProgress(++done, chosen.Count, planned.Setup.Name, Planned: planned));
            }
            Total(plan);
            return Task.FromResult(plan);
        }

        public Task<BulkImportPlan> ReplanGroupAsync(BulkImportPlan plan, string version, DetectedInstance newWinner, CancellationToken ct)
        {
            var next = new BulkImportPlan { RunId = plan.RunId };
            foreach (var group in plan.Groups)
            {
                if (group.Version != version || group.Setups.All(s => s.Setup.BulkId() != newWinner.BulkId())) { next.Groups.Add(group); continue; }
                var options = group.Setups.Count > 0 ? group.Setups[0].Plan.Options : new ImportOptions();
                next.Groups.Add(BuildGroup(plan.RunId, version, group.Setups.Select(s => s.Setup).ToList(), options, newWinner));
            }
            next.Skipped.AddRange(plan.Skipped);
            next.Blocked.AddRange(plan.Blocked);
            Total(next);
            return Task.FromResult(next);
        }

        public async Task<BulkImportResult> ApplyBulkAsync(BulkImportPlan plan, IProgress<BulkProgress> progress, CancellationToken ct)
        {
            var all = plan.Groups.SelectMany(g => g.Setups).ToList();
            long done = 0;
            var total = all.Sum(p => p.Plan.BytesToCopy);
            for (var i = 0; i < all.Count; i++)
            {
                ct.ThrowIfCancellationRequested();
                var planned = all[i];
                var label = $"{planned.Setup.SourceLabel}: {planned.Setup.Name}";
                var bytes = planned.Plan.BytesToCopy;
                progress.Report(new BulkProgress(i, all.Count, label,
                    new ImportProgress(ImportStage.Mods, 1, 3, "xaeros-minimap-24.2.jar"), done, total));
                if (_demo == Demo.Progress && i == 2)
                {
                    // Stop here, a third of the way in, until the render lets go (or the player cancels).
                    progress.Report(new BulkProgress(i, all.Count, label,
                        new ImportProgress(ImportStage.Packs, 2, 3, "Faithful 64x.zip"), done + bytes / 2, total));
                    using (ct.Register(() => _hold.TrySetResult(true))) await _hold.Task;
                    return new BulkImportResult { RunId = plan.RunId, State = ImportState.RolledBack, Cancelled = true };
                }
                if (_demo == Demo.Failed && i == 1)
                    return new BulkImportResult
                    {
                        RunId = plan.RunId, State = ImportState.RolledBack,
                        Error = "Not enough space on the drive.",
                    };
                done += bytes;
                await Task.Yield();
            }

            var versions = plan.Groups.Select(g => new VersionOutcome(g.Version, g.Setups.Select(s => new SetupOutcome(
                s.Setup, s.Role, s.Role == SetupRole.PacksOnly ? SetupResultKind.ImportedPartly : SetupResultKind.Imported, s.Note)).ToList())).ToList();
            // Like the engine: the skipped setups are the "didn't come over" list (by label) and the structured Skipped list.
            var notImported = plan.Skipped.Select(k => new NotImportedItem(k.Setup.BulkLabel(), "Whole setup", k.Reason)).ToList();
            return new BulkImportResult
            {
                RunId = plan.RunId, State = ImportState.Committed, Versions = versions, DidntComeOver = notImported,
                Skipped = plan.Skipped.ToList(),
                SummaryText = $"Imported {all.Count} setups into {plan.Groups.Count} versions. {plan.Skipped.Count} could not come over.",
            };
        }

        private static void Total(BulkImportPlan plan) =>
            plan.BytesToCopy = plan.Groups.SelectMany(g => g.Setups).Sum(s => s.Plan.BytesToCopy)
                + plan.Groups.Sum(g => g.GameFileOp?.Bytes ?? 0);

        /// <summary>One version's group. Mod-capable setups share the work (winner full, the rest mods and packs); Forge on a Fabric version is packs only.</summary>
        private static VersionGroup BuildGroup(string runId, string version, List<DetectedInstance> setups, ImportOptions options,
            DetectedInstance? chosenWinner)
        {
            bool PacksOnly(DetectedInstance s) => s.Loader is LoaderKind.Forge or LoaderKind.NeoForge && version != "1.8.9";
            var capable = setups.Where(s => !PacksOnly(s)).ToList();
            var candidates = capable.Count > 0 ? capable : setups;
            var pick = VersionPicker.Pick(candidates);
            var winner = chosenWinner ?? pick.Preselected ?? candidates[0];
            var gameDir = Path.Combine("demo", "instances", version, "game");
            var group = new VersionGroup
            {
                Version = version, TargetGameDir = gameDir, Winner = winner, Pick = pick,
                NeedsChoice = chosenWinner == null && pick.Mode == PickMode.Choose,
                GameFileOp = new PlannedOp
                {
                    Kind = OpKind.Custom, Item = PlanItemKind.GameFile, Label = $"Files Minecraft {version} needs",
                    Dest = Path.Combine("demo", "versions", version), Bytes = 96_000_000,
                },
            };
            var order = new List<DetectedInstance> { winner };
            order.AddRange(capable.Where(s => s.BulkId() != winner.BulkId()));
            order.AddRange(setups.Where(PacksOnly).Where(s => s.BulkId() != winner.BulkId()));
            var n = 0;
            foreach (var setup in order.DistinctBy(s => s.BulkId()))
            {
                var role = PacksOnly(setup) ? SetupRole.PacksOnly : setup.BulkId() == winner.BulkId() ? SetupRole.Full : SetupRole.ModsAndPacks;
                var plan = BuildPlan(setup, version, options, role, $"{setup.SourceLabel}: {setup.Name}", $"{runId}{n++:x2}", blocked: false);
                plan.TargetGameDir = gameDir;
                plan.BytesToCopy = plan.Lines.Where(l => l.Section is SummarySection.Imported or SummarySection.NeedsAttention).Sum(l => l.Bytes)
                    + plan.Ops.Sum(o => o.Bytes);
                group.Setups.Add(new PlannedSetup(setup, role, plan, role == SetupRole.PacksOnly ? ForgeNote : null));
            }
            return group;
        }

        public async Task<ImportResult> ApplyAsync(ImportPlan plan, IProgress<ImportProgress>? progress, CancellationToken ct)
        {
            if (_demo == Demo.Progress)
            {
                progress?.Report(new ImportProgress(ImportStage.Mods, 14, 40, "xaeros-minimap-24.2.jar"));
                using (ct.Register(() => _hold.TrySetResult(true))) await _hold.Task;
                return new ImportResult { ImportId = plan.ImportId, State = ImportState.RolledBack, Cancelled = true, SummaryText = "" };
            }
            if (_demo == Demo.Failed)
            {
                var failed = ImportSummary.Render(plan, null, "Failed: nothing was changed.");
                return new ImportResult
                {
                    ImportId = plan.ImportId, State = ImportState.RolledBack, SummaryText = failed,
                    Error = "Not enough space on the drive.",
                };
            }
            var text = ImportSummary.Render(plan);
            return await Task.FromResult(new ImportResult { ImportId = plan.ImportId, State = ImportState.Committed, SummaryText = text });
        }
    }
}
