using System.Diagnostics;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Sources;
using Xunit;

namespace OriginLauncher.Tests.Import;

// The detectors: the official launcher, Prism / MultiMC, Modrinth, CurseForge and Browse.
// Every launcher folder here is generated in a temp folder — never a real launcher's.
public class SourceDetectionTests : IDisposable
{
    private readonly string _root = Kit.Temp("detect");
    private readonly List<string> _opened = new();

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private ImportEngine Engine() => new(new ImportEnvironment
    {
        InstancesRoot = Path.Combine(_root, "OL", "instances"), ImportsRoot = Path.Combine(_root, "OL", "imports"),
        OnSourceOpen = _opened.Add,
    });

    private async Task<ScanResult> Scan(SourceRoots roots) =>
        await Engine().ScanAsync(roots, ImportEngine.DefaultSources(), null, CancellationToken.None);

    // ------------------------------------------------------------ official launcher (criterion 1)

    // Accuracy round (2026-10-18): a never-played profile on a folder with no game data is no longer a setup, and
    // profiles sharing a folder collapse into one. So the never-played "latest-release" (on .minecraft) and "Never"
    // profiles get folders with real game data (options.txt), and "Never" its own folder, to keep testing what
    // these tests are about: one candidate per profile, version resolution, the 1970 "never played" date.
    private string BuildOfficial(string mcDir, string absoluteGameDir)
    {
        Directory.CreateDirectory(Path.Combine(mcDir, "relpack"));
        Directory.CreateDirectory(absoluteGameDir);
        Kit.Write(mcDir, "options.txt", "fov:0.5");
        Kit.Write(mcDir, "neverdir/options.txt", "fov:0.5");
        Kit.Write(mcDir, "versions/fabric-loader-0.16.10-1.21.1/fabric-loader-0.16.10-1.21.1.json",
            """{"id":"fabric-loader-0.16.10-1.21.1","inheritsFrom":"1.21.1","libraries":[{"name":"net.fabricmc:fabric-loader:0.16.10"}]}""");
        Kit.Write(mcDir, "versions/1.21.4/1.21.4.json", """{"id":"1.21.4"}""");
        Kit.Write(mcDir, "versions/version_manifest_v2.json",
            """{"latest":{"release":"1.21.4","snapshot":"25w02a"},"versions":[{"id":"1.21.4","type":"release"}]}""");
        var absolute = absoluteGameDir.Replace("\\", "\\\\");
        Kit.Write(mcDir, "launcher_profiles.json", $$"""
            {
              "profiles": {
                "p1": { "name": "Hypixel", "type": "custom", "lastVersionId": "fabric-loader-0.16.10-1.21.1",
                        "lastUsed": "2026-10-01T10:00:00.000Z", "gameDir": "relpack", "icon": "Grass" },
                "p2": { "name": "Abs", "type": "custom", "lastVersionId": "1.20.4",
                        "lastUsed": "2026-09-01T10:00:00.000Z", "gameDir": "{{absolute}}" },
                "latest-release": { "type": "latest-release", "lastUsed": "1970-01-02T00:00:00.000Z" },
                "p4": { "name": "Never", "type": "custom", "lastVersionId": "1.8.9", "lastUsed": "1970-01-01T00:00:00.000Z", "gameDir": "neverdir" }
              },
              "authenticationDatabase": { "abc": { "accessToken": "SECRET-ACCESS-TOKEN-123", "username": "steve@example.com" } },
              "clientToken": "SECRET-CLIENT-TOKEN-XYZ",
              "selectedUser": { "account": "acc1", "profile": "prof1" }
            }
            """);
        return mcDir;
    }

    [Fact]
    public async Task OfficialLauncherGivesOneCandidatePerProfileWithVersionLoaderAndGameDir()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        var abs = Path.Combine(_root, "elsewhere", "abs-game");
        BuildOfficial(mc, abs);

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc });

        Assert.Equal(4, scan.Instances.Count);
        var hypixel = scan.Instances.Single(i => i.Name == "Hypixel");
        Assert.Equal("1.21.1", hypixel.McVersion);
        Assert.Equal(LoaderKind.Fabric, hypixel.Loader);
        Assert.Equal("0.16.10", hypixel.LoaderVersion);
        Assert.Equal(Confidence.Exact, hypixel.Confidence);
        Assert.Equal(Path.GetFullPath(Path.Combine(mc, "relpack")), hypixel.GameDir);          // relative gameDir
        Assert.Equal(new DateTimeOffset(2026, 10, 1, 10, 0, 0, TimeSpan.Zero), hypixel.LastPlayed);

        var absolute = scan.Instances.Single(i => i.Name == "Abs");
        Assert.Equal("1.20.4", absolute.McVersion);
        Assert.Equal(LoaderKind.None, absolute.Loader);
        Assert.Equal(Path.GetFullPath(abs), absolute.GameDir);                                // absolute gameDir
    }

    [Fact]
    public async Task LatestReleaseResolvesToAConcreteVersionAndA1970DateMeansNeverPlayed()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        BuildOfficial(mc, Path.Combine(_root, "abs"));

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc });

        // A folder only latest-* profiles use is named after the folder; the profile is an alias (accuracy round 2).
        var latest = scan.Instances.Single(i => i.Aliases.Contains("Latest release"));
        Assert.Equal("Minecraft Launcher (dotminecraft)", latest.Name);
        Assert.Equal("1.21.4", latest.McVersion);                // from version_manifest_v2.json
        Assert.Equal(mc, latest.GameDir);                        // no gameDir = the .minecraft itself
        Assert.Null(latest.LastPlayed);                          // 1970-01-02 is the launcher's "never"
        Assert.Null(scan.Instances.Single(i => i.Name == "Never").LastPlayed);
    }

    [Fact]
    public async Task LatestReleaseWithoutAManifestUsesTheNewestInstalledRelease()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        Kit.Write(mc, "versions/1.20.4/1.20.4.json", "{}");
        Kit.Write(mc, "versions/1.21.1/1.21.1.json", "{}");
        Kit.Write(mc, "versions/fabric-loader-0.16.10-1.21.1/fabric-loader-0.16.10-1.21.1.json", "{}");
        Kit.Write(mc, "options.txt", "fov:0.5"); // game data: a never-played profile on an empty folder is not a setup
        Kit.Write(mc, "launcher_profiles.json", """{"profiles":{"latest-release":{"type":"latest-release"}}}""");

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc });

        Assert.Equal("1.21.1", Assert.Single(scan.Instances).McVersion);
    }

    // ------------------------------------------------------------ Browse (criterion 5)

    [Fact]
    public void BrowseAcceptsAnInstanceRootHoldingADotMinecraftFolder()
    {
        var instance = Path.Combine(_root, "MyPack");
        Kit.Write(instance, ".minecraft/options.txt", "fov:0.5");
        Kit.Write(instance, ".minecraft/logs/latest.log", "[main/INFO] (FabricLoader) Loading Minecraft 1.21.1 with Fabric Loader 0.16.10");

        var result = FolderSource.Inspect(instance, new SourceFs(), CancellationToken.None);

        var found = Assert.Single(result.Instances);
        Assert.Equal("MyPack", found.Name);
        Assert.Equal(Path.Combine(instance, ".minecraft"), found.GameDir);
        Assert.Equal("1.21.1", found.McVersion);
        Assert.Equal(LoaderKind.Fabric, found.Loader);
        Assert.Equal(Confidence.Inferred, found.Confidence);
    }

    [Fact]
    public void BrowseAcceptsTheGameFolderItself()
    {
        var game = Path.Combine(_root, "MyPack", ".minecraft");
        Kit.Write(game, "mods/placeholder.txt", "x");
        Directory.CreateDirectory(Path.Combine(game, "saves"));

        var result = FolderSource.Inspect(game, new SourceFs(), CancellationToken.None);

        var found = Assert.Single(result.Instances);
        Assert.Equal(game, found.GameDir);
        Assert.Equal("MyPack", found.Name);                      // not ".minecraft"
    }

    [Fact]
    public void BrowseOnANonInstanceFolderSaysSoAndGivesNoCandidate()
    {
        var folder = Path.Combine(_root, "Pictures");
        Kit.Write(folder, "holiday.jpg", "x");
        Kit.Write(folder, "config/readme.txt", "x"); // a bare config folder is not enough

        var result = FolderSource.Inspect(folder, new SourceFs(), CancellationToken.None);

        Assert.Empty(result.Instances);
        Assert.Equal("This folder doesn't look like a Minecraft instance", result.Message);
        Assert.Equal("This folder doesn't look like a Minecraft instance",
            FolderSource.Inspect(Path.Combine(_root, "does-not-exist"), new SourceFs(), CancellationToken.None).Message);
    }

    [Fact]
    public void BrowseGuessesTheVersionFromTheModsWhenThereIsNoLog()
    {
        var game = Path.Combine(_root, "NoLog");
        Kit.WriteBytes(game, "mods/a.jar", Kit.FabricJar("a", minecraft: "~1.20.4"));
        Kit.WriteBytes(game, "mods/b.jar", Kit.FabricJar("b", minecraft: "~1.20.4"));
        Kit.WriteBytes(game, "mods/c.jar", Kit.FabricJar("c", minecraft: "1.16.5"));

        var found = Assert.Single(FolderSource.Inspect(game, new SourceFs(), CancellationToken.None).Instances);

        Assert.Equal("1.20.4", found.McVersion);
        Assert.Equal(LoaderKind.Fabric, found.Loader);
        Assert.Equal(Confidence.Inferred, found.Confidence);
    }

    [Fact]
    public void BrowseOnAnOfficialLauncherFolderListsItsProfiles()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        BuildOfficial(mc, Path.Combine(_root, "abs"));
        var result = FolderSource.Inspect(mc, new SourceFs(), CancellationToken.None);
        Assert.Equal(4, result.Instances.Count);
    }

    // ------------------------------------------------------------ garbage in, nothing escapes (criterion 6)

    [Fact]
    public async Task GarbageLauncherProfilesNeverEscapesAndGivesNoInstances()
    {
        var mc = Path.Combine(_root, "garbage");
        Kit.Write(mc, "launcher_profiles.json", "{{{ this is not json \0\0");

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc });

        Assert.Empty(scan.Instances);
    }

    [Fact]
    public async Task ATruncatedProfileListKeepsTheProfilesReadBeforeTheDamage()
    {
        var mc = Path.Combine(_root, "truncated");
        Kit.Write(mc, "g/options.txt", "fov:0.5"); // game data: a never-played profile on an empty folder is not a setup
        Kit.Write(mc, "versions/1.21.1/1.21.1.json", "{}");
        Kit.Write(mc, "launcher_profiles.json",
            """{"profiles":{"a":{"name":"Whole","type":"custom","lastVersionId":"1.21.1","gameDir":"g"},"b":{"name":"Cut off","type":"custom","lastVer""");

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc });

        Assert.Equal("Whole", Assert.Single(scan.Instances).Name);
        Assert.NotEmpty(scan.Notes);
    }

    [Fact]
    public async Task AProfileWhoseVersionCannotBeToldIsMarkedCouldntReadDetails()
    {
        var mc = Path.Combine(_root, "unknown-version");
        Kit.Write(mc, "g/options.txt", "fov:0.5"); // game data: a never-played profile on an empty folder is not a setup
        Kit.Write(mc, "launcher_profiles.json",
            """{"profiles":{"a":{"name":"Odd","type":"custom","lastVersionId":"some-modpack-build-7","gameDir":"g"}}}""");

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc });

        var odd = Assert.Single(scan.Instances);
        Assert.Equal(DetectedInstance.CouldntRead, odd.Problem);
        Assert.Null(odd.McVersion);
    }

    [Fact]
    public async Task MissingRootsGiveAnEmptyScan()
    {
        var scan = await Scan(new SourceRoots
        {
            OfficialMinecraftDir = Path.Combine(_root, "no1"), PrismDataDir = Path.Combine(_root, "no2"),
            MultiMcDir = Path.Combine(_root, "no3"), ModrinthDir = Path.Combine(_root, "no4"),
            CurseForgeDir = Path.Combine(_root, "no5"),
        });
        Assert.Empty(scan.Instances);
        Assert.Empty((await Scan(new SourceRoots())).Instances);
    }

    [Fact]
    public async Task DamagedPrismAndCurseForgeFilesAreMarkedNotThrown()
    {
        var prism = Path.Combine(_root, "prism");
        Kit.Write(prism, "instances/Bad/instance.cfg", "[General]\nname=Bad Pack\n");
        Kit.Write(prism, "instances/Bad/mmc-pack.json", "{ truncated");
        Kit.Write(prism, "instances/Bad/minecraft/options.txt", "x");
        var cf = Path.Combine(_root, "cf");
        Kit.Write(cf, "Instances/Broken/minecraftinstance.json", "\0\0\0 not json");

        var scan = await Scan(new SourceRoots { PrismDataDir = prism, CurseForgeDir = cf });

        Assert.Equal(2, scan.Instances.Count);
        Assert.All(scan.Instances, i => Assert.Equal(DetectedInstance.CouldntRead, i.Problem));
    }

    // ------------------------------------------------------------ speed (criterion 7): ImportTimingTests (runs alone)

    // ------------------------------------------------------------ Prism / MultiMC (criterion 2)

    private void PrismInstance(string root, string id, string cfg, string? pack, string? gameFolder = ".minecraft")
    {
        Kit.Write(root, $"instances/{id}/instance.cfg", cfg);
        if (pack != null) Kit.Write(root, $"instances/{id}/mmc-pack.json", pack);
        if (gameFolder != null) Directory.CreateDirectory(Path.Combine(root, "instances", id, gameFolder));
    }

    private const string FabricPack =
        """{"formatVersion":1,"components":[{"uid":"net.minecraft","version":"1.21.1","important":true,"cachedName":"Minecraft"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.10","cachedName":"Fabric Loader"}]}""";

    private const string PrismCfg =
        "[General]\nInstanceType=OneSix\niconKey=default\nname=My Pack\nlastLaunchTime=1760000000000\ntotalTimePlayed=7200\nlastTimePlayed=300\n";

    [Fact]
    public async Task PrismReadsNamePlaytimeLastLaunchVersionAndLoader()
    {
        var prism = Path.Combine(_root, "PrismLauncher");
        PrismInstance(prism, "my-pack", PrismCfg, FabricPack);

        var scan = await Scan(new SourceRoots { PrismDataDir = prism });

        var pack = Assert.Single(scan.Instances);
        Assert.Equal(SourceKind.Prism, pack.Kind);
        Assert.Equal("My Pack", pack.Name);
        Assert.Equal(7200, pack.PlaytimeSeconds);                                          // seconds, as is
        Assert.Equal(DateTimeOffset.FromUnixTimeMilliseconds(1760000000000), pack.LastPlayed); // milliseconds
        Assert.Equal("1.21.1", pack.McVersion);
        Assert.Equal(LoaderKind.Fabric, pack.Loader);
        Assert.Equal("0.16.10", pack.LoaderVersion);
        Assert.Equal(Confidence.Exact, pack.Confidence);
        Assert.False(pack.Pinned);
        Assert.Equal(Path.Combine(prism, "instances", "my-pack", ".minecraft"), pack.GameDir);
    }

    [Fact]
    public async Task PrismGameDirIsDotMinecraftUnlessOnlyMinecraftExists()
    {
        var prism = Path.Combine(_root, "PrismLauncher");
        PrismInstance(prism, "dot", PrismCfg, FabricPack, ".minecraft");
        PrismInstance(prism, "plain", PrismCfg.Replace("My Pack", "Plain"), FabricPack, "minecraft");
        PrismInstance(prism, "both", PrismCfg.Replace("My Pack", "Both"), FabricPack, ".minecraft");
        Directory.CreateDirectory(Path.Combine(prism, "instances", "both", "minecraft"));

        var scan = await Scan(new SourceRoots { PrismDataDir = prism });

        string Game(string name) => scan.Instances.Single(i => i.Name == name).GameDir;
        Assert.Equal(Path.Combine(prism, "instances", "dot", ".minecraft"), Game("My Pack"));
        Assert.Equal(Path.Combine(prism, "instances", "plain", "minecraft"), Game("Plain"));
        Assert.Equal(Path.Combine(prism, "instances", "both", "minecraft"), Game("Both"));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task PrismHonoursACustomInstanceDirAbsoluteOrRelative(bool absolute)
    {
        var prism = Path.Combine(_root, "PrismLauncher");
        var instances = Path.Combine(_root, "Games", "PrismInstances");
        Kit.Write(prism, "prismlauncher.cfg",
            "[General]\nMSAClientIDOverride=SECRET-OVERRIDE\nInstanceDir=" + (absolute ? instances.Replace('\\', '/') : "../Games/PrismInstances") + "\n");
        Kit.Write(instances, "pack1/instance.cfg", PrismCfg);
        Kit.Write(instances, "pack1/mmc-pack.json", FabricPack);
        Directory.CreateDirectory(Path.Combine(instances, "pack1", "minecraft"));

        var scan = await Scan(new SourceRoots { PrismDataDir = prism });

        Assert.Equal("My Pack", Assert.Single(scan.Instances).Name);
    }

    [Fact]
    public async Task PrismConfigWithoutAHeaderAndLegacyNamesStillWork()
    {
        var prism = Path.Combine(_root, "PrismLauncher");
        var instances = Path.Combine(_root, "legacy-instances");
        Kit.Write(prism, "polymc.cfg", "InstanceDir=" + instances.Replace('\\', '/') + "\n");
        Kit.Write(instances, "x/instance.cfg", PrismCfg);
        Kit.Write(instances, "x/mmc-pack.json", FabricPack);
        Directory.CreateDirectory(Path.Combine(instances, "x", "minecraft"));

        Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);
    }

    [Fact]
    public async Task PrismNeverPlayedAndAMissingNameAreHandled()
    {
        var prism = Path.Combine(_root, "PrismLauncher");
        PrismInstance(prism, "fresh", "[General]\nlastLaunchTime=0\ntotalTimePlayed=0\n", FabricPack);

        var fresh = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("fresh", fresh.Name);        // no name key: the folder name
        Assert.Null(fresh.LastPlayed);
        Assert.Equal(0, fresh.PlaytimeSeconds);
    }

    [Fact]
    public async Task PrismRejectsAPackFormatItDoesNotKnow()
    {
        var prism = Path.Combine(_root, "PrismLauncher");
        PrismInstance(prism, "future", PrismCfg, """{"formatVersion":2,"components":[{"uid":"net.minecraft","version":"1.21.1"}]}""");

        var future = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal(DetectedInstance.CouldntRead, future.Problem);
    }

    [Fact]
    public async Task PrismReadsQuiltForgeAndNeoForgeLoaders()
    {
        var prism = Path.Combine(_root, "PrismLauncher");
        string Pack(string uid, string v) => $$"""{"formatVersion":1,"components":[{"uid":"net.minecraft","version":"1.20.1"},{"uid":"{{uid}}","version":"{{v}}"}]}""";
        PrismInstance(prism, "q", PrismCfg.Replace("My Pack", "Q"), Pack("org.quiltmc.quilt-loader", "0.26.0"));
        PrismInstance(prism, "f", PrismCfg.Replace("My Pack", "F"), Pack("net.minecraftforge", "47.2.0"));
        PrismInstance(prism, "n", PrismCfg.Replace("My Pack", "N"), Pack("net.neoforged", "21.1.5"));

        var scan = await Scan(new SourceRoots { PrismDataDir = prism });

        Assert.Equal(LoaderKind.Quilt, scan.Instances.Single(i => i.Name == "Q").Loader);
        Assert.Equal(LoaderKind.Forge, scan.Instances.Single(i => i.Name == "F").Loader);
        Assert.Equal(LoaderKind.NeoForge, scan.Instances.Single(i => i.Name == "N").Loader);
    }

    [Fact]
    public void AMultiMcFormatFolderPickedWithBrowseReadsTheSameAsAScannedOne()
    {
        var multimc = Path.Combine(_root, "MultiMC");
        PrismInstance(multimc, "my-pack", PrismCfg, FabricPack);
        var folder = Path.Combine(multimc, "instances", "my-pack");

        var browsed = Assert.Single(FolderSource.Inspect(folder, new SourceFs(), CancellationToken.None).Instances);
        var browsedFromGameDir = Assert.Single(FolderSource.Inspect(Path.Combine(folder, ".minecraft"), new SourceFs(), CancellationToken.None).Instances);

        foreach (var i in new[] { browsed, browsedFromGameDir })
        {
            Assert.Equal("My Pack", i.Name);
            Assert.Equal("1.21.1", i.McVersion);
            Assert.Equal(LoaderKind.Fabric, i.Loader);
            Assert.Equal(7200, i.PlaytimeSeconds);
            Assert.Equal(Confidence.Exact, i.Confidence);
        }

        var scanned = new ScanResult();
        new PrismSource().DetectAsync(new SourceRoots { MultiMcDir = multimc }, new SourceFs(), scanned, CancellationToken.None).GetAwaiter().GetResult();
        Assert.Equal(scanned.Instances[0].McVersion, browsed.McVersion);
        Assert.Equal(scanned.Instances[0].PlaytimeSeconds, browsed.PlaytimeSeconds);
        Assert.Equal(scanned.Instances[0].GameDir, browsed.GameDir);
    }

    // ------------------------------------------------------------ Modrinth (criterion 3)

    [Fact]
    public async Task ModrinthTakesVersionAndLoaderFromTheLogAsInferredAndNeverOpensTheDatabase()
    {
        var modrinth = Path.Combine(_root, "ModrinthApp");
        Kit.Write(modrinth, "profiles/Fabulously Optimized/logs/latest.log",
            "[10:00:00] [main/INFO] (FabricLoader) Loading Minecraft 1.21.1 with Fabric Loader 0.16.10\n");
        Kit.Write(modrinth, "profiles/Fabulously Optimized/options.txt", "fov:0.5");
        Kit.Write(modrinth, "app.db", "SQLite format 3 SECRET-DB-CONTENT");
        Kit.Write(modrinth, "profiles/Empty Folder/readme.txt", "not a game folder");

        var scan = await Scan(new SourceRoots { ModrinthDir = modrinth });

        var profile = Assert.Single(scan.Instances);
        Assert.Equal(SourceKind.Modrinth, profile.Kind);
        Assert.Equal("Fabulously Optimized", profile.Name);
        Assert.Equal("1.21.1", profile.McVersion);
        Assert.Equal(LoaderKind.Fabric, profile.Loader);
        Assert.Equal("0.16.10", profile.LoaderVersion);
        Assert.Equal(Confidence.Inferred, profile.Confidence);
        Assert.Null(profile.PlaytimeSeconds);
        Assert.Null(profile.LastPlayed);
        Assert.Equal(Path.Combine(modrinth, "profiles", "Fabulously Optimized"), profile.GameDir);
        Assert.DoesNotContain(_opened, p => Path.GetFileName(p) == "app.db");
    }

    [Fact]
    public async Task ModrinthAlsoLooksInTheOlderTheseusFolder()
    {
        var appData = Path.Combine(_root, "Roaming");
        Kit.Write(appData, "com.modrinth.theseus/profiles/Old/logs/latest.log",
            "Loading Minecraft 1.20.4 with Fabric Loader 0.15.11");
        Kit.Write(appData, "com.modrinth.theseus/profiles/Old/options.txt", "x");

        var scan = await Scan(new SourceRoots { ModrinthDir = Path.Combine(appData, "ModrinthApp") });

        Assert.Equal("1.20.4", Assert.Single(scan.Instances).McVersion);
    }

    // ------------------------------------------------------------ CurseForge (criterion 4)

    private const string CurseFabric =
        """{"name":"My Pack","gameVersion":"1.21.1","isVanilla":false,"gameTypeID":432,"baseModLoader":{"name":"fabric-0.16.10","minecraftVersion":"1.21.1","type":4},"lastPlayed":"2025-07-04T02:11:26.3696048Z","playedCount":12,"timePlayed":0,"installPath":"C:\\Users\\X\\curseforge\\minecraft\\Instances\\My Pack\\","installedAddons":[]}""";

    [Fact]
    public async Task CurseForgeReadsNameVersionLoaderRecencyAndUsesTheInstanceFolderAsGameDir()
    {
        var cf = Path.Combine(_root, "curseforge", "minecraft");
        Kit.Write(cf, "Instances/My Pack/minecraftinstance.json", CurseFabric);

        var scan = await Scan(new SourceRoots { CurseForgeDir = cf });

        var pack = Assert.Single(scan.Instances);
        Assert.Equal(SourceKind.CurseForge, pack.Kind);
        Assert.Equal("My Pack", pack.Name);
        Assert.Equal("1.21.1", pack.McVersion);
        Assert.Equal(LoaderKind.Fabric, pack.Loader);
        Assert.Equal("0.16.10", pack.LoaderVersion);
        Assert.Equal(new DateTimeOffset(2025, 7, 4, 2, 11, 26, TimeSpan.Zero).AddTicks(3696048), pack.LastPlayed);
        Assert.Equal(12, pack.LaunchCount);
        Assert.Null(pack.PlaytimeSeconds);                         // timePlayed has no known unit: not used
        Assert.Equal(Path.Combine(cf, "Instances", "My Pack"), pack.GameDir);
        Assert.Equal(Confidence.Exact, pack.Confidence);
    }

    [Fact]
    public async Task CurseForgeNeverPlayedAndANullLoaderAreTolerated()
    {
        var cf = Path.Combine(_root, "curseforge", "minecraft");
        Kit.Write(cf, "Instances/Fresh/minecraftinstance.json",
            """{"name":"Fresh","gameVersion":"1.20.4","isVanilla":false,"baseModLoader":null,"lastPlayed":"0001-01-01T00:00:00","playedCount":0}""");
        Kit.Write(cf, "Instances/Vanilla/minecraftinstance.json",
            """{"name":"Vanilla","gameVersion":"1.21.1","isVanilla":true,"baseModLoader":{}}""");

        var scan = await Scan(new SourceRoots { CurseForgeDir = cf });

        var fresh = scan.Instances.Single(i => i.Name == "Fresh");
        Assert.Null(fresh.LastPlayed);                              // the never-played sentinel
        Assert.Equal("1.20.4", fresh.McVersion);
        Assert.Equal(Confidence.Inferred, fresh.Confidence);        // loader not recorded
        var vanilla = scan.Instances.Single(i => i.Name == "Vanilla");
        Assert.Equal(LoaderKind.None, vanilla.Loader);
        Assert.Equal(Confidence.Exact, vanilla.Confidence);
    }

    [Fact]
    public async Task CurseForgeNeoForgeIsReadAsNeoForgeAndItsModsAreListedAsNotFabric()
    {
        var cf = Path.Combine(_root, "curseforge", "minecraft");
        Kit.Write(cf, "Instances/Neo/minecraftinstance.json",
            """{"name":"Neo","gameVersion":"1.21.1","baseModLoader":{"name":"neoforge-21.1.209","minecraftVersion":"1.21.1"},"lastPlayed":"2026-01-01T00:00:00Z"}""");
        Kit.WriteBytes(cf, "Instances/Neo/mods/neomod.jar",
            Kit.Zip(Kit.Text("META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"neomod\"\nversion=\"1.0\"\n")));

        var neo = Assert.Single((await Scan(new SourceRoots { CurseForgeDir = cf })).Instances);

        Assert.Equal(LoaderKind.NeoForge, neo.Loader);
        Assert.Equal("21.1.209", neo.LoaderVersion);

        // ... and importing it lists the mod as a NeoForge mod, not copied.
        using var rig = new Rig();
        var plan = await rig.Engine().PlanAsync(rig.Request() with { Source = neo }, null, CancellationToken.None);
        Assert.Equal(ModOutcome.WrongLoader, Assert.Single(plan.Mods).Outcome);
        Assert.Contains("NeoForge", plan.Mods[0].Reason);
    }
}
