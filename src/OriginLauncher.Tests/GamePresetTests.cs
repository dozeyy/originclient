using System.Text;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Presets;
using Xunit;

namespace OriginLauncher.Tests;

// The 1.8.9 performance presets (Core/Presets). Everything runs on temp folders; no Minecraft, no launcher window.

internal static class RepoFiles
{
    /// <summary>The repo root: the folder holding OriginLauncher.sln, found from the test binary.</summary>
    public static string Root
    {
        get
        {
            var dir = new DirectoryInfo(AppContext.BaseDirectory);
            while (dir != null && !File.Exists(Path.Combine(dir.FullName, "OriginLauncher.sln"))) dir = dir.Parent;
            return dir?.FullName ?? throw new InvalidOperationException("OriginLauncher.sln not found above " + AppContext.BaseDirectory);
        }
    }

    public static string Path_(params string[] parts) => Path.Combine(new[] { Root }.Concat(parts).ToArray());
}

public class GamePresetOptionsTests
{
    private static readonly string KeysFile = RepoFiles.Path_("tools", "bench", "optifine-keys-189.txt");

    private static HashSet<string> VerifiedOptiFineKeys() =>
        File.ReadAllLines(KeysFile)
            .Where(l => l.Trim().Length > 0 && !l.TrimStart().StartsWith('#'))
            .Select(l => l.Split(':')[0])
            .ToHashSet(StringComparer.Ordinal);

    [Fact]
    public void TheVerifiedKeyListIsThere()
    {
        var keys = VerifiedOptiFineKeys();
        Assert.True(keys.Count >= 70, "optifine-keys-189.txt should list the 71 verified of* keys + the zoom key");
        Assert.Contains("ofFastRender", keys);
        Assert.Contains("ofRenderRegions", keys);
    }

    [Theory]
    [InlineData(GamePresetKind.Competitive)]
    [InlineData(GamePresetKind.Balanced)]
    [InlineData(GamePresetKind.Quality)]
    public void EveryOptiFineKeyIsVerified(GamePresetKind kind)
    {
        var verified = VerifiedOptiFineKeys();
        var optifine = GamePresetOptions.For(kind, "1.8.9").Where(e => e.File == GamePresetFile.OptiFine).ToList();
        Assert.NotEmpty(optifine);
        var unverified = optifine.Where(e => !verified.Contains(e.Key)).Select(e => e.Key).ToList();
        Assert.True(unverified.Count == 0, "unverified OptiFine keys: " + string.Join(", ", unverified));
        // Nothing that looks like an OptiFine key may hide in the vanilla or Origin groups either.
        Assert.DoesNotContain(GamePresetOptions.For(kind, "1.8.9"), e => e.File != GamePresetFile.OptiFine && e.Key.StartsWith("of", StringComparison.Ordinal)
            && e.Key.Length > 2 && char.IsUpper(e.Key[2]));
    }

    [Theory]
    [InlineData(GamePresetKind.Competitive)]
    [InlineData(GamePresetKind.Balanced)]
    [InlineData(GamePresetKind.Quality)]
    public void EveryVanillaKeyIsOneThe189LoaderReads(GamePresetKind kind)
    {
        foreach (var e in GamePresetOptions.For(kind, "1.8.9").Where(e => e.File == GamePresetFile.Options))
            Assert.True(App.Core.Sync.OptionsTxtFormat.KnownIn(e.Key, "1.8.9"), e.Key + " is not a 1.8.9 options.txt key");
    }

    [Fact]
    public void EveryOriginKeyExistsInTheMod()
    {
        // Mods.java is the schema the mod reads its config with: a typo'd key would be written and never read.
        var mods = File.ReadAllText(RepoFiles.Path_("src", "mods", "versions", "1.8.9", "src", "main", "java", "com", "origin", "client", "client", "mods", "Mods.java"));
        foreach (var kind in Enum.GetValues<GamePresetKind>())
            foreach (var e in GamePresetOptions.For(kind, "1.8.9").Where(e => e.File == GamePresetFile.Origin))
            {
                var dot = e.Key.IndexOf('.');
                var (mod, option) = (e.Key[..dot], e.Key[(dot + 1)..]);
                Assert.True(mods.Contains($"\"{mod}\""), "mod id " + mod);
                Assert.True(mods.Contains($"\"{option}\""), "option " + e.Key);
                Assert.NotNull(e.Default);
            }
    }

    [Fact]
    public void NoKeyAppearsTwiceInABundle()
    {
        foreach (var kind in Enum.GetValues<GamePresetKind>())
        {
            var keys = GamePresetOptions.For(kind, "1.8.9").Select(e => e.File + "/" + e.Key).ToList();
            Assert.Equal(keys.Count, keys.Distinct().Count());
        }
    }

    [Fact]
    public void OnlyVersion189HasABundle()
    {
        Assert.True(GamePresetOptions.IsSupported("1.8.9"));
        Assert.False(GamePresetOptions.IsSupported("1.21.1"));
        Assert.Throws<ArgumentOutOfRangeException>(() => GamePresetOptions.For(GamePresetKind.Balanced, "1.21.1"));
    }

    [Fact]
    public void FastRenderIsOffInEveryPreset()
    {
        // Not render-checked against MenuBlur / ColorGrade / MotionBlur yet (see the comment in GamePresetOptions).
        foreach (var kind in Enum.GetValues<GamePresetKind>())
            Assert.Equal("false", GamePresetOptions.For(kind, "1.8.9").Single(e => e.Key == "ofFastRender").Value);
    }

    [Fact]
    public void InventoryBlurIsOffInCompetitiveAndOnInTheOthers()
    {
        string Blur(GamePresetKind k) => GamePresetOptions.For(k, "1.8.9").Single(e => e.Key == "menublur.inventory").Value;
        Assert.Equal("false", Blur(GamePresetKind.Competitive));
        Assert.Equal("true", Blur(GamePresetKind.Balanced));
        Assert.Equal("true", Blur(GamePresetKind.Quality));
    }

    [Fact]
    public void CompetitiveKeepsCritParticlesAndTheOriginTogglesAreThere()
    {
        var c = GamePresetOptions.For(GamePresetKind.Competitive, "1.8.9");
        Assert.Equal("1", c.Single(e => e.Key == "particles" && e.File == GamePresetFile.Options).Value); // decreased, not minimal
        // Particle Changer "Reduced" would drop crits, so no preset uses it.
        foreach (var kind in Enum.GetValues<GamePresetKind>())
            Assert.Equal("All", GamePresetOptions.For(kind, "1.8.9").Single(e => e.Key == "particles.mode").Value);
        foreach (var key in new[] { "@general.cosmeticEffects", "@performance.entityDistance", "@performance.tileEntityDistance", "particles.enabled" })
            Assert.Contains(c, e => e.Key == key);
    }

    [Fact]
    public void EveryPresetWritesTheWholeParticleChangerSoSwitchingIsOrderIndependent()
    {
        foreach (var kind in Enum.GetValues<GamePresetKind>())
        {
            var b = GamePresetOptions.For(kind, "1.8.9");
            foreach (var key in new[] { "particles.enabled", "particles.hideBlockBreak", "particles.hideAll", "particles.mode" })
                Assert.Contains(b, e => e.Key == key);
            Assert.Equal("true", b.Single(e => e.Key == "particles.enabled").Value);
            Assert.Equal("false", b.Single(e => e.Key == "particles.hideAll").Value);
        }
        Assert.Equal("true", GamePresetOptions.For(GamePresetKind.Competitive, "1.8.9").Single(e => e.Key == "particles.hideBlockBreak").Value);
        Assert.Equal("false", GamePresetOptions.For(GamePresetKind.Balanced, "1.8.9").Single(e => e.Key == "particles.hideBlockBreak").Value);
    }

    [Fact]
    public void ValuesStayInsideTheKnownOptiFineRanges()
    {
        // Ranges as the option cycling works in OptiFine 1.8.9. Confirmed from Origin's own source: ofClouds 0-3.
        // The rest follow the ranges players see in the video settings and each default in
        // tools/bench/optifine-keys-189.txt must sit inside its range (the cross-check). A value outside a range
        // may clamp to another setting, which is how ofVignette "3" (Fancy) slipped in once.
        var ranges = new Dictionary<string, (int Min, int Max)>
        {
            ["ofVignette"] = (0, 2),       // default / fast / fancy; Fast draws none
            ["ofClouds"] = (0, 3),         // default / fast / fancy / off
            ["ofFogType"] = (1, 3),        // fast / fancy / off
            ["ofTrees"] = (0, 2),
            ["ofAnimatedWater"] = (0, 2),  // default / fast / off
            ["ofAnimatedLava"] = (0, 2),
            ["ofDynamicLights"] = (1, 3),  // fast / fancy / off
            ["ofBetterGrass"] = (1, 3),
            ["ofConnectedTextures"] = (1, 3),
        };
        var defaults = File.ReadAllLines(KeysFile).Where(l => l.Contains(':') && !l.StartsWith('#'))
            .ToDictionary(l => l.Split(':')[0], l => l[(l.IndexOf(':') + 1)..]);
        foreach (var (key, (min, max)) in ranges)
        {
            var def = int.Parse(defaults[key]);
            Assert.InRange(def, min, max);
            foreach (var kind in Enum.GetValues<GamePresetKind>())
                Assert.InRange(int.Parse(GamePresetOptions.For(kind, "1.8.9").Single(e => e.Key == key).Value), min, max);
        }
    }

    [Fact]
    public void TheThreePresetsDiffer()
    {
        string Sig(GamePresetKind k) => string.Join("|", GamePresetOptions.For(k, "1.8.9").Select(e => e.Key + "=" + e.Value));
        Assert.Equal(3, Enum.GetValues<GamePresetKind>().Select(Sig).Distinct().Count());
    }
}

public class GamePresetFilesTests
{
    // tools/bench/presets-189/<name>.json is generated from the C# table (the one source). The test fails when a
    // checked-in file drifts; ORIGIN_REGEN_PRESETS=1 dotnet test --filter GamePresetFilesTests rewrites them.
    [Theory]
    [InlineData(GamePresetKind.Competitive, "competitive")]
    [InlineData(GamePresetKind.Balanced, "balanced")]
    [InlineData(GamePresetKind.Quality, "quality")]
    public void BenchJsonMatchesTheTable(GamePresetKind kind, string name)
    {
        var path = RepoFiles.Path_("tools", "bench", "presets-189", name + ".json");
        var expected = GamePresetOptions.ToBenchJson(kind);
        if (Environment.GetEnvironmentVariable("ORIGIN_REGEN_PRESETS") == "1")
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, expected, new UTF8Encoding(false));
        }
        Assert.True(File.Exists(path), path + " is missing: run with ORIGIN_REGEN_PRESETS=1");
        Assert.Equal(expected.Replace("\r\n", "\n"), File.ReadAllText(path).Replace("\r\n", "\n"));
    }

    [Theory]
    [InlineData(GamePresetKind.Competitive)]
    [InlineData(GamePresetKind.Balanced)]
    [InlineData(GamePresetKind.Quality)]
    public void BenchJsonIsTheShapeProd189Reads(GamePresetKind kind)
    {
        // prod189.py apply_preset: {"options": {...}, "optionsof": {...}} with every value written through "%s:%s".
        using var doc = System.Text.Json.JsonDocument.Parse(GamePresetOptions.ToBenchJson(kind));
        foreach (var section in new[] { "options", "optionsof" })
        {
            Assert.Equal(System.Text.Json.JsonValueKind.Object, doc.RootElement.GetProperty(section).ValueKind);
            foreach (var p in doc.RootElement.GetProperty(section).EnumerateObject())
                Assert.Equal(System.Text.Json.JsonValueKind.String, p.Value.ValueKind); // a JSON true would print as "True"
        }
    }
}

public sealed class GamePresetWriterTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-preset-tests-" + Guid.NewGuid().ToString("N"));
    private readonly string _gameDir;
    private GamePhase _phase = GamePhase.Idle;
    private readonly GamePresetTarget _account;

    public GamePresetWriterTests()
    {
        _gameDir = Path.Combine(_root, "accounts", "offline");
        Directory.CreateDirectory(_gameDir);
        _account = new GamePresetTarget(_gameDir, () => _phase);
    }

    public void Dispose()
    {
        try
        {
            foreach (var f in Directory.EnumerateFiles(_root, "*", SearchOption.AllDirectories))
                File.SetAttributes(f, FileAttributes.Normal);
            Directory.Delete(_root, recursive: true);
        }
        catch (IOException) { }
    }

    // ---- fixtures: what a real 1.8.9 + OptiFine + Origin folder looks like (CRLF, like Windows Java writes) ----

    private const string OptionsTxt =
        "invertYMouse:false\r\nmouseSensitivity:0.5\r\nfov:0.0\r\ngamma:0.0\r\nsaturation:0.0\r\nrenderDistance:12\r\nguiScale:0\r\n"
        + "particles:0\r\nbobView:true\r\nanaglyph3d:false\r\nmaxFps:120\r\nfboEnable:true\r\ndifficulty:2\r\nfancyGraphics:true\r\n"
        + "ao:2\r\nrenderClouds:true\r\nresourcePacks:[]\r\nlastServer:mc.example.net:25565\r\nlang:en_US\r\nsomeFutureOption:42\r\n"
        + "key_key.forward:17\r\nkey_key.jump:57\r\n";

    private static string OptionsOf()
    {
        var lines = File.ReadAllLines(RepoFiles.Path_("tools", "bench", "optifine-keys-189.txt"))
            .Where(l => l.Trim().Length > 0 && !l.TrimStart().StartsWith('#'));
        return string.Join("\r\n", lines) + "\r\n";
    }

    private const string OriginJson =
        "{\n  \"mods\": {\n    \"particles\": {\n      \"mode\": \"All\",\n      \"enabled\": false\n    },\n    \"@general\": {}\n  },\n"
        + "  \"hud\": {\n    \"fps\": [\n      0.0,\n      4.0,\n      4.0,\n      1.0,\n      0.0\n    ]\n  },\n"
        + "  \"meta\": {\n    \"panelBacking\": false\n  }\n}";

    private string P(params string[] parts) => Path.Combine(new[] { _gameDir }.Concat(parts).ToArray());
    private string OptionsPath => P("options.txt");
    private string OfPath => P("optionsof.txt");
    private string OriginPath => P("config", "originclient-mods.json");
    private string UndoPath => P("origin-preset-undo.json");

    private void WriteFiles(bool options = true, bool of = true, bool origin = true)
    {
        if (options) File.WriteAllText(OptionsPath, OptionsTxt, new UTF8Encoding(false));
        if (of) File.WriteAllText(OfPath, OptionsOf(), new UTF8Encoding(false));
        if (origin)
        {
            Directory.CreateDirectory(P("config"));
            File.WriteAllText(OriginPath, OriginJson, new UTF8Encoding(false));
        }
    }

    private byte[]?[] Snapshot() => new[] { OptionsPath, OfPath, OriginPath }.Select(p => File.Exists(p) ? File.ReadAllBytes(p) : null).ToArray();

    private static void AssertSame(byte[]?[] a, byte[]?[] b)
    {
        for (var i = 0; i < a.Length; i++)
        {
            if (a[i] == null || b[i] == null) Assert.Equal(a[i] == null, b[i] == null);
            else Assert.Equal(a[i], b[i]);
        }
    }

    private static Dictionary<string, string> Map(string text) =>
        text.Split('\n').Select(l => l.TrimEnd('\r')).Where(l => l.IndexOf(':') > 0)
            .GroupBy(l => l[..l.IndexOf(':')]).ToDictionary(g => g.Key, g => g.Last()[(g.Last().IndexOf(':') + 1)..]);

    // ---- preview ----

    [Fact]
    public void PreviewListsKeyCurrentAndNewAndWritesNothing()
    {
        WriteFiles();
        var before = Snapshot();
        var preview = GamePresetWriter.Preview(_account, GamePresetKind.Competitive);

        Assert.Null(preview.Blocked);
        var rd = preview.Changes.Single(c => c.File == GamePresetFile.Options && c.Key == "renderDistance");
        Assert.Equal("12", rd.Current);
        Assert.Equal("8", rd.New);
        var mip = preview.Changes.Single(c => c.Key == "mipmapLevels");
        Assert.Null(mip.Current); // not in the file yet
        var dist = preview.Changes.Single(c => c.Key == "@performance.entityDistance");
        Assert.Equal("100", dist.Current); // absent in the config = the mod's default
        Assert.Equal("75", dist.New);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath));
    }

    [Fact]
    public void PreviewOnlyListsKeysThatDiffer()
    {
        WriteFiles();
        var preview = GamePresetWriter.Preview(_account, GamePresetKind.Quality);
        Assert.DoesNotContain(preview.Changes, c => c.Key == "fancyGraphics"); // already true
        Assert.DoesNotContain(preview.Changes, c => c.Key == "ao");            // already 2
    }

    // ---- apply ----

    [Fact]
    public void ApplyWritesOnlyChangedKeysAndKeepsEveryOtherLineInOrder()
    {
        WriteFiles();
        var result = GamePresetWriter.Apply(_account, GamePresetKind.Competitive);
        Assert.Equal(GamePresetStatus.Done, result.Status);

        var lines = File.ReadAllText(OptionsPath).Split("\r\n");
        var original = OptionsTxt.Split("\r\n");
        // Every original line is still there, in order; changed ones are replaced in place.
        var keysInOrder = original.Where(l => l.Length > 0).Select(l => l[..l.IndexOf(':')]).ToList();
        var nowKeys = lines.Where(l => l.Length > 0).Select(l => l[..l.IndexOf(':')]).ToList();
        Assert.Equal(keysInOrder, nowKeys.Take(keysInOrder.Count).ToList());
        var map = Map(File.ReadAllText(OptionsPath));
        Assert.Equal("8", map["renderDistance"]);
        Assert.Equal("false", map["fancyGraphics"]);
        Assert.Equal("0", map["ao"]);
        Assert.Equal("0", map["mipmapLevels"]); // appended
        Assert.Equal("42", map["someFutureOption"]);
        Assert.Equal("mc.example.net:25565", map["lastServer"]);
        Assert.Equal("17", map["key_key.forward"]);
        Assert.Equal("120", map["maxFps"]); // not a preset key: untouched
        Assert.DoesNotContain("\n\n", File.ReadAllText(OptionsPath).Replace("\r\n", "\n"));
        Assert.True(File.ReadAllText(OptionsPath).Replace("\r\n", "").IndexOf('\r') < 0);
        // CRLF stays CRLF: no bare LF appeared.
        Assert.Equal(File.ReadAllText(OptionsPath).Count(c => c == '\n'), File.ReadAllText(OptionsPath).Count(c => c == '\r'));
    }

    [Fact]
    public void ApplyWritesOptiFineAndOriginKeys()
    {
        WriteFiles();
        GamePresetWriter.Apply(_account, GamePresetKind.Competitive);
        var of = Map(File.ReadAllText(OfPath));
        Assert.Equal("true", of["ofFastMath"]);
        Assert.Equal("false", of["ofFastRender"]);
        Assert.Equal("3", of["ofFogType"]);
        Assert.Equal("false", of["ofSky"]);
        Assert.Equal("46", of["key_of.key.zoom"]); // not ours: untouched
        Assert.Equal(of.Count, Map(OptionsOf()).Count); // no key added or lost

        using var json = System.Text.Json.JsonDocument.Parse(File.ReadAllText(OriginPath));
        var mods = json.RootElement.GetProperty("mods");
        Assert.Equal(75, mods.GetProperty("@performance").GetProperty("entityDistance").GetInt32());
        Assert.Equal(50, mods.GetProperty("@performance").GetProperty("tileEntityDistance").GetInt32());
        Assert.False(mods.GetProperty("@general").GetProperty("cosmeticEffects").GetBoolean());
        Assert.True(mods.GetProperty("particles").GetProperty("enabled").GetBoolean());
        Assert.Equal("All", mods.GetProperty("particles").GetProperty("mode").GetString()); // sibling key kept
        Assert.Equal(5, json.RootElement.GetProperty("hud").GetProperty("fps").GetArrayLength()); // the layout is carried through
        Assert.False(json.RootElement.GetProperty("meta").GetProperty("panelBacking").GetBoolean());
    }

    [Fact]
    public void ApplyWritesTheUndoRecordFirst()
    {
        WriteFiles();
        GamePresetWriter.Apply(_account, GamePresetKind.Balanced);
        Assert.True(File.Exists(UndoPath));
        var info = GamePresetWriter.PendingUndo(_account);
        Assert.NotNull(info);
        Assert.Equal(GamePresetKind.Balanced, info!.Kind);
        // No temp files are left behind.
        Assert.DoesNotContain(Directory.EnumerateFiles(_root, "*", SearchOption.AllDirectories), f => f.EndsWith(".tmp"));
    }

    [Fact]
    public void ApplyingTheSamePresetAgainChangesNothingAndKeepsTheFirstUndo()
    {
        WriteFiles();
        var original = Snapshot();
        GamePresetWriter.Apply(_account, GamePresetKind.Quality);
        var again = GamePresetWriter.Apply(_account, GamePresetKind.Quality);
        Assert.Equal(GamePresetStatus.NothingToChange, again.Status);
        Assert.True(again.Ok);
        Assert.True(GamePresetWriter.Undo(_account).Ok);
        AssertSame(original, Snapshot());
    }

    // ---- undo ----

    [Theory]
    [InlineData(GamePresetKind.Competitive)]
    [InlineData(GamePresetKind.Balanced)]
    [InlineData(GamePresetKind.Quality)]
    public void ApplyThenUndoIsByteIdentical(GamePresetKind kind)
    {
        WriteFiles();
        var before = Snapshot();
        Assert.Equal(GamePresetStatus.Done, GamePresetWriter.Apply(_account, kind).Status);
        Assert.NotEqual(Convert.ToHexString(before[0]!), Convert.ToHexString(File.ReadAllBytes(OptionsPath)));

        var undo = GamePresetWriter.Undo(_account);
        Assert.Equal(GamePresetStatus.Done, undo.Status);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath));
        Assert.Null(GamePresetWriter.PendingUndo(_account));
    }

    [Fact]
    public void UndoRestoresKeysExactlyEvenAfterTheGameRewroteTheFiles()
    {
        WriteFiles();
        GamePresetWriter.Apply(_account, GamePresetKind.Competitive);

        // The player ran the game: it re-saved options.txt with a changed unrelated key and a new line, the order moved,
        // and Origin reformatted its config. The file no longer matches what we wrote, so Undo works key by key.
        var text = File.ReadAllText(OptionsPath).Replace("maxFps:120", "maxFps:260") + "newVanillaOption:true\r\n";
        File.WriteAllText(OptionsPath, text);
        File.WriteAllText(OriginPath, File.ReadAllText(OriginPath).Replace("\n", "\r\n"));

        Assert.True(GamePresetWriter.Undo(_account).Ok);

        var map = Map(File.ReadAllText(OptionsPath));
        var orig = Map(OptionsTxt);
        foreach (var key in new[] { "renderDistance", "fancyGraphics", "ao", "particles", "renderClouds" })
            Assert.Equal(orig[key], map[key]);
        Assert.False(map.ContainsKey("mipmapLevels"));  // it did not exist before: gone again
        Assert.False(map.ContainsKey("entityShadows"));
        Assert.Equal("260", map["maxFps"]);              // the player's later change survives
        Assert.Equal("true", map["newVanillaOption"]);
        Assert.Equal(Map(OptionsOf()), Map(File.ReadAllText(OfPath)));

        using var json = System.Text.Json.JsonDocument.Parse(File.ReadAllText(OriginPath));
        var mods = json.RootElement.GetProperty("mods");
        Assert.False(mods.GetProperty("particles").GetProperty("enabled").GetBoolean());
        Assert.False(mods.GetProperty("particles").TryGetProperty("hideBlockBreak", out _)); // absent before
        Assert.False(mods.GetProperty("@performance").TryGetProperty("entityDistance", out _));
        Assert.False(File.Exists(UndoPath));
    }

    [Fact]
    public void UndoWithNothingToUndoSaysSoAndTouchesNothing()
    {
        WriteFiles();
        var before = Snapshot();
        var r = GamePresetWriter.Undo(_account);
        Assert.Equal(GamePresetStatus.Failed, r.Status);
        Assert.Contains("nothing to undo", r.Message, StringComparison.OrdinalIgnoreCase);
        AssertSame(before, Snapshot());
    }

    // ---- refusal while the game runs ----

    [Theory]
    [InlineData(GamePhase.Preparing)]
    [InlineData(GamePhase.Starting)]
    [InlineData(GamePhase.Running)]
    public void RefusesWhileTheAccountsGameIsUp(GamePhase phase)
    {
        WriteFiles();
        var before = Snapshot();
        _phase = phase;

        var preview = GamePresetWriter.Preview(_account, GamePresetKind.Competitive);
        Assert.NotNull(preview.Blocked);
        var r = GamePresetWriter.Apply(_account, GamePresetKind.Competitive);
        Assert.Equal(GamePresetStatus.Refused, r.Status);
        Assert.Contains("1.8.9", r.Message);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath));

        _phase = GamePhase.Idle;
        Assert.Equal(GamePresetStatus.Done, GamePresetWriter.Apply(_account, GamePresetKind.Competitive).Status);
        _phase = phase;
        var after = Snapshot();
        Assert.Equal(GamePresetStatus.Refused, GamePresetWriter.Undo(_account).Status);
        AssertSame(after, Snapshot());
        Assert.True(File.Exists(UndoPath));
    }

    [Fact]
    public void ARunningGameInAnotherVersionDoesNotBlockThisOne()
    {
        // GamePresetTarget.ForAccount only reports a phase while the account's game is 1.8.9.
        var book = new AccountLaunchBook();
        var ticket = book.BeginPrepare("offline", "1.21.1");
        Assert.NotNull(ticket);
        var target = GamePresetTarget.ForAccount(book, "offline");
        Assert.Equal(GamePhase.Idle, target.Phase());
        book.EndPrepare(ticket!);

        var t189 = book.BeginPrepare("offline", "1.8.9");
        Assert.Equal(GamePhase.Preparing, GamePresetTarget.ForAccount(book, "offline").Phase());
        book.EndPrepare(t189!);
    }

    [Fact]
    public void AGameThatStartsBetweenTheCheckAndTheWriteIsStillRefused()
    {
        WriteFiles();
        var before = Snapshot();
        var calls = 0;
        var racing = new GamePresetTarget(_gameDir, () => ++calls <= 1 ? GamePhase.Idle : GamePhase.Running);
        var r = GamePresetWriter.Apply(racing, GamePresetKind.Competitive);
        Assert.Equal(GamePresetStatus.Refused, r.Status);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath));
    }

    // ---- missing and damaged files ----

    [Fact]
    public void AMissingOptionsOfIsCreatedWithJustThePresetKeysAndUndoDeletesIt()
    {
        WriteFiles(of: false);
        var preview = GamePresetWriter.Preview(_account, GamePresetKind.Balanced);
        Assert.Contains(preview.Notes, n => n.Contains("optionsof.txt"));

        Assert.Equal(GamePresetStatus.Done, GamePresetWriter.Apply(_account, GamePresetKind.Balanced).Status);
        Assert.True(File.Exists(OfPath));
        var of = Map(File.ReadAllText(OfPath));
        Assert.Equal("true", of["ofFastMath"]);
        Assert.Equal("1", of["ofClouds"]);
        Assert.DoesNotContain("ofShowFps", of.Keys); // only preset keys: OptiFine fills in the rest itself
        Assert.EndsWith("\n", File.ReadAllText(OfPath));

        Assert.True(GamePresetWriter.Undo(_account).Ok);
        Assert.False(File.Exists(OfPath));
    }

    [Fact]
    public void ABrandNewFolderGetsBothFilesAndUndoRemovesBoth()
    {
        // Nothing in the folder yet: options.txt and optionsof.txt are created, the Origin config is not.
        var r = GamePresetWriter.Apply(_account, GamePresetKind.Quality);
        Assert.Equal(GamePresetStatus.Done, r.Status);
        Assert.True(File.Exists(OptionsPath));
        Assert.True(File.Exists(OfPath));
        Assert.False(File.Exists(OriginPath));
        Assert.True(GamePresetWriter.Undo(_account).Ok);
        Assert.False(File.Exists(OptionsPath));
        Assert.False(File.Exists(OfPath));
    }

    [Fact]
    public void TheOriginConfigIsNeverCreatedBecauseTheModSeedsItsDefaultsOnlyWhenItIsAbsent()
    {
        WriteFiles(origin: false);
        var preview = GamePresetWriter.Preview(_account, GamePresetKind.Competitive);
        Assert.Contains(preview.Notes, n => n.Contains("Origin's own settings"));
        Assert.DoesNotContain(preview.Changes, c => c.File == GamePresetFile.Origin);
        Assert.True(GamePresetWriter.Apply(_account, GamePresetKind.Competitive).Ok);
        Assert.False(File.Exists(OriginPath));
        // The label ignores Origin keys it could not apply, so the applied preset still reads as itself.
        Assert.Equal("Competitive", GamePresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void AMissingGameFolderIsNotCreated()
    {
        var gone = new GamePresetTarget(Path.Combine(_root, "accounts", "never-played"), () => GamePhase.Idle);
        var r = GamePresetWriter.Apply(gone, GamePresetKind.Balanced);
        Assert.Equal(GamePresetStatus.Failed, r.Status);
        Assert.False(Directory.Exists(gone.GameDir)); // AccountSeed seeds a folder only while it does not exist
        Assert.Equal("Custom", GamePresetWriter.CurrentLabel(gone));
    }

    [Fact]
    public void ADamagedOptionsFileIsLeftAloneAndNothingIsWritten()
    {
        WriteFiles();
        File.WriteAllBytes(OptionsPath, new byte[64]); // a crash mid-write: all NUL
        var before = Snapshot();
        var r = GamePresetWriter.Apply(_account, GamePresetKind.Competitive);
        Assert.Equal(GamePresetStatus.Failed, r.Status);
        Assert.Contains("options.txt", r.Message);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath));
        Assert.Equal("Custom", GamePresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void ADamagedOriginConfigJustLeavesOriginSettingsOut()
    {
        WriteFiles();
        Directory.CreateDirectory(P("config"));
        File.WriteAllText(OriginPath, "{ not json");
        var r = GamePresetWriter.Apply(_account, GamePresetKind.Competitive);
        Assert.Equal(GamePresetStatus.Done, r.Status);
        Assert.Equal("{ not json", File.ReadAllText(OriginPath));
        Assert.Equal("8", Map(File.ReadAllText(OptionsPath))["renderDistance"]);
        Assert.True(GamePresetWriter.Undo(_account).Ok);
        Assert.Equal("{ not json", File.ReadAllText(OriginPath));
    }

    // ---- the undo file itself ----

    [Theory]
    [InlineData("")]
    [InlineData("{ nope")]
    [InlineData("{\"schema\":1}")]
    [InlineData("{\"schema\":99,\"version\":\"1.8.9\",\"kind\":\"Balanced\",\"appliedUtc\":\"2026-01-01T00:00:00Z\",\"files\":[]}")]
    [InlineData("{\"schema\":1,\"version\":\"1.8.9\",\"kind\":\"Nonsense\",\"appliedUtc\":\"2026-01-01T00:00:00Z\",\"files\":[]}")]
    [InlineData("{\"schema\":1,\"version\":\"1.8.9\",\"kind\":\"Balanced\",\"appliedUtc\":\"x\",\"files\":[]}")]
    public void ACorruptUndoFileLosesNothing(string content)
    {
        WriteFiles();
        File.WriteAllText(UndoPath, content);
        var before = Snapshot();

        Assert.Null(GamePresetWriter.PendingUndo(_account));
        var r = GamePresetWriter.Undo(_account);
        Assert.Equal(GamePresetStatus.Failed, r.Status);
        Assert.Contains("damaged", r.Message);
        AssertSame(before, Snapshot());
        Assert.Equal(content, File.ReadAllText(UndoPath)); // left in place

        // Applying a preset again starts a fresh, working undo and keeps the damaged one aside.
        Assert.Equal(GamePresetStatus.Done, GamePresetWriter.Apply(_account, GamePresetKind.Competitive).Status);
        Assert.True(File.Exists(UndoPath + ".bad"));
        Assert.True(GamePresetWriter.Undo(_account).Ok);
        AssertSame(before, Snapshot());
    }

    [Fact]
    public void ATruncatedUndoFileLosesNothing()
    {
        WriteFiles();
        var before = Snapshot();
        GamePresetWriter.Apply(_account, GamePresetKind.Quality);
        var good = File.ReadAllText(UndoPath);
        var afterApply = Snapshot();
        File.WriteAllText(UndoPath, good[..(good.Length / 2)]);

        Assert.Equal(GamePresetStatus.Failed, GamePresetWriter.Undo(_account).Status);
        AssertSame(afterApply, Snapshot());
        _ = before;
    }

    [Fact]
    public void AnUndoRecordCannotReachOutsideTheThreeFiles()
    {
        // A hand-edited record naming an unknown file is rejected as damaged, not followed.
        WriteFiles();
        File.WriteAllText(UndoPath, "{\"schema\":1,\"version\":\"1.8.9\",\"kind\":\"Balanced\",\"appliedUtc\":\"2026-01-01T00:00:00Z\","
            + "\"files\":[{\"file\":\"..\\\\..\\\\evil.txt\",\"existed\":false,\"writtenSha256\":\"x\",\"keys\":[]}]}");
        var before = Snapshot();
        Assert.Equal(GamePresetStatus.Failed, GamePresetWriter.Undo(_account).Status);
        AssertSame(before, Snapshot());
    }

    [Fact]
    public void AFailedWriteRestoresTheFilesAlreadyWritten()
    {
        if (!OperatingSystem.IsWindows()) return; // a read-only target blocks the rename only on Windows
        WriteFiles();
        File.SetAttributes(OfPath, FileAttributes.ReadOnly); // options.txt is written first, then this one fails
        var before = Snapshot();
        var r = GamePresetWriter.Apply(_account, GamePresetKind.Competitive);
        Assert.Equal(GamePresetStatus.Failed, r.Status);
        File.SetAttributes(OfPath, FileAttributes.Normal);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath)); // nothing changed, so nothing to undo
    }

    // ---- the label ----

    [Fact]
    public void LabelIsCustomUntilAPresetIsAppliedThenNamesIt()
    {
        WriteFiles();
        Assert.Equal("Custom", GamePresetWriter.CurrentLabel(_account));
        foreach (var kind in new[] { GamePresetKind.Competitive, GamePresetKind.Balanced, GamePresetKind.Quality, GamePresetKind.Competitive })
        {
            Assert.True(GamePresetWriter.Apply(_account, kind).Ok);
            Assert.Equal(kind.ToString(), GamePresetWriter.CurrentLabel(_account));
        }
    }

    [Fact]
    public void LabelGoesCustomWhenAnyPresetKeyIsChanged()
    {
        WriteFiles();
        GamePresetWriter.Apply(_account, GamePresetKind.Balanced);
        Assert.Equal("Balanced", GamePresetWriter.CurrentLabel(_account));

        File.WriteAllText(OptionsPath, File.ReadAllText(OptionsPath).Replace("renderDistance:10", "renderDistance:11"));
        Assert.Equal("Custom", GamePresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void LabelSeesThroughHowMinecraftRewritesNumbers()
    {
        WriteFiles();
        GamePresetWriter.Apply(_account, GamePresetKind.Quality);
        // 1.8.9 reads "ao:true" as 2; a float-looking int is the same number.
        File.WriteAllText(OptionsPath, File.ReadAllText(OptionsPath).Replace("ao:2", "ao:true").Replace("renderDistance:14", "renderDistance:14.0"));
        Assert.Equal("Quality", GamePresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void LabelChangesWhenAnOriginKeyDiffers()
    {
        WriteFiles();
        GamePresetWriter.Apply(_account, GamePresetKind.Competitive);
        File.WriteAllText(OriginPath, File.ReadAllText(OriginPath).Replace("\"entityDistance\": 75", "\"entityDistance\": 90"));
        Assert.Equal("Custom", GamePresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void BalancedOnAPristineOriginConfigWritesNothingThereBecauseTheDefaultsAlreadyMatch()
    {
        WriteFiles();
        var originBefore = File.ReadAllBytes(OriginPath);
        // Every Origin key Balanced wants is the mod's default except one: it switches the particle changer on
        // (pass-through) so a later switch from Competitive is order-independent. The fixture has it off.
        var originChanges = GamePresetWriter.Preview(_account, GamePresetKind.Balanced).Changes.Where(c => c.File == GamePresetFile.Origin).ToList();
        Assert.Equal(new[] { "particles.enabled" }, originChanges.Select(c => c.Key).ToArray());
        Assert.True(GamePresetWriter.Apply(_account, GamePresetKind.Balanced).Ok);
        Assert.NotEqual(originBefore, File.ReadAllBytes(OriginPath));
        // Going back from Competitive to Balanced writes the defaults explicitly, so the numbers really come back.
        GamePresetWriter.Apply(_account, GamePresetKind.Competitive);
        GamePresetWriter.Apply(_account, GamePresetKind.Balanced);
        using var json = System.Text.Json.JsonDocument.Parse(File.ReadAllText(OriginPath));
        Assert.Equal(100, json.RootElement.GetProperty("mods").GetProperty("@performance").GetProperty("entityDistance").GetInt32());
        Assert.Equal("Balanced", GamePresetWriter.CurrentLabel(_account));
    }

    // ---- charset: 1.8.9 writes options.txt in the JVM default charset (Cp1252 on Windows), not UTF-8 ----

    private static readonly Encoding Cp = Encoding.Latin1;

    // Cafe with an acute e (0xE9), u-umlaut (0xFC), sharp s (0xDF) and the Cp1252 euro sign (0x80): none is valid UTF-8 here.
    private const string NonAscii = "Caf\u00E9 \u00FC\u00DF \u0080 pack";

    private void WriteNonAsciiFiles()
    {
        File.WriteAllBytes(OptionsPath, Cp.GetBytes(OptionsTxt.Replace("resourcePacks:[]", "resourcePacks:[\"" + NonAscii + ".zip\"]")
            .Replace("lastServer:mc.example.net:25565", "lastServer:" + NonAscii)));
        File.WriteAllBytes(OfPath, Cp.GetBytes(OptionsOf()));
        Directory.CreateDirectory(P("config"));
        File.WriteAllText(OriginPath, OriginJson, new UTF8Encoding(false));
    }

    private static bool Contains(byte[] haystack, byte[] needle) => haystack.AsSpan().IndexOf(needle) >= 0;

    [Theory]
    [InlineData(GamePresetKind.Competitive)]
    [InlineData(GamePresetKind.Quality)]
    public void NonAsciiPackAndServerNamesSurviveApplyAndUndoByteForByte(GamePresetKind kind)
    {
        WriteNonAsciiFiles();
        var before = Snapshot();
        Assert.True(GamePresetWriter.Apply(_account, kind).Ok);

        var applied = File.ReadAllBytes(OptionsPath);
        Assert.True(Contains(applied, Cp.GetBytes(NonAscii + ".zip")), "the pack name's raw bytes were rewritten");
        Assert.True(Contains(applied, Cp.GetBytes("lastServer:" + NonAscii)));
        Assert.False(Contains(applied, new byte[] { 0xEF, 0xBF, 0xBD }), "no UTF-8 replacement characters");

        Assert.True(GamePresetWriter.Undo(_account).Ok);
        AssertSame(before, Snapshot());
    }

    [Fact]
    public void NonAsciiBytesSurviveAKeyByKeyUndoToo()
    {
        WriteNonAsciiFiles();
        GamePresetWriter.Apply(_account, GamePresetKind.Competitive);
        // The game re-saved the file, so Undo can't use the byte-exact path.
        File.AppendAllText(OptionsPath, "newVanillaOption:true\r\n", Cp);
        Assert.True(GamePresetWriter.Undo(_account).Ok);
        var after = File.ReadAllBytes(OptionsPath);
        Assert.True(Contains(after, Cp.GetBytes("lastServer:" + NonAscii)));
        Assert.True(Contains(after, Cp.GetBytes(NonAscii + ".zip")));
        Assert.True(Contains(after, Cp.GetBytes("newVanillaOption:true")));
    }

    // ---- an earlier undo is never lost to a later Apply that does not finish ----

    private string UndoText => File.ReadAllText(UndoPath);

    [Fact]
    public void ALateRefusalKeepsTheEarlierUndoRecord()
    {
        WriteFiles();
        Assert.True(GamePresetWriter.Apply(_account, GamePresetKind.Balanced).Ok);
        var undoBefore = UndoText;
        var filesBefore = Snapshot();

        var calls = 0;
        var racing = new GamePresetTarget(_gameDir, () => ++calls <= 1 ? GamePhase.Idle : GamePhase.Running);
        Assert.Equal(GamePresetStatus.Refused, GamePresetWriter.Apply(racing, GamePresetKind.Competitive).Status);

        Assert.Equal(undoBefore, UndoText);
        Assert.False(File.Exists(UndoPath + ".new"));
        AssertSame(filesBefore, Snapshot());
        Assert.Equal(GamePresetKind.Balanced, GamePresetWriter.PendingUndo(_account)!.Kind);
    }

    [Fact]
    public void ARolledBackWriteKeepsTheEarlierUndoRecord()
    {
        if (!OperatingSystem.IsWindows()) return; // a read-only target blocks the rename only on Windows
        WriteFiles();
        Assert.True(GamePresetWriter.Apply(_account, GamePresetKind.Balanced).Ok);
        var undoBefore = UndoText;
        var filesBefore = Snapshot();

        File.SetAttributes(OfPath, FileAttributes.ReadOnly);
        Assert.Equal(GamePresetStatus.Failed, GamePresetWriter.Apply(_account, GamePresetKind.Competitive).Status);
        File.SetAttributes(OfPath, FileAttributes.Normal);

        Assert.Equal(undoBefore, UndoText);
        Assert.False(File.Exists(UndoPath + ".new"));
        AssertSame(filesBefore, Snapshot());
        Assert.True(GamePresetWriter.Undo(_account).Ok); // and that earlier undo still works
    }

    [Fact]
    public void AnApplyCutOffBeforeItsRecordSwapIsStillUndoable()
    {
        WriteFiles();
        var before = Snapshot();
        Assert.True(GamePresetWriter.Apply(_account, GamePresetKind.Quality).Ok);
        // Simulate dying after the files were written but before the pending record was swapped in.
        File.Move(UndoPath, UndoPath + ".new");
        Assert.Equal(GamePresetKind.Quality, GamePresetWriter.PendingUndo(_account)!.Kind);
        Assert.True(GamePresetWriter.Undo(_account).Ok);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath + ".new"));
        Assert.False(File.Exists(UndoPath));
    }

    [Fact]
    public void AStalePendingRecordFromAnApplyThatNeverWroteIsDropped()
    {
        WriteFiles();
        Assert.True(GamePresetWriter.Apply(_account, GamePresetKind.Balanced).Ok);
        var undoBefore = UndoText;
        // A pending file that matches nothing on disk (a crash right after step 1, or junk).
        File.WriteAllText(UndoPath + ".new", "not a record");
        Assert.Equal(GamePresetKind.Balanced, GamePresetWriter.PendingUndo(_account)!.Kind);
        Assert.True(GamePresetWriter.Apply(_account, GamePresetKind.Quality).Ok);
        Assert.False(File.Exists(UndoPath + ".new"));
        Assert.NotEqual(undoBefore, UndoText);
    }

    // ---- double click ----

    [Fact]
    public async Task TwoAppliesAtOnceRunOneAfterTheOtherAndTheSecondChangesNothing()
    {
        WriteFiles();
        var before = Snapshot();
        var start = new ManualResetEventSlim(false);
        var tasks = Enumerable.Range(0, 2).Select(_ => Task.Run(() =>
        {
            start.Wait();
            return GamePresetWriter.Apply(_account, GamePresetKind.Competitive).Status;
        })).ToArray();
        start.Set();
        await Task.WhenAll(tasks);
        var statuses = tasks.Select(t => t.Result).OrderBy(x => x).ToArray();
        Assert.Equal(new[] { GamePresetStatus.Done, GamePresetStatus.NothingToChange }.OrderBy(x => x).ToArray(), statuses);
        // The first run's undo record is the one that stayed, so Undo returns the files to the very start.
        Assert.True(GamePresetWriter.Undo(_account).Ok);
        AssertSame(before, Snapshot());
    }
}
