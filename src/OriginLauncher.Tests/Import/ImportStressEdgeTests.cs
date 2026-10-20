using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Sources;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Stress round (2026-10-19), the awkward inputs: unicode, spaces and very long paths; corrupt and missing files;
// versions Origin doesn't support; full disks and IO failures; two imports at once; and the two promises that hold
// whatever happens: the source is never changed, and nothing is written outside Origin's own folders (the shared
// per-version mods/ folder included). Every folder is a temp folder; nothing real is touched.
public class ImportStressEdgeTests : IDisposable
{
    private readonly Rig _rig = new();

    public void Dispose() => _rig.Dispose();

    private static readonly ImportOptions WithWorlds = new() { CopyWorlds = true };

    private ImportRequest RequestFor(string gameDir, ImportOptions? options = null, string? account = Rig.Account, string target = "1.21.1") =>
        _rig.Request(target: target, options: options, account: account) with { Source = _rig.SourceInstance() with { GameDir = gameDir } };

    // ------------------------------------------------------------ unicode, spaces, long paths

    [Fact]
    public async Task UnicodeSpacesAndVeryLongPathsComeOverByteForByte()
    {
        var src = Path.Combine(_rig.Root, "Jörg's Setups", "日本語 フォルダ", "my  game (1)");
        TypicalSource.Populate(src);
        const string pack = "Pack — ünï 🌍.zip";
        Kit.WriteBytes(src, $"resourcepacks/{pack}", Kit.Zip(Kit.Text("pack.mcmeta", "unicode")));
        Kit.Write(src, "options.txt", TypicalSource.Options.Replace("file/Faithful.zip", $"file/{pack}"));
        Kit.WriteBytes(src, "mods/mod é ñ.jar", Kit.FabricJar("unicodemod", "1.0", minecraft: "~1.21.1"));
        var world = "Mundo ñ café 🌍";
        Kit.Write(src, $"saves/{world}/level.dat", "LEVEL");
        // A region file deep enough that its full path is far past the old 260-character limit. The depth comes from
        // the real temp folder's length (CI's is much shorter than a Windows profile's), so it always gets past 300.
        var worldDir = Path.Combine(src, "saves", world);
        var segments = new List<string>();
        while (worldDir.Length + segments.Sum(s => s.Length + 1) + "/r.0.0.mca".Length <= 320)
            segments.Add($"sub folder {segments.Count:D2} ü");
        var deep = string.Join("/", segments);
        var deepFile = Kit.Write(src, $"saves/{world}/{deep}/r.0.0.mca", "DEEP");
        Assert.True(deepFile.Length > 300, $"the deep path is only {deepFile.Length} characters");
        var sourceBefore = Kit.TreeHash(src, mtimes: true);

        var plan = await _rig.Engine().PlanAsync(RequestFor(src, WithWorlds), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join(" ", plan.Blocked));
        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var game = _rig.GameDir();
        Assert.Equal(Kit.TreeHash(Path.Combine(src, "saves", world)), Kit.TreeHash(Path.Combine(game, "saves", world)));
        Assert.True(File.Exists(Path.Combine(game, "resourcepacks", pack)));
        // The list is JSON (Minecraft reads it with Gson on every version), so escaped and plain spellings are the same name.
        var packsLine = File.ReadAllLines(Path.Combine(game, "options.txt"), Encoding.UTF8).Single(l => l.StartsWith("resourcePacks:"));
        Assert.Contains($"file/{pack}", JsonSerializer.Deserialize<string[]>(packsLine["resourcePacks:".Length..])!);
        Assert.True(File.Exists(Path.Combine(InstanceLayout.ModsDir(_rig.Instances, "1.21.1"), "mod é ñ.jar")));
        Assert.Equal(sourceBefore, Kit.TreeHash(src, mtimes: true));
    }

    // ------------------------------------------------------------ corrupt, missing, unsupported

    [Fact]
    public async Task CorruptJarsAndGarbageSettingsFilesNeverStopTheImport()
    {
        TypicalSource.Populate(_rig.Source);
        Kit.WriteBytes(_rig.Source, "mods/zero-bytes.jar", Array.Empty<byte>());
        Kit.WriteBytes(_rig.Source, "mods/random-bytes.jar", Enumerable.Range(0, 4096).Select(i => (byte)(i * 31 % 251)).ToArray());
        Kit.WriteBytes(_rig.Source, "mods/bad-metadata.jar", Kit.Zip(Kit.Text("fabric.mod.json", "{ \"id\": \"broken\", \"version\": ")));
        Kit.WriteBytes(_rig.Source, "mods/truncated.jar", Kit.FabricJar("truncated", "1.0").Take(40).ToArray());
        Kit.WriteBytes(_rig.Source, "options.txt", Enumerable.Range(0, 2048).Select(i => (byte)(i % 256)).ToArray());
        Kit.Write(_rig.Source, "config/iris.properties", "\0\0\0shaderPack=\u0001\n=\n==\n");
        Kit.Write(_rig.Source, "config/sodium-options.json", "{ not json at all");
        Kit.WriteBytes(_rig.Source, "servers.dat", new byte[] { 0x0A, 0xFF, 0xFF });
        Kit.Write(_rig.Source, "saves/NoLevelDat/region/r.0.0.mca", "R"); // not a world: no level.dat

        var plan = await _rig.Engine().PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join(" ", plan.Blocked));
        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        foreach (var jar in new[] { "zero-bytes.jar", "random-bytes.jar", "bad-metadata.jar", "truncated.jar" })
            Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name.Contains(jar));
        Assert.False(Directory.Exists(Path.Combine(_rig.GameDir(), "saves", "NoLevelDat")));
        Assert.True(File.Exists(Path.Combine(InstanceLayout.ModsDir(_rig.Instances, "1.21.1"), "mymod.jar")));
    }

    [Theory]
    [InlineData("1.12.2")] // pulled
    [InlineData("1.7.10")] // never supported
    [InlineData("b1.7.3")]
    [InlineData("")]
    public async Task AVersionOriginDoesntSupportIsRefusedBeforeAnythingIsWritten(string target)
    {
        TypicalSource.Populate(_rig.Source);
        var before = Kit.TreeHash(_rig.Root, mtimes: true);

        var plan = await _rig.Engine().PlanAsync(_rig.Request(target: target, mc: target), null, CancellationToken.None);
        Assert.False(plan.CanApply);
        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.False(string.IsNullOrWhiteSpace(result.Error));
        Assert.Equal(before, Kit.TreeHash(_rig.Root, mtimes: true));
    }

    [Fact]
    public async Task ASourceFolderThatVanishedAfterThePlanWritesNothingVisible()
    {
        TypicalSource.Populate(_rig.Source);
        var plan = await _rig.Engine().PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);
        Directory.Delete(_rig.Source, recursive: true);

        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        // Every item was left out (or the run refused): either way Origin's game folder holds nothing half-done.
        if (result.Succeeded) Assert.Contains("Couldn't read it in your old setup", result.SummaryText);
        Assert.False(Directory.Exists(Path.Combine(_rig.GameDir(), "saves", "World1")));
        if (!result.Succeeded) Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
    }

    // ------------------------------------------------------------ disk full and IO failures

    // A plan step that fails the way a full disk does (ERROR_DISK_FULL), after everything else has been written.
    private sealed class FullDiskAtTheEnd : IImportPlanContributor
    {
        public Task ContributeAsync(ImportPlan plan, ImportRequest request, SourceFs fs, CancellationToken ct)
        {
            plan.Ops.Add(new PlannedOp
            {
                Kind = OpKind.Custom, Item = PlanItemKind.Cache, Label = "caches", Dest = plan.TargetGameDir,
                Run = _ => throw new IOException("There is not enough space on the disk.", unchecked((int)0x80070070)),
            });
            return Task.CompletedTask;
        }
    }

    [Fact]
    public async Task ADiskThatFillsUpPartWayPutsEveryFileAndSettingBack()
    {
        TypicalSource.Populate(_rig.Source);
        Kit.Write(_rig.GameDir(), "options.txt", "fov:1.0\n");
        var engine = EngineBKit.Engine(_rig, new IImportPlanContributor[] { new FullDiskAtTheEnd() });
        var plan = await engine.PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);
        var settings = _rig.Settings.FileHash();

        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.Contains("not enough space", result.Error, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal(settings, _rig.Settings.FileHash());
        Assert.False(Directory.Exists(ImportLayout.Staging(_rig.Imports, plan.ImportId)));
    }

    [Fact]
    public async Task AFileSittingWhereOriginsFolderShouldBeRollsBackCleanly()
    {
        TypicalSource.Populate(_rig.Source);
        Kit.Write(_rig.GameDir(), "resourcepacks", "a file, not a folder");
        var plan = await _rig.Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);

        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        if (!result.Succeeded) Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal("a file, not a folder", File.ReadAllText(Path.Combine(_rig.GameDir(), "resourcepacks")));
    }

    // ------------------------------------------------------------ where it writes

    [Fact]
    public async Task NothingIsWrittenOutsideOriginsFoldersAndTheSharedModsFolderOnlyGainsFiles()
    {
        TypicalSource.Populate(_rig.Source);
        var shared = InstanceLayout.ModsDir(_rig.Instances, "1.21.1");
        foreach (var (name, jar) in new[] { ("originclient-1.21.1.jar", Kit.OriginClientJar()), ("mymod.jar", Kit.FabricJar("mymod", "0.9")) })
            File.SetLastWriteTimeUtc(Kit.WriteBytes(shared, name, jar), TypicalSource.Old); // mymod: same id, older: never replaced in place
        // Mods now land in the version's shared folder, but only as additions: what Origin already had stays byte-for-byte.
        static string Stamp(string p) => Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(File.ReadAllBytes(p)))
                                         + "|" + File.GetLastWriteTimeUtc(p).Ticks;
        var sharedBefore = Directory.GetFiles(shared).ToDictionary(p => p, Stamp);
        var origin = Path.Combine(_rig.Root, "OriginLauncher");
        string Outside() => string.Join("\n", Directory.EnumerateFileSystemEntries(_rig.Root, "*", SearchOption.AllDirectories)
            .Where(p => !p.StartsWith(origin, StringComparison.OrdinalIgnoreCase) && Path.GetFileName(p) != "settings.json")
            .Select(p => $"{p}|{(File.Exists(p) ? new FileInfo(p).Length + "|" + File.GetLastWriteTimeUtc(p).Ticks : "dir")}")
            .OrderBy(p => p, StringComparer.Ordinal));
        var outsideBefore = Outside();

        var plan = await _rig.Engine().PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(outsideBefore, Outside());
        foreach (var (path, stamp) in sharedBefore) Assert.Equal(stamp, Stamp(path));  // never replaced or touched in place
        var journal = ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!;
        Assert.NotEmpty(journal.Entries);
        foreach (var entry in journal.Entries.Where(e => e.Path != null))
        {
            Assert.True(Inside(entry.Path!, _rig.Instances) || Inside(entry.Path!, _rig.Imports), entry.Path);
            if (Inside(entry.Path!, shared))
                Assert.True(entry.Kind == JournalEntryKinds.Created, $"the shared mods folder was changed in place: {entry.Path}");
            if (entry.Backup != null) Assert.True(Inside(entry.Backup, _rig.Imports), entry.Backup);
        }
    }

    private static bool Inside(string path, string root) =>
        Path.GetFullPath(path).StartsWith(Path.TrimEndingDirectorySeparator(Path.GetFullPath(root)) + Path.DirectorySeparatorChar,
            StringComparison.OrdinalIgnoreCase);

    // ------------------------------------------------------------ two at once

    // TypicalSource's World1, as an import brings it: level.dat and the region, never the game's session.lock.
    private static void AssertWholeWorld(string gameDir)
    {
        Assert.Equal("LEVEL", Kit.Read(gameDir, "saves/World1/level.dat"));
        Assert.Equal("REGION", Kit.Read(gameDir, "saves/World1/region/r.0.0.mca"));
        Assert.False(Kit.Exists(gameDir, "saves/World1/session.lock"));
    }

    // The rig's own engine records every source open into a plain List, which two threads can't share; these tests
    // only need the engine itself, so they leave that record out.
    private static ImportEngine ThreadSafeEngine(Rig rig) => new(new ImportEnvironment
    {
        InstancesRoot = rig.Instances, ImportsRoot = rig.Imports, FreeSpace = _ => long.MaxValue, IsGameRunning = _ => false,
    });

    [Fact]
    public async Task TwoAccountsImportingIntoTheSameVersionAtTheSameTimeBothLandWhole()
    {
        TypicalSource.Populate(_rig.Source);
        const string other = "0123456789abcdef0123456789abcdef";
        var engine = ThreadSafeEngine(_rig);
        var planA = await engine.PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        var planB = await engine.PlanAsync(_rig.Request(options: WithWorlds, account: other), null, CancellationToken.None);

        var results = await Task.WhenAll(
            engine.ApplyAsync(planA, _rig.Settings, null, CancellationToken.None),
            engine.ApplyAsync(planB, _rig.Settings, null, CancellationToken.None));

        Assert.All(results, r => Assert.True(r.Succeeded, r.Error));
        foreach (var plan in new[] { planA, planB })
        {
            AssertWholeWorld(plan.TargetGameDir);
            Assert.Contains("renderDistance:12", File.ReadAllText(Path.Combine(plan.TargetGameDir, "options.txt")));
        }
        Assert.NotEqual(planA.TargetGameDir, planB.TargetGameDir);
    }

    // The cause of the test above failing 7 runs in 10 on Windows: one import's commit prunes old backups, which READS
    // every journal, while the other import renames its new journal over the old one. A reader that opened the file
    // without delete-sharing makes that rename "Access to the path is denied", and the import rolled back. Readers
    // and the atomic write are run against each other here: no write may ever fail.
    [Fact]
    public async Task ReadingAJournalNeverBlocksItsNextAtomicWrite()
    {
        var path = Path.Combine(_rig.Imports, "race", ImportLayout.JournalFile);
        var journal = new ImportJournal { ImportId = "race", State = ImportState.Applying };
        ImportJournalStore.Save(journal, path);
        using var stop = new CancellationTokenSource();
        var readers = Enumerable.Range(0, 3).Select(_ => Task.Run(() =>
        {
            // Each reader reads about once a millisecond: far more often than any real one, but not holding the file
            // open without a break (no writer could ever replace a file that is never closed).
            while (!stop.IsCancellationRequested)
            {
                ImportJournalStore.Load(path);
                Thread.Sleep(1);
            }
        })).ToArray();
        try
        {
            for (var i = 0; i < 2000; i++)
            {
                journal.SourceName = $"write {i}";
                ImportJournalStore.Save(journal, path);
            }
        }
        finally
        {
            stop.Cancel();
            await Task.WhenAll(readers);
        }
        Assert.Equal("write 1999", ImportJournalStore.Load(path)!.SourceName);
    }

    [Fact]
    public async Task TheSameImportStartedTwiceAtOnceNeverDamagesTheOneThatFinished()
    {
        for (var round = 0; round < 8; round++)
        {
            using var rig = new Rig();
            TypicalSource.Populate(rig.Source);
            var engine = ThreadSafeEngine(rig);
            var a = await engine.PlanAsync(rig.Request(options: WithWorlds), null, CancellationToken.None);
            var b = await engine.PlanAsync(rig.Request(options: WithWorlds), null, CancellationToken.None);

            var results = await Task.WhenAll(
                Task.Run(() => engine.ApplyAsync(a, rig.Settings, null, CancellationToken.None)),
                Task.Run(() => engine.ApplyAsync(b, rig.Settings, null, CancellationToken.None)));

            // One lands; the other waited for it, saw its plan was older than the folder, and changed nothing.
            Assert.Single(results, r => r.Succeeded);
            var refused = Assert.Single(results, r => !r.Succeeded);
            Assert.Equal(ImportState.Planned, refused.State);
            Assert.Equal(ImportTargetLocks.StalePlan, refused.Error);
            // Whatever the order, what a finished import put there is all there: the world whole, the settings merged.
            var game = rig.GameDir();
            AssertWholeWorld(game);
            Assert.Contains("renderDistance:12", File.ReadAllText(Path.Combine(game, "options.txt")));
            // One finished: its mods are in the version's shared mods folder (the refused one never wrote there).
            var mods = InstanceLayout.ModsDir(rig.Instances, "1.21.1");
            Assert.True(File.Exists(Path.Combine(mods, "mymod.jar")), $"round {round}: a finished import lost its mods");
            Assert.Empty(rig.Settings.Load().Profiles);
        }
    }

    [Fact]
    public async Task APlanMadeBeforeAnotherImportFinishedChangesNothingAndPlanningAgainBringsOnlyWhatsLeft()
    {
        TypicalSource.Populate(_rig.Source);
        var engine = _rig.Engine();
        var first = await engine.PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        var stale = await engine.PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        Assert.True((await engine.ApplyAsync(first, _rig.Settings, null, CancellationToken.None)).Succeeded);
        var after = Kit.TreeHash(_rig.Instances, mtimes: true);
        var settings = _rig.Settings.FileHash();

        var result = await engine.ApplyAsync(stale, _rig.Settings, null, CancellationToken.None);

        Assert.Equal(ImportState.Planned, result.State);
        Assert.Equal(ImportTargetLocks.StalePlan, result.Error);
        Assert.Equal(after, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal(settings, _rig.Settings.FileHash());
        var again = await engine.PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        Assert.True((await engine.ApplyAsync(again, _rig.Settings, null, CancellationToken.None)).Succeeded);
        Assert.False(Directory.Exists(Path.Combine(_rig.GameDir(), "saves", "World1 (imported)")));
        Assert.Empty(_rig.Settings.Load().Profiles);
    }

    [Fact]
    public async Task UndoingAnImportMakesAPlanMadeBeforeTheUndoStale()
    {
        TypicalSource.Populate(_rig.Source);
        var engine = _rig.Engine();
        var first = await engine.PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        Assert.True((await engine.ApplyAsync(first, _rig.Settings, null, CancellationToken.None)).Succeeded);
        // Planned while World1 is in Origin: "already in Origin (same world), left as it is".
        var beforeUndo = await engine.PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);
        Assert.Equal(ImportState.RolledBack, (await engine.RollbackAsync(first.ImportId, _rig.Settings)).State);

        var result = await engine.ApplyAsync(beforeUndo, _rig.Settings, null, CancellationToken.None);

        Assert.Equal(ImportTargetLocks.StalePlan, result.Error);
    }

    // ------------------------------------------------------------ malformed launcher data

    public static IEnumerable<object[]> Garbage() => new[]
    {
        new object[] { "" }, new object[] { "{" }, new object[] { "null" }, new object[] { "[]" }, new object[] { "42" },
        new object[] { "\"text\"" }, new object[] { "{\"profiles\":[1,2,3]}" }, new object[] { "{\"profiles\":{\"a\":null,\"b\":7,\"c\":{\"gameDir\":42}}}" },
        new object[] { "{\"components\":\"x\"}" }, new object[] { "{\"components\":[null,{\"uid\":5}]}" },
        new object[] { "﻿\0\0\0\u0001garbage" }, new object[] { new string('[', 5000) },
        new object[] { "{\"baseModLoader\":{\"name\":123},\"gameVersion\":[],\"name\":null}" },
    };

    [Theory]
    [MemberData(nameof(Garbage))]
    public async Task EveryLauncherDetectorSurvivesGarbageInItsOwnFiles(string garbage)
    {
        var root = Path.Combine(_rig.Root, "launchers");
        var mc = Path.Combine(root, ".minecraft");
        Kit.Write(mc, "launcher_profiles.json", garbage);
        Kit.Write(mc, "options.txt", "fov:0.5");
        var prism = Path.Combine(root, "PrismLauncher");
        Kit.Write(prism, "prismlauncher.cfg", garbage);
        Kit.Write(prism, "instances/A/instance.cfg", garbage);
        Kit.Write(prism, "instances/A/mmc-pack.json", garbage);
        Kit.Write(prism, "instances/A/.minecraft/options.txt", "fov:0.5");
        var modrinth = Path.Combine(root, "ModrinthApp");
        Kit.Write(modrinth, "profiles/B/options.txt", "fov:0.5");
        Kit.Write(modrinth, "profiles/B/logs/latest.log", garbage);
        var cf = Path.Combine(root, "CurseForge");
        Kit.Write(cf, "C/minecraftinstance.json", garbage);
        Kit.Write(cf, "C/options.txt", "fov:0.5");
        var before = Kit.TreeHash(root, mtimes: true);

        var engine = _rig.Engine();
        var scan = await engine.ScanAsync(new SourceRoots { OfficialMinecraftDir = mc, PrismDataDir = prism, ModrinthDir = modrinth, CurseForgeDir = cf },
            ImportEngine.DefaultSources(), null, CancellationToken.None);

        // Nothing throws; whatever is listed has a name and a folder, and can be planned without throwing either.
        foreach (var setup in scan.Instances)
        {
            Assert.False(string.IsNullOrWhiteSpace(setup.Name));
            Assert.True(Directory.Exists(setup.GameDir), setup.GameDir);
            await engine.PlanAsync(_rig.Request() with { Source = setup }, null, CancellationToken.None);
        }
        Assert.Equal(before, Kit.TreeHash(root, mtimes: true));
    }
}
