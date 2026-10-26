using System.IO.Compression;
using System.Text;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests.Import;

// The code review of the launcher import grid (2026-10-19): the server list's edge input, servers.dat on a rollback,
// the version's shared mods folder (an Origin jar that is too old, a turned-off twin) and undoing an import whose
// shared jars another account's import counts on. Everything runs in temp folders (Rig).
public sealed class ImportSharedModsTests : IDisposable
{
    private readonly Rig _rig = new();
    private const string OtherAccount = "1b2c3d4e5f60718293a4b5c6d7e8f901";

    public void Dispose() => _rig.Dispose();

    private string SharedMods => Path.Combine(_rig.Instances, "1.21.1", "mods");
    private string Shared(string name) => Path.Combine(SharedMods, name);

    private async Task<(ImportPlan Plan, ImportResult Result)> Run(ImportRequest? request = null, ImportEngine? engine = null)
    {
        engine ??= _rig.Engine();
        var plan = await engine.PlanAsync(request ?? _rig.Request(), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        return (plan, await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None));
    }

    // ------------------------------------------------------------ the server list's edge input

    // A servers.dat written byte by byte, so a name can carry Java's modified UTF-8 (what Minecraft writes).
    private static byte[] RawServersDat(params (byte[] Name, string Ip)[] servers)
    {
        var b = new List<byte>();
        void Str(byte[] s) { b.Add((byte)(s.Length >> 8)); b.Add((byte)s.Length); b.AddRange(s); }
        void Utf(string s) => Str(Encoding.UTF8.GetBytes(s));
        b.Add(10); Utf("");
        b.Add(9); Utf("servers"); b.Add(10);
        b.Add((byte)(servers.Length >> 24)); b.Add((byte)(servers.Length >> 16)); b.Add((byte)(servers.Length >> 8)); b.Add((byte)servers.Length);
        foreach (var (name, ip) in servers)
        {
            b.Add(8); Utf("name"); Str(name);
            b.Add(8); Utf("ip"); Utf(ip);
            b.Add(0);
        }
        b.Add(0);
        return b.ToArray();
    }

    private static bool Contains(byte[] haystack, byte[] needle)
    {
        for (var i = 0; i + needle.Length <= haystack.Length; i++)
            if (haystack.AsSpan(i, needle.Length).SequenceEqual(needle)) return true;
        return false;
    }

    // U+1F600 as Java writes it: two surrogates, three bytes each (not the four-byte UTF-8 form).
    private static readonly byte[] ModifiedUtf8Smile = { 0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80 };

    [Fact]
    public void ATruncatedServerListIsNotAServerListAndIsNeverMergedInto()
    {
        var whole = Kit.ServersDat(("Hub", "play.example.net"), ("Mini", "mini.example.net"));
        var cut = whole[..(whole.Length / 2)];

        Assert.Null(ServerList.Read(cut));
        Assert.Null(ServerList.Merge(cut, ServerList.Read(Kit.ServersDat(("New", "new.example.net")))!, out var added));
        Assert.Equal(0, added);
    }

    [Fact]
    public void AGzippedServerListIsRead()
    {
        var plain = Kit.ServersDat(("Hub", "play.example.net"), ("Mini", "mini.example.net"));
        using var ms = new MemoryStream();
        using (var gz = new GZipStream(ms, CompressionLevel.Optimal, leaveOpen: true)) gz.Write(plain);

        var entries = ServerList.Read(ms.ToArray());

        Assert.NotNull(entries);
        Assert.Equal(new[] { "play.example.net", "mini.example.net" }, entries!.Select(e => e.Address));
    }

    [Fact]
    public void AModifiedUtf8NameSurvivesAMergeByteForByteOnBothSides()
    {
        var withSmile = RawServersDat((ModifiedUtf8Smile, "smile.example.net"));
        var imported = ServerList.Read(withSmile)!;
        Assert.Single(imported);

        // Imported into a plain list: the entry's bytes are carried, never re-encoded.
        var into = ServerList.Merge(Kit.ServersDat(("Mine", "mine.example.net")), imported, out var added)!;
        Assert.Equal(1, added);
        Assert.True(Contains(into, ModifiedUtf8Smile));
        Assert.Equal(new[] { "mine.example.net", "smile.example.net" }, ServerList.Read(into)!.Select(e => e.Address));

        // Origin's own list holds the name: a merge keeps it exactly.
        var kept = ServerList.Merge(withSmile, ServerList.Read(Kit.ServersDat(("Hub", "play.example.net")))!, out _)!;
        Assert.True(Contains(kept, ModifiedUtf8Smile));
    }

    [Fact]
    public async Task AFailedImportPutsOriginsServerListBackExactly()
    {
        Kit.WriteBytes(_rig.Source, "servers.dat", Kit.ServersDat(("Hub", "play.example.net")));
        var original = Kit.ServersDat(("Mine", "mine.example.net"));
        Kit.WriteBytes(_rig.GameDir(), "servers.dat", original);
        var failing = EngineBKit.Engine(_rig, new IImportPlanContributor[] { new FailingStep(() => new IOException("disk full")) });
        var plan = await failing.PlanAsync(_rig.Request(), null, CancellationToken.None);
        // The failing step runs after the server list was merged, so the rollback has something to undo.
        Assert.True(plan.Ops.FindIndex(o => o.Kind == OpKind.MergeServers) < plan.Ops.FindIndex(o => o.Kind == OpKind.Custom));

        var result = await failing.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Equal(original, File.ReadAllBytes(Path.Combine(_rig.GameDir(), "servers.dat")));
    }

    // ------------------------------------------------------------ Origin's jar is too old for the imported mods

    // Mods land in a profile of their own; Origin's jars in the version's shared folder are never swapped for it.
    private static string In(ImportPlan plan, string name) => Path.Combine(plan.ProfileModsDir!, name);

    private static string Lines(ImportPlan plan) => string.Join(" // ", plan.Lines.Select(l => $"{l.Section} {l.Kind} {l.Name}: {l.Reason}"));

    [Fact]
    public async Task AfterAnImportPlayLoadsThePlayersVersionNotOriginsAndTheCheckLetsItStart()
    {
        // Import -> profile -> Play, end to end on real files: the launch folder Play builds and the check it runs.
        var origin = _rig.OriginJar("1.21.1", "cloth-config-0.90.0.jar", Kit.FabricJar("cloth-config", "0.90.0"));
        Kit.WriteBytes(_rig.Source, "mods/cloth-config-0.100.0.jar", Kit.FabricJar("cloth-config", "0.100.0"));
        Kit.WriteBytes(_rig.Source, "mods/needsnew.jar", Kit.FabricJar("needsnew", "1.0", depends: new() { ["cloth-config"] = ">=0.100.0" }));
        var (plan, result) = await Run();
        Assert.True(result.Succeeded, result.Error);

        App.Core.Compat.ModFolderScanner.ClearCache();
        App.Core.Compat.LaunchPreflight.ClearCache();
        var overrides = App.Core.Compat.LaunchPreflight.OverridesFor("1.21.1", SharedMods, plan.ProfileModsDir!, _rig.IsOriginJar);
        Assert.Equal(new[] { "cloth-config" }, overrides.OverriddenIds);
        Assert.True(overrides.Skips(origin));

        var launch = App.Core.Profiles.LaunchModFolder.LaunchSet(
            Directory.GetFiles(SharedMods), Directory.GetFiles(plan.ProfileModsDir!), _rig.IsOriginJar, skip: overrides.Skips);
        Assert.DoesNotContain(origin, launch.Values);                                   // Origin's 0.90 is not linked in
        Assert.Contains(In(plan, "cloth-config-0.100.0.jar"), launch.Values);           // the player's 0.100 is
        Assert.Contains(In(plan, "needsnew.jar"), launch.Values);

        var verdict = App.Core.Compat.LaunchPreflight.Evaluate("1.21.1", SharedMods, plan.ProfileModsDir!, true,
            _rig.IsOriginJar, _rig.IsOriginJar);
        Assert.True(verdict.Blocking.Count == 0, string.Join("; ", verdict.Blocking));
        Assert.Contains("cloth-config", verdict.Overrides.OverriddenIds);
        Assert.Contains(verdict.Soft, n => n.Contains("0.100.0 from the profile is used instead of Origin's 0.90.0"));
    }

    [Fact]
    public async Task AnOriginJarTooOldForTheImportedModsStaysAndThePlayersNewerCopyLandsInTheProfile()
    {
        // Will (2026-10-25): the player's own mod list takes priority. Origin's copy in the shared folder is never
        // swapped; the player's newer one lands in the profile and loads there instead (PlayerOverrides), so the mod
        // that needs it lands turned on.
        var oldJar = Kit.FabricJar("cloth-config", "0.90.0");
        var origin = _rig.OriginJar("1.21.1", "cloth-config-0.90.0.jar", oldJar);
        Kit.WriteBytes(_rig.Source, "mods/cloth-config-0.100.0.jar", Kit.FabricJar("cloth-config", "0.100.0"));
        Kit.WriteBytes(_rig.Source, "mods/needsnew.jar", Kit.FabricJar("needsnew", "1.0", depends: new() { ["cloth-config"] = ">=0.100.0" }));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(oldJar, File.ReadAllBytes(origin));                                   // Origin's copy: never swapped
        Assert.False(File.Exists(Shared("cloth-config-0.100.0.jar")), Lines(plan));
        Assert.True(File.Exists(In(plan, "cloth-config-0.100.0.jar")), Lines(plan));       // the player's copy, in the profile
        Assert.True(File.Exists(In(plan, "needsnew.jar")), Lines(plan));                   // its need is met there: on

        var undone = await _rig.Engine().RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.Equal(oldJar, File.ReadAllBytes(origin));
        Assert.False(Directory.Exists(InstanceLayout.ProfileDir(_rig.Instances, "1.21.1", plan.ProfileId!)));
        Assert.Empty(_rig.Settings.Load().Profiles);
    }

    [Fact]
    public async Task AnOlderCopyOfOriginsModIsStillThePlayersChoiceAndWhatNeedsANewerOneLandsTurnedOff()
    {
        var oldJar = Kit.FabricJar("cloth-config", "0.90.0");
        var origin = _rig.OriginJar("1.21.1", "cloth-config-0.90.0.jar", oldJar);
        Kit.WriteBytes(_rig.Source, "mods/cloth-config-0.80.0.jar", Kit.FabricJar("cloth-config", "0.80.0"));
        Kit.WriteBytes(_rig.Source, "mods/needsnew.jar", Kit.FabricJar("needsnew", "1.0", depends: new() { ["cloth-config"] = ">=0.100.0" }));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(oldJar, File.ReadAllBytes(origin));
        Assert.False(File.Exists(Shared("cloth-config-0.80.0.jar")));
        Assert.True(File.Exists(In(plan, "cloth-config-0.80.0.jar")), Lines(plan));        // the player's version wins in the profile
        Assert.True(File.Exists(In(plan, "needsnew.jar.disabled")), Lines(plan));          // nothing gives it 0.100+: off
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Kind == PlanItemKind.Mod);
    }

    // ------------------------------------------------------------ an imported mod that wants another version of Origin's mod

    // 2026-10-20 launch crash: Counter / Spawn Chaining / Unchained (Cobblemon 1.7.3 add-ons) were imported next to
    // Origin's Cobblemon 1.8.0, and Fabric refused to start the game ("only the wrong version is present").
    [Fact]
    public async Task AnImportedModThatNeedsAnotherVersionOfOriginsModGetsThePlayersCopyAndLandsTurnedOn()
    {
        var cobblemon = Kit.FabricJar("cobblemon", "1.8.0+1.21.1");
        var origin = _rig.OriginJar("1.21.1", "cobblemon-1.8.0.jar", cobblemon);
        Kit.WriteBytes(_rig.Source, "mods/cobblemon-1.7.3.jar", Kit.FabricJar("cobblemon", "1.7.3+1.21.1"));
        Kit.WriteBytes(_rig.Source, "mods/counter.jar", Kit.FabricJar("cobbled_counter", "1.9.0", depends: new() { ["cobblemon"] = "1.7.3+1.21.1" }));
        Kit.WriteBytes(_rig.Source, "mods/counter-extra.jar", Kit.FabricJar("counter_extra", "1.0", depends: new() { ["cobbled_counter"] = "*" }));
        Kit.WriteBytes(_rig.Source, "mods/unrelated.jar", Kit.FabricJar("unrelated", "1.0"));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(cobblemon, File.ReadAllBytes(origin));                                 // Origin's copy untouched
        Assert.True(File.Exists(In(plan, "cobblemon-1.7.3.jar")), Lines(plan));             // the player's Cobblemon wins there
        Assert.True(File.Exists(In(plan, "counter.jar")), Lines(plan));                     // so its add-ons start
        Assert.True(File.Exists(In(plan, "counter-extra.jar")));
        Assert.True(File.Exists(In(plan, "unrelated.jar")));
    }

    // Review finding (2026-10-25): the imported lib 2.0 can't take the place of Origin's lib 1.0, and its own dependency
    // (helper 2+, which Origin keeps at 1.0) fails anyway. Origin's lib 1.0 stays ON, so the mod that accepts any lib
    // stays on too, and the lib's line says it was turned off, not "already in Origin's mods".
    [Fact]
    public async Task ThePlayersCopiesWinAndOriginsModThatCannotTakeThemIsKeptOutAndNamed()
    {
        var originLib = Kit.FabricJar("lib", "1.0.0");
        var lib = _rig.OriginJar("1.21.1", "lib-1.0.0.jar", originLib);
        _rig.OriginJar("1.21.1", "helper-1.0.0.jar", Kit.FabricJar("helper", "1.0.0"));
        // Another of Origin's mods says it does not work with helper 2: it is kept out of this profile's launches.
        _rig.OriginJar("1.21.1", "originuser.jar", Kit.FabricJar("originuser", "1.0", depends: new() { ["helper"] = "<2.0.0" }));
        Kit.WriteBytes(_rig.Source, "mods/helper-2.0.0.jar", Kit.FabricJar("helper", "2.0.0"));
        Kit.WriteBytes(_rig.Source, "mods/lib-2.0.0.jar", Kit.FabricJar("lib", "2.0.0", depends: new() { ["helper"] = ">=2.0.0" }));
        Kit.WriteBytes(_rig.Source, "mods/needs-lib2.jar", Kit.FabricJar("needs_lib2", "1.0", depends: new() { ["lib"] = ">=2.0.0" }));
        Kit.WriteBytes(_rig.Source, "mods/app.jar", Kit.FabricJar("app", "1.0", depends: new() { ["lib"] = "*" }));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(originLib, File.ReadAllBytes(lib));                                    // Origin's copy: untouched
        Assert.True(File.Exists(In(plan, "lib-2.0.0.jar")), Lines(plan));
        Assert.True(File.Exists(In(plan, "helper-2.0.0.jar")));
        Assert.True(File.Exists(In(plan, "app.jar")));
        Assert.True(File.Exists(In(plan, "needs-lib2.jar")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Reason != null
            && l.Reason.Contains("originuser") && l.Reason.Contains("helper 2.0.0"));
    }

    [Fact]
    public async Task AnImportedModWhoseNeedIsMetByOriginsModLandsTurnedOnAsBefore()
    {
        _rig.OriginJar("1.21.1", "cloth-config-0.100.0.jar", Kit.FabricJar("cloth-config", "0.100.0"));
        Kit.WriteBytes(_rig.Source, "mods/cloth-config-0.100.0.jar", Kit.FabricJar("cloth-config", "0.100.0"));
        Kit.WriteBytes(_rig.Source, "mods/needsok.jar", Kit.FabricJar("needsok", "1.0", depends: new() { ["cloth-config"] = ">=0.90.0" }));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.True(File.Exists(In(plan, "needsok.jar")), Lines(plan));
        Assert.False(File.Exists(In(plan, "needsok.jar.disabled")));
        Assert.False(File.Exists(In(plan, "cloth-config-0.100.0.jar")));                    // Origin's copy is the one used
    }

    // ------------------------------------------------------------ the player's own jars in the shared folder

    // A profile launch loads only Origin's own turned-on jars from the shared folder (LaunchModFolder), so a player's
    // loose jar there - on or off - is not "already in Origin": the imported mod lands in the profile, turned on.
    [Fact]
    public async Task APlayersOwnJarInTheSharedFolderDoesNotCountTheImportedOneLandsInTheProfile()
    {
        var loose = Kit.WriteBytes(SharedMods, "mymod.jar.disabled", Kit.FabricJar("mymod", "1.0"));
        var before = File.ReadAllBytes(loose);
        Kit.WriteBytes(_rig.Source, "mods/mymod.jar", Kit.FabricJar("mymod", "1.0"));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.True(File.Exists(In(plan, "mymod.jar")), Lines(plan));
        Assert.Equal(before, File.ReadAllBytes(loose));                                     // the shared folder: untouched
        Assert.False(File.Exists(Shared("mymod.jar")));
    }

    [Fact]
    public void ATwinIsFoundTurnedOnOrOffButNeverTheJarTheSwapReplaces()
    {
        Directory.CreateDirectory(SharedMods);
        File.WriteAllText(Shared("x.jar"), "on");
        PlannedOp Op(string dest, string? replaces = null) => new()
        {
            Kind = OpKind.CopyFile, Item = PlanItemKind.Mod, Label = "x", Dest = Shared(dest), Replaces = replaces,
        };

        Assert.Equal(Shared("x.jar"), ImportEngine.ModTwinThere(Op("x.jar.disabled")));  // no "x.jar.disabled.disabled"
        Assert.Equal(Shared("x.jar"), ImportEngine.ModTwinThere(Op("x.jar")));
        Assert.Null(ImportEngine.ModTwinThere(Op("x.jar", replaces: Shared("x.jar"))));
        Assert.Null(ImportEngine.ModTwinThere(Op("y.jar")));
    }

    // ------------------------------------------------------------ undo and the profile another import counts on

    private void PopulateOneMod() =>
        Kit.WriteBytes(_rig.Source, "mods/onlyhere.jar", Kit.FabricJar("onlyhere", "1.0"));

    // Another account imports the same setup: it reuses the profile (same files) instead of making "Folder: Hypixel 2".
    // Undoing the FIRST import must not take that profile away from the second.
    [Fact]
    public async Task UndoKeepsAProfileAnotherAccountsImportCountsOn()
    {
        PopulateOneMod();
        var (planA, a) = await Run();
        Assert.True(a.Succeeded, a.Error);
        var (planB, b) = await Run(_rig.Request(account: OtherAccount) with { ExistingProfiles = _rig.Settings.Load().Profiles });
        Assert.True(b.Succeeded, b.Error);
        Assert.True(planB.ProfileReused);
        Assert.Equal(planA.ProfileId, planB.ProfileId);

        var undone = await _rig.Engine().RollbackAsync(planA.ImportId, _rig.Settings);

        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.True(File.Exists(In(planA, "onlyhere.jar")));                               // the other account still needs it
        Assert.Contains(_rig.Settings.Load().Profiles, p => p.Id == planA.ProfileId);
    }

    [Fact]
    public async Task UndoWithNobodyCountingOnTheProfileRemovesItAndItsSettingsEntry()
    {
        PopulateOneMod();
        var (plan, result) = await Run();
        Assert.True(result.Succeeded, result.Error);
        Assert.Single(_rig.Settings.Load().Profiles);

        var undone = await _rig.Engine().RollbackAsync(plan.ImportId, _rig.Settings);

        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.False(Directory.Exists(InstanceLayout.ProfileDir(_rig.Instances, "1.21.1", plan.ProfileId!)));
        var settings = _rig.Settings.Load();
        Assert.Empty(settings.Profiles);
        Assert.Equal("1.21.11", settings.SelectedVersion);                                 // the selection goes back too
        Assert.Null(settings.SelectedProfileId);
    }

    [Fact]
    public async Task UndoIsRefusedWhileAnotherAccountsGameRunsOnThatVersion()
    {
        PopulateOneMod();
        var (plan, result) = await Run();
        Assert.True(result.Succeeded, result.Error);
        var other = Path.Combine(_rig.Instances, "1.21.1", "accounts", OtherAccount);
        Directory.CreateDirectory(other);
        _rig.RunningDirs.Add(other);

        var refused = await _rig.Engine().RollbackAsync(plan.ImportId, _rig.Settings);

        Assert.NotEqual(ImportState.RolledBack, refused.State);
        Assert.Contains("Close Minecraft first", refused.Error);
        Assert.True(File.Exists(In(plan, "onlyhere.jar")));                                // any account may play the profile

        _rig.RunningDirs.Remove(other);                                                 // closed: the undo goes through
        var undone = await _rig.Engine().RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.False(File.Exists(In(plan, "onlyhere.jar")));
    }
}

// Packs from several setups of one run: the same pack under two names lands once.
public sealed class BulkPackDedupeTests : IDisposable
{
    private readonly BulkFixture _f = new();
    public void Dispose() => _f.Dispose();

    [Fact]
    public async Task TheSamePackUnderTwoNamesFromTwoSetupsLandsOnce()
    {
        var a = _f.Add("Survival", "1.21.1", playtimeHours: 20);
        var c = _f.Add("Testing", "1.21.1", playtimeHours: 2);
        var pack = Kit.Zip(Kit.Text("pack.mcmeta", "same bytes"), Kit.Text("assets/x.png", "png"));
        Kit.WriteBytes(a.GameDir, "resourcepacks/Cool Pack.zip", pack);
        Kit.WriteBytes(c.GameDir, "resourcepacks/Cool Pack v2.zip", pack);
        var options = new ImportOptions { CopyWorlds = true, CopyScreenshots = true, CopyUnusedPacks = true };

        var plan = await _f.Plan(_f.Engine(), _f.Request(BulkSelection.OfLauncher(SourceKind.Prism), options));
        var result = await _f.Engine().ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var landed = Directory.GetFiles(_f.Target("1.21.1", "resourcepacks"), "Cool Pack*");
        Assert.Single(landed);
        Assert.Equal(pack, File.ReadAllBytes(landed[0]));
    }
}

// Will, 2026-10-19: one launcher's settings (Lunar keeps one set for every version), its most played setup's for every
// version it has no setup on, migrated to that version: only the keys that version knows, in its own format.
public sealed class SettingsCarryTests : IDisposable
{
    private readonly BulkFixture _f = new();
    public void Dispose() => _f.Dispose();

    private const string ModernOptions =
        "version:3955\nfov:0.25\ngraphicsMode:2\ntoggleCrouch:true\nkey_key.forward:key.keyboard.up\nlang:en_us\n";

    private async Task<BulkImportResult> Import()
    {
        var grid = new ImportGridModel(_f.Setups);
        if (grid.SettingsLauncher != SourceKind.Lunar) grid.Toggle(SourceKind.Lunar, GridColumn.Settings);
        Assert.Equal(SourceKind.Lunar, grid.SettingsLauncher);
        var plan = await _f.Plan(_f.Engine(), _f.Request(grid: grid.Grid()));
        return await _f.Engine().ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
    }

    [Fact]
    public async Task AVersionLunarHasNoSetupOnGetsItsMostPlayedSettingsMigrated()
    {
        var lunar = _f.Add("Lunar 1.21.1", "1.21.1", SourceKind.Lunar, playtimeHours: 40);
        Kit.Write(lunar.GameDir, "options.txt", ModernOptions);
        _f.Add("PvP", "1.8.9", SourceKind.CurseForge, LoaderKind.Forge, playtimeHours: 2);

        var result = await Import();

        Assert.True(result.Succeeded, result.Error);
        var lines = File.ReadAllLines(_f.Target("1.8.9", "options.txt"));
        Assert.Contains("fov:0.25", lines);
        Assert.Contains("key_key.forward:200", lines);                            // GLFW name -> 1.8.9's LWJGL code
        Assert.Contains("lang:en_US", lines);
        Assert.Contains("fancyGraphics:true", lines);                             // from graphicsMode 2 (fabulous)
        Assert.DoesNotContain(lines, l => l.StartsWith("toggleCrouch:") || l.StartsWith("graphicsMode:") || l.StartsWith("version:"));
    }

    [Fact]
    public async Task AVersionWithItsOwnLunarSetupKeepsItsOwnSettings()
    {
        var main = _f.Add("Lunar 1.21.1", "1.21.1", SourceKind.Lunar, playtimeHours: 40);
        Kit.Write(main.GameDir, "options.txt", ModernOptions);
        var old = _f.Add("Lunar 1.20.4", "1.20.4", SourceKind.Lunar, playtimeHours: 3);
        Kit.Write(old.GameDir, "options.txt", "version:3700\nfov:0.75\n");

        var result = await Import();

        Assert.True(result.Succeeded, result.Error);
        var lines = File.ReadAllLines(_f.Target("1.20.4", "options.txt"));
        Assert.Contains("fov:0.75", lines);
        Assert.DoesNotContain("fov:0.25", lines);
        Assert.Contains("fov:0.25", File.ReadAllLines(_f.Target("1.21.1", "options.txt")));
    }

    [Fact]
    public void KeysATargetVersionDoesntKnowAreDropped()
    {
        var modern = new Dictionary<string, string?>
        {
            ["fov"] = "0.25", ["graphicsMode"] = "1", ["showNowPlayingToast"] = "true", ["toggleCrouch"] = "true",
            ["key_key.jump"] = "key.keyboard.space",
        };
        var to1211 = SettingsMigrator.CarryOptions(modern, "1.21.11");                 // graphicsPreset era
        Assert.False(to1211.ContainsKey("graphicsMode"));
        Assert.False(to1211.ContainsKey("showNowPlayingToast"));
        Assert.Equal("true", to1211["toggleCrouch"]);
        var to189 = SettingsMigrator.CarryOptions(modern, "1.8.9");
        Assert.Equal(new[] { "fancyGraphics", "fov", "key_key.jump" }, to189.Keys.OrderBy(k => k, StringComparer.Ordinal));
        Assert.Equal("true", to189["fancyGraphics"]);

        var legacy = new Dictionary<string, string?> { ["fov"] = "0.5", ["fancyGraphics"] = "false", ["streamFps"] = "0.3", ["key_key.jump"] = "57" };
        var to1211Back = SettingsMigrator.CarryOptions(legacy, "1.21.1");
        Assert.Equal(new[] { "fov", "graphicsMode", "key_key.jump" }, to1211Back.Keys.OrderBy(k => k, StringComparer.Ordinal));
        Assert.Equal("0", to1211Back["graphicsMode"]);                                 // 1.8.9 Fast stays Fast
        Assert.Equal("1", SettingsMigrator.CarryOptions(new Dictionary<string, string?> { ["fancyGraphics"] = "true" }, "1.16.5")["graphicsMode"]);
        Assert.Empty(SettingsMigrator.CarryOptions(new Dictionary<string, string?> { ["fancyGraphics"] = "true" }, "1.21.11"));

        // The boundaries, as read from each version's Options class (javap, 2026-10-19).
        Assert.True(OptionsTxtFormat.KnownIn("musicToast", "26.1"));
        Assert.True(OptionsTxtFormat.KnownIn("graphicsPreset", "1.21.11"));
        Assert.False(OptionsTxtFormat.KnownIn("graphicsMode", "26.1"));
        Assert.True(OptionsTxtFormat.KnownIn("graphicsMode", "1.21.10"));
        Assert.False(OptionsTxtFormat.KnownIn("musicToast", "1.21.10"));
        Assert.True(OptionsTxtFormat.KnownIn("showNowPlayingToast", "1.21.8"));
        Assert.True(OptionsTxtFormat.KnownIn("showNowPlayingToast", "1.21.6"));
        Assert.False(OptionsTxtFormat.KnownIn("showNowPlayingToast", "1.21.5"));
        Assert.True(OptionsTxtFormat.KnownIn("renderClouds", "1.8.9"));
        Assert.False(OptionsTxtFormat.KnownIn("clouds", "1.8.9"));                       // 1.8.9 reads renderClouds only
    }
}
