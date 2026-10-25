using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Versions;
using Xunit;

namespace OriginLauncher.Tests;

// InstalledVersions over temp folders only. The roots are handed in through its constructor (the same seam
// OriginPaths.VersionRoot/ModsDir fill in the launcher) rather than OriginPaths.UseScratchRoot, which can be
// set once per process and would leak between parallel test classes. Never touches %LocalAppData%.
public class InstalledVersionsTests : IDisposable
{
    private const string Fabric = "1.21.1";
    private const string Forge = "1.8.9";
    private const string ForgeId = "1.8.9-forge1.8.9-11.15.1.2318-1.8.9";
    private const string ForgeLib = "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9.jar";

    private readonly string _tmp = Directory.CreateTempSubdirectory("installed-versions").FullName;
    private string Instances => Path.Combine(_tmp, "instances");
    private string Bundled => Path.Combine(_tmp, "bundled");

    public void Dispose()
    {
        try
        {
            if (!OperatingSystem.IsWindows())
                foreach (var d in Directory.EnumerateDirectories(_tmp, "*", SearchOption.AllDirectories))
                    File.SetUnixFileMode(d, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            Directory.Delete(_tmp, true);
        }
        catch { /* best effort */ }
    }

    private string Root(string version) => Path.Combine(Instances, version);
    private string BundledJar(string version) => Path.Combine(Bundled, "originclient-" + version + ".jar");
    private static string ForgeLibPath => ForgeLib.Replace('/', Path.DirectorySeparatorChar);

    private InstalledVersions Make(
        Func<string, string?>? bundled = null,
        Func<string, string, bool>? stamp = null,
        Action<string>? listable = null) => new(
        [Fabric, Forge, "26.3"],
        Root,
        v => Path.Combine(Root(v), "mods"),
        bundled ?? (v => BundledJar(v)),
        v => v == Forge ? new LegacyLoaderLayout(ForgeId, ForgeLibPath) : null,
        stamp,
        listable);

    private static string Put(string path, int bytes = 8)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[bytes]);
        return path;
    }

    private void Client(string version)
    {
        Put(Path.Combine(Root(version), "versions", version, version + ".json"));
        Put(Path.Combine(Root(version), "versions", version, version + ".jar"), 100);
    }

    private void FabricLoader(string version, string loader = "0.16.14")
    {
        var name = $"fabric-loader-{loader}-{version}";
        Put(Path.Combine(Root(version), "versions", name, name + ".json"));
    }

    private void ForgeLoader()
    {
        Put(Path.Combine(Root(Forge), "versions", ForgeId, ForgeId + ".json"));
        Put(Path.Combine(Root(Forge), "libraries", ForgeLibPath), 50);
    }

    // The bundled jar, and the installed mods/originclient.jar the way Play leaves it (same bytes and timestamp).
    private void OriginJar(string version, int bundledBytes = 300, int installedBytes = 300, int mtimeOffsetSeconds = 0)
    {
        var bundled = Put(BundledJar(version), bundledBytes);
        var stamp = new DateTime(2026, 10, 1, 12, 0, 0, DateTimeKind.Utc);
        File.SetLastWriteTimeUtc(bundled, stamp);
        var installed = Put(Path.Combine(Root(version), "mods", InstalledVersions.OriginJarFileName), installedBytes);
        File.SetLastWriteTimeUtc(installed, stamp.AddSeconds(mtimeOffsetSeconds));
    }

    private void FullFabric()
    {
        Client(Fabric);
        FabricLoader(Fabric);
        OriginJar(Fabric);
    }

    private static List<string> Snapshot(string dir) =>
        Directory.EnumerateFileSystemEntries(dir, "*", SearchOption.AllDirectories)
            .Select(p => p + "|" + (File.Exists(p) ? new FileInfo(p).Length + "|" + File.GetLastWriteTimeUtc(p).Ticks : "dir"))
            .Order().ToList();

    [Fact]
    public void AllPiecesPresent_IsInstalled()
    {
        FullFabric();
        var info = Make().Check(Fabric);
        Assert.Equal(InstallState.Installed, info.State);
        Assert.Equal(InstallPieces.Client | InstallPieces.Loader | InstallPieces.OriginMod, info.Present);
        Assert.Equal(InstallPieces.None, info.Missing);
    }

    [Fact]
    public void NoFolder_IsNotInstalled_AndNothingIsCreated()
    {
        var info = Make().Check("26.3");
        Assert.Equal(InstallState.NotInstalled, info.State);
        Assert.False(Directory.Exists(Instances)); // read-only: checking never creates the instance folder
    }

    [Fact]
    public void FolderWithOnlyAnEmptyModsDir_IsNotInstalled()
    {
        // A launch attempt creates mods/ and config/ before anything downloads.
        Directory.CreateDirectory(Path.Combine(Root(Fabric), "mods"));
        Assert.Equal(InstallState.NotInstalled, Make().Check(Fabric).State);
    }

    [Fact]
    public void ClientWithoutLoader_IsPartial()
    {
        Client(Fabric);
        OriginJar(Fabric);
        var info = Make().Check(Fabric);
        Assert.Equal(InstallState.Partial, info.State);
        Assert.Equal(InstallPieces.Loader, info.Missing);
    }

    [Fact]
    public void MissingOriginJar_IsPartial()
    {
        Client(Fabric);
        FabricLoader(Fabric);
        Put(BundledJar(Fabric), 300);
        var info = Make().Check(Fabric);
        Assert.Equal(InstallState.Partial, info.State);
        Assert.Equal(InstallPieces.OriginMod, info.Missing);
    }

    [Fact]
    public void EmptyClientJar_CountsAsMissing()
    {
        FullFabric();
        Put(Path.Combine(Root(Fabric), "versions", Fabric, Fabric + ".jar"), 0);
        var info = Make().Check(Fabric);
        Assert.Equal(InstallState.Partial, info.State);
        Assert.Equal(InstallPieces.Client, info.Missing);
    }

    [Fact]
    public void LoaderTooOldForSharedMods_IsPartial()
    {
        // Play reinstalls a Fabric Loader older than 0.15 (no fabric.modsFolder), so it is not "installed".
        Client(Fabric);
        FabricLoader(Fabric, "0.14.24");
        OriginJar(Fabric);
        Assert.Equal(InstallPieces.Loader, Make().Check(Fabric).Missing);
    }

    [Fact]
    public void LoaderOfAnotherMinecraftVersion_DoesNotCount()
    {
        Client(Fabric);
        var name = "fabric-loader-0.16.14-1.21";
        Put(Path.Combine(Root(Fabric), "versions", name, name + ".json"));
        OriginJar(Fabric);
        Assert.Equal(InstallState.Partial, Make().Check(Fabric).State);
    }

    [Fact]
    public void DifferentSize_IsUpdateReady()
    {
        FullFabric();
        OriginJar(Fabric, bundledBytes: 400, installedBytes: 300);
        Assert.Equal(InstallState.UpdateReady, Make().Check(Fabric).State);
    }

    [Fact]
    public void SameSizeDifferentTimestamp_IsUpdateReady()
    {
        FullFabric();
        OriginJar(Fabric, mtimeOffsetSeconds: 60);
        Assert.Equal(InstallState.UpdateReady, Make().Check(Fabric).State);
    }

    [Fact]
    public void BundledJarMissingOnDisk_DevBuild_IsInstalledWithoutTheJar()
    {
        // A dev build has no Gradle output to bundle: Play could never install the jar, so it is neither
        // required nor compared (else the version would read Partial forever).
        Client(Fabric);
        FabricLoader(Fabric);
        Assert.Equal(InstallState.Installed, Make().Check(Fabric).State);
    }

    [Fact]
    public void VersionWithoutOriginBuild_NeedsNoOriginJar()
    {
        Client(Fabric);
        FabricLoader(Fabric);
        Assert.Equal(InstallState.Installed, Make(_ => null).Check(Fabric).State);
    }

    [Fact]
    public void Forge189_AllPieces_IsInstalled()
    {
        Client(Forge);
        ForgeLoader();
        OriginJar(Forge);
        var info = Make().Check(Forge);
        Assert.Equal(InstallState.Installed, info.State);
        Assert.Equal(InstallPieces.None, info.Missing);
    }

    [Fact]
    public void Forge189_MissingUniversalJar_IsPartial()
    {
        Client(Forge);
        ForgeLoader();
        OriginJar(Forge);
        File.Delete(Path.Combine(Root(Forge), "libraries", ForgeLibPath));
        var info = Make().Check(Forge);
        Assert.Equal(InstallState.Partial, info.State);
        Assert.Equal(InstallPieces.Loader, info.Missing);
    }

    [Fact]
    public void Forge189_AFabricProfileDoesNotSatisfyIt()
    {
        Client(Forge);
        FabricLoader(Forge);
        OriginJar(Forge);
        Assert.Equal(InstallPieces.Loader, Make().Check(Forge).Missing);
    }

    [Fact]
    public void Forge189_StaleOriginJar_IsUpdateReady()
    {
        Client(Forge);
        ForgeLoader();
        OriginJar(Forge, bundledBytes: 999, installedBytes: 300);
        Assert.Equal(InstallState.UpdateReady, Make().Check(Forge).State);
    }

    [Fact]
    public void VersionFolderIsAFile_IsUnknown()
    {
        Put(Root(Fabric));
        Assert.Equal(InstallState.Unknown, Make().Check(Fabric).State);
    }

    [Fact]
    public void ThrowingLookup_IsUnknown_NeverThrows()
    {
        Directory.CreateDirectory(Root(Fabric));
        var info = Make(_ => throw new IOException("boom")).Check(Fabric);
        Assert.Equal(InstallState.Unknown, info.State);
    }

    // The seam throws what a real unreadable folder throws, so this runs the same on Windows and Linux.
    [Theory]
    [InlineData("instances")]
    [InlineData("root")]
    [InlineData("versions")]
    [InlineData("mods")]
    public void UnreadableFolder_IsUnknown(string which)
    {
        FullFabric();
        var unreadable = which switch
        {
            "instances" => Instances,
            "root" => Root(Fabric),
            "versions" => Path.Combine(Root(Fabric), "versions"),
            _ => Path.Combine(Root(Fabric), "mods"),
        };
        var sut = Make(listable: dir =>
        {
            if (string.Equals(dir, unreadable, StringComparison.OrdinalIgnoreCase)) throw new UnauthorizedAccessException(dir);
        });
        Assert.Equal(InstallState.Unknown, sut.Check(Fabric).State);
    }

    [Fact]
    public void UnreadableLibraries_IsUnknown_On189()
    {
        Client(Forge);
        ForgeLoader();
        OriginJar(Forge);
        var libraries = Path.Combine(Root(Forge), "libraries");
        var sut = Make(listable: dir =>
        {
            if (string.Equals(dir, libraries, StringComparison.OrdinalIgnoreCase)) throw new IOException(dir);
        });
        Assert.Equal(InstallState.Unknown, sut.Check(Forge).State);
    }

    [Fact]
    public void UnreadableInstancesFolder_IsUnknown_EvenWhenTheVersionFolderIsInvisible()
    {
        // Nothing under instances/ is visible when it cannot be listed: that must not read as NotInstalled.
        var sut = Make(listable: dir => throw new UnauthorizedAccessException(dir));
        Directory.CreateDirectory(Instances);
        Assert.Equal(InstallState.Unknown, sut.Check("26.3").State);
    }

    [Fact]
    public void DefaultListProbe_ReadsARealFolder()
    {
        FullFabric();
        Assert.Equal(InstallState.Installed, Make().Check(Fabric).State);
    }

    [Fact]
    public void ZeroByteClientJson_IsMissing()
    {
        FullFabric();
        Put(Path.Combine(Root(Fabric), "versions", Fabric, Fabric + ".json"), 0);
        var info = Make().Check(Fabric);
        Assert.Equal(InstallState.Partial, info.State);
        Assert.Equal(InstallPieces.Client, info.Missing);
    }

    [Fact]
    public void ZeroByteFabricProfileJson_IsMissing()
    {
        FullFabric();
        var name = "fabric-loader-0.16.14-" + Fabric;
        Put(Path.Combine(Root(Fabric), "versions", name, name + ".json"), 0);
        var info = Make().Check(Fabric);
        Assert.Equal(InstallState.Partial, info.State);
        Assert.Equal(InstallPieces.Loader, info.Missing);
    }

    [Fact]
    public void ZeroByteForgeProfileJson_IsMissing()
    {
        Client(Forge);
        ForgeLoader();
        OriginJar(Forge);
        Put(Path.Combine(Root(Forge), "versions", ForgeId, ForgeId + ".json"), 0);
        var info = Make().Check(Forge);
        Assert.Equal(InstallState.Partial, info.State);
        Assert.Equal(InstallPieces.Loader, info.Missing);
    }

    [Fact]
    public void StaleVerificationStamp_WithAllFiles_StaysInstalled_AndFlagsVerifyDue()
    {
        FullFabric();
        var info = Make(stamp: (_, _) => false).Check(Fabric);
        Assert.Equal(InstallState.Installed, info.State);
        Assert.True(info.VerifyDue);
        Assert.Equal(InstallPieces.None, info.Missing);
        Assert.False(info.Present.HasFlag(InstallPieces.Verified));
    }

    [Fact]
    public void StaleVerificationStamp_WithStaleOriginJar_IsStillUpdateReady()
    {
        FullFabric();
        OriginJar(Fabric, bundledBytes: 400);
        var info = Make(stamp: (_, _) => false).Check(Fabric);
        Assert.Equal(InstallState.UpdateReady, info.State);
        Assert.True(info.VerifyDue);
    }

    [Fact]
    public void StaleVerificationStamp_WithAMissingFile_IsPartialForTheFileOnly()
    {
        Client(Fabric);
        OriginJar(Fabric);
        var info = Make(stamp: (_, _) => false).Check(Fabric);
        Assert.Equal(InstallState.Partial, info.State);
        Assert.Equal(InstallPieces.Loader, info.Missing); // the stamp is never a missing piece
        Assert.False(info.VerifyDue);
    }

    [Fact]
    public void RefreshReportsVerifyDueChange()
    {
        FullFabric();
        var due = true;
        var sut = Make(stamp: (_, _) => !due);
        var seen = new List<string>();
        sut.Changed += seen.Add;
        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();
        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();
        Assert.Single(seen);
        due = false; // a full verify has passed since
        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();
        Assert.Equal(2, seen.Count);
        Assert.False(sut.Get(Fabric)!.VerifyDue);
        Assert.Equal(InstallState.Installed, sut.StateOf(Fabric));
    }

    [Fact]
    public void ValidVerificationStamp_IsInstalled_NotDue_AndAskedWithTheRootAndVersion()
    {
        FullFabric();
        string? seenRoot = null, seenVersion = null;
        var info = Make(stamp: (root, version) => { seenRoot = root; seenVersion = version; return true; }).Check(Fabric);
        Assert.Equal(InstallState.Installed, info.State);
        Assert.True(info.Present.HasFlag(InstallPieces.Verified));
        Assert.False(info.VerifyDue);
        Assert.Equal(Root(Fabric), seenRoot);
        Assert.Equal(Fabric, seenVersion);
    }

    [Fact]
    public void StaleOriginJarWithValidStamp_IsUpdateReady()
    {
        FullFabric();
        OriginJar(Fabric, bundledBytes: 400);
        Assert.Equal(InstallState.UpdateReady, Make(stamp: (_, _) => true).Check(Fabric).State);
    }

    [Fact]
    public void NothingOnDisk_IsNotInstalled_RegardlessOfTheStamp()
    {
        Directory.CreateDirectory(Path.Combine(Root(Fabric), "mods"));
        Assert.Equal(InstallState.NotInstalled, Make(stamp: (_, _) => false).Check(Fabric).State);
    }

    [Fact]
    public void Invalidate_DropsAProbeThatWasAlreadyReadingTheDisk()
    {
        FullFabric();
        using var entered = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var first = true;
        var sut = Make(bundled: v =>
        {
            if (first) { first = false; entered.Set(); release.Wait(TimeSpan.FromSeconds(10)); }
            return BundledJar(v);
        });

        var refresh = sut.RefreshAsync(Fabric);
        Assert.True(entered.Wait(TimeSpan.FromSeconds(10)));   // the probe holds its ticket and is mid-read
        sut.Invalidate(Fabric);                                // newer ticket recorded
        release.Set();
        refresh.GetAwaiter().GetResult();

        Assert.Equal(InstallState.Unknown, sut.StateOf(Fabric)); // the stale answer was dropped
        Assert.Null(sut.Get(Fabric));

        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();       // a probe after the Invalidate does store
        Assert.Equal(InstallState.Installed, sut.StateOf(Fabric));
    }

    [Fact]
    public void LegacyForgeLayout_MatchesTheRealTable()
    {
        var layout = LegacyForgeBuilds.LayoutFor("1.8.9");
        Assert.NotNull(layout);
        Assert.Equal(ForgeId, layout!.VersionId);
        Assert.Equal(ForgeLibPath, layout.LibraryRelativePath);
        Assert.Null(LegacyForgeBuilds.LayoutFor("1.21.1"));
    }

    [Fact]
    public void LegacyForgeProfileId_MatchesTheBundledForgeJson()
    {
        // The profile id InstalledVersions looks for must be the id inside the JSON the installer copies there.
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null && !File.Exists(Path.Combine(dir.FullName, "src", "OriginLauncher.App", "Assets", "Forge", "forge-1.8.9.json")))
            dir = dir.Parent;
        Assert.NotNull(dir);
        var json = File.ReadAllText(Path.Combine(dir!.FullName, "src", "OriginLauncher.App", "Assets", "Forge", "forge-1.8.9.json"));
        using var doc = System.Text.Json.JsonDocument.Parse(json);
        Assert.Equal(LegacyForgeBuilds.LayoutFor("1.8.9")!.VersionId, doc.RootElement.GetProperty("id").GetString());
    }

    [Fact]
    public void Check_WritesAndDeletesNothing()
    {
        FullFabric();
        Client(Forge);
        Directory.CreateDirectory(Path.Combine(Root("26.3"), "mods"));
        var before = Snapshot(_tmp);
        var sut = Make();
        foreach (var v in new[] { Fabric, Forge, "26.3", "nope" }) sut.Check(v);
        sut.RefreshAllAsync().GetAwaiter().GetResult();
        Assert.Equal(before, Snapshot(_tmp));
    }

    [Fact]
    public void StateIsUnknownUntilRefreshed_ThenCachedUntilRefreshedAgain()
    {
        FullFabric();
        var sut = Make();
        Assert.Equal(InstallState.Unknown, sut.StateOf(Fabric));
        Assert.Null(sut.Get(Fabric));

        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();
        Assert.Equal(InstallState.Installed, sut.StateOf(Fabric));

        // The disk changes; the cache does not look (StateOf never touches disk) until a refresh.
        File.Delete(Path.Combine(Root(Fabric), "mods", InstalledVersions.OriginJarFileName));
        Assert.Equal(InstallState.Installed, sut.StateOf(Fabric));

        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();
        Assert.Equal(InstallState.Partial, sut.StateOf(Fabric));
        Assert.Equal(InstallPieces.OriginMod, sut.Get(Fabric)!.Missing);
    }

    [Fact]
    public void RefreshAll_FillsEveryListedVersion()
    {
        FullFabric();
        var sut = Make();
        sut.RefreshAllAsync().GetAwaiter().GetResult();
        Assert.Equal(InstallState.Installed, sut.StateOf(Fabric));
        Assert.Equal(InstallState.NotInstalled, sut.StateOf(Forge));
        Assert.Equal(InstallState.NotInstalled, sut.StateOf("26.3"));
        Assert.Equal(InstallState.Unknown, sut.StateOf("1.20")); // not in the list: never guessed
    }

    [Fact]
    public void Invalidate_ForgetsTheCachedAnswer()
    {
        FullFabric();
        var sut = Make();
        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();
        sut.Invalidate(Fabric);
        Assert.Equal(InstallState.Unknown, sut.StateOf(Fabric));
        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();
        Assert.Equal(InstallState.Installed, sut.StateOf(Fabric));
    }

    [Fact]
    public void Changed_FiresOnlyWhenTheAnswerChanges()
    {
        FullFabric();
        var sut = Make();
        var seen = new List<string>();
        sut.Changed += seen.Add;

        sut.RefreshAsync(Fabric).GetAwaiter().GetResult(); // Unknown -> Installed
        sut.RefreshAsync(Fabric).GetAwaiter().GetResult(); // same answer
        Assert.Equal([Fabric], seen);

        OriginJar(Fabric, bundledBytes: 900);              // -> UpdateReady
        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();
        Assert.Equal([Fabric, Fabric], seen);
        Assert.Equal(InstallState.UpdateReady, sut.StateOf(Fabric));
    }

    [Fact]
    public void ThrowingListener_DoesNotBreakTheRefresh()
    {
        FullFabric();
        var sut = Make();
        sut.Changed += _ => throw new InvalidOperationException("ui bug");
        sut.RefreshAsync(Fabric).GetAwaiter().GetResult();
        Assert.Equal(InstallState.Installed, sut.StateOf(Fabric));
    }
}
