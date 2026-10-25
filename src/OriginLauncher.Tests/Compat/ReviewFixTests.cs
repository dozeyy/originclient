using System.Text;
using OriginLauncher.App.Core.Compat;
using Xunit;
using static OriginLauncher.Tests.Compat.CompatKit;

namespace OriginLauncher.Tests.Compat;

// Regression tests for the code review of the pre-launch check and the importer: a transient read failure must
// never block a launch for good, unreadable nested jars are never a "confirmed" missing mod, odd metadata never throws,
// and a cancelled launch stops the check.
public sealed class ReviewFixTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-review-" + Guid.NewGuid().ToString("N"));
    private readonly string _mods;

    public ReviewFixTests()
    {
        _mods = Path.Combine(_root, "mods");
        Directory.CreateDirectory(_mods);
        ModFolderScanner.ClearCache();
        LaunchPreflight.ClearCache();
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch (IOException) { /* temp folder */ }
    }

    private static bool IsManaged(string n) => n.StartsWith("fabric-api-");
    private static bool IsProvisioned(string n) => IsManaged(n);

    private static string Json(string id, string extra = "") =>
        "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\"" + extra + "}";

    private static byte[] JarBytes(string json) => Zip(("fabric.mod.json", Encoding.UTF8.GetBytes(json)));

    // ---- review 1: a managed jar that can't be read right now must not block the launch, and nothing bad is remembered

    [Fact]
    public void AnUnreadableOriginJarStandsTheCheckDownInsteadOfBlamingEveryDependent()
    {
        System.IO.File.WriteAllText(Path.Combine(_mods, "fabric-api-0.116.0.jar"), "half copied, not a zip yet");
        ModImporterTests.WriteJar(_mods, "needs-api-1.0.jar", "needs-api", mc: "~1.21.1", needs: new[] { ("fabric-api", ">=0.100.0") });

        LaunchPreflight.Run("1.21.1", _mods, null, true, IsManaged, IsProvisioned, null); // must not throw
        var verdict = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        Assert.Empty(verdict.Blocking);
        Assert.Same(LaunchVerdict.Clear, verdict);
    }

    [Fact]
    public void AGoodVerdictIsNotReusedOnceTheOriginJarBecomesReadable()
    {
        var api = Path.Combine(_mods, "fabric-api-0.116.0.jar");
        System.IO.File.WriteAllText(api, "half copied");
        ModImporterTests.WriteJar(_mods, "needs-api-1.0.jar", "needs-api", mc: "~1.21.1", needs: new[] { ("fabric-api", ">=0.100.0") });
        LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);

        ModImporterTests.WriteJar(_mods, "fabric-api-0.116.0.jar", "fabric-api", "0.116.0");
        LaunchPreflight.Run("1.21.1", _mods, null, true, IsManaged, IsProvisioned, null); // now satisfied, still no throw
    }

    [Fact]
    public void ABlockingVerdictIsNeverCached()
    {
        ModImporterTests.WriteJar(_mods, "bad-1.0.jar", "bad", mc: "~1.16.5");
        var first = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        var second = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        Assert.Single(first.Blocking);
        Assert.NotSame(first, second);

        // The player turns the mod off: the very next check sees it, nothing stale holds the launch.
        var bad = Path.Combine(_mods, "bad-1.0.jar");
        System.IO.File.Move(bad, bad + ".disabled");
        Assert.Empty(LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned).Blocking);
    }

    [Fact]
    public void AVerdictBuiltWhileAJarWasUnreadableIsNotCached()
    {
        System.IO.File.WriteAllText(Path.Combine(_mods, "junk.jar"), "not a zip");
        var first = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        var second = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        Assert.NotSame(first, second);
    }

    [Fact]
    public void AFailedReadIsNotRememberedEvenWhenSizeAndTimeStayTheSame()
    {
        var path = Path.Combine(_mods, "m.jar");
        var good = JarBytes(Json("m"));
        var broken = (byte[])good.Clone();
        Array.Clear(broken, broken.Length - 22, 22); // the end-of-central-directory record: not a zip any more
        var stamp = new DateTime(2026, 1, 2, 3, 4, 5, DateTimeKind.Utc);

        System.IO.File.WriteAllBytes(path, broken);
        System.IO.File.SetLastWriteTimeUtc(path, stamp);
        Assert.True(ModFolderScanner.ReadInfo(path)!.ReadFailed);

        System.IO.File.WriteAllBytes(path, good);
        System.IO.File.SetLastWriteTimeUtc(path, stamp); // same size, same time: only the content differs
        Assert.Equal("m", ModFolderScanner.ReadInfo(path)!.Id);
    }

    // ---- review 2: nested jars that could not be read never make a dependency "confirmed missing"

    private static byte[] OuterWithNested(string outerId, string nestedId, byte[]? nestedPad = null)
    {
        var inner = nestedPad == null
            ? JarBytes(Json(nestedId))
            : Zip(("fabric.mod.json", Encoding.UTF8.GetBytes(Json(nestedId))), ("pad.bin", nestedPad));
        return Zip(
            ("fabric.mod.json", Encoding.UTF8.GetBytes(Json(outerId, ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]"))),
            ("META-INF/jars/lib.jar", inner));
    }

    private static ModInfo ReadOuter(byte[] bytes, long budget) =>
        ModMetadataReader.Read(new MemoryStream(bytes), "outer.jar", readNested: true, nestedByteBudget: budget);

    [Fact]
    public void ANestedJarOverTheBudgetMarksTheInfoIncompleteAndTheIdIsUnknown()
    {
        var bytes = OuterWithNested("outer", "lib", new byte[4096]);
        var info = ReadOuter(bytes, budget: 10);
        Assert.True(info.NestedIncomplete);
        Assert.Empty(info.Nested);

        var whole = ReadOuter(bytes, ModMetadataReader.DefaultNestedBudget);
        Assert.False(whole.NestedIncomplete);
        Assert.Equal("lib", Assert.Single(whole.Nested).Id);
    }

    [Fact]
    public void ARequirementThatMayBeAnsweredByAnUnreadNestedJarIsNeverBlocking()
    {
        var outer = File(ReadOuter(OuterWithNested("outer", "lib", new byte[4096]), budget: 10), ModRole.Profile);
        var dependent = Mod("dependent", needs: new[] { ("lib", ">=1.0.0") }, mc: "~1.21.1");

        var report = Check(outer, dependent);

        Assert.False(report.HasBlocking);
        var f = Assert.Single(Of(report, FindingKind.MissingDependency));
        Assert.Equal(Certainty.Likely, f.Certainty);
        Assert.NotEqual(Severity.Blocking, f.Severity);
        Assert.Null(report.Terminal(dependent));
    }

    [Fact]
    public void WithEveryNestedJarReadTheSameRequirementIsMet()
    {
        var outer = File(ReadOuter(OuterWithNested("outer", "lib"), ModMetadataReader.DefaultNestedBudget), ModRole.Profile);
        var dependent = Mod("dependent", needs: new[] { ("lib", ">=1.0.0") }, mc: "~1.21.1");
        Assert.Empty(Of(Check(outer, dependent), FindingKind.MissingDependency));
    }

    [Fact]
    public void AnOriginJarWithUnreadNestedJarsDoesNotBlockAPlayersMod()
    {
        var api = File(ReadOuter(OuterWithNested("fabric-api", "fabric-lib", new byte[4096]), budget: 10), ModRole.Managed);
        var dependent = Mod("dependent", needs: new[] { ("fabric-lib", "*") }, mc: "~1.21.1");
        var report = Check(api, dependent);
        Assert.False(report.HasBlocking);
        Assert.False(report.Findings.Any(f => f.Certainty == Certainty.Confirmed && f.Severity == Severity.Blocking));
    }

    // ---- review 5: odd metadata shapes never throw

    [Theory]
    [InlineData("{\"schemaVersion\":1,\"id\":5,\"version\":[1],\"jars\":[{\"file\":5},{\"file\":null},\"x\",{}]}")]
    [InlineData("{\"schemaVersion\":1,\"id\":\"a\",\"depends\":{\"b\":5,\"c\":[1,\"~1\"],\"d\":{}},\"provides\":[1,{\"id\":2},null]}")]
    [InlineData("{\"schemaVersion\":1,\"id\":\"a\",\"jars\":{\"file\":\"x\"},\"environment\":7,\"breaks\":[\"z\"]}")]
    [InlineData("[1,2,3]")]
    [InlineData("\"just a string\"")]
    [InlineData("null")]
    public void OddFabricMetadataNeverThrows(string json)
    {
        var info = ModMetadataReader.Read(new MemoryStream(JarBytes(json)), "odd.jar");
        Assert.NotNull(info);
    }

    [Theory]
    [InlineData("{\"quilt_loader\":5}")]
    [InlineData("{\"quilt_loader\":{\"id\":[1]}}")]
    public void OddQuiltMetadataNeverThrows(string json)
    {
        var bytes = Zip(("quilt.mod.json", Encoding.UTF8.GetBytes(json)));
        Assert.NotNull(ModMetadataReader.Read(new MemoryStream(bytes), "odd.jar"));
    }

    [Theory]
    [InlineData("[]")]
    [InlineData("{\"modList\":5}")]
    [InlineData("[5,{\"modid\":1}]")]
    [InlineData("{\"modList\":[{\"modid\":\"m\",\"requiredMods\":[1,null,\"Forge\"]}]}")]
    public void OddLegacyMetadataNeverThrows(string json)
    {
        var bytes = Zip(("mcmod.info", Encoding.UTF8.GetBytes(json)));
        Assert.NotNull(ModMetadataReader.Read(new MemoryStream(bytes), "odd.jar"));
    }

    // ---- review 7: a cancelled launch stops the check

    [Fact]
    public void ACancelledLaunchStopsTheCheckAndIsNotSwallowedAsACheckerFailure()
    {
        for (var i = 0; i < 5; i++) ModImporterTests.WriteJar(_mods, $"m{i}-1.0.jar", $"m{i}", mc: "~1.21.1");
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        Assert.ThrowsAny<OperationCanceledException>(
            () => LaunchPreflight.Run("1.21.1", _mods, null, true, IsManaged, IsProvisioned, null, cts.Token));
    }

    [Fact]
    public void ACancelledLaunchDoesNotCacheOrBlame()
    {
        ModImporterTests.WriteJar(_mods, "m-1.0.jar", "m", mc: "~1.21.1");
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        Assert.ThrowsAny<OperationCanceledException>(
            () => LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned, null, cts.Token));
        Assert.Empty(LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned).Blocking);
    }
}
