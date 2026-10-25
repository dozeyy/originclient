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

public class PresetOptionsTests
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
    [InlineData(PresetKind.Competitive)]
    [InlineData(PresetKind.Balanced)]
    [InlineData(PresetKind.Quality)]
    public void EveryOptiFineKeyIsVerified(PresetKind kind)
    {
        var verified = VerifiedOptiFineKeys();
        var optifine = PresetOptions.For(kind, "1.8.9").Where(e => e.File == PresetFile.OptiFine).ToList();
        Assert.NotEmpty(optifine);
        var unverified = optifine.Where(e => !verified.Contains(e.Key)).Select(e => e.Key).ToList();
        Assert.True(unverified.Count == 0, "unverified OptiFine keys: " + string.Join(", ", unverified));
        // Nothing that looks like an OptiFine key may hide in the vanilla or Origin groups either.
        Assert.DoesNotContain(PresetOptions.For(kind, "1.8.9"), e => e.File != PresetFile.OptiFine && e.Key.StartsWith("of", StringComparison.Ordinal)
            && e.Key.Length > 2 && char.IsUpper(e.Key[2]));
    }

    [Theory]
    [InlineData(PresetKind.Competitive)]
    [InlineData(PresetKind.Balanced)]
    [InlineData(PresetKind.Quality)]
    public void EveryVanillaKeyIsOneThe189LoaderReads(PresetKind kind)
    {
        foreach (var e in PresetOptions.For(kind, "1.8.9").Where(e => e.File == PresetFile.Options))
            Assert.True(App.Core.Sync.OptionsTxtFormat.KnownIn(e.Key, "1.8.9"), e.Key + " is not a 1.8.9 options.txt key");
    }

    [Fact]
    public void EveryOriginKeyExistsInTheMod()
    {
        // Mods.java is the schema the mod reads its config with: a typo'd key would be written and never read.
        var mods = File.ReadAllText(RepoFiles.Path_("src", "mods", "versions", "1.8.9", "src", "main", "java", "com", "origin", "client", "client", "mods", "Mods.java"));
        foreach (var kind in Enum.GetValues<PresetKind>())
            foreach (var e in PresetOptions.For(kind, "1.8.9").Where(e => e.File == PresetFile.Origin))
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
        foreach (var kind in Enum.GetValues<PresetKind>())
        {
            var keys = PresetOptions.For(kind, "1.8.9").Select(e => e.File + "/" + e.Key).ToList();
            Assert.Equal(keys.Count, keys.Distinct().Count());
        }
    }

    [Fact]
    public void OnlyVersion189HasABundle()
    {
        Assert.True(PresetOptions.IsSupported("1.8.9"));
        Assert.False(PresetOptions.IsSupported("1.21.1"));
        Assert.Throws<ArgumentOutOfRangeException>(() => PresetOptions.For(PresetKind.Balanced, "1.21.1"));
    }

    [Fact]
    public void FastRenderIsOffInEveryPreset()
    {
        // Not render-checked against MenuBlur / ColorGrade / MotionBlur yet (see the comment in PresetOptions).
        foreach (var kind in Enum.GetValues<PresetKind>())
            Assert.Equal("false", PresetOptions.For(kind, "1.8.9").Single(e => e.Key == "ofFastRender").Value);
    }

    [Fact]
    public void InventoryBlurIsOffInCompetitiveAndOnInTheOthers()
    {
        string Blur(PresetKind k) => PresetOptions.For(k, "1.8.9").Single(e => e.Key == "menublur.inventory").Value;
        Assert.Equal("false", Blur(PresetKind.Competitive));
        Assert.Equal("true", Blur(PresetKind.Balanced));
        Assert.Equal("true", Blur(PresetKind.Quality));
    }

    [Fact]
    public void CompetitiveKeepsCritParticlesAndTheOriginTogglesAreThere()
    {
        var c = PresetOptions.For(PresetKind.Competitive, "1.8.9");
        Assert.Equal("1", c.Single(e => e.Key == "particles" && e.File == PresetFile.Options).Value); // decreased, not minimal
        Assert.DoesNotContain(c, e => e.Key == "particles.mode"); // Particle Changer "Reduced" would drop crits
        foreach (var key in new[] { "@general.cosmeticEffects", "@performance.entityDistance", "@performance.tileEntityDistance", "particles.enabled" })
            Assert.Contains(c, e => e.Key == key);
    }

    [Fact]
    public void TheThreePresetsDiffer()
    {
        string Sig(PresetKind k) => string.Join("|", PresetOptions.For(k, "1.8.9").Select(e => e.Key + "=" + e.Value));
        Assert.Equal(3, Enum.GetValues<PresetKind>().Select(Sig).Distinct().Count());
    }
}

public class PresetFilesTests
{
    // tools/bench/presets-189/<name>.json is generated from the C# table (the one source). The test fails when a
    // checked-in file drifts; ORIGIN_REGEN_PRESETS=1 dotnet test --filter PresetFilesTests rewrites them.
    [Theory]
    [InlineData(PresetKind.Competitive, "competitive")]
    [InlineData(PresetKind.Balanced, "balanced")]
    [InlineData(PresetKind.Quality, "quality")]
    public void BenchJsonMatchesTheTable(PresetKind kind, string name)
    {
        var path = RepoFiles.Path_("tools", "bench", "presets-189", name + ".json");
        var expected = PresetOptions.ToBenchJson(kind);
        if (Environment.GetEnvironmentVariable("ORIGIN_REGEN_PRESETS") == "1")
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, expected, new UTF8Encoding(false));
        }
        Assert.True(File.Exists(path), path + " is missing: run with ORIGIN_REGEN_PRESETS=1");
        Assert.Equal(expected.Replace("\r\n", "\n"), File.ReadAllText(path).Replace("\r\n", "\n"));
    }

    [Theory]
    [InlineData(PresetKind.Competitive)]
    [InlineData(PresetKind.Balanced)]
    [InlineData(PresetKind.Quality)]
    public void BenchJsonIsTheShapeProd189Reads(PresetKind kind)
    {
        // prod189.py apply_preset: {"options": {...}, "optionsof": {...}} with every value written through "%s:%s".
        using var doc = System.Text.Json.JsonDocument.Parse(PresetOptions.ToBenchJson(kind));
        foreach (var section in new[] { "options", "optionsof" })
        {
            Assert.Equal(System.Text.Json.JsonValueKind.Object, doc.RootElement.GetProperty(section).ValueKind);
            foreach (var p in doc.RootElement.GetProperty(section).EnumerateObject())
                Assert.Equal(System.Text.Json.JsonValueKind.String, p.Value.ValueKind); // a JSON true would print as "True"
        }
    }
}

public sealed class PresetWriterTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-preset-tests-" + Guid.NewGuid().ToString("N"));
    private readonly string _gameDir;
    private GamePhase _phase = GamePhase.Idle;
    private readonly PresetTarget _account;

    public PresetWriterTests()
    {
        _gameDir = Path.Combine(_root, "accounts", "offline");
        Directory.CreateDirectory(_gameDir);
        _account = new PresetTarget(_gameDir, () => _phase);
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
        var preview = PresetWriter.Preview(_account, PresetKind.Competitive);

        Assert.Null(preview.Blocked);
        var rd = preview.Changes.Single(c => c.File == PresetFile.Options && c.Key == "renderDistance");
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
        var preview = PresetWriter.Preview(_account, PresetKind.Quality);
        Assert.DoesNotContain(preview.Changes, c => c.Key == "fancyGraphics"); // already true
        Assert.DoesNotContain(preview.Changes, c => c.Key == "ao");            // already 2
    }

    // ---- apply ----

    [Fact]
    public void ApplyWritesOnlyChangedKeysAndKeepsEveryOtherLineInOrder()
    {
        WriteFiles();
        var result = PresetWriter.Apply(_account, PresetKind.Competitive);
        Assert.Equal(PresetStatus.Done, result.Status);

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
        PresetWriter.Apply(_account, PresetKind.Competitive);
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
        PresetWriter.Apply(_account, PresetKind.Balanced);
        Assert.True(File.Exists(UndoPath));
        var info = PresetWriter.PendingUndo(_account);
        Assert.NotNull(info);
        Assert.Equal(PresetKind.Balanced, info!.Kind);
        // No temp files are left behind.
        Assert.DoesNotContain(Directory.EnumerateFiles(_root, "*", SearchOption.AllDirectories), f => f.EndsWith(".tmp"));
    }

    [Fact]
    public void ApplyingTheSamePresetAgainChangesNothingAndKeepsTheFirstUndo()
    {
        WriteFiles();
        var original = Snapshot();
        PresetWriter.Apply(_account, PresetKind.Quality);
        var again = PresetWriter.Apply(_account, PresetKind.Quality);
        Assert.Equal(PresetStatus.NothingToChange, again.Status);
        Assert.True(again.Ok);
        Assert.True(PresetWriter.Undo(_account).Ok);
        AssertSame(original, Snapshot());
    }

    // ---- undo ----

    [Theory]
    [InlineData(PresetKind.Competitive)]
    [InlineData(PresetKind.Balanced)]
    [InlineData(PresetKind.Quality)]
    public void ApplyThenUndoIsByteIdentical(PresetKind kind)
    {
        WriteFiles();
        var before = Snapshot();
        Assert.Equal(PresetStatus.Done, PresetWriter.Apply(_account, kind).Status);
        Assert.NotEqual(Convert.ToHexString(before[0]!), Convert.ToHexString(File.ReadAllBytes(OptionsPath)));

        var undo = PresetWriter.Undo(_account);
        Assert.Equal(PresetStatus.Done, undo.Status);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath));
        Assert.Null(PresetWriter.PendingUndo(_account));
    }

    [Fact]
    public void UndoRestoresKeysExactlyEvenAfterTheGameRewroteTheFiles()
    {
        WriteFiles();
        PresetWriter.Apply(_account, PresetKind.Competitive);

        // The player ran the game: it re-saved options.txt with a changed unrelated key and a new line, the order moved,
        // and Origin reformatted its config. The file no longer matches what we wrote, so Undo works key by key.
        var text = File.ReadAllText(OptionsPath).Replace("maxFps:120", "maxFps:260") + "newVanillaOption:true\r\n";
        File.WriteAllText(OptionsPath, text);
        File.WriteAllText(OriginPath, File.ReadAllText(OriginPath).Replace("\n", "\r\n"));

        Assert.True(PresetWriter.Undo(_account).Ok);

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
        var r = PresetWriter.Undo(_account);
        Assert.Equal(PresetStatus.Failed, r.Status);
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

        var preview = PresetWriter.Preview(_account, PresetKind.Competitive);
        Assert.NotNull(preview.Blocked);
        var r = PresetWriter.Apply(_account, PresetKind.Competitive);
        Assert.Equal(PresetStatus.Refused, r.Status);
        Assert.Contains("1.8.9", r.Message);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath));

        _phase = GamePhase.Idle;
        Assert.Equal(PresetStatus.Done, PresetWriter.Apply(_account, PresetKind.Competitive).Status);
        _phase = phase;
        var after = Snapshot();
        Assert.Equal(PresetStatus.Refused, PresetWriter.Undo(_account).Status);
        AssertSame(after, Snapshot());
        Assert.True(File.Exists(UndoPath));
    }

    [Fact]
    public void ARunningGameInAnotherVersionDoesNotBlockThisOne()
    {
        // PresetTarget.ForAccount only reports a phase while the account's game is 1.8.9.
        var book = new AccountLaunchBook();
        var ticket = book.BeginPrepare("offline", "1.21.1");
        Assert.NotNull(ticket);
        var target = PresetTarget.ForAccount(book, "offline");
        Assert.Equal(GamePhase.Idle, target.Phase());
        book.EndPrepare(ticket!);

        var t189 = book.BeginPrepare("offline", "1.8.9");
        Assert.Equal(GamePhase.Preparing, PresetTarget.ForAccount(book, "offline").Phase());
        book.EndPrepare(t189!);
    }

    [Fact]
    public void AGameThatStartsBetweenTheCheckAndTheWriteIsStillRefused()
    {
        WriteFiles();
        var before = Snapshot();
        var calls = 0;
        var racing = new PresetTarget(_gameDir, () => ++calls <= 1 ? GamePhase.Idle : GamePhase.Running);
        var r = PresetWriter.Apply(racing, PresetKind.Competitive);
        Assert.Equal(PresetStatus.Refused, r.Status);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath));
    }

    // ---- missing and damaged files ----

    [Fact]
    public void AMissingOptionsOfIsCreatedWithJustThePresetKeysAndUndoDeletesIt()
    {
        WriteFiles(of: false);
        var preview = PresetWriter.Preview(_account, PresetKind.Balanced);
        Assert.Contains(preview.Notes, n => n.Contains("optionsof.txt"));

        Assert.Equal(PresetStatus.Done, PresetWriter.Apply(_account, PresetKind.Balanced).Status);
        Assert.True(File.Exists(OfPath));
        var of = Map(File.ReadAllText(OfPath));
        Assert.Equal("true", of["ofFastMath"]);
        Assert.Equal("1", of["ofClouds"]);
        Assert.DoesNotContain("ofShowFps", of.Keys); // only preset keys: OptiFine fills in the rest itself
        Assert.EndsWith("\n", File.ReadAllText(OfPath));

        Assert.True(PresetWriter.Undo(_account).Ok);
        Assert.False(File.Exists(OfPath));
    }

    [Fact]
    public void ABrandNewFolderGetsBothFilesAndUndoRemovesBoth()
    {
        // Nothing in the folder yet: options.txt and optionsof.txt are created, the Origin config is not.
        var r = PresetWriter.Apply(_account, PresetKind.Quality);
        Assert.Equal(PresetStatus.Done, r.Status);
        Assert.True(File.Exists(OptionsPath));
        Assert.True(File.Exists(OfPath));
        Assert.False(File.Exists(OriginPath));
        Assert.True(PresetWriter.Undo(_account).Ok);
        Assert.False(File.Exists(OptionsPath));
        Assert.False(File.Exists(OfPath));
    }

    [Fact]
    public void TheOriginConfigIsNeverCreatedBecauseTheModSeedsItsDefaultsOnlyWhenItIsAbsent()
    {
        WriteFiles(origin: false);
        var preview = PresetWriter.Preview(_account, PresetKind.Competitive);
        Assert.Contains(preview.Notes, n => n.Contains("Origin's own settings"));
        Assert.DoesNotContain(preview.Changes, c => c.File == PresetFile.Origin);
        Assert.True(PresetWriter.Apply(_account, PresetKind.Competitive).Ok);
        Assert.False(File.Exists(OriginPath));
        // The label ignores Origin keys it could not apply, so the applied preset still reads as itself.
        Assert.Equal("Competitive", PresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void AMissingGameFolderIsNotCreated()
    {
        var gone = new PresetTarget(Path.Combine(_root, "accounts", "never-played"), () => GamePhase.Idle);
        var r = PresetWriter.Apply(gone, PresetKind.Balanced);
        Assert.Equal(PresetStatus.Failed, r.Status);
        Assert.False(Directory.Exists(gone.GameDir)); // AccountSeed seeds a folder only while it does not exist
        Assert.Equal("Custom", PresetWriter.CurrentLabel(gone));
    }

    [Fact]
    public void ADamagedOptionsFileIsLeftAloneAndNothingIsWritten()
    {
        WriteFiles();
        File.WriteAllBytes(OptionsPath, new byte[64]); // a crash mid-write: all NUL
        var before = Snapshot();
        var r = PresetWriter.Apply(_account, PresetKind.Competitive);
        Assert.Equal(PresetStatus.Failed, r.Status);
        Assert.Contains("options.txt", r.Message);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath));
        Assert.Equal("Custom", PresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void ADamagedOriginConfigJustLeavesOriginSettingsOut()
    {
        WriteFiles();
        Directory.CreateDirectory(P("config"));
        File.WriteAllText(OriginPath, "{ not json");
        var r = PresetWriter.Apply(_account, PresetKind.Competitive);
        Assert.Equal(PresetStatus.Done, r.Status);
        Assert.Equal("{ not json", File.ReadAllText(OriginPath));
        Assert.Equal("8", Map(File.ReadAllText(OptionsPath))["renderDistance"]);
        Assert.True(PresetWriter.Undo(_account).Ok);
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

        Assert.Null(PresetWriter.PendingUndo(_account));
        var r = PresetWriter.Undo(_account);
        Assert.Equal(PresetStatus.Failed, r.Status);
        Assert.Contains("damaged", r.Message);
        AssertSame(before, Snapshot());
        Assert.Equal(content, File.ReadAllText(UndoPath)); // left in place

        // Applying a preset again starts a fresh, working undo and keeps the damaged one aside.
        Assert.Equal(PresetStatus.Done, PresetWriter.Apply(_account, PresetKind.Competitive).Status);
        Assert.True(File.Exists(UndoPath + ".bad"));
        Assert.True(PresetWriter.Undo(_account).Ok);
        AssertSame(before, Snapshot());
    }

    [Fact]
    public void ATruncatedUndoFileLosesNothing()
    {
        WriteFiles();
        var before = Snapshot();
        PresetWriter.Apply(_account, PresetKind.Quality);
        var good = File.ReadAllText(UndoPath);
        var afterApply = Snapshot();
        File.WriteAllText(UndoPath, good[..(good.Length / 2)]);

        Assert.Equal(PresetStatus.Failed, PresetWriter.Undo(_account).Status);
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
        Assert.Equal(PresetStatus.Failed, PresetWriter.Undo(_account).Status);
        AssertSame(before, Snapshot());
    }

    [Fact]
    public void AFailedWriteRestoresTheFilesAlreadyWritten()
    {
        if (!OperatingSystem.IsWindows()) return; // a read-only target blocks the rename only on Windows
        WriteFiles();
        File.SetAttributes(OfPath, FileAttributes.ReadOnly); // options.txt is written first, then this one fails
        var before = Snapshot();
        var r = PresetWriter.Apply(_account, PresetKind.Competitive);
        Assert.Equal(PresetStatus.Failed, r.Status);
        File.SetAttributes(OfPath, FileAttributes.Normal);
        AssertSame(before, Snapshot());
        Assert.False(File.Exists(UndoPath)); // nothing changed, so nothing to undo
    }

    // ---- the label ----

    [Fact]
    public void LabelIsCustomUntilAPresetIsAppliedThenNamesIt()
    {
        WriteFiles();
        Assert.Equal("Custom", PresetWriter.CurrentLabel(_account));
        foreach (var kind in new[] { PresetKind.Competitive, PresetKind.Balanced, PresetKind.Quality, PresetKind.Competitive })
        {
            Assert.True(PresetWriter.Apply(_account, kind).Ok);
            Assert.Equal(kind.ToString(), PresetWriter.CurrentLabel(_account));
        }
    }

    [Fact]
    public void LabelGoesCustomWhenAnyPresetKeyIsChanged()
    {
        WriteFiles();
        PresetWriter.Apply(_account, PresetKind.Balanced);
        Assert.Equal("Balanced", PresetWriter.CurrentLabel(_account));

        File.WriteAllText(OptionsPath, File.ReadAllText(OptionsPath).Replace("renderDistance:10", "renderDistance:11"));
        Assert.Equal("Custom", PresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void LabelSeesThroughHowMinecraftRewritesNumbers()
    {
        WriteFiles();
        PresetWriter.Apply(_account, PresetKind.Quality);
        // 1.8.9 reads "ao:true" as 2; a float-looking int is the same number.
        File.WriteAllText(OptionsPath, File.ReadAllText(OptionsPath).Replace("ao:2", "ao:true").Replace("renderDistance:14", "renderDistance:14.0"));
        Assert.Equal("Quality", PresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void LabelChangesWhenAnOriginKeyDiffers()
    {
        WriteFiles();
        PresetWriter.Apply(_account, PresetKind.Competitive);
        File.WriteAllText(OriginPath, File.ReadAllText(OriginPath).Replace("\"entityDistance\": 75", "\"entityDistance\": 90"));
        Assert.Equal("Custom", PresetWriter.CurrentLabel(_account));
    }

    [Fact]
    public void BalancedOnAPristineOriginConfigWritesNothingThereBecauseTheDefaultsAlreadyMatch()
    {
        WriteFiles();
        var originBefore = File.ReadAllBytes(OriginPath);
        // Every Origin key Balanced wants is the mod's default (the particle changer stays untouched).
        Assert.DoesNotContain(PresetWriter.Preview(_account, PresetKind.Balanced).Changes, c => c.File == PresetFile.Origin);
        Assert.True(PresetWriter.Apply(_account, PresetKind.Balanced).Ok);
        Assert.Equal(originBefore, File.ReadAllBytes(OriginPath));
        // Going back from Competitive to Balanced writes the defaults explicitly, so the numbers really come back.
        PresetWriter.Apply(_account, PresetKind.Competitive);
        PresetWriter.Apply(_account, PresetKind.Balanced);
        using var json = System.Text.Json.JsonDocument.Parse(File.ReadAllText(OriginPath));
        Assert.Equal(100, json.RootElement.GetProperty("mods").GetProperty("@performance").GetProperty("entityDistance").GetInt32());
        Assert.Equal("Balanced", PresetWriter.CurrentLabel(_account));
    }
}
