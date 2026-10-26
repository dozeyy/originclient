using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Launcher;
using Xunit;

namespace OriginLauncher.Tests.Import;

// B-24 (settings porting on import), against two scrubbed fixtures shaped like real setups: a Lunar 1.8 line (Lunar
// writes ONE modern options.txt into every line, so 1.8.9 must get it translated to its own era) and a Prism 1.20
// Fabric instance (same era: carried over as is, with 1.20's data version). Every name, path and token here is made
// up: no user names, no real tokens, no private paths. Each run goes scan -> plan -> apply -> step 4's report.
public class SettingsPortingTests : IDisposable
{
    private readonly BulkFixture _f = new();
    private Rig Rig => _f.Rig;
    private string Pc => Path.Combine(Rig.Root, "pc");
    private string Lunar => Path.Combine(Pc, "User", ".lunarclient");
    private string PrismRoot => Path.Combine(Pc, "AppData", "PrismLauncher");

    public void Dispose() => _f.Dispose();

    private Task<ScanResult> Scan() => Rig.Engine().ScanAsync(
        ImportRoots.Sources(new MachineFolders(Path.Combine(Pc, "AppData"), Path.Combine(Pc, "Local"), Path.Combine(Pc, "User"), Path.Combine(Pc, "Docs"))),
        ImportEngine.DefaultSources(), null, CancellationToken.None);

    private void LunarOneEight()
    {
        var recent = DateTime.UtcNow.AddDays(-40);
        Directory.CreateDirectory(Path.Combine(Lunar, "profiles", "1.8", "mods", "ichor-1.8.9"));
        Kit.Write(Lunar, "profiles/1.8/options.txt",
            "version:4671\nfov:0.575\ngamma:0.55\nguiScale:2\nmaxFps:260\nrenderDistance:8\ngraphicsPreset:\"custom\"\n"
            + "mouseSensitivity:0.09859155\nresourcePacks:[]\nlang:en_US\nmusicToast:\"never\"\n"
            + "key_key.attack:key.mouse.left\nkey_key.forward:key.keyboard.w\nkey_key.sprint:key.keyboard.left.control\n", recent);
        Kit.Write(Lunar, "profiles/1.8/optionsof.txt", "ofClouds:3\nofTrees:4\n", recent);
        Kit.Write(Lunar, "profiles/1.8/optionsLC.txt", "lunar-only", recent);
        Kit.WriteBytes(Lunar, "profiles/1.8/servers.dat", Kit.ServersDat(("Example PvP", "pvp.example.net")));
        Kit.Write(Lunar, "profiles/1.8/logs/latest.log",
            "[14:08:52] [Client thread/INFO]: LWJGL Version: 2.9.4\n[14:08:52] [Client thread/INFO]: [OptiFine] OptiFine_1.8.9_HD_U_M6_pre2\n");
        Kit.Write(Lunar, "settings/launcher.json", "{\"settings\":{\"gameDirectory\":"
            + System.Text.Json.JsonSerializer.Serialize(Path.Combine(Pc, "AppData", ".minecraft")) + "}}");
        Kit.Write(Lunar, "settings/game/accounts.json", "{\"accounts\":{\"x\":{\"accessToken\":\"REDACTED-TEST-TOKEN\"}}}");
    }

    private void PrismOneTwenty()
    {
        Kit.Write(PrismRoot, "accounts.json", "{\"accounts\":[{\"token\":\"REDACTED-TEST-TOKEN\"}]}");
        Kit.Write(PrismRoot, "instances/Survival/instance.cfg", "[General]\nname=Survival 1.20\nlastLaunchTime=0\ntotalTimePlayed=3600\n");
        Kit.Write(PrismRoot, "instances/Survival/mmc-pack.json",
            """{"formatVersion":1,"components":[{"uid":"net.minecraft","version":"1.20"},{"uid":"net.fabricmc.fabric-loader","version":"0.14.21"}]}""");
        var game = Path.Combine(PrismRoot, "instances", "Survival", ".minecraft");
        Kit.Write(game, "options.txt",
            "version:3463\nfov:0.25\nrenderDistance:16\nguiScale:3\ngraphicsMode:1\nresourcePacks:[\"vanilla\",\"file/Stay True.zip\"]\n"
            + "key_key.forward:key.keyboard.w\nkey_key.jump:key.keyboard.space\nsoundCategory_music:0.0\n");
        Kit.WriteBytes(game, "resourcepacks/Stay True.zip", Kit.Zip(Kit.Text("pack.mcmeta", "{}")));
        Kit.Write(game, "config/iris.properties", "shaderPack=\nenableShaders=false\n");
        Kit.WriteBytes(game, "mods/zoomify.jar", Kit.FabricJar("zoomify", "2.11.0", minecraft: "~1.20"));
        Kit.WriteBytes(game, "servers.dat", Kit.ServersDat(("Example SMP", "smp.example.net")));
    }

    private async Task<(BulkImportPlan Plan, BulkImportResult Result, ImportVerification Report)> ImportLauncher(DetectedInstance setup, params string[] extraTargets)
    {
        _f.Setups.Add(setup);
        var request = _f.Request(BulkSelection.OfLauncher(setup.Kind)) with { SupportedTargets = BulkFixture.Supported.Concat(extraTargets).ToArray() };
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, request);
        Assert.True(plan.CanApply, string.Join(" ", plan.Blocked));
        var result = await engine.ApplyBulkAsync(plan, Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        return (plan, result, ImportVerifier.Verify(plan, result));
    }

    [Fact]
    public async Task LunarOneEightSettingsArriveTranslatedForOneEightNine()
    {
        LunarOneEight();
        var lunarBefore = Kit.TreeHash(Lunar, mtimes: true);
        var setup = Assert.Single((await Scan()).Instances, s => s.Kind == SourceKind.Lunar && s.McVersion == "1.8.9");

        var (_, _, report) = await ImportLauncher(setup);

        var options = Kit.Read(Rig.GameDir("1.8.9"), "options.txt");
        Assert.Contains("fov:0.575", options);
        Assert.Contains("guiScale:2", options);
        Assert.Contains("key_key.attack:-100", options);   // GLFW mouse name -> LWJGL 2 button
        Assert.Contains("key_key.forward:17", options);    // key.keyboard.w -> scancode 17
        Assert.Contains("key_key.sprint:29", options);
        Assert.DoesNotContain("version:", options);        // 1.8.9 has no data version
        Assert.DoesNotContain("graphicsPreset", options);  // keys 1.8.9 doesn't read stay behind
        Assert.Equal("ofClouds:3\nofTrees:4\n", Kit.Read(Rig.GameDir("1.8.9"), "optionsof.txt").Replace("\r\n", "\n"));
        Assert.False(Kit.Exists(Rig.GameDir("1.8.9"), "optionsLC.txt"));
        Assert.Contains(report.In(VerifyBucket.Succeeded), i => i.Name.Contains("settings file"));
        Assert.Contains(report.In(VerifyBucket.Unsupported), i => i.Name.Contains("Accounts"));
        Assert.Empty(report.In(VerifyBucket.Failed));
        Assert.Equal(lunarBefore, Kit.TreeHash(Lunar, mtimes: true));                  // the source is never written
        Assert.DoesNotContain(Rig.Opened, p => Path.GetFileName(p) == "accounts.json"); // never even opened
    }

    [Fact]
    public async Task PrismOneTwentySettingsArriveAsTheyAreWithItsModsInAProfile()
    {
        PrismOneTwenty();
        var prismBefore = Kit.TreeHash(PrismRoot, mtimes: true);
        var setup = Assert.Single((await Scan()).Instances, s => s.Kind == SourceKind.Prism);
        Assert.Equal("1.20", setup.McVersion);

        var (_, result, report) = await ImportLauncher(setup, "1.20");

        var options = File.ReadAllLines(Path.Combine(Rig.GameDir("1.20"), "options.txt"));
        Assert.Contains("version:3463", options);
        Assert.Contains("fov:0.25", options);
        Assert.Contains("renderDistance:16", options);
        Assert.Contains("key_key.forward:key.keyboard.w", options);
        Assert.Contains(options, l => l.StartsWith("resourcePacks:") && l.Contains("Stay True.zip"));
        Assert.True(Kit.Exists(Rig.GameDir("1.20"), "resourcepacks/Stay True.zip"));
        var outcome = Assert.Single(result.Versions.SelectMany(v => v.Setups), s => s.ProfileId != null);
        Assert.True(File.Exists(Path.Combine(outcome.ProfileModsDir!, "zoomify.jar")));
        Assert.Contains(Rig.Settings.Load().Profiles, p => p.Id == outcome.ProfileId);
        Assert.Contains(report.Profiles, p => p.ProfileId == outcome.ProfileId);
        Assert.Contains(report.In(VerifyBucket.Succeeded), i => i.Name == "1 mod");
        Assert.Empty(report.In(VerifyBucket.Failed));
        Assert.Equal(prismBefore, Kit.TreeHash(PrismRoot, mtimes: true));
        Assert.DoesNotContain(Rig.Opened, p => Path.GetFileName(p) == "accounts.json");
    }
}
