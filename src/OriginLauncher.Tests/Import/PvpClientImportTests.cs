using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Detection;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Lunar Client, Feather, Badlion, LabyMod 4 and Essential (Will, 2026-10-19: "check if the import thing imports
// from lunar and other client like it"). Every client folder here is a fake built in a temp folder; no real
// client is installed, run or read. Layouts and their sources: Sources/PvpClientSource.cs.
public class PvpClientImportTests : IDisposable
{
    private readonly Rig _rig = new();
    private string Pc => Path.Combine(_rig.Root, "pc");
    private string Minecraft => Path.Combine(Pc, "AppData", ".minecraft");
    private string Lunar => Path.Combine(Pc, "User", ".lunarclient");
    private string Feather => Path.Combine(Pc, "AppData", ".feather");
    private string Badlion => Path.Combine(Pc, "AppData", "Badlion Client");

    public void Dispose() => _rig.Dispose();

    // What a PvP player's .minecraft holds: settings last written by 1.21.1, a world, a pack in use, a shader
    // pack, a server list, a log from the game. No launcher_profiles.json: the client installed the game itself.
    private void PlayerMinecraft()
    {
        Kit.Write(Minecraft, "options.txt", "version:3955\nfov:0.75\nresourcePacks:[\"vanilla\",\"file/PvP Pack.zip\"]\n");
        Kit.WriteBytes(Minecraft, "resourcepacks/PvP Pack.zip", Kit.Zip(Kit.Text("pack.mcmeta", "{}")));
        Kit.WriteBytes(Minecraft, "shaderpacks/Soft.zip", Kit.Zip(Kit.Text("shaders/final.fsh", "void main(){}")));
        Kit.Write(Minecraft, "saves/Bedwars Practice/level.dat", "LEVEL");
        Kit.Write(Minecraft, "saves/Bedwars Practice/region/r.0.0.mca", "REGION");
        Kit.WriteBytes(Minecraft, "servers.dat", new byte[] { 10, 0, 0, 9, 0, 7, 115, 101, 114, 118, 101, 114, 115, 0, 0, 0, 0, 0 });
    }

    // Lunar as it is on a real install (read-only look at Will's PC, 2026-10-19): one folder per version line under
    // profiles/ with its settings, servers, packs and "shaders"; the player's Fabric mods in mods/fabric-<version>,
    // beside a dependencies/ folder of libraries Lunar unpacked from them; worlds and screenshots in the game
    // directory settings/launcher.json names.
    private string LunarWithFabricMods(string version = "1.21.1", string line = "1.21", string? gameDirectory = null)
    {
        var profile = Path.Combine(Lunar, "profiles", line);
        Kit.WriteBytes(profile, $"mods/fabric-{version}/lithium.jar", Kit.FabricJar("lithium", "0.15.0", minecraft: $"~{version}"));
        Kit.WriteBytes(profile, $"mods/fabric-{version}/appleskin.jar", Kit.FabricJar("appleskin", "3.0.5", minecraft: $"~{version}"));
        Kit.WriteBytes(profile, $"mods/fabric-{version}/dependencies/nested-lib.jar", Kit.FabricJar("nestedlib", "1.0.0"));
        Kit.Write(profile, "options.txt", "version:4671\nfov:0.9\nresourcePacks:[\"vanilla\",\"file/Lunar Pack.zip\"]\n");
        Kit.Write(profile, "optionsLC.txt", "lunar-only");
        Kit.WriteBytes(profile, "resourcepacks/Lunar Pack.zip", Kit.Zip(Kit.Text("pack.mcmeta", "{}")));
        Kit.WriteBytes(profile, "shaders/Lunar Shader.zip", Kit.Zip(Kit.Text("shaders/final.fsh", "void main(){}")));
        Kit.WriteBytes(profile, "servers.dat", new byte[] { 10, 0, 0, 9, 0, 7, 115, 101, 114, 118, 101, 114, 115, 0, 0, 0, 0, 0 });
        Kit.Write(Lunar, "settings/launcher.json", "{\"settings\":{\"gameDirectory\":"
            + System.Text.Json.JsonSerializer.Serialize(gameDirectory ?? Minecraft) + ",\"selectedProfile\":\"x\"},\"trackingId\":\"t\"}");
        // Lunar's own HUD layouts and its login file: never opened.
        Kit.Write(Lunar, "settings/game/accounts.json", "{\"accounts\":{\"x\":{\"accessToken\":\"SECRET-LUNAR-TOKEN\"}}}");
        Kit.Write(Lunar, "settings/game/default/mods.json", "{\"keystrokes\":{\"enabled\":true}}");
        return profile;
    }

    private SourceRoots Roots() => ImportRoots.Sources(new MachineFolders(
        Path.Combine(Pc, "AppData"), Path.Combine(Pc, "Local"), Path.Combine(Pc, "User"), Path.Combine(Pc, "Docs")));

    private Task<ScanResult> Scan() => _rig.Engine().ScanAsync(Roots(), ImportEngine.DefaultSources(), null, CancellationToken.None);

    private async Task<ImportPlan> Plan(DetectedInstance setup, bool worlds = true) =>
        await _rig.Engine().PlanAsync(_rig.Request(options: new ImportOptions { CopyWorlds = worlds }) with { Source = setup },
            null, CancellationToken.None);

    private string ProfileMods(ImportPlan plan) => InstanceLayout.ProfileModsDir(_rig.Instances, "1.21.1", plan.ProfileId!);

    private void AssertNoSecretOpened()
    {
        Assert.DoesNotContain(_rig.Opened, p => Path.GetFileName(p).Contains("account", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(_rig.Opened, p => p.Contains(Path.Combine(".lunarclient", "settings", "game"), StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void TheMachineFoldersOfEachClientAreFoundWhereTheyInstall()
    {
        Directory.CreateDirectory(Lunar);
        Directory.CreateDirectory(Feather);
        Directory.CreateDirectory(Badlion);
        var roots = Roots();
        Assert.Equal(Lunar, roots.LunarDir);
        Assert.Equal(Feather, roots.FeatherDir);
        Assert.Equal(Badlion, roots.BadlionDir);

        Directory.Delete(Feather);
        Assert.Null(Roots().FeatherDir);
    }

    [Fact]
    public async Task LunarsOwnFabricModsAndItsMinecraftFolderBothShowUpAndImport()
    {
        PlayerMinecraft();
        var profile = LunarWithFabricMods();
        var lunarBefore = Kit.TreeHash(Lunar, mtimes: true);
        var minecraftBefore = Kit.TreeHash(Minecraft, mtimes: true);

        var scan = await Scan();

        var mods = Assert.Single(scan.Instances, s => s.ModsDir != null);
        Assert.Equal(SourceKind.Lunar, mods.Kind);
        Assert.Equal("1.21.1", mods.Name);
        Assert.Equal("1.21.1", mods.McVersion);
        Assert.Equal(LoaderKind.Fabric, mods.Loader);
        Assert.True(mods.VersionCertain, mods.UncertainReason);
        Assert.Equal(profile, mods.GameDir);       // settings, servers and packs: the version line's own
        Assert.Equal(Minecraft, mods.SharedGameDir); // worlds and screenshots: Lunar's game directory
        var folder = Assert.Single(scan.Instances, s => s.ModsDir == null);
        Assert.Equal(SourceKind.Lunar, folder.Kind); // only Lunar is on this PC, so its .minecraft is named for it
        Assert.Equal("1.21.1", folder.McVersion);
        Assert.NotEqual(mods.SetupId, folder.SetupId);
        Assert.Equal(SetupKey.StableId(Minecraft), folder.SetupId); // a plain folder's id is what it always was

        var plan = await Plan(mods);
        Assert.True(plan.CanApply, string.Join(" ", plan.Blocked));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name == "Lunar Client's built-in mods"
            && l.Reason!.Contains("Right Shift"));
        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);

        Assert.True(File.Exists(Path.Combine(ProfileMods(plan), "lithium.jar")));
        Assert.True(File.Exists(Path.Combine(ProfileMods(plan), "appleskin.jar")));
        Assert.False(File.Exists(Path.Combine(ProfileMods(plan), "nested-lib.jar"))); // Lunar's unpacked copy, not the player's
        Assert.False(Directory.Exists(Path.Combine(ProfileMods(plan), "dependencies")));
        var game = _rig.GameDir();
        Assert.True(Kit.Exists(game, "resourcepacks/Lunar Pack.zip"));
        Assert.True(Kit.Exists(game, "shaderpacks/Lunar Shader.zip"));   // Lunar's "shaders" folder
        Assert.True(Kit.Exists(game, "saves/Bedwars Practice/region/r.0.0.mca"));
        Assert.True(Kit.Exists(game, "servers.dat"));
        Assert.Contains("fov:0.9", Kit.Read(game, "options.txt"));
        Assert.False(Kit.Exists(game, "optionsLC.txt"));
        Assert.Equal("Lunar: 1.21.1", Assert.Single(_rig.Settings.Load().Profiles).Name);

        Assert.Equal(lunarBefore, Kit.TreeHash(Lunar, mtimes: true));
        Assert.Equal(minecraftBefore, Kit.TreeHash(Minecraft, mtimes: true));
        AssertNoSecretOpened();
    }

    [Fact]
    public async Task ImportingTheSameLunarSetupTwiceAddsNothingTheSecondTime()
    {
        PlayerMinecraft();
        LunarWithFabricMods();
        var mods = Assert.Single((await Scan()).Instances, s => s.ModsDir != null);
        Assert.True((await _rig.Engine().ApplyAsync(await Plan(mods), _rig.Settings, null, CancellationToken.None)).Succeeded);

        var again = await _rig.Engine().PlanAsync(_rig.Request(options: new ImportOptions { CopyWorlds = true },
            profiles: _rig.Settings.Load().Profiles) with { Source = mods }, null, CancellationToken.None);
        Assert.True((await _rig.Engine().ApplyAsync(again, _rig.Settings, null, CancellationToken.None)).Succeeded);

        Assert.Single(_rig.Settings.Load().Profiles);
        Assert.False(Directory.Exists(Path.Combine(_rig.GameDir(), "saves", "Bedwars Practice (imported)")));
    }

    [Fact]
    public async Task EveryLunarVersionFolderIsItsOwnSetupAndOneWithoutJarsIsNot()
    {
        PlayerMinecraft();
        LunarWithFabricMods("1.21.1");
        LunarWithFabricMods("1.21.11");
        LunarWithFabricMods("1.20.4", line: "1.20");
        Directory.CreateDirectory(Path.Combine(Lunar, "profiles", "1.21", "mods", "fabric-1.21.4")); // empty
        Kit.WriteBytes(Lunar, "profiles/26/mods/fabric-26.2-snapshot-4/a.jar", Kit.FabricJar("a"));  // a snapshot: not offered
        Kit.Write(Lunar, "profiles/pvp/mods/fabric-1.21.1/readme.txt", "not a mod");               // no jar
        Kit.Write(Lunar, "profiles/broken/mods", "a file where a folder should be");

        var scan = await Scan();

        var versions = scan.Instances.Where(s => s.ModsDir != null).Select(s => s.McVersion).OrderBy(v => v).ToList();
        Assert.Equal(new[] { "1.20.4", "1.21.1", "1.21.11" }, versions);
        Assert.Equal(new[] { "1.20.4", "1.21.1", "1.21.11" },
            scan.Instances.Where(s => s.Kind == SourceKind.Lunar && s.ModsDir != null).Select(s => s.Name).OrderBy(n => n));
        Assert.Empty(scan.Notes);
    }

    [Fact]
    public async Task LunarsOneEightLineWithoutFabricStillBringsItsSettingsServersAndPacks()
    {
        Kit.Write(Lunar, "profiles/1.8/options.txt", "fov:0.5\nresourcePacks:[\"Old Pack.zip\"]\n");
        Kit.WriteBytes(Lunar, "profiles/1.8/servers.dat", new byte[] { 10, 0, 0, 0 });
        Kit.WriteBytes(Lunar, "profiles/1.8/resourcepacks/Old Pack.zip", Kit.Zip(Kit.Text("pack.mcmeta", "{}")));
        Directory.CreateDirectory(Path.Combine(Lunar, "profiles", "1.8", "mods", "ichor-1.8.9")); // Lunar's own 1.8 loader
        Kit.Write(Lunar, "profiles/26/logs/latest.log", "nothing to bring");                     // no settings: not a setup

        var lunar = Assert.Single((await Scan()).Instances, s => s.Kind == SourceKind.Lunar);

        Assert.Equal("1.8.9", lunar.Name);
        Assert.Equal("1.8.9", lunar.McVersion);
        Assert.Null(lunar.ModsDir);
        Assert.Equal(Path.Combine(Lunar, "profiles", "1.8"), lunar.GameDir);
    }

    [Fact]
    public async Task LunarsChosenGameDirectoryIsWhereWorldsAndScreenshotsComeFrom()
    {
        var custom = Path.Combine(Pc, "Games", "lunar-game");
        Kit.Write(custom, "saves/Practice/level.dat", "LEVEL");
        Kit.Write(custom, "screenshots/win.png", "PNG");
        LunarWithFabricMods(gameDirectory: custom);

        var mods = Assert.Single((await Scan()).Instances, s => s.ModsDir != null);
        Assert.Equal(custom, mods.SharedGameDir);
        var result = await _rig.Engine().ApplyAsync(await Plan(mods), _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.True(Kit.Exists(_rig.GameDir(), "saves/Practice/level.dat"));
        Assert.True(Kit.Exists(_rig.GameDir(), "screenshots/win.png"));

        // A game directory that is gone falls back to .minecraft rather than losing the setup.
        Directory.Delete(custom, recursive: true);
        PlayerMinecraft();
        var fallback = Assert.Single((await Scan()).Instances, s => s.ModsDir != null);
        Assert.Equal(Minecraft, fallback.SharedGameDir);
        AssertNoSecretOpened();
    }

    [Fact]
    public async Task ALunarFolderOfModsForAnotherVersionIsAskedAboutNotImportedSilently()
    {
        PlayerMinecraft();
        Kit.WriteBytes(Lunar, "profiles/1.21/mods/fabric-1.21.1/old.jar", Kit.FabricJar("old", minecraft: "1.20.1"));
        Kit.WriteBytes(Lunar, "profiles/1.21/mods/fabric-1.21.1/old2.jar", Kit.FabricJar("old2", minecraft: "1.20.1"));

        var mods = Assert.Single((await Scan()).Instances, s => s.ModsDir != null);

        Assert.False(mods.VersionCertain);
    }

    [Fact]
    public async Task FeathersOwnFabricModsImportWithTheMinecraftFoldersSettings()
    {
        PlayerMinecraft();
        Kit.WriteBytes(Feather, "user-mods/1.21.1/sodium-extra.jar", Kit.FabricJar("sodium-extra", minecraft: "~1.21.1"));
        Kit.Write(Feather, "accounts.json", "{\"token\":\"SECRET-FEATHER\"}");
        var before = Kit.TreeHash(Feather, mtimes: true);

        var scan = await Scan();
        var mods = Assert.Single(scan.Instances, s => s.ModsDir != null);
        Assert.Equal(SourceKind.Feather, mods.Kind);
        Assert.Equal(SourceKind.Feather, Assert.Single(scan.Instances, s => s.ModsDir == null).Kind);

        var plan = await Plan(mods, worlds: false);
        Assert.True((await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None)).Succeeded);
        Assert.True(File.Exists(Path.Combine(ProfileMods(plan), "sodium-extra.jar")));
        Assert.True(Kit.Exists(_rig.GameDir(), "resourcepacks/PvP Pack.zip"));
        Assert.Contains(plan.Lines, l => l.Name == "Feather Client's built-in mods");
        Assert.Equal(before, Kit.TreeHash(Feather, mtimes: true));
        AssertNoSecretOpened();
    }

    [Fact]
    public async Task LabyModsFabricModsInsideMinecraftImportAndItsAddonsStayBehind()
    {
        PlayerMinecraft();
        Kit.WriteBytes(Minecraft, "labymod-neo/fabric/1.21.1/mods/zoomify.jar", Kit.FabricJar("zoomify", minecraft: "~1.21.1"));
        Kit.WriteBytes(Minecraft, "labymod-neo/addons/voicechat.jar", Kit.Zip(Kit.Text("addon.json", "{}")));

        var scan = await Scan();
        var mods = Assert.Single(scan.Instances, s => s.ModsDir != null);
        Assert.Equal(SourceKind.LabyMod, mods.Kind);

        var plan = await Plan(mods, worlds: false);
        Assert.True((await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None)).Succeeded);
        var copied = Directory.GetFiles(ProfileMods(plan)).Select(Path.GetFileName).ToList();
        Assert.Equal(new[] { "zoomify.jar" }, copied);
        Assert.Contains(plan.Lines, l => l.Name == "LabyMod's built-in mods");
    }

    [Fact]
    public async Task ABadlionPlayersMinecraftFolderIsFoundEvenWithNoLauncherProfiles()
    {
        PlayerMinecraft();
        Directory.CreateDirectory(Badlion);

        var scan = await Scan();
        var folder = Assert.Single(scan.Instances);
        Assert.Equal(SourceKind.Badlion, folder.Kind);
        Assert.Equal("Badlion Client", ImportLogic.GroupTitle(folder.SourceLabel));

        var plan = await Plan(folder);
        Assert.True((await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None)).Succeeded);
        Assert.True(Kit.Exists(_rig.GameDir(), "saves/Bedwars Practice/level.dat"));
        Assert.Contains(plan.Lines, l => l.Name == "Badlion Client's built-in mods");
    }

    [Fact]
    public async Task WithSeveralClientsOnThePcTheMinecraftFolderIsNotPinnedOnAnyOne()
    {
        PlayerMinecraft();
        Directory.CreateDirectory(Badlion);
        Directory.CreateDirectory(Feather);

        var folder = Assert.Single((await Scan()).Instances);

        Assert.Equal(SourceKind.OfficialLauncher, folder.Kind);
        Assert.Null(PvpClientSource.BuiltInModsNote(folder.Kind));
    }

    [Fact]
    public async Task WhenTheOfficialLauncherListsMinecraftItIsNotListedASecondTime()
    {
        PlayerMinecraft();
        LunarWithFabricMods();
        Kit.Write(Minecraft, "launcher_profiles.json",
            """{"profiles":{"p":{"name":"Vanilla","type":"custom","lastVersionId":"1.21.1","lastUsed":"2026-10-01T10:00:00.000Z"}}}""");

        var scan = await Scan();

        Assert.Single(scan.Instances, s => s.ModsDir == null && s.Kind == SourceKind.OfficialLauncher);
        Assert.Single(scan.Instances, s => s.ModsDir != null && s.Kind == SourceKind.Lunar);
        Assert.Equal(2, scan.Instances.Count);
    }

    [Fact]
    public async Task EssentialIsAnOrdinaryModAndItsAccountFileIsNeverRead()
    {
        PlayerMinecraft();
        Directory.CreateDirectory(Badlion);
        Kit.WriteBytes(Minecraft, "mods/essential.jar", Kit.FabricJar("essential-container", minecraft: "~1.21.1"));
        Kit.Write(Minecraft, "essential/microsoft_accounts.json", "{\"refresh\":\"SECRET-ESSENTIAL\"}");

        var folder = Assert.Single((await Scan()).Instances);
        var plan = await Plan(folder, worlds: false);
        Assert.True((await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None)).Succeeded);

        Assert.True(File.Exists(Path.Combine(ProfileMods(plan), "essential.jar")));
        Assert.False(Kit.Exists(_rig.GameDir(), "essential"));
        AssertNoSecretOpened();
    }

    [Fact]
    public async Task NoClientsAndNoMinecraftFolderMeansNothingFoundAndNoNotes()
    {
        Directory.CreateDirectory(Pc);

        var scan = await Scan();

        Assert.Empty(scan.Instances);
        Assert.Empty(scan.Notes);
    }

    [Fact]
    public void TheDrawerNamesEachClient()
    {
        Assert.Equal("Lunar Client", ImportLogic.GroupTitle("Lunar"));
        Assert.Equal("Feather Client", ImportLogic.GroupTitle("Feather"));
        Assert.Equal("Badlion Client", ImportLogic.GroupTitle("Badlion"));
        Assert.Equal("LabyMod", ImportLogic.GroupTitle("LabyMod"));
        foreach (var kind in new[] { SourceKind.Lunar, SourceKind.Feather, SourceKind.Badlion, SourceKind.LabyMod })
            Assert.True(PvpClientSource.BuiltInModsNote(kind)!.Value.Reason.All(c => c < 128)); // UI strings stay ASCII
    }
}
