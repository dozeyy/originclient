using OriginLauncher.App.Core.GameFolders;
using Xunit;

namespace OriginLauncher.Tests;

// Per-account game folders (several Minecraft windows at once, one per
// account): the folder layout, the one-time migration from the old
// one-folder-per-version layout, seeding a new account's folder, and the
// per-account launch state machine behind the Play button. Folder tests run on
// real files in a temp directory.
public class GameFoldersTests
{
    private const string Steve = "069a79f444e94726a5befca90e38aaf5";
    private const string Alex = "853c80ef3c3749fdaa49938b674adae6";

    private readonly string _root = Directory.CreateTempSubdirectory("game-folders").FullName;

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

    [Fact]
    public void TheMarkerRemembersWhichAccountInheritsTheOldFolders()
    {
        var marker = Path.Combine(_root, "account-migration.json");
        Assert.Null(InstanceMigration.ReadMarker(marker));

        InstanceMigration.WriteMarker(marker, Steve);
        Assert.Equal(Steve, InstanceMigration.ReadMarker(marker));
        Assert.Empty(Directory.EnumerateFiles(_root, "*.tmp"));
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
        var view = AccountLaunchBook.ButtonFor(GamePhase.Running, canPlay: true, hasVersion: true);
        Assert.True(view.Enabled);
        Assert.True(view.ShowsRunning);
        Assert.False(view.Spinner);
        Assert.NotNull(view.ToolTip);
    }

    [Theory]
    [InlineData(GamePhase.Preparing)]
    [InlineData(GamePhase.Starting)]
    public void ThePlayButtonSpinsWhileALaunchIsUnderWay(GamePhase phase)
    {
        var view = AccountLaunchBook.ButtonFor(phase, canPlay: true, hasVersion: true);
        Assert.False(view.Enabled);
        Assert.True(view.Spinner);
        Assert.False(view.ShowsRunning);
        Assert.Equal(PlayAction.None, AccountLaunchBook.ActionFor(phase));
    }

    [Theory]
    [InlineData(true, true, true, null)]
    [InlineData(true, false, false, "Select a version to play")]
    [InlineData(false, true, false, "Sign in to play")]
    [InlineData(false, false, false, "Sign in to play")]
    public void AnIdleAccountsPlayButton(bool canPlay, bool hasVersion, bool enabled, string? toolTip)
    {
        var view = AccountLaunchBook.ButtonFor(GamePhase.Idle, canPlay, hasVersion);
        Assert.Equal(enabled, view.Enabled);
        Assert.Equal(toolTip, view.ToolTip);
        Assert.False(view.ShowsRunning);
        Assert.False(view.Spinner);
    }
}
