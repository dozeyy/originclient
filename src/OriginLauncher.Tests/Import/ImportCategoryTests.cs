using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using Xunit;

namespace OriginLauncher.Tests.Import;

// "What to bring" (ImportCategories) through the real engine: a left-out kind is neither planned nor copied, the
// plan says so, and per-version choices reach each version of a bulk import. Plus the drawer's folder counts
// (SetupInventory). Every folder is a temp folder (Rig / BulkFixture); nothing real is touched.
public class ImportCategoryTests : IDisposable
{
    private readonly Rig _rig = new();

    public void Dispose() => _rig.Dispose();

    private async Task<ImportPlan> Plan(ImportOptions options) =>
        await _rig.Engine().PlanAsync(_rig.Request(options: options), null, CancellationToken.None);

    private static ImportCategories Category(PlannedOp op) => CategoryFilter.Of(op.Item, op.Label);

    [Fact]
    public async Task Nothing_left_out_plans_exactly_as_before()
    {
        TypicalSource.Populate(_rig.Source);
        var before = await Plan(new ImportOptions { CopyWorlds = true });
        var after = await Plan(new ImportOptions { CopyWorlds = true, LeaveOut = ImportCategories.None });

        Assert.Equal(before.Ops.Select(o => o.Label), after.Ops.Select(o => o.Label));
        Assert.Equal(before.Created, after.Created);
        Assert.Equal(before.Lines.Count, after.Lines.Count);
    }

    [Fact]
    public async Task Left_out_kinds_are_not_planned_and_the_plan_says_why()
    {
        TypicalSource.Populate(_rig.Source);
        var full = await Plan(new ImportOptions { CopyWorlds = true });
        var leaveOut = ImportCategories.Worlds | ImportCategories.Servers | ImportCategories.ResourcePacks;

        var plan = await Plan(new ImportOptions { CopyWorlds = true, LeaveOut = leaveOut });

        Assert.Contains(full.Ops, o => Category(o) == ImportCategories.Servers);
        Assert.Contains(full.Ops, o => Category(o) == ImportCategories.ResourcePacks);
        Assert.Contains(full.Ops, o => Category(o) == ImportCategories.Worlds);
        Assert.DoesNotContain(plan.Ops, o => (Category(o) & leaveOut) != 0);
        Assert.Contains(plan.Ops, o => Category(o) == ImportCategories.ShaderPacks);   // the rest still comes
        Assert.Contains(plan.Ops, o => o.Label == "options.txt");
        Assert.True(plan.Created < full.Created);
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name == "Server list"
            && l.Reason == CategoryFilter.LeftOutReason(ImportCategories.Servers));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name == "Resource packs");
        Assert.DoesNotContain(plan.Lines, l => l.Section == SummarySection.Imported && l.Kind == PlanItemKind.World);
    }

    [Fact]
    public async Task Left_out_packs_are_not_switched_on_by_options_txt()
    {
        TypicalSource.Populate(_rig.Source);
        var plan = await Plan(new ImportOptions { LeaveOut = ImportCategories.ResourcePacks });

        var options = plan.Ops.Where(o => o.Kind == OpKind.WriteOptions).ToList();
        Assert.All(options, o =>
        {
            Assert.Null(o.Extra);
            Assert.False(o.Desired?.ContainsKey("resourcePacks") ?? false);
        });
    }

    [Fact]
    public async Task Left_out_mods_and_server_list_never_reach_the_game_folder()
    {
        TypicalSource.Populate(_rig.Source);
        var engine = _rig.Engine();
        var plan = await engine.PlanAsync(
            _rig.Request(options: new ImportOptions { LeaveOut = ImportCategories.Mods | ImportCategories.Servers }), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        Assert.Empty(plan.Mods);

        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.False(File.Exists(Path.Combine(_rig.GameDir(), "servers.dat")));
        Assert.True(File.Exists(Path.Combine(_rig.GameDir(), "options.txt")));
        Assert.True(File.Exists(Path.Combine(_rig.GameDir(), "resourcepacks", "Faithful.zip")));
        Assert.Empty(Directory.EnumerateFiles(_rig.Instances, "mymod.jar", SearchOption.AllDirectories));
    }

    [Fact]
    public async Task Worlds_follow_the_category_over_the_old_switch()
    {
        TypicalSource.Populate(_rig.Source);
        var on = await Plan(new ImportOptions { CopyWorlds = false, BringByVersion = new CategoryMap(new[] { KeyValuePair.Create("1.21.1", ImportCategories.All) }) });
        var off = await Plan(new ImportOptions { CopyWorlds = true, LeaveOut = ImportCategories.Worlds });

        // A map naming the version brings what it says, within the switches: worlds stay off unless the switch is on.
        Assert.DoesNotContain(on.Ops, o => Category(o) == ImportCategories.Worlds);
        Assert.DoesNotContain(off.Ops, o => Category(o) == ImportCategories.Worlds);
        var map = await Plan(new ImportOptions { CopyWorlds = true, BringByVersion = new CategoryMap(new[] { KeyValuePair.Create("1.21.1", ImportCategories.All) }) });
        Assert.Contains(map.Ops, o => Category(o) == ImportCategories.Worlds);
    }

    [Fact]
    public async Task Bulk_import_gives_each_version_its_own_choice()
    {
        using var f = new BulkFixture();
        f.Add("A", "1.21.1", playtimeHours: 30);
        f.Add("C", "1.20.4", playtimeHours: 10);
        var engine = f.Engine();
        var map = new CategoryMap(new[]
        {
            KeyValuePair.Create("1.21.1", ImportCategories.All),
            KeyValuePair.Create("1.20.4", ImportCategories.All & ~(ImportCategories.Settings | ImportCategories.Worlds)),
        });

        var plan = await f.Plan(engine, f.Request(options: new ImportOptions { CopyWorlds = true, BringByVersion = map }));

        var newer = plan.Groups.Single(g => g.Version == "1.21.1").Setups.Single().Plan;
        var older = plan.Groups.Single(g => g.Version == "1.20.4").Setups.Single().Plan;
        Assert.Contains(newer.Ops, o => o.Label == "options.txt");
        Assert.Contains(newer.Ops, o => Category(o) == ImportCategories.Worlds);
        Assert.DoesNotContain(older.Ops, o => Category(o) is ImportCategories.Settings or ImportCategories.Worlds);
        Assert.Contains(older.Ops, o => Category(o) == ImportCategories.Servers);
        Assert.Contains(older.Lines, l => l.Section == SummarySection.NotImported && l.Name == "Settings"
            && l.Reason == CategoryFilter.LeftOutReason(ImportCategories.Settings));

        var result = await engine.ApplyBulkAsync(plan, f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        Assert.True(File.Exists(f.Target("1.21.1", "options.txt")));
        Assert.False(File.Exists(f.Target("1.20.4", "options.txt")));
        Assert.True(File.Exists(f.Target("1.20.4", "servers.dat")));
    }

    [Fact]
    public void Category_map_is_value_equal_so_a_replan_reuses_the_plan()
    {
        var a = new CategoryMap(new[] { KeyValuePair.Create("1.21.1", ImportCategories.Mods), KeyValuePair.Create("1.8.9", ImportCategories.All) });
        var b = new CategoryMap(new[] { KeyValuePair.Create("1.8.9", ImportCategories.All), KeyValuePair.Create("1.21.1", ImportCategories.Mods) });

        Assert.Equal(a, b);
        Assert.Equal(new ImportOptions { BringByVersion = a }, new ImportOptions { BringByVersion = b });
        Assert.Equal(ImportCategories.All, a.For("1.20.1"));
        Assert.NotEqual(a, new CategoryMap(new[] { KeyValuePair.Create("1.21.1", ImportCategories.All) }));
    }

    [Fact]
    public void Inventory_counts_what_a_setup_holds_from_folder_listings()
    {
        TypicalSource.Populate(_rig.Source);
        Kit.Write(_rig.Source, "screenshots/a.png", "png");
        Kit.Write(_rig.Source, "saves/NotAWorld/readme.txt", "x");

        var contents = SetupInventory.Read(_rig.SourceInstance());

        Assert.Equal(5, contents.Mods);              // .jar only; the .jar.disabled one isn't counted
        Assert.Equal(1, contents.Worlds);            // a folder without level.dat isn't a world
        Assert.Equal(3, contents.ResourcePacks);     // two zips and a folder pack
        Assert.Equal(2, contents.ShaderPacks);       // the settings .txt beside a shader isn't a pack
        Assert.Equal(1, contents.Screenshots);
        Assert.True(contents.Servers);
        Assert.True(contents.Settings);
        Assert.Equal(SetupContents.Empty, SetupInventory.Read(_rig.SourceInstance() with { GameDir = Path.Combine(_rig.Root, "gone") }));
    }

    [Fact]
    public void Installed_launchers_are_the_ones_whose_folder_exists()
    {
        var root = Path.Combine(_rig.Root, "pc");
        var minecraft = Directory.CreateDirectory(Path.Combine(root, ".minecraft")).FullName;
        Directory.CreateDirectory(Path.Combine(minecraft, "labymod-neo"));
        var lunar = Directory.CreateDirectory(Path.Combine(root, ".lunarclient")).FullName;

        var installed = SetupInventory.Installed(new SourceRoots
        {
            OfficialMinecraftDir = minecraft, LunarDir = lunar,
            FeatherDir = Path.Combine(root, "feather-missing"), PrismDataDir = null,
        });

        Assert.Equal(new[] { SourceKind.OfficialLauncher, SourceKind.Lunar, SourceKind.LabyMod }.OrderBy(k => k), installed.OrderBy(k => k));
    }
}
