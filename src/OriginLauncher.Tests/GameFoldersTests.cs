using OriginLauncher.App.Core.GameFolders;
using Xunit;

namespace OriginLauncher.Tests;

// Per-account game folders (several Minecraft windows at once, one per
// account): the folder layout, the one-time migration from the old
// one-folder-per-version layout, seeding a new account's folder, and the
// per-account launch state machine behind the Play button. Folder tests run on
// real files in a temp directory.
public class GameFoldersTests : IDisposable
{
    private const string Steve = "069a79f444e94726a5befca90e38aaf5";
    private const string Alex = "853c80ef3c3749fdaa49938b674adae6";

    private readonly string _root = Directory.CreateTempSubdirectory("game-folders").FullName;

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch { /* best effort: a locked temp folder must not fail the test */ }
    }

    private string Write(string relativePath, string text = "x")
    {
        var path = Path.Combine(_root, relativePath.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, text);
        return path;
    }

    private string Read(string relativePath) =>
        File.ReadAllText(Path.Combine(_root, relativePath.Replace('/', Path.DirectorySeparatorChar)));

    private bool Exists(string relativePath)
    {
        var path = Path.Combine(_root, relativePath.Replace('/', Path.DirectorySeparatorChar));
        return File.Exists(path) || Directory.Exists(path);
    }

    // An old-layout version folder: shared game files and one player's data
    // side by side at the top.
    private string OldLayout(string version)
    {
        Write($"{version}/versions/{version}/{version}.jar", "game");
        Write($"{version}/libraries/org/lwjgl/lwjgl.jar", "lib");
        Write($"{version}/assets/indexes/17.json", "{}");
        Write($"{version}/runtime/java-runtime-delta/bin/java.exe", "java");
        Write($"{version}/origin-verified.json", "{}");
        Write($"{version}/options.txt", "fov:0.5\nguiScale:2\n");
        Write($"{version}/servers.dat", "servers");
        Write($"{version}/saves/My World/level.dat", "world");
        Write($"{version}/mods/originclient.jar", "origin");
        Write($"{version}/mods/my-mod.jar", "mine");
        Write($"{version}/config/originclient-mods.json", "{\"hud\":1}");
        Write($"{version}/logs/latest.log", "log");
        return Path.Combine(_root, version);
    }

    // ------------------------------------------------------------------ layout

    [Fact]
    public void AccountKeyIsTheProfileUuidLowerCaseWithoutDashes()
    {
        Assert.Equal(Steve, InstanceLayout.KeyFor("069A79F4-44E9-4726-A5BE-FCA90E38AAF5"));
        Assert.Equal(Steve, InstanceLayout.KeyFor(Steve));
    }

    [Fact]
    public void NoAccountMeansTheOfflineFolder()
    {
        Assert.Equal(InstanceLayout.OfflineKey, InstanceLayout.KeyFor(null));
        Assert.Equal(InstanceLayout.OfflineKey, InstanceLayout.KeyFor("  "));
    }

    [Fact]
    public void AKeyIsAlwaysOneSafeFolderName()
    {
        Assert.Equal("unknown", InstanceLayout.KeyFor("../.."));
        Assert.Equal("ab", InstanceLayout.KeyFor("a/../b"));
        Assert.True(InstanceLayout.IsAccountKey(InstanceLayout.KeyFor("a/../b")));
    }

    [Fact]
    public void GameFoldersLiveUnderTheVersionsAccountsFolder()
    {
        Assert.Equal(Path.Combine("I", "1.21.1"), InstanceLayout.VersionRoot("I", "1.21.1"));
        Assert.Equal(Path.Combine("I", "1.21.1", "accounts", Steve), InstanceLayout.GameDir("I", "1.21.1", Steve));
        Assert.Equal(Path.Combine("I", "1.21.1", "accounts", "offline"),
            InstanceLayout.GameDir("I", "1.21.1", InstanceLayout.OfflineKey));
    }

    [Fact]
    public void OnlyRealAccountFoldersCountAsAccounts()
    {
        Assert.True(InstanceLayout.IsAccountKey(Steve));
        Assert.True(InstanceLayout.IsAccountKey("offline"));
        Assert.False(InstanceLayout.IsAccountKey($".seeding-{Steve}-0123"));
        Assert.False(InstanceLayout.IsAccountKey("Steve"));
        Assert.False(InstanceLayout.IsAccountKey(""));
    }

    [Fact]
    public void EachAccountExtractsNativesIntoItsOwnFolder()
    {
        var a = InstanceLayout.NativesDir("versions", "fabric-loader-0.16-1.21.1", Steve);
        var b = InstanceLayout.NativesDir("versions", "fabric-loader-0.16-1.21.1", Alex);
        Assert.NotEqual(a, b);
        Assert.StartsWith(Path.Combine("versions", "fabric-loader-0.16-1.21.1"), a);
    }

    // --------------------------------------------------------------- migration

    [Fact]
    public void MigrationPlanMovesEverythingButTheSharedGameFiles()
    {
        var plan = InstanceMigration.EntriesToMove(new[]
        {
            "versions", "libraries", "assets", "runtime", "resources", "origin-verified.json", "accounts",
            "options.txt", "saves", "mods", "config", "logs", ".fabric", "mods-origin-only",
        });
        // mods/ stays: it becomes the version's shared mods folder.
        Assert.Equal(new[] { ".fabric", "config", "logs", "mods-origin-only", "options.txt", "saves" }, plan);
    }

    [Fact]
    public void SharedNamesAreMatchedWithoutCase()
    {
        Assert.Empty(InstanceMigration.EntriesToMove(new[] { "Versions", "ASSETS", "Accounts" }));
    }

    [Fact]
    public void MigrationMovesTheOldFolderIntoTheAccountAndKeepsSharedFilesInPlace()
    {
        var root = OldLayout("1.21.1");
        Assert.True(InstanceMigration.NeedsMigration(root));

        Assert.True(InstanceMigration.MigrateVersion(root, Steve));

        var game = $"1.21.1/accounts/{Steve}";
        Assert.Equal("fov:0.5\nguiScale:2\n", Read($"{game}/options.txt"));
        Assert.Equal("servers", Read($"{game}/servers.dat"));
        Assert.Equal("world", Read($"{game}/saves/My World/level.dat"));
        Assert.Equal("{\"hud\":1}", Read($"{game}/config/originclient-mods.json"));
        Assert.Equal("log", Read($"{game}/logs/latest.log"));
        // The old mods folder stays put as the version's shared one.
        Assert.Equal("mine", Read("1.21.1/mods/my-mod.jar"));
        Assert.Equal("origin", Read("1.21.1/mods/originclient.jar"));
        Assert.False(Exists($"{game}/mods"));
        // The heavy shared files stay where every account's launch finds them.
        Assert.Equal("game", Read("1.21.1/versions/1.21.1/1.21.1.jar"));
        Assert.Equal("lib", Read("1.21.1/libraries/org/lwjgl/lwjgl.jar"));
        Assert.True(Exists("1.21.1/assets/indexes/17.json"));
        Assert.True(Exists("1.21.1/runtime/java-runtime-delta/bin/java.exe"));
        Assert.True(Exists("1.21.1/origin-verified.json"));
        // Nothing of the player's is left at the top.
        Assert.False(Exists("1.21.1/options.txt"));
        Assert.False(Exists("1.21.1/saves"));
        Assert.False(InstanceMigration.NeedsMigration(root));
    }

    [Fact]
    public void MigrationIsIdempotent()
    {
        var root = OldLayout("1.20.1");
        Assert.True(InstanceMigration.MigrateVersion(root, Steve));
        var before = Directory.EnumerateFileSystemEntries(root, "*", SearchOption.AllDirectories).OrderBy(p => p).ToList();

        Assert.True(InstanceMigration.MigrateVersion(root, Steve));
        Assert.True(InstanceMigration.MigrateVersion(root, Alex)); // a later account changes nothing

        var after = Directory.EnumerateFileSystemEntries(root, "*", SearchOption.AllDirectories).OrderBy(p => p).ToList();
        Assert.Equal(before, after);
        Assert.False(Exists($"1.20.1/accounts/{Alex}"));
    }

    [Fact]
    public void AMigrationInterruptedHalfwayFinishesOnTheNextPass()
    {
        var root = OldLayout("1.21.1");
        // A crash after two of the renames: those entries are already in the
        // account folder, the rest still at the top.
        var game = Path.Combine(root, "accounts", Steve);
        Directory.CreateDirectory(game);
        Directory.Move(Path.Combine(root, "config"), Path.Combine(game, "config"));
        File.Move(Path.Combine(root, "options.txt"), Path.Combine(game, "options.txt"));

        Assert.True(InstanceMigration.MigrateVersion(root, Steve));

        Assert.Equal("fov:0.5\nguiScale:2\n", Read($"1.21.1/accounts/{Steve}/options.txt"));
        Assert.Equal("world", Read($"1.21.1/accounts/{Steve}/saves/My World/level.dat"));
        Assert.Equal("{\"hud\":1}", Read($"1.21.1/accounts/{Steve}/config/originclient-mods.json"));
        Assert.False(InstanceMigration.NeedsMigration(root));
    }

    [Fact]
    public void MigrationMergesIntoFoldersTheAccountAlreadyHasAndNeverOverwrites()
    {
        var root = OldLayout("1.21.1");
        // The account already played this version in the new layout (the old
        // game was still running at the last pass).
        Write($"1.21.1/accounts/{Steve}/saves/New World/level.dat", "new world");
        Write($"1.21.1/accounts/{Steve}/options.txt", "fov:1.0\n");

        Assert.True(InstanceMigration.MigrateVersion(root, Steve));

        var game = $"1.21.1/accounts/{Steve}";
        Assert.Equal("world", Read($"{game}/saves/My World/level.dat"));
        Assert.Equal("new world", Read($"{game}/saves/New World/level.dat"));
        // The account's current file wins; the old one is kept beside it.
        Assert.Equal("fov:1.0\n", Read($"{game}/options.txt"));
        Assert.Equal("fov:0.5\nguiScale:2\n", Read($"{game}/options.txt.before-accounts"));
        Assert.False(Exists("1.21.1/saves"));
        Assert.False(InstanceMigration.NeedsMigration(root));
    }

    [Fact]
    public void AMissingVersionFolderNeedsNothing()
    {
        Assert.True(InstanceMigration.MigrateVersion(Path.Combine(_root, "1.19.4"), Steve));
        Assert.False(InstanceMigration.NeedsMigration(Path.Combine(_root, "1.19.4")));
    }

    // The migration runs on every launcher start and moves anything at the top of a version root that is not in
    // InstanceLayout's shared set. config-overlays (profile Sodium/Iris settings) was missing from that set and was
    // carried off into one account's folder on every start. Every path the launcher builds inside a version root,
    // and every literal its source joins onto one, must therefore name a shared entry.
    [Fact]
    public void EveryLauncherMadeVersionRootEntryIsShared()
    {
        const string version = "9.9.9";
        var versionRoot = Path.GetFullPath(OriginLauncher.App.Core.OriginPaths.VersionRoot(version)) + Path.DirectorySeparatorChar;
        var checkedPaths = 0;
        foreach (var method in typeof(OriginLauncher.App.Core.OriginPaths).GetMethods(
                     System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static))
        {
            var parameters = method.GetParameters();
            if (method.ReturnType != typeof(string) || parameters.Length == 0
                || parameters.Any(p => p.ParameterType != typeof(string))
                || !parameters.Any(p => p.Name == "version")) continue;
            // Pure path builders: a version, then account keys / profile ids.
            var path = Path.GetFullPath((string)method.Invoke(null,
                parameters.Select(p => (object)(p.Name == "version" ? version : "abc123")).ToArray())!);
            if (!path.StartsWith(versionRoot, StringComparison.Ordinal)) continue; // e.g. InstallState, outside instances/
            var top = path[versionRoot.Length..].Split(Path.DirectorySeparatorChar)[0];
            Assert.True(InstanceLayout.IsSharedEntry(top), $"OriginPaths.{method.Name} puts '{top}' at the top of a version root, which the migration would move");
            checkedPaths++;
        }
        Assert.True(checkedPaths >= 6, $"only {checkedPaths} OriginPaths version paths found");

        // Every name joined straight onto a version root in the launchers' source.
        var repo = RepoRoot();
        if (repo == null) return; // run outside a checkout
        var joined = new System.Text.RegularExpressions.Regex(
            @"(?:VersionRoot\([^()]*\)|\b(?:sharedRoot|versionRoot)),\s*""([^""]+)""");
        var found = 0;
        foreach (var project in new[] { "OriginLauncher.App", "OriginLauncher.Desktop", "OriginLauncher.Mac" })
        {
            var dir = Path.Combine(repo, "src", project);
            if (!Directory.Exists(dir)) continue;
            foreach (var file in Directory.EnumerateFiles(dir, "*.cs", SearchOption.AllDirectories))
                foreach (System.Text.RegularExpressions.Match m in joined.Matches(File.ReadAllText(file)))
                {
                    var top = m.Groups[1].Value.Split('/', '\\')[0];
                    Assert.True(InstanceLayout.IsSharedEntry(top), $"{Path.GetFileName(file)} joins '{top}' onto a version root, which the migration would move");
                    found++;
                }
        }
        Assert.True(found > 0, "the source scan found no version-root joins; the pattern no longer matches the code");

        // And the folders CmlLib (MinecraftPath on the version root) and the install stamp make.
        foreach (var name in new[] { "versions", "libraries", "assets", "runtime", "resources", InstanceLayout.VerifiedStampFileName })
            Assert.True(InstanceLayout.IsSharedEntry(name), name);
    }

    [Fact]
    public void ConfigOverlaysStayInTheVersionRoot()
    {
        var root = OldLayout("1.21.1");
        Assert.True(InstanceMigration.MigrateVersion(root, Steve));
        Write($"1.21.1/config-overlays/{Steve}/active.txt", "sodium-iris-x");

        Assert.False(InstanceMigration.NeedsMigration(root));
        Assert.True(InstanceMigration.MigrateVersion(root, Steve));
        Assert.Equal("sodium-iris-x", Read($"1.21.1/config-overlays/{Steve}/active.txt"));
    }

    // Installs that ran the older launcher have config-overlays inside the inheriting account's folder (moved there on
    // every start). The next start puts it back; the version root's copy is the current one and wins a clash.
    [Fact]
    public void AMovedConfigOverlaysFolderIsPutBackWithoutOverwriting()
    {
        var root = OldLayout("1.21.1");
        Assert.True(InstanceMigration.MigrateVersion(root, Steve));
        var moved = $"1.21.1/accounts/{Steve}/config-overlays";
        Write($"{moved}/{Steve}/active.txt", "sodium-iris-old");                       // a swap the launcher never finished
        Write($"{moved}/{Steve}/sodium-iris-old/sodium-options.json", "old pair");
        Write($"{moved}/{Alex}/default/iris.properties", "alex older");
        Write($"1.21.1/config-overlays/{Alex}/default/iris.properties", "alex now");  // made since the last move

        var marker = Path.Combine(_root, "account-migration.json");
        Assert.Equal((Steve, true), InstanceMigration.MigrateAll(marker, new[] { root }, Steve));

        Assert.False(Exists(moved));
        Assert.Equal("sodium-iris-old", Read($"1.21.1/config-overlays/{Steve}/active.txt"));
        Assert.Equal("old pair", Read($"1.21.1/config-overlays/{Steve}/sodium-iris-old/sodium-options.json"));
        Assert.Equal("alex now", Read($"1.21.1/config-overlays/{Alex}/default/iris.properties"));
        Assert.Equal("alex older", Read($"1.21.1/config-overlays/{Alex}/default/iris.properties.older"));
        // The player's own game data is untouched, and a second pass does nothing.
        Assert.Equal("fov:0.5\nguiScale:2\n", Read($"1.21.1/accounts/{Steve}/options.txt"));
        Assert.True(InstanceMigration.ReturnMisplacedShared(root));
    }

    // Up to v1.0.88 every start moved config-overlays into the account selected AT THAT START: a player who switched
    // accounts between starts has copies under several accounts. Every one comes back; nothing is overwritten: the
    // version root's file wins, then the newest account copy, and older copies are parked as ".older".
    [Fact]
    public void ConfigOverlaysMovedIntoSeveralAccountsAllComeBackNewestFirst()
    {
        var root = OldLayout("1.21.1");
        Assert.True(InstanceMigration.MigrateVersion(root, Steve));
        var steveCopy = $"1.21.1/accounts/{Steve}/config-overlays";
        var alexCopy = $"1.21.1/accounts/{Alex}/config-overlays";
        Write($"{steveCopy}/{Steve}/default/sodium-options.json", "steve older start");
        Write($"{steveCopy}/{Alex}/default/iris.properties", "alex older");
        Write($"{alexCopy}/{Steve}/default/sodium-options.json", "steve newer start");
        Write($"1.21.1/config-overlays/{Alex}/default/iris.properties", "alex now");
        var old = DateTime.UtcNow.AddDays(-2);
        foreach (var f in Directory.EnumerateFiles(Path.Combine(_root, steveCopy), "*", SearchOption.AllDirectories))
            File.SetLastWriteTimeUtc(f, old);

        // The marker names Steve, but Alex's folder is checked too.
        var marker = Path.Combine(_root, "account-migration.json");
        Assert.Equal((Steve, true), InstanceMigration.MigrateAll(marker, new[] { root }, Steve));

        Assert.False(Exists(steveCopy));
        Assert.False(Exists(alexCopy));
        Assert.Equal("steve newer start", Read($"1.21.1/config-overlays/{Steve}/default/sodium-options.json"));
        Assert.Equal("steve older start", Read($"1.21.1/config-overlays/{Steve}/default/sodium-options.json.older"));
        Assert.Equal("alex now", Read($"1.21.1/config-overlays/{Alex}/default/iris.properties"));
        Assert.Equal("alex older", Read($"1.21.1/config-overlays/{Alex}/default/iris.properties.older"));
        Assert.True(InstanceMigration.ReturnMisplacedShared(root)); // nothing left
    }

    private static string? RepoRoot()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir != null; dir = dir.Parent)
            if (File.Exists(Path.Combine(dir.FullName, "src", "mods", "live-modules.json"))) return dir.FullName;
        return null;
    }

    [Fact]
    public void TheMarkerRemembersWhichAccountInheritsTheOldFolders()
    {
        var marker = Path.Combine(_root, "account-migration.json");
        Assert.Null(InstanceMigration.ReadMarker(marker));

        InstanceMigration.WriteMarker(marker, Steve);
        Assert.Equal(Steve, InstanceMigration.ReadMarker(marker));
        Assert.Empty(Directory.EnumerateFiles(_root, "*.tmp"));
    }

    // The migration runs on the UI thread (App startup, Home's account refresh). A marker file another program holds
    // open (antivirus, a backup tool) must fail the write at once: never a frozen window while it waits, nothing
    // moved, and the next call does the whole move.
    [Fact]
    public void ABusyMarkerFailsTheMigrationAtOnceMovesNothingAndTheNextCallFinishes()
    {
        if (!OperatingSystem.IsWindows()) return; // Linux renames over an open file, so the marker is never busy there
        var root = OldLayout("1.21.1");
        var marker = Write("account-migration.json", ""); // unreadable, so no account is recorded yet
        var reader = new FileStream(marker, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);

        var clock = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            Assert.ThrowsAny<Exception>(() => InstanceMigration.MigrateAll(marker, new[] { root }, Steve));
        }
        finally
        {
            clock.Stop();
            reader.Dispose();
        }

        // The rename's wait for readers is ~1.46 s; the UI-thread write must not take it.
        Assert.True(clock.ElapsedMilliseconds < 1000, $"a busy marker held the migration for {clock.ElapsedMilliseconds} ms");
        Assert.True(InstanceMigration.NeedsMigration(root));
        Assert.Equal("world", Read("1.21.1/saves/My World/level.dat"));
        Assert.False(Exists("1.21.1/accounts"));

        Assert.Equal((Steve, true), InstanceMigration.MigrateAll(marker, new[] { root }, Steve));
        Assert.Equal(Steve, InstanceMigration.ReadMarker(marker));
        Assert.Equal("world", Read($"1.21.1/accounts/{Steve}/saves/My World/level.dat"));
        Assert.False(InstanceMigration.NeedsMigration(root));
    }

    [Fact]
    public void ADamagedOrForgedMarkerIsIgnored()
    {
        var marker = Write("account-migration.json", "{not json");
        Assert.Null(InstanceMigration.ReadMarker(marker));
        File.WriteAllText(marker, "{\"Account\":\"../../elsewhere\"}");
        Assert.Null(InstanceMigration.ReadMarker(marker));
    }

    // ----------------------------------------------------------------- seeding

    [Theory]
    [InlineData("options.txt", true)]
    [InlineData("servers.dat", true)]
    [InlineData("optionsof.txt", true)]
    [InlineData("optionsshaders.txt", true)]
    [InlineData("config/originclient.json", true)]
    [InlineData("config/originclient-mods.json", true)]
    [InlineData("config/originclient-waypoints.json", true)]
    [InlineData("config/sodium-options.json", true)]
    [InlineData("config/iris.properties", true)]
    [InlineData("config/voicechat/voicechat-client.properties", true)]
    [InlineData(@"config\iris.properties", true)]
    [InlineData("config/originclient-mods.json.tmp", false)]
    [InlineData("saves/My World/level.dat", false)]
    [InlineData("screenshots/2026-10-12.png", false)]
    [InlineData("logs/latest.log", false)]
    [InlineData("crash-reports/crash.txt", false)]
    [InlineData("mods/originclient.jar", false)]
    [InlineData("resourcepacks/pack.zip", false)]
    [InlineData("shaderpacks/pack.zip", false)]
    [InlineData("hotbar.nbt", false)]
    [InlineData("usercache.json", false)]
    [InlineData("", false)]
    public void OnlySettingsAreSeeded(string relativePath, bool seeded)
    {
        Assert.Equal(seeded, AccountSeed.IsSettingsFile(relativePath));
    }

    [Fact]
    public void TheSeedListIsTheSourcesSettingsFiles()
    {
        OldLayout("1.21.1");
        Write("1.21.1/config/sodium-options.json", "{}");
        Write("1.21.1/screenshots/a.png", "png");
        var files = AccountSeed.FilesToSeed(Path.Combine(_root, "1.21.1"));
        Assert.Equal(new[] { "config/originclient-mods.json", "config/sodium-options.json", "options.txt", "servers.dat" }, files);
    }

    [Fact]
    public void ANewAccountFolderGetsTheSettingsButNotTheWorlds()
    {
        var root = OldLayout("1.21.1");
        InstanceMigration.MigrateVersion(root, Steve);
        var source = Path.Combine(root, "accounts", Steve);
        var target = Path.Combine(root, "accounts", Alex);

        Assert.True(AccountSeed.Seed(target, source));

        var game = $"1.21.1/accounts/{Alex}";
        Assert.Equal("fov:0.5\nguiScale:2\n", Read($"{game}/options.txt"));
        Assert.Equal("servers", Read($"{game}/servers.dat"));
        Assert.Equal("{\"hud\":1}", Read($"{game}/config/originclient-mods.json"));
        Assert.False(Exists($"{game}/saves"));
        Assert.False(Exists($"{game}/mods"));
        Assert.False(Exists($"{game}/logs"));
        // The source is untouched, and no temporary folder is left behind.
        Assert.Equal("world", Read($"1.21.1/accounts/{Steve}/saves/My World/level.dat"));
        Assert.Equal(new[] { Alex, Steve }.OrderBy(k => k),
            Directory.EnumerateDirectories(Path.Combine(root, "accounts")).Select(Path.GetFileName).OrderBy(k => k));
    }

    [Fact]
    public void ANewAccountFolderGetsCopiesOfTheResourceAndShaderPacks()
    {
        var root = OldLayout("1.21.1");
        InstanceMigration.MigrateVersion(root, Steve);
        var src = $"1.21.1/accounts/{Steve}";
        Write($"{src}/resourcepacks/Faithful.zip", "faithful");
        Write($"{src}/resourcepacks/My Pack/pack.mcmeta", "{}");
        Write($"{src}/shaderpacks/Complementary.zip", "shader");
        Write($"{src}/shaderpacks/Complementary.zip.txt", "SHADOW=true");
        Write($"{src}/shaderpacks/half.zip.tmp", "half");
        var target = Path.Combine(root, "accounts", Alex);

        Assert.Equal(new[] { "resourcepacks/Faithful.zip", "resourcepacks/My Pack/pack.mcmeta",
                "shaderpacks/Complementary.zip", "shaderpacks/Complementary.zip.txt" },
            AccountSeed.PackFilesToSeed(Path.Combine(root, "accounts", Steve)));
        Assert.True(AccountSeed.Seed(target, Path.Combine(root, "accounts", Steve)));

        var game = $"1.21.1/accounts/{Alex}";
        Assert.Equal("faithful", Read($"{game}/resourcepacks/Faithful.zip"));
        Assert.Equal("{}", Read($"{game}/resourcepacks/My Pack/pack.mcmeta"));
        Assert.Equal("shader", Read($"{game}/shaderpacks/Complementary.zip"));
        Assert.Equal("SHADOW=true", Read($"{game}/shaderpacks/Complementary.zip.txt"));
        Assert.False(Exists($"{game}/shaderpacks/half.zip.tmp"));

        // Copies, not moves or links: removing the new account's pack leaves the source's.
        File.Delete(Path.Combine(_root, game, "resourcepacks", "Faithful.zip"));
        Assert.Equal("faithful", Read($"{src}/resourcepacks/Faithful.zip"));
    }

    [Fact]
    public void PacksTooBigOrWithoutRoomOnTheDiskAreLeftOutWithANotice()
    {
        var root = OldLayout("1.21.1");
        InstanceMigration.MigrateVersion(root, Steve);
        var src = Path.Combine(root, "accounts", Steve);
        Write($"1.21.1/accounts/{Steve}/shaderpacks/Complementary.zip", new string('x', 1000));

        string? notice = null, stage = null;
        var big = Path.Combine(root, "accounts", Alex);
        Assert.True(AccountSeed.Seed(big, src, stage: s => stage = s, notice: n => notice = n, maxPackBytes: 999));
        Assert.Contains("more than 4 GB", notice);
        Assert.Null(stage);
        Assert.False(Exists($"1.21.1/accounts/{Alex}/shaderpacks"));
        Assert.Equal("servers", Read($"1.21.1/accounts/{Alex}/servers.dat")); // the settings still came over

        notice = null;
        var full = Path.Combine(root, "accounts", "third");
        Assert.True(AccountSeed.Seed(full, src, notice: n => notice = n, freeBytes: _ => AccountSeed.FreeSpaceMargin + 500));
        Assert.Contains("not enough free disk space", notice);
        Assert.False(Exists("1.21.1/accounts/third/shaderpacks"));

        notice = null;
        var fits = Path.Combine(root, "accounts", "fourth");
        Assert.True(AccountSeed.Seed(fits, src, stage: s => stage = s, notice: n => notice = n,
            freeBytes: _ => AccountSeed.FreeSpaceMargin + 2000));
        Assert.Null(notice);
        Assert.Equal(AccountSeed.CopyingPacksStage, stage);
        Assert.Equal(1000, new FileInfo(Path.Combine(fits, "shaderpacks", "Complementary.zip")).Length);
    }

    [Fact]
    public void CancellingAPackCopyLeavesNothingBehind()
    {
        // N92-1: Cancel on the launch screen stops the copy between files: no game folder, no half-made temp copy.
        var root = OldLayout("1.21.1");
        InstanceMigration.MigrateVersion(root, Steve);
        var src = Path.Combine(root, "accounts", Steve);
        Write($"1.21.1/accounts/{Steve}/shaderpacks/Complementary.zip", "shaders");
        Write($"1.21.1/accounts/{Steve}/resourcepacks/Faithful.zip", "faithful");

        using var cts = new CancellationTokenSource();
        var target = Path.Combine(root, "accounts", Alex);
        Assert.ThrowsAny<OperationCanceledException>(() =>
            AccountSeed.Seed(target, src, stage: _ => cts.Cancel(), freeBytes: _ => null, cancel: cts.Token));
        Assert.False(Directory.Exists(target));
        Assert.DoesNotContain(Directory.EnumerateDirectories(Path.Combine(root, "accounts")),
            d => Path.GetFileName(d).StartsWith(".seeding-", StringComparison.Ordinal));

        // The next Play seeds normally.
        Assert.True(AccountSeed.Seed(target, src, freeBytes: _ => null));
        Assert.Equal("shaders", Read($"1.21.1/accounts/{Alex}/shaderpacks/Complementary.zip"));
    }

    [Fact]
    public void AFolderMadeWhileThePacksCopyWinsAndTheCopyIsDropped()
    {
        // N92-1: the copy runs outside the folder lock; only the final exists-check and rename take it.
        var root = OldLayout("1.21.1");
        InstanceMigration.MigrateVersion(root, Steve);
        var src = Path.Combine(root, "accounts", Steve);
        Write($"1.21.1/accounts/{Steve}/shaderpacks/Complementary.zip", "shaders");

        var gate = new object();
        var target = Path.Combine(root, "accounts", Alex);
        var lockFreeDuringCopy = false;
        Assert.False(AccountSeed.Seed(target, src, freeBytes: _ => null, commitLock: gate, stage: _ =>
        {
            // Another thread can take the lock while the packs copy (an account switch on the UI thread).
            lockFreeDuringCopy = Task.Run(() => { lock (gate) return true; }).Wait(TimeSpan.FromSeconds(5));
            Directory.CreateDirectory(target);
            File.WriteAllText(Path.Combine(target, "options.txt"), "made meanwhile");
        }));
        Assert.True(lockFreeDuringCopy);
        Assert.Equal("made meanwhile", Read($"1.21.1/accounts/{Alex}/options.txt"));
        Assert.False(Exists($"1.21.1/accounts/{Alex}/shaderpacks"));
        Assert.DoesNotContain(Directory.EnumerateDirectories(Path.Combine(root, "accounts")),
            d => Path.GetFileName(d).StartsWith(".seeding-", StringComparison.Ordinal));
    }

    [Fact]
    public void SeedingNeverTouchesAnExistingFolder()
    {
        var target = Path.Combine(_root, "1.21.1", "accounts", Alex);
        Write($"1.21.1/accounts/{Alex}/options.txt", "mine");
        Write($"1.21.1/accounts/{Steve}/options.txt", "theirs");

        Assert.False(AccountSeed.Seed(target, Path.Combine(_root, "1.21.1", "accounts", Steve)));
        Assert.Equal("mine", Read($"1.21.1/accounts/{Alex}/options.txt"));
    }

    [Fact]
    public void WithNothingToSeedFromTheFolderStartsEmpty()
    {
        var target = Path.Combine(_root, "1.20.4", "accounts", Alex);
        Assert.True(AccountSeed.Seed(target, null));
        Assert.True(Directory.Exists(target));
        Assert.Empty(Directory.EnumerateFileSystemEntries(target));
    }

    [Fact]
    public void AnAbandonedSeedCopyIsCleanedUp()
    {
        Write($"1.21.1/accounts/.seeding-{Alex}-dead/options.txt", "half");
        var target = Path.Combine(_root, "1.21.1", "accounts", Alex);
        Assert.True(AccountSeed.Seed(target, null));
        Assert.False(Exists($"1.21.1/accounts/.seeding-{Alex}-dead"));
    }

    [Fact]
    public void TheSeedSourceIsTheAccountWithTheNewestSettings()
    {
        var older = Path.GetDirectoryName(Write("a/options.txt", "old"))!;
        var newer = Path.GetDirectoryName(Write("b/options.txt", "new"))!;
        var bare = Path.Combine(_root, "c");
        Directory.CreateDirectory(bare);
        File.SetLastWriteTimeUtc(Path.Combine(older, "options.txt"), DateTime.UtcNow.AddHours(-2));
        File.SetLastWriteTimeUtc(Path.Combine(newer, "options.txt"), DateTime.UtcNow.AddHours(-1));

        Assert.Equal(newer, AccountSeed.PickSource(new[] { older, bare, newer }));
        Assert.Null(AccountSeed.PickSource(new[] { bare }));
        Assert.Null(AccountSeed.PickSource(Array.Empty<string>()));
    }

    // ------------------------------------------------- launch state machine

    [Fact]
    public void AnAccountStartsIdleAndPlayable()
    {
        var book = new AccountLaunchBook();
        Assert.Equal(GamePhase.Idle, book.PhaseOf(Steve));
        Assert.Equal(PlayAction.Launch, AccountLaunchBook.ActionFor(book.PhaseOf(Steve)));
        Assert.Null(book.GameOf(Steve));
        Assert.Null(book.VersionOf(Steve));
    }

    [Fact]
    public void PreparingOneAccountNeverCancelsAnother()
    {
        var book = new AccountLaunchBook();
        var steve = book.BeginPrepare(Steve, "1.21.1")!;
        var alex = book.BeginPrepare(Alex, "1.20.1")!;

        Assert.False(steve.Token.IsCancellationRequested);
        Assert.False(alex.Token.IsCancellationRequested);
        Assert.True(book.IsCurrent(steve));
        Assert.True(book.IsCurrent(alex));
        Assert.Equal(GamePhase.Preparing, book.PhaseOf(Steve));
        Assert.Equal(GamePhase.Preparing, book.PhaseOf(Alex));
    }

    [Fact]
    public void TheNewestLaunchForTheSameAccountCancelsItsEarlierOne()
    {
        var book = new AccountLaunchBook();
        var first = book.BeginPrepare(Steve, "1.21.1")!;
        var second = book.BeginPrepare(Steve, "1.20.1")!;

        Assert.True(first.Token.IsCancellationRequested);
        Assert.False(second.Token.IsCancellationRequested);
        Assert.False(book.IsCurrent(first));

        // The cancelled launch finishing late must not reset the newer one.
        Assert.False(book.EndPrepare(first));
        Assert.Equal(GamePhase.Preparing, book.PhaseOf(Steve));
        Assert.Equal("1.20.1", book.VersionOf(Steve));
        Assert.False(book.GameStarted(first, new object()));
    }

    [Fact]
    public void AFailedOrCancelledPreparationReturnsTheAccountToPlay()
    {
        var book = new AccountLaunchBook();
        var ticket = book.BeginPrepare(Steve, "1.21.1")!;
        Assert.True(book.CancelPreparation(Steve));
        Assert.True(ticket.Token.IsCancellationRequested);

        Assert.True(book.EndPrepare(ticket));
        Assert.Equal(GamePhase.Idle, book.PhaseOf(Steve));
        Assert.False(book.CancelPreparation(Steve));
    }

    [Fact]
    public void OneGamePerAccountButAnotherAccountCanStillPlay()
    {
        var book = new AccountLaunchBook();
        var game = new object();
        var ticket = book.BeginPrepare(Steve, "1.21.1")!;
        Assert.True(book.GameStarted(ticket, game));
        Assert.Equal(GamePhase.Starting, book.PhaseOf(Steve));
        Assert.Same(game, book.GameOf(Steve));

        Assert.Null(book.BeginPrepare(Steve, "1.21.1"));       // its game is starting
        Assert.True(book.WindowShown(Steve, game));
        Assert.Equal(GamePhase.Running, book.PhaseOf(Steve));
        Assert.Null(book.BeginPrepare(Steve, "1.20.1"));       // and now open
        Assert.Equal(PlayAction.FocusGame, AccountLaunchBook.ActionFor(book.PhaseOf(Steve)));

        Assert.NotNull(book.BeginPrepare(Alex, "1.21.1"));     // a second window, other account
        Assert.Equal(GamePhase.Running, book.PhaseOf(Steve));  // untouched
        Assert.Equal(new[] { Steve }, book.PlayingKeys());
    }

    [Fact]
    public void WhenTheGameExitsTheAccountCanPlayAgain()
    {
        var book = new AccountLaunchBook();
        var game = new object();
        book.GameStarted(book.BeginPrepare(Steve, "1.21.1")!, game);
        book.WindowShown(Steve, game);

        Assert.False(book.Exited(Steve, new object()));  // some other process
        Assert.False(book.Exited(Alex, game));
        Assert.Equal(GamePhase.Running, book.PhaseOf(Steve));

        Assert.True(book.Exited(Steve, game));
        Assert.Equal(GamePhase.Idle, book.PhaseOf(Steve));
        Assert.Null(book.GameOf(Steve));
        Assert.False(book.Exited(Steve, game));          // a second report changes nothing
        Assert.NotNull(book.BeginPrepare(Steve, "1.21.1"));
    }

    [Fact]
    public void ABootCrashGoesStraightBackToIdle()
    {
        var book = new AccountLaunchBook();
        var game = new object();
        book.GameStarted(book.BeginPrepare(Steve, "1.21.1")!, game);
        Assert.True(book.Exited(Steve, game));
        Assert.False(book.WindowShown(Steve, game));      // a late window check is ignored
        Assert.Equal(GamePhase.Idle, book.PhaseOf(Steve));
    }

    [Fact]
    public void NotesAreKeptPerAccount()
    {
        var book = new AccountLaunchBook();
        book.SetNote(Steve, new LaunchNote("Running · Steve", LaunchTone.Success));
        book.SetNote(Alex, new LaunchNote("Session expired", LaunchTone.Error));
        Assert.Equal("Running · Steve", book.NoteOf(Steve)!.Text);
        Assert.Equal(LaunchTone.Error, book.NoteOf(Alex)!.Tone);
        Assert.Null(book.NoteOf("offline"));

        book.BeginPrepare(Alex, "1.21.1"); // a new launch starts with a clean line
        Assert.Null(book.NoteOf(Alex));
    }

    // ------------------------------------------------------------- the Play button

    [Fact]
    public void ThePlayButtonReadsRunningAndStaysClickableWhileTheGameIsOpen()
    {
        var view = AccountLaunchBook.ButtonFor(GamePhase.Running, hasVersion: true);
        Assert.True(view.Enabled);
        Assert.True(view.ShowsRunning);
        Assert.False(view.Spinner);
    }

    [Theory]
    [InlineData(GamePhase.Preparing)]
    [InlineData(GamePhase.Starting)]
    public void ThePlayButtonSpinsWhileALaunchIsUnderWay(GamePhase phase)
    {
        var view = AccountLaunchBook.ButtonFor(phase, hasVersion: true);
        Assert.False(view.Enabled);
        Assert.True(view.Spinner);
        Assert.False(view.ShowsRunning);
        Assert.Equal(PlayAction.None, AccountLaunchBook.ActionFor(phase));
    }

    [Theory]
    [InlineData(true, true)]
    [InlineData(false, false)]
    public void AnIdleAccountsPlayButton(bool hasVersion, bool enabled)
    {
        var view = AccountLaunchBook.ButtonFor(GamePhase.Idle, hasVersion);
        Assert.Equal(enabled, view.Enabled);
        Assert.False(view.ShowsRunning);
        Assert.False(view.Spinner);
    }

    [Theory]
    [InlineData(false, false, true)]  // signed out: Play opens the sign-in card (JL-7), never a dead grey button
    [InlineData(true, false, false)]
    [InlineData(false, true, false)]  // offline test mode (dev builds) plays without an account
    [InlineData(true, true, false)]
    public void SignedOutPlayOpensSignIn(bool hasAccount, bool offlineTest, bool opens) =>
        Assert.Equal(opens, AccountLaunchBook.SignedOutPlayOpensSignIn(hasAccount, offlineTest));

    [Fact]
    public void ACancelledLaunchForgetsOnlyItsOwnPacksNotice()
    {
        // A cancelled first Play must not delete the newer launch's "packs weren't copied" sentence for the same
        // folder, even when the two sentences are the same words.
        var notices = new SeedNotices();
        var dir = Path.Combine(_root, "1.21.1", "accounts", Alex);
        const string text = "Your resource and shader packs weren't copied to this account (more than 4 GB).";
        var older = notices.Set(dir, text);
        notices.Set(dir, text); // the newer launch
        notices.Forget(dir, older);
        Assert.Equal(text, notices.Take(dir));
        Assert.Null(notices.Take(dir)); // once

        var own = notices.Set(dir, text);
        notices.Forget(dir, own);
        Assert.Null(notices.Take(dir));
        notices.Forget(dir, null); // a launch that set nothing
    }
}
