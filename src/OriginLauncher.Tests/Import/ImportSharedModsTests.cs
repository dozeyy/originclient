using System.IO.Compression;
using System.Text;
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

    [Fact]
    public async Task AnOriginJarTooOldForTheImportedModsIsSwappedForTheImportedOneAndUndoPutsItBack()
    {
        var oldJar = Kit.FabricJar("cloth-config", "0.90.0");
        Kit.WriteBytes(SharedMods, "cloth-config-0.90.0.jar", oldJar);
        Kit.WriteBytes(_rig.Source, "mods/cloth-config-0.100.0.jar", Kit.FabricJar("cloth-config", "0.100.0"));
        Kit.WriteBytes(_rig.Source, "mods/needsnew.jar", Kit.FabricJar("needsnew", "1.0", depends: new() { ["cloth-config"] = ">=0.100.0" }));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.True(File.Exists(Shared("cloth-config-0.100.0.jar")), string.Join(" // ", plan.Lines.Select(l => $"{l.Section} {l.Kind} {l.Name}: {l.Reason}")));
        Assert.False(File.Exists(Shared("cloth-config-0.90.0.jar")));                     // never two copies of one mod
        Assert.True(File.Exists(Shared("needsnew.jar")));
        Assert.Contains(plan.Lines, l => l.Reason != null && l.Reason.Contains("replacing Origin's older 0.90.0"));

        var undone = await _rig.Engine().RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.Equal(oldJar, File.ReadAllBytes(Shared("cloth-config-0.90.0.jar")));
        Assert.False(File.Exists(Shared("cloth-config-0.100.0.jar")));
    }

    [Fact]
    public async Task WhenTheImportedJarIsNoNewerOriginsIsKeptAndTheLineSaysSo()
    {
        var oldJar = Kit.FabricJar("cloth-config", "0.90.0");
        Kit.WriteBytes(SharedMods, "cloth-config-0.90.0.jar", oldJar);
        Kit.WriteBytes(_rig.Source, "mods/cloth-config-0.80.0.jar", Kit.FabricJar("cloth-config", "0.80.0"));
        Kit.WriteBytes(_rig.Source, "mods/needsnew.jar", Kit.FabricJar("needsnew", "1.0", depends: new() { ["cloth-config"] = ">=0.100.0" }));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(oldJar, File.ReadAllBytes(Shared("cloth-config-0.90.0.jar")));
        Assert.False(File.Exists(Shared("cloth-config-0.80.0.jar")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Kind == PlanItemKind.Mod);
    }

    // ------------------------------------------------------------ the .disabled twin

    [Fact]
    public async Task ATurnedOffOriginTwinIsNotAlreadyInOriginTheImportedOnLandsInItsPlace()
    {
        Kit.WriteBytes(SharedMods, "mymod.jar.disabled", Kit.FabricJar("mymod", "1.0"));
        Kit.WriteBytes(_rig.Source, "mods/mymod.jar", Kit.FabricJar("mymod", "1.0"));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.True(File.Exists(Shared("mymod.jar")));
        Assert.False(File.Exists(Shared("mymod.jar.disabled")));                        // one copy, turned on
        Assert.Contains(plan.Lines, l => l.Reason == SharedModsLedger.TurnedOffNote);
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

    // ------------------------------------------------------------ undo and the shared mods folder

    private void PopulateOneMod() =>
        Kit.WriteBytes(_rig.Source, "mods/onlyhere.jar", Kit.FabricJar("onlyhere", "1.0"));

    [Fact]
    public async Task UndoKeepsASharedJarAnotherAccountsImportCountsOn()
    {
        PopulateOneMod();
        var (planA, a) = await Run();
        Assert.True(a.Succeeded, a.Error);
        var (_, b) = await Run(_rig.Request(account: OtherAccount));                   // finds the jar already in Origin
        Assert.True(b.Succeeded, b.Error);

        var undone = await _rig.Engine().RollbackAsync(planA.ImportId, _rig.Settings);

        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.True(File.Exists(Shared("onlyhere.jar")));                              // the other account still needs it
    }

    [Fact]
    public async Task UndoWithNobodyCountingOnTheSharedJarRemovesIt()
    {
        PopulateOneMod();
        var (plan, result) = await Run();
        Assert.True(result.Succeeded, result.Error);

        var undone = await _rig.Engine().RollbackAsync(plan.ImportId, _rig.Settings);

        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.False(File.Exists(Shared("onlyhere.jar")));
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
        Assert.True(File.Exists(Shared("onlyhere.jar")));

        _rig.RunningDirs.Remove(other);                                                 // closed: the undo goes through
        var undone = await _rig.Engine().RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.False(File.Exists(Shared("onlyhere.jar")));
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
        Assert.Equal(new[] { "fov", "key_key.jump" }, to1211Back.Keys.OrderBy(k => k, StringComparer.Ordinal));

        Assert.True(OptionsTxtFormat.KnownIn("musicToast", "26.1"));
        Assert.False(OptionsTxtFormat.KnownIn("musicToast", "1.21.10"));
        Assert.True(OptionsTxtFormat.KnownIn("showNowPlayingToast", "1.21.8"));
    }
}
