using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Caches;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Regression tests for the re-review of the bulk engine (d1db1c80), named after what must stay true.
public class BulkReviewRound2Tests : IDisposable
{
    private readonly BulkFixture _f = new();

    public void Dispose() => _f.Dispose();

    private const string DhFile = "Distant_Horizons_server_data/Hyp/aaa@minecraft@@overworld/DistantHorizons.sqlite";

    private DetectedInstance WithCache(string name, string version, long hours)
    {
        var s = _f.Add(name, version, playtimeHours: hours);
        Kit.WriteBytes(s.GameDir, "mods/dh.jar", Kit.FabricJar("distanthorizons", "2.3.3-b", name: "Distant Horizons"));
        Kit.WriteBytes(s.GameDir, DhFile, EngineBKit.Sqlite("cache-" + name));
        return s;
    }

    private static SetupOutcome Outcome(BulkImportResult result, string name) =>
        result.Versions.SelectMany(v => v.Setups).Single(s => s.Setup.Name == name);

    // ------------------------------------------------------------ 1: a released claim never crosses version groups

    [Fact]
    public async Task R2_1_ASkippedCacheOwnerInOneVersionNeverFiresAStandInInAnother()
    {
        var a = WithCache("A", "1.21.1", 40);   // 1.21.1's owner: skipped mid-run
        WithCache("B", "1.21.1", 30);           // 1.21.1's stand-in: must land
        WithCache("C", "1.20.4", 20);           // 1.20.4's owner: copies its own
        WithCache("D", "1.20.4", 10);           // 1.20.4's stand-in: must stay idle
        var engine = _f.Engine(new IImportPlanContributor[] { new CacheContributor() });
        var plan = await _f.Plan(engine, _f.Request(options: new ImportOptions { ReplaceExistingCaches = true }));
        _f.Rig.RunningDirs.Add(a.GameDir);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(EngineBKit.Sqlite("cache-B"), File.ReadAllBytes(_f.Target("1.21.1", DhFile)));
        Assert.Equal(EngineBKit.Sqlite("cache-C"), File.ReadAllBytes(_f.Target("1.20.4", DhFile)));    // never replaced by D's
        Assert.DoesNotContain(Outcome(result, "D").Lines!, l => l.Reason?.StartsWith("came over from this setup", StringComparison.Ordinal) == true);
        Assert.False(Directory.Exists(ImportLayout.Backup(_f.Rig.Imports, plan.RunId)));                // nothing was backed up and replaced
    }

    [Fact]
    public void R2_1_TheSameCacheInTwoTargetFoldersIsTwoClaims()
    {
        var identity = "DistantHorizons|Multiplayer|Hyp|";
        Assert.NotEqual(DestinationLedger.CacheKey(_f.Rig.GameDir("1.21.1"), identity), DestinationLedger.CacheKey(_f.Rig.GameDir("1.20.4"), identity));
        Assert.Equal(DestinationLedger.CacheKey(_f.Rig.GameDir("1.21.1"), identity), DestinationLedger.CacheKey(_f.Rig.GameDir("1.21.1"), identity));
    }

    // ------------------------------------------------------------ 2: a "replaced" entry is on the disk before the move

    [Fact]
    public void R2_2_AReplacedEntryIsForcedToTheDiskBeforeTheOriginalMoves()
    {
        var src = Kit.Write(_f.Rig.Root, "src/options.txt", "new");
        var dest = Kit.Write(_f.Rig.Root, "dest/options.txt", "old");
        var created = Path.Combine(_f.Rig.Root, "dest", "fresh.txt");
        using var tx = new ImportTransaction(_f.Rig.Imports, _f.Rig.Root, new string('e', 32), () => DateTime.UtcNow, "x", "1.21.1");
        tx.Begin();

        tx.CopyFile(src, created, false);                                            // a new file: buffered, no forced sync
        var afterCreate = tx.DiskSyncs;
        tx.CopyFile(src, dest, false);                                               // a replace: synced at once

        Assert.Equal(0, afterCreate);
        Assert.Equal(1, tx.DiskSyncs);
        Assert.Equal("new", File.ReadAllText(dest));
        tx.Rollback(null);
        Assert.Equal("old", File.ReadAllText(dest));
    }

    // ------------------------------------------------------------ 3: a landed stand-in leaves no contradicting line

    [Fact]
    public async Task R2_3_WhenAStandInLandsTheAlreadyBroughtOverLineIsGone()
    {
        var a = WithCache("A", "1.21.1", 30);
        WithCache("B", "1.21.1", 10);
        var engine = _f.Engine(new IImportPlanContributor[] { new CacheContributor() });
        var plan = await _f.Plan(engine);
        var bPlanned = plan.AllSetups.Single(s => s.Setup.Name == "B").Plan.Lines;
        Assert.Contains(bPlanned, l => l.Reason == "already brought over from Prism: A");          // the plan said so...
        Assert.Contains(bPlanned, l => l.Reason == "Another of your setups already brings this one.");
        _f.Rig.RunningDirs.Add(a.GameDir);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var lines = Outcome(result, "B").Lines!;
        Assert.DoesNotContain(lines, l => l.Reason == "already brought over from Prism: A");      // ...the result doesn't
        Assert.DoesNotContain(lines, l => l.Reason == "Another of your setups already brings this one.");
        Assert.Contains(lines, l => l.Name == "Faithful.zip" && l.Reason!.StartsWith("came over from this setup", StringComparison.Ordinal));
        Assert.Contains(lines, l => l.Kind == PlanItemKind.Cache && l.Reason!.StartsWith("came over from this setup", StringComparison.Ordinal));
        var text = BulkSummary.Render(result);
        Assert.DoesNotContain("already brought over from Prism: A", text);
    }
}
