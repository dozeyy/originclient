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

    // Each version's second setup is another launcher's, and every grid cell is ticked: both bring their mods (DH).
    private DetectedInstance WithCache(string name, string version, long hours, SourceKind kind = SourceKind.Prism)
    {
        var s = _f.Add(name, version, playtimeHours: hours, kind: kind);
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
        WithCache("B", "1.21.1", 30, SourceKind.Modrinth); // 1.21.1's stand-in: must land
        WithCache("C", "1.20.4", 20);           // 1.20.4's owner: copies its own
        WithCache("D", "1.20.4", 10, SourceKind.Modrinth); // 1.20.4's stand-in: must stay idle
        var engine = _f.Engine(new IImportPlanContributor[] { new CacheContributor() });
        var plan = await _f.Plan(engine, _f.Request(options: new ImportOptions { ReplaceExistingCaches = true }, grid: _f.AllTicked()));
        _f.Rig.RunningDirs.Add(a.GameDir);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(EngineBKit.Sqlite("cache-B"), File.ReadAllBytes(_f.Target("1.21.1", DhFile)));
        Assert.Equal(EngineBKit.Sqlite("cache-C"), File.ReadAllBytes(_f.Target("1.20.4", DhFile)));    // never replaced by D's
        Assert.DoesNotContain(Outcome(result, "D").Lines!, l => l.Reason?.StartsWith("came over from this setup", StringComparison.Ordinal) == true);
        Assert.False(Directory.Exists(ImportLayout.Backup(_f.Rig.Imports, plan.RunId)));                // nothing was backed up and replaced
    }

    [Fact]
    public async Task R2_1_ASkippedOwnerInOneVersionNeverMakesAnotherVersionsStandInReplaceItsOwnersCopy()
    {
        var a = WithCache("A", "1.21.1", 40);   // 1.21.1: its only setup, skipped mid-run (released, nobody stands in)
        WithCache("C", "1.20.4", 20);           // 1.20.4's owner: copies its own
        WithCache("D", "1.20.4", 10, SourceKind.Modrinth); // 1.20.4's stand-in: must stay idle
        var engine = _f.Engine(new IImportPlanContributor[] { new CacheContributor() });
        var plan = await _f.Plan(engine, _f.Request(options: new ImportOptions { ReplaceExistingCaches = true }, grid: _f.AllTicked()));
        _f.Rig.RunningDirs.Add(a.GameDir);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(EngineBKit.Sqlite("cache-C"), File.ReadAllBytes(_f.Target("1.20.4", DhFile)));
        Assert.DoesNotContain(Outcome(result, "D").Lines!, l => l.Reason?.StartsWith("came over from this setup", StringComparison.Ordinal) == true);
    }

    [Fact]
    public async Task R2_1_AStandInThatLandedInOneVersionNeverBlocksAGenuineReleaseInAnother()
    {
        var a = WithCache("A", "1.21.1", 40);
        WithCache("B", "1.21.1", 30, SourceKind.Modrinth); // lands for A
        var c = WithCache("C", "1.20.4", 20);
        WithCache("D", "1.20.4", 10, SourceKind.Modrinth); // must land for C
        var engine = _f.Engine(new IImportPlanContributor[] { new CacheContributor() });
        var plan = await _f.Plan(engine, _f.Request(grid: _f.AllTicked()));
        _f.Rig.RunningDirs.Add(a.GameDir);
        _f.Rig.RunningDirs.Add(c.GameDir);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(EngineBKit.Sqlite("cache-B"), File.ReadAllBytes(_f.Target("1.21.1", DhFile)));
        Assert.Equal(EngineBKit.Sqlite("cache-D"), File.ReadAllBytes(_f.Target("1.20.4", DhFile)));
    }

    [Fact]
    public void R2_1_TheSameCacheInTwoTargetFoldersIsTwoClaims()
    {
        var identity = "DistantHorizons|Multiplayer|Hyp|";
        Assert.NotEqual(DestinationLedger.CacheKey(_f.Rig.GameDir("1.21.1"), identity), DestinationLedger.CacheKey(_f.Rig.GameDir("1.20.4"), identity));
        Assert.Equal(DestinationLedger.CacheKey(_f.Rig.GameDir("1.21.1"), identity), DestinationLedger.CacheKey(_f.Rig.GameDir("1.21.1"), identity));
    }

    // ------------------------------------------------------------ a real crash with a torn entries log, recovered end to end

    [Fact]
    public async Task ACrashMidRunWithATornLastLogLineHalfwayThroughAUtf8CharacterIsRecoveredExactly()
    {
        _f.Add("A", "1.21.1", playtimeHours: 30);
        _f.Add("B", "1.21.1", playtimeHours: 20);
        _f.Add("C", "1.20.4", playtimeHours: 10);
        Kit.Write(_f.Target("1.21.1"), "options.txt", "fov:1.0\n");                 // a file the run REPLACES (backup + restore)
        // "Crash": OutOfMemoryException is the one thing the engine never catches — the run stops dead, journal Applying.
        var engine = _f.Engine(new IImportPlanContributor[] { new StepFor("C", _ => throw new OutOfMemoryException("simulated crash")) });
        var plan = await _f.Plan(engine);
        var before = Kit.TreeHash(_f.Rig.Instances, mtimes: true);
        var settingsBefore = _f.Rig.Settings.FileHash();

        await Assert.ThrowsAsync<OutOfMemoryException>(() => engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None));

        var run = ImportLayout.RunDir(_f.Rig.Imports, plan.RunId);
        var log = Assert.Single(Directory.GetFiles(run, "entries-*.log"));          // the real mid-run log...
        Assert.True(new FileInfo(log).Length > 0);
        var snapshot = ImportJournalStore.Load(ImportLayout.Journal(_f.Rig.Imports, plan.RunId))!;
        Assert.Equal(ImportState.Applying, snapshot.State);
        Assert.Contains(snapshot.Entries, e => e.Kind == JournalEntryKinds.Replaced);  // ...holding A and B's entries
        Assert.NotEqual(before, Kit.TreeHash(_f.Rig.Instances, mtimes: true));

        // The power went out while the NEXT entry was being written: its line stops halfway through "é" (0xC3 0xA9).
        // (The journal's writer escapes non-ASCII as é, so its own torn lines are ASCII; the reader must survive a
        // raw multi-byte character torn in half too, so this line carries a raw UTF-8 "é".)
        var next = ImportJournalStore.Line(new JournalEntry { Kind = JournalEntryKinds.Created, Path = Path.Combine(_f.Rig.GameDir("1.20.4"), "Café.jar") })
            .Replace("\\u00E9", "é", StringComparison.OrdinalIgnoreCase);
        var bytes = System.Text.Encoding.UTF8.GetBytes(next);
        var cut = Array.IndexOf(bytes, (byte)0xC3) + 1;
        Assert.True(cut > 0);
        using (var stream = new FileStream(log, FileMode.Append, FileAccess.Write)) stream.Write(bytes, 0, cut);

        var recovered = ImportRecovery.Recover(_f.Rig.Imports, Path.Combine(_f.Rig.Root, "OriginLauncher"), _f.Rig.Settings, null, CancellationToken.None);

        Assert.Equal(1, recovered);
        Assert.Equal(before, Kit.TreeHash(_f.Rig.Instances, mtimes: true));
        Assert.Equal(settingsBefore, _f.Rig.Settings.FileHash());
        var after = ImportJournalStore.Load(ImportLayout.Journal(_f.Rig.Imports, plan.RunId))!;
        Assert.Equal(ImportState.RolledBack, after.State);
        Assert.Null(after.EntriesLog);
        Assert.Empty(Directory.GetFiles(run, "entries-*.log"));                        // folded into one snapshot again
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
        WithCache("B", "1.21.1", 10, SourceKind.Modrinth);
        var engine = _f.Engine(new IImportPlanContributor[] { new CacheContributor() });
        var plan = await _f.Plan(engine, _f.Request(grid: _f.AllTicked()));
        var bPlanned = plan.AllSetups.Single(s => s.Setup.Name == "B").Plan.Lines;
        Assert.Contains(bPlanned, l => l.Reason == "already brought over from Prism: A");          // the plan said so...
        Assert.Contains(bPlanned, l => l.Reason == "Another of your setups already brings this one.");
        _f.Rig.RunningDirs.Add(a.GameDir);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var lines = Outcome(result, "B").Lines!;
        Assert.DoesNotContain(lines, l => l.Kind == PlanItemKind.Cache && l.Reason == "already brought over from Prism: A"); // ...the result doesn't
        Assert.DoesNotContain(lines, l => l.Reason == "Another of your setups already brings this one.");
        Assert.Contains(lines, l => l.Name == "Faithful.zip" && l.Reason!.StartsWith("came over from this setup", StringComparison.Ordinal));
        Assert.Contains(lines, l => l.Kind == PlanItemKind.Cache && l.Reason!.StartsWith("came over from this setup", StringComparison.Ordinal));
        var text = BulkSummary.Render(result);
        Assert.DoesNotContain("Another of your setups already brings this one.", text);
    }

    [Fact]
    public async Task R2_3b_WithThreeSetupsALaterStandInNamesTheSetupThatReallyBroughtIt()
    {
        // A owns the claim and is skipped mid-run; B's stand-in lands; C's stand-in stays idle because B already landed it.
        // C's plan line said "already brought over from Prism: A": the result must name B, never the skipped A.
        var a = WithCache("A", "1.21.1", 40);
        WithCache("B", "1.21.1", 30);
        WithCache("C", "1.21.1", 10);
        var engine = _f.Engine(new IImportPlanContributor[] { new CacheContributor() });
        var plan = await _f.Plan(engine);
        Assert.Contains(plan.AllSetups.Single(s => s.Setup.Name == "C").Plan.Lines, l => l.Reason == "already brought over from Prism: A");
        _f.Rig.RunningDirs.Add(a.GameDir);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var c = Outcome(result, "C").Lines!;
        Assert.DoesNotContain(c, l => l.Reason == "already brought over from Prism: A");
        Assert.Contains(c, l => l.Name == "Faithful.zip" && l.Reason == "already brought over from Prism: B");
        Assert.DoesNotContain(c, l => l.Reason?.StartsWith("came over from this setup", StringComparison.Ordinal) == true); // C's stand-in never ran
        Assert.DoesNotContain("already brought over from Prism: A", BulkSummary.Render(result));
    }
}
