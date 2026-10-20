using System.Diagnostics;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import;
using Xunit;
using Xunit.Abstractions;

namespace OriginLauncher.Tests.Import;

// A huge old setup: 3,000 mods, a world of 5,000 region files in nested dimensions, and one 64 MB file. Measures plan
// and apply (printed, never asserted against a number: timings depend on the disk) and proves it all lands whole and
// that undo puts it all back. Runs alone so the numbers aren't shared with other tests' disk work.
[Collection(ImportTimingCollection.Name)]
public class ImportScaleTests : IDisposable
{
    private readonly Rig _rig = new();
    private readonly ITestOutputHelper _out;

    public ImportScaleTests(ITestOutputHelper output) => _out = output;

    public void Dispose() => _rig.Dispose();

    [Fact]
    public async Task AHugeSetupImportsWholeAndUndoesWhole()
    {
        const int mods = 3000, regions = 5000;
        var build = Stopwatch.StartNew();
        TypicalSource.Populate(_rig.Source, withMods: false);
        for (var i = 0; i < mods; i++)
            Kit.WriteBytes(_rig.Source, $"mods/mod-{i:D4}.jar", Kit.FabricJar($"mod{i:D4}", "1.0.0", minecraft: "~1.21.1"));
        var dims = new[] { "region", "DIM-1/region", "DIM1/region", "dimensions/a/b/region", "entities", "poi" };
        for (var i = 0; i < regions; i++)
            Kit.WriteBytes(_rig.Source, $"saves/Big World/{dims[i % dims.Length]}/r.{i}.0.mca", new byte[4096]);
        Kit.Write(_rig.Source, "saves/Big World/level.dat", "LEVEL");
        var big = new byte[64 * 1024 * 1024];
        new Random(7).NextBytes(big);
        Kit.WriteBytes(_rig.Source, "saves/Big World/data/huge.dat", big);
        var sourceBefore = Kit.TreeHash(_rig.Source, mtimes: true);
        _out.WriteLine($"built the fake source in {build.ElapsedMilliseconds} ms");

        var engine = _rig.Engine();
        var plan = Stopwatch.StartNew();
        var p = await engine.PlanAsync(_rig.Request(options: new ImportOptions { CopyWorlds = true }), null, CancellationToken.None);
        plan.Stop();
        Assert.True(p.CanApply, string.Join(" ", p.Blocked));

        var apply = Stopwatch.StartNew();
        var result = await engine.ApplyAsync(p, _rig.Settings, null, CancellationToken.None);
        apply.Stop();
        Assert.True(result.Succeeded, result.Error);

        var profileMods = InstanceLayout.ModsDir(_rig.Instances, "1.21.1");
        Assert.Equal(mods, Directory.GetFiles(profileMods, "mod-*.jar").Length);
        var world = Path.Combine(_rig.GameDir(), "saves", "Big World");
        Assert.Equal(regions, Directory.GetFiles(world, "*.mca", SearchOption.AllDirectories).Length);
        Assert.Equal(big.Length, new FileInfo(Path.Combine(world, "data", "huge.dat")).Length);
        Assert.Equal(sourceBefore, Kit.TreeHash(_rig.Source, mtimes: true));

        var undo = Stopwatch.StartNew();
        var undone = await engine.RollbackAsync(p.ImportId, _rig.Settings);
        undo.Stop();
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.False(Directory.Exists(world));
        Assert.False(Directory.Exists(profileMods));

        _out.WriteLine($"plan {plan.ElapsedMilliseconds} ms, apply {apply.ElapsedMilliseconds} ms, undo {undo.ElapsedMilliseconds} ms "
            + $"({mods} mods, {regions} region files, one {big.Length / (1024 * 1024)} MB file)");
    }
}
