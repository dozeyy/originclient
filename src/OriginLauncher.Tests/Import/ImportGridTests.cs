using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests.Import;

/// <summary>
/// Imports go into plain Origin, never a profile: the one-launcher fast path ("Bring everything from Prism") and the
/// "Choose what to import" grid (a row per launcher, a tick per thing, Settings one launcher per version).
/// Temp folders only (BulkFixture); the grid model is pure.
/// </summary>
public sealed class ImportGridTests : IDisposable
{
    private readonly BulkFixture _f = new();

    public void Dispose() => _f.Dispose();

    // The options the Import page plans the fast path and the grid with (ImportFlowModel.Options outside Ticks mode).
    private static readonly ImportOptions Fast = new() { CopyWorlds = true, CopyScreenshots = true, CopyUnusedPacks = true };

    private static string Fov(string fov) => TypicalSource.Options.Replace("fov:0.5", "fov:" + fov);

    // ------------------------------------------------------------ the fast path (one launcher, everything)

    [Fact]
    public async Task TheFastPathMakesNoProfileAndEveryVersionGetsTheMostPlayedSetupsSettings()
    {
        var a = _f.Add("Survival", "1.21.1", playtimeHours: 20);
        var b = _f.Add("Skyblock", "1.20.4", playtimeHours: 50);              // the most played of all
        var c = _f.Add("Testing", "1.21.1", playtimeHours: 2);
        _f.Add("Other launcher", "1.21.1", kind: SourceKind.Modrinth, playtimeHours: 500); // not this import
        Kit.Write(a.GameDir, "options.txt", Fov("0.2"));
        Kit.Write(b.GameDir, "options.txt", Fov("0.9"));
        Kit.Write(c.GameDir, "options.txt", Fov("0.1"));

        var request = _f.Request(BulkSelection.OfLauncher(SourceKind.Prism), Fast);
        Assert.True(request.SameSettingsEverywhere);
        var plan = await _f.Plan(_f.Engine(), request);
        var result = await _f.Engine().ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.All(plan.Groups, g => Assert.Same(b, g.Winner));
        Assert.Contains("fov:0.9", File.ReadAllText(_f.Target("1.21.1", "options.txt")));
        Assert.Contains("fov:0.9", File.ReadAllText(_f.Target("1.20.4", "options.txt")));
        Assert.DoesNotContain(plan.AllSetups, s => s.Setup.Kind == SourceKind.Modrinth);
        // No profile, no picked version changed, no profiles folder.
        var settings = _f.Rig.Settings.Load();
        Assert.Empty(settings.Profiles);
        Assert.Null(settings.SelectedProfileId);
        Assert.Equal("1.21.11", settings.SelectedVersion);
        Assert.False(Directory.Exists(Path.Combine(_f.Rig.Instances, "1.21.1", "profiles")));
    }

    [Fact]
    public async Task ModsComeFromTheMostPlayedSetupOfEachVersionWhilePacksAndWorldsAreUnioned()
    {
        var a = _f.Add("Survival", "1.21.1", playtimeHours: 20);
        var c = _f.Add("Testing", "1.21.1", playtimeHours: 2);
        Kit.WriteBytes(a.GameDir, "mods/aonly.jar", Kit.FabricJar("aonly", "1.0"));
        Kit.WriteBytes(c.GameDir, "mods/conly.jar", Kit.FabricJar("conly", "1.0"));
        Kit.WriteBytes(c.GameDir, "resourcepacks/TestingPack.zip", Kit.Zip(Kit.Text("pack.mcmeta", "testing")));
        Kit.WriteBytes(c.GameDir, "shaderpacks/TestingShader.zip", Kit.Zip(Kit.Text("shaders/t.fsh", "t")));
        Kit.Write(c.GameDir, "saves/Testing World/level.dat", "LEVEL");

        var plan = await _f.Plan(_f.Engine(), _f.Request(BulkSelection.OfLauncher(SourceKind.Prism), Fast));
        var result = await _f.Engine().ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var shared = Path.Combine(_f.Rig.Instances, "1.21.1", "mods");
        Assert.True(File.Exists(Path.Combine(shared, "aonly.jar")));
        Assert.False(File.Exists(Path.Combine(shared, "conly.jar")));           // one setup's mods, never a union
        Assert.DoesNotContain(plan.AllSetups.Single(s => s.Setup == c).Plan.Ops, o => o.Item == PlanItemKind.Mod);
        Assert.True(File.Exists(_f.Target("1.21.1", "resourcepacks/TestingPack.zip")));
        Assert.True(File.Exists(_f.Target("1.21.1", "shaderpacks/TestingShader.zip")));
        Assert.True(File.Exists(_f.Target("1.21.1", "saves/Testing World/level.dat")));
        Assert.True(File.Exists(_f.Target("1.21.1", "resourcepacks/Faithful.zip")));
        // The packs that were not brought by the settings setup are copied, never switched on.
        Assert.DoesNotContain("TestingPack", File.ReadAllText(_f.Target("1.21.1", "options.txt")));
    }

    [Fact]
    public async Task ServersAreAppendedAfterOriginsOwnWithEachAddressOnce()
    {
        var a = _f.Add("Survival", "1.21.1", playtimeHours: 20);
        var c = _f.Add("Testing", "1.21.1", playtimeHours: 2);
        Kit.WriteBytes(a.GameDir, "servers.dat", Kit.ServersDat(("Hub", "play.example.net"), ("Mine again", "MINE.example.net:25565")));
        Kit.WriteBytes(c.GameDir, "servers.dat", Kit.ServersDat(("Hub too", "PLAY.example.net."), ("Mini", "mini.example.net")));
        Kit.WriteBytes(_f.Target("1.21.1"), "servers.dat", Kit.ServersDat(("Mine", "mine.example.net")));

        var plan = await _f.Plan(_f.Engine(), _f.Request(BulkSelection.OfLauncher(SourceKind.Prism), Fast));
        var result = await _f.Engine().ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var servers = Kit.Servers(_f.Target("1.21.1", "servers.dat"))!;
        Assert.Equal(new[] { "mine.example.net", "play.example.net", "mini.example.net" }, servers.Select(s => s.Ip));
        Assert.Equal("Mine", servers[0].Name);                                    // Origin's own: first, never dropped
    }

    [Fact]
    public async Task ImportEverythingAcrossLaunchersMakesNoProfileEither()
    {
        _f.Add("Survival", "1.21.1", playtimeHours: 20);
        _f.Add("Fabulously Optimized", "1.21.1", kind: SourceKind.Modrinth, playtimeHours: 5);
        _f.Add("Skyblock", "1.20.4", kind: SourceKind.CurseForge, playtimeHours: 9);

        var plan = await _f.Plan(_f.Engine());
        var result = await _f.Engine().ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Empty(_f.Rig.Settings.Load().Profiles);
        Assert.Equal("1.21.11", _f.Rig.Settings.Load().SelectedVersion);
    }

    // ------------------------------------------------------------ the grid model

    private static DetectedInstance Setup(string name, SourceKind kind, string mc, long hours, LoaderKind loader = LoaderKind.Fabric) => new()
    {
        Kind = kind, Name = name, GameDir = Path.Combine("C:", "nowhere", kind.ToString(), name), McVersion = mc, Loader = loader,
        PlaytimeSeconds = hours * 3600, Confidence = Confidence.Exact, VersionCertain = true,
    };

    private static List<DetectedInstance> TwoLaunchersTwoVersions() => new()
    {
        Setup("Survival", SourceKind.Prism, "1.21.1", 40),
        Setup("Skyblock", SourceKind.Prism, "1.20.4", 30),
        Setup("FO", SourceKind.Modrinth, "1.21.1", 5),
        Setup("FO old", SourceKind.Modrinth, "1.20.4", 2),
    };

    [Fact]
    public void SettingsIsOneLauncherForTheWholeGridARadio()
    {
        // Will, 2026-10-19: only ONE launcher's settings, across every version, so nothing conflicts.
        var grid = new ImportGridModel(TwoLaunchersTwoVersions());
        Assert.Equal(SourceKind.Prism, grid.SettingsLauncher);                     // the most played on both versions
        Assert.Equal(SourceKind.Prism, grid.SettingsFrom("1.21.1"));
        Assert.Equal(SourceKind.Prism, grid.SettingsFrom("1.20.4"));

        grid.SetTab("1.21.1");                                                    // a tab changes the one pick for every version
        grid.Toggle(SourceKind.Modrinth, GridColumn.Settings);

        Assert.Equal(SourceKind.Modrinth, grid.SettingsLauncher);
        Assert.True(grid.Cell(SourceKind.Modrinth, GridColumn.Settings).On);
        Assert.False(grid.Cell(SourceKind.Prism, GridColumn.Settings).On);        // a radio: the old pick turned off
        Assert.Equal(SourceKind.Modrinth, grid.SettingsFrom("1.20.4"));
        grid.SetTab(ImportGridModel.AllTab);
        Assert.True(grid.Cell(SourceKind.Modrinth, GridColumn.Settings).On);
        Assert.False(grid.Cell(SourceKind.Prism, GridColumn.Settings).On);
        var engine = grid.Grid();
        Assert.Equal(SourceKind.Modrinth, engine.ChoseSettings("1.21.1", out var a) ? a : null);
        Assert.Equal(SourceKind.Modrinth, engine.ChoseSettings("1.20.4", out var b) ? b : null);

        grid.Toggle(SourceKind.Modrinth, GridColumn.Settings);                    // off: no settings come at all
        Assert.Null(grid.SettingsLauncher);
        Assert.All(grid.Versions, v => Assert.Null(grid.SettingsFrom(v)));
    }

    [Fact]
    public void AVersionTheSettingsLauncherDoesntHaveKeepsOriginsOwnAndTheTipSaysSo()
    {
        var grid = new ImportGridModel(new[]
        {
            Setup("Survival", SourceKind.Prism, "1.21.1", 40),
            Setup("PvP", SourceKind.Modrinth, "1.20.4", 60),
            Setup("PvP new", SourceKind.Modrinth, "1.21.1", 1),
        });
        // Modrinth is the engine's pick on 1.20.4 only, Prism on 1.21.1 only: a tie goes to the newest version's.
        Assert.Equal(SourceKind.Prism, grid.SettingsLauncher);
        Assert.Null(grid.SettingsFrom("1.20.4"));
        Assert.Contains("Minecraft 1.20.4 keeps Origin's own", grid.Cell(SourceKind.Prism, GridColumn.Settings).Tip);
        Assert.False(grid.Grid().ChoseSettings("1.20.4", out var none) && none != null);
    }

    [Fact]
    public void ACellWithNothingBehindItIsGreyedWithTheReason()
    {
        var lunar = Setup("Lunar 1.21.1", SourceKind.Lunar, "1.21.1", 8, LoaderKind.None);
        var forge = Setup("Create", SourceKind.Modrinth, "1.21.1", 3, LoaderKind.Forge);
        var prism = Setup("Survival", SourceKind.Prism, "1.21.1", 40);
        var grid = new ImportGridModel(new[] { lunar, forge, prism })
        {
            Contents = new Dictionary<string, SetupContents>
            {
                [lunar.SetupId] = new(Mods: 0, Worlds: 2, ResourcePacks: 1, Servers: true, Settings: true),
                [forge.SetupId] = new(Mods: 40, Worlds: 1, ResourcePacks: 2, Servers: true, Settings: true),
                [prism.SetupId] = new(Mods: 12, Worlds: 3, ResourcePacks: 0, ShaderPacks: 1, Servers: true, Settings: true),
            },
        };

        var lunarMods = grid.Cell(SourceKind.Lunar, GridColumn.Mods);
        Assert.False(lunarMods.Enabled);
        Assert.Equal("Lunar's mods are built in, not files", lunarMods.Tip);

        foreach (var column in new[] { GridColumn.Mods, GridColumn.Worlds, GridColumn.Servers })
        {
            var cell = grid.Cell(SourceKind.Modrinth, column);
            Assert.False(cell.Enabled);
            Assert.Equal(ImportGridModel.PacksOnlyReason, cell.Tip);
        }
        Assert.False(grid.Cell(SourceKind.Modrinth, GridColumn.Settings).Enabled);  // Forge on Fabric: packs only
        Assert.True(grid.Cell(SourceKind.Modrinth, GridColumn.ResourcePacks).Enabled);

        var prismPacks = grid.Cell(SourceKind.Prism, GridColumn.ResourcePacks);
        Assert.False(prismPacks.Enabled);
        Assert.StartsWith("No resource packs in ", prismPacks.Tip);

        // A greyed cell does not change when pressed.
        var key = grid.Key();
        grid.Toggle(SourceKind.Lunar, GridColumn.Mods);
        Assert.Equal(key, grid.Key());
    }

    [Fact]
    public void TabsShowOnlyWithMoreThanOneVersionAllVersionsFirst()
    {
        var one = new ImportGridModel(new[] { Setup("Survival", SourceKind.Prism, "1.21.1", 4), Setup("FO", SourceKind.Modrinth, "1.21.1", 2) });
        Assert.False(one.ShowTabs);
        Assert.Empty(one.Tabs);
        Assert.Equal("1.21.1", one.Tab);

        var two = new ImportGridModel(TwoLaunchersTwoVersions());
        Assert.Equal(new[] { ImportGridModel.AllTab, "1.21.1", "1.20.4" }, two.Tabs);
        Assert.Equal(ImportGridModel.AllTab, two.Tab);
        Assert.Equal("All versions", ImportGridModel.TabTitle(two.Tab));
    }

    [Fact]
    public void ModsFromTwoLaunchersOnOneVersionAreAllowedWithTheWarning()
    {
        var setups = TwoLaunchersTwoVersions();
        var grid = new ImportGridModel(setups);
        Assert.Null(grid.Warning());                                              // the default: one launcher's mods per version
        grid.SetTab("1.21.1");
        var off = grid.Cell(SourceKind.Prism, GridColumn.Mods).On == true ? SourceKind.Modrinth : SourceKind.Prism;

        grid.Toggle(off, GridColumn.Mods);

        Assert.Equal("Mods from different launchers might not work together.", grid.Warning());
        grid.SetTab("1.20.4");                                                    // shown whatever the tab
        Assert.Equal(ImportGridModel.MixedModsWarning, grid.Warning());
        grid.SetTab(ImportGridModel.AllTab);
        Assert.Equal(ImportGridModel.MixedModsWarning, grid.Warning());
        Assert.Equal(new[] { "1.21.1" }, grid.MixedModsVersions());
        var composition = BulkComposer.Compose(setups, grid.Grid());
        Assert.Contains(composition, c => c.Version == "1.21.1" && c.MixedMods);
        Assert.DoesNotContain(composition, c => c.Version == "1.20.4" && c.MixedMods);
    }

    [Fact]
    public void TwoLaunchersModsOnDifferentVersionsNeverWarn()
    {
        // Each version has its own mods folder: Prism's 1.21.1 mods and Modrinth's 1.20.4 mods never meet.
        var grid = new ImportGridModel(new[] { Setup("Survival", SourceKind.Prism, "1.21.1", 40), Setup("FO", SourceKind.Modrinth, "1.20.4", 5) });
        Assert.True(grid.Cell(SourceKind.Prism, GridColumn.Mods).On);
        Assert.True(grid.Cell(SourceKind.Modrinth, GridColumn.Mods).On);
        Assert.Null(grid.Warning());
    }

    [Fact]
    public void AnUntouchedGridPlansExactlyLikeImportEverything()
    {
        var setups = TwoLaunchersTwoVersions();
        setups.Add(Setup("Create", SourceKind.CurseForge, "1.20.4", 90, LoaderKind.Forge));
        var grid = new ImportGridModel(setups);

        static string Shape(IEnumerable<VersionComposition> c) => string.Join(";", c.Select(v =>
            v.Version + ":" + v.SettingsFrom?.Name + ":" + string.Join(",", v.Order.Select(o => $"{o.Setup.Name}={(int)o.Brings}"))));

        Assert.Equal(Shape(BulkComposer.Compose(setups, null)), Shape(BulkComposer.Compose(setups, grid.Grid())));
        Assert.False(grid.BringsNothing);
    }

    [Fact]
    public void EveryTilesFastButtonLabelIsShortEnoughForTheSmallestWindow()
    {
        // 228 px at the minimum window held 34 characters with room; "Minecraft Launcher" (40) trimmed in CI.
        foreach (var kind in Enum.GetValues<SourceKind>())
            Assert.True(("Bring everything from " + ImportGridModel.ShortName(kind)).Length <= 34, kind.ToString());
    }

    [Fact]
    public void UntickingEveryCellBringsNothing()
    {
        var grid = new ImportGridModel(new[] { Setup("Survival", SourceKind.Prism, "1.21.1", 4) });
        foreach (var column in ImportGridModel.Columns)
            if (grid.Cell(SourceKind.Prism, column).On == true) grid.Toggle(SourceKind.Prism, column);

        Assert.True(grid.BringsNothing);
        Assert.Null(grid.SettingsFrom("1.21.1"));
    }
}
