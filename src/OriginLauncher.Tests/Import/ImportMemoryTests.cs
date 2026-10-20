using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Will, 2026-10-20: "after you import you can't import the exact same things; if I imported all of Lunar Client it
// shouldn't show up anymore unless something changes later." The rules live in ImportMemory (pure); the settings
// field holds one JSON string, so a damaged value can only ever mean "nothing was imported".
public class ImportMemoryTests : IDisposable
{
    private readonly BulkFixture _f = new();

    public void Dispose() => _f.Dispose();

    private static List<ImportedSetupRecord> Remember(params DetectedInstance[] setups) =>
        ImportMemory.RecordsFor(setups.Select(s => (s, ImportCategories.All)), DateTime.UtcNow);

    // ---- the stored value ----

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("{not json")]
    [InlineData("{}")]
    [InlineData("42")]
    [InlineData("[1,2,3]")]
    [InlineData("[null]")]
    [InlineData("[{\"SetupId\":\"\"}]")]
    [InlineData("[{\"SetupId\":\"a\",\"Signatures\":null}]")]
    public void Missing_or_damaged_storage_is_nothing_imported_and_never_throws(string? stored)
    {
        Assert.Empty(ImportMemory.Parse(stored));
    }

    [Fact]
    public void Records_survive_a_round_trip_through_the_stored_string()
    {
        var setup = _f.Add("A", "1.21.1", SourceKind.Lunar);
        var records = Remember(setup);

        var back = ImportMemory.Parse(ImportMemory.Serialize(records));

        var one = Assert.Single(back);
        Assert.Equal(setup.SetupId, one.SetupId);
        Assert.Equal("1.21.1", one.Version);
        Assert.Equal(records[0].Signatures, one.Signatures);
    }

    [Fact]
    public void One_good_record_among_damaged_ones_is_kept()
    {
        var json = "[{\"SetupId\":\"\"},{\"SetupId\":\"x\",\"Version\":\"1.21.1\",\"Signatures\":{\"Mods\":\"abc\"}},null]";
        Assert.Equal("x", Assert.Single(ImportMemory.Parse(json)).SetupId);
    }

    // ---- the signature ----

    [Fact]
    public void Signature_is_stable_until_something_in_the_source_changes()
    {
        var setup = _f.Add("A", "1.21.1", SourceKind.Prism);
        var first = SetupSignature.Compute(setup);
        Assert.Equal(first, SetupSignature.Compute(setup));
        Assert.Contains(ImportCategories.Settings, first.Keys);
        Assert.Contains(ImportCategories.Servers, first.Keys);

        Kit.Write(setup.GameDir, "options.txt", TypicalSource.Options + "fov:0.9\n");     // a setting changed
        var after = SetupSignature.Compute(setup);
        Assert.NotEqual(first[ImportCategories.Settings], after[ImportCategories.Settings]);
        Assert.Equal(first[ImportCategories.Servers], after[ImportCategories.Servers]);   // nothing else moved
    }

    [Fact]
    public void A_category_with_nothing_in_it_has_no_signature()
    {
        var setup = _f.Add("Empty", "1.21.1", populate: false);
        Assert.Empty(SetupSignature.Compute(setup));
    }

    // ---- is it already imported? ----

    [Fact]
    public void A_setup_that_was_never_imported_is_offered()
    {
        var setup = _f.Add("A", "1.21.1");
        Assert.False(ImportMemory.IsAlreadyImported(setup, Array.Empty<ImportedSetupRecord>()));
        var (offered, done) = ImportMemory.Partition(new[] { setup }, Array.Empty<ImportedSetupRecord>());
        Assert.Single(offered);
        Assert.Empty(done);
    }

    [Fact]
    public void An_imported_unchanged_setup_is_not_offered_again()
    {
        var setup = _f.Add("A", "1.21.1", SourceKind.Lunar);
        var records = Remember(setup);

        Assert.True(ImportMemory.IsAlreadyImported(setup, records));
        var (offered, done) = ImportMemory.Partition(new[] { setup }, records);
        Assert.Empty(offered);
        Assert.Single(done);
    }

    [Theory]
    [InlineData("options.txt")]                      // a setting changed
    [InlineData("servers.dat")]                      // a server added
    [InlineData("mods/newmod.jar")]                  // a mod added
    [InlineData("resourcepacks/Another.zip")]        // a pack added
    [InlineData("shaderpacks/Another.zip")]
    public void Anything_that_changes_in_the_source_brings_the_setup_back(string changed)
    {
        var setup = _f.Add("A", "1.21.1", SourceKind.Lunar);
        var records = Remember(setup);

        Kit.Write(setup.GameDir, changed, "something different " + Guid.NewGuid());

        Assert.False(ImportMemory.IsAlreadyImported(setup, records));
    }

    [Fact]
    public void Only_the_setup_that_changed_comes_back_not_its_untouched_neighbours()
    {
        var a = _f.Add("A", "1.21.1", SourceKind.Lunar);
        var b = _f.Add("B", "1.20.1", SourceKind.Lunar);
        var c = _f.Add("C", "1.21.1", SourceKind.Prism);
        var records = Remember(a, b, c);

        Kit.Write(b.GameDir, "options.txt", TypicalSource.Options + "ao:false\n");

        var (offered, done) = ImportMemory.Partition(new[] { a, b, c }, records);
        Assert.Equal(new[] { b.SetupId }, offered.Select(s => s.SetupId));
        Assert.Equal(2, done.Count);
    }

    [Fact]
    public void A_setup_whose_minecraft_version_changed_is_a_new_thing()
    {
        var setup = _f.Add("A", "1.21.1", SourceKind.Prism);
        var records = Remember(setup);

        var upgraded = new DetectedInstance
        {
            Kind = setup.Kind, Name = setup.Name, GameDir = setup.GameDir, McVersion = "1.21.4", Loader = setup.Loader,
        };

        Assert.Equal(setup.SetupId, upgraded.SetupId);                                  // same folder, same id
        Assert.False(ImportMemory.IsAlreadyImported(upgraded, records));
    }

    [Fact]
    public void Worlds_that_were_not_copied_do_not_keep_a_finished_launcher_on_offer()
    {
        // Worlds are off by default (they can be huge): not copying them is not "something left to import".
        var setup = _f.Add("A", "1.21.1", SourceKind.Lunar);   // TypicalSource has saves/World1
        var withoutWorlds = ImportMemory.RecordsFor(new[] { (setup, ImportCategories.All & ~ImportCategories.Worlds) }, DateTime.UtcNow);

        Assert.DoesNotContain("Worlds", withoutWorlds[0].Signatures.Keys);
        Assert.True(ImportMemory.IsAlreadyImported(setup, withoutWorlds));
    }

    [Fact]
    public void Worlds_that_were_copied_bring_the_setup_back_when_they_change()
    {
        var setup = _f.Add("A", "1.21.1", SourceKind.Lunar);
        var records = Remember(setup);
        Assert.Contains("Worlds", records[0].Signatures.Keys);

        Kit.Write(setup.GameDir, "saves/World1/level.dat", "LEVEL, played some more");

        Assert.False(ImportMemory.IsAlreadyImported(setup, records));
    }

    [Fact]
    public void Mods_the_player_left_out_keep_the_setup_on_offer()
    {
        var setup = _f.Add("A", "1.21.1", SourceKind.Lunar);
        Kit.WriteBytes(setup.GameDir, "mods/m.jar", new byte[] { 1, 2, 3 });
        var withoutMods = ImportMemory.RecordsFor(new[] { (setup, ImportCategories.All & ~ImportCategories.Mods) }, DateTime.UtcNow);

        Assert.False(ImportMemory.IsAlreadyImported(setup, withoutMods));
    }

    [Fact]
    public void Unreadable_content_counts_as_no_content_never_as_a_crash()
    {
        var gone = new DetectedInstance { Kind = SourceKind.Prism, Name = "gone", GameDir = Path.Combine(_f.SetupsRoot, "does-not-exist"), McVersion = "1.21.1" };
        Assert.Empty(SetupSignature.Compute(gone));
        Assert.False(ImportMemory.IsAlreadyImported(gone, Array.Empty<ImportedSetupRecord>()));
    }

    // ---- merging what was just imported into what was stored ----

    [Fact]
    public void Importing_again_keeps_the_categories_already_recorded_and_updates_the_new_ones()
    {
        var setup = _f.Add("A", "1.21.1", SourceKind.Lunar);
        var first = ImportMemory.RecordsFor(new[] { (setup, ImportCategories.Settings) }, DateTime.UtcNow.AddHours(-1));
        Kit.WriteBytes(setup.GameDir, "mods/m.jar", new byte[] { 9 });
        var second = ImportMemory.RecordsFor(new[] { (setup, ImportCategories.Mods) }, DateTime.UtcNow);

        var merged = Assert.Single(ImportMemory.Merge(first, second));

        Assert.Contains("Settings", merged.Signatures.Keys);
        Assert.Contains("Mods", merged.Signatures.Keys);
    }

    [Fact]
    public void A_record_for_a_new_minecraft_version_replaces_the_old_one()
    {
        var old = new ImportedSetupRecord { SetupId = "s", Version = "1.20.1", Signatures = { ["Mods"] = "a" }, ImportedUtc = DateTime.UtcNow.AddDays(-1) };
        var fresh = new ImportedSetupRecord { SetupId = "s", Version = "1.21.1", Signatures = { ["Settings"] = "b" }, ImportedUtc = DateTime.UtcNow };

        var merged = Assert.Single(ImportMemory.Merge(new[] { old }, new[] { fresh }));

        Assert.Equal("1.21.1", merged.Version);
        Assert.Equal(new[] { "Settings" }, merged.Signatures.Keys.ToArray());
    }

    [Fact]
    public void The_store_stays_small_by_dropping_the_oldest_records()
    {
        var many = Enumerable.Range(0, ImportMemory.MaxRecords + 25)
            .Select(i => new ImportedSetupRecord { SetupId = "s" + i, Version = "1.21.1", ImportedUtc = DateTime.UtcNow.AddMinutes(-i) })
            .ToList();

        var merged = ImportMemory.Merge(Array.Empty<ImportedSetupRecord>(), many);

        Assert.Equal(ImportMemory.MaxRecords, merged.Count);
        Assert.Contains(merged, r => r.SetupId == "s0");                                 // newest kept
        Assert.DoesNotContain(merged, r => r.SetupId == "s" + (ImportMemory.MaxRecords + 24)); // oldest gone
    }

    // ---- the whole loop: import a launcher, it disappears; change it, it comes back ----

    [Fact]
    public async Task Importing_all_of_a_launcher_makes_it_disappear_until_something_in_it_changes()
    {
        var old = _f.Add("Lunar Client 1.20.1", "1.20.1", SourceKind.Lunar, playtimeHours: 100);
        var current = _f.Add("Lunar Client 1.21.1", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        var prism = _f.Add("Prism pack", "1.21.1", SourceKind.Prism, playtimeHours: 1);
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, _f.Request(BulkSelection.OfLauncher(SourceKind.Lunar)));
        var before = SetupSignatures(old, current);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);

        // What the launcher does after a run: record what was covered, with the signatures read BEFORE the copy.
        var covered = ImportMemory.CoveredByRun(plan, result);
        Assert.Equal(2, covered.Count);                                                  // the donor appears in two copies, one record
        var records = ImportMemory.RecordsFor(covered, DateTime.UtcNow, (s, c) =>
            before[s.SetupId].Where(kv => (c & kv.Key) != 0).ToDictionary(kv => kv.Key, kv => kv.Value));

        var all = new[] { old, current, prism };
        var (offered, done) = ImportMemory.Partition(all, records);
        Assert.Equal(new[] { prism.SetupId }, offered.Select(s => s.SetupId));           // all of Lunar is gone, Prism was never imported
        Assert.Equal(2, done.Count);

        // Later: Lunar's old version changes. Only that setup is offered again.
        Kit.WriteBytes(old.GameDir, "mods/another.jar", new byte[] { 7, 7 });
        (offered, done) = ImportMemory.Partition(all, records);
        Assert.Equal(new[] { old.SetupId, prism.SetupId }.OrderBy(x => x), offered.Select(s => s.SetupId).OrderBy(x => x));
        Assert.Equal(new[] { current.SetupId }, done.Select(s => s.SetupId));
    }

    [Fact]
    public async Task A_setup_that_did_not_import_is_not_remembered()
    {
        var a = _f.Add("A", "1.21.1", SourceKind.Prism, playtimeHours: 5);
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);
        using var cts = new CancellationTokenSource();
        cts.Cancel();

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, cts.Token);

        Assert.False(result.Succeeded);
        Assert.Empty(ImportMemory.CoveredByRun(plan, result));
        Assert.False(ImportMemory.IsAlreadyImported(a, Array.Empty<ImportedSetupRecord>()));
    }

    private static Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> SetupSignatures(params DetectedInstance[] setups) =>
        setups.ToDictionary(s => s.SetupId, s => (IReadOnlyDictionary<ImportCategories, string>)SetupSignature.Compute(s));
}
