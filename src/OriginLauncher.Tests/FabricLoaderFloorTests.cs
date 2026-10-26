using System.IO;
using System.Text.Json;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Versions;
using Xunit;

namespace OriginLauncher.Tests;

// The Fabric Loader floor (2026-10-25): an instance that cached a loader older than the jars Origin ships need
// (JEI 11.68 and newer: fabricloader >=0.19.4; every module builds on 0.20.1) gets a fresh loader instead of
// "Incompatible mods found". Checked per live Fabric version.
public class FabricLoaderFloorTests
{
    public static IEnumerable<object[]> LiveFabricVersions() =>
        new[]
        {
            "1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.3", "1.19.4", "1.20", "1.20.1", "1.20.2", "1.20.4",
            "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.6", "1.21.7", "1.21.8", "1.21.10", "1.21.11",
            "26.1", "26.1.1", "26.1.2", "26.2", "26.3",
        }.Select(v => new object[] { v });

    [Theory]
    [MemberData(nameof(LiveFabricVersions))]
    public void AnOlderCachedLoaderIsReplacedOnEveryVersion(string mc)
    {
        foreach (var old in new[] { "0.15.0", "0.16.14", "0.19.3", "0.19.5", "0.20.0" })
        {
            var name = $"fabric-loader-{old}-{mc}";
            Assert.False(FabricLoaderFloor.IsMet(name), name);
            Assert.True(FabricLoaderFloor.ForcesFullVerify(name), name);
        }
        foreach (var ok in new[] { "0.20.1", "0.20.2", "0.21.0", "1.0.0" })
        {
            var name = $"fabric-loader-{ok}-{mc}";
            Assert.True(FabricLoaderFloor.IsMet(name), name);
            Assert.False(FabricLoaderFloor.ForcesFullVerify(name), name);
        }
    }

    [Theory]
    [InlineData(null, false)]          // no stamp: the launch is already a full one
    [InlineData("", true)]             // unreadable name: reinstall
    [InlineData("1.21.1", true)]
    [InlineData("fabric-loader-0.10.0+build.208-1.16.5", true)]
    public void OddNames(string? cached, bool forcesFull) =>
        Assert.Equal(forcesFull, FabricLoaderFloor.ForcesFullVerify(cached));

    // The floor must equal the loader every live Fabric module builds and boots on in CI; raise both together.
    [Fact]
    public void FloorMatchesEveryLiveModulesLoaderVersion()
    {
        var root = RepoRoot();
        if (root == null) return; // run outside a checkout: nothing to compare against
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(root, "src", "mods", "live-modules.json")));
        var checkedModules = 0;
        foreach (var entry in doc.RootElement.GetProperty("modules").EnumerateArray())
        {
            var module = entry.ValueKind == JsonValueKind.String ? entry.GetString()! : entry.GetProperty("module").GetString()!;
            if (module == "1.8.9") continue; // Forge: no Fabric Loader
            var props = File.ReadAllText(Path.Combine(root, "src", "mods", "versions", module, "gradle.properties"));
            var match = Regex.Match(props, @"^loader_version=(\S+)", RegexOptions.Multiline);
            Assert.True(match.Success, module);
            Assert.Equal(FabricLoaderFloor.Minimum, Version.Parse(match.Groups[1].Value));
            checkedModules++;
        }
        Assert.True(checkedModules >= 19, $"only {checkedModules} modules checked");
    }

    private static string? RepoRoot()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir != null; dir = dir.Parent)
            if (File.Exists(Path.Combine(dir.FullName, "src", "mods", "live-modules.json"))) return dir.FullName;
        return null;
    }
}
