using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests.Import;

/// <summary>
/// The import drawer's choices (ImportFlowModel): launcher tiles, the versions list, "Import from all versions",
/// what each version brings, unsupported versions and whose settings win. Pure: the folders are made-up paths
/// (never read) except where a test says it counts files, and then they live in a temp folder.
/// </summary>
public class ImportFlowModelTests
{
    private static readonly string[] Supported = { "1.21.1", "1.20.1", "1.8.9" };

    private static DetectedInstance Setup(string name, string? mc, SourceKind kind, string? dir = null, long? hours = null,
        bool pinned = false, string? problem = null, LoaderKind loader = LoaderKind.Fabric, string? shared = null) => new()
    {
        Kind = kind, Name = name, McVersion = mc, Loader = loader, Pinned = pinned, Problem = problem,
        GameDir = dir ?? Path.Combine(Path.GetTempPath(), "origin-flow-never-read", kind.ToString(), name),
        SharedGameDir = shared,
        PlaytimeSeconds = hours * 3600, Confidence = Confidence.Exact,
    };

    // A Lunar install (three versions sharing one .minecraft) plus the official launcher (two profiles).
    private static (ImportFlowModel Model, DetectedInstance[] Setups) Mixed()
    {
        var shared = Path.Combine(Path.GetTempPath(), "origin-flow-never-read", "lunar-shared");
        var setups = new[]
        {
            Setup("Lunar 1.21.1", "1.21.1", SourceKind.Lunar, shared: shared, hours: 40),
            Setup("Lunar 1.8.9", "1.8.9", SourceKind.Lunar, shared: shared, hours: 300),
            Setup("Lunar 1.21.5", "1.21.5", SourceKind.Lunar, shared: shared),
            Setup("Latest release", "1.21.1", SourceKind.OfficialLauncher, hours: 5),
            Setup("Hypixel", "1.20.1", SourceKind.OfficialLauncher, hours: 12),
        };
        return (new ImportFlowModel(setups, Supported), setups);
    }

    [Fact]
    public void Tiles_list_every_launcher_in_order_and_say_why_one_is_greyed()
    {
        var (model, _) = Mixed();
        model.Installed = new HashSet<SourceKind> { SourceKind.OfficialLauncher, SourceKind.Lunar, SourceKind.Prism };

        var tiles = model.Tiles(scanning: false);

        Assert.Equal(ImportFlowModel.TileOrder, tiles.Select(t => t.Kind));
        Assert.Equal("Minecraft Launcher", tiles[0].Title);
        Assert.Equal(LauncherState.Found, tiles[0].State);
        Assert.Equal("2 versions", tiles[0].Caption);
        Assert.Equal(LauncherState.Found, tiles.Single(t => t.Kind == SourceKind.Lunar).State);
        Assert.Equal("3 versions", tiles.Single(t => t.Kind == SourceKind.Lunar).Caption);
        var prism = tiles.Single(t => t.Kind == SourceKind.Prism);
        Assert.Equal(LauncherState.Empty, prism.State);
        Assert.False(prism.Enabled);
        var feather = tiles.Single(t => t.Kind == SourceKind.Feather);
        Assert.Equal(LauncherState.NotInstalled, feather.State);
        Assert.Equal("Not installed", feather.Caption);
        Assert.False(feather.Enabled);
        Assert.DoesNotContain(tiles, t => t.Kind == SourceKind.Folder);
    }

    [Fact]
    public void Tiles_say_looking_while_the_scan_runs_and_picked_folders_appear_after_browse()
    {
        var model = new ImportFlowModel(Array.Empty<DetectedInstance>(), Supported);
        Assert.All(model.Tiles(scanning: true), t => Assert.Equal(LauncherState.Looking, t.State));

        Assert.Equal(1, model.Add(new[] { Setup("My folder", "1.21.1", SourceKind.Folder) }));
        Assert.Equal(0, model.Add(new[] { Setup("My folder", "1.21.1", SourceKind.Folder) })); // same folder twice: once
        var folder = model.Tiles(scanning: false).Last();
        Assert.Equal(SourceKind.Folder, folder.Kind);
        Assert.Equal("Picked folders", folder.Title);
        Assert.True(folder.Enabled);
    }

    [Fact]
    public void Tile_counts_worlds_once_per_saves_folder()
    {
        var (model, setups) = Mixed();
        model.Contents = setups.ToDictionary(s => s.SetupId, _ => new SetupContents(Worlds: 4));

        // Three Lunar versions share one saves folder: 4 worlds, not 12.
        Assert.Equal("3 versions, 4 worlds", model.Tiles(false).Single(t => t.Kind == SourceKind.Lunar).Caption);
        Assert.Equal("2 versions, 8 worlds", model.Tiles(false)[0].Caption);
    }

    [Fact]
    public void Versions_are_one_row_per_version_newest_first_unsupported_listed_with_reason()
    {
        var (model, _) = Mixed();
        model.SetScope(SourceKind.Lunar);

        var versions = model.Versions();

        Assert.Equal(new[] { "1.21.1", "1.8.9", "1.21.5" }, versions.Select(v => v.Key));
        var unsupported = versions.Last();
        Assert.False(unsupported.Supported);
        Assert.Equal(BulkRules.UnsupportedCaption, unsupported.Reason);
        Assert.False(model.IsSelected(unsupported));
    }

    [Fact]
    public void Unknown_versions_are_grouped_and_disabled_never_dropped()
    {
        var model = new ImportFlowModel(new[]
        {
            Setup("Broken", null, SourceKind.Prism),
            Setup("Unreadable", "1.21.1", SourceKind.Prism, problem: "Can't read instance.cfg"),
            Setup("Good", "1.21.1", SourceKind.Prism),
        }, Supported);

        var versions = model.Versions();

        Assert.Equal(2, versions.Count);
        var unknown = versions.Last();
        Assert.Equal(ImportFlowModel.UnknownKey, unknown.Key);
        Assert.Equal("Version unknown", unknown.Title);
        Assert.False(unknown.Supported);
        Assert.Equal(2, unknown.Setups.Count);
    }

    [Fact]
    public void All_versions_is_tri_state_and_ignores_unsupported()
    {
        var (model, _) = Mixed();
        Assert.True(model.AllVersions());

        model.SetSelected("1.20.1", false);
        Assert.Null(model.AllVersions());

        model.SetAllVersions(false);
        Assert.False(model.AllVersions());
        Assert.Equal(0, model.SelectedVersionCount);

        model.SetAllVersions(true);
        Assert.True(model.AllVersions());
        Assert.Equal(3, model.SelectedVersionCount); // 1.21.1, 1.20.1, 1.8.9; never 1.21.5
        Assert.False(model.IsSelected(model.Version("1.21.5")!));
    }

    [Fact]
    public void Selection_scopes_to_the_launcher_and_leaves_out_unticked_versions_and_setups()
    {
        var (model, setups) = Mixed();
        model.SetScope(SourceKind.OfficialLauncher);
        model.SetSelected("1.20.1", false);

        var selection = model.Selection();

        Assert.True(selection.Includes(setups[3]));    // official 1.21.1
        Assert.False(selection.Includes(setups[4]));   // official 1.20.1: version unticked
        Assert.False(selection.Includes(setups[0]));   // Lunar: another launcher
        Assert.Single(model.AllGoing());

        model.SetSelected("1.20.1", true);
        model.SetTicked(setups[4], false);
        Assert.False(model.Selection().Includes(setups[4]));
        Assert.Equal("0 of 1 setup", model.VersionCaption(model.Version("1.20.1")!));
    }

    [Fact]
    public void Unsupported_setups_stay_in_the_selection_so_the_engine_reports_why()
    {
        var (model, setups) = Mixed();
        model.SetScope(SourceKind.Lunar);
        model.SetAllVersions(false);

        var selection = model.Selection();

        Assert.True(selection.Includes(setups[2]));    // 1.21.5: skipped by the engine with "Not supported"
        Assert.False(selection.Includes(setups[0]));
        Assert.False(selection.Includes(setups[1]));
    }

    [Fact]
    public void All_launchers_scope_selects_every_setup()
    {
        var (model, setups) = Mixed();
        var selection = model.Selection();
        Assert.All(setups, s => Assert.True(selection.Includes(s)));
        Assert.Equal(BulkSelection.All().Kind, selection.Kind);
    }

    [Fact]
    public void Bring_defaults_to_everything_but_worlds_and_can_be_set_per_version_or_for_all()
    {
        var (model, _) = Mixed();
        Assert.Equal(ImportFlowModel.DefaultBring, model.BringFor("1.21.1"));
        Assert.False(model.BringsAll(ImportCategories.Worlds));
        Assert.True(model.BringsAll(ImportCategories.Mods));

        model.SetBring("1.21.1", ImportCategories.Worlds, true);
        Assert.Null(model.BringsAll(ImportCategories.Worlds));

        model.SetBringAll(ImportCategories.Worlds, true);
        Assert.True(model.BringsAll(ImportCategories.Worlds));

        model.SetBringAll(ImportCategories.Servers, false);
        var map = model.BringMap();
        Assert.Equal(ImportCategories.All & ~ImportCategories.Servers, map.For("1.21.1"));
        Assert.Equal(ImportCategories.All & ~ImportCategories.Servers, map.For("1.8.9"));
        Assert.DoesNotContain("1.21.5", map.Entries.Keys); // unsupported: nothing to bring
        Assert.Equal("Server list", ImportFlowModel.LeftOutNames(map.For("1.20.1")));
    }

    [Fact]
    public void Bring_all_switch_follows_only_the_ticked_versions()
    {
        var (model, _) = Mixed();
        model.SetBring("1.8.9", ImportCategories.Mods, false);
        Assert.Null(model.BringsAll(ImportCategories.Mods));

        model.SetSelected("1.8.9", false);
        Assert.True(model.BringsAll(ImportCategories.Mods));
    }

    [Fact]
    public void Options_hand_worlds_and_screenshots_to_the_categories()
    {
        var (model, _) = Mixed();
        model.SetBring("1.21.1", ImportCategories.Screenshots, false);

        var options = model.Options(new ImportOptions { CopyWorlds = false, CopyScreenshots = false, KeepMyOriginSettings = true });

        Assert.True(options.CopyWorlds);
        Assert.True(options.CopyScreenshots);
        Assert.True(options.KeepMyOriginSettings);
        var forVersion = options.ForVersion("1.21.1");
        Assert.False(forVersion.CopyWorlds);          // worlds are off by default
        Assert.False(forVersion.CopyScreenshots);     // the player turned them off for 1.21.1
        Assert.True(options.ForVersion("1.20.1").CopyScreenshots);
        Assert.Equal(options, model.Options(new ImportOptions { KeepMyOriginSettings = true })); // value-equal: plans are reused
    }

    [Fact]
    public void Winner_follows_the_engine_rule_and_the_players_choice()
    {
        var model = new ImportFlowModel(new[]
        {
            Setup("Small", "1.21.1", SourceKind.Prism, hours: 2),
            Setup("Main", "1.21.1", SourceKind.Prism, hours: 200),
            Setup("Forge pack", "1.21.1", SourceKind.CurseForge, loader: LoaderKind.Forge, hours: 900),
        }, Supported);
        var version = model.Version("1.21.1")!;

        var preview = model.Winner(version);
        var participants = version.Setups.Where(s => !BulkRules.IsPacksOnly(s, "1.21.1")).OrderBy(s => s, BulkRanking.Order).ToList();
        var pick = VersionPicker.Pick(participants);

        Assert.Equal((pick.Preselected ?? participants[0]).Name, preview.Winner!.Name);
        Assert.Equal(pick.Mode == PickMode.Choose, preview.NeedsChoice);
        Assert.DoesNotContain(preview.Participants, s => s.Name == "Forge pack"); // packs-only setups never give settings

        var other = preview.Participants.First(s => s.Name != preview.Winner.Name);
        model.Choices["1.21.1"] = other.BulkId();
        var chosen = model.Winner(version);
        Assert.Equal(other.Name, chosen.Winner!.Name);
        Assert.False(chosen.NeedsChoice);
        Assert.Contains("settings from " + other.Name, model.VersionCaption(version));

        model.SetTicked(other, false); // the chosen setup left out: back to the rule
        Assert.NotEqual(other.Name, model.Winner(version).Winner!.Name);
    }

    [Fact]
    public void Contents_line_and_counts_follow_the_ticked_setups()
    {
        var (model, setups) = Mixed();
        Assert.Equal("", model.ContentsLine(setups));
        Assert.True(model.Has(setups, ImportCategories.Worlds)); // unknown before counting: switchable

        model.Contents = new Dictionary<string, SetupContents>
        {
            [setups[3].SetupId] = new(Mods: 12, Worlds: 2, ResourcePacks: 2, ShaderPacks: 1, Servers: true, Settings: true),
            [setups[4].SetupId] = new(Settings: true),
        };

        Assert.Equal("12 mods, 2 worlds, 3 packs, servers", model.ContentsLine(new[] { setups[3] }));
        Assert.Equal("Settings only", model.ContentsLine(new[] { setups[4] }));
        Assert.Equal("12", model.CountText(new[] { setups[3] }, ImportCategories.Mods));
        Assert.Equal("", model.CountText(new[] { setups[3] }, ImportCategories.Servers));
        Assert.False(model.Has(new[] { setups[4] }, ImportCategories.Worlds));
    }

    [Fact]
    public void Shows_hides_setups_of_other_launchers_from_review_and_done()
    {
        var (model, setups) = Mixed();
        model.SetScope(SourceKind.Lunar);

        Assert.False(model.Shows(new SkippedSetup(setups[3], "x", SkipKind.NotSelected)));
        Assert.True(model.Shows(new SkippedSetup(setups[2], "x", SkipKind.Unsupported)));
        Assert.True(model.Shows(new SkippedSetup(setups[0], "x", SkipKind.Unticked)));
        Assert.False(model.Shows(new SkippedSetup(setups[4], "x", SkipKind.Unticked)));
    }
}
