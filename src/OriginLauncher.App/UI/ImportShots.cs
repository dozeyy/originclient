using System.IO;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.App.UI;

/// <summary>
/// CI screen render (RenderShots): the import card in every state, driven by a made-up backend. There is
/// no scan, no plan against a real folder and nothing is written: the plan is built in memory here, and
/// "Import" only plays back canned progress.
/// </summary>
internal static class ImportShots
{
    private enum Demo { Choose, Auto, Unsupported, Blocked, Progress, Done, Failed, Empty, Bulk }

    public static void Run(MainWindow window, string outDir)
    {
        try
        {
            // 12a: the Profiles page's Import button, pressed like a click (the real click path), on a PC with no other launcher.
            window.TourBackendFactory = () => new DemoBackend(Demo.Empty);
            window.TourShowProfiles(new Core.Models.LauncherSettings(), _ => 0, openForm: false);
            RenderShots.Pump(500);
            window.TourClickProfilesImport();
            RenderShots.Pump(900);
            RenderShots.Snap(window, outDir, "12a-import-from-profiles-empty");
            window.TourBackendFactory = null;

            // 12: nothing is obvious, so the player picks (Find).
            Open(window, Demo.Choose, 900);
            RenderShots.Snap(window, outDir, "12-import-find");

            // 13: one clear instance: straight to Review, with counts and "needs attention" open.
            var panel = Open(window, Demo.Auto, 1500);
            RenderShots.Snap(window, outDir, "13-import-review");

            // 14: the details: options and every item.
            panel.TourSetDetails(true);
            RenderShots.Pump(500);
            panel.TourScrollReview(430);
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "14-import-review-details");
            panel.TourScrollReview(double.MaxValue); // the per-item tables at the bottom
            RenderShots.Pump(300);
            RenderShots.Snap(window, outDir, "14b-import-review-tables");

            // 15: importing (progress bar and the step text; Home's Play is disabled behind the card).
            panel = Open(window, Demo.Progress, 1500);
            panel.TourPrimary();
            RenderShots.Pump(900);
            RenderShots.Snap(window, outDir, "15-import-progress");
            panel.TourAskCancel(); // the "Cancel the import?" confirmation
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "15b-import-cancel-confirm");
            DemoBackend.Release();
            RenderShots.Pump(400);

            // 16: done.
            panel = Open(window, Demo.Done, 1500);
            panel.TourPrimary();
            RenderShots.Pump(1200);
            RenderShots.Snap(window, outDir, "16-import-done");

            // 17: the one-time first-run card.
            window.TourCloseImport();
            RenderShots.Pump(300);
            window.TourImportPrompt.ShowQuestion(ImportPrompt.Question(new[] { Instance("Survival 1.21.1", SourceKind.Prism, "1.21.1", 147600) }));
            RenderShots.Pump(400);
            RenderShots.Snap(window, outDir, "17-import-first-run-prompt");
            window.TourImportPrompt.Hide();

            // 18: a source version Origin does not have (Import stays off until a version is picked).
            Open(window, Demo.Unsupported, 1500);
            RenderShots.Snap(window, outDir, "18-import-review-unsupported");

            // 19: blocked (Minecraft is still running from the old instance).
            Open(window, Demo.Blocked, 1500);
            RenderShots.Snap(window, outDir, "19-import-review-blocked");

            // 20: failed and rolled back.
            panel = Open(window, Demo.Failed, 1500);
            panel.TourPrimary();
            RenderShots.Pump(1200);
            RenderShots.Snap(window, outDir, "20-import-failed");
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(outDir, "import-error.txt"), ex.ToString());
        }
        finally
        {
            DemoBackend.Release();
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

    private static DetectedInstance Instance(string name, SourceKind kind, string mc, long playSeconds,
        LoaderKind loader = LoaderKind.Fabric, int lastPlayedDaysAgo = 1, Confidence confidence = Confidence.Exact) => new()
    {
        Kind = kind, Name = name, GameDir = Path.Combine("demo", name), McVersion = mc, Loader = loader,
        PlaytimeSeconds = playSeconds > 0 ? playSeconds : null,
        LastPlayed = DateTimeOffset.UtcNow.AddDays(-lastPlayedDaysAgo), Confidence = confidence,
    };

    /// <summary>
    /// "Import everything" demo data: 3 launchers, 9 setups on 4 Minecraft versions. 1.21.1 has a clear settings
    /// winner; 1.20.1 has an unclear one and a Forge setup that can only bring its packs; 1.8.9 is Forge and fine;
    /// two setups are on 1.21.5, which Origin does not have.
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
        Instance("Vanilla+", SourceKind.CurseForge, "1.21.1", 1 * 3600, LoaderKind.Fabric, 100, Confidence.Inferred),
    };

    private sealed class DemoBackend : IImportBackend
    {
        private static TaskCompletionSource<bool> _hold = new();
        private readonly Demo _demo;

        public DemoBackend(Demo demo)
        {
            _demo = demo;
            if (demo == Demo.Progress) _hold = new TaskCompletionSource<bool>();
        }

        /// <summary>Lets a held apply finish (so the render does not leave a task waiting).</summary>
        public static void Release() => _hold.TrySetResult(true);

        public IReadOnlyList<string> SupportedTargets { get; } = new[] { "1.21.1", "1.20.1", "1.20", "1.19.2", "1.8.9" };

        public string ImportsRoot => Path.Combine("demo", "imports");

        public Task<ScanResult> ScanAsync(IProgress<ImportProgress>? progress, CancellationToken ct)
        {
            var scan = new ScanResult();
            switch (_demo)
            {
                case Demo.Choose:
                    scan.Instances.Add(Instance("Survival 1.21.1", SourceKind.Prism, "1.21.1", 41 * 3600, lastPlayedDaysAgo: 2));
                    scan.Instances.Add(Instance("Skyblock", SourceKind.Prism, "1.20.1", 38 * 3600, lastPlayedDaysAgo: 9));
                    scan.Instances.Add(Instance("Cobblemon pack", SourceKind.Modrinth, "1.21.1", 0, LoaderKind.NeoForge, 5, Confidence.Inferred));
                    scan.Instances.Add(Instance("All the Mods 10", SourceKind.CurseForge, "1.21.1", 0, LoaderKind.NeoForge, 30));
                    scan.Instances.Add(Instance("1.8.9 PvP", SourceKind.OfficialLauncher, "1.8.9", 0, LoaderKind.Forge, 120));
                    break;
                case Demo.Bulk:
                    scan.Instances.AddRange(BulkSetups());
                    break;
                case Demo.Empty:
                    break;
                case Demo.Unsupported:
                    scan.Instances.Add(Instance("Old survival world", SourceKind.OfficialLauncher, "1.19", 0, LoaderKind.Fabric, 200));
                    break;
                default:
                    scan.Instances.Add(Instance("Survival 1.21.1", SourceKind.Prism, "1.21.1", 41 * 3600, lastPlayedDaysAgo: 2));
                    break;
            }
            return Task.FromResult(scan);
        }

        public Task<FolderInspection> InspectFolderAsync(string folder, CancellationToken ct) =>
            Task.FromResult(new FolderInspection { Message = FolderInspection.NotAnInstance });

        public Task<ImportPlan> PlanAsync(DetectedInstance source, string? targetVersion, ImportOptions options,
            IProgress<ImportProgress>? progress, CancellationToken ct)
        {
            var target = targetVersion ?? source.McVersion ?? "1.21.1";
            return Task.FromResult(BuildPlan(source, target, options, SetupRole.Full, "Prism: Survival 1.21.1",
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
                plan.Blocked.Add("Close Minecraft first: it is still running from the instance you're importing.");

            ModDecision Mod(string file, string name, ModOutcome outcome, string? reason = null) =>
                new(file, null, name, null, outcome, reason, outcome is ModOutcome.Copied or ModOutcome.CopiedDisabled ? file : null, "demo");
            plan.Mods.AddRange(new[]
            {
                Mod("xaeros-minimap-24.2.jar", "Xaero's Minimap", ModOutcome.Copied),
                Mod("journeymap-1.21.1.jar", "JourneyMap", ModOutcome.Copied),
                Mod("waystones-fabric.jar", "Waystones", ModOutcome.Copied),
                Mod("jade-15.jar", "Jade", ModOutcome.Copied),
                Mod("appleskin-3.0.jar", "AppleSkin", ModOutcome.Copied),
                Mod("create-fabric.jar", "Create", ModOutcome.CopiedDisabled, "needs Fabric Kotlin, which is not in this instance"),
                Mod("sodium-fabric-0.6.13.jar", "Sodium", ModOutcome.BuiltIn, "Origin includes Sodium 0.6.13"),
                Mod("lithium-fabric-0.14.jar", "Lithium", ModOutcome.BuiltIn, "Origin includes Lithium 0.14.3"),
                Mod("iris-1.8.8.jar", "Iris", ModOutcome.BuiltIn, "Origin includes Iris 1.8.8"),
                Mod("fabric-api-0.116.jar", "Fabric API", ModOutcome.BuiltIn, "Origin includes Fabric API"),
                Mod("oldmap-1.20.1.jar", "Old Map Mod", ModOutcome.WrongMinecraft, "built for Minecraft 1.20.1, this is 1.21.1"),
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
                    plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Mod, label, "Imported but turned off: " + m.Reason, 4_200_000));
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
                plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Setting, "Resource pack \"Old Textures\"",
                    "was in the options but is not in the instance's resourcepacks folder."));
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

        private const string ForgeNote = "Forge mods can't come over. Origin runs Fabric, except on 1.8.9.";

        private static readonly string[] DemoTargets = { "1.21.1", "1.20.1", "1.20", "1.19.2", "1.8.9" };

        public Task<BulkImportPlan> PlanBulkAsync(BulkSelection selection, ImportOptions options,
            IProgress<BulkPlanProgress>? progress, CancellationToken ct)
        {
            var plan = new BulkImportPlan { RunId = "bulk0123456789abcdef0123456789ab" };
            var chosen = BulkSetups().Where(selection.Includes).ToList();
            var byVersion = new List<(string Version, List<DetectedInstance> Setups)>();
            var done = 0;
            foreach (var setup in chosen)
            {
                ct.ThrowIfCancellationRequested();
                var version = setup.McVersion ?? "";
                if (!DemoTargets.Contains(version))
                {
                    var skipped = new SkippedSetup(setup, $"Origin doesn't support Minecraft {version}, so this setup can't come over.");
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
            for (var i = 0; i < all.Count; i++)
            {
                ct.ThrowIfCancellationRequested();
                var planned = all[i];
                var bytes = planned.Plan.BytesToCopy;
                progress.Report(new BulkProgress(i, all.Count, planned.Setup.Name,
                    new ImportProgress(ImportStage.Mods, 1, 3, "xaeros-minimap-24.2.jar"), done, plan.BytesToCopy));
                progress.Report(new BulkProgress(i, all.Count, planned.Setup.Name,
                    new ImportProgress(ImportStage.Packs, 2, 3, "Faithful 64x.zip"), done + bytes / 2, plan.BytesToCopy));
                done += bytes;
                await Task.Yield();
            }

            var versions = plan.Groups.Select(g => new VersionOutcome(g.Version, g.Setups.Select(s => new SetupOutcome(
                s.Setup, s.Role, s.Role == SetupRole.PacksOnly ? SetupResultKind.ImportedPartly : SetupResultKind.Imported, s.Note)).ToList())).ToList();
            var notImported = plan.Skipped.Select(k => new NotImportedItem(k.Setup.Name, "Whole setup", k.Reason))
                .Concat(plan.Groups.SelectMany(g => g.Setups).SelectMany(s => s.Plan.Lines
                    .Where(l => l.Section == SummarySection.NotImported)
                    .Select(l => new NotImportedItem(s.Setup.Name, l.Name, l.Reason ?? ""))))
                .ToList();
            return new BulkImportResult
            {
                RunId = plan.RunId, State = ImportState.Committed, Versions = versions, DidntComeOver = notImported,
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
                    Kind = OpKind.Custom, Item = PlanItemKind.GameFile, Label = $"Game files for Minecraft {version}",
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
                await _hold.Task;
                return new ImportResult { ImportId = plan.ImportId, State = ImportState.RolledBack, Cancelled = true, SummaryText = "" };
            }
            if (_demo == Demo.Failed)
            {
                var failed = ImportSummary.Render(plan, null, "Failed: nothing was changed.");
                return new ImportResult
                {
                    ImportId = plan.ImportId, State = ImportState.RolledBack, SummaryText = failed,
                    Error = "The import didn't finish (Not enough space on the drive). Everything was put back the way it was.",
                };
            }
            var text = ImportSummary.Render(plan);
            return await Task.FromResult(new ImportResult { ImportId = plan.ImportId, State = ImportState.Committed, SummaryText = text });
        }
    }
}
