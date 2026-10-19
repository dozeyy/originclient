using System.Diagnostics;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Caches;
using OriginLauncher.App.Core.Import.GameFiles;
using Xunit;
using Xunit.Abstractions;

namespace OriginLauncher.Tests.Import;

// B19: a big scan plans in bounded time and the panel starts filling in at once. 50 realistic setups (each with
// 30 extra mod jars) across 6 supported and 2 unsupported versions, game-file reuse against a fake Mojang, caches.
public class BulkPerfTests : IDisposable
{
    private readonly BulkFixture _f = new();
    private readonly ITestOutputHelper _out;

    public BulkPerfTests(ITestOutputHelper output) => _out = output;

    public void Dispose() => _f.Dispose();

    [Fact]
    public async Task FiftySetupsPlanWithinFifteenSecondsAndTheFirstArrivesWithinTwo()
    {
        var versions = new[] { "1.21.11", "1.21.4", "1.21.1", "1.20.4", "1.20.1", "1.16.5", "1.21.5", "1.19.2" }; // last two: unsupported
        var build = Stopwatch.StartNew();
        for (var i = 0; i < 50; i++)
        {
            var setup = _f.Add($"Setup {i:D2}", versions[i % versions.Length], kind: (SourceKind)(i % 4), playtimeHours: 50 - i);
            for (var j = 0; j < 30; j++)
                Kit.WriteBytes(setup.GameDir, $"mods/extra-{j:D2}.jar",
                    Kit.FabricJar($"extra{j}", $"1.{i}.{j}", minecraft: j % 3 == 0 ? "*" : "~" + setup.McVersion));
        }
        var mojang = new Mojang();
        var official = Path.Combine(_f.Rig.Root, "official-mc");
        mojang.WriteOfficial(official);
        build.Stop();
        var engine = _f.Engine(new IImportPlanContributor[]
        {
            new GameFileContributor(new GameFileRoots { InstancesRoot = _f.Rig.Instances, OfficialMinecraftDir = official }, mojang.Http),
            new CacheContributor(),
        });

        var watch = new Stopwatch();
        long firstPlanned = -1;
        var progress = new SyncBulkProgress<BulkPlanProgress>(p =>
        {
            if (p.Planned != null && firstPlanned < 0) firstPlanned = watch.ElapsedMilliseconds;
        });
        watch.Start();
        var plan = await engine.PlanBulkAsync(_f.Request(), progress, CancellationToken.None);
        watch.Stop();

        _out.WriteLine($"fixture: 50 setups x (typical + 30 jars) built in {build.ElapsedMilliseconds} ms");
        _out.WriteLine($"plan: {watch.ElapsedMilliseconds} ms total, first setup planned after {firstPlanned} ms");
        _out.WriteLine($"groups {plan.Groups.Count}, planned setups {plan.AllSetups.Count()}, skipped {plan.Skipped.Count}, metadata requests {mojang.Http.Requests.Count}");

        Assert.Equal(6, plan.Groups.Count);
        Assert.Equal(50, plan.AllSetups.Count() + plan.Skipped.Count);
        Assert.Equal(12, plan.Skipped.Count(s => s.Kind == SkipKind.Unsupported));
        Assert.True(mojang.Http.Requests.Count <= 2 + 3 * plan.Groups.Count);
        Assert.InRange(firstPlanned, 0, 2000);
        Assert.InRange(watch.ElapsedMilliseconds, 0, 15000);
    }
}
