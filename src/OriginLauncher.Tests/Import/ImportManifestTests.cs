using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Will, 2026-10-20: "the import should show the actual exact versions it's importing settings from and to, like copying
// over from 1.8.9 to 1.8.9 ... in the importing process it didn't say it was gonna copy anything from 1.8.9 but then
// when actually importing it said it was."
//
// Root cause (proven by the first test): with "Bring everything from Lunar", the engine makes the launcher's most played
// setup (Lunar on the OLD version) the settings donor of EVERY version, so the plan holds that setup a second time inside
// the other version's group (PlannedSetup.OtherVersion). The old Review drew one row per setup NAME without the source
// version and counted the donor twice, so the old version was never named as a source; the run did copy from it.
// ImportManifest is the one list every screen now reads, built from the very plan the run executes.
public class ImportManifestTests : IDisposable
{
    private readonly BulkFixture _f = new();

    public void Dispose() => _f.Dispose();

    // Lunar on two versions: the old one is played far more, so it donates its settings to the newer version too.
    private (DetectedInstance Old, DetectedInstance New) LunarOldAndNew()
    {
        var old = _f.Add("Lunar Client 1.20.1", "1.20.1", SourceKind.Lunar, playtimeHours: 100);
        var current = _f.Add("Lunar Client 1.21.1", "1.21.1", SourceKind.Lunar, playtimeHours: 5);
        return (old, current);
    }

    [Fact]
    public async Task The_plan_holds_the_settings_donor_inside_the_other_versions_group()
    {
        var (old, current) = LunarOldAndNew();
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, _f.Request(BulkSelection.OfLauncher(SourceKind.Lunar)));

        // The root cause, as data: three planned setups for two real setups. The old version appears in BOTH groups.
        Assert.Equal(3, plan.AllSetups.Count());
        Assert.Equal(2, plan.AllSetups.Select(p => p.Setup.BulkId()).Distinct().Count());
        var inNew = plan.Groups.Single(g => g.Version == "1.21.1").Setups;
        Assert.Contains(inNew, p => p.OtherVersion && p.Setup.BulkId() == old.BulkId());
        Assert.Contains(inNew, p => !p.OtherVersion && p.Setup.BulkId() == current.BulkId());
    }

    [Fact]
    public async Task Every_copy_is_named_with_its_exact_source_and_origin_version()
    {
        LunarOldAndNew();
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, _f.Request(BulkSelection.OfLauncher(SourceKind.Lunar)));

        var mappings = ImportManifest.From(plan);

        Assert.Equal(
            new[] { "Lunar 1.20.1 -> 1.20.1", "Lunar 1.20.1 -> 1.21.1", "Lunar 1.21.1 -> 1.21.1" },
            mappings.Select(m => m.Title).OrderBy(t => t, StringComparer.Ordinal).ToArray());
        // The cross-version copy says so, in words: this is what Review used to leave out.
        var donor = mappings.Single(m => m.Title == "Lunar 1.20.1 -> 1.21.1");
        Assert.True(donor.FromOtherVersion);
        Assert.Contains("from its Minecraft 1.20.1", donor.Caption);
        Assert.DoesNotContain(mappings.Where(m => m != donor), m => m.Caption.Contains("from its Minecraft"));
        // A donor is one setup in two copies: counted once as a setup, shown twice as a copy.
        Assert.Equal(3, mappings.Count);
        Assert.Equal(2, ImportManifest.DistinctSetups(mappings));
        // Unique ids: Review and Importing rows are keyed by them.
        Assert.Equal(mappings.Count, mappings.Select(m => m.Id).Distinct().Count());
    }

    [Fact]
    public async Task What_the_run_does_is_exactly_the_manifest_in_the_same_order()
    {
        LunarOldAndNew();
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, _f.Request(BulkSelection.OfLauncher(SourceKind.Lunar)));
        var mappings = ImportManifest.From(plan);
        var progress = new SyncBulkProgress<BulkProgress>();

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, progress, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        // One outcome per mapping, in the engine's order: outcome i of version v is mapping (v, i).
        Assert.Equal(mappings.Count, result.Versions.Sum(v => v.Setups.Count));
        foreach (var version in result.Versions)
            for (var slot = 0; slot < version.Setups.Count; slot++)
            {
                var mapping = ImportManifest.Of(mappings, version.Version, slot);
                Assert.NotNull(mapping);
                Assert.Equal(mapping!.Setup.BulkId(), version.Setups[slot].Setup.BulkId());
            }
        // The Importing rows follow BulkProgress.SetupIndex/SetupCount: the same count, the same order.
        lock (progress.Seen)
        {
            Assert.NotEmpty(progress.Seen);
            Assert.All(progress.Seen, p => Assert.Equal(mappings.Count, p.SetupCount));
            // Every copy gets its turn as the current one (the engine may also report SetupCount once at the end: all done).
            var seen = progress.Seen.Select(p => p.SetupIndex).Distinct().ToList();
            Assert.All(Enumerable.Range(0, mappings.Count), i => Assert.Contains(i, seen));
            Assert.All(seen, i => Assert.InRange(i, 0, mappings.Count));
        }
        Assert.Equal(mappings.Select(m => m.Index), Enumerable.Range(0, mappings.Count));
    }

    [Fact]
    public async Task A_plain_same_version_copy_reads_1_21_1_to_1_21_1_and_names_what_it_brings()
    {
        _f.Add("Main", "1.21.1", SourceKind.Prism, playtimeHours: 10);
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);

        var only = Assert.Single(ImportManifest.From(plan));

        Assert.Equal("Prism 1.21.1 -> 1.21.1", only.Title);
        Assert.False(only.FromOtherVersion);
        Assert.True(only.IsSettingsWinner);
        Assert.StartsWith("Main - Settings", only.Caption);
    }

    [Fact]
    public void FromText_names_each_launcher_with_its_source_versions_newest_first()
    {
        var lunarOld = new DetectedInstance { Kind = SourceKind.Lunar, Name = "a", GameDir = "C:\\a", McVersion = "1.8.9" };
        var lunarNew = new DetectedInstance { Kind = SourceKind.Lunar, Name = "b", GameDir = "C:\\b", McVersion = "1.21.1" };
        var prism = new DetectedInstance { Kind = SourceKind.Prism, Name = "c", GameDir = "C:\\c", McVersion = "1.21.1" };
        ImportMapping Map(DetectedInstance s) => new(0, "1.21.1", 0, s, "1.21.1", SetupRole.Full, false, false, ImportCategories.All, "");

        Assert.Equal("Lunar 1.21.1 and 1.8.9", ImportManifest.FromText(new[] { Map(lunarOld), Map(lunarNew) }));
        Assert.Equal("Prism 1.21.1, Lunar 1.8.9", ImportManifest.FromText(new[] { Map(prism), Map(lunarOld) }));
        Assert.Equal("Lunar 1.8.9", ImportManifest.FromText(new[] { Map(lunarOld), Map(lunarOld) }));
    }

    [Fact]
    public void An_unknown_source_version_reads_as_a_question_mark_not_as_blank()
    {
        var folder = new DetectedInstance { Kind = SourceKind.Folder, Name = "My pack", GameDir = "C:\\p", McVersion = null };
        Assert.Equal("My pack ? -> 1.21.1", ImportManifest.TitleOf(folder, "1.21.1"));
    }
}
