using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Detection;
using OriginLauncher.App.Core.Import.Sources;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Detection accuracy: the right setup, the right version, the right loader — or "not sure" with a reason.
// Rule ids (V1..V6, W1..W4) are the ones documented in Core/Import/Detection/EvidenceJudge.cs; the folder
// rules (one setup per folder, aliases, empty drop, names, stable ids) are in SetupResolver.cs.
// Every launcher folder is generated here in a temp folder — never a real launcher's.
public class SourceAccuracyTests : IDisposable
{
    private readonly string _root = Kit.Temp("accuracy");

    private static readonly DateTime T0 = new(2026, 9, 1, 12, 0, 0, DateTimeKind.Utc);

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private async Task<ScanResult> Scan(SourceRoots roots) =>
        await new ImportEngine(new ImportEnvironment
        {
            InstancesRoot = Path.Combine(_root, "OL", "instances"), ImportsRoot = Path.Combine(_root, "OL", "imports"),
        }).ScanAsync(roots, ImportEngine.DefaultSources(), null, CancellationToken.None);

    // ---- fixture helpers ----

    private static string FabricLog(string mc, string loader = "0.16.10") =>
        $"[12:00:00] [main/INFO] (FabricLoader) Loading Minecraft {mc} with Fabric Loader {loader}\n";

    private static void Log(string gameDir, string text, DateTime mtime) => Kit.Write(gameDir, "logs/latest.log", text, mtime);

    private static void FabricMods(string gameDir, string prefix, int count, string range)
    {
        for (var i = 0; i < count; i++) Kit.WriteBytes(gameDir, $"mods/{prefix}{i}.jar", Kit.FabricJar(prefix + i, minecraft: range));
    }

    private static void ForgeMods(string gameDir, int count)
    {
        for (var i = 0; i < count; i++)
            Kit.WriteBytes(gameDir, $"mods/forgemod{i}.jar",
                Kit.Zip(Kit.Text("META-INF/mods.toml", $"[[mods]]\nmodId=\"forgemod{i}\"\nversion=\"1.0\"\n")));
    }

    private static string Pack(string mc, string? loaderUid = "net.fabricmc.fabric-loader", string loaderVersion = "0.16.10") =>
        loaderUid == null
            ? $$"""{"formatVersion":1,"components":[{"uid":"net.minecraft","version":"{{mc}}"}]}"""
            : $$"""{"formatVersion":1,"components":[{"uid":"net.minecraft","version":"{{mc}}"},{"uid":"{{loaderUid}}","version":"{{loaderVersion}}"}]}""";

    /// <summary>A Prism instance; returns its game dir (.minecraft). The pack file's mtime is "when Prism last changed it".</summary>
    private static string Prism(string prismRoot, string id, string name, string pack, DateTime packChanged)
    {
        Kit.Write(prismRoot, $"instances/{id}/instance.cfg", $"[General]\nname={name}\nlastLaunchTime=0\ntotalTimePlayed=0\n");
        Kit.Write(prismRoot, $"instances/{id}/mmc-pack.json", pack, packChanged);
        var game = Path.Combine(prismRoot, "instances", id, ".minecraft");
        Kit.Write(game, "options.txt", "fov:0.5");
        return game;
    }

    private static string OfficialProfiles(params string[] profiles) => "{\"profiles\":{" + string.Join(",", profiles) + "}}";

    private static string Profile(string id, string? name, string type, string? lastVersionId, string? lastUsed = null, string? gameDir = null)
    {
        var parts = new List<string> { $"\"type\":\"{type}\"" };
        if (name != null) parts.Add($"\"name\":\"{name}\"");
        if (lastVersionId != null) parts.Add($"\"lastVersionId\":\"{lastVersionId}\"");
        if (lastUsed != null) parts.Add($"\"lastUsed\":\"{lastUsed}\"");
        if (gameDir != null) parts.Add($"\"gameDir\":\"{gameDir.Replace("\\", "\\\\")}\"");
        return $"\"{id}\":{{{string.Join(",", parts)}}}";
    }

    private static void FabricVersionJson(string mc, string id = "fabric-loader-0.16.10-1.21.1", string inherits = "1.21.1") =>
        Kit.Write(mc, $"versions/{id}/{id}.json",
            $$"""{"id":"{{id}}","inheritsFrom":"{{inherits}}","libraries":[{"name":"net.fabricmc:fabric-loader:0.16.10"},{"name":"org.ow2.asm:asm:9.7"}]}""");

    private static void Manifest(string mc, string release, string snapshot) =>
        Kit.Write(mc, "versions/version_manifest_v2.json",
            $$"""{"latest":{"release":"{{release}}","snapshot":"{{snapshot}}"},"versions":[]}""");

    // ================================================================ version: launcher metadata (V2, V4)

    [Fact]
    public async Task RenamedProfileWithAFabricVersionIdReadsTheVersionAndLoaderFromTheVersionJson()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc);
        Kit.Write(mc, "options.txt", "fov:0.5");
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("p1", "My Fabric", "custom", "fabric-loader-0.16.10-1.21.1", "2026-10-01T10:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("My Fabric", setup.Name);
        Assert.Equal("1.21.1", setup.McVersion);
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.Equal("0.16.10", setup.LoaderVersion);
        Assert.True(setup.VersionCertain, setup.UncertainReason);          // V4: one explicit launcher record
        Assert.Equal(Confidence.Exact, setup.Confidence);
        Assert.Contains(setup.Evidence, e => e.Source == EvidenceSource.LauncherMetadata && e.From == "My Fabric");
    }

    [Fact]
    public async Task ARenamedVersionIdGetsItsLoaderFromTheVersionJsonLibraries()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc, id: "my-custom-install");                    // the id names neither version nor loader
        Kit.Write(mc, "options.txt", "fov:0.5");
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("p1", "Custom", "custom", "my-custom-install", "2026-10-01T10:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);                            // inheritsFrom
        Assert.Equal(LoaderKind.Fabric, setup.Loader);                      // libraries: net.fabricmc:fabric-loader:0.16.10
        Assert.Equal("0.16.10", setup.LoaderVersion);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
    }

    // ================================================================ version: launcher vs log (V3)

    [Fact]
    public async Task AStaleLogOlderThanTheLauncherRecordIsNotCertainAndPrefersTheLauncher()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.20.1"), packChanged: T0.AddDays(1));   // changed AFTER the last play
        Log(game, FabricLog("1.19.2"), T0);

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("1.20.1", setup.McVersion);
        Assert.False(setup.VersionCertain);
        Assert.Equal("The launcher says 1.20.1 but the game last ran 1.19.2.", setup.UncertainReason);
        Assert.Equal(Confidence.Exact, setup.Confidence);
    }

    [Fact]
    public async Task AStaleLogIsExplainedWhenTheModsConfirmTheNewerLauncherRecord()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.20.1"), packChanged: T0.AddDays(1));
        Log(game, FabricLog("1.19.2"), T0);
        FabricMods(game, "m", 3, "~1.20.1");                                // the player already swapped the mods too

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("1.20.1", setup.McVersion);
        Assert.True(setup.VersionCertain, setup.UncertainReason);          // V3 exception: launcher newer + mods agree
    }

    [Fact]
    public async Task ALogNewerThanTheLauncherRecordIsNotCertainAndPrefersTheLog()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.20.1"), packChanged: T0);
        Log(game, FabricLog("1.20.4", "0.15.11"), T0.AddDays(2));          // the game last ran something else

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("1.20.4", setup.McVersion);
        Assert.Equal("0.15.11", setup.LoaderVersion);                       // W1: the loader comes from the winner too
        Assert.False(setup.VersionCertain);
        Assert.Equal(Confidence.Inferred, setup.Confidence);
        Assert.Contains("The launcher says 1.20.1 but the game last ran 1.20.4.", setup.UncertainReason);
        Assert.Contains(setup.Evidence, e => e.Source == EvidenceSource.LatestLog && e.McVersion == "1.20.4" && e.ObservedUtc != null);
    }

    [Fact]
    public async Task LauncherAndLogAgreeingIsCertain()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.21.1"), packChanged: T0);
        Log(game, FabricLog("1.21.1"), T0.AddDays(3));

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.True(setup.VersionCertain, setup.UncertainReason);          // V2
        Assert.Null(setup.UncertainReason);
        Assert.Equal(2, setup.Evidence.Count);
    }

    // ================================================================ version: the mods (V6)

    [Fact]
    public async Task ModsAllBuiltForAnotherVersionMakeTheSetupNotCertain()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.21.1"), packChanged: T0);
        FabricMods(game, "old", 5, "~1.20.4");

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);                            // the launcher's answer stays the preselection
        Assert.False(setup.VersionCertain);
        Assert.Equal("Most mods here are built for Minecraft 1.20.4, not 1.21.1.", setup.UncertainReason);
        Assert.Contains(setup.Evidence, e => e.Source == EvidenceSource.ModDepends && e.McVersion == "1.20.4");
    }

    [Fact]
    public async Task ModsSplitHalfAndHalfMakeTheSetupNotCertain()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.21.1"), packChanged: T0);
        FabricMods(game, "new", 2, "~1.21.1");
        FabricMods(game, "old", 2, "~1.20.4");

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.False(setup.VersionCertain);
        Assert.Equal("Only some of the mods here are built for Minecraft 1.21.1.", setup.UncertainReason);
    }

    [Fact]
    public async Task ModsThatMostlyFitStillCountAsAgreeing()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.21.1"), packChanged: T0);
        FabricMods(game, "ok", 4, ">=1.21");
        FabricMods(game, "odd", 1, "1.21");                                 // 4 of 5 accept 1.21.1: exactly 80 %

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.True(setup.VersionCertain, setup.UncertainReason);
    }

    // ================================================================ loader (W2, W3, W4)

    [Fact]
    public async Task ForgeJarsInAFabricCurseForgeSetupMakeTheLoaderNotCertain()
    {
        var cf = Path.Combine(_root, "curseforge", "minecraft");
        var game = Path.Combine(cf, "Instances", "Mixed");
        Kit.Write(game, "minecraftinstance.json",
            """{"name":"Mixed","gameVersion":"1.21.1","baseModLoader":{"name":"fabric-0.16.10","minecraftVersion":"1.21.1"},"lastPlayed":"2026-10-01T00:00:00Z"}""");
        ForgeMods(game, 3);

        var setup = Assert.Single((await Scan(new SourceRoots { CurseForgeDir = cf })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.Equal(LoaderKind.Fabric, setup.Loader);                      // the launcher's answer stays the preselection
        Assert.False(setup.LoaderCertain);
        Assert.False(setup.VersionCertain);
        Assert.Equal("This setup is set to Fabric, but most mods here are Forge mods.", setup.UncertainReason);
    }

    [Fact]
    public async Task CurseForgeWithNoLoaderAndAFabricLogIsFabricButNotCertainWithoutMods()
    {
        var cf = Path.Combine(_root, "curseforge", "minecraft");
        var game = Path.Combine(cf, "Instances", "NullLoader");
        Kit.Write(game, "minecraftinstance.json", """{"name":"NullLoader","gameVersion":"1.21.1","isVanilla":false,"baseModLoader":null}""");
        Log(game, FabricLog("1.21.1"), T0);

        var setup = Assert.Single((await Scan(new SourceRoots { CurseForgeDir = cf })).Instances);

        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.Equal("1.21.1", setup.McVersion);
        Assert.False(setup.LoaderCertain);                                  // W3: the log alone isn't enough
        Assert.False(setup.VersionCertain);
        Assert.Contains("didn't record a mod loader", setup.UncertainReason);
        Assert.Equal(Confidence.Inferred, setup.Confidence);
    }

    [Fact]
    public async Task CurseForgeWithNoLoaderAFabricLogAndAgreeingModsIsCertain()
    {
        var cf = Path.Combine(_root, "curseforge", "minecraft");
        var game = Path.Combine(cf, "Instances", "NullLoader");
        Kit.Write(game, "minecraftinstance.json", """{"name":"NullLoader","gameVersion":"1.21.1","isVanilla":false,"baseModLoader":null}""");
        Log(game, FabricLog("1.21.1"), T0);
        FabricMods(game, "m", 3, "~1.21.1");

        var setup = Assert.Single((await Scan(new SourceRoots { CurseForgeDir = cf })).Instances);

        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
    }

    [Fact]
    public async Task AForgeLogInAFabricSetupMakesTheLoaderNotCertain()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.20.1"), packChanged: T0.AddDays(1));
        Log(game, "[main/INFO] [cpw.mods.modlauncher.Launcher/MODLAUNCHER]: ModLauncher running: args [--fml.forgeVersion, 47.2.0, --fml.mcVersion, 1.20.1, --fml.forgeGroup, net.minecraftforge]\n", T0);

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("1.20.1", setup.McVersion);
        Assert.Equal(LoaderKind.Fabric, setup.Loader);                      // W2: the launcher record is newer
        Assert.False(setup.LoaderCertain);
        Assert.Contains("The launcher says Fabric but the game last ran Forge.", setup.UncertainReason);
    }

    [Fact]
    public async Task ANewerLogNamingAnotherLoaderWinsThePreselectionButIsNotCertainOrExact()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.21.1", loaderUid: null), packChanged: T0);   // vanilla in the launcher
        Log(game, FabricLog("1.21.1"), T0.AddDays(1));                                           // ...but Fabric ran since

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.False(setup.LoaderCertain);
        Assert.Equal(Confidence.Inferred, setup.Confidence);                // the loader no longer comes from the launcher
        Assert.Contains("The launcher says vanilla but the game last ran Fabric.", setup.UncertainReason);
    }

    // ================================================================ shared folders: one setup per folder

    [Fact]
    public async Task TwoOfficialProfilesSharingDotMinecraftAreOneSetupWithAnAliasAndTheLogsVersion()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc);
        Kit.Write(mc, "versions/1.20.4/1.20.4.json", """{"id":"1.20.4"}""");
        Kit.Write(mc, "options.txt", "fov:0.5");
        Kit.Write(mc, "saves/World/level.dat", "x");
        Log(mc, FabricLog("1.21.1"), T0.AddDays(30).AddHours(1));
        // Neither has a gameDir: both use the .minecraft itself. Never played -> no recency to go by.
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("a", "Vanilla 1.20.4", "custom", "1.20.4"),
            Profile("b", "Fabric 1.21.1", "custom", "fabric-loader-0.16.10-1.21.1")));

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc });

        var setup = Assert.Single(scan.Instances);                          // never two setups for one folder
        Assert.Equal("1.21.1", setup.McVersion);                            // V1: the profile the log matches
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
        Assert.Equal("Fabric 1.21.1", setup.Name);                          // no recency: the profile the evidence matches
        Assert.Equal(new[] { "Vanilla 1.20.4" }, setup.Aliases);
        Assert.Equal(2, setup.Evidence.Count(e => e.Source == EvidenceSource.LauncherMetadata));
    }

    [Fact]
    public async Task ASharedFolderIsNamedAfterTheMostRecentlyUsedProfile()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc);
        Kit.Write(mc, "options.txt", "fov:0.5");
        Log(mc, FabricLog("1.21.1"), T0.AddDays(30).AddHours(1));
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("a", "Old Fabric", "custom", "fabric-loader-0.16.10-1.21.1", "2026-08-01T10:00:00.000Z"),
            Profile("b", "Hypixel", "custom", "fabric-loader-0.16.10-1.21.1", "2026-10-01T10:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("Hypixel", setup.Name);
        Assert.Equal(new[] { "Old Fabric" }, setup.Aliases);
        Assert.Equal(new DateTimeOffset(2026, 10, 1, 10, 0, 0, TimeSpan.Zero), setup.LastPlayed);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
    }

    [Fact]
    public async Task LatestProfilesOnAFolderWithACustomProfileAreOnlyAliasesNeverTheName()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc);
        Manifest(mc, "26.3", "26.4-snapshot-3");
        Kit.Write(mc, "options.txt", "fov:0.5");
        Log(mc, FabricLog("1.21.1"), T0.AddDays(40));
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            // "Latest release" was even used more recently than the custom profile: still never the name.
            Profile("latest-release", null, "latest-release", "latest-release", "2026-10-05T10:00:00.000Z"),
            Profile("latest-snapshot", null, "latest-snapshot", "latest-snapshot", "1970-01-02T00:00:00.000Z"),
            Profile("p1", "My Fabric", "custom", "fabric-loader-0.16.10-1.21.1", "2026-10-01T10:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("My Fabric", setup.Name);
        Assert.Equal(new[] { "Latest release", "Latest snapshot" }, setup.Aliases.OrderBy(a => a));
        Assert.Equal("1.21.1", setup.McVersion);                            // the "latest" statements are not explicit (V1)
        Assert.True(setup.VersionCertain, setup.UncertainReason);
    }

    [Fact]
    public async Task LatestReleaseAndLatestSnapshotSharingAFolderWithDataAreOneSetupAndNotCertain()
    {
        // The real dry-run shape: "Latest release" (26.3) and "Latest snapshot" (26.4-snapshot-3) on the default .minecraft.
        var mc = Path.Combine(_root, "dotminecraft");
        Manifest(mc, "26.3", "26.4-snapshot-3");
        Kit.Write(mc, "options.txt", "fov:0.5");
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("latest-release", null, "latest-release", "latest-release", "1970-01-02T00:00:00.000Z"),
            Profile("latest-snapshot", null, "latest-snapshot", "latest-snapshot", "1970-01-02T00:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("Latest release", setup.Name);
        Assert.Equal(new[] { "Latest snapshot" }, setup.Aliases);
        Assert.False(setup.VersionCertain);                                 // nothing says which one the folder last ran
        Assert.NotNull(setup.UncertainReason);
    }

    [Fact]
    public async Task NeverPlayedLatestProfilesSharingAnEmptyFolderAreNoSetupAtAll()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        Manifest(mc, "26.3", "26.4-snapshot-3");
        Kit.Write(mc, "versions/26.3/26.3.json", """{"id":"26.3"}""");     // installed files are not game data
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("latest-release", null, "latest-release", "latest-release", "1970-01-02T00:00:00.000Z"),
            Profile("latest-snapshot", null, "latest-snapshot", "latest-snapshot", "1970-01-01T00:00:00.000Z")));

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc });

        Assert.Empty(scan.Instances);
        Assert.Contains(scan.Debug, d => d.Contains("Latest release") && d.Contains("Latest snapshot"));
        Assert.Empty(scan.Notes);                                           // not a user-facing note
    }

    [Fact]
    public async Task APlayedProfileOnAnEmptyFolderIsStillASetup()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("p1", "Played", "custom", "1.21.1", "2026-10-01T10:00:00.000Z", gameDir: "empty")));
        Directory.CreateDirectory(Path.Combine(mc, "empty"));

        Assert.Equal("Played", Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances).Name);
    }

    [Fact]
    public async Task TheSameFolderSpelledDifferentlyIsStillOneSetup()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        var game = Path.Combine(_root, "Games", "Shared");
        Kit.Write(game, "options.txt", "fov:0.5");
        var odd = Path.Combine(_root, "Games", "x", "..", "SHARED") + Path.DirectorySeparatorChar;
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("a", "One", "custom", "1.21.1", "2026-10-01T10:00:00.000Z", gameDir: game),
            Profile("b", "Two", "custom", "1.21.1", "2026-09-01T10:00:00.000Z",
                gameDir: OperatingSystem.IsWindows() ? odd : Path.Combine(_root, "Games", "x", "..", "Shared"))));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("One", setup.Name);
        Assert.Equal(new[] { "Two" }, setup.Aliases);
    }

    [Fact]
    public async Task AFolderFoundByTwoLaunchersIsOneSetup()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Prism Pack", Pack("1.21.1"), packChanged: T0);
        Log(game, FabricLog("1.21.1"), T0.AddDays(1));
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc);
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("p1", "Official View", "custom", "fabric-loader-0.16.10-1.21.1", "2026-08-01T10:00:00.000Z", gameDir: game)));

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc, PrismDataDir = prism });

        var setup = Assert.Single(scan.Instances);
        Assert.Equal(new[] { "Prism Pack" }, setup.Aliases);                // merged into the setup found first
        Assert.Equal("1.21.1", setup.McVersion);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
        Assert.Contains(scan.Debug, d => d.Contains("merged"));
    }

    [Fact]
    public async Task TwoLaunchersDisagreeingAboutOneFolderAreNotCertainUnlessTheLogDecides()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Prism Pack", Pack("1.20.4"), packChanged: T0);
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc);
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("p1", "Official View", "custom", "fabric-loader-0.16.10-1.21.1", "2026-08-01T10:00:00.000Z", gameDir: game)));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc, PrismDataDir = prism })).Instances);

        Assert.False(setup.VersionCertain);
        Assert.Contains("different versions", setup.UncertainReason);
    }

    // ================================================================ names + stable ids

    [Fact]
    public async Task TwoPrismSetupsWithTheSameNameInDifferentFoldersAreTwoSetupsWithDistinctIds()
    {
        var prism = Path.Combine(_root, "prism");
        Prism(prism, "survival-a", "Survival", Pack("1.21.1"), T0);
        Prism(prism, "survival-b", "Survival", Pack("1.20.4"), T0);

        var scan = await Scan(new SourceRoots { PrismDataDir = prism });

        Assert.Equal(2, scan.Instances.Count);
        Assert.All(scan.Instances, i => Assert.Equal("Survival", i.Name));
        Assert.NotEqual(scan.Instances[0].SetupId, scan.Instances[1].SetupId);
        Assert.Equal(new[] { "1.20.4", "1.21.1" }, scan.Instances.Select(i => i.McVersion).OrderBy(v => v));
    }

    [Fact]
    public async Task TheSetupIdIsUnchangedAcrossRescansAndDependsOnlyOnKindAndFolder()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.21.1"), T0);

        var first = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances).SetupId;
        Kit.Write(prism, "instances/p/instance.cfg", "[General]\nname=Renamed Pack\nlastLaunchTime=1760000000000\n"); // name + recency change
        Log(game, FabricLog("1.21.1"), T0.AddDays(5));
        var second = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal(first, second.SetupId);
        Assert.Equal("Renamed Pack", second.Name);
        Assert.StartsWith("prism-", first);
        Assert.Equal(SetupKey.StableId(SourceKind.Prism, game + Path.DirectorySeparatorChar), first); // a trailing separator doesn't matter
        Assert.NotEqual(SetupKey.StableId(SourceKind.Folder, game), first);                           // the kind is part of it
    }

    // ================================================================ Modrinth (V5)

    [Fact]
    public async Task ModrinthWithALogAndAgreeingModsIsCertain()
    {
        var modrinth = Path.Combine(_root, "ModrinthApp");
        var game = Path.Combine(modrinth, "profiles", "FO");
        Log(game, FabricLog("1.21.1"), T0);
        FabricMods(game, "m", 3, "~1.21.1");

        var setup = Assert.Single((await Scan(new SourceRoots { ModrinthDir = modrinth })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
        Assert.Equal(Confidence.Inferred, setup.Confidence);                // still not from a launcher record
    }

    [Fact]
    public async Task ModrinthWithOnlyALogIsNotCertain()
    {
        var modrinth = Path.Combine(_root, "ModrinthApp");
        var game = Path.Combine(modrinth, "profiles", "FO");
        Log(game, FabricLog("1.21.1"), T0);

        var setup = Assert.Single((await Scan(new SourceRoots { ModrinthDir = modrinth })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.False(setup.VersionCertain);
        Assert.StartsWith("Guessed 1.21.1 from the game's last log; nothing else confirms it.", setup.UncertainReason);
        Assert.False(setup.LoaderCertain);                                  // the loader rests on the same lone log
    }

    [Fact]
    public async Task ModrinthWithOnlyModsGuessesButIsNotCertain()
    {
        var modrinth = Path.Combine(_root, "ModrinthApp");
        var game = Path.Combine(modrinth, "profiles", "NoLog");
        FabricMods(game, "m", 3, "~1.20.4");

        var setup = Assert.Single((await Scan(new SourceRoots { ModrinthDir = modrinth })).Instances);

        Assert.Equal("1.20.4", setup.McVersion);
        Assert.False(setup.VersionCertain);
        Assert.Contains("Guessed 1.20.4 from the mods", setup.UncertainReason);
    }

    // ================================================================ the log parser

    [Theory]
    [InlineData("[main/INFO] (FabricLoader) Loading Minecraft 1.21.1 with Fabric Loader 0.16.10", "1.21.1", LoaderKind.Fabric, "0.16.10")]
    [InlineData("[main/INFO]: Loading Minecraft 1.20.1 with Quilt Loader 0.26.0", "1.20.1", LoaderKind.Quilt, "0.26.0")]
    [InlineData("ModLauncher running: args [--username, Steve, --fml.forgeVersion, 47.2.0, --fml.mcVersion, 1.20.1, --fml.forgeGroup, net.minecraftforge]", "1.20.1", LoaderKind.Forge, "47.2.0")]
    [InlineData("ModLauncher running: args [--fml.neoForgeVersion, 21.1.77, --fml.fmlVersion, 4.0.24, --fml.mcVersion, 1.21.1, --launchTarget, forgeclient]", "1.21.1", LoaderKind.NeoForge, "21.1.77")]
    [InlineData("[main/INFO] [net.neoforged.fml.loading.FMLLoader/CORE]: NeoForge mod loading, version 21.1.77, for MC 1.21.1", "1.21.1", LoaderKind.NeoForge, "21.1.77")]
    [InlineData("[main/INFO] [FML]: Forge Mod Loader version 14.23.5.2859 for Minecraft 1.12.2 loading", "1.12.2", LoaderKind.Forge, "14.23.5.2859")]
    public void TheLogParserReadsEveryLoadersBanner(string line, string mc, LoaderKind loader, string version)
    {
        var parsed = LogParser.Parse("[12:00:00] [main/INFO]: Setting user: Steve\n" + line + "\n");

        Assert.NotNull(parsed);
        Assert.Equal(mc, parsed!.Value.Mc);
        Assert.Equal(loader, parsed.Value.Loader);
        Assert.Equal(version, parsed.Value.LoaderVersion);
    }

    [Fact]
    public void AVanillaVersionLineNamesTheVersionButNoLoaderAndANoiseLogNamesNothing()
    {
        var vanilla = LogParser.Parse("[main/INFO]: Setting user: Steve\n-- System Details --\n\tMinecraft Version: 1.20.4\n");
        Assert.Equal("1.20.4", vanilla!.Value.Mc);
        Assert.Null(vanilla.Value.Loader);
        Assert.Null(LogParser.Parse("[main/INFO]: Setting user: Steve\n[Render thread/INFO]: Backend library: LWJGL\n"));
    }

    [Fact]
    public void OnlyTheLogsHeadIsRead()
    {
        var game = Path.Combine(_root, "big");
        Kit.Write(game, "logs/latest.log", new string('x', LogParser.HeadBytes + 10) + "\n" + FabricLog("1.21.1"));

        Assert.Null(LogParser.Read(game, new SourceFs())); // the banner sits past 256 KB: not read
    }
}
