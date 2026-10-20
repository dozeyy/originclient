using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Mods;
using OriginLauncher.App.Core.Profiles;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Plan + apply for mods, settings and packs: what lands where, in which format, and what the
// plan and summary say about it. Every folder is a temp folder (Rig); nothing real is touched.
public class ImportEngineTests : IDisposable
{
    private readonly Rig _rig = new();

    public void Dispose() => _rig.Dispose();

    private string Dst(string relative, string version = "1.21.1") =>
        Path.Combine(_rig.GameDir(version), relative.Replace('/', Path.DirectorySeparatorChar));

    private void Populate(bool withMods = true) => TypicalSource.Populate(_rig.Source, withMods);

    private async Task<ImportPlan> Plan(ImportRequest? request = null, ImportEngine? engine = null) =>
        await (engine ?? _rig.Engine()).PlanAsync(request ?? _rig.Request(), null, CancellationToken.None);

    private async Task<(ImportPlan Plan, ImportResult Result)> Run(ImportRequest? request = null)
    {
        var engine = _rig.Engine();
        var plan = await Plan(request, engine);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        return (plan, result);
    }

    // ------------------------------------------------------------ mods: the version's shared folder, never a profile

    [Fact]
    public async Task ModsLandInTheVersionsSharedModsFolderAndNoProfileIsMade()
    {
        Populate();
        var shared = Path.Combine(_rig.Instances, "1.21.1", "mods");
        var existing = Kit.WriteBytes(shared, "existing.jar", Kit.FabricJar("existing", "1.0"));
        var existingBytes = File.ReadAllBytes(existing);

        var (plan, result) = await Run();

        Assert.True(result.Succeeded);
        Assert.Equal(new[] { "disabled-thing.jar.disabled", "existing.jar", "mymod.jar", "needs-lib.jar.disabled" },
            Directory.GetFiles(shared).Select(Path.GetFileName).OrderBy(n => n, StringComparer.Ordinal));
        Assert.Equal(existingBytes, File.ReadAllBytes(existing));                 // Origin's own mod: untouched
        // No launcher setting changes at all: no profile, and the picked version stays the player's.
        Assert.Null(plan.SettingsDelta);
        var settings = _rig.Settings.Load();
        Assert.Empty(settings.Profiles);
        Assert.Equal("1.21.11", settings.SelectedVersion);
        Assert.Null(settings.SelectedProfileId);
        Assert.False(Directory.Exists(Path.Combine(_rig.Instances, "1.21.1", "profiles")));
    }

    [Fact]
    public async Task AModOriginsSharedFolderAlreadyHasIsLeftAsItIs()
    {
        Populate();
        var shared = Path.Combine(_rig.Instances, "1.21.1", "mods");
        // Same file name, other bytes: Origin's copy stays, the old setup's is never written over it.
        var mine = Kit.WriteBytes(shared, "mymod.jar", Kit.FabricJar("mymod", "0.9"));
        var before = File.ReadAllBytes(mine);

        var (plan, result) = await Run();

        Assert.True(result.Succeeded);
        Assert.Equal(before, File.ReadAllBytes(mine));
        Assert.Contains(plan.Lines, l => l.Kind == PlanItemKind.Mod && l.Reason == ImportEngine.AlreadyInOriginMods);
        Assert.DoesNotContain(plan.Ops, o => o.Item == PlanItemKind.Mod && o.Dest == mine);
    }

    [Fact]
    public async Task WithPersonalModsOffThereIsANeedsAttentionLineAndTheSettingStaysOff()
    {
        Populate();
        var plan = await Plan(_rig.Request(playWithExternalMods: false));

        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Name.Contains("own mods are turned off"));
        // The delta never carries PlayWithExternalMods: there is no such field to set.
        Assert.DoesNotContain(typeof(SettingsDelta).GetProperties(), p => p.Name.Contains("External"));
    }

    [Fact]
    public async Task NoModsMeansNoSettingsChangeAtAll()
    {
        Populate(withMods: false);
        var (plan, result) = await Run();

        Assert.True(result.Succeeded);
        Assert.Null(plan.SettingsDelta);
        Assert.Equal("1.21.11", _rig.Settings.Load().SelectedVersion);
        Assert.Empty(_rig.Settings.Load().Profiles);
        Assert.False(Directory.Exists(Path.Combine(_rig.Instances, "1.21.1", "profiles")));
    }

    [Fact]
    public async Task WithoutTheBundledJarTheBuiltInListIsCatalogOnlyAndSaysItIsIncomplete()
    {
        Populate();
        var request = _rig.Request() with { BundledOriginJarPath = Path.Combine(_rig.Root, "missing.jar") };
        var plan = await Plan(request);

        Assert.True(plan.BuiltInListIncomplete);
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Reason!.Contains("incomplete"));
    }

    // ------------------------------------------------------------ blockers (criteria 12, 53)

    [Fact]
    public async Task AnUnsupportedSourceVersionBlocksTheImportUntilASupportedOneIsPicked()
    {
        Populate();
        var unsupported = _rig.Request(mc: "1.21.5") with { TargetVersion = null };

        var blocked = await Plan(unsupported);
        Assert.False(blocked.CanApply);
        Assert.Contains("1.21.5", blocked.Blocked[0]);
        Assert.Contains("Origin doesn't support it", blocked.Blocked[0]);
        Assert.Contains("1.21.4", blocked.Blocked[0]);
        Assert.Empty(blocked.Ops);

        var picked = await Plan(unsupported with { TargetVersion = "1.21.4" });
        Assert.True(picked.CanApply);
        // The 1.21.5 source's mods are checked against the version actually picked.
        Assert.Equal(ModOutcome.WrongMinecraft, picked.Mods.Single(m => m.FileName == "old-thing.jar").Outcome);

        var unsupportedTarget = await Plan(_rig.Request(target: "1.21.5"));
        Assert.False(unsupportedTarget.CanApply);
    }

    [Fact]
    public async Task ASourceWhoseVersionIsSupportedImportsIntoItWithoutPickingATarget()
    {
        Populate();
        var plan = await Plan(_rig.Request() with { TargetVersion = null });
        Assert.True(plan.CanApply);
        Assert.Equal("1.21.1", plan.TargetVersion);
    }

    [Fact]
    public async Task NoAccountKeyBlocksWithTheSignInSentence()
    {
        Populate();
        var plan = await Plan(_rig.Request(account: null));
        Assert.False(plan.CanApply);
        Assert.Contains(plan.Blocked, b => b.StartsWith("Sign in (or turn on offline test mode) first"));
    }

    [Fact]
    public async Task AGameHoldingLatestLogOpenInTheSourceOrTheDestinationBlocksThePlan()
    {
        Populate();
        var engine = new ImportEngine(new ImportEnvironment
        {
            InstancesRoot = _rig.Instances, ImportsRoot = _rig.Imports, // the real probe: an exclusive open of logs/latest.log
        });
        var sourceLog = Kit.Write(_rig.Source, "logs/latest.log", "log");
        using (new FileStream(sourceLog, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            var plan = await Plan(engine: engine);
            Assert.False(plan.CanApply);
            Assert.Contains(plan.Blocked, b => b.StartsWith("Close Minecraft first"));
        }
        Assert.True((await Plan(engine: engine)).CanApply);                       // released: fine

        var destLog = Kit.Write(_rig.GameDir(), "logs/latest.log", "log");
        using (new FileStream(destLog, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            var plan = await Plan(engine: engine);
            Assert.Contains(plan.Blocked, b => b.StartsWith("Close Minecraft first"));
        }
    }

    [Fact]
    public async Task NotEnoughFreeSpaceBlocksWithTheNeededAmount()
    {
        Populate();
        var enough = await Plan(engine: _rig.Engine(freeSpace: _ => long.MaxValue));
        var needed = (long)(enough.BytesToCopy * 1.1);
        Assert.True(needed > 0);

        var tight = await Plan(engine: _rig.Engine(freeSpace: _ => needed - 1));
        Assert.False(tight.CanApply);
        Assert.Contains("Not enough free disk space", tight.Blocked[0]);

        Assert.True((await Plan(engine: _rig.Engine(freeSpace: _ => needed))).CanApply);
    }

    [Fact]
    public async Task ApplyingABlockedPlanDoesNothing()
    {
        Populate();
        var engine = _rig.Engine();
        var plan = await Plan(_rig.Request(account: null), engine);
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.False(Directory.Exists(_rig.Instances));
        Assert.False(Directory.Exists(_rig.Imports));
    }

    // ------------------------------------------------------------ options.txt in each era (criterion 33, 13)

    [Theory]
    [InlineData("1.16.5", "version:2586", "key_key.forward:key.keyboard.w", "lang:en_us", "resourcePacks:[\"vanilla\",\"file/Faithful.zip\",\"file/FolderPack\"]")]
    [InlineData("1.21.11", "version:4671", "key_key.forward:key.keyboard.w", "lang:en_us", "resourcePacks:[\"vanilla\",\"file/Faithful.zip\",\"file/FolderPack\"]")]
    [InlineData("1.8.9", null, "key_key.forward:17", "lang:en_US", "resourcePacks:[\"Faithful.zip\",\"FolderPack\"]")]
    public async Task OptionsAreWrittenInTheTargetErasFormatAndTheSourceVersionLineIsNeverCopied(
        string target, string? versionLine, string forward, string lang, string packs)
    {
        Populate();
        var (_, result) = await Run(_rig.Request(target: target));
        Assert.True(result.Succeeded);

        var lines = File.ReadAllLines(Dst("options.txt", target));
        Assert.Contains(forward, lines);
        Assert.Contains(lang, lines);
        Assert.Contains(packs, lines);
        Assert.Contains("highContrast:true", lines);                              // accessibility keys come across
        Assert.Contains("narrator:1", lines);
        Assert.Contains("ao:true", lines);
        Assert.Contains("fov:0.5", lines);
        Assert.DoesNotContain("version:3955", lines);                              // the source's data version
        if (versionLine == null) Assert.DoesNotContain(lines, l => l.StartsWith("version:"));
        else Assert.Contains(versionLine, lines);                                   // the TARGET's own
    }

    [Fact]
    public async Task SourceKeybindsInLwjglCodesComeAcrossAsGlfwNamesFrom189()
    {
        Kit.Write(_rig.Source, "options.txt", "key_key.forward:17\nkey_key.jump:57\nkey_key.attack:-100\nlang:en_US\nresourcePacks:[\"Old.zip\"]\n");
        Kit.WriteBytes(_rig.Source, "resourcepacks/Old.zip", Kit.Zip(Kit.Text("pack.mcmeta", "o")));

        var (_, result) = await Run(_rig.Request(mc: "1.8.9"));
        Assert.True(result.Succeeded);

        var lines = File.ReadAllLines(Dst("options.txt"));
        Assert.Contains("key_key.forward:key.keyboard.w", lines);
        Assert.Contains("key_key.jump:key.keyboard.space", lines);
        Assert.Contains("key_key.attack:key.mouse.left", lines);
        Assert.Contains("resourcePacks:[\"vanilla\",\"file/Old.zip\"]", lines);     // 1.8.9 bare names -> 1.13+ form
    }

    [Fact]
    public async Task On189NoModsAreCopiedAndEveryFabricModIsListedWithTheForgeReason()
    {
        Populate();
        var (plan, result) = await Run(_rig.Request(target: "1.8.9"));

        Assert.True(result.Succeeded);
        Assert.DoesNotContain(plan.Ops, o => o.Item == PlanItemKind.Mod);
        Assert.All(plan.Mods.Where(m => m.FileName is "mymod.jar" or "sodium-fabric.jar" or "needs-lib.jar" or "old-thing.jar" or "disabled-thing.jar.disabled"),
            m => Assert.Contains("1.8.9 runs on Forge in Origin", m.Reason));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Reason!.Contains("1.8.9 runs on Forge in Origin"));
        Assert.False(Directory.Exists(Path.Combine(_rig.Instances, "1.8.9", "profiles")));
    }

    // ------------------------------------------------------------ settings timestamps, configs (34, 35)

    [Fact]
    public async Task EveryWrittenSettingsFileIsStampedAsModifiedNow()
    {
        Populate();
        var start = DateTime.UtcNow.AddSeconds(-1);

        var (_, result) = await Run();
        Assert.True(result.Succeeded);

        foreach (var file in new[] { "options.txt", "servers.dat", "config/somemod.json", "config/nested/x.toml", "config/iris.properties" })
            Assert.True(File.GetLastWriteTimeUtc(Dst(file)) >= start, $"{file} is older than the apply start");
    }

    [Fact]
    public async Task ConfigsAreCopiedExceptOriginsOwnTheBuiltInMixinSwitchesAndCredentialNames()
    {
        Populate();
        var (plan, result) = await Run();
        Assert.True(result.Succeeded);

        Assert.True(File.Exists(Dst("config/somemod.json")));
        Assert.True(File.Exists(Dst("config/nested/x.toml")));
        foreach (var excluded in new[]
                 {
                     "config/originclient-mods.json", "config/sodium-mixins.properties", "config/lithium.properties",
                     "config/immediatelyfast.json", "config/krypton.toml", "config/discord-token.json",
                 })
            Assert.False(File.Exists(Dst(excluded)), excluded + " should not be copied");
        Assert.Contains("discord-token.json", plan.SkippedForSafety);
        // A fresh Sodium creates its own options file, so none is copied over it.
        Assert.False(File.Exists(Dst("config/sodium-options.json")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name == "sodium-options.json");
    }

    [Fact]
    public async Task SodiumAndIrisSettingsAreKeyMergedKeepingWhatOnlyTheDestinationHas()
    {
        Populate();
        Kit.Write(_rig.GameDir(), "config/sodium-options.json",
            """{"quality":{"weather_quality":"DEFAULT"},"advanced":{"cpu_render_ahead_limit":1},"extra":{"dest_only":true}}""");
        Kit.Write(_rig.GameDir(), "config/iris.properties", "shaderPack=\nenableShaders=false\nmaxShadowRenderDistance=8\ncolorSpace=SRGB\n");

        var (_, result) = await Run();
        Assert.True(result.Succeeded);

        var sodium = Kit.Read(_rig.GameDir(), "config/sodium-options.json");
        Assert.Contains("\"FAST\"", sodium);                                       // source value merged in
        Assert.Contains("\"cpu_render_ahead_limit\": 3", sodium);
        Assert.Contains("dest_only", sodium);                                      // destination-only key kept
        var iris = File.ReadAllLines(Dst("config/iris.properties"));
        Assert.Contains("colorSpace=SRGB", iris);                                  // destination-only key kept
        Assert.Contains("maxShadowRenderDistance=16", iris);
        Assert.Contains("shaderPack=Complementary.zip", iris);
        Assert.Contains("enableShaders=true", iris);
    }

    // ------------------------------------------------------------ conflict policy (36)

    [Fact]
    public async Task ADifferingDestinationFileIsReplacedAndTheOldCopyIsBackedUp()
    {
        Populate();
        Kit.Write(_rig.GameDir(), "config/somemod.json", """{"a":999}""");
        Kit.Write(_rig.GameDir(), "config/nested/x.toml", "x=1");                  // identical: no change, no backup

        var (plan, result) = await Run();

        Assert.True(result.Succeeded);
        Assert.Equal("""{"a":1}""", Kit.Read(_rig.GameDir(), "config/somemod.json"));
        var backups = Directory.GetFiles(ImportLayout.Backup(_rig.Imports, plan.ImportId));
        var saved = Assert.Single(backups, b => Path.GetFileName(b).EndsWith("somemod.json"));
        Assert.Equal("""{"a":999}""", File.ReadAllText(saved));
        Assert.True(plan.Replaced >= 1);
        Assert.True(plan.Unchanged >= 1);
        Assert.Contains("replaced", result.SummaryText);
        Assert.Contains($"{plan.Replaced} replaced", result.SummaryText);
    }

    [Fact]
    public async Task KeepMyCurrentOriginSettingsLeavesADifferingFileAsItWas()
    {
        Populate();
        Kit.Write(_rig.GameDir(), "config/somemod.json", """{"a":999}""");
        Kit.Write(_rig.GameDir(), "options.txt", "fov:1.0\n");

        var (plan, result) = await Run(_rig.Request(options: new ImportOptions { KeepMyOriginSettings = true }));

        Assert.True(result.Succeeded);
        Assert.Equal("""{"a":999}""", Kit.Read(_rig.GameDir(), "config/somemod.json"));
        Assert.Equal("fov:1.0\n", Kit.Read(_rig.GameDir(), "options.txt"));
        Assert.True(plan.Kept >= 2);
        Assert.Contains($"{plan.Kept} kept", result.SummaryText);
        Assert.True(File.Exists(Dst("config/nested/x.toml")));                     // a file that wasn't there still arrives
    }

    // ------------------------------------------------------------ packs (37, 38, 39)

    [Fact]
    public async Task OnlyTheSelectedResourcePacksAreCopiedZipsAndFoldersAlike()
    {
        Populate();
        var (plan, result) = await Run();
        Assert.True(result.Succeeded);

        Assert.True(File.Exists(Dst("resourcepacks/Faithful.zip")));
        Assert.True(File.Exists(Dst("resourcepacks/FolderPack/pack.mcmeta")));
        Assert.False(File.Exists(Dst("resourcepacks/Unused.zip")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Name == "Missing.zip");
    }

    [Fact]
    public async Task CopyUnusedPacksAlsoBringsTheOthers()
    {
        Populate();
        var (_, result) = await Run(_rig.Request(options: new ImportOptions { CopyUnusedPacks = true }));
        Assert.True(result.Succeeded);

        Assert.True(File.Exists(Dst("resourcepacks/Unused.zip")));
        Assert.True(File.Exists(Dst("shaderpacks/OtherShader.zip")));
        Assert.False(File.ReadAllText(Dst("options.txt")).Contains("Unused.zip")); // copied, but not switched on
    }

    [Fact]
    public async Task APackNeverOverwritesAnotherSameBytesAreSkippedDifferentBytesGetAnImportedName()
    {
        Populate();
        // Faithful.zip exists with different bytes; FolderPack exists with the same bytes.
        Kit.WriteBytes(_rig.GameDir(), "resourcepacks/Faithful.zip", Kit.Zip(Kit.Text("pack.mcmeta", "MINE")));
        var mine = File.ReadAllBytes(Dst("resourcepacks/Faithful.zip"));
        Kit.Write(_rig.GameDir(), "resourcepacks/FolderPack/pack.mcmeta", "{}");
        var folderStamp = File.GetLastWriteTimeUtc(Dst("resourcepacks/FolderPack/pack.mcmeta"));

        var (plan, result) = await Run();
        Assert.True(result.Succeeded);

        Assert.Equal(mine, File.ReadAllBytes(Dst("resourcepacks/Faithful.zip")));  // untouched
        Assert.True(File.Exists(Dst("resourcepacks/Faithful (imported).zip")));
        Assert.Equal(folderStamp, File.GetLastWriteTimeUtc(Dst("resourcepacks/FolderPack/pack.mcmeta")));
        Assert.False(Directory.Exists(Dst("resourcepacks/FolderPack (imported)"))); // identical: skipped
        var packs = File.ReadAllLines(Dst("options.txt")).Single(l => l.StartsWith("resourcePacks:"));
        Assert.Equal("resourcePacks:[\"vanilla\",\"file/Faithful (imported).zip\",\"file/FolderPack\"]", packs);
        Assert.Contains(plan.Lines, l => l.Name == "Faithful (imported).zip" && l.Reason!.Contains("different pack"));
    }

    [Fact]
    public async Task TheActiveIrisPackAndItsSettingsFileAreCopiedAndTheTargetSelectsIt()
    {
        Populate();
        var (_, result) = await Run();
        Assert.True(result.Succeeded);

        Assert.True(File.Exists(Dst("shaderpacks/Complementary.zip")));
        Assert.Equal("SHADOW=2048\n", File.ReadAllText(Dst("shaderpacks/Complementary.zip.txt")));
        Assert.False(File.Exists(Dst("shaderpacks/OtherShader.zip")));
        var iris = File.ReadAllLines(Dst("config/iris.properties"));
        Assert.Contains("shaderPack=Complementary.zip", iris);
        Assert.Contains("enableShaders=true", iris);
    }

    [Fact]
    public async Task ARenamedShaderPackIsReferencedByItsNewNameAndKeepsItsSettingsFile()
    {
        Populate();
        Kit.WriteBytes(_rig.GameDir(), "shaderpacks/Complementary.zip", Kit.Zip(Kit.Text("shaders/c.fsh", "DIFFERENT")));

        var (_, result) = await Run();
        Assert.True(result.Succeeded);

        Assert.True(File.Exists(Dst("shaderpacks/Complementary (imported).zip")));
        Assert.True(File.Exists(Dst("shaderpacks/Complementary (imported).zip.txt")));
        Assert.Contains("shaderPack=Complementary (imported).zip", File.ReadAllLines(Dst("config/iris.properties")).Select(l => l.Replace("\\ ", " ")));
    }

    [Fact]
    public async Task AnOptiFineSourceMapsTheSameWayForFabricAndStaysOptionsshadersForOneEightNine()
    {
        Kit.WriteBytes(_rig.Source, "shaderpacks/Sildurs.zip", Kit.Zip(Kit.Text("shaders/a.fsh", "x")));
        Kit.Write(_rig.Source, "shaderpacks/Sildurs.zip.txt", "OPT=1\n");
        Kit.Write(_rig.Source, "optionsshaders.txt", "shaderPack=Sildurs.zip\nold=1\n");

        var (_, fabric) = await Run();
        Assert.True(fabric.Succeeded);
        Assert.Contains("shaderPack=Sildurs.zip", File.ReadAllLines(Dst("config/iris.properties")));
        Assert.True(File.Exists(Dst("shaderpacks/Sildurs.zip")));
        Assert.False(File.Exists(Dst("optionsshaders.txt")));

        var (_, legacy) = await Run(_rig.Request(target: "1.8.9", mc: "1.8.9"));
        Assert.True(legacy.Succeeded);
        Assert.Equal("shaderPack=Sildurs.zip\nold=1\n", File.ReadAllText(Dst("optionsshaders.txt", "1.8.9")));
        Assert.True(File.Exists(Dst("shaderpacks/Sildurs.zip", "1.8.9")));
        Assert.False(File.Exists(Dst("config/iris.properties", "1.8.9")));
    }

    [Fact]
    public async Task AnActivePackMissingFromTheSourceIsNeedsAttentionAndShadersStayAsTheyAre()
    {
        Kit.Write(_rig.Source, "config/iris.properties", "shaderPack=Gone.zip\nenableShaders=true\n");
        Kit.Write(_rig.GameDir(), "config/iris.properties", "shaderPack=Mine.zip\nenableShaders=true\n");
        Kit.WriteBytes(_rig.GameDir(), "shaderpacks/Mine.zip", Kit.Zip(Kit.Text("a", "b")));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded);
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Name == "Gone.zip");
        Assert.Contains("shaderPack=Mine.zip", File.ReadAllLines(Dst("config/iris.properties")));   // not blanked, not pointed at nothing
    }

    [Fact]
    public async Task OptionsofIsOnlyCarriedTo189AndListedAsNotApplicableElsewhere()
    {
        Kit.Write(_rig.Source, "optionsof.txt", "ofFastRender:true\n");

        var (fabricPlan, _) = await Run();
        Assert.False(File.Exists(Dst("optionsof.txt")));
        Assert.Contains(fabricPlan.Lines, l => l.Section == SummarySection.NotImported && l.Name.Contains("optionsof.txt"));

        var (_, legacy) = await Run(_rig.Request(target: "1.8.9", mc: "1.8.9"));
        Assert.True(legacy.Succeeded);
        Assert.Equal("ofFastRender:true\n", File.ReadAllText(Dst("optionsof.txt", "1.8.9")));
    }

    // ------------------------------------------------------------ worlds (OQ1)

    [Fact]
    public async Task WorldsAreOffByDefault()
    {
        Populate();
        var (plan, result) = await Run();
        Assert.True(result.Succeeded);
        Assert.False(Directory.Exists(Dst("saves")));
        Assert.DoesNotContain(plan.Ops, o => o.Item == PlanItemKind.World);
    }

    [Fact]
    public async Task WorldsAreCopiedWhenAskedNeverOverwriteAndLeaveNoLockFile()
    {
        Populate();
        Kit.Write(_rig.GameDir(), "saves/World1/level.dat", "MY OWN WORLD");

        var (plan, result) = await Run(_rig.Request(options: new ImportOptions { CopyWorlds = true }));
        Assert.True(result.Succeeded);

        Assert.Equal("MY OWN WORLD", Kit.Read(_rig.GameDir(), "saves/World1/level.dat"));    // never overwritten
        Assert.Equal("LEVEL", Kit.Read(_rig.GameDir(), "saves/World1 (imported)/level.dat"));
        Assert.Equal("REGION", Kit.Read(_rig.GameDir(), "saves/World1 (imported)/region/r.0.0.mca"));
        Assert.False(File.Exists(Dst("saves/World1 (imported)/session.lock")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.Imported && l.Name == "World1 (imported)");
    }

    [Fact]
    public async Task ACancelWhileAWorldIsBeingCopiedLeavesNoHalfWorldAndNoStaging()
    {
        Populate();
        for (var i = 0; i < 30; i++) Kit.Write(_rig.Source, $"saves/World1/region/r.{i}.mca", new string('x', 100));
        using var cts = new CancellationTokenSource();
        var opened = 0;
        var engine = new ImportEngine(new ImportEnvironment
        {
            InstancesRoot = _rig.Instances, ImportsRoot = _rig.Imports,
            OnSourceOpen = path => { if (path.Contains("region") && ++opened == 10) cts.Cancel(); },
        });
        var request = _rig.Request(options: new ImportOptions { CopyWorlds = true });
        var plan = await engine.PlanAsync(request, null, CancellationToken.None);
        opened = 0;
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);

        var result = await engine.ApplyAsync(plan, _rig.Settings, null, cts.Token);

        Assert.True(result.Cancelled);
        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.False(Directory.Exists(ImportLayout.Staging(_rig.Imports, plan.ImportId)));
    }

    // ------------------------------------------------------------ summary, log, plug-in point (54, 61)

    [Fact]
    public async Task TheSummaryPutsEachItemUnderTheRightSectionAndIsWrittenToDisk()
    {
        Populate();
        var (plan, result) = await Run();
        Assert.True(result.Succeeded);

        var text = File.ReadAllText(ImportLayout.Summary(_rig.Imports, plan.ImportId));
        Assert.Equal(text, result.SummaryText);

        string Section(string title, string next)
        {
            var start = text.IndexOf(title + Environment.NewLine, StringComparison.Ordinal);
            var end = text.IndexOf(next + Environment.NewLine, start + 1, StringComparison.Ordinal);
            Assert.True(start >= 0 && end > start, $"section {title} missing or out of order");
            return text[start..end];
        }
        var imported = Section("Imported", "Reused game files");
        var attention = Section("Needs attention", "Not imported");
        var notImported = text[text.IndexOf("Not imported" + Environment.NewLine, StringComparison.Ordinal)..];

        Assert.Contains("mymod", imported);
        Assert.Contains("Game settings (options.txt)", imported);
        Assert.Contains("Faithful.zip", imported);
        Assert.Contains("Complementary.zip", imported);
        Assert.Contains("disabledthing", imported);
        Assert.Contains("needslib", attention);                                    // disabled for a missing dependency
        Assert.Contains("Needs ghostlib", text);
        Assert.Contains("Missing.zip", attention);
        Assert.Contains("Origin includes Sodium 0.6.13", notImported);             // built-in
        Assert.Contains("forge-thing.jar", notImported);                            // wrong loader, with the reason
        Assert.Contains("Forge mod", notImported);
        Assert.Contains("old-thing.jar", notImported);                              // wrong Minecraft
        Assert.Contains("discord-token.json", notImported);                         // skipped for safety, by name
        Assert.Contains("Skipped for safety", notImported);
        Assert.Contains("Reused game files" + Environment.NewLine + "  (none)", text);
        Assert.Contains("Reused caches" + Environment.NewLine + "  (none)", text);
    }

    [Fact]
    public async Task ImportLogListsActionsByRelativePathWithNoContentsAndNoAbsolutePaths()
    {
        Populate();
        var (plan, result) = await Run();
        Assert.True(result.Succeeded);

        var log = File.ReadAllText(ImportLayout.Log(_rig.Imports, plan.ImportId));
        Assert.Contains($"instances/1.21.1/accounts/{Rig.Account}/options.txt", log);
        Assert.Contains("instances/1.21.1/mods/mymod.jar", log);
        Assert.DoesNotContain(_rig.Root, log);                                      // relative, never the machine's absolute path
        Assert.DoesNotContain("highContrast", log);                                 // no file contents
        Assert.DoesNotContain("SERVERLIST", log);
        Assert.Contains("import committed", log);
    }

    private sealed class FakeGameFileContributor : IImportPlanContributor
    {
        public Task ContributeAsync(ImportPlan plan, ImportRequest request, SourceFs fs, CancellationToken ct)
        {
            plan.Lines.Add(new SummaryLine(SummarySection.ReusedGameFiles, PlanItemKind.GameFile, "libraries/a.jar", Bytes: 3 * 1048576));
            plan.Lines.Add(new SummaryLine(SummarySection.ReusedCaches, PlanItemKind.Cache, "World1 / overworld"));
            return Task.CompletedTask;
        }
    }

    [Fact]
    public async Task AContributorFromALaterUnitCanAddGameFileAndCacheLinesToThePlan()
    {
        Populate();
        var engine = new ImportEngine(new ImportEnvironment
        {
            InstancesRoot = _rig.Instances, ImportsRoot = _rig.Imports,
            Contributors = new IImportPlanContributor[] { new FakeGameFileContributor() },
        });
        var plan = await Plan(engine: engine);
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.Contains("1 file, 3 MB", result.SummaryText);
        Assert.Contains("World1 / overworld", result.SummaryText);
    }

    // ------------------------------------------------------------ failure rolls back, retention

    [Fact]
    public async Task AFailureInTheMiddleRollsEverythingBackAndSaysSo()
    {
        Populate();
        Directory.CreateDirectory(Dst("config/somemod.json"));     // a FOLDER where a file must go: the copy cannot happen
        File.WriteAllText(Path.Combine(Dst("config/somemod.json"), "keep.txt"), "mine");
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);
        var settingsBefore = _rig.Settings.FileHash();

        var engine = _rig.Engine();
        var plan = await Plan(engine: engine);
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.False(result.Cancelled);
        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.Contains("put back", result.Error);
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal(settingsBefore, _rig.Settings.FileHash());
        Assert.Equal(ImportState.RolledBack, ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!.State);
    }

    [Fact]
    public async Task OnlyTheBackupsOfTheNewestThreeCommittedImportsAreKept()
    {
        Populate(withMods: false);
        var ids = new List<string>();
        for (var i = 0; i < 5; i++)
        {
            Kit.Write(_rig.GameDir(), "config/somemod.json", $"{{\"a\":{100 + i}}}");   // differs each time: a backup per import
            var (plan, result) = await Run();
            Assert.True(result.Succeeded);
            ids.Add(plan.ImportId);
            await Task.Delay(15);                                                       // distinct start times
        }

        var withBackup = ids.Where(id => Directory.Exists(ImportLayout.Backup(_rig.Imports, id))).ToList();
        Assert.Equal(ids.Skip(2), withBackup);
        Assert.All(ids, id => Assert.True(File.Exists(ImportLayout.Summary(_rig.Imports, id))));   // journals and logs stay
    }
}
