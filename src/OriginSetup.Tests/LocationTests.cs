using System.IO;
using System.Linq;
using OriginLauncher.App.Core;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

public class LocationTests
{
    private static string Temp() => Directory.CreateTempSubdirectory("loc").FullName;

    [Fact]
    public void PickingABusyFolderInstallsIntoItsOwnSubfolder()
    {
        var docs = Temp();
        File.WriteAllText(Path.Combine(docs, "homework.txt"), "x");
        Assert.Equal(Path.Combine(docs, "OriginLauncher"), InstallLocation.Resolve(docs));
    }

    [Fact]
    public void PickingAnEmptyOrNewFolderUsesIt()
    {
        var empty = Temp();
        Assert.Equal(empty, InstallLocation.Resolve(empty));
        var fresh = Path.Combine(Temp(), "Games", "Stuff");
        Assert.Equal(fresh, InstallLocation.Resolve(fresh));
        // What a cancelled Setup leaves behind still counts as empty.
        var cancelled = Temp();
        Directory.CreateDirectory(Path.Combine(cancelled, "_setup-staging"));
        Assert.Equal(cancelled, InstallLocation.Resolve(cancelled));
        Assert.Null(InstallLocation.Problem(cancelled, new string[0], Temp()));
    }

    [Fact]
    public void AFolderNamedLikeOriginButHoldingSomethingElseGetsItsOwnSubfolder()
    {
        // EA's old "Origin" client or game library, D:\Origin: its name proves nothing.
        foreach (var name in new[] { "Origin", "Origin Launcher", "OriginLauncher" })
        {
            var ea = Path.Combine(Temp(), name);
            Directory.CreateDirectory(ea);
            File.WriteAllText(Path.Combine(ea, "Origin.exe"), "EA");
            Assert.Equal(Path.Combine(ea, "OriginLauncher"), InstallLocation.Resolve(ea + Path.DirectorySeparatorChar));
        }
    }

    [Fact]
    public void AnotherProgramsUninstallerDoesNotMakeItsFolderOrigins()
    {
        // uninstall.exe is NSIS's default uninstaller name: countless programs have one.
        var game = Temp();
        File.WriteAllText(Path.Combine(game, "uninstall.exe"), "someone else's");
        File.WriteAllText(Path.Combine(game, "game.exe"), "x");
        Assert.False(InstallLocation.IsOriginInstall(game));
        Assert.Equal(Path.Combine(game, "OriginLauncher"), InstallLocation.Resolve(game));
        Assert.Contains("other files", InstallLocation.Problem(game, new string[0], Temp()));
    }

    [Fact]
    public void ABusyFolderIsRefusedButAnOriginInstallIsNot()
    {
        var busy = Temp();
        File.WriteAllText(Path.Combine(busy, "homework.txt"), "x");
        Assert.Contains("other files", InstallLocation.Problem(busy, new string[0], Temp()));
        var install = Temp();
        File.WriteAllText(Path.Combine(install, "OriginLauncher.App.exe"), "x");
        File.WriteAllText(Path.Combine(install, "Some.dll"), "x");
        Assert.Null(InstallLocation.Problem(install, new string[0], Temp()));
    }

    [Fact]
    public void TheDataFolderAndAnythingAroundOrInsideItIsRefused()
    {
        // Installing into the data folder made uninstall delete worlds with "Also delete" off (bug run 3, IN-1).
        var local = Temp();
        var data = Path.Combine(local, "OriginLauncher");
        Directory.CreateDirectory(Path.Combine(data, "instances"));
        Assert.Contains("worlds", InstallLocation.Problem(data, new string[0], data));
        Assert.Contains("worlds", InstallLocation.Problem(data + Path.DirectorySeparatorChar, new string[0], data));
        Assert.Contains("worlds", InstallLocation.Problem(Path.Combine(data, "app"), new string[0], data));
        Assert.Contains("worlds", InstallLocation.Problem(local, new string[0], data));
        // Picking %LocalAppData% itself resolves to the data folder, which is then refused.
        Assert.Equal(data, InstallLocation.Resolve(local));
        // A sibling that merely starts with the same letters is fine.
        Assert.Null(InstallLocation.Problem(data + "Games", new string[0], data));
        Assert.Null(InstallLocation.Problem(Path.Combine(local, "Programs", "OriginLauncher"), new string[0], data));
    }

    [Fact]
    public void AnExistingInstallIsReusedInPlace()
    {
        var dir = Temp();
        File.WriteAllText(Path.Combine(dir, "OriginLauncher.App.exe"), "x");
        Assert.Equal(dir, InstallLocation.Resolve(dir));
        Assert.True(InstallLocation.IsOriginInstall(dir));
    }

    [Fact]
    public void ProtectedAndBadLocationsAreRefusedInPlainWords()
    {
        var pf = Temp();
        Assert.Contains("Program Files", InstallLocation.Problem(Path.Combine(pf, "OriginLauncher"), new[] { pf }));
        Assert.Null(InstallLocation.Problem(Path.Combine(Temp(), "OriginLauncher"), new[] { pf }));
        Assert.NotNull(InstallLocation.Problem("", new string[0]));
        Assert.NotNull(InstallLocation.Problem("relative/path", new string[0]));
        Assert.NotNull(InstallLocation.Problem(Path.Combine(Temp(), new string('a', 200)), new string[0]));
        // A sibling that merely starts with the same letters is not inside it.
        Assert.Null(InstallLocation.Problem(pf + "Games", new[] { pf }));
    }

    [Fact]
    public void WriteProbeLeavesNothingBehind()
    {
        var root = Temp();
        var target = Path.Combine(root, "a", "b", "OriginLauncher");
        Assert.Null(InstallLocation.ProbeWritable(target));
        Assert.False(Directory.Exists(Path.Combine(root, "a")));
    }


    [Fact]
    public void VersionListIsNewestFirstAndDeduplicated()
    {
        var json = "{ \"modules\": [ { \"module\": \"1.21.4\", \"versions\": [\"1.21.2\", \"1.21.3\", \"1.21.4\"] }," +
                   " { \"module\": \"1.21.1\", \"versions\": [ \"1.21.1\" ] }, { \"module\": \"1.21.11\", \"versions\": [\"1.21.11\"] }," +
                   " { \"module\": \"1.16.5\", \"versions\": [\"1.16.5\", \"1.21.1\"] } ] }";
        Assert.Equal(new[] { "1.21.11", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.16.5" }, VersionChoices.Parse(json));
    }

    [Fact]
    public void RealLiveModulesListParses()
    {
        var versions = VersionChoices.Parse(File.ReadAllText(Path.Combine(TestKit.Repo, "src", "mods", "live-modules.json")));
        Assert.Contains("1.21.1", versions);
        Assert.Equal(versions.Distinct().Count(), versions.Count);
        Assert.Equal("1.8.9", versions.Last()); // oldest last
    }

    [Theory]
    [InlineData(16384, 8192, 14336)]
    [InlineData(8192, 4096, 6144)]
    [InlineData(4096, 2048, 2048)]
    [InlineData(32768, 16384, 30720)]
    [InlineData(16180, 8192, 14080)] // a "16 GB" PC as Windows reports it -> 8 GB, not 7.75
    [InlineData(12200, 6144, 9984)]
    [InlineData(3000, 2048, 2048)]   // never below 2 GB, never above the maximum
    public void RamAdviceMatchesTheLauncherRule(int totalMb, int recommended, int maximum)
    {
        Assert.Equal(recommended, RamAdvice.RecommendedMb(totalMb));
        Assert.Equal(maximum, RamAdvice.MaximumMb(totalMb));
    }
}
