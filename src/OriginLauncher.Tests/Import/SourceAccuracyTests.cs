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
    private readonly Xunit.Abstractions.ITestOutputHelper _out;

    public SourceAccuracyTests(Xunit.Abstractions.ITestOutputHelper output) => _out = output;

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
        Assert.Contains("The launcher says Fabric 1.20.1, but the game last ran Fabric 1.19.2.", setup.UncertainReason);
        Assert.Equal(Confidence.Inferred, setup.Confidence);                // never Exact while not certain
    }

    [Fact]
    public async Task AStaleLogWithModsAgreeingWithTheNewerLauncherRecordIsStillAsked()
    {
        // Round 3 (certainty gate): this used to be "certain" (launcher newer + mods agree). The game itself never
        // wrote anything newer that agrees, so the 1.19.2 log is a conflict nothing explains -> ask, preselect 1.20.1.
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.20.1"), packChanged: T0.AddDays(1));
        Log(game, FabricLog("1.19.2"), T0);
        FabricMods(game, "m", 3, "~1.20.1");                                // the player already swapped the mods too

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("1.20.1", setup.McVersion);
        Assert.False(setup.VersionCertain);
        Assert.Contains("the game last ran Fabric 1.19.2", setup.UncertainReason);
    }

    [Fact]
    public async Task AnOlderConflictIsExplainedByANewerAgreeingPairTheGameWrote()
    {
        // The other side of the gate: the launcher record is OLDER than a log that agrees with the mods -> certain.
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.20.1"), packChanged: T0);
        Log(game, FabricLog("1.21.1"), T0.AddDays(2));
        FabricMods(game, "m", 3, "~1.21.1");

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
        Assert.Equal(Confidence.Inferred, setup.Confidence);                // the answer isn't the launcher's record
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
        Assert.Contains("The game last ran Fabric 1.20.4, but the launcher says Fabric 1.20.1.", setup.UncertainReason);
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
        Assert.Equal("The launcher says Fabric 1.21.1, but most mods here are built for Minecraft 1.20.4.", setup.UncertainReason);
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
        Assert.Equal("The launcher says Fabric 1.21.1, but only some of the mods here are built for Minecraft 1.21.1.", setup.UncertainReason);
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
        Assert.Equal("The launcher says Fabric 1.21.1, but most mods here are Forge mods.", setup.UncertainReason);
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
        Assert.Contains("The launcher says Fabric 1.20.1, but the game last ran Forge 1.20.1.", setup.UncertainReason);
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
        Assert.Contains("The game last ran Fabric 1.21.1, but the launcher says vanilla 1.21.1.", setup.UncertainReason);
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

        Assert.Equal("Minecraft Launcher (dotminecraft)", setup.Name);      // a plain folder name, never "Latest ..."
        Assert.Equal(new[] { "Latest release", "Latest snapshot" }, setup.Aliases.OrderBy(a => a));
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
        Assert.Contains("1.20.4", setup.UncertainReason);                    // both candidates are named
        Assert.Contains("1.21.1", setup.UncertainReason);
    }

    // ================================================================ regression: the real .minecraft (2026-10-18 dry run)
    // Only latest-release + latest-snapshot profiles, both used recently; an older log that says Fabric 1.21.1;
    // Fabric 1.21.1 mods; versions/ holding a newer snapshot. 07a14534 called it "Latest snapshot", 26.4-snapshot-3,
    // vanilla, Exact — and a bulk import would have skipped the player's real 1.21.1 Fabric setup as unsupported.

    private string RealDotMinecraft(bool log = true, string? modsRange = "~1.21.1", int mods = 6)
    {
        var mc = Path.Combine(_root, "Roaming", ".minecraft");
        foreach (var v in new[] { "1.20", "1.21.1", "1.21.11", "1.8.9", "26.2", "26.3", "26.4-snapshot-3" })
            Kit.Write(mc, $"versions/{v}/{v}.json", $$"""{"id":"{{v}}"}""");          // no manifest: the newest installed are used
        Kit.Write(mc, "options.txt", "fov:0.5");
        Directory.CreateDirectory(Path.Combine(mc, "mods"));
        if (modsRange != null)
        {
            FabricMods(mc, "mod", mods, modsRange);
            FabricMods(mc, "lib", 2, ">=1.21");
        }
        if (log) Log(mc, FabricLog("1.21.1", "0.19.3"), new DateTime(2026, 7, 4, 18, 0, 0, DateTimeKind.Utc));
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("a1b2", null, "latest-release", "latest-release", "2026-10-17T20:00:00.000Z"),
            Profile("c3d4", null, "latest-snapshot", "latest-snapshot", "2026-10-18T01:00:00.000Z")));
        return mc;
    }

    [Fact]
    public async Task RealDotMinecraft_LatestProfilesNeverOutrankAFabricLogAndFabricMods()
    {
        var mc = RealDotMinecraft();

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.Equal("0.19.3", setup.LoaderVersion);
        Assert.True(setup.VersionCertain, setup.UncertainReason);          // log + mods agree (V0, V5)
        Assert.True(setup.LoaderCertain);
        Assert.Equal(Confidence.Inferred, setup.Confidence);                // not from a launcher record
        Assert.Equal("Minecraft Launcher (.minecraft)", setup.Name);        // never "Latest snapshot"
        Assert.Equal(new[] { "Latest release", "Latest snapshot" }, setup.Aliases.OrderBy(a => a));
        Assert.Null(setup.Problem);
        Assert.Contains(setup.Evidence, e => e.From == "Latest snapshot" && e.McVersion == "26.4-snapshot-3" && !e.Explicit);
    }

    [Fact]
    public async Task RealDotMinecraft_WithoutALogTheModsGiveTheVersionButNotCertain()
    {
        var mc = RealDotMinecraft(log: false);

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.False(setup.VersionCertain);                                 // the mods alone are a guess (V5)
        Assert.Contains("most mods here are built for Fabric 1.21.1, but the \"Latest", setup.UncertainReason, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(Confidence.Inferred, setup.Confidence);
        Assert.Equal("Minecraft Launcher (.minecraft)", setup.Name);
    }

    [Fact]
    public async Task RealDotMinecraft_WithNoLogAndNoModsIsVanillaNewestUsedProfileAndNotCertain()
    {
        // Decided: with nothing in the folder naming a version, the most recently used "latest" profile's version is
        // the preselection (here Latest snapshot = 26.4-snapshot-3, vanilla), and it is NOT certain: a latest profile
        // follows whatever is newest, not what the folder last ran, and the two profiles disagree.
        var mc = RealDotMinecraft(log: false, modsRange: null);

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("26.4-snapshot-3", setup.McVersion);
        Assert.Equal(LoaderKind.None, setup.Loader);
        Assert.False(setup.VersionCertain);
        Assert.Equal(Confidence.Inferred, setup.Confidence);
        Assert.Equal("Minecraft Launcher (.minecraft)", setup.Name);
    }

    [Fact]
    public async Task RealDotMinecraft_LogAndModsDisagreeingPrefersTheModsAndIsNotCertain()
    {
        var mc = RealDotMinecraft(modsRange: "~1.21.4");                    // the mods moved on since the log

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.4", setup.McVersion);                            // the mods are what gets imported
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.False(setup.VersionCertain);
        Assert.Contains("Most mods here are built for Fabric 1.21.4, but the game last ran Fabric 1.21.1.", setup.UncertainReason);
    }

    [Fact]
    public async Task AnExplicitVanillaRecordCannotBeCertainAboutAFolderFullOfFabricMods()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.21.4", loaderUid: null), packChanged: T0);  // the launcher says vanilla 1.21.4
        FabricMods(game, "m", 4, "~1.21.1");

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);                            // V0: the version follows the loader-bearing evidence
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.False(setup.LoaderCertain);
        Assert.False(setup.VersionCertain);
        Assert.Equal(Confidence.Inferred, setup.Confidence);
        Assert.Contains("Most mods here are built for Fabric 1.21.1, but the launcher says vanilla 1.21.4.", setup.UncertainReason);
    }

    [Fact]
    public async Task ConfidenceIsNeverExactWhileTheSetupIsNotCertain()
    {
        var prism = Path.Combine(_root, "prism");
        var stale = Prism(prism, "stale", "Stale", Pack("1.20.1"), packChanged: T0.AddDays(1));
        Log(stale, FabricLog("1.19.2"), T0);
        var split = Prism(prism, "split", "Split", Pack("1.21.1"), packChanged: T0);
        FabricMods(split, "a", 2, "~1.21.1");
        FabricMods(split, "b", 2, "~1.20.4");
        Prism(prism, "clean", "Clean", Pack("1.21.1"), packChanged: T0);
        var cf = Path.Combine(_root, "curseforge", "minecraft");
        var forge = Path.Combine(cf, "Instances", "Mixed");
        Kit.Write(forge, "minecraftinstance.json", """{"name":"Mixed","gameVersion":"1.21.1","baseModLoader":{"name":"fabric-0.16.10"}}""");
        ForgeMods(forge, 2);

        var scan = await Scan(new SourceRoots { PrismDataDir = prism, CurseForgeDir = cf });

        Assert.Equal(4, scan.Instances.Count);
        Assert.All(scan.Instances, i =>
            Assert.True(i.Confidence != Confidence.Exact || (i.VersionCertain && i.LoaderCertain), $"{i.Name}: Exact but not certain"));
        Assert.Equal(Confidence.Exact, scan.Instances.Single(i => i.Name == "Clean").Confidence);
        Assert.Equal(3, scan.Instances.Count(i => !i.VersionCertain));
    }

    // ================================================================ review fixes (round 2)

    // The reviewer's shape: the default .minecraft with "Latest release" (1.21.10) played yesterday and a Fabric
    // installer profile for 1.20.1; a vanilla log with no loader banner; no mods. 07a14534 said "certain Fabric
    // 1.20.1" -> a bulk import would put 1.21.10 worlds into 1.20.1.
    private string ReviewerDotMinecraft(string? fabricLastUsed, string? optionsVersion = null)
    {
        var mc = Path.Combine(_root, "rev", ".minecraft");
        Manifest(mc, "1.21.10", "25w41a");
        FabricVersionJson(mc, id: "fabric-loader-0.16.10-1.20.1", inherits: "1.20.1");
        Kit.Write(mc, "options.txt", optionsVersion == null ? "fov:0.5\n" : $"version:{optionsVersion}\nfov:0.5\n");
        Kit.Write(mc, "saves/World/level.dat", "x");
        Log(mc, "[12:00:00] [main/INFO]: Setting user: Steve\n[12:00:01] [Render thread/INFO]: Backend library: LWJGL\n", T0.AddDays(45));
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("latest-release", null, "latest-release", "latest-release", "2026-10-17T20:00:00.000Z"),
            Profile("fab", "fabric-loader-1.20.1", "custom", "fabric-loader-0.16.10-1.20.1", fabricLastUsed)));
        return mc;
    }

    [Fact]
    public async Task ANeverPlayedExplicitProfileNeverBeatsAPlayedLatestProfile()
    {
        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = ReviewerDotMinecraft(fabricLastUsed: null) })).Instances);

        Assert.Equal("1.21.10", setup.McVersion);                           // the played profile is the preselection...
        Assert.Equal(LoaderKind.None, setup.Loader);
        Assert.False(setup.VersionCertain);                                 // ...but a latest profile alone is never certain
        Assert.Equal(Confidence.Inferred, setup.Confidence);
        Assert.Contains("Latest release", setup.Aliases);
    }

    [Fact]
    public async Task ALatestProfilePlayedMoreRecentlyThanAnOldExplicitOneIsPreselectedButNotCertain()
    {
        var setup = Assert.Single((await Scan(new SourceRoots
        {
            OfficialMinecraftDir = ReviewerDotMinecraft(fabricLastUsed: "2026-05-01T10:00:00.000Z"),
        })).Instances);

        Assert.Equal("1.21.10", setup.McVersion);
        Assert.False(setup.VersionCertain);
        Assert.Contains("(now Minecraft 1.21.10)", setup.UncertainReason);    // both candidates are named
        Assert.Contains("Fabric 1.20.1", setup.UncertainReason);
    }

    [Theory]
    [InlineData("4556", "1.21.10", LoaderKind.None)]                        // options.txt last written by 1.21.10
    [InlineData("3465", "1.20.1", LoaderKind.Fabric)]                       // ...or by 1.20.1: the Fabric profile ran last
    public async Task OptionsTxtDataVersionTellsSharedProfilesApart(string dataVersion, string mc, LoaderKind loader)
    {
        var setup = Assert.Single((await Scan(new SourceRoots
        {
            OfficialMinecraftDir = ReviewerDotMinecraft(fabricLastUsed: "2026-05-01T10:00:00.000Z", optionsVersion: dataVersion),
        })).Instances);

        Assert.Equal(mc, setup.McVersion);
        Assert.Equal(loader, setup.Loader);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
        Assert.Contains(setup.Evidence, e => e.Source == EvidenceSource.OptionsFile && e.McVersion == mc);
    }

    [Fact]
    public async Task RealDotMinecraft_OptionsTxtStandsInForAMissingLogAlongsideTheMods()
    {
        var mc = RealDotMinecraft(log: false);
        Kit.Write(mc, "options.txt", "version:3955\nfov:0.5\n");            // written by 1.21.1

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.Equal("Minecraft Launcher (.minecraft)", setup.Name);
        // Version: options.txt + mods agree. Loader: only the jars say Fabric (no log line) -> still a guess (W3).
        Assert.False(setup.LoaderCertain);
        Assert.DoesNotContain("from the mods; there's no", setup.UncertainReason);
    }

    [Fact]
    public async Task ALatestProfilesRecentLastUsedNeverOutranksWhatTheGameWrote()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        Manifest(mc, "1.21.10", "25w41a");
        Kit.Write(mc, "options.txt", "version:3955\n", T0);                 // the game that last wrote it: 1.21.1, months ago
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("latest-release", null, "latest-release", "latest-release", "2026-10-17T20:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);                            // V3: the latest record is not explicit
        Assert.False(setup.VersionCertain);
        Assert.Contains("The game's settings were last saved by Minecraft 1.21.1, but the \"Latest release\" profile (now Minecraft 1.21.10) was last used on "
            + EvidenceJudge.Day(new DateTimeOffset(2026, 10, 17, 20, 0, 0, TimeSpan.Zero)) + ".", setup.UncertainReason); // local day, invariant format
    }

    [Fact]
    public async Task AnOptionsTxtNewerThanTheLogNamingAnotherVersionIsNotCertain()
    {
        var modrinth = Path.Combine(_root, "ModrinthApp");
        var game = Path.Combine(modrinth, "profiles", "Odd");
        Log(game, FabricLog("1.21.1"), T0);
        Kit.Write(game, "options.txt", "version:4189\n", T0.AddDays(1));   // 1.21.4, saved AFTER the log
        FabricMods(game, "m", 3, "1.21.1");                                 // exactly 1.21.1 ("~1.21.1" would also accept 1.21.4)

        var setup = Assert.Single((await Scan(new SourceRoots { ModrinthDir = modrinth })).Instances);

        Assert.Equal("1.21.4", setup.McVersion);                            // the newest thing the game wrote
        Assert.False(setup.VersionCertain);
        Assert.Contains("settings were last saved by Minecraft 1.21.4", setup.UncertainReason);
        Assert.Contains("1.21.1", setup.UncertainReason);
    }

    [Fact]
    public async Task AnOptionsTxtOlderThanAnAgreeingLogAndModsIsExplained()
    {
        // The gate's "explained by time" case: options.txt (1.21.4) is older than a log (1.21.1) the mods agree with.
        var modrinth = Path.Combine(_root, "ModrinthApp");
        var game = Path.Combine(modrinth, "profiles", "Odd");
        Log(game, FabricLog("1.21.1"), T0);
        Kit.Write(game, "options.txt", "version:4189\n", T0.AddDays(-1));
        FabricMods(game, "m", 3, "~1.21.1");

        var setup = Assert.Single((await Scan(new SourceRoots { ModrinthDir = modrinth })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
    }

    [Theory]
    [InlineData("version:3955\nfov:0.5", "1.21.1")]
    [InlineData("fov:0.5\nversion:4671\n", "1.21.11")]
    [InlineData("version:9999\n", null)]                                    // unknown DataVersion: no evidence
    [InlineData("version:abc\n", null)]
    [InlineData("fov:0.5\n", null)]
    public void OptionsTxtVersionIsMappedOnlyWhenKnown(string text, string? expected)
    {
        var game = Path.Combine(_root, "opt-" + Guid.NewGuid().ToString("N"));
        Kit.Write(game, "options.txt", text);

        Assert.Equal(expected, OptionsEvidence.Read(game, new SourceFs())?.McVersion);
    }

    [Fact]
    public async Task TheJarLoaderAloneNeverDecidesBetweenProfilesOfDifferentVersions()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc);
        Kit.Write(mc, "options.txt", "fov:0.5");
        for (var i = 0; i < 3; i++) Kit.WriteBytes(mc, $"mods/m{i}.jar", Kit.FabricJar("m" + i)); // Fabric jars, no Minecraft range
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("a", "Forge 1.20.1", "custom", "1.20.1-forge-47.2.0", "2026-09-01T10:00:00.000Z"),
            Profile("b", "Fabric 1.21.1", "custom", "fabric-loader-0.16.10-1.21.1", "2026-08-01T10:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.False(setup.VersionCertain);                                 // the loader can't say WHICH version
        Assert.Contains("Forge 1.20.1", setup.UncertainReason);             // both candidates are named
        Assert.Contains("Fabric 1.21.1", setup.UncertainReason);
    }

    [Fact]
    public async Task ACustomInstallIdIsNotMistakenForAMinecraftVersion()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        Kit.Write(mc, "a/options.txt", "fov:0.5");
        Kit.Write(mc, "b/options.txt", "fov:0.5");
        Kit.Write(mc, "versions/1.20.1-OptiFine_HD_U_I6/1.20.1-OptiFine_HD_U_I6.json",
            """{"id":"1.20.1-OptiFine_HD_U_I6","inheritsFrom":"1.20.1","libraries":[{"name":"optifine:OptiFine:1.20.1_HD_U_I6"}]}""");
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("a", "No Json", "custom", "1.20.1-OptiFine_HD_U_I5", "2026-10-01T10:00:00.000Z", gameDir: "a"),
            Profile("b", "With Json", "custom", "1.20.1-OptiFine_HD_U_I6", "2026-10-01T10:00:00.000Z", gameDir: "b")));

        var scan = await Scan(new SourceRoots { OfficialMinecraftDir = mc });

        var noJson = scan.Instances.Single(i => i.Name == "No Json");
        Assert.Null(noJson.McVersion);                                       // was "1.20.1-OptiFine_HD_U_I5", certain
        Assert.Equal(DetectedInstance.CouldntRead, noJson.Problem);
        Assert.Equal("1.20.1", scan.Instances.Single(i => i.Name == "With Json").McVersion); // via inheritsFrom
    }

    // ================================================================ re-review probes (round 3): only certain when the evidence agrees

    private static readonly DateTime May1 = new(2026, 5, 1, 12, 0, 0, DateTimeKind.Utc);
    private static readonly DateTime Oct17 = new(2026, 10, 17, 21, 0, 0, DateTimeKind.Utc);

    [Fact]
    public async Task ProbeA_LeftoverFabricModsAndAFreshVanillaPlayNeverMakeAVersionLoaderPairNobodyStated()
    {
        // "Latest release" (1.21.10) played Oct 17, which also wrote options.txt; the mods are leftover Fabric 1.20.1.
        // 78ddd49a answered "1.21.10 Fabric, loader certain" — a setup that never existed.
        var mc = Path.Combine(_root, "probeA", ".minecraft");
        Manifest(mc, "1.21.10", "25w41a");
        Kit.Write(mc, "options.txt", "version:4556\nfov:0.5\n", Oct17);
        FabricMods(mc, "old", 4, "~1.20.1");
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("latest-release", null, "latest-release", "latest-release", "2026-10-17T20:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.10", setup.McVersion);                           // what the game last ran...
        Assert.Equal(LoaderKind.None, setup.Loader);                         // ...as ONE source stated it: vanilla, never "1.21.10 Fabric"
        Assert.False(setup.VersionCertain);
        Assert.False(setup.LoaderCertain);
        Assert.Equal(Confidence.Inferred, setup.Confidence);
        Assert.Contains("1.20.1", setup.UncertainReason);                   // names the other candidate
    }

    [Fact]
    public async Task ProbeB_ANeverPlayedLoaderProfileThatContradictsAFreshLogIsAskedAbout()
    {
        // A never-played "fabric-loader-1.20.1" profile, a fresh Fabric 1.21.1 log, 1.21.1 mods.
        // 78ddd49a preselected 1.20.1 with the loader certain.
        var mc = Path.Combine(_root, "probeB", ".minecraft");
        FabricVersionJson(mc, id: "fabric-loader-0.16.10-1.20.1", inherits: "1.20.1");
        Kit.Write(mc, "options.txt", "fov:0.5");
        Log(mc, FabricLog("1.21.1"), Oct17);
        FabricMods(mc, "m", 4, "~1.21.1");
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("fab", "fabric-loader-1.20.1", "custom", "fabric-loader-0.16.10-1.20.1")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);                            // the log + mods, not the never-played profile
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.False(setup.VersionCertain);                                 // a record with no time can never be outranked
        Assert.Equal(Confidence.Inferred, setup.Confidence);
        Assert.Contains("Fabric 1.20.1 (never played)", setup.UncertainReason);
    }

    [Fact]
    public async Task ProbeC_AMoreRecentlyPlayedProfileContradictingOlderGameEvidenceIsAskedAbout()
    {
        // "Latest release" 1.21.10 played Oct 17; a Fabric 1.20.1 profile played in May; options.txt saved by 1.20.1 in May.
        // 78ddd49a said "1.20.1 Fabric, certain, Exact".
        var mc = Path.Combine(_root, "probeC", ".minecraft");
        Manifest(mc, "1.21.10", "25w41a");
        FabricVersionJson(mc, id: "fabric-loader-0.16.10-1.20.1", inherits: "1.20.1");
        Kit.Write(mc, "options.txt", "version:3465\nfov:0.5\n", May1);
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("latest-release", null, "latest-release", "latest-release", "2026-10-17T20:00:00.000Z"),
            Profile("fab", "Fabric 1.20.1", "custom", "fabric-loader-0.16.10-1.20.1", "2026-05-01T11:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.20.1", setup.McVersion);                            // the newest thing the GAME wrote...
        Assert.Equal(LoaderKind.Fabric, setup.Loader);                       // ...paired with the record that states 1.20.1
        Assert.False(setup.VersionCertain);                                 // a newer play contradicts it
        Assert.NotEqual(Confidence.Exact, setup.Confidence);
        Assert.Contains("the \"Latest release\" profile (now Minecraft 1.21.10) was last used on", setup.UncertainReason);
    }

    [Fact]
    public async Task WhenTheModsVersionIsPreselectedItsLoaderComesFromTheModsToo()
    {
        // LOW: version from the mods + loader version from the log would be a pair no single source stated.
        var mc = RealDotMinecraft(modsRange: "~1.21.4");                    // log: Fabric 0.19.3 on 1.21.1; mods: 1.21.4

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.4", setup.McVersion);
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.Null(setup.LoaderVersion);                                   // 0.19.3 belongs to the 1.21.1 run, not to these mods
    }

    // ================================================================ re-review round 4: agreeing setups are Exact again

    [Fact]
    public async Task RP1_APrismInstanceWhoseNewerLogAndModsAgreeIsExact()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.21.1"), packChanged: T0);
        Log(game, FabricLog("1.21.1"), T0.AddDays(1));                      // played since: the log is the newer piece
        FabricMods(game, "m", 3, "~1.21.1");

        var setup = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.True(setup.VersionCertain, setup.UncertainReason);
        Assert.Equal(Confidence.Exact, setup.Confidence);                   // not "(guessed)" in the import panel
    }

    [Fact]
    public async Task RP2_AnOfficialFabricProfileWhoseNewerLogAndModsAgreeIsExact()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc);
        Kit.Write(mc, "options.txt", "fov:0.5");
        Log(mc, FabricLog("1.21.1"), new DateTime(2026, 10, 1, 11, 0, 0, DateTimeKind.Utc));
        FabricMods(mc, "m", 3, "~1.21.1");
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("p1", "Fabric", "custom", "fabric-loader-0.16.10-1.21.1", "2026-10-01T10:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.True(setup.VersionCertain, setup.UncertainReason);
        Assert.Equal(Confidence.Exact, setup.Confidence);
    }

    [Fact]
    public async Task RP8_AVanillaProfileConfirmedByOptionsTxtIsExact()
    {
        var mc = Path.Combine(_root, "dotminecraft");
        Kit.Write(mc, "versions/1.21.1/1.21.1.json", """{"id":"1.21.1"}""");
        Kit.Write(mc, "options.txt", "version:3955\nfov:0.5\n", new DateTime(2026, 10, 1, 11, 0, 0, DateTimeKind.Utc));
        Log(mc, "[main/INFO]: Setting user: Steve\n", new DateTime(2026, 10, 1, 11, 0, 0, DateTimeKind.Utc)); // vanilla: no banner
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("p1", "Vanilla", "custom", "1.21.1", "2026-10-01T10:00:00.000Z")));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.Equal(LoaderKind.None, setup.Loader);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
        Assert.Equal(Confidence.Exact, setup.Confidence);
    }

    [Fact]
    public async Task RP6_ANewerOptionsTxtKeepsLaterLaunchesAsEvidenceAndNoPairIsInvented()
    {
        // Will's real shape, plus options.txt saved on Oct 17 by 1.21.10 (newer than the July log): the game DID run here
        // since the log, so the October launches stay as evidence. 06e47446 answered "Fabric 1.21.10" — Fabric borrowed
        // from mods whose ~1.21.1 range merely accepts 1.21.10 — and its reason never named 1.21.1.
        var mc = RealDotMinecraft();
        Kit.Write(mc, "options.txt", "version:4556\nfov:0.5\n", Oct17);

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.10", setup.McVersion);                           // the newest thing the game wrote
        Assert.NotEqual(LoaderKind.Fabric, setup.Loader);                   // nobody stated "Fabric 1.21.10"
        Assert.False(setup.VersionCertain);
        Assert.Equal(Confidence.Inferred, setup.Confidence);
        Assert.Contains("Minecraft 1.21.10", setup.UncertainReason);        // both candidates are named
        Assert.Contains("Fabric 1.21.1", setup.UncertainReason);
        Assert.Contains("the \"Latest", setup.UncertainReason);              // the October launches were NOT set aside
    }

    [Fact]
    public async Task RealDotMinecraft_ANewerOptionsTxtWithTheLogsOwnVersionKeepsTheAnswerCertain()
    {
        // Will's files: options.txt (2026-09-07) is newer than latest.log (2026-07-04). Today it has no version line; if it
        // had the log's own 1.21.1, the October "latest" launches must still be set aside (something rewrote settings,
        // nothing ran another version) — the answer stays certain 1.21.1 Fabric.
        var mc = RealDotMinecraft();
        Kit.Write(mc, "options.txt", "version:3955\nfov:0.5\n", new DateTime(2026, 9, 7, 12, 0, 0, DateTimeKind.Utc));

        var setup = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc })).Instances);

        Assert.Equal("1.21.1", setup.McVersion);
        Assert.Equal(LoaderKind.Fabric, setup.Loader);
        Assert.True(setup.VersionCertain, setup.UncertainReason);
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
    public async Task TheSetupIdIsUnchangedAcrossRescansAndDependsOnlyOnTheFolder()
    {
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Pack", Pack("1.21.1"), T0);

        var first = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances).SetupId;
        Kit.Write(prism, "instances/p/instance.cfg", "[General]\nname=Renamed Pack\nlastLaunchTime=1760000000000\n"); // name + recency change
        Log(game, FabricLog("1.21.1"), T0.AddDays(5));
        var second = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);

        Assert.Equal(first, second.SetupId);
        Assert.Equal("Renamed Pack", second.Name);
        Assert.StartsWith("setup-", first);
        Assert.Equal(SetupKey.StableId(game + Path.DirectorySeparatorChar), first); // a trailing separator doesn't matter
    }

    [Fact]
    public async Task OneFolderHasOneIdWhicheverLauncherOrBrowseFindsIt()
    {
        // Review fix: the id used to hash the name-winner's Kind, which flips when another launcher gets played,
        // and Browse re-kinds official profiles to Folder -> a second id for the same folder.
        var prism = Path.Combine(_root, "prism");
        var game = Prism(prism, "p", "Prism Pack", Pack("1.21.1"), packChanged: T0);
        var mc = Path.Combine(_root, "dotminecraft");
        FabricVersionJson(mc);
        Kit.Write(mc, "launcher_profiles.json", OfficialProfiles(
            Profile("p1", "Official View", "custom", "fabric-loader-0.16.10-1.21.1", "2026-10-01T10:00:00.000Z", gameDir: game)));

        var prismOnly = Assert.Single((await Scan(new SourceRoots { PrismDataDir = prism })).Instances);
        var both = Assert.Single((await Scan(new SourceRoots { OfficialMinecraftDir = mc, PrismDataDir = prism })).Instances);
        var browsedOfficial = FolderSource.Inspect(mc, new SourceFs(), CancellationToken.None).Instances.Single();
        var browsedGameDir = Assert.Single(FolderSource.Inspect(game, new SourceFs(), CancellationToken.None).Instances);

        Assert.Equal(SourceKind.OfficialLauncher, both.Kind);              // a different kind wins the name here...
        Assert.Equal(SourceKind.Folder, browsedOfficial.Kind);
        Assert.Equal(prismOnly.SetupId, both.SetupId);                     // ...but the folder's id never changes
        Assert.Equal(prismOnly.SetupId, browsedOfficial.SetupId);
        Assert.Equal(prismOnly.SetupId, browsedGameDir.SetupId);
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
        Assert.StartsWith("Only the game's last log points to Minecraft 1.21.1; nothing else confirms it.", setup.UncertainReason);
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
        Assert.Contains("Only the mods point to Minecraft 1.20.4", setup.UncertainReason);
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
