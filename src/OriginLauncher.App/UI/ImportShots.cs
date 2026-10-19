using System.IO;
using OriginLauncher.App.Core.Import;
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
    private enum Demo { Choose, Auto, Unsupported, Blocked, Progress, Done, Failed }

    public static void Run(MainWindow window, string outDir)
    {
        try
        {
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

            // 15: importing (progress bar and the step text; Home's Play is disabled behind the card).
            panel = Open(window, Demo.Progress, 1500);
            panel.TourPrimary();
            RenderShots.Pump(900);
            RenderShots.Snap(window, outDir, "15-import-progress");
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
            var plan = new ImportPlan
            {
                ImportId = "0123456789abcdef0123456789abcdef", Source = source, TargetVersion = target, Options = options,
                ProfileId = "fedcba9876543210fedcba9876543210", ProfileName = "Prism: Survival 1.21.1",
            };
            if (_demo == Demo.Blocked)
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
                plan.Ops.Add(new PlannedOp { Kind = OpKind.CopyFile, Item = kind, Label = name, Dest = Path.Combine("demo", name), Bytes = 40_000 });
                plan.Lines.Add(new SummaryLine(SummarySection.Imported, kind, name));
            }
            plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Setting, "Resource pack \"Old Textures\"",
                "was in the options but is not in the instance's resourcepacks folder."));
            plan.Lines.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.Setting, "authlib-injector.cfg",
                "Skipped for safety: it looks like a login or account file."));
            plan.SkippedForSafety.Add("authlib-injector.cfg");
            plan.GameFiles = new GameFilePlanStats(612, 380_000_000, 14, 96_000_000, 0, false, false);
            for (var i = 0; i < 3; i++)
                plan.Lines.Add(new SummaryLine(SummarySection.ReusedGameFiles, PlanItemKind.GameFile, $"libraries/demo/{i}.jar", null, 1_000_000));
            plan.Caches.Add(new CachePlanItem(CacheKind.DistantHorizons, CacheScope.SinglePlayer, "New World", "overworld", true, null, 220_000_000));
            plan.Lines.Add(new SummaryLine(SummarySection.ReusedCaches, PlanItemKind.Cache, "Distant Horizons: New World (overworld)", null, 220_000_000));
            plan.Created = 3; plan.Replaced = 1; plan.Kept = 0; plan.Unchanged = 0;
            return Task.FromResult(plan);
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
