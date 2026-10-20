using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using Xunit;

namespace OriginLauncher.Tests.Import;

// ImportMemoryKeeper is the working side of the import memory: what the launcher does around an import (sign the
// sources BEFORE the copy, remember after, merge into the freshest stored value, never throw). The launcher's own
// glue (Launcher/ImportMemoryStore) only plugs in the settings reader/writer and the folder check, so these tests plug
// in a string and a folder check of their own.
public class ImportMemoryKeeperTests : IDisposable
{
    private readonly BulkFixture _f = new();
    private string? _stored;                                   // stands in for LauncherSettings.ImportedSetupsJson
    private int _writes;

    public void Dispose() => _f.Dispose();

    private bool InstanceExists(string account, string version) =>
        account == Rig.Account && Directory.Exists(_f.Rig.GameDir(version));

    private ImportMemoryKeeper Keeper(Func<string?>? read = null, Action<Func<string?, string?>>? update = null) =>
        new(read ?? (() => _stored), update ?? (mutate => { _stored = mutate(_stored); _writes++; }), InstanceExists);

    private async Task<(BulkImportPlan Plan, BulkImportResult Result)> Run(ImportMemoryKeeper keeper, DetectedInstance[] setups,
        Action? betweenSignAndCopy = null, SourceKind launcher = SourceKind.Lunar)
    {
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, _f.Request(BulkSelection.OfLauncher(launcher)));
        var pre = keeper.PreSign(setups);
        betweenSignAndCopy?.Invoke();
        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        keeper.RememberRun(plan, result, pre);
        return (plan, result);
    }

    [Fact]
    public async Task A_finished_run_is_remembered_and_the_scan_stops_offering_it_until_it_changes()
    {
        var a = _f.Add("Lunar Client 1.20.1", "1.20.1", SourceKind.Lunar, playtimeHours: 100);
        var b = _f.Add("Lunar Client 1.21.1", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var prism = _f.Add("Prism pack", "1.21.1", SourceKind.Prism, playtimeHours: 1);
        var keeper = Keeper();

        await Run(keeper, new[] { a, b });

        var (offered, done) = keeper.Partition(new[] { a, b, prism }, Rig.Account);
        Assert.Equal(new[] { prism.SetupId }, offered.Select(s => s.SetupId));
        Assert.Equal(2, done.Count);

        Kit.WriteBytes(a.GameDir, "mods/new.jar", new byte[] { 4, 2 });                  // "unless something changes later"
        (offered, done) = keeper.Partition(new[] { a, b, prism }, Rig.Account);
        Assert.Contains(a.SetupId, offered.Select(s => s.SetupId));
        Assert.Equal(new[] { b.SetupId }, done.Select(s => s.SetupId));
    }

    [Fact]
    public async Task The_source_is_signed_before_the_copy_so_a_change_during_the_import_is_offered_again()
    {
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var keeper = Keeper();

        // The player edits the source after Origin looked at it and before the copy read it.
        await Run(keeper, new[] { a }, betweenSignAndCopy: () => Kit.Write(a.GameDir, "options.txt", TypicalSource.Options + "fov:0.5\n"));

        var (offered, done) = keeper.Partition(new[] { a }, Rig.Account);
        Assert.Single(offered);                                                          // recorded the OLD state, so it differs now
        Assert.Empty(done);
    }

    [Fact]
    public async Task Signing_after_the_change_would_hide_it_which_is_why_the_order_matters()
    {
        // Control for the test above: with the same edit made BEFORE signing, the run is recorded and the source is hidden.
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var keeper = Keeper();
        Kit.Write(a.GameDir, "options.txt", TypicalSource.Options + "fov:0.5\n");

        await Run(keeper, new[] { a });

        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).AlreadyImported);
    }

    [Fact]
    public async Task A_run_that_did_not_commit_remembers_nothing()
    {
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var keeper = Keeper();
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, _f.Request(BulkSelection.OfLauncher(SourceKind.Lunar)));
        var pre = keeper.PreSign(new[] { a });
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, cts.Token);
        keeper.RememberRun(plan, result, pre);

        Assert.False(result.Succeeded);
        Assert.Equal(0, _writes);
        Assert.Null(_stored);
        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).Offered);
    }

    [Fact]
    public async Task A_run_for_one_account_does_not_hide_the_setup_for_another_account()
    {
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var keeper = Keeper();
        await Run(keeper, new[] { a });

        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).AlreadyImported);
        Assert.Single(keeper.Partition(new[] { a }, "some-other-account").Offered);
        Assert.Single(keeper.Partition(new[] { a }, null).Offered);                      // nobody signed in: nothing is hidden
    }

    [Fact]
    public async Task Resetting_the_origin_instance_makes_the_source_offered_again_and_the_dead_record_is_dropped_on_the_next_write()
    {
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var keeper = Keeper();
        await Run(keeper, new[] { a });
        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).AlreadyImported);

        Directory.Delete(_f.Rig.GameDir("1.21.1"), recursive: true);

        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).Offered);
        keeper.Remember(new[] { new ImportedSetupRecord { SetupId = "other", Account = "x", Targets = { "1.21.1" }, Version = "1.21.1", ImportedUtc = DateTime.UtcNow } });
        Assert.DoesNotContain(keeper.Load(), r => r.SetupId == a.SetupId);               // pruned: its instance is gone
    }

    [Fact]
    public async Task The_merge_runs_on_the_freshest_stored_value_not_on_one_read_earlier()
    {
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var keeper = Keeper();
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, _f.Request(BulkSelection.OfLauncher(SourceKind.Lunar)));
        var pre = keeper.PreSign(new[] { a });
        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        // Another writer (a second import, another window) stores a record after this run began and before it finishes.
        _stored = ImportMemory.Serialize(new[]
        {
            new ImportedSetupRecord { SetupId = "someone-else", Launcher = "Prism", Version = "1.21.1", Account = Rig.Account,
                Targets = { "1.21.1" }, Signatures = { ["Mods"] = "abc" }, ImportedUtc = DateTime.UtcNow },
        });
        Directory.CreateDirectory(_f.Rig.GameDir("1.21.1"));

        keeper.RememberRun(plan, result, pre);

        var ids = keeper.Load().Select(r => r.SetupId).ToList();
        Assert.Contains("someone-else", ids);                                            // not overwritten
        Assert.Contains(a.SetupId, ids);
    }

    [Fact]
    public async Task Importing_the_same_setup_twice_keeps_one_record()
    {
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var keeper = Keeper();

        await Run(keeper, new[] { a });
        Kit.Write(a.GameDir, "options.txt", TypicalSource.Options + "fov:0.7\n");
        await Run(keeper, new[] { a });

        Assert.Single(keeper.Load());
        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).AlreadyImported);
    }

    // ---- a single import ----

    [Fact]
    public void A_committed_single_import_is_remembered_for_the_account_in_its_target_folder()
    {
        var a = _f.Add("Prism pack", "1.21.1", SourceKind.Prism);
        var keeper = Keeper();
        var plan = SinglePlan(a);
        var pre = keeper.PreSign(new[] { a });

        keeper.RememberImport(plan, new ImportResult { ImportId = "i", State = ImportState.Committed }, pre);

        var record = Assert.Single(keeper.Load());
        Assert.Equal(Rig.Account, record.Account);
        Assert.Equal(new[] { "1.21.1" }, record.Targets);
        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).AlreadyImported);
        Assert.Single(keeper.Partition(new[] { a }, "someone-else").Offered);
    }

    [Theory]
    [InlineData(ImportState.RolledBack)]
    [InlineData(ImportState.Planned)]
    [InlineData(ImportState.Applying)]
    public void A_single_import_that_did_not_commit_is_not_remembered(ImportState state)
    {
        var a = _f.Add("Prism pack", "1.21.1", SourceKind.Prism);
        var keeper = Keeper();

        keeper.RememberImport(SinglePlan(a), new ImportResult { ImportId = "i", State = state }, keeper.PreSign(new[] { a }));

        Assert.Equal(0, _writes);
        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).Offered);
    }

    private ImportPlan SinglePlan(DetectedInstance source)
    {
        Directory.CreateDirectory(_f.Rig.GameDir("1.21.1"));
        return new ImportPlan
        {
            ImportId = "i", Source = source, TargetVersion = "1.21.1", Options = new ImportOptions(),
            TargetGameDir = _f.Rig.GameDir("1.21.1"),
        };
    }

    // ---- nothing here may throw into an import or a scan ----

    [Fact]
    public async Task A_store_that_cannot_be_written_never_fails_a_finished_import()
    {
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var keeper = Keeper(update: _ => throw new IOException("settings.json is locked"));

        await Run(keeper, new[] { a });                                                  // Run asserts the import itself succeeded

        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).Offered);            // nothing remembered: offered again
    }

    [Fact]
    public void A_store_that_cannot_be_read_offers_everything_and_never_throws()
    {
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar);
        var keeper = Keeper(read: () => throw new InvalidOperationException("boom"));

        Assert.Empty(keeper.Load());
        var (offered, done) = keeper.Partition(new[] { a }, Rig.Account);
        Assert.Single(offered);
        Assert.Empty(done);
    }

    [Fact]
    public async Task A_damaged_stored_value_is_replaced_by_the_new_record()
    {
        var a = _f.Add("Lunar Client", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        _stored = "{this is not json";
        var keeper = Keeper();

        await Run(keeper, new[] { a });

        Assert.Single(keeper.Load());
        Assert.Single(keeper.Partition(new[] { a }, Rig.Account).AlreadyImported);
    }

    [Fact]
    public void A_source_that_cannot_be_read_is_simply_not_signed()
    {
        var gone = new DetectedInstance { Kind = SourceKind.Prism, Name = "gone", GameDir = Path.Combine(_f.SetupsRoot, "nope"), McVersion = "1.21.1" };
        var keeper = Keeper();

        var pre = keeper.PreSign(new[] { gone });

        Assert.Empty(Assert.Single(pre).Value);
    }
}
