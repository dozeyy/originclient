using System.Diagnostics;
using OriginLauncher.App.Core.Compat;
using Xunit;
using Xunit.Abstractions;

namespace OriginLauncher.Tests.Compat;

// The check made right before a launch: a hard incompatibility is a sentence, never a crash; Origin-only launches,
// soft issues and checker bugs never stop the game; a warm launch pays a listing and one hash.
public sealed class LaunchPreflightTests : IDisposable
{
    private readonly ITestOutputHelper _out;
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-preflight-" + Guid.NewGuid().ToString("N"));
    private readonly string _mods;
    private readonly string _profile;
    private readonly string _log;

    public LaunchPreflightTests(ITestOutputHelper output)
    {
        _out = output;
        _mods = Path.Combine(_root, "mods");
        _profile = Path.Combine(_root, "profile");
        _log = Path.Combine(_root, "logs", "mod-compat-1.21.1.log");
        Directory.CreateDirectory(_mods);
        Directory.CreateDirectory(_profile);
        ModFolderScanner.ClearCache();
        LaunchPreflight.ClearCache();
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch (IOException) { /* temp folder */ }
    }

    private static bool IsManaged(string n) => n.StartsWith("originclient") || n.StartsWith("fabric-api-");
    private static bool IsProvisioned(string n) => IsManaged(n) || n.StartsWith("voicechat-");

    private string Jar(string folder, string name, string id, string version = "1.0.0", string? mc = "~1.21.1",
        (string, string)[]? needs = null, (string, string)[]? breaks = null) =>
        ModImporterTests.WriteJar(folder, name, id, version, mc, needs, breaks);

    private void Run(string version = "1.21.1", bool externalMods = true, string? profileFolder = null, string? overrideSetKey = null) =>
        LaunchPreflight.Run(version, _mods, profileFolder, externalMods, IsManaged, IsProvisioned, _log, default, overrideSetKey);

    [Fact]
    public void ACompatibleSetLaunches()
    {
        Jar(_mods, "originclient-1.jar", "originclient");
        Jar(_mods, "fabric-api-0.116.0.jar", "fabric-api", "0.116.0");
        Jar(_mods, "cool-1.0.jar", "cool", needs: new[] { ("fabric-api", ">=0.100.0") });
        Run();
        Assert.True(System.IO.File.Exists(_log), "the verdict is written to the log");
    }

    [Fact]
    public void AModThatNeedsMixinExtrasWithoutBundlingItStillLaunches()
    {
        // Fabric Loader (0.19.3: mixinextras-fabric-0.5.4.jar inside the loader jar) provides mixinextras itself.
        Jar(_mods, "originclient-1.jar", "originclient");
        Jar(_mods, "fabric-api-0.116.0.jar", "fabric-api", "0.116.0");
        Jar(_mods, "uses-extras-1.0.jar", "usesextras", needs: new[] { ("mixinextras", ">=0.4.1") });
        Jar(_mods, "uses-extras-id-1.0.jar", "usesextrasid", needs: new[] { ("com_github_llamalad7_mixinextras", "*") });
        Run();
        Assert.True(System.IO.File.Exists(_log));
    }

    [Fact]
    public void MixinExtrasFromTheLoaderIsStillHeldToItsVersion()
    {
        // The loader's copy is 0.5.x: a mod that needs a newer one than that is a real mismatch, not a free pass.
        Jar(_mods, "needs-new-extras-1.0.jar", "needsnew", needs: new[] { ("mixinextras", ">=9.0.0") });
        Assert.Throws<IncompatibleModsException>(() => Run());
    }

    [Fact]
    public void PlayOnTheSameModSetLaunchesAnywayAndLogsIt()
    {
        Jar(_mods, "old-1.0.jar", "old", mc: "~1.20.4");
        var first = Assert.Throws<IncompatibleModsException>(() => Run());
        Assert.False(string.IsNullOrEmpty(first.SetKey));

        // The player was told and pressed Play: Home passes the refused set's key and the game goes.
        Run(overrideSetKey: first.SetKey);
        var log = System.IO.File.ReadAllText(_log);
        Assert.Contains("THE PLAYER OVERRODE THIS", log);
        Assert.Contains("STOPS THE GAME: old", log);
    }

    [Fact]
    public void WithoutTheKeyTheSameSetIsRefusedEveryTime()
    {
        Jar(_mods, "old-1.0.jar", "old", mc: "~1.20.4");
        var first = Assert.Throws<IncompatibleModsException>(() => Run());
        Run(overrideSetKey: first.SetKey);
        // Nothing is remembered in the check itself: the next launch without the key is refused again.
        Assert.Throws<IncompatibleModsException>(() => Run());
        Assert.Throws<IncompatibleModsException>(() => Run());
    }

    [Fact]
    public void TheOverrideOnlyCoversTheExactSetItWasGivenFor()
    {
        Jar(_mods, "old-1.0.jar", "old", mc: "~1.20.4");
        var first = Assert.Throws<IncompatibleModsException>(() => Run());

        // A different broken jar joins the set: new fingerprint, the key no longer matches, the check applies again.
        Jar(_mods, "older-1.0.jar", "older", mc: "~1.19.2");
        var second = Assert.Throws<IncompatibleModsException>(() => Run(overrideSetKey: first.SetKey));
        Assert.NotEqual(first.SetKey, second.SetKey);
        Assert.Equal(2, second.Names.Count);
    }

    [Fact]
    public void AnOverrideKeyNobodyGaveIsIgnored()
    {
        Jar(_mods, "old-1.0.jar", "old", mc: "~1.20.4");
        Assert.Throws<IncompatibleModsException>(() => Run(overrideSetKey: "not-a-real-key"));
        Assert.Throws<IncompatibleModsException>(() => Run(overrideSetKey: ""));
    }

    [Fact]
    public void TheOverrideKeyOfOneVersionDoesNotOpenAnother()
    {
        // The same mods folder on another Minecraft version is a different set (the version is in the fingerprint).
        Jar(_mods, "old-1.0.jar", "old", mc: "~1.20.4");
        var on121 = Assert.Throws<IncompatibleModsException>(() => Run());
        var on1204 = Assert.Throws<IncompatibleModsException>(() => Run(version: "1.20.1"));
        Assert.NotEqual(on121.SetKey, on1204.SetKey);
        Assert.Throws<IncompatibleModsException>(() => Run(version: "1.20.1", overrideSetKey: on121.SetKey));
    }

    [Fact]
    public void AModForAnotherMinecraftVersionStopsTheLaunchWithASentence()
    {
        Jar(_mods, "old-1.0.jar", "old", mc: "~1.20.4");
        var ex = Assert.Throws<IncompatibleModsException>(() => Run());
        Assert.Contains("old", ex.Message);
        Assert.Contains("1.21.1", ex.Message);
        Assert.Contains("Turn it off on the Mods page, or press Play to start anyway.", ex.Message);
        Assert.Single(ex.Reasons);
        Assert.Single(ex.Names);
    }

    [Fact]
    public void AMissingRequiredModStopsTheLaunch()
    {
        Jar(_mods, "addon-1.0.jar", "addon", needs: new[] { ("somelib", ">=1.0") });
        var ex = Assert.Throws<IncompatibleModsException>(() => Run());
        Assert.Contains("addon", ex.Message);       // Home names the mod that stops the game
        Assert.Contains("somelib", ex.Reasons[0]);  // the reason (what it needs) goes to the log
    }

    [Fact]
    public void ADeclaredBreaksStopsTheLaunch()
    {
        Jar(_mods, "a-1.0.jar", "a", breaks: new[] { ("b", "*") });
        Jar(_mods, "b-1.0.jar", "b");
        var ex = Assert.Throws<IncompatibleModsException>(() => Run());
        Assert.Contains("a", ex.Message);
    }

    [Fact]
    public void ManyBadModsAreSummarisedWithACount()
    {
        for (var i = 0; i < 5; i++) Jar(_mods, $"bad{i}-1.0.jar", $"bad{i}", mc: "~1.16.5");
        var ex = Assert.Throws<IncompatibleModsException>(() => Run());
        Assert.Equal(5, ex.Reasons.Count);
        Assert.Equal(5, ex.Names.Count);
        Assert.Contains("(and 3 more)", ex.Message);
        Assert.Contains("Turn them off on the Mods page, or press Play to start anyway.", ex.Message);
        // Only the first two are named; the rest are counted.
        Assert.Contains(ex.Names[0], ex.Message);
        Assert.Contains(ex.Names[1], ex.Message);
        Assert.DoesNotContain(ex.Names[2], ex.Message);
    }

    [Fact]
    public void TheHomeLineIsTwoLinesNamesAndInstruction()
    {
        var ex = new IncompatibleModsException("1.21.1",
            new[] { "Old Mod", "Some Lib", "Third", "Fourth" },
            new[] { "Old Mod: x.", "Some Lib: y.", "Third: z.", "Fourth: w." });
        Assert.Equal("Old Mod and Some Lib (and 2 more) won't start on 1.21.1.\nTurn them off on the Mods page, or press Play to start anyway.", ex.Message);

        var one = new IncompatibleModsException("1.21.1", new[] { "Old Mod" }, new[] { "Old Mod: x." });
        Assert.Equal("Old Mod won't start on 1.21.1.\nTurn it off on the Mods page, or press Play to start anyway.", one.Message);
    }

    [Theory]
    [InlineData("1.21.1", 1)]
    [InlineData("1.21.11", 2)]
    [InlineData("1.21.11", 7)]
    [InlineData("26.1.2", 120)]
    public void EveryMessageIsTwoRowsWhateverTheNames(string version, int count)
    {
        // Mixed-case names at the 20 character limit, and the worst case: wide capitals.
        foreach (var sample in new[] { "Zoomify Extended Edition Pro Plus", new string('W', 60), "iiiiiiiiiiiiiiiiiiiiiiiiii", "Sodium Extra Optimized Renderer" })
        {
            var names = Enumerable.Range(0, count).Select(i => count == 1 ? sample : sample + i).ToList();
            var ex = new IncompatibleModsException(version, names, names);
            var lines = ex.Message.Split('\n');
            Assert.Equal(2, lines.Length);
            Assert.True(LaunchPreflightEstimate(lines[0]) <= IncompatibleModsException.LineBudget, lines[0]);
            // Row two is fixed text: it has to fit the status width on its own, with room to spare.
            Assert.True(LaunchPreflightEstimate(lines[1]) <= StatusWidth, lines[1]);
            Assert.True(ex.Message.All(c => c < 128), "ASCII only");
            var shown = lines[0].Contains(" and ") ? 2 : 1;
            if (count > shown) Assert.Contains($"(and {count - shown} more)", lines[0]);
            else Assert.DoesNotContain("more)", lines[0]);
        }
    }

    // Home's status text box (Size.StatusWidth) and the estimate the first row is held to.
    private const double StatusWidth = 400;
    private static double LaunchPreflightEstimate(string text) => IncompatibleModsException.EstimateWidth(text);

    [Fact]
    public void LongModNamesAreCutWithDots()
    {
        var longName = new string('W', 60);
        var ex = new IncompatibleModsException("1.21.11",
            new[] { longName, longName, longName }, new[] { "a", "b", "c" });
        Assert.DoesNotContain(longName, ex.Message);
        Assert.Contains("...", ex.Message);
    }

    [Fact]
    public void AnOriginOnlyLaunchIsNeverChecked()
    {
        Jar(_mods, "old-1.0.jar", "old", mc: "~1.20.4");
        Run(externalMods: false);
        Assert.False(System.IO.File.Exists(_log));
    }

    [Fact]
    public void ATurnedOffModIsNotInTheLaunchSet()
    {
        var path = Jar(_mods, "old-1.0.jar", "old", mc: "~1.20.4");
        System.IO.File.Move(path, path + ".disabled");
        Run();
    }

    [Fact]
    public void ASoftIssueIsLoggedAndNeverStopsTheLaunch()
    {
        // "conflicts" is only a warning in the loader; a Likely/advisory finding is not a stop.
        var path = Path.Combine(_mods, "meh-1.0.jar");
        var json = "{\"schemaVersion\":1,\"id\":\"meh\",\"version\":\"1.0.0\",\"conflicts\":{\"b\":\"*\"},\"recommends\":{\"nothere\":\"*\"}}";
        System.IO.File.WriteAllBytes(path, CompatKit.Zip(("fabric.mod.json", System.Text.Encoding.UTF8.GetBytes(json))));
        Jar(_mods, "b-1.0.jar", "b");
        Run();
        var log = System.IO.File.ReadAllText(_log);
        Assert.Contains("note:", log);
        Assert.DoesNotContain("STOPS THE GAME", log);
    }

    [Fact]
    public void AnOriginJarIsNeverTheReasonTheGameWontStart()
    {
        // Even if Origin's own jar looked wrong to the check, the player is not told their game can't start.
        Jar(_mods, "originclient-1.jar", "originclient", mc: "~1.20.4");
        Run();
    }

    [Fact]
    public void ACheckerThatThrowsLaunchesAsBefore()
    {
        Jar(_mods, "cool-1.0.jar", "cool");
        LaunchPreflight.Run("1.21.1", _mods, null, true, _ => throw new InvalidOperationException("boom"), IsProvisioned, _log);
        Assert.Contains("failed; launching as before", System.IO.File.ReadAllText(_log));
    }

    [Fact]
    public void ABrokenJarDoesNotStopTheLaunch()
    {
        // An unreadable file is the loader's warning, not a verified stop; only confirmed findings block.
        System.IO.File.WriteAllText(Path.Combine(_mods, "junk.jar"), "not a zip");
        Run();
    }

    [Fact]
    public void LegacyForge189IsLogOnly()
    {
        Jar(_mods, "old-1.0.jar", "old", mc: "~1.20.4");
        LaunchPreflight.Run("1.8.9", _mods, null, true, IsManaged, IsProvisioned, _log);
    }

    [Fact]
    public void AProfileLaunchChecksTheProfileAndNotTheLooseSharedMods()
    {
        Jar(_mods, "fabric-api-0.116.0.jar", "fabric-api", "0.116.0");
        Jar(_mods, "loose-bad-1.0.jar", "loose-bad", mc: "~1.16.5"); // not in a profile launch
        Jar(_profile, "ok-1.0.jar", "ok");
        Run(profileFolder: _profile);

        Jar(_profile, "bad-1.0.jar", "bad", mc: "~1.16.5");
        var ex = Assert.Throws<IncompatibleModsException>(() => Run(profileFolder: _profile));
        Assert.Contains("bad", ex.Message);
        Assert.DoesNotContain("loose-bad", ex.Message);
    }

    [Fact]
    public void TheVerdictIsRememberedByTheLaunchSetFingerprint()
    {
        Jar(_mods, "cool-1.0.jar", "cool");
        var first = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        var second = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        Assert.Same(first, second);

        // A new file changes the set: checked again.
        Jar(_mods, "late-1.0.jar", "late", mc: "~1.16.5");
        var third = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        Assert.NotSame(first, third);
        Assert.Single(third.Blocking);

        // A changed file (same name, new size) is checked again too.
        Jar(_mods, "late-1.0.jar", "late", version: "1.0.1", mc: "~1.21.1");
        var fourth = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        Assert.Empty(fourth.Blocking);
    }

    [Fact]
    public void ColdAndWarmCheckOfA150JarSet()
    {
        for (var i = 0; i < 150; i++)
            Jar(_mods, $"mod{i:000}-1.0.jar", $"mod{i:000}", needs: i % 3 == 0 ? new[] { ("mod000", "*") } : null);

        ModFolderScanner.ClearCache();
        LaunchPreflight.ClearCache();
        var sw = Stopwatch.StartNew();
        var cold = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        var coldMs = sw.Elapsed.TotalMilliseconds;
        Assert.Equal(150, cold.ModCount);
        Assert.Empty(cold.Blocking);
        // Measured 2026-10-25: 33 ms on Will's PC. Cold means every jar opened once, so this is the price of a changed set.
        Assert.True(coldMs < 400, $"a cold check of 150 jars took {coldMs:F0} ms");

        // Jar metadata cached, verdict not: what a changed set costs once the jars have been read.
        LaunchPreflight.ClearCache();
        sw.Restart();
        LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        var rescanMs = sw.Elapsed.TotalMilliseconds;

        // Warm: the verdict is remembered. Best of 25 so a busy CI core cannot decide it.
        var warmMs = double.MaxValue;
        for (var i = 0; i < 25; i++)
        {
            sw.Restart();
            LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
            warmMs = Math.Min(warmMs, sw.Elapsed.TotalMilliseconds);
        }

        _out.WriteLine($"150-jar launch set: cold check {coldMs:F1} ms (reads every jar), "
            + $"re-check with jars cached {rescanMs:F1} ms, warm launch (verdict cached) {warmMs:F2} ms");
        Assert.True(warmMs < 25, $"warm launch took {warmMs:F2} ms");
        Assert.True(warmMs < coldMs, "the verdict cache must beat a cold check");
    }

    // ---- real jars --------------------------------------------------------------------------------------------

    // The built mod and Fabric API live on a machine that built it (RealJars); a CI runner has neither, and this test
    // is then reported as SKIPPED rather than passing without checking anything.
    [RealJarFact(RealJarSet.FabricApiAndOrigin)]
    public void ColdCheckOfTheRealFabricApiAndOriginJarsIsFast()
    {
        var fabricApi = RealJars.FabricApi()!;
        var origin = RealJars.OriginMod()!;

        System.IO.File.Copy(fabricApi, Path.Combine(_mods, Path.GetFileName(fabricApi)));
        System.IO.File.Copy(origin, Path.Combine(_mods, Path.GetFileName(origin)));
        Jar(_mods, "cool-1.0.jar", "cool", needs: new[] { ("fabric-api", ">=0.100.0") });
        ModFolderScanner.ClearCache();
        LaunchPreflight.ClearCache();

        var sw = Stopwatch.StartNew();
        var verdict = LaunchPreflight.Evaluate("1.21.1", _mods, null, true, IsManaged, IsProvisioned);
        var coldMs = sw.Elapsed.TotalMilliseconds;
        var size = new FileInfo(fabricApi).Length + new FileInfo(origin).Length;
        _out.WriteLine($"real jars ({Path.GetFileName(fabricApi)}, {Path.GetFileName(origin)}, {size / 1024} KB): cold check {coldMs:F0} ms; blocking: {string.Join(" / ", verdict.Blocking)}");
        Assert.Empty(verdict.Blocking);
        // Measured 2026-10-25 on Will's PC: 75 ms for these two jars (18 MB, Fabric API's ~70 nested modules included).
        // The limit leaves a slow CI disk about six times that; a cold check that reaches it is a regression.
        Assert.True(coldMs < 500, $"a cold check of Fabric API + Origin took {coldMs:F0} ms");
    }
}
