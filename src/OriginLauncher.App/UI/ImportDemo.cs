using System.IO;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;

namespace OriginLauncher.App.UI;

/// <summary>
/// The render shots' made-up import (UI/ImportShots.cs on Windows; the Mac launcher links this file for its own render,
/// src/OriginLauncher.Mac/UI/Shots/AreaDShots.cs): the same 14 setups, plans and canned progress on both, so each Mac
/// shot lies next to its Windows twin. Framework-free. There is no scan, no plan against a real folder and nothing is
/// written: the plans are built in memory here, and "Import" only plays back canned progress.
/// </summary>
internal static class ImportDemo
{
    // Bulk: the normal scan, plan and a successful apply. Progress: the apply stops part way and waits. Failed: the
    // apply rolls back with a reason. Blocked: the plan says Minecraft is running. Empty: nothing found (no launcher
    // installed). Scanning: the scan waits until the render lets go.
    public enum Demo { Bulk, Progress, Failed, Blocked, Empty, Scanning }

    /// <summary>The Mac render: the tiles' folders read as a Mac's (~/Library/Application Support/...).</summary>
    public static bool MacPaths { get; set; }

    /// <summary>
    /// A believable Windows folder per launcher, so the tiles' path line reads as it will on a real PC. Made up and never
    /// read: the demo backend answers every question itself.
    /// </summary>
    private static string DemoFolder(SourceKind kind, string name) => MacPaths ? MacFolder(kind, name) : kind switch
    {
        SourceKind.Prism => $@"C:\Users\Will\AppData\Roaming\PrismLauncher\instances\{name}\minecraft",
        SourceKind.Modrinth => $@"C:\Users\Will\AppData\Roaming\ModrinthApp\profiles\{name}",
        SourceKind.CurseForge => $@"C:\Users\Will\curseforge\minecraft\Instances\{name}",
        SourceKind.Lunar => $@"C:\Users\Will\.lunarclient\offline\{name}",
        _ => $@"C:\Users\Will\AppData\Roaming\.minecraft\versions\{name}",
    };

    // The same launchers' Mac folders (Core/Import/Launcher/ImportRoots.cs names each one and where it comes from).
    private static string MacFolder(SourceKind kind, string name) => kind switch
    {
        SourceKind.Prism => $"/Users/will/Library/Application Support/PrismLauncher/instances/{name}/minecraft",
        SourceKind.Modrinth => $"/Users/will/Library/Application Support/ModrinthApp/profiles/{name}",
        SourceKind.CurseForge => $"/Users/will/Documents/curseforge/minecraft/Instances/{name}",
        SourceKind.Lunar => $"/Users/will/.lunarclient/offline/{name}",
        _ => $"/Users/will/Library/Application Support/minecraft/versions/{name}",
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
    public static List<DetectedInstance> BulkSetups() => new()
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

    public sealed class DemoBackend : IImportBackend
    {
        private static TaskCompletionSource<bool> _hold = new();
        private readonly Demo _demo;
        private readonly bool _oneLauncher;

        /// <param name="oneLauncher">Only Prism has setups (the one-launcher fast path).</param>
        public DemoBackend(Demo demo, bool oneLauncher = false)
        {
            _demo = demo;
            _oneLauncher = oneLauncher;
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
            if (_demo != Demo.Empty) scan.Instances.AddRange(Setups());
            return scan;
        }

        /// <summary>The demo's setups: all 14, or Prism's alone for the one-launcher shots.</summary>
        private List<DetectedInstance> Setups() =>
            _oneLauncher ? BulkSetups().Where(s => s.Kind == SourceKind.Prism).ToList() : BulkSetups();

        public Task<IReadOnlySet<SourceKind>?> GetInstalledLaunchersAsync(CancellationToken ct) =>
            Task.FromResult<IReadOnlySet<SourceKind>?>(_demo == Demo.Empty ? new HashSet<SourceKind>()
                : _oneLauncher ? new HashSet<SourceKind> { SourceKind.Prism, SourceKind.OfficialLauncher }
                : new HashSet<SourceKind>(DemoInstalled));

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
                    Outcome = ModOutcome.WrongLoader, DestFileName = null, Reason = Core.Import.ImportVerifier.WrongLoaderReason,
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
            // Each copied jar is a copy op, as the engine plans it: the report counts a profile only once a jar landed.
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

        // The demo ignores the grid: the grid screen itself is drawn from the page's own model, and progress and Done
        // only need a believable plan.
        public Task<BulkImportPlan> PlanBulkAsync(BulkSelection selection, ImportOptions options, ImportGrid? grid,
            IProgress<BulkPlanProgress>? progress, CancellationToken ct)
        {
            var plan = new BulkImportPlan { RunId = "bulk0123456789abcdef0123456789ab" };
            if (_demo == Demo.Blocked) plan.Blocked.Add("Close Minecraft first: it is still running from one of your setups.");
            var all = Setups();
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
                s.Setup, s.Role, s.Role == SetupRole.PacksOnly ? SetupResultKind.ImportedPartly : SetupResultKind.Imported, s.Note,
                ProfileId: s.Plan.ProfileId, ProfileName: s.Plan.ProfileName, ProfileModsDir: s.Plan.ProfileModsDir)).ToList())).ToList();
            // Like the engine: the skipped setups are the "didn't come over" list (by label) and the structured Skipped list.
            var notImported = plan.Skipped.Select(k => new NotImportedItem(k.Setup.BulkLabel(), "Whole setup", k.Reason)).ToList();
            return new BulkImportResult
            {
                RunId = plan.RunId, State = ImportState.Committed, Versions = versions, DidntComeOver = notImported,
                Skipped = plan.Skipped.ToList(),
                SummaryText = $"Imported {all.Count} setups into {plan.Groups.Count} versions. {plan.Skipped.Count} could not come over.",
            };
        }

        // Step 4 over the demo's made-up result with the real verifier: nothing is read from disk (one shader pack
        // reads as gone, so the Failed section has a line), and the second profile made can't start, so the report
        // shows the Fix Compatibility line.
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
                if (role != SetupRole.PacksOnly)
                {
                    // Like the engine: each setup whose mods come over gets its own profile.
                    plan.ProfileId = $"demo-{version.Replace('.', '-')}-{plan.ImportId[^2..]}";
                    plan.ProfileName = Core.Import.ImportEngine.MakeProfileName(setup, version, Array.Empty<Core.Profiles.ModProfile>());
                    plan.ProfileModsDir = Path.Combine("demo", "profiles", plan.ProfileId, "mods");
                }
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
