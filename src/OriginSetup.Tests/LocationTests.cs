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
    public void PickingAnEmptyOrNewOrOwnFolderUsesIt()
    {
        var empty = Temp();
        Assert.Equal(empty, InstallLocation.Resolve(empty));
        var fresh = Path.Combine(Temp(), "Games", "Stuff");
        Assert.Equal(fresh, InstallLocation.Resolve(fresh));
        var own = Path.Combine(Temp(), "Origin Launcher");
        Directory.CreateDirectory(own);
        File.WriteAllText(Path.Combine(own, "x"), "x");
        Assert.Equal(own, InstallLocation.Resolve(own + Path.DirectorySeparatorChar));
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
    public void OnlyRecognisableOriginFoldersAreDeletable()
    {
        var random = Temp();
        File.WriteAllText(Path.Combine(random, "notes.txt"), "keep me");
        Assert.False(InstallLocation.SafeToDelete(random));
        var origin = Temp();
        File.WriteAllText(Path.Combine(origin, "uninstall.exe"), "x");
        Assert.True(InstallLocation.SafeToDelete(origin));
        Assert.False(InstallLocation.SafeToDelete(Path.Combine(Temp(), "missing")));
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
