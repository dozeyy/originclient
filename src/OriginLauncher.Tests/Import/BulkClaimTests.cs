using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Caches;
using Xunit;

namespace OriginLauncher.Tests.Import;

// A setup skipped mid-run (its game started) or whose copy fails on the source side must not take things with it:
// what later setups deferred to it (an identical pack, a create-only config file, a cache) still comes over, from the
// next setup that has it, exactly once.
public class BulkClaimTests : IDisposable
{
    private readonly BulkFixture _f = new();

    public void Dispose() => _f.Dispose();

    private const string DhFile = "Distant_Horizons_server_data/Hyp/aaa@minecraft@@overworld/DistantHorizons.sqlite";

    // Three setups on 1.21.1 with the same pack, the same config path (different content) and the same server's cache.
    // Three launchers, every grid cell ticked: each brings its mods (one setup per launcher brings mods), so their
    // configs and caches all come into play.
    private (DetectedInstance A, DetectedInstance B, DetectedInstance C) ThreeSharing()
    {
        var a = _f.Add("A", playtimeHours: 30);
        var b = _f.Add("B", playtimeHours: 20, kind: SourceKind.Modrinth);
        var c = _f.Add("C", playtimeHours: 10, kind: SourceKind.CurseForge);
        foreach (var s in new[] { a, b, c })
        {
            Kit.Write(s.GameDir, "config/somemod.json", $"{{\"from\":\"{s.Name}\"}}");
            Kit.WriteBytes(s.GameDir, "mods/dh.jar", Kit.FabricJar("distanthorizons", "2.3.3-b", name: "Distant Horizons"));
            Kit.WriteBytes(s.GameDir, DhFile, EngineBKit.Sqlite("cache-" + s.Name));
        }
        return (a, b, c);
    }

    private ImportEngine Engine() => _f.Engine(new IImportPlanContributor[] { new CacheContributor() });

    private Task<BulkImportPlan> Plan(ImportEngine engine) => _f.Plan(engine, _f.Request(grid: _f.AllTicked()));

    private static SetupOutcome Outcome(BulkImportResult result, string name) =>
        result.Versions.SelectMany(v => v.Setups).Single(s => s.Setup.Name == name);

    [Fact]
    public async Task AWinnerSkippedMidRunPassesItsPackConfigAndCacheToTheNextSetup()
    {
        var (a, b, _) = ThreeSharing();
        var before = Kit.TreeHash(_f.Rig.Instances, mtimes: true);
        var engine = Engine();
        var plan = await Plan(engine);
        Assert.Same(a, plan.Groups.Single().Winner);
        _f.Rig.RunningDirs.Add(a.GameDir);                                          // the winner's game starts before its turn

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(SetupResultKind.Skipped, Outcome(result, "A").Kind);
        Assert.Equal(File.ReadAllBytes(_f.Dir(b, "resourcepacks/Faithful.zip")), File.ReadAllBytes(_f.Target("1.21.1", "resourcepacks/Faithful.zip")));
        Assert.Single(Directory.GetFiles(_f.Target("1.21.1", "resourcepacks"), "Faithful*"));          // once, no "(imported)"
        Assert.Equal("{\"from\":\"B\"}", File.ReadAllText(_f.Target("1.21.1", "config/somemod.json"))); // B's, not C's
        Assert.Equal(EngineBKit.Sqlite("cache-B"), File.ReadAllBytes(_f.Target("1.21.1", DhFile)));    // B's cache, not C's
        Assert.Contains(Outcome(result, "B").Lines!, l => l.Name == "Faithful.zip" && l.Reason!.StartsWith("came over from this setup", StringComparison.Ordinal));
        Assert.DoesNotContain(Outcome(result, "C").Lines!, l => l.Reason?.StartsWith("came over from this setup", StringComparison.Ordinal) == true);

        // And it all still undoes as one run.
        _f.Rig.RunningDirs.Clear();
        var undone = await engine.RollbackAsync(plan.RunId, _f.Rig.Settings);
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.Equal(before, Kit.TreeHash(_f.Rig.Instances, mtimes: true));
    }

    [Fact]
    public async Task WhenTheFirstTwoAreSkippedTheThirdBringsItOnce()
    {
        var (a, b, c) = ThreeSharing();
        var engine = Engine();
        var plan = await Plan(engine);
        _f.Rig.RunningDirs.Add(a.GameDir);
        _f.Rig.RunningDirs.Add(b.GameDir);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("{\"from\":\"C\"}", File.ReadAllText(_f.Target("1.21.1", "config/somemod.json")));
        Assert.Equal(EngineBKit.Sqlite("cache-C"), File.ReadAllBytes(_f.Target("1.21.1", DhFile)));
        Assert.Equal(File.ReadAllBytes(_f.Dir(c, "resourcepacks/Faithful.zip")), File.ReadAllBytes(_f.Target("1.21.1", "resourcepacks/Faithful.zip")));
    }

    [Fact]
    public async Task AClaimantsPackThatVanishedFromItsSourceComesFromTheNextSetup()
    {
        var (a, b, _) = ThreeSharing();
        var engine = Engine();
        var plan = await Plan(engine);
        File.Delete(_f.Dir(a, "resourcepacks/Faithful.zip"));                       // a SOURCE problem in the winner

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(SetupResultKind.Imported, Outcome(result, "A").Kind);          // the rest of A still came over
        Assert.Equal("{\"from\":\"A\"}", File.ReadAllText(_f.Target("1.21.1", "config/somemod.json")));
        Assert.Equal(File.ReadAllBytes(_f.Dir(b, "resourcepacks/Faithful.zip")), File.ReadAllBytes(_f.Target("1.21.1", "resourcepacks/Faithful.zip")));
    }

    [Fact]
    public async Task StandInsNeverRunWhenTheClaimantDidItsJobAndNeverCountTowardsTheSize()
    {
        ThreeSharing();
        var engine = Engine();
        var plan = await Plan(engine);
        var standIns = plan.AllSetups.SelectMany(s => s.Plan.Ops).Where(o => o.Fallback).ToList();
        Assert.NotEmpty(standIns);
        Assert.All(plan.AllSetups, s => Assert.Equal(s.Plan.Ops.Where(o => !o.Fallback).Sum(o => o.Bytes), s.Plan.BytesToCopy));

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("{\"from\":\"A\"}", File.ReadAllText(_f.Target("1.21.1", "config/somemod.json")));
        Assert.Equal(EngineBKit.Sqlite("cache-A"), File.ReadAllBytes(_f.Target("1.21.1", DhFile)));
        Assert.DoesNotContain(result.Versions.SelectMany(v => v.Setups).SelectMany(s => s.Lines!),
            l => l.Reason?.StartsWith("came over from this setup", StringComparison.Ordinal) == true);
    }
}
