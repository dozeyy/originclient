using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Shell;
using BulkPick = OriginLauncher.App.Core.Import.VersionPicker;

namespace OriginLauncher.Desktop.Shots;

/// <summary>
/// The Import page in every state, named like the Windows set (UI/ImportShots.cs) so the two sheets line up, driven by
/// a made-up backend (the same fixture as the Windows set): no scan, no plan against a real folder and nothing is
/// written. Each shot is its own fresh window, so each one replays the player's steps up to its state.
/// </summary>
public static class ImportShots
{
    // Bulk: the normal scan, plan and a successful apply. Progress: the apply stops part way and waits. Failed: the
    // apply rolls back with a reason. Blocked: the plan says Minecraft is running. Empty: nothing found (no launcher
    // installed). Scanning: the scan waits until the render lets go.
    private enum Fake { Bulk, Progress, Failed, Blocked, Empty, Scanning }

    private const double MinW = 980, MinH = 600;

    [ShotSet]
    public static IEnumerable<Shot> All()
    {
        // ---- the first-run card on Home ----
        var many = BulkSetups();
        var one = new[] { many[0] };
        yield return new Shot("17-home-import-card", w => Card(w, many));
        yield return new Shot("17b-home-import-card-one-setup", w => Card(w, one));
        // 17c: a game is starting (or an update runs): Import would be refused, so the card stays out of Home.
        yield return new Shot("17c-home-import-card-refused", w => Card(w, many).SetRefusal("Wait for Minecraft to finish starting, then try again."));
        yield return new Shot("50-min-home-import-card", w => Card(w, many), MinW, MinH);
        yield return new Shot("51-min-home-import-card-one-setup", w => Card(w, one), MinW, MinH);

        // ---- step 1, the launchers ----
        yield return new Shot("38-import-page-empty", w => Open(w, Fake.Empty));
        yield return new Shot("30a-import-page-scanning", w => Open(w, Fake.Scanning, 300));
        yield return new Shot("30-import-page-detect", w => Open(w, Fake.Bulk));
        yield return new Shot("30b-import-page-detect-bottom", w => Scrolled(Open(w, Fake.Bulk), double.MaxValue));
        yield return new Shot("52-min-import-detect", w => Open(w, Fake.Bulk), MinW, MinH);
        yield return new Shot("60-import-detect-one-launcher", w => Open(w, Fake.Bulk, oneLauncher: true));

        // ---- step 2, how much / the grid / single setups ----
        yield return new Shot("63-import-scope", w => Step(Open(w, Fake.Bulk), p => p.TourChooseLauncher(SourceKind.Modrinth)));
        yield return new Shot("64-min-import-scope", w => Step(Open(w, Fake.Bulk), p => p.TourChooseLauncher(SourceKind.Modrinth)), MinW, MinH);
        yield return new Shot("61-import-grid", w => Grid(w));
        yield return new Shot("62-min-import-grid", w => Grid(w), MinW, MinH);
        yield return new Shot("61b-import-grid-version", w =>
        {
            var p = Grid(w);
            p.TourGridTab("1.20.1");
            p.TourGridTurnOn(SourceKind.Modrinth, GridColumn.Mods);
            Settle(300);
        });
        yield return new Shot("31-import-page-pick", w => Pick(w));
        yield return new Shot("31c-import-page-pick-bottom", w => Scrolled(Pick(w), double.MaxValue));
        yield return new Shot("58-min-import-pick", w => Pick(w), MinW, MinH);

        // ---- What to bring, one version, whose settings ----
        yield return new Shot("43-import-versions-all", w => Versions(w));
        yield return new Shot("54-min-import-versions", w => Versions(w), MinW, MinH);
        yield return new Shot("43b-import-versions-mixed", w => Mixed(w));
        yield return new Shot("43c-import-versions-cannot", w => Scrolled(Mixed(w), double.MaxValue));
        yield return new Shot("45-import-version", w => Step(Versions(w), p => p.TourOpenVersion("1.20.1")));
        yield return new Shot("55-min-import-version", w => Step(Versions(w), p => p.TourOpenVersion("1.20.1")), MinW, MinH);
        yield return new Shot("45b-import-version-unsupported", w => Step(Versions(w), p => p.TourOpenVersion("1.21.5")));
        yield return new Shot("32-import-page-pick-settings", w =>
            Step(Step(Versions(w), p => p.TourOpenVersion("1.20.1")), p => p.TourOpenChooser("1.20.1")));

        // ---- step 3, Review and Details ----
        yield return new Shot("46-import-review", w => Review(w));
        yield return new Shot("56-min-import-review", w => Review(w), MinW, MinH);
        yield return new Shot("46b-import-review-versions", w => Scrolled(Review(w), 520));
        yield return new Shot("46c-import-review-left-out", w => Scrolled(Review(w), double.MaxValue));
        yield return new Shot("33-import-page-details", w => Step(Review(w), p => p.TourOpenDetails()));
        yield return new Shot("33b-import-page-details-groups", w => Scrolled(Step(Review(w), p => p.TourOpenDetails()), 480));
        yield return new Shot("46d-import-confirm-profiles", w =>
            Step(Step(Open(w, Fake.Bulk), p => p.TourChooseLauncher(SourceKind.Prism)), p => p.TourFastImport(SourceKind.Prism), 1000));

        // ---- one setup on its own ----
        yield return new Shot("39-import-single-review", w => Step(Pick(w), p => p.TourOpenSingle("Fabulously Optimized"), 1000));
        yield return new Shot("53-min-import-single-review", w => Step(Pick(w), p => p.TourOpenSingle("Fabulously Optimized"), 1000), MinW, MinH);
        yield return new Shot("39b-import-single-unsupported", w => Step(Pick(w), p => p.TourOpenSingle("Snapshot fun"), 700));

        // ---- step 4, importing and the report ----
        yield return new Shot("34-import-page-progress", w => Everything(w, Fake.Progress, SourceKind.Prism));
        yield return new Shot("59-min-import-progress", w => Everything(w, Fake.Progress, SourceKind.Prism), MinW, MinH);
        yield return new Shot("60b-import-fast-progress", w => Everything(w, Fake.Progress, SourceKind.Prism, oneLauncher: true));
        yield return new Shot("35-import-page-cancel-confirm", w => Step(Everything(w, Fake.Progress, SourceKind.Prism), p => p.TourAskCancel()));
        yield return new Shot("36-import-page-done", w => Everything(w, Fake.Bulk, SourceKind.Prism));
        yield return new Shot("57-min-import-done", w => Everything(w, Fake.Bulk, SourceKind.Prism), MinW, MinH);
        yield return new Shot("60c-import-fast-done", w => Everything(w, Fake.Bulk, SourceKind.Prism, oneLauncher: true));
        yield return new Shot("36f-import-page-done-report", w => Scrolled(Everything(w, Fake.Bulk, SourceKind.Prism), 420));
        yield return new Shot("36d-import-page-done-bottom", w => Scrolled(Everything(w, Fake.Bulk, SourceKind.Prism), double.MaxValue));
        yield return new Shot("36b-import-page-done-details", w => Step(Everything(w, Fake.Bulk, SourceKind.Prism), p => p.TourOpenDetails()));
        yield return new Shot("36c-import-done-lunar", w => Everything(w, Fake.Bulk, SourceKind.Lunar));
        // 36e: the same report with Fix Compatibility wired (a stand-in: nothing opens).
        yield return new Shot("36e-import-done-fix-compatibility", w =>
            Scrolled(Everything(w, Fake.Bulk, SourceKind.Prism, fix: true), 420));
        yield return new Shot("37-import-page-failed", w => Everything(w, Fake.Failed, SourceKind.Prism));
        yield return new Shot("40-import-page-blocked", w => Everything(w, Fake.Blocked, SourceKind.Prism));
        // 41: a game is starting: step 2's two ways on are off until it opens.
        yield return new Shot("41-import-page-launching", w =>
            Step(Step(Open(w, Fake.Bulk), p => p.TourChooseLauncher(SourceKind.Prism)), p => p.TourRefusal("Wait for Minecraft to finish starting.")));

        // ---- keyboard focus, as Tab shows it (the frame forced: the render window takes no real keyboard) ----
        yield return new Shot("42-import-page-focus-tick", w =>
        {
            var p = Step(Step(Open(w, Fake.Bulk), x => x.TourChooseLauncher(SourceKind.Prism)), x => x.TourBring());
            if (p.TourFirstTick() is { } tick) tick.ForceFocus = true;
            Settle(100);
        });
        yield return new Shot("42b-import-page-focus-footer", w =>
        {
            var p = Step(Step(Step(Open(w, Fake.Bulk), x => x.TourChooseLauncher(SourceKind.Prism)), x => x.TourBring()), x => x.TourOpenReview(), 1000);
            p.TourFooterPrimary().ForceFocus = true;
            Settle(100);
        });
    }

    // ---------------------------------------------------------------- the player's steps

    private static ImportPromptCard Card(MainWindow window, IReadOnlyList<DetectedInstance> setups)
    {
        var card = ImportView.ShowCard(window, ImportPrompt.Question(setups), setups, open: _ => null);
        Settle(100);
        return card;
    }

    private static ImportPage Open(MainWindow window, Fake fake, int settleMs = 600, bool oneLauncher = false, bool fix = false)
    {
        ImportHooks.FixCompatibility = fix ? (_, _) => Task.CompletedTask : null;
        var page = ImportView.OpenWith(window, ImportPage.Intent.Detect, new FakeBackend(fake, oneLauncher))
            ?? throw new InvalidOperationException("the Import page did not open");
        Settle(settleMs);
        return page;
    }

    private static ImportPage Step(ImportPage page, Action<ImportPage> step, int settleMs = 400)
    {
        step(page);
        Settle(settleMs);
        return page;
    }

    private static ImportPage Scrolled(ImportPage page, double offset)
    {
        page.TourScroll(offset);
        Settle(200);
        return page;
    }

    private static ImportPage Grid(MainWindow w) =>
        Step(Step(Open(w, Fake.Bulk), p => p.TourChooseLauncher(SourceKind.Modrinth)), p => p.TourShowGrid(), 500);

    private static ImportPage Pick(MainWindow w) => Step(Grid(w), p => p.TourShowPick(), 500);

    private static ImportPage Versions(MainWindow w) => Step(Pick(w), p => p.TourBring(), 500);

    // One version out (the select-all row is mixed) and screenshots switched off for every version.
    private static ImportPage Mixed(MainWindow w) => Step(Versions(w), p =>
    {
        p.TourSetVersion("1.20.1", false);
        p.TourBringAll(ImportCategories.Screenshots, false);
    });

    private static ImportPage Review(MainWindow w) => Step(Versions(w), p => p.TourOpenReview(), 1000);

    // Steps 1-3 the way a player goes: the launcher's tile, Import everything, then Review's Import (once its plan is in).
    private static ImportPage Everything(MainWindow w, Fake fake, SourceKind kind, bool oneLauncher = false, bool fix = false)
    {
        var page = Open(w, fake, oneLauncher: oneLauncher, fix: fix);
        Step(page, p => p.TourChooseLauncher(kind), 300);
        Step(page, p => p.TourFastImport(kind), 1000);
        Step(page, p => p.TourImportAll(), 2000);
        return Step(page, p => p.TourTick(), 200);
    }

    /// <summary>Lets posted work, the thread pool's made-up answers and the page's progress timer land (motion is off).</summary>
    private static void Settle(int ms)
    {
        var end = DateTime.UtcNow.AddMilliseconds(ms);
        do
        {
            ShotRunner.Pump();
            Thread.Sleep(15);
        }
        while (DateTime.UtcNow < end);
        ShotRunner.Pump();
    }

    // ---------------------------------------------------------------- the made-up data (same as the Windows set)

    /// <summary>A believable folder per launcher, so the tiles' path line reads as it will on a real PC. Made up and never read.</summary>
    private static string DemoFolder(SourceKind kind, string name) => kind switch
    {
        SourceKind.Prism => $"/home/will/.local/share/PrismLauncher/instances/{name}/minecraft",
        SourceKind.Modrinth => $"/home/will/.local/share/ModrinthApp/profiles/{name}",
        SourceKind.CurseForge => $"/home/will/curseforge/minecraft/Instances/{name}",
        SourceKind.Lunar => $"/home/will/.lunarclient/offline/{name}",
        _ => $"/home/will/.minecraft/versions/{name}",
    };

    private static DetectedInstance Instance(string name, SourceKind kind, string mc, long playSeconds,
        LoaderKind loader = LoaderKind.Fabric, int lastPlayedDaysAgo = 1, string? notSure = null) => new()
    {
        Kind = kind, Name = name, GameDir = DemoFolder(kind, name), McVersion = mc, Loader = loader,
        PlaytimeSeconds = playSeconds > 0 ? playSeconds : null,
        LastPlayed = DateTimeOffset.UtcNow.AddDays(-lastPlayedDaysAgo),
        Confidence = notSure == null ? Confidence.Exact : Confidence.Inferred,
        VersionCertain = notSure == null, UncertainReason = notSure,
    };

    /// <summary>
    /// 5 launchers (the Minecraft Launcher and Lunar among them), 14 setups on 5 Minecraft versions. 1.21.1 has a clear
    /// settings winner; 1.20.1 an unclear one and a Forge setup that can only bring its packs; 1.8.9 is Forge and Lunar;
    /// three setups are on 1.21.5, which Origin does not have; one (Vanilla+) is "not sure" of its version.
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

    private sealed class FakeBackend : IImportBackend
    {
        private static TaskCompletionSource<bool> _hold = new();
        private readonly Fake _fake;
        private readonly bool _oneLauncher;

        public FakeBackend(Fake fake, bool oneLauncher)
        {
            _fake = fake;
            _oneLauncher = oneLauncher;
            if (fake is Fake.Progress or Fake.Scanning) _hold = new TaskCompletionSource<bool>();
        }

        public IReadOnlyList<string> SupportedTargets { get; } = new[] { "1.21.1", "1.20.1", "1.20", "1.19.2", "1.8.9" };

        public string ImportsRoot => Path.Combine("demo", "imports");

        public async Task<ScanResult> ScanAsync(IProgress<ImportProgress>? progress, CancellationToken ct)
        {
            if (_fake == Fake.Scanning)
                using (ct.Register(() => _hold.TrySetResult(true))) await _hold.Task;
            var scan = new ScanResult();
            if (_fake != Fake.Empty) scan.Instances.AddRange(Setups());
            return scan;
        }

        private List<DetectedInstance> Setups() =>
            _oneLauncher ? BulkSetups().Where(s => s.Kind == SourceKind.Prism).ToList() : BulkSetups();

        public Task<IReadOnlySet<SourceKind>?> GetInstalledLaunchersAsync(CancellationToken ct) =>
            Task.FromResult<IReadOnlySet<SourceKind>?>(_fake == Fake.Empty ? new HashSet<SourceKind>()
                : _oneLauncher ? new HashSet<SourceKind> { SourceKind.Prism, SourceKind.OfficialLauncher }
                : new HashSet<SourceKind>(DemoInstalled));

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
            return Task.FromResult(BuildPlan(source, target, options, SetupRole.Full, "0123456789abcdef0123456789abcdef", _fake == Fake.Blocked));
        }

        /// <summary>One setup's made-up plan, shaped by its role the way the real engine shapes it.</summary>
        private static ImportPlan BuildPlan(DetectedInstance source, string target, ImportOptions options, SetupRole role,
            string importId, bool blocked)
        {
            var plan = new ImportPlan { ImportId = importId, Source = source, TargetVersion = target, Options = options };
            if (blocked) plan.Blocked.Add("Close Minecraft first: it is still running from one of your setups.");

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
                    Outcome = ModOutcome.WrongLoader, DestFileName = null, Reason = ImportVerifier.WrongLoaderReason,
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
            foreach (var m in plan.Mods.Where(m => m.IsCopied))
                plan.Ops.Add(new PlannedOp
                {
                    Kind = OpKind.CopyFile, Item = PlanItemKind.Mod, Label = m.DestFileName!,
                    Dest = Path.Combine("demo", "mods", m.DestFileName!), Bytes = 400_000,
                });
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
                plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.ResourcePack, "Old Textures", "Pack file missing - skipped"));
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

        private static readonly string[] DemoTargets = { "1.21.1", "1.20.1", "1.20", "1.19.2", "1.8.9" };

        public Task<BulkImportPlan> PlanBulkAsync(BulkSelection selection, ImportOptions options, ImportGrid? grid,
            IProgress<BulkPlanProgress>? progress, CancellationToken ct)
        {
            var plan = new BulkImportPlan { RunId = "bulk0123456789abcdef0123456789ab" };
            if (_fake == Fake.Blocked) plan.Blocked.Add("Close Minecraft first: it is still running from one of your setups.");
            var all = Setups();
            var chosen = all.Where(selection.Includes).ToList();
            var byVersion = new List<(string Version, List<DetectedInstance> Setups)>();
            var done = 0;
            foreach (var setup in all.Where(selection.IsUnticked))
                plan.Skipped.Add(new SkippedSetup(setup, "You left this setup out.", SkipKind.Unticked));
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
                progress.Report(new BulkProgress(i, all.Count, label, new ImportProgress(ImportStage.Mods, 1, 3, "xaeros-minimap-24.2.jar"), done, total));
                if (_fake == Fake.Progress && i == 2)
                {
                    // Stop here, a third of the way in, until the render lets go (or the player cancels).
                    progress.Report(new BulkProgress(i, all.Count, label,
                        new ImportProgress(ImportStage.Packs, 2, 3, "Faithful 64x.zip"), done + bytes / 2, total));
                    using (ct.Register(() => _hold.TrySetResult(true))) await _hold.Task;
                    return new BulkImportResult { RunId = plan.RunId, State = ImportState.RolledBack, Cancelled = true };
                }
                if (_fake == Fake.Failed && i == 1)
                    return new BulkImportResult { RunId = plan.RunId, State = ImportState.RolledBack, Error = "Not enough space on the drive." };
                done += bytes;
                await Task.Yield();
            }

            var versions = plan.Groups.Select(g => new VersionOutcome(g.Version, g.Setups.Select(s => new SetupOutcome(
                s.Setup, s.Role, s.Role == SetupRole.PacksOnly ? SetupResultKind.ImportedPartly : SetupResultKind.Imported, s.Note,
                ProfileId: s.Plan.ProfileId, ProfileName: s.Plan.ProfileName, ProfileModsDir: s.Plan.ProfileModsDir)).ToList())).ToList();
            var notImported = plan.Skipped.Select(k => new NotImportedItem(k.Setup.BulkLabel(), "Whole setup", k.Reason)).ToList();
            return new BulkImportResult
            {
                RunId = plan.RunId, State = ImportState.Committed, Versions = versions, DidntComeOver = notImported,
                Skipped = plan.Skipped.ToList(),
                SummaryText = $"Imported {all.Count} setups into {plan.Groups.Count} versions. {plan.Skipped.Count} could not come over.",
            };
        }

        // Step 4 with the real verifier over the made-up result: one shader pack reads as gone, and the second profile
        // made can't start, so the report shows the Fix Compatibility line.
        public Task<ImportVerification> VerifyAsync(BulkImportPlan plan, BulkImportResult result, CancellationToken ct)
        {
            var broken = result.Versions.SelectMany(v => v.Setups).Select(s => s.ProfileModsDir).Where(d => d != null).Skip(1).FirstOrDefault();
            return Task.FromResult(ImportVerifier.Verify(plan, result,
                exists: path => !path.EndsWith("Complementary Reimagined.zip", StringComparison.Ordinal),
                checkProfile: (_, dir) => dir == broken
                    ? new[] { "Create: needs Fabric Language Kotlin, which isn't in this profile." }
                    : Array.Empty<string>()));
        }

        private static void Total(BulkImportPlan plan) =>
            plan.BytesToCopy = plan.Groups.SelectMany(g => g.Setups).Sum(s => s.Plan.BytesToCopy)
                + plan.Groups.Sum(g => g.GameFileOp?.Bytes ?? 0);

        private static VersionGroup BuildGroup(string runId, string version, List<DetectedInstance> setups, ImportOptions options,
            DetectedInstance? chosenWinner)
        {
            bool PacksOnly(DetectedInstance s) => s.Loader is LoaderKind.Forge or LoaderKind.NeoForge && version != "1.8.9";
            var capable = setups.Where(s => !PacksOnly(s)).ToList();
            var candidates = capable.Count > 0 ? capable : setups;
            var pick = BulkPick.Pick(candidates);
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
                var plan = BuildPlan(setup, version, options, role, $"{runId}{n++:x2}", blocked: false);
                plan.TargetGameDir = gameDir;
                if (role != SetupRole.PacksOnly)
                {
                    plan.ProfileId = $"demo-{version.Replace('.', '-')}-{plan.ImportId[^2..]}";
                    plan.ProfileName = ImportEngine.MakeProfileName(setup, version, Array.Empty<App.Core.Profiles.ModProfile>());
                    plan.ProfileModsDir = Path.Combine("demo", "profiles", plan.ProfileId, "mods");
                }
                plan.BytesToCopy = plan.Lines.Where(l => l.Section is SummarySection.Imported or SummarySection.NeedsAttention).Sum(l => l.Bytes)
                    + plan.Ops.Sum(o => o.Bytes);
                group.Setups.Add(new PlannedSetup(setup, role, plan, role == SetupRole.PacksOnly ? BulkRules.ForgeNote : null));
            }
            return group;
        }

        public async Task<ImportResult> ApplyAsync(ImportPlan plan, IProgress<ImportProgress>? progress, CancellationToken ct)
        {
            if (_fake == Fake.Progress)
            {
                progress?.Report(new ImportProgress(ImportStage.Mods, 14, 40, "xaeros-minimap-24.2.jar"));
                using (ct.Register(() => _hold.TrySetResult(true))) await _hold.Task;
                return new ImportResult { ImportId = plan.ImportId, State = ImportState.RolledBack, Cancelled = true, SummaryText = "" };
            }
            if (_fake == Fake.Failed)
                return new ImportResult
                {
                    ImportId = plan.ImportId, State = ImportState.RolledBack,
                    SummaryText = ImportSummary.Render(plan, null, "Failed: nothing was changed."), Error = "Not enough space on the drive.",
                };
            return await Task.FromResult(new ImportResult { ImportId = plan.ImportId, State = ImportState.Committed, SummaryText = ImportSummary.Render(plan) });
        }
    }
}
