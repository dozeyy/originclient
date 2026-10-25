using System.IO.Compression;
using System.Text.Json;
using OriginLauncher.App.Core.Compat;
using Xunit;

namespace OriginLauncher.Tests.Compat;

// The ids the loader itself provides come from the loader jars, not from memory: where a jar is on disk (the gradle
// cache of a machine that built the mod), the table must say what the jar says.
public sealed class FabricLoaderModsTests
{
    [RealJarFact(RealJarSet.FabricLoader)]
    public void TheTableMatchesTheRealLoaderJarsWhereTheyAreOnDisk()
    {
        foreach (var row in FabricLoaderMods.Verified)
        {
            // A row whose loader jar is not in this gradle cache is not read here (the test is skipped when none is).
            var jar = RealJars.FabricLoader(row.LoaderVersion);
            if (jar == null) continue;

            using var zip = ZipFile.OpenRead(jar);
            var nested = zip.Entries.Single(e => e.FullName.StartsWith("META-INF/jars/mixinextras") && e.FullName.EndsWith(".jar"));
            using var inner = new ZipArchive(nested.Open());
            using var json = JsonDocument.Parse(inner.GetEntry("fabric.mod.json")!.Open());
            Assert.Equal("mixinextras", json.RootElement.GetProperty("id").GetString());
            Assert.Equal(row.MixinExtras, json.RootElement.GetProperty("version").GetString());
            var ids = new[] { "mixinextras" }.Concat(json.RootElement.GetProperty("provides").EnumerateArray().Select(p => p.GetString()!));
            Assert.Equal(FabricLoaderMods.MixinExtrasIds.OrderBy(x => x), ids.OrderBy(x => x));
        }
    }

    [Theory]
    [InlineData(null, "0.5.5")]      // unknown: the newest verified row
    [InlineData("0.19.3", "0.5.4")]
    [InlineData("0.19.4", "0.5.4")]  // between rows: the row at or below
    [InlineData("0.19.5", "0.5.5")]
    [InlineData("0.30.0", "0.5.5")]  // newer than the table: the newest row
    [InlineData("0.14.0", "0.5.4")]  // older than the table: the oldest row
    public void PicksTheRowForTheLoaderVersion(string? loader, string expected)
    {
        var provided = FabricLoaderMods.For(loader).ToList();
        Assert.Equal(FabricLoaderMods.MixinExtrasIds.Count, provided.Count);
        Assert.All(provided, p => Assert.Equal(expected, p.Version));
    }
}
