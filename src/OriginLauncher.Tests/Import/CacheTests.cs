using System.Text;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Caches;
using OriginLauncher.App.Core.Import.Mods;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Distant Horizons and Voxy caches: what the scanner finds, when a cache is allowed to cross, and how
// it crosses (as a group, probed for locks, nothing merged). Fixtures only; no SQLite engine anywhere.
public class CacheTests : IDisposable
{
    private readonly Rig _rig = new();

    public void Dispose() => _rig.Dispose();

    private string Src(string rel) => Path.Combine(_rig.Source, rel.Replace('/', Path.DirectorySeparatorChar));
    private string Dst(string rel) => Path.Combine(_rig.GameDir(), rel.Replace('/', Path.DirectorySeparatorChar));

    private ImportEngine Engine(CacheContributor? contributor = null) =>
        EngineBKit.Engine(_rig, new IImportPlanContributor[] { contributor ?? new CacheContributor() });

    // DH 2.3.3 and Voxy 0.1.5 among the source's mods: both are imported enabled (target 1.21.1 bundles Sodium 0.6.13).
    private void Mods(string dh = "2.3.3-b", string voxy = "0.1.5")
    {
        if (dh != null) Kit.WriteBytes(_rig.Source, "mods/dh.jar", Kit.FabricJar("distanthorizons", dh, name: "Distant Horizons"));
        if (voxy != null) Kit.WriteBytes(_rig.Source, "mods/voxy.jar", Kit.FabricJar("voxy", voxy, name: "Voxy"));
    }

    private async Task<(ImportPlan Plan, ImportResult Result)> Run(ImportEngine engine, ImportOptions? options = null)
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var plan = await engine.PlanAsync(_rig.Request(options: options), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        return (plan, await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None));
    }

    private void World(string root, string name, string levelName, long seed, bool modern = true) =>
        Kit.WriteBytes(root, $"saves/{name}/level.dat", EngineBKit.LevelDat(levelName, seed, modern));

    private static bool Imported(ImportPlan plan, CacheKind kind, string owner, string dimension = "") =>
        plan.Caches.Any(c => c.Kind == kind && c.Owner == owner && c.Dimension == dimension && c.WillImport);

    private static CachePlanItem Item(ImportPlan plan, CacheKind kind, string owner, string dimension = "") =>
        plan.Caches.Single(c => c.Kind == kind && c.Owner == owner && c.Dimension == dimension);

    // ------------------------------------------------------------ the scanner (criterion 40)

    [Fact]
    public void TheScannerFindsEveryKindOfCacheAndListsTheNeverImportedOnes()
    {
        var gameDir = Path.Combine(_rig.Root, "found");
        var db = EngineBKit.Sqlite("db");
        foreach (var dim in new[] { "", "DIM-1/", "DIM1/", "dimensions/mymod/space/deep/" })
            Kit.WriteBytes(gameDir, $"saves/W1/{dim}data/DistantHorizons.sqlite", db);
        Kit.WriteBytes(gameDir, "saves/W1/region/r.0.0.mca", new byte[4]);
        Kit.WriteBytes(gameDir, "saves/W1/data/DistantHorizons.sqlite-wal", db);       // same group
        Kit.WriteBytes(gameDir, "saves/Lone/data/DistantHorizons.sqlite-wal", db);      // a -wal on its own
        Kit.WriteBytes(gameDir, "Distant_Horizons_server_data/Hypixel%2C%20IP/abc@minecraft@@overworld/DistantHorizons.sqlite", db);
        Kit.WriteBytes(gameDir, "Distant_Horizons_server_data/REPLAY/x@minecraft@@overworld/DistantHorizons.sqlite", db);
        Kit.Write(gameDir, "saves/W1/voxy/config.json", "{}");
        Kit.Write(gameDir, "saves/W1/voxy/7788/storage/CURRENT", "x");
        Kit.Write(gameDir, ".voxy/saves/play.example.net_25565/config.json", "{}");
        Kit.Write(gameDir, ".voxy/saves/realms_1234/config.json", "{}");
        Kit.Write(gameDir, ".voxy/saves/UNKNOWN/config.json", "{}");

        var scan = CacheScanner.Scan(gameDir, new SourceFs(), CancellationToken.None);

        Assert.Equal(new[] { "", "DIM-1", "DIM1", "dimensions/mymod/space/deep" }.OrderBy(d => d, StringComparer.Ordinal),
            scan.DhSinglePlayer.Where(d => d.Owner == "W1").Select(d => d.Dimension).OrderBy(d => d, StringComparer.Ordinal));
        Assert.True(scan.DhSinglePlayer.Single(d => d.Owner == "Lone").LoneWal);
        var server = Assert.Single(scan.DhServers);
        Assert.Equal("Hypixel%2C%20IP", server.Folder);                                // the exact folder name
        Assert.Equal("Hypixel, IP", server.DisplayName);                              // percent-decoded for display only
        Assert.Equal("abc@minecraft@@overworld", Assert.Single(server.Levels).Dimension);
        Assert.Equal(new[] { "play.example.net_25565" }, scan.Voxy.Where(v => v.Scope == CacheScope.Multiplayer).Select(v => v.Owner));
        Assert.Equal(new[] { "W1" }, scan.Voxy.Where(v => v.Scope == CacheScope.SinglePlayer).Select(v => v.Owner));
        Assert.Contains(scan.Never, n => n.Kind == CacheKind.DistantHorizons && n.Name == "REPLAY");
        Assert.Contains(scan.Never, n => n.Kind == CacheKind.Voxy && n.Name == "realms_1234" && n.Reason.Contains("Realm"));
        Assert.Contains(scan.Never, n => n.Kind == CacheKind.Voxy && n.Name == "UNKNOWN");
    }

    [Fact]
    public async Task RealmsUnknownAndReplayCachesAreListedAsNeverImportedAndNeverCopied()
    {
        Mods();
        Kit.WriteBytes(_rig.Source, "Distant_Horizons_server_data/REPLAY/x/DistantHorizons.sqlite", EngineBKit.Sqlite("r"));
        Kit.Write(_rig.Source, ".voxy/saves/realms_9/config.json", "{}");
        Kit.Write(_rig.Source, ".voxy/saves/UNKNOWN/config.json", "{}");

        var (plan, result) = await Run(Engine());

        Assert.True(result.Succeeded);
        Assert.False(Directory.Exists(Dst("Distant_Horizons_server_data")));
        Assert.False(Directory.Exists(Dst(".voxy")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name.Contains("REPLAY") && l.Reason!.Contains("never imported"));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name.Contains("realms_9"));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name.Contains("UNKNOWN"));
    }

    // ------------------------------------------------------------ single player identity (criterion 41)

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task ASinglePlayerCacheCrossesOnlyIntoAWorldWithTheSameNameAndSeedForBothSeedLocations(bool modern)
    {
        Mods();
        World(_rig.Source, "Match", "My World", 12345, modern);
        World(_rig.GameDir(), "Match", "My World", 12345, modern);
        World(_rig.Source, "WrongSeed", "Seed", 1, modern);
        World(_rig.GameDir(), "WrongSeed", "Seed", 2, modern);
        World(_rig.Source, "WrongName", "Alpha", 7, modern);
        World(_rig.GameDir(), "WrongName", "Beta", 7, modern);
        World(_rig.Source, "NoDest", "Gone", 9, modern);
        foreach (var w in new[] { "Match", "WrongSeed", "WrongName", "NoDest" })
        {
            Kit.WriteBytes(_rig.Source, $"saves/{w}/data/DistantHorizons.sqlite", EngineBKit.Sqlite("overworld-" + w));
            Kit.WriteBytes(_rig.Source, $"saves/{w}/DIM-1/data/DistantHorizons.sqlite", EngineBKit.Sqlite("nether-" + w));
            Kit.Write(_rig.Source, $"saves/{w}/voxy/config.json", "{\"w\":\"" + w + "\"}");
        }

        var (plan, result) = await Run(Engine());

        Assert.True(result.Succeeded);
        Assert.Equal(EngineBKit.Sqlite("overworld-Match"), File.ReadAllBytes(Dst("saves/Match/data/DistantHorizons.sqlite")));
        Assert.Equal(EngineBKit.Sqlite("nether-Match"), File.ReadAllBytes(Dst("saves/Match/DIM-1/data/DistantHorizons.sqlite")));   // same relative dimension
        Assert.Equal("{\"w\":\"Match\"}", File.ReadAllText(Dst("saves/Match/voxy/config.json")));
        foreach (var w in new[] { "WrongSeed", "WrongName", "NoDest" })
        {
            Assert.False(File.Exists(Dst($"saves/{w}/data/DistantHorizons.sqlite")), w);
            Assert.False(Directory.Exists(Dst($"saves/{w}/voxy")), w);
            Assert.False(Imported(plan, CacheKind.DistantHorizons, w), w);
            Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name.StartsWith(w)
                                             && l.Reason == "No matching Origin world — left in source.");
        }
        Assert.True(Imported(plan, CacheKind.DistantHorizons, "Match"));
        Assert.True(Imported(plan, CacheKind.DistantHorizons, "Match", "DIM-1"));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.ReusedCaches && l.Name == "Match / overworld (Distant Horizons)");
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.ReusedCaches && l.Name == "Match / DIM-1 (Distant Horizons)");
    }

    [Fact]
    public async Task ACacheTravelsWithAWorldThatThisImportCopies()
    {
        Mods();
        World(_rig.Source, "Copied", "Copied", 5);
        Kit.WriteBytes(_rig.Source, "saves/Copied/data/DistantHorizons.sqlite", EngineBKit.Sqlite("travels"));
        Kit.Write(_rig.Source, "saves/Copied/voxy/config.json", "{\"v\":1}");
        // no matching world in Origin: without the world copy this cache would be left behind

        var (plan, result) = await Run(Engine(), new ImportOptions { CopyWorlds = true });

        Assert.True(result.Succeeded);
        Assert.Equal(EngineBKit.Sqlite("travels"), File.ReadAllBytes(Dst("saves/Copied/data/DistantHorizons.sqlite")));
        Assert.Equal("{\"v\":1}", File.ReadAllText(Dst("saves/Copied/voxy/config.json")));
        Assert.True(Item(plan, CacheKind.DistantHorizons, "Copied").WillImport);
        Assert.Equal("travels with the world copy", Item(plan, CacheKind.DistantHorizons, "Copied").Reason);
        Assert.DoesNotContain(plan.Ops, o => o.Item == PlanItemKind.Cache);          // no second copy of it
    }

    // ------------------------------------------------------------ multiplayer (criterion 42)

    private void ServerFixture(string content = "server")
    {
        Mods();
        Kit.WriteBytes(_rig.Source, "Distant_Horizons_server_data/Hyp/aaa@minecraft@@overworld/DistantHorizons.sqlite", EngineBKit.Sqlite(content + "-ow"));
        Kit.WriteBytes(_rig.Source, "Distant_Horizons_server_data/Hyp/bbb@minecraft@@the_nether/DistantHorizons.sqlite", EngineBKit.Sqlite(content + "-nether"));
        Kit.Write(_rig.Source, "Distant_Horizons_server_data/Hyp/readme.txt", "extra file in the folder");
    }

    [Fact]
    public async Task AnAbsentMultiplayerFolderReceivesTheWholeServerFolder()
    {
        ServerFixture();
        var (plan, result) = await Run(Engine());

        Assert.True(result.Succeeded);
        Assert.Equal(EngineBKit.Sqlite("server-ow"), File.ReadAllBytes(Dst("Distant_Horizons_server_data/Hyp/aaa@minecraft@@overworld/DistantHorizons.sqlite")));
        Assert.Equal(EngineBKit.Sqlite("server-nether"), File.ReadAllBytes(Dst("Distant_Horizons_server_data/Hyp/bbb@minecraft@@the_nether/DistantHorizons.sqlite")));
        Assert.Equal("extra file in the folder", File.ReadAllText(Dst("Distant_Horizons_server_data/Hyp/readme.txt")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.ReusedCaches && l.Name == "Hyp (Distant Horizons)");
        Assert.Equal(2, plan.Caches.Count(c => c.Owner == "Hyp" && c.WillImport));     // one entry per level folder
    }

    [Fact]
    public async Task AnExistingNonEmptyDestinationFolderIsLeftByteUnchangedByDefault()
    {
        ServerFixture();
        Kit.WriteBytes(_rig.GameDir(), "Distant_Horizons_server_data/Hyp/mine@minecraft@@overworld/DistantHorizons.sqlite", EngineBKit.Sqlite("MINE"));
        var before = Kit.TreeHash(Dst("Distant_Horizons_server_data/Hyp"), mtimes: true);

        var (plan, result) = await Run(Engine());

        Assert.True(result.Succeeded);
        Assert.Equal(before, Kit.TreeHash(Dst("Distant_Horizons_server_data/Hyp"), mtimes: true));   // nothing merged in, nothing replaced
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name.StartsWith("Hyp") && l.Reason!.Contains("left alone"));
        Assert.False(Imported(plan, CacheKind.DistantHorizons, "Hyp", "aaa@minecraft@@overworld"));
    }

    [Fact]
    public async Task TheReplaceOptionBacksTheOldFolderUpFirstAndUndoPutsItBack()
    {
        ServerFixture();
        Kit.WriteBytes(_rig.GameDir(), "Distant_Horizons_server_data/Hyp/mine@minecraft@@overworld/DistantHorizons.sqlite", EngineBKit.Sqlite("MINE"));
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);
        var engine = Engine();
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var plan = await engine.PlanAsync(_rig.Request(options: new ImportOptions { ReplaceExistingCaches = true }), null, CancellationToken.None);

        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded);
        Assert.False(Directory.Exists(Dst("Distant_Horizons_server_data/Hyp/mine@minecraft@@overworld")));   // replaced as a whole
        Assert.True(File.Exists(Dst("Distant_Horizons_server_data/Hyp/aaa@minecraft@@overworld/DistantHorizons.sqlite")));
        var backups = Directory.GetDirectories(ImportLayout.Backup(_rig.Imports, plan.ImportId));
        var backup = Assert.Single(backups, b => Path.GetFileName(b).EndsWith("Hyp"));
        Assert.Equal(EngineBKit.Sqlite("MINE"), File.ReadAllBytes(Path.Combine(backup, "mine@minecraft@@overworld", "DistantHorizons.sqlite")));
        var journal = ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!;
        Assert.Contains(journal.Entries, e => e.Kind == JournalEntryKinds.Replaced && e.IsDirectory && e.Backup == backup);

        var undone = await engine.RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Null(undone.Error);
        Assert.Equal(EngineBKit.Sqlite("MINE"), File.ReadAllBytes(Dst("Distant_Horizons_server_data/Hyp/mine@minecraft@@overworld/DistantHorizons.sqlite")));
        Assert.False(Directory.Exists(Dst("Distant_Horizons_server_data/Hyp/aaa@minecraft@@overworld")));
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
    }

    [Fact]
    public async Task VoxyMultiplayerBaseIsCopiedAsIsConfigJsonUntouched()
    {
        Mods();
        var config = "{\"unusual\":   true,\n \"keys\":[3,1,2]}";                     // odd formatting: must survive byte for byte
        Kit.Write(_rig.Source, ".voxy/saves/play.example.net_25565/config.json", config);
        Kit.WriteBytes(_rig.Source, ".voxy/saves/play.example.net_25565/1a2b/storage/000012.sst", new byte[] { 1, 2, 3, 4 });
        Kit.Write(_rig.Source, ".voxy/saves/play.example.net_25565/1a2b/storage/CURRENT", "MANIFEST-000005\n");

        var (plan, result) = await Run(Engine());

        Assert.True(result.Succeeded);
        Assert.Equal(config, File.ReadAllText(Dst(".voxy/saves/play.example.net_25565/config.json")));
        Assert.Equal(new byte[] { 1, 2, 3, 4 }, File.ReadAllBytes(Dst(".voxy/saves/play.example.net_25565/1a2b/storage/000012.sst")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.ReusedCaches && l.Name == "play.example.net_25565 (Voxy)");
    }

    // ------------------------------------------------------------ mod-version compatibility (criterion 43)

    [Fact]
    public async Task ADhDisabledByTheKnownIncompatibleRuleLeavesItsCacheInTheSourceToRebuild()
    {
        // 1.21.11 ships Sodium 0.8.x: Distant Horizons 2.3.2 and older is imported turned off, so its data must not cross.
        Kit.WriteBytes(_rig.Source, "mods/dh.jar", Kit.FabricJar("distanthorizons", "2.3.2-b", name: "Distant Horizons"));
        World(_rig.Source, "W", "W", 1);
        World(_rig.GameDir("1.21.11"), "W", "W", 1);
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite", EngineBKit.Sqlite("w"));
        Kit.WriteBytes(_rig.Source, "Distant_Horizons_server_data/S/l/DistantHorizons.sqlite", EngineBKit.Sqlite("s"));
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var engine = Engine();

        var plan = await engine.PlanAsync(_rig.Request(target: "1.21.11"), null, CancellationToken.None);
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded);
        Assert.Equal(ModOutcome.CopiedDisabled, plan.Mods.Single(m => m.ModId == "distanthorizons").Outcome);
        Assert.False(File.Exists(Path.Combine(_rig.GameDir("1.21.11"), "saves", "W", "data", "DistantHorizons.sqlite")));
        Assert.False(Directory.Exists(Path.Combine(_rig.GameDir("1.21.11"), "Distant_Horizons_server_data")));
        Assert.All(plan.Caches, c => Assert.False(c.WillImport));
        Assert.All(plan.Caches, c => Assert.Contains("it will rebuild", c.Reason));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Reason!.Contains("turned off") && l.Reason.Contains("it will rebuild"));
    }

    private static ModDecision Dec(string id, string? version, ModOutcome outcome = ModOutcome.Copied, string? reason = null) =>
        new(id + ".jar", id, id, version, outcome, reason, outcome == ModOutcome.Copied ? id + ".jar" : null, "x");

    // The compatibility rules as pure functions of the resolved mod set.
    [Theory]
    [InlineData("2.3.3", ModOutcome.Copied, true)]          // same 2.x, copied enabled at the source's version
    [InlineData("1.7.0", ModOutcome.Copied, false)]         // DH 1.x data is never imported
    [InlineData("2.3.3", ModOutcome.CopiedDisabled, false)] // resolved disabled
    [InlineData("2.3.3", ModOutcome.CopiedAsBefore, false)] // it was off in the source
    [InlineData("2.3.3", ModOutcome.WrongMinecraft, false)] // not coming across at all
    [InlineData("2.3.3", ModOutcome.BuiltIn, false)]
    public async Task DistantHorizonsDataCrossesOnlyWhenDhWillLoadAtTheSameMajorAndANotOlderVersion(string version, ModOutcome outcome, bool crosses)
    {
        var plan = CraftedPlan(new[] { Dec("distanthorizons", version, outcome, "because") });
        Kit.WriteBytes(plan.Source.GameDir, "Distant_Horizons_server_data/S/l/DistantHorizons.sqlite", EngineBKit.Sqlite("x"));

        await new CacheContributor().ContributeAsync(plan, null!, new SourceFs(), CancellationToken.None);

        Assert.Equal(crosses, plan.Caches.Single().WillImport);
        if (!crosses) Assert.Contains("it will rebuild", plan.Caches.Single().Reason);
    }

    [Fact]
    public async Task DhNewerInTheDestinationThanTheSourceIsFineAndOlderIsNot()
    {
        // two DH jars in the source: the newest one is what the data came from; only an enabled one at least as new can read it
        var older = CraftedPlan(new[] { Dec("distanthorizons", "2.4.0"), Dec("distanthorizons", "2.3.0", ModOutcome.Duplicate) });
        Kit.WriteBytes(older.Source.GameDir, "Distant_Horizons_server_data/S/l/DistantHorizons.sqlite", EngineBKit.Sqlite("x"));
        await new CacheContributor().ContributeAsync(older, null!, new SourceFs(), CancellationToken.None);
        Assert.True(older.Caches.Single().WillImport);

        var tooOld = CraftedPlan(new[] { Dec("distanthorizons", "2.2.0"), Dec("distanthorizons", "2.4.0", ModOutcome.WrongMinecraft) });
        Kit.WriteBytes(tooOld.Source.GameDir, "Distant_Horizons_server_data/S/l/DistantHorizons.sqlite", EngineBKit.Sqlite("x"));
        await new CacheContributor().ContributeAsync(tooOld, null!, new SourceFs(), CancellationToken.None);
        Assert.False(tooOld.Caches.Single().WillImport);
        Assert.Contains("older", tooOld.Caches.Single().Reason);
    }

    [Fact]
    public async Task VoxyDataCrossesWhenVoxyIsEnabledAtTheVersionThatWroteIt()
    {
        var plan = CraftedPlan(new[] { Dec("voxy", "0.1.5") });
        Kit.Write(plan.Source.GameDir, ".voxy/saves/host_25565/config.json", "{}");

        await new CacheContributor().ContributeAsync(plan, null!, new SourceFs(), CancellationToken.None);

        Assert.True(plan.Caches.Single().WillImport);
    }

    [Fact]
    public async Task VoxyDataStaysWhenTheEnabledVoxyIsNotTheSourcesVersion()
    {
        // The source ran 0.1.6; only 0.1.5 is imported turned on: the version strings differ, so the data must not be used.
        var plan = CraftedPlan(new[] { Dec("voxy", "0.1.5"), Dec("voxy", "0.1.6", ModOutcome.WrongMinecraft, "built for another version") });
        Kit.Write(plan.Source.GameDir, ".voxy/saves/host_25565/config.json", "{}");

        await new CacheContributor().ContributeAsync(plan, null!, new SourceFs(), CancellationToken.None);

        Assert.False(plan.Caches.Single().WillImport);
        Assert.Contains("different version", plan.Caches.Single().Reason);
        Assert.Contains("it will rebuild", plan.Caches.Single().Reason);
    }

    [Fact]
    public async Task VoxyAndDhAreSkippedWhenTheirModIsNotImportedAtAll()
    {
        var plan = CraftedPlan(Array.Empty<ModDecision>());
        Kit.Write(plan.Source.GameDir, ".voxy/saves/host_25565/config.json", "{}");
        Kit.WriteBytes(plan.Source.GameDir, "Distant_Horizons_server_data/S/l/DistantHorizons.sqlite", EngineBKit.Sqlite("x"));

        await new CacheContributor().ContributeAsync(plan, null!, new SourceFs(), CancellationToken.None);

        Assert.All(plan.Caches, c => Assert.False(c.WillImport));
        Assert.All(plan.Caches, c => Assert.Contains("it will rebuild", c.Reason));
    }

    private ImportPlan CraftedPlan(IEnumerable<ModDecision> mods)
    {
        var source = Path.Combine(_rig.Root, "crafted-" + Guid.NewGuid().ToString("N")[..6]);
        Directory.CreateDirectory(source);
        var plan = new ImportPlan
        {
            ImportId = ProfileIdForTest(), TargetVersion = "1.21.1", TargetGameDir = _rig.GameDir(), Options = new ImportOptions(),
            Source = new DetectedInstance { Kind = SourceKind.Folder, Name = "x", GameDir = source, McVersion = "1.21.1" },
        };
        plan.Mods.AddRange(mods);
        return plan;
    }

    private static string ProfileIdForTest() => OriginLauncher.App.Core.Profiles.ProfileRules.NewId();

    // ------------------------------------------------------------ WAL groups (criterion 44)

    private void DhWorld(string world = "W")
    {
        Mods();
        World(_rig.Source, world, world, 1);
        World(_rig.GameDir(), world, world, 1);
    }

    [Fact]
    public async Task ADatabaseGroupIsCopiedByteEqualAsAGroup()
    {
        DhWorld();
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite", EngineBKit.Sqlite("main"));
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite-wal", Encoding.ASCII.GetBytes("WAL-CONTENT"));
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite-shm", Encoding.ASCII.GetBytes("SHM-CONTENT"));

        var (_, result) = await Run(Engine());

        Assert.True(result.Succeeded);
        foreach (var name in new[] { "", "-wal", "-shm" })
            Assert.Equal(File.ReadAllBytes(Src("saves/W/data/DistantHorizons.sqlite" + name)), File.ReadAllBytes(Dst("saves/W/data/DistantHorizons.sqlite" + name)));
    }

    [Fact]
    public async Task ALoneWalWithoutItsDatabaseIsSkipped()
    {
        DhWorld();
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite-wal", Encoding.ASCII.GetBytes("ORPHAN"));

        var (plan, result) = await Run(Engine());

        Assert.True(result.Succeeded);
        Assert.False(File.Exists(Dst("saves/W/data/DistantHorizons.sqlite-wal")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Reason!.Contains("no database"));
    }

    [Fact]
    public async Task AnExistingDestinationGroupIsKeptByDefaultAndReplacedOnlyWholeAfterABackupWhenAsked()
    {
        DhWorld();
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite", EngineBKit.Sqlite("NEW"));        // the source has no -wal / -shm
        Kit.WriteBytes(_rig.GameDir(), "saves/W/data/DistantHorizons.sqlite", EngineBKit.Sqlite("OLD"));
        Kit.WriteBytes(_rig.GameDir(), "saves/W/data/DistantHorizons.sqlite-wal", Encoding.ASCII.GetBytes("OLD-WAL"));
        Kit.WriteBytes(_rig.GameDir(), "saves/W/data/DistantHorizons.sqlite-shm", Encoding.ASCII.GetBytes("OLD-SHM"));
        var untouched = Kit.TreeHash(Dst("saves/W/data"), mtimes: true);

        var (_, kept) = await Run(Engine());
        Assert.True(kept.Succeeded);
        Assert.Equal(untouched, Kit.TreeHash(Dst("saves/W/data"), mtimes: true));               // default: a populated cache is never overwritten

        var engine = Engine();
        var plan = await engine.PlanAsync(_rig.Request(options: new ImportOptions { ReplaceExistingCaches = true }), null, CancellationToken.None);
        var replaced = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(replaced.Succeeded);
        Assert.Equal(EngineBKit.Sqlite("NEW"), File.ReadAllBytes(Dst("saves/W/data/DistantHorizons.sqlite")));
        Assert.False(File.Exists(Dst("saves/W/data/DistantHorizons.sqlite-wal")));               // stale journal files can't outlive their database
        Assert.False(File.Exists(Dst("saves/W/data/DistantHorizons.sqlite-shm")));
        var backups = Directory.GetFiles(ImportLayout.Backup(_rig.Imports, plan.ImportId)).Select(File.ReadAllBytes).Select(Encoding.ASCII.GetString).ToList();
        Assert.Equal(3, backups.Count);                                                           // all three old files saved first
        Assert.Contains("OLD-WAL", backups);
        Assert.Contains("OLD-SHM", backups);

        var undone = await engine.RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Null(undone.Error);
        Assert.Equal(untouched, Kit.TreeHash(Dst("saves/W/data"), mtimes: true));                // the whole old group is back
    }

    // ------------------------------------------------------------ locks (criterion 45) — Windows only

    [WindowsOnlyFact]
    public async Task ALockedDatabaseOrWalOrVoxyLockIsSkippedAsInUse()
    {
        DhWorld();
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite", EngineBKit.Sqlite("main"));
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite-wal", Encoding.ASCII.GetBytes("wal"));
        Kit.Write(_rig.Source, "saves/W/voxy/config.json", "{}");
        Kit.Write(_rig.Source, "saves/W/voxy/1/storage/LOCK", "");
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");

        foreach (var locked in new[] { "saves/W/data/DistantHorizons.sqlite", "saves/W/data/DistantHorizons.sqlite-wal", "saves/W/voxy/1/storage/LOCK" })
        {
            using var hold = new FileStream(Src(locked), FileMode.Open, FileAccess.Read, FileShare.None);
            var plan = await Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);
            var isVoxy = locked.Contains("voxy");
            var cache = isVoxy ? CacheKind.Voxy : CacheKind.DistantHorizons;
            Assert.False(Item(plan, cache, "W").WillImport, locked);
            Assert.Contains("In use", Item(plan, cache, "W").Reason);
        }
        // released: it crosses
        var free = await Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.True(Item(free, CacheKind.DistantHorizons, "W").WillImport);
        Assert.True(Item(free, CacheKind.Voxy, "W").WillImport);
    }

    [WindowsOnlyFact]
    public async Task ALockThatAppearsBetweenTheCopyAndTheFinalProbeDiscardsTheStagedCopy()
    {
        DhWorld();
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite", EngineBKit.Sqlite("main"));
        Kit.Write(_rig.Source, "saves/W/voxy/config.json", "{}");
        Kit.Write(_rig.Source, "saves/W/voxy/1/storage/LOCK", "");
        var holds = new List<FileStream>();
        var contributor = new CacheContributor
        {
            AfterStaged = label =>
            {
                // "the game opens the database right after we copied it"
                var path = label.Contains("Voxy") ? Src("saves/W/voxy/1/storage/LOCK") : Src("saves/W/data/DistantHorizons.sqlite");
                holds.Add(new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.None));
            },
        };
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var engine = Engine(contributor);
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.True(Item(plan, CacheKind.DistantHorizons, "W").WillImport);

        ImportResult result;
        try
        {
            result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        }
        finally
        {
            foreach (var hold in holds) hold.Dispose();
        }
        Assert.Equal(2, holds.Count);                                                           // the hook fired for both caches

        Assert.True(result.Succeeded);
        Assert.False(File.Exists(Dst("saves/W/data/DistantHorizons.sqlite")));                 // the staged copy was discarded
        Assert.False(Directory.Exists(Dst("saves/W/voxy")));
        Assert.Contains("In use", result.SummaryText);
        Assert.False(Directory.EnumerateFiles(_rig.Imports, "DistantHorizons*", SearchOption.AllDirectories).Any());   // and not left in staging
    }

    // ------------------------------------------------------------ cancel mid-cache (extends 50), no SQLite engine (46)

    [Fact]
    public async Task CancellingMidCacheRollsEverythingBackToAnIdenticalDestination()
    {
        DhWorld();
        Kit.WriteBytes(_rig.Source, "saves/W/data/DistantHorizons.sqlite", EngineBKit.Sqlite("main"));
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        Kit.WriteBytes(_rig.Source, "Distant_Horizons_server_data/Hyp/l/DistantHorizons.sqlite", EngineBKit.Sqlite("srv"));
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);
        var settingsBefore = _rig.Settings.FileHash();
        using var cts = new CancellationTokenSource();
        var engine = Engine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);

        var result = await engine.ApplyAsync(plan, _rig.Settings,
            new SyncProgress(p => { if (p.Stage == ImportStage.Caches && p.Item == "Hyp (Distant Horizons)") cts.Cancel(); }), cts.Token);

        Assert.True(result.Cancelled);
        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal(settingsBefore, _rig.Settings.FileHash());
        Assert.False(Directory.Exists(ImportLayout.Staging(_rig.Imports, plan.ImportId)));
    }

    [Fact]
    public void NoSqliteEngineIsReferencedAnywhereInCoreImport()
    {
        var dir = AppContext.BaseDirectory;
        string? import = null;
        while (dir != null && import == null)
        {
            var candidate = Path.Combine(dir, "src", "OriginLauncher.App", "Core", "Import");
            if (Directory.Exists(candidate)) import = candidate;
            dir = Path.GetDirectoryName(dir);
        }
        Assert.NotNull(import);
        var pattern = new System.Text.RegularExpressions.Regex(@"sqlite(connection|command)|Microsoft\.Data\.Sqlite",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        foreach (var file in Directory.EnumerateFiles(import!, "*.cs", SearchOption.AllDirectories))
            Assert.False(pattern.IsMatch(File.ReadAllText(file)), Path.GetFileName(file));
        Assert.DoesNotContain(File.ReadAllText(Path.Combine(import!, "..", "..", "OriginLauncher.App.csproj")), "Sqlite", StringComparison.OrdinalIgnoreCase);
    }

    // ------------------------------------------------------------ level.dat reader

    [Fact]
    public void TheLevelReaderReadsBothSeedLocationsAndRefusesDamagedOrHugeFiles()
    {
        var dir = Path.Combine(_rig.Root, "lv");
        Kit.WriteBytes(dir, "modern/level.dat", EngineBKit.LevelDat("Αβγ World", -987654321012345L));
        Kit.WriteBytes(dir, "legacy/level.dat", EngineBKit.LevelDat("Old", 42, modern: false));
        var fs = new SourceFs();

        var modern = LevelDat.Read(Path.Combine(dir, "modern", "level.dat"), fs)!;
        Assert.Equal(("Αβγ World", -987654321012345L), (modern.LevelName, modern.Seed));
        var legacy = LevelDat.Read(Path.Combine(dir, "legacy", "level.dat"), fs)!;
        Assert.Equal(("Old", 42L), (legacy.LevelName, legacy.Seed));

        var truncated = File.ReadAllBytes(Path.Combine(dir, "modern", "level.dat"));
        Kit.WriteBytes(dir, "cut/level.dat", truncated[..(truncated.Length / 2)]);
        Assert.Null(LevelDat.Read(Path.Combine(dir, "cut", "level.dat"), fs));
        Kit.Write(dir, "text/level.dat", "not gzip at all");
        Assert.Null(LevelDat.Read(Path.Combine(dir, "text", "level.dat"), fs));
        Assert.Null(LevelDat.Parse(new byte[] { 10, 0, 0, 10, 0, 4, (byte)'D', (byte)'a', (byte)'t', (byte)'a', 9, 0, 1, (byte)'x', 12, 0, 0, 0x7f, 0xff, 0xff, 0xff }));

        // a world with no seed is never a match
        Assert.False(new WorldIdentity("A", null).Matches(new WorldIdentity("A", null)));
        Assert.True(new WorldIdentity("A", 1).Matches(new WorldIdentity("A", 1)));
    }
}
