using System.Diagnostics;
using System.Text;
using OriginLauncher.App.Core.Compat;
using Xunit;
using static OriginLauncher.Tests.Compat.CompatKit;

namespace OriginLauncher.Tests.Compat;

public sealed class ModFolderScannerTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-scan-" + Guid.NewGuid().ToString("N"));

    public ModFolderScannerTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch (IOException) { /* temp folder */ }
    }

    private string Jar(string name, string id, string version = "1.0.0")
    {
        var path = Path.Combine(_dir, name);
        var json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}";
        System.IO.File.WriteAllBytes(path, Zip(("fabric.mod.json", Encoding.UTF8.GetBytes(json))));
        return path;
    }

    [Fact]
    public void ScansJarsAndDisabledJarsByNameAndSkipsEverythingElse()
    {
        Jar("b.jar", "b");
        Jar("a.jar.disabled", "a");
        System.IO.File.WriteAllText(Path.Combine(_dir, "notes.txt"), "x");
        Directory.CreateDirectory(Path.Combine(_dir, "sub.jar"));
        var mods = ModFolderScanner.Scan(_dir, ModRole.Profile);
        Assert.Equal(new[] { "a.jar.disabled", "b.jar" }, mods.Select(m => m.FileName));
        Assert.False(mods[0].Enabled);
        Assert.True(mods[1].Enabled);
        Assert.All(mods, m => Assert.Equal(ModRole.Profile, m.Role));
        Assert.Equal("a", mods[0].Info.Id);
    }

    [Fact]
    public void OriginProvisionedJarsAreManaged()
    {
        Jar("origin-client.jar", "origin-client");
        Jar("mine.jar", "mine");
        var mods = ModFolderScanner.Scan(_dir, ModRole.SharedLoose, n => n == "origin-client.jar");
        Assert.Equal(ModRole.Managed, mods.Single(m => m.FileName == "origin-client.jar").Role);
        Assert.Equal(ModRole.SharedLoose, mods.Single(m => m.FileName == "mine.jar").Role);
    }

    [Fact]
    public void AMissingFolderIsEmptyAndABrokenJarIsStillListed()
    {
        Assert.Empty(ModFolderScanner.Scan(Path.Combine(_dir, "nope"), ModRole.Profile));
        System.IO.File.WriteAllText(Path.Combine(_dir, "broken.jar"), "not a zip");
        var broken = Assert.Single(ModFolderScanner.Scan(_dir, ModRole.Profile));
        Assert.True(broken.Info.ReadFailed);
    }

    [Fact]
    public void AChangedJarIsReadAgain()
    {
        var path = Jar("m.jar", "first");
        Assert.Equal("first", ModFolderScanner.ReadInfo(path)!.Id);
        Jar("m.jar", "second-longer-id"); // different size
        Assert.Equal("second-longer-id", ModFolderScanner.ReadInfo(path)!.Id);
    }

    [Fact]
    public void AMissingFileIsNullNotAnException() =>
        Assert.Null(ModFolderScanner.ReadInfo(Path.Combine(_dir, "absent.jar")));

    // ---- speed contract: a 300-mod set is checked in well under a frame ----

    [Fact]
    public void ThreeHundredModsAreCheckedInUnderFiftyMilliseconds()
    {
        var mods = new List<ModFile>();
        for (var i = 0; i < 300; i++)
        {
            var needs = new List<(string, string)> { ("mod" + ((i + 1) % 300), ">=1.0"), ("minecraft", "1.21.x"), ("fabricloader", ">=0.15") };
            if (i % 10 == 0) needs.Add(("absent" + i, "*"));
            mods.Add(Mod("mod" + i, "1.0." + i, needs: needs.ToArray(), breaks: i % 7 == 0 ? new[] { ("other" + i, "*") } : null,
                nested: i % 5 == 0 ? new[] { new NestedMod("inner" + i, "1.0.0", null) } : null));
        }
        CompatEngine.Check(mods, Fabric121); // warm up the JIT: the contract is about the check, not the first call

        var best = double.MaxValue;
        CompatReport report = null!;
        for (var run = 0; run < 5; run++)
        {
            var sw = Stopwatch.StartNew();
            report = CompatEngine.Check(mods, Fabric121);
            sw.Stop();
            best = Math.Min(best, sw.Elapsed.TotalMilliseconds);
        }
        Assert.True(best < 50, $"300 mods took {best:F1} ms");
        Assert.True(report.HasBlocking); // the broken ones were really found, not skipped
        Assert.Equal(30, report.Findings.Count(f => f.Kind == FindingKind.MissingDependency && !f.IsKnockOn && f.TargetId!.StartsWith("absent")));
    }
}
