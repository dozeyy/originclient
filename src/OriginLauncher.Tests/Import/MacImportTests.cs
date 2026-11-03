using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using Xunit;

namespace OriginLauncher.Tests.Import;

/// <summary>
/// The import on a Mac: every launcher's macOS folder (ImportRoots, MachineFolders.ForMac), then a
/// scratch home folder laid out exactly like a Mac's, found and imported end to end through the same
/// engine as Windows. Pure, so it runs in the Windows suite too; the macos-15 CI legs run it in Mac
/// mode (-p:OriginPlatform=Mac), where it also proves the real MachineFolders.Current() is the Mac one.
/// Every launcher folder is generated under a temp "home": never a real launcher's.
/// </summary>
public sealed class MacImportTests : IDisposable
{
    private readonly string _home = Kit.Temp("machome");
    private readonly Rig _rig = new();

    public void Dispose()
    {
        _rig.Dispose();
        try { Directory.Delete(_home, recursive: true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private string Support => Path.Combine(_home, "Library", "Application Support");

    // ------------------------------------------------------------ where each launcher lives on a Mac

    [Fact]
    public void Mac_folders_come_from_home()
    {
        var f = MachineFolders.ForMac("/Users/p");
        Assert.True(f.Mac);
        Assert.Equal(Path.Combine("/Users/p", "Library", "Application Support"), f.AppData);
        Assert.Equal(f.AppData, f.LocalAppData);
        Assert.Equal("/Users/p", f.UserProfile);
        Assert.Equal(Path.Combine("/Users/p", "Documents"), f.Documents);
    }

    [Fact]
    public void Every_launcher_windows_reads_has_its_mac_folder()
    {
        var f = MachineFolders.ForMac(_home);
        var present = new HashSet<string>(StringComparer.Ordinal)
        {
            Path.Combine(Support, "minecraft"),
            Path.Combine(Support, "PrismLauncher"),
            Path.Combine(Support, "ModrinthApp"),
            Path.Combine(Support, "ModrinthApp", "meta"),
            Path.Combine(_home, "Documents", "curseforge", "minecraft"),
            Path.Combine(_home, ".lunarclient"),
            Path.Combine(Support, ".feather"),
            Path.Combine(Support, "Badlion Client"),
        };
        var roots = ImportRoots.Sources(f, present.Contains);
        Assert.Equal(Path.Combine(Support, "minecraft"), roots.OfficialMinecraftDir);
        Assert.Equal(Path.Combine(Support, "PrismLauncher"), roots.PrismDataDir);
        Assert.Equal(Path.Combine(Support, "ModrinthApp"), roots.ModrinthDir);
        Assert.Equal(Path.Combine(_home, "Documents", "curseforge", "minecraft", "Instances"), roots.CurseForgeDir);
        Assert.Equal(Path.Combine(_home, ".lunarclient"), roots.LunarDir);
        Assert.Equal(Path.Combine(Support, ".feather"), roots.FeatherDir);
        Assert.Equal(Path.Combine(Support, "Badlion Client"), roots.BadlionDir);

        var files = ImportRoots.GameFiles(f, "inst", present.Contains);
        Assert.Equal(Path.Combine(Support, "minecraft"), files.OfficialMinecraftDir);
        Assert.Equal(Path.Combine(Support, "ModrinthApp", "meta"), files.ModrinthMetaDir);
        Assert.Equal(Path.Combine(_home, "Documents", "curseforge", "minecraft"), files.CurseForgeRoot);
        Assert.Empty(files.OfficialRuntimeRoots); // no Microsoft Store edition on a Mac (minecraft/runtime is read by GameFileSources)
    }

    [Fact]
    public void Mac_never_reads_the_windows_names()
    {
        var f = MachineFolders.ForMac(_home);
        var windowsNames = new HashSet<string>(StringComparer.Ordinal)
        {
            Path.Combine(Support, ".minecraft"),
            Path.Combine(Support, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"),
        };
        Assert.Null(ImportRoots.Sources(f, windowsNames.Contains).OfficialMinecraftDir);
        Assert.Empty(ImportRoots.GameFiles(f, "inst", windowsNames.Contains).OfficialRuntimeRoots);
    }

    [Fact]
    public void Older_modrinth_and_the_home_curseforge_folder_are_read_too()
    {
        var f = MachineFolders.ForMac(_home);
        var present = new HashSet<string>(StringComparer.Ordinal)
        {
            Path.Combine(Support, "com.modrinth.theseus"),
            Path.Combine(_home, "curseforge", "minecraft"),
        };
        var roots = ImportRoots.Sources(f, present.Contains);
        Assert.Equal(Path.Combine(Support, "com.modrinth.theseus"), roots.ModrinthDir);
        Assert.Equal(Path.Combine(_home, "curseforge", "minecraft", "Instances"), roots.CurseForgeDir);
    }

#if ORIGIN_MAC
    [Fact]
    public void This_mac_reads_its_own_home()
    {
        var current = MachineFolders.Current();
        Assert.Equal(MachineFolders.ForMac(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)), current);
        Assert.EndsWith(Path.Combine("Library", "Application Support"), current.AppData, StringComparison.Ordinal);
    }
#endif

    // ------------------------------------------------------------ a whole Mac home, found and imported

    private const string FabricPack =
        """{"formatVersion":1,"components":[{"uid":"net.minecraft","version":"1.21.1","important":true},{"uid":"net.fabricmc.fabric-loader","version":"0.16.10"}]}""";

    private const string FabricLog = "[10:00:00] [main/INFO] (FabricLoader) Loading Minecraft 1.21.1 with Fabric Loader 0.16.10\n";

    // One game folder's worth: settings, a world, a mod, a resource pack (what an import brings).
    private static void Game(string dir, string tag)
    {
        Kit.Write(dir, "options.txt", $"version:3955\nfov:0.25\nlang:en_us\nresourcePacks:[\"vanilla\",\"file/{tag} Pack.zip\"]\n");
        Kit.Write(dir, $"saves/{tag} World/level.dat", "LEVEL-" + tag);
        Kit.Write(dir, $"saves/{tag} World/region/r.0.0.mca", "REGION-" + tag);
        Kit.WriteBytes(dir, $"mods/{tag.ToLowerInvariant()}mod.jar", Kit.FabricJar(tag.ToLowerInvariant() + "mod", "1.0", minecraft: "~1.21.1"));
        Kit.WriteBytes(dir, $"resourcepacks/{tag} Pack.zip", Kit.Zip(Kit.Text("pack.mcmeta", tag)));
    }

    private void BuildMacHome()
    {
        // Official launcher: ~/Library/Application Support/minecraft (no dot), one Fabric profile in its own folder.
        var mc = Path.Combine(Support, "minecraft");
        Game(Path.Combine(mc, "profiles-fabric"), "Official");
        Kit.Write(mc, "versions/fabric-loader-0.16.10-1.21.1/fabric-loader-0.16.10-1.21.1.json",
            """{"id":"fabric-loader-0.16.10-1.21.1","inheritsFrom":"1.21.1","libraries":[{"name":"net.fabricmc:fabric-loader:0.16.10"}]}""");
        Kit.Write(mc, "launcher_profiles.json", """
            {"profiles":{"p1":{"name":"Official Fabric","type":"custom","lastVersionId":"fabric-loader-0.16.10-1.21.1",
              "lastUsed":"2026-10-01T10:00:00.000Z","gameDir":"profiles-fabric"}}}
            """);

        // Prism: ~/Library/Application Support/PrismLauncher/instances/<id>/minecraft (Prism on a Mac uses "minecraft").
        var prism = Path.Combine(Support, "PrismLauncher");
        Kit.Write(prism, "instances/survival/instance.cfg",
            "[General]\nInstanceType=OneSix\nname=Prism Survival\nlastLaunchTime=1760000000000\ntotalTimePlayed=7200\n");
        Kit.Write(prism, "instances/survival/mmc-pack.json", FabricPack);
        Game(Path.Combine(prism, "instances", "survival", "minecraft"), "Prism");

        // Modrinth App: ~/Library/Application Support/ModrinthApp/profiles/<name>.
        var modrinth = Path.Combine(Support, "ModrinthApp");
        var profile = Path.Combine(modrinth, "profiles", "Modrinth Pack");
        Game(profile, "Modrinth");
        Kit.Write(profile, "logs/latest.log", FabricLog);
    }

    private async Task<ScanResult> ScanMacHome() =>
        await _rig.Engine().ScanAsync(ImportRoots.Sources(MachineFolders.ForMac(_home)), ImportEngine.DefaultSources(), null, CancellationToken.None);

    [Fact]
    public async Task A_mac_home_is_found_with_every_launcher_on_it()
    {
        BuildMacHome();
        var scan = await ScanMacHome();

        var official = scan.Instances.Single(i => i.Kind == SourceKind.OfficialLauncher);
        Assert.Equal("Official Fabric", official.Name);
        Assert.Equal("1.21.1", official.McVersion);
        Assert.Equal(LoaderKind.Fabric, official.Loader);
        Assert.Equal(Path.Combine(Support, "minecraft", "profiles-fabric"), official.GameDir);

        var prism = scan.Instances.Single(i => i.Kind == SourceKind.Prism);
        Assert.Equal("Prism Survival", prism.Name);
        Assert.Equal("1.21.1", prism.McVersion);
        Assert.Equal(Path.Combine(Support, "PrismLauncher", "instances", "survival", "minecraft"), prism.GameDir);

        var modrinth = scan.Instances.Single(i => i.Kind == SourceKind.Modrinth);
        Assert.Equal("Modrinth Pack", modrinth.Name);
        Assert.Equal("1.21.1", modrinth.McVersion);
        Assert.Equal(LoaderKind.Fabric, modrinth.Loader);
    }

    [Theory]
    [InlineData(SourceKind.OfficialLauncher, "Official")]
    [InlineData(SourceKind.Prism, "Prism")]
    [InlineData(SourceKind.Modrinth, "Modrinth")]
    public async Task Each_mac_launcher_imports_end_to_end_and_is_never_changed(SourceKind kind, string tag)
    {
        BuildMacHome();
        var before = Kit.TreeHash(Support, mtimes: true);
        var setup = (await ScanMacHome()).Instances.Single(i => i.Kind == kind);

        var engine = _rig.Engine();
        var plan = await engine.PlanAsync(_rig.Request(options: new ImportOptions { CopyWorlds = true }) with { Source = setup }, null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        Assert.Contains(plan.Mods, m => m.IsCopied && m.FileName.StartsWith(tag.ToLowerInvariant() + "mod", StringComparison.OrdinalIgnoreCase));
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("LEVEL-" + tag, Kit.Read(_rig.GameDir(), $"saves/{tag} World/level.dat"));
        Assert.Equal("REGION-" + tag, Kit.Read(_rig.GameDir(), $"saves/{tag} World/region/r.0.0.mca"));
        Assert.True(Kit.Exists(_rig.GameDir(), $"resourcepacks/{tag} Pack.zip"));
        Assert.Contains("fov:0.25", Kit.Read(_rig.GameDir(), "options.txt"), StringComparison.Ordinal);
        Assert.Single(_rig.Settings.Load().Profiles);                        // the setup's mods became its own profile
        Assert.Equal(before, Kit.TreeHash(Support, mtimes: true));          // the other launcher is only read
    }
}
