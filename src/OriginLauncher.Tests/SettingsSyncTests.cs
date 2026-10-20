using System.Text;
using System.Text.Json.Nodes;
using OriginLauncher.App.Core.Sync;
using Xunit;

namespace OriginLauncher.Tests;

// Settings Sync's merge rules, on real instance folders in a temp directory.
// File times are set explicitly so "newer wins" is deterministic.
public class SettingsSyncTests
{
    private readonly string _root = Directory.CreateTempSubdirectory("settings-sync").FullName;
    private readonly DateTime _t0 = DateTime.UtcNow.AddHours(-10);

    private SyncInstance Instance(string version)
    {
        var dir = Path.Combine(_root, version);
        Directory.CreateDirectory(dir);
        return new SyncInstance(version, dir);
    }

    // Writes a file inside an instance and stamps its last-write time at
    // _t0 + minutes, so tests control which file is "newer".
    private string Write(SyncInstance instance, string relativePath, string text, int minutes)
    {
        var path = Path.Combine(instance.Directory, relativePath.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, text, new UTF8Encoding(false));
        File.SetLastWriteTimeUtc(path, _t0.AddMinutes(minutes));
        return path;
    }

    private static string Options(params string[] lines) => string.Join("\n", lines) + "\n";

    private static Dictionary<string, string> OptionsOf(SyncInstance instance) =>
        File.ReadAllLines(Path.Combine(instance.Directory, "options.txt"))
            .Where(l => l.Contains(':'))
            .GroupBy(l => l[..l.IndexOf(':')])
            .ToDictionary(g => g.Key, g => g.Last()[(g.Last().IndexOf(':') + 1)..]);

    private static SettingsSyncEngine Engine() => new(new SyncStoreData());

    // ---------------------------------------------------------------- options.txt

    [Fact]
    public void HarvestPushRoundTripCarriesAChangeToAnotherVersion()
    {
        var a = Instance("1.21.1");
        var b = Instance("1.20.1");
        Write(a, "options.txt", Options("version:3955", "fov:0.0", "key_key.jump:key.keyboard.space", "guiScale:2"), 1);
        Write(b, "options.txt", Options("version:3465", "fov:0.0", "key_key.jump:key.keyboard.space", "guiScale:2", "lang:en_us"), 1);
        var engine = Engine();
        engine.Seed(new[] { a, b });

        // The player rebinds jump and changes FOV while playing 1.21.1.
        Write(a, "options.txt", Options("version:3955", "fov:0.5", "key_key.jump:key.keyboard.j", "guiScale:2"), 30);
        engine.Harvest(a);
        engine.Harvest(b);
        engine.Push(b);

        var after = OptionsOf(b);
        Assert.Equal("0.5", after["fov"]);
        Assert.Equal("key.keyboard.j", after["key_key.jump"]);
        Assert.Equal("en_us", after["lang"]);      // a key only B has is untouched
        Assert.Equal("3465", after["version"]);    // its own data version stays
    }

    [Fact]
    public void ConflictingEditsNewerWinsWhateverTheHarvestOrder()
    {
        foreach (var olderFirst in new[] { true, false })
        {
            var a = Instance($"1.21.1-{olderFirst}");
            var b = Instance($"1.20.4-{olderFirst}");
            var c = Instance($"1.19.4-{olderFirst}");
            foreach (var i in new[] { a, b, c })
                Write(i, "options.txt", Options("version:3337", "renderDistance:12"), 1);
            var engine = Engine();
            engine.Seed(new[] { a, b, c });

            Write(a, "options.txt", Options("version:3337", "renderDistance:8"), 20);   // older change
            Write(b, "options.txt", Options("version:3337", "renderDistance:24"), 40);  // newer change
            if (olderFirst) { engine.Harvest(a); engine.Harvest(b); }
            else { engine.Harvest(b); engine.Harvest(a); }
            engine.Harvest(c);
            engine.Push(c);
            engine.Push(a);

            Assert.Equal("24", OptionsOf(c)["renderDistance"]);
            Assert.Equal("24", OptionsOf(a)["renderDistance"]);
        }
    }

    [Fact]
    public void AKeyMissingFromAnOlderVersionIsNotADeletion()
    {
        var modern = Instance("1.21.1");
        var old = Instance("1.16.5");
        Write(modern, "options.txt", Options("version:3955", "simulationDistance:10", "renderDistance:12"), 5);
        Write(old, "options.txt", Options("version:2586", "renderDistance:12"), 1);
        var engine = Engine();
        engine.Seed(new[] { modern, old });

        // 1.16.5 has no simulationDistance; pushing it there is harmless (unknown
        // keys are ignored), and when 1.16.5 saves it drops the line again.
        engine.Push(old);
        Write(old, "options.txt", Options("version:2586", "renderDistance:16"), 60);
        engine.Harvest(old);
        engine.Harvest(modern);
        engine.Push(modern);

        var after = OptionsOf(modern);
        Assert.Equal("10", after["simulationDistance"]);
        Assert.Equal("16", after["renderDistance"]);
    }

    [Fact]
    public void VersionKeyIsNeverSynced()
    {
        var a = Instance("1.21.11");
        var b = Instance("1.16.5");
        Write(a, "options.txt", Options("version:4671", "fov:0.25"), 10);
        Write(b, "options.txt", Options("version:2586", "fov:0.0"), 1);
        var engine = Engine();
        engine.Seed(new[] { a, b });
        engine.Push(b);

        Assert.False(engine.Store.Files["options.txt"].ContainsKey("version"));
        Assert.Equal("2586", OptionsOf(b)["version"]);
        Assert.Equal("0.25", OptionsOf(b)["fov"]);
    }

    [Fact]
    public void ResourcePackListsAreNotSynced()
    {
        var a = Instance("1.21.1");
        var b = Instance("1.20.1");
        Write(a, "options.txt", Options("version:3955", "resourcePacks:[\"vanilla\",\"file/Faithful.zip\"]"), 10);
        Write(b, "options.txt", Options("version:3465", "resourcePacks:[\"vanilla\"]"), 1);
        var engine = Engine();
        engine.Seed(new[] { a, b });
        engine.Push(b);

        Assert.Equal("[\"vanilla\"]", OptionsOf(b)["resourcePacks"]);
    }

    [Fact]
    public void EraFormatsAreTranslated()
    {
        var modern = Instance("1.21.1");
        var old = Instance("1.16.5");
        Write(modern, "options.txt", Options("version:3955", "ao:true", "mainHand:\"left\"", "renderClouds:\"fast\"", "soundDevice:\"Speakers (Realtek Audio)\""), 10);
        Write(old, "options.txt", Options("version:2586", "ao:1", "mainHand:right", "renderClouds:true"), 1);
        var engine = Engine();
        engine.Seed(new[] { modern, old });
        engine.Push(old);

        var after = OptionsOf(old);
        Assert.Equal("1", after["ao"]);               // "1" (min) already means true: left as the player had it
        Assert.Equal("left", after["mainHand"]);      // 1.16.5 reads only the bare form
        Assert.Equal("fast", after["renderClouds"]);

        // ...and back: a bare 1.16.5 value into a codec-era version.
        Write(old, "options.txt", Options("version:2586", "ao:0", "mainHand:right", "renderClouds:fast"), 120);
        engine.Harvest(old);
        engine.Harvest(modern);
        engine.Push(modern);
        var modernAfter = OptionsOf(modern);
        Assert.Equal("false", modernAfter["ao"]);
        Assert.Equal("right", modernAfter["mainHand"]);
        Assert.Equal("\"Speakers (Realtek Audio)\"", modernAfter["soundDevice"]);
    }

    [Fact]
    public void KeybindsAndLanguageCrossThe189Cliff()
    {
        var legacy = Instance("1.8.9");
        var modern = Instance("1.21.1");
        // 1.8.9 stores LWJGL 2 key codes (and has no version line).
        Write(legacy, "options.txt", Options("key_key.forward:17", "key_key.attack:-100", "key_key.sprint:29", "lang:en_US", "fov:0.0"), 1);
        Write(modern, "options.txt", Options("version:3955", "key_key.forward:key.keyboard.w", "key_key.attack:key.mouse.left",
            "key_key.sprint:key.keyboard.left.control", "lang:en_us", "fov:0.0"), 1);
        var engine = Engine();
        engine.Seed(new[] { legacy, modern });

        // The player rebinds sprint to R and switches to German in 1.21.1...
        Write(modern, "options.txt", Options("version:3955", "key_key.forward:key.keyboard.w", "key_key.attack:key.mouse.left",
            "key_key.sprint:key.keyboard.r", "key_key.drop:scancode.86", "lang:de_de", "fov:0.0"), 30);
        engine.Harvest(modern);
        engine.Harvest(legacy);
        engine.Push(legacy);

        var after = OptionsOf(legacy);
        Assert.Equal("19", after["key_key.sprint"]);      // R as an LWJGL 2 code
        Assert.Equal("17", after["key_key.forward"]);     // the same key in both eras: untouched
        Assert.Equal("-100", after["key_key.attack"]);
        Assert.False(after.ContainsKey("key_key.drop"));  // a raw scancode has no 1.8.9 code
        Assert.Equal("de_DE", after["lang"]);
        Assert.False(after.ContainsKey("version"));       // 1.8.9 has no data version

        // ...and back: a rebind made in 1.8.9 reaches 1.21.1 as a GLFW name.
        Write(legacy, "options.txt", Options("key_key.forward:17", "key_key.attack:-100", "key_key.sprint:42",
            "key_key.jump:-98", "lang:de_DE", "fov:0.0"), 120);
        engine.Harvest(legacy);
        engine.Harvest(modern);
        engine.Push(modern);
        var modernAfter = OptionsOf(modern);
        Assert.Equal("key.keyboard.left.shift", modernAfter["key_key.sprint"]);
        Assert.Equal("key.mouse.middle", modernAfter["key_key.jump"]);
        Assert.Equal("de_de", modernAfter["lang"]);
    }

    [Fact]
    public void ABrandNew189OptionsFileHasNoVersionLine()
    {
        var modern = Instance("1.21.1");
        var fresh = Instance("1.8.9");
        Write(modern, "options.txt", Options("version:3955", "fov:0.25", "key_key.jump:key.keyboard.space"), 10);
        var engine = Engine();
        engine.Seed(new[] { modern, fresh });
        engine.Push(fresh);

        var created = File.ReadAllLines(Path.Combine(fresh.Directory, "options.txt"));
        Assert.DoesNotContain(created, l => l.StartsWith("version:", StringComparison.Ordinal));
        Assert.Contains("fov:0.25", created);
        Assert.Contains("key_key.jump:57", created);
    }

    [Fact]
    public void SoundDeviceIsRawIn118AndQuotedFrom119()
    {
        var old = Instance("1.18.2");
        var modern = Instance("1.21.11");
        Write(old, "options.txt", Options("version:2975", "soundDevice:OpenAL Soft on Speakers (Realtek)"), 10);
        Write(modern, "options.txt", Options("version:4671", "soundDevice:\"\""), 1);
        var engine = Engine();
        engine.Seed(new[] { old, modern });
        engine.Push(modern);
        Assert.Equal("\"OpenAL Soft on Speakers (Realtek)\"", OptionsOf(modern)["soundDevice"]);

        Write(modern, "options.txt", Options("version:4671", "soundDevice:\"Headset (USB)\""), 100);
        engine.Harvest(modern);
        engine.Harvest(old);
        engine.Push(old);
        Assert.Equal("Headset (USB)", OptionsOf(old)["soundDevice"]);
    }

    [Fact]
    public void ValuesThatWouldBreakALoaderAreNeverSynced()
    {
        var a = Instance("1.21.1");
        var b = Instance("1.21.11");
        Write(a, "options.txt", Options("version:3955", "key_key.jump:banana", "fov:not a number", "gamma:7.5", "lastServer:mc.example.net:25565"), 10);
        Write(b, "options.txt", Options("version:4671", "key_key.jump:key.keyboard.space", "fov:0.0", "gamma:0.5"), 1);
        var engine = Engine();
        engine.Seed(new[] { a, b });
        engine.Push(b);

        var after = OptionsOf(b);
        Assert.Equal("key.keyboard.space", after["key_key.jump"]); // 1.19+ aborts the whole load on an unknown key name
        Assert.Equal("0.0", after["fov"]);                         // a bad codec value would too
        Assert.Equal("0.5", after["gamma"]);                       // out of [0,1] would reset to default
        Assert.Equal("mc.example.net:25565", after["lastServer"]); // free text, split at the first ':'
    }

    [Fact]
    public void NumbersCompareByValueAcrossEras()
    {
        var format = OptionsTxtFormat.Instance;
        Assert.True(format.Equivalent("fov", "0.5", "0.50"));
        Assert.True(format.Equivalent("chatDelay", " 0.0", "0"));
        Assert.True(format.Equivalent("ao", "2", "true"));
        Assert.True(format.Equivalent("ao", "0", "false"));
        Assert.True(format.Equivalent("mainHand", "\"left\"", "left"));
        Assert.False(format.Equivalent("fov", "0.5", "0.25"));
    }

    [Fact]
    public void ABrandNewInstanceGetsAnOptionsFileStampedWithItsOwnDataVersion()
    {
        var a = Instance("1.21.1");
        var fresh = Instance("1.20.4");
        var unknown = Instance("26.1"); // in neither the table nor an installed jar (26.2 and 26.3 are in the table now)
        Write(a, "options.txt", Options("version:3955", "fov:0.25"), 10);
        var engine = Engine();
        engine.Seed(new[] { a, fresh, unknown });
        engine.Push(fresh);
        engine.Push(unknown);

        var created = File.ReadAllLines(Path.Combine(fresh.Directory, "options.txt"));
        Assert.Equal("version:3700", created[0]); // no data fixers re-run over our values
        Assert.Contains("fov:0.25", created);
        // No known data version -> no file: the game creates its own first.
        Assert.False(File.Exists(Path.Combine(unknown.Directory, "options.txt")));
    }

    // ---------------------------------------------------------------- seeding

    [Fact]
    public void FirstEnableSeedsFromTheNewestFilePerKeyAndKeepsTheRest()
    {
        var older = Instance("1.20.1");
        var newer = Instance("1.21.1");
        var target = Instance("1.19.4");
        Write(older, "options.txt", Options("version:3465", "fov:0.1", "key_key.drop:key.keyboard.x"), 1);
        Write(newer, "options.txt", Options("version:3955", "fov:0.9"), 50);
        Write(target, "options.txt", Options("version:3337", "fov:0.0"), 5);
        var engine = Engine();
        engine.Seed(new[] { older, newer, target });
        engine.Push(target);

        var after = OptionsOf(target);
        Assert.Equal("0.9", after["fov"]);                       // newest file wins
        Assert.Equal("key.keyboard.x", after["key_key.drop"]);  // only the older file had it: kept
    }

    [Fact]
    public void AVersionNeverSyncedBeforeOnlyFillsGaps()
    {
        var a = Instance("1.21.1");
        Write(a, "options.txt", Options("version:3955", "fov:0.75"), 1);
        var engine = Engine();
        engine.Seed(new[] { a });

        // A version installed later: the game wrote fresh defaults, so its file
        // is newer — but those defaults must not beat what the player chose.
        var later = Instance("1.20.4");
        Write(later, "options.txt", Options("version:3700", "fov:0.0", "narrator:0"), 300);
        engine.Harvest(later);
        engine.Push(a);

        Assert.Equal("0.75", engine.Store.Files["options.txt"]["fov"].Value);
        Assert.Equal("0", engine.Store.Files["options.txt"]["narrator"].Value);
        Assert.Equal("0.75", OptionsOf(a)["fov"]);
    }

    // ---------------------------------------------------------------- Origin configs

    [Fact]
    public void OriginModSettingsAndHudSyncAndUnknownFieldsSurvive()
    {
        var a = Instance("1.21.1");
        var b = Instance("1.16.5");
        Write(a, "config/originclient-mods.json", """
            { "mods": { "zoom": { "enabled": true, "fov": 15.0 }, "windcharge": { "enabled": true } },
              "hud": { "coords": [0, 4.0, 4.0, 1.0, 0] }, "meta": {}, "profiles": {} }
            """, 10);
        Write(b, "config/originclient-mods.json", """
            { "mods": { "zoom": { "enabled": false, "fov": 30.0 }, "legacyonly": { "enabled": true } },
              "hud": { "coords": [0, 2.0, 2.0, 1.0, 0] }, "meta": { "panelBacking": true }, "profiles": {} }
            """, 1);
        var engine = Engine();
        engine.Seed(new[] { a, b });
        engine.Push(b);

        var root = JsonNode.Parse(File.ReadAllText(Path.Combine(b.Directory, "config", "originclient-mods.json")))!;
        Assert.True(root["mods"]!["zoom"]!["enabled"]!.GetValue<bool>());
        Assert.Equal(15.0, root["mods"]!["zoom"]!["fov"]!.GetValue<double>());
        Assert.True(root["mods"]!["windcharge"]!["enabled"]!.GetValue<bool>()); // a 1.21-only mod rides along harmlessly
        Assert.True(root["mods"]!["legacyonly"]!["enabled"]!.GetValue<bool>()); // B's own keys stay
        Assert.Equal(4.0, root["hud"]!["coords"]![1]!.GetValue<double>());
        Assert.True(root["meta"]!["panelBacking"]!.GetValue<bool>());
    }

    [Fact]
    public void ResettingAHudElementFollowsThePlayerButAMassDropDoesNot()
    {
        var a = Instance("1.21.1");
        var b = Instance("1.20.1");
        var mods = """{ "mods": { "zoom": { "enabled": true } }, "hud": { "coords": [0, 4, 4, 1, 0], "fps": [1, 2, 2, 1, 0] } }""";
        Write(a, "config/originclient-mods.json", mods, 1);
        Write(b, "config/originclient-mods.json", mods, 1);
        var engine = Engine();
        engine.Seed(new[] { a, b });

        // The player resets the FPS element in 1.21.1 (ModsConfig removes it).
        Write(a, "config/originclient-mods.json", """{ "mods": { "zoom": { "enabled": true } }, "hud": { "coords": [0, 4, 4, 1, 0] } }""", 30);
        engine.Harvest(a);
        engine.Harvest(b);
        engine.Push(b);
        var root = JsonNode.Parse(File.ReadAllText(Path.Combine(b.Directory, "config", "originclient-mods.json")))!;
        Assert.Null(root["hud"]!["fps"]);
        Assert.NotNull(root["hud"]!["coords"]);

        // A file that suddenly lost most of its keys is a reset/failed load, not deletions.
        var many = new JsonObject { ["mods"] = new JsonObject() };
        for (var i = 0; i < 20; i++) many["mods"]!.AsObject()[$"m{i}"] = new JsonObject { ["enabled"] = true };
        Write(a, "config/originclient-mods.json", many.ToJsonString(), 40);
        Write(b, "config/originclient-mods.json", many.ToJsonString(), 40);
        engine.Harvest(a);
        engine.Harvest(b);
        Write(a, "config/originclient-mods.json", "{ \"mods\": {} }", 50);
        engine.Harvest(a);
        engine.Push(b);
        var after = JsonNode.Parse(File.ReadAllText(Path.Combine(b.Directory, "config", "originclient-mods.json")))!;
        Assert.Equal(20, after["mods"]!.AsObject().Count);
    }

    [Fact]
    public void OriginConfigsAreCreatedInABrandNewInstanceAndListsSyncWhole()
    {
        var a = Instance("1.21.1");
        var fresh = Instance("1.18.2");
        Write(a, "config/originclient-waypoints.json", """[ { "id": "1", "name": "Base", "x": 1, "y": 64, "z": 2 } ]""", 1);
        Write(a, "config/originclient-itemsizes.json", """{ "minecraft:diamond": 1.5 }""", 1);
        var engine = Engine();
        engine.Seed(new[] { a, fresh });
        engine.Push(fresh);

        var waypoints = JsonNode.Parse(File.ReadAllText(Path.Combine(fresh.Directory, "config", "originclient-waypoints.json")))!;
        Assert.Equal("Base", waypoints[0]!["name"]!.GetValue<string>());
        var sizes = JsonNode.Parse(File.ReadAllText(Path.Combine(fresh.Directory, "config", "originclient-itemsizes.json")))!;
        Assert.Equal(1.5, sizes["minecraft:diamond"]!.GetValue<double>());
    }

    // ---------------------------------------------------------------- third-party configs

    [Fact]
    public void ThirdPartyConfigsNeverGainForeignKeysOrWrongTypes()
    {
        var a = Instance("1.21.1");
        var b = Instance("1.16.5");
        var missing = Instance("1.20.1");
        Write(a, "config/sodium-options.json", """
            { "quality": { "weather_quality": "FAST", "leaves_quality": "FANCY", "enable_vignette": false },
              "performance": { "chunk_builder_threads": 4, "use_entity_culling": false },
              "advanced": { "cpu_render_ahead_limit": 2 } }
            """, 10);
        Write(b, "config/sodium-options.json", """
            { "quality": { "weather_quality": "DEFAULT", "leaves_quality": "DEFAULT", "enable_vignette": true,
                           "smooth_lighting": "HIGH" },
              "performance": { "chunk_builder_threads": "auto" },
              "advanced": { "chunk_renderer_backend": "GL43" } }
            """, 1);
        var engine = Engine();
        engine.Seed(new[] { a, b, missing });
        engine.Push(b);
        engine.Push(missing);

        var root = JsonNode.Parse(File.ReadAllText(Path.Combine(b.Directory, "config", "sodium-options.json")))!;
        Assert.Equal("FAST", root["quality"]!["weather_quality"]!.GetValue<string>());
        Assert.False(root["quality"]!["enable_vignette"]!.GetValue<bool>());
        Assert.Equal("auto", root["performance"]!["chunk_builder_threads"]!.GetValue<string>()); // type differs: kept
        Assert.Null(root["performance"]!["use_entity_culling"]);                                 // foreign key: not added
        Assert.Null(root["advanced"]!["cpu_render_ahead_limit"]);
        Assert.Equal("GL43", root["advanced"]!["chunk_renderer_backend"]!.GetValue<string>());
        Assert.False(File.Exists(Path.Combine(missing.Directory, "config", "sodium-options.json"))); // never created
    }

    [Fact]
    public void IrisKeepsItsOwnKeysAndOnlyGetsShaderPacksItHas()
    {
        var a = Instance("1.21.1");
        var withPack = Instance("1.20.1");
        var withoutPack = Instance("1.19.2");
        Write(a, "config/iris.properties", "#Iris\nshaderPack=Complementary\\ Reimagined.zip\nenableShaders=true\ncolorSpace=SRGB\nnewIrisKey=1\n", 10);
        Write(withPack, "config/iris.properties", "#Iris\nshaderPack=\nenableShaders=false\ncolorSpace=SRGB\n", 1);
        Write(withoutPack, "config/iris.properties", "#Iris\nshaderPack=\nenableShaders=false\n", 1);
        Write(withPack, "shaderpacks/Complementary Reimagined.zip", "zip", 1);
        var engine = Engine();
        engine.Seed(new[] { a, withPack, withoutPack });
        engine.Push(withPack);
        engine.Push(withoutPack);

        var p1 = File.ReadAllText(Path.Combine(withPack.Directory, "config", "iris.properties"));
        Assert.Contains("shaderPack=Complementary\\ Reimagined.zip", p1);
        Assert.Contains("enableShaders=true", p1);
        Assert.DoesNotContain("newIrisKey", p1);
        Assert.StartsWith("#Iris", p1);
        var p2 = File.ReadAllText(Path.Combine(withoutPack.Directory, "config", "iris.properties"));
        Assert.Contains("shaderPack=" + Environment.NewLine, p2);  // pack not installed there: left as it was
    }

    [Fact]
    public void KeysAModFillsInOnItsFirstRunDoNotBeatThePlayersChoice()
    {
        var a = Instance("1.21.1");
        Write(a, "config/iris.properties", "shaderPack=BSL.zip\nenableShaders=true\ndisableUpdateMessage=true\n", 1);
        var fresh = Instance("1.20.4");
        // The launcher seeds a one-line iris.properties before Iris ever runs.
        Write(fresh, "config/iris.properties", "disableUpdateMessage=true\n", 2);
        var engine = Engine();
        engine.Seed(new[] { a, fresh });
        engine.Push(fresh);

        // Iris's first run writes its own defaults — a newer file than 1.21.1's.
        Write(fresh, "config/iris.properties", "shaderPack=\nenableShaders=true\ndisableUpdateMessage=true\n", 100);
        engine.Harvest(fresh);
        engine.Harvest(a);
        engine.Push(a);

        Assert.Equal("BSL.zip", engine.Store.Files["config/iris.properties"]["shaderPack"].Value);
        Assert.Contains("shaderPack=BSL.zip", File.ReadAllText(Path.Combine(a.Directory, "config", "iris.properties")));
    }

    [Fact]
    public void AConfigRecreatedWithDefaultsAfterBeingDeletedDoesNotSpreadThem()
    {
        var a = Instance("1.21.1");
        var b = Instance("1.20.1");
        var sodium = """{ "quality": { "weather_quality": "FAST" } }""";
        Write(a, "config/sodium-options.json", sodium, 1);
        Write(b, "config/sodium-options.json", sodium, 1);
        var engine = Engine();
        engine.Seed(new[] { a, b });

        // B's file was corrupted, deleted by the launcher, then rewritten by Sodium with defaults.
        File.Delete(Path.Combine(b.Directory, "config", "sodium-options.json"));
        engine.Harvest(b);
        Write(b, "config/sodium-options.json", """{ "quality": { "weather_quality": "DEFAULT" } }""", 100);
        engine.Harvest(b);
        engine.Harvest(a);
        engine.Push(b);

        Assert.Equal("\"FAST\"", engine.Store.Files["config/sodium-options.json"]["/quality/weather_quality"].Value);
        var root = JsonNode.Parse(File.ReadAllText(Path.Combine(b.Directory, "config", "sodium-options.json")))!;
        Assert.Equal("FAST", root["quality"]!["weather_quality"]!.GetValue<string>());
    }

    // ---------------------------------------------------------------- servers.dat

    [Fact]
    public void ServerListNewestWinsWholeFile()
    {
        var a = Instance("1.21.1");
        var b = Instance("1.16.5");
        var older = new byte[] { 0x0A, 0, 0, 1, 2, 3 };
        var newer = new byte[] { 0x0A, 0, 0, 9, 9, 9, 9 };
        var pa = Path.Combine(a.Directory, "servers.dat");
        var pb = Path.Combine(b.Directory, "servers.dat");
        File.WriteAllBytes(pa, newer);
        File.SetLastWriteTimeUtc(pa, _t0.AddMinutes(20));
        File.WriteAllBytes(pb, older);
        File.SetLastWriteTimeUtc(pb, _t0.AddMinutes(1));
        var engine = Engine();
        engine.Seed(new[] { a, b });
        engine.Push(b);

        Assert.Equal(newer, File.ReadAllBytes(pb));
    }

    // ---------------------------------------------------------------- robustness

    [Fact]
    public void CorruptFilesAreSkippedWithoutThrowingAndLeftUntouched()
    {
        var good = Instance("1.21.1");
        var bad = Instance("1.20.1");
        Write(good, "options.txt", Options("version:3955", "fov:0.5"), 10);
        Write(good, "config/originclient-mods.json", """{ "mods": { "zoom": { "enabled": true } } }""", 10);
        var badOptions = Write(bad, "options.txt", "\0\0\0\0", 1);
        var badMods = Write(bad, "config/originclient-mods.json", "{ \"mods\": { broken", 1);
        var badServers = Path.Combine(bad.Directory, "servers.dat");
        File.WriteAllBytes(badServers, new byte[] { 0x1F, 0x8B, 0x00 });
        File.WriteAllBytes(Path.Combine(good.Directory, "servers.dat"), new byte[] { 0x0A, 0, 0, 5 });
        var logs = new List<string>();
        var engine = new SettingsSyncEngine(new SyncStoreData(), logs.Add);

        engine.Seed(new[] { good, bad });
        engine.Harvest(bad);
        engine.Push(bad);

        Assert.Equal("\0\0\0\0", File.ReadAllText(badOptions));
        Assert.Equal("{ \"mods\": { broken", File.ReadAllText(badMods));
        Assert.Equal(new byte[] { 0x1F, 0x8B, 0x00 }, File.ReadAllBytes(badServers));
        Assert.Equal("0.5", engine.Store.Files["options.txt"]["fov"].Value);
        Assert.NotEmpty(logs);
    }

    [Fact]
    public void StoreRoundTripsAndACorruptStoreReadsAsMissing()
    {
        var a = Instance("1.21.1");
        Write(a, "options.txt", Options("version:3955", "fov:0.5"), 10);
        var engine = Engine();
        engine.Seed(new[] { a });
        var path = Path.Combine(_root, "settings-sync.json");
        SyncStoreFile.Save(path, engine.Store);

        var loaded = SyncStoreFile.Load(path)!;
        Assert.Equal("0.5", loaded.Files["options.txt"]["fov"].Value);
        Assert.Equal("0.5", loaded.Baselines["1.21.1"]["options.txt"]["fov"]);

        File.WriteAllText(path, "{ not json");
        Assert.Null(SyncStoreFile.Load(path));
        Assert.Null(SyncStoreFile.Load(Path.Combine(_root, "nope.json")));
    }

    [Fact]
    public void PushWithNothingNewLeavesFilesUntouched()
    {
        var a = Instance("1.21.1");
        var path = Write(a, "options.txt", Options("version:3955", "fov:0.5"), 10);
        var engine = Engine();
        engine.Seed(new[] { a });
        engine.Push(a);

        Assert.Equal(_t0.AddMinutes(10), File.GetLastWriteTimeUtc(path));
    }

    [Fact]
    public void VersionOrdering()
    {
        Assert.True(McVersion.AtLeast("1.19.2", "1.19"));
        Assert.False(McVersion.AtLeast("1.18.2", "1.19"));
        Assert.True(McVersion.AtLeast("1.21.11", "1.21.2"));
        Assert.True(McVersion.AtLeast("26.2", "1.21.11"));
    }
}
