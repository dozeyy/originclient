using OriginLauncher.App.Core.GameFolders;
using Xunit;

namespace OriginLauncher.Tests;

// One mods folder per version, shared by every account (Will, 2026-10-12):
// where it lives, the loader check behind fabric.modsFolder, and the merge of
// each account's old mods folder into it (union, duplicates dropped, the newer
// file wins a name clash and the other is parked as <name>.before-shared).
// Folder tests run on real files in a temp directory.
public class SharedModsTests
{
    private const string Steve = "069a79f444e94726a5befca90e38aaf5";
    private const string Alex = "853c80ef3c3749fdaa49938b674adae6";
    private static readonly DateTime Monday = new(2026, 10, 5, 12, 0, 0, DateTimeKind.Utc);
    private static readonly DateTime Friday = new(2026, 10, 9, 12, 0, 0, DateTimeKind.Utc);

    private readonly string _root = Directory.CreateTempSubdirectory("shared-mods").FullName;

    private string P(string relativePath) =>
        Path.Combine(_root, relativePath.Replace('/', Path.DirectorySeparatorChar));

    private string Write(string relativePath, string text, DateTime? time = null)
    {
        var path = P(relativePath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, text);
        if (time is { } t) File.SetLastWriteTimeUtc(path, t);
        return path;
    }

    private string Read(string relativePath) => File.ReadAllText(P(relativePath));

    private bool Exists(string relativePath) => File.Exists(P(relativePath)) || Directory.Exists(P(relativePath));

    private string Root(string version) => P(version);

    private static readonly Func<string, bool> NothingRunning = _ => false;

    private List<string> Tree(string version) =>
        Directory.EnumerateFileSystemEntries(Root(version), "*", SearchOption.AllDirectories)
            .Select(p => Path.GetRelativePath(Root(version), p).Replace('\\', '/'))
            .OrderBy(p => p, StringComparer.Ordinal)
            .ToList();

    // ------------------------------------------------------------------ layout

    [Fact]
    public void TheModsFolderIsInTheVersionRootNotTheAccount()
    {
        Assert.Equal(Path.Combine("I", "1.21.1", "mods"), InstanceLayout.ModsDir("I", "1.21.1"));
        Assert.True(InstanceLayout.IsSharedEntry("mods"));
        Assert.True(InstanceLayout.IsSharedEntry("MODS"));
        // The Origin-only copy is rebuilt per account, never shared.
        Assert.False(InstanceLayout.IsSharedEntry("mods-origin-only"));
    }

    [Fact]
    public void AnAccountGameFolderFindsItsVersionsSharedMods()
    {
        var game = InstanceLayout.GameDir("I", "1.20.4", Steve);
        Assert.Equal(InstanceLayout.ModsDir("I", "1.20.4"), InstanceLayout.ModsDirForGameDir(game));
        Assert.Equal(InstanceLayout.ModsDir("I", "1.20.4"),
            InstanceLayout.ModsDirForGameDir(game + Path.DirectorySeparatorChar));
    }

    // ----------------------------------------------------- fabric.modsFolder

    [Theory]
    [InlineData("fabric-loader-0.16.14-1.21.1", true)]
    [InlineData("fabric-loader-0.17.2-1.16.5", true)]
    [InlineData("fabric-loader-0.15.0-1.20.1", true)]
    [InlineData("fabric-loader-0.14.24-1.20.1", false)]
    [InlineData("fabric-loader-0.10.0+build.208-1.16.5", false)]
    [InlineData("1.21.1", false)]
    [InlineData("", false)]
    [InlineData(null, false)]
    public void OnlyALoaderThatReadsTheModsFolderPropertyIsReused(string? versionName, bool reads)
    {
        Assert.Equal(reads, SharedMods.LoaderReadsModsFolderProperty(versionName));
    }

    [Fact]
    public void OfflineThePickIsTheNewestInstalledLoaderForThatVersionThatReadsTheModsFolder()
    {
        var names = new[]
        {
            "1.21.1", "fabric-loader-0.14.24-1.21.1", "fabric-loader-0.16.9-1.21.1",
            "fabric-loader-0.16.14-1.21.1", "fabric-loader-0.17.2-1.21", "fabric-loader-0.17.3-1.20.1",
        };
        Assert.Equal("fabric-loader-0.16.14-1.21.1", SharedMods.NewestInstalledLoader(names, "1.21.1"));
        Assert.Equal("fabric-loader-0.17.2-1.21", SharedMods.NewestInstalledLoader(names, "1.21"));
        Assert.Null(SharedMods.NewestInstalledLoader(new[] { "1.21.1", "fabric-loader-0.14.24-1.21.1" }, "1.21.1"));
        Assert.Null(SharedMods.NewestInstalledLoader(names, "1.16.5"));
    }

    [Fact]
    public void TheJvmArgumentIsFabricsOwnProperty()
    {
        Assert.Equal(@"-Dfabric.modsFolder=C:\Users\Will\mods", SharedMods.JvmArgument(@"C:\Users\Will\mods"));
    }

    // ------------------------------------------------------------- the rules

    private static readonly ModMergeEntry FileMonday = new(false, Monday);
    private static readonly ModMergeEntry FileFriday = new(false, Friday);
    private static readonly ModMergeEntry Folder = new(true, Monday);
    private static bool NeverAsked() => throw new InvalidOperationException("bytes compared needlessly");

    [Fact]
    public void ANameTheSharedFolderLacksMovesIn()
    {
        Assert.Equal(ModMergeStep.Move, SharedMods.Decide(FileMonday, null, NeverAsked));
        Assert.Equal(ModMergeStep.Move, SharedMods.Decide(Folder, null, NeverAsked));
    }

    [Fact]
    public void TwoFoldersMerge()
    {
        Assert.Equal(ModMergeStep.MergeFolders, SharedMods.Decide(Folder, Folder, NeverAsked));
    }

    [Fact]
    public void AFileAgainstAFolderKeepsTheSharedOne()
    {
        Assert.Equal(ModMergeStep.KeepShared, SharedMods.Decide(FileFriday, Folder, NeverAsked));
        Assert.Equal(ModMergeStep.KeepShared, SharedMods.Decide(Folder, FileFriday, NeverAsked));
    }

    [Fact]
    public void IdenticalBytesAreADuplicateWhateverTheirAge()
    {
        Assert.Equal(ModMergeStep.DropDuplicate, SharedMods.Decide(FileFriday, FileMonday, () => true));
        Assert.Equal(ModMergeStep.DropDuplicate, SharedMods.Decide(FileMonday, FileFriday, () => true));
    }

    [Fact]
    public void ADifferentFileTheNewerOneWins()
    {
        Assert.Equal(ModMergeStep.TakeAccounts, SharedMods.Decide(FileFriday, FileMonday, () => false));
        Assert.Equal(ModMergeStep.KeepShared, SharedMods.Decide(FileMonday, FileFriday, () => false));
        // Same age: the shared copy stays (stable across passes).
        Assert.Equal(ModMergeStep.KeepShared, SharedMods.Decide(FileMonday, FileMonday, () => false));
    }

    [Fact]
    public void AnEnabledModBeatsItsDisabledTwin()
    {
        var twins = SharedMods.DisabledTwins(new[]
        {
            "sodium-extra.jar", "sodium-extra.jar.disabled",  // on for one account, off for another
            "zoomify.jar.disabled",                           // off everywhere: stays off
            "Tweaks.JAR", "tweaks.jar.disabled",              // case-insensitive (Windows)
            "notes.txt",
        });
        Assert.Equal(new[] { "sodium-extra.jar.disabled", "tweaks.jar.disabled" }, twins);
    }

    // --------------------------------------------------------------- the merge

    [Fact]
    public void EveryAccountsModsJoinTheSharedFolder()
    {
        Write("1.21.1/mods/originclient.jar", "origin");
        Write($"1.21.1/accounts/{Steve}/mods/originclient.jar", "origin");     // same bytes
        Write($"1.21.1/accounts/{Steve}/mods/steves-mod.jar", "steve");
        Write($"1.21.1/accounts/{Alex}/mods/alexs-mod.jar.disabled", "alex");
        Write($"1.21.1/accounts/{Alex}/mods/sub/data.json", "{}");             // some mods keep folders here
        Write($"1.21.1/accounts/{Alex}/options.txt", "fov:1.0");               // settings stay put

        Assert.True(SharedMods.MergeVersion(Root("1.21.1"), NothingRunning));

        Assert.Equal("origin", Read("1.21.1/mods/originclient.jar"));
        Assert.Equal("steve", Read("1.21.1/mods/steves-mod.jar"));
        Assert.Equal("alex", Read("1.21.1/mods/alexs-mod.jar.disabled"));
        Assert.Equal("{}", Read("1.21.1/mods/sub/data.json"));
        Assert.Equal("fov:1.0", Read($"1.21.1/accounts/{Alex}/options.txt"));
        // The accounts' own mods folders are gone (emptied, nothing deleted that
        // isn't byte-for-byte in the shared folder), and nothing was parked.
        Assert.False(Exists($"1.21.1/accounts/{Steve}/mods"));
        Assert.False(Exists($"1.21.1/accounts/{Alex}/mods"));
        Assert.DoesNotContain(Tree("1.21.1"), p => p.Contains(SharedMods.ParkSuffix));
        Assert.False(SharedMods.NeedsMerge(Root("1.21.1")));
    }

    [Fact]
    public void ASameNameClashKeepsTheNewerAndParksTheOther()
    {
        Write("1.21.1/mods/cool-mod.jar", "monday build", Monday);
        Write($"1.21.1/accounts/{Steve}/mods/cool-mod.jar", "friday build", Friday);
        Write("1.21.1/mods/other.jar", "friday other", Friday);
        Write($"1.21.1/accounts/{Alex}/mods/other.jar", "monday other", Monday);

        Assert.True(SharedMods.MergeVersion(Root("1.21.1"), NothingRunning));

        Assert.Equal("friday build", Read("1.21.1/mods/cool-mod.jar"));
        Assert.Equal("monday build", Read("1.21.1/mods/cool-mod.jar.before-shared"));
        Assert.Equal("friday other", Read("1.21.1/mods/other.jar"));
        Assert.Equal("monday other", Read("1.21.1/mods/other.jar.before-shared"));
    }

    [Fact]
    public void ThreeVersionsOfOneJarKeepTheNewestAndParkBoth()
    {
        Write("1.20.4/mods/cool-mod.jar", "sunday", Monday.AddDays(-1));
        Write($"1.20.4/accounts/{Steve}/mods/cool-mod.jar", "monday", Monday);
        Write($"1.20.4/accounts/{Alex}/mods/cool-mod.jar", "friday", Friday);

        Assert.True(SharedMods.MergeVersion(Root("1.20.4"), NothingRunning));

        Assert.Equal("friday", Read("1.20.4/mods/cool-mod.jar"));
        var parked = Directory.EnumerateFiles(P("1.20.4/mods"), "cool-mod.jar.before-shared*")
            .Select(File.ReadAllText).OrderBy(t => t).ToList();
        Assert.Equal(new[] { "monday", "sunday" }, parked);
    }

    [Fact]
    public void OnForOneAccountOffForAnotherEndsUpOn()
    {
        Write($"1.21.1/accounts/{Steve}/mods/zoom.jar", "zoom");
        Write($"1.21.1/accounts/{Alex}/mods/zoom.jar.disabled", "zoom");
        Write($"1.21.1/accounts/{Steve}/mods/map.jar", "map v2");
        Write($"1.21.1/accounts/{Alex}/mods/map.jar.disabled", "map v1");

        Assert.True(SharedMods.MergeVersion(Root("1.21.1"), NothingRunning));

        Assert.Equal("zoom", Read("1.21.1/mods/zoom.jar"));
        Assert.False(Exists("1.21.1/mods/zoom.jar.disabled"));          // same jar: just the duplicate
        Assert.Equal("map v2", Read("1.21.1/mods/map.jar"));
        Assert.False(Exists("1.21.1/mods/map.jar.disabled"));
        Assert.Equal("map v1", Read("1.21.1/mods/map.jar.disabled.before-shared")); // kept, out of the way
    }

    [Fact]
    public void TheMergeIsIdempotent()
    {
        Write("1.21.1/mods/a.jar", "shared a", Monday);
        Write($"1.21.1/accounts/{Steve}/mods/a.jar", "steve a", Friday);
        Write($"1.21.1/accounts/{Alex}/mods/b.jar", "alex b");
        Assert.True(SharedMods.MergeVersion(Root("1.21.1"), NothingRunning));
        var before = Tree("1.21.1");

        Assert.True(SharedMods.MergeVersion(Root("1.21.1"), NothingRunning));
        Assert.True(SharedMods.MergeVersion(Root("1.21.1"), NothingRunning));

        Assert.Equal(before, Tree("1.21.1"));
    }

    [Fact]
    public void AMergeInterruptedBetweenItsTwoRenamesFinishesNextPass()
    {
        // A crash after parking the shared copy, before moving the newer one in.
        Write("1.21.1/mods/a.jar.before-shared", "shared a", Monday);
        Write($"1.21.1/accounts/{Steve}/mods/a.jar", "steve a", Friday);

        Assert.True(SharedMods.MergeVersion(Root("1.21.1"), NothingRunning));

        Assert.Equal("steve a", Read("1.21.1/mods/a.jar"));
        Assert.Equal("shared a", Read("1.21.1/mods/a.jar.before-shared"));
        Assert.False(Exists($"1.21.1/accounts/{Steve}/mods"));
    }

    [Fact]
    public void AnAccountWhoseGameIsOpenWaitsForItToClose()
    {
        Write($"1.21.1/accounts/{Steve}/mods/steve.jar", "steve");
        Write($"1.21.1/accounts/{Alex}/mods/alex.jar", "alex");
        var steveGame = P($"1.21.1/accounts/{Steve}");

        Assert.False(SharedMods.MergeVersion(Root("1.21.1"), dir => dir == steveGame));
        Assert.Equal("steve", Read($"1.21.1/accounts/{Steve}/mods/steve.jar")); // untouched while playing
        Assert.Equal("alex", Read("1.21.1/mods/alex.jar"));
        Assert.True(SharedMods.NeedsMerge(Root("1.21.1")));

        Assert.True(SharedMods.MergeVersion(Root("1.21.1"), NothingRunning));
        Assert.Equal("steve", Read("1.21.1/mods/steve.jar"));
        Assert.False(SharedMods.NeedsMerge(Root("1.21.1")));
    }

    [Fact]
    public void SeedingAndTemporaryFoldersAreNotAccounts()
    {
        Write($"1.21.1/accounts/.seeding-{Alex}-0123/mods/x.jar", "half a seed");
        Assert.True(SharedMods.MergeVersion(Root("1.21.1"), NothingRunning));
        Assert.True(Exists($"1.21.1/accounts/.seeding-{Alex}-0123/mods/x.jar"));
        Assert.False(Exists("1.21.1/mods/x.jar"));
    }

    [Fact]
    public void NothingToMergeIsANoOp()
    {
        Assert.True(SharedMods.MergeVersion(Root("1.19.4"), NothingRunning));
        Assert.False(Exists("1.19.4"));
    }

    [Fact]
    public void SameBytesComparesContentNotJustSize()
    {
        var a = Write("a.jar", "abcd");
        var b = Write("b.jar", "abce");
        var c = Write("c.jar", "abcd");
        Assert.False(SharedMods.SameBytes(a, b));
        Assert.True(SharedMods.SameBytes(a, c));
        Assert.False(SharedMods.SameBytes(a, P("missing.jar")));
    }

    // ------------------------------------------- the 1.8.9 (Forge) mods mirror

    private string Mirror(string account = Steve) => P($"1.8.9/accounts/{account}/mods");

    private bool MirrorShared(Func<string, bool>? include = null, string account = Steve) =>
        SharedMods.Mirror(P("1.8.9/mods"), Mirror(account), include);

    [Fact]
    public void TheMirrorCopiesEveryLoadableSharedFile()
    {
        Write("1.8.9/mods/originclient.jar", "origin", Monday);
        Write("1.8.9/mods/optifine.jar", "optifine", Monday);
        Write("1.8.9/mods/old-mod.zip", "zip mod", Monday);
        Write("1.8.9/mods/off.jar.disabled", "off");             // turned off on the Mods page
        Write("1.8.9/mods/notes.txt", "not a mod");
        Write("1.8.9/mods/1.8.9/nested.jar", "sub-folders stay out");

        Assert.True(MirrorShared());

        Assert.Equal("origin", Read($"1.8.9/accounts/{Steve}/mods/originclient.jar"));
        Assert.Equal("optifine", Read($"1.8.9/accounts/{Steve}/mods/optifine.jar"));
        Assert.Equal("zip mod", Read($"1.8.9/accounts/{Steve}/mods/old-mod.zip"));
        Assert.False(Exists($"1.8.9/accounts/{Steve}/mods/off.jar.disabled"));
        Assert.False(Exists($"1.8.9/accounts/{Steve}/mods/notes.txt"));
        Assert.False(Exists($"1.8.9/accounts/{Steve}/mods/1.8.9"));
        // The shared folder is never changed by mirroring it.
        Assert.Equal("off", Read("1.8.9/mods/off.jar.disabled"));
    }

    [Fact]
    public void TheMirrorFollowsChangesAndRemovalsInTheSharedFolder()
    {
        Write("1.8.9/mods/originclient.jar", "origin v1", Monday);
        Write("1.8.9/mods/cool-mod.jar", "cool", Monday);
        Assert.True(MirrorShared());

        Write("1.8.9/mods/originclient.jar", "origin v2", Friday); // a launcher update
        File.Move(P("1.8.9/mods/cool-mod.jar"), P("1.8.9/mods/cool-mod.jar.disabled")); // turned off
        Assert.True(MirrorShared());

        Assert.Equal("origin v2", Read($"1.8.9/accounts/{Steve}/mods/originclient.jar"));
        Assert.False(Exists($"1.8.9/accounts/{Steve}/mods/cool-mod.jar"));
        Assert.Equal("cool", Read("1.8.9/mods/cool-mod.jar.disabled"));
    }

    [Fact]
    public void AJarThePlayerDropsIntoTheMirrorJoinsTheSharedFolder()
    {
        Write("1.8.9/mods/originclient.jar", "origin", Monday);
        Assert.True(MirrorShared());
        Write($"1.8.9/accounts/{Steve}/mods/keystrokes.jar", "keystrokes");

        Assert.True(MirrorShared());

        Assert.Equal("keystrokes", Read("1.8.9/mods/keystrokes.jar"));
        Assert.Equal("keystrokes", Read($"1.8.9/accounts/{Steve}/mods/keystrokes.jar"));
        // ...and from there it reaches every account's mirror.
        Assert.True(MirrorShared(account: Alex));
        Assert.Equal("keystrokes", Read($"1.8.9/accounts/{Alex}/mods/keystrokes.jar"));
    }

    [Fact]
    public void OriginOnlyMirrorsJustTheProvisionedJarsAndLosesNothing()
    {
        Write("1.8.9/mods/originclient.jar", "origin", Monday);
        Write("1.8.9/mods/keystrokes.jar", "keystrokes", Monday);
        Assert.True(MirrorShared());

        Assert.True(MirrorShared(name => name.StartsWith("originclient", StringComparison.Ordinal)));

        Assert.True(Exists($"1.8.9/accounts/{Steve}/mods/originclient.jar"));
        Assert.False(Exists($"1.8.9/accounts/{Steve}/mods/keystrokes.jar"));
        Assert.Equal("keystrokes", Read("1.8.9/mods/keystrokes.jar"));

        Assert.True(MirrorShared()); // external mods back on
        Assert.Equal("keystrokes", Read($"1.8.9/accounts/{Steve}/mods/keystrokes.jar"));
    }

    [Fact]
    public void TheMergeLeavesAMirrorAlone()
    {
        Write("1.8.9/mods/originclient.jar", "origin", Monday);
        Assert.True(MirrorShared());

        Assert.False(SharedMods.NeedsMerge(Root("1.8.9")));
        Assert.True(SharedMods.MergeVersion(Root("1.8.9"), NothingRunning));
        Assert.Equal("origin", Read($"1.8.9/accounts/{Steve}/mods/originclient.jar"));
    }
}
