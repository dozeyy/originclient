using OriginLauncher.App.Core.Import;
using Xunit;
using Xunit.Abstractions;

namespace OriginLauncher.Tests.Import;

// Stress round (2026-10-19): repeated imports, read-only and locked files, crash and cancel at every step.
// Every folder is generated in a temp folder; nothing here touches a real launcher or the real Origin data folder.
public class ImportStressTests : IDisposable
{
    private readonly Rig _rig = new();
    private readonly ITestOutputHelper _out;

    public ImportStressTests(ITestOutputHelper output) => _out = output;

    public void Dispose()
    {
        foreach (var file in Directory.EnumerateFiles(_rig.Root, "*", SearchOption.AllDirectories))
            try { File.SetAttributes(file, FileAttributes.Normal); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        _rig.Dispose();
    }

    private static readonly ImportOptions WithWorlds = new() { CopyWorlds = true };

    private async Task<ImportResult> Import(ImportOptions? options = null, IProgress<ImportProgress>? progress = null, CancellationToken ct = default)
    {
        var plan = await _rig.Engine().PlanAsync(_rig.Request(options: options), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join(" ", plan.Blocked));
        return await _rig.Engine().ApplyAsync(plan, _rig.Settings, progress, ct);
    }

    // ------------------------------------------------------------ repeated imports

    [Fact]
    public async Task ImportingTheSameSetupTwiceAddsNoSecondCopyOfAnything()
    {
        TypicalSource.Populate(_rig.Source);
        var first = await Import(WithWorlds);
        Assert.True(first.Succeeded, first.Error);
        var afterFirst = Kit.TreeHash(_rig.GameDir());
        var profilesAfterFirst = _rig.Settings.Load().Profiles.Count;

        var second = await Import(WithWorlds);
        Assert.True(second.Succeeded, second.Error);

        _out.WriteLine(second.SummaryText);
        Assert.Equal(new[] { "World1" }, Directory.GetDirectories(Path.Combine(_rig.GameDir(), "saves")).Select(Path.GetFileName));
        Assert.Equal(afterFirst, Kit.TreeHash(_rig.GameDir()));
        Assert.Equal(profilesAfterFirst, _rig.Settings.Load().Profiles.Count);
    }

    // ------------------------------------------------------------ read-only and locked files

    [Fact]
    public async Task AReadOnlySettingsFileInOriginsGameFolderIsStillUpdated()
    {
        TypicalSource.Populate(_rig.Source);
        var dest = Kit.Write(_rig.GameDir(), "options.txt", "fov:1.0\n");
        File.SetAttributes(dest, FileAttributes.ReadOnly);

        var result = await Import();

        Assert.True(result.Succeeded, result.Error);
        Assert.Contains("renderDistance:12", File.ReadAllText(dest));
    }

    [Fact]
    public async Task AReadOnlySettingsFileStaysReadOnlyAndUndoPutsTheOriginalBackExactly()
    {
        TypicalSource.Populate(_rig.Source);
        var dest = Kit.Write(_rig.GameDir(), "options.txt", "fov:1.0\n");
        File.SetAttributes(dest, FileAttributes.ReadOnly);
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);
        var settingsBefore = _rig.Settings.FileHash();

        var result = await Import();
        Assert.True(result.Succeeded, result.Error);
        Assert.True(File.GetAttributes(dest).HasFlag(FileAttributes.ReadOnly)); // the player's lock is kept

        var undo = await _rig.Engine().RollbackAsync(result.ImportId, _rig.Settings);
        Assert.Equal(ImportState.RolledBack, undo.State);
        Assert.Null(undo.Error);
        var journal = ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, result.ImportId))!;
        Assert.Empty(journal.RollbackProblems ?? new List<string>());
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal("fov:1.0\n", File.ReadAllText(dest));
        Assert.True(File.GetAttributes(dest).HasFlag(FileAttributes.ReadOnly));
        Assert.Equal(settingsBefore, _rig.Settings.FileHash());
    }

    [Fact]
    public void AnOldBackupHoldingAReadOnlyFileIsStillPrunedAndDoesNotKeepTheOthersAlive()
    {
        var start = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        var runs = new List<string>();
        for (var i = 0; i < 6; i++)
        {
            var id = $"run{i}";
            var run = ImportLayout.RunDir(_rig.Imports, id);
            Directory.CreateDirectory(run);
            ImportJournalStore.Save(new ImportJournal { ImportId = id, State = ImportState.Committed, StartedUtc = start.AddHours(i) },
                Path.Combine(run, ImportLayout.JournalFile));
            var file = Kit.Write(Path.Combine(run, ImportLayout.BackupFolder), "0001-options.txt", "old");
            if (i % 2 == 0) File.SetAttributes(file, FileAttributes.ReadOnly);
            runs.Add(run);
        }

        ImportRetention.PruneBackups(_rig.Imports);

        for (var i = 0; i < 6; i++)
            Assert.Equal(i >= 3, Directory.Exists(Path.Combine(runs[i], ImportLayout.BackupFolder)));
    }

    [Fact]
    public async Task ReadOnlySourceFilesAreCopiedAndTheSourceKeepsItsAttributes()
    {
        TypicalSource.Populate(_rig.Source);
        foreach (var file in Directory.EnumerateFiles(_rig.Source, "*", SearchOption.AllDirectories))
            File.SetAttributes(file, FileAttributes.ReadOnly);
        var before = Kit.TreeHash(_rig.Source, mtimes: true);

        var result = await Import(WithWorlds);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(before, Kit.TreeHash(_rig.Source, mtimes: true));
        Assert.All(Directory.EnumerateFiles(_rig.Source, "*", SearchOption.AllDirectories),
            f => Assert.True(File.GetAttributes(f).HasFlag(FileAttributes.ReadOnly)));
        Assert.True(Kit.Exists(_rig.GameDir(), "saves/World1/region/r.0.0.mca"));
    }

    [WindowsOnlyFact]
    public async Task AModJarLockedByAnotherProgramAfterThePlanIsLeftOutNotTheWholeImport()
    {
        TypicalSource.Populate(_rig.Source);
        var plan = await _rig.Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);
        var mod = plan.Ops.First(o => o.Kind == OpKind.CopyFile && o.Item == PlanItemKind.Mod);

        ImportResult result;
        using (new FileStream(mod.Source!, FileMode.Open, FileAccess.Read, FileShare.None))
            result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.False(File.Exists(mod.Dest));
        Assert.Contains($"{mod.Label}: Couldn't read it in your old setup", SectionOf(result.SummaryText, "Not imported"));
        // The plan's line for it ("imported") is no longer true, so it is gone from every other section.
        Assert.DoesNotContain(mod.PlannedLine!.Name, result.SummaryText.Replace(mod.Label, ""));
        Assert.True(Kit.Exists(_rig.GameDir(), "options.txt"));
    }

    // ------------------------------------------------------------ crash and cancel at every step

    // A source with something in every lane (mods, packs, shaders, options, a world) over an Origin folder that
    // already has a settings file and a different world of the same name, so replace and rename paths both run.
    private static void Scenario(Rig rig)
    {
        TypicalSource.Populate(rig.Source);
        Kit.Write(rig.GameDir(), "options.txt", "fov:1.0\n");
        Kit.Write(rig.GameDir(), "saves/World1/level.dat", "MINE");
    }

    private static ImportEngine CrashingEngine(Rig rig, Func<bool> armed, int crashAtOpen, Action<int>? onOpen = null)
    {
        var opens = 0;
        return new ImportEngine(new ImportEnvironment
        {
            InstancesRoot = rig.Instances, ImportsRoot = rig.Imports, FreeSpace = _ => long.MaxValue, IsGameRunning = _ => false,
            OnSourceOpen = _ =>
            {
                if (!armed()) return;
                var n = opens++;
                onOpen?.Invoke(n);
                // The one exception the engine never catches: what a killed process leaves is exactly this.
                if (n == crashAtOpen) throw new OutOfMemoryException("simulated crash");
            },
        });
    }

    private sealed record Before(string Instances, string Settings, string Source);

    private static Before Snapshot(Rig rig) =>
        new(Kit.TreeHash(rig.Instances, mtimes: true), rig.Settings.FileHash(), Kit.TreeHash(rig.Source, mtimes: true));

    // After a crash: the journal says Applying; the next start's recovery puts back every byte and every timestamp.
    private static void AssertRecoversExactly(Rig rig, ImportPlan plan, Before before, string what)
    {
        var journal = ImportJournalStore.Load(ImportLayout.Journal(rig.Imports, plan.ImportId));
        Assert.True(journal?.State == ImportState.Applying, $"{what}: the journal should still say Applying, was {journal?.State}");
        var recovered = ImportRecovery.Recover(rig.Imports, Path.Combine(rig.Root, "OriginLauncher"), rig.Settings, null, CancellationToken.None);
        Assert.Equal(1, recovered);
        AssertUnchanged(rig, plan, before, what);
        journal = ImportJournalStore.Load(ImportLayout.Journal(rig.Imports, plan.ImportId))!;
        Assert.Equal(ImportState.RolledBack, journal.State);
        Assert.True((journal.RollbackProblems ?? new List<string>()).Count == 0, $"{what}: " + string.Join(" ", journal.RollbackProblems ?? new()));
    }

    private static void AssertUnchanged(Rig rig, ImportPlan plan, Before before, string what)
    {
        Assert.True(before.Instances == Kit.TreeHash(rig.Instances, mtimes: true), $"{what}: Origin's folders changed");
        Assert.True(before.Settings == rig.Settings.FileHash(), $"{what}: the launcher settings changed");
        Assert.True(before.Source == Kit.TreeHash(rig.Source, mtimes: true), $"{what}: the SOURCE changed");
        Assert.False(Directory.Exists(Path.Combine(ImportLayout.RunDir(rig.Imports, plan.ImportId), ImportLayout.StagingFolder)),
            $"{what}: half-copied files were left in staging");
    }

    [Fact]
    public async Task ACrashAtAnySourceFileOpenRecoversExactlyAndTheImportWorksAfterwards()
    {
        for (var k = 0; ; k++)
        {
            using var rig = new Rig();
            Scenario(rig);
            var armed = false;
            var engine = CrashingEngine(rig, () => armed, crashAtOpen: k);
            var plan = await engine.PlanAsync(rig.Request(options: WithWorlds), null, CancellationToken.None);
            var before = Snapshot(rig);
            armed = true;
            try
            {
                var done = await engine.ApplyAsync(plan, rig.Settings, null, CancellationToken.None);
                Assert.True(done.Succeeded, done.Error);
                _out.WriteLine($"crash sweep: {k} source opens, every one recovered exactly");
                Assert.True(k > 10, "the sweep should cover more than a handful of files");
                return;
            }
            catch (OutOfMemoryException)
            {
                AssertRecoversExactly(rig, plan, before, $"crash at open {k}");
            }

            // The player starts the launcher again and imports again: it lands as if nothing had happened.
            armed = false;
            var again = await engine.PlanAsync(rig.Request(options: WithWorlds), null, CancellationToken.None);
            var result = await engine.ApplyAsync(again, rig.Settings, null, CancellationToken.None);
            Assert.True(result.Succeeded, $"re-import after crash at open {k}: {result.Error}");
            Assert.Equal(again.ProfileId, Assert.Single(rig.Settings.Load().Profiles).Id);  // the crashed run left no profile behind
            Assert.Equal(new[] { "World1", "World1 (imported)" },
                Directory.GetDirectories(Path.Combine(rig.GameDir(), "saves")).Select(Path.GetFileName).OrderBy(n => n, StringComparer.Ordinal));
        }
    }

    [Fact]
    public async Task ACrashAtAnyStepOfApplyRecoversExactly()
    {
        for (var k = 0; ; k++)
        {
            using var rig = new Rig();
            Scenario(rig);
            var engine = rig.Engine();
            var plan = await engine.PlanAsync(rig.Request(options: WithWorlds), null, CancellationToken.None);
            var before = Snapshot(rig);
            var reports = 0;
            var progress = new SyncProgress(_ => { if (reports++ == k) throw new OutOfMemoryException("simulated crash"); });
            try
            {
                var done = await engine.ApplyAsync(plan, rig.Settings, progress, CancellationToken.None);
                Assert.True(done.Succeeded, done.Error);
                Assert.True(k == plan.Ops.Count + 1, $"every step reports once: {k} reports for {plan.Ops.Count} ops");
                _out.WriteLine($"step sweep: {k} steps, every one recovered exactly");
                break;
            }
            catch (OutOfMemoryException)
            {
                AssertRecoversExactly(rig, plan, before, $"crash at step {k}");
            }
        }
        // The "settings saved (the new profile), journal not yet committed" window is its own test:
        // ImportVerifierTests.AnImportWhoseProfileWasSavedButNeverCommittedIsPutBackWholeOnTheNextStart.
    }

    [Fact]
    public async Task CancellingAtAnyStepOrInsideAWorldCopyPutsEverythingBack()
    {
        var steps = 0;
        for (var k = 0; ; k++)
        {
            using var rig = new Rig();
            Scenario(rig);
            var engine = rig.Engine();
            var plan = await engine.PlanAsync(rig.Request(options: WithWorlds), null, CancellationToken.None);
            var before = Snapshot(rig);
            using var cts = new CancellationTokenSource();
            var reports = 0;
            var result = await engine.ApplyAsync(plan, rig.Settings, new SyncProgress(_ => { if (reports++ == k) cts.Cancel(); }), cts.Token);
            if (!cts.IsCancellationRequested) { Assert.True(result.Succeeded, result.Error); steps = k; break; }
            Assert.True(result.Cancelled, $"cancel at step {k}: {result.State} {result.Error}");
            Assert.Equal(ImportState.RolledBack, result.State);
            AssertUnchanged(rig, plan, before, $"cancel at step {k}");
            Assert.Equal(ImportState.RolledBack, ImportJournalStore.Load(ImportLayout.Journal(rig.Imports, plan.ImportId))!.State);
        }

        var opens = 0;
        for (var k = 0; ; k++)
        {
            using var rig = new Rig();
            Scenario(rig);
            using var cts = new CancellationTokenSource();
            var armed = false;
            var engine = CrashingEngine(rig, () => armed, crashAtOpen: -1, onOpen: n => { if (n == k) cts.Cancel(); });
            var plan = await engine.PlanAsync(rig.Request(options: WithWorlds), null, CancellationToken.None);
            var before = Snapshot(rig);
            armed = true;
            var result = await engine.ApplyAsync(plan, rig.Settings, null, cts.Token);
            if (!cts.IsCancellationRequested) { Assert.True(result.Succeeded, result.Error); opens = k; break; }
            Assert.True(result.Cancelled, $"cancel at open {k}: {result.State} {result.Error}");
            AssertUnchanged(rig, plan, before, $"cancel at open {k}");
        }
        _out.WriteLine($"cancel sweep: {steps} steps and {opens} source opens, every one put back exactly");
    }

    // The lines of one section of a summary.txt ("Imported", "Not imported"...), up to the next blank line.
    private static string SectionOf(string summary, string title)
    {
        var lines = summary.Replace("\r", "").Split('\n');
        var start = Array.IndexOf(lines, title);
        Assert.True(start >= 0, $"no \"{title}\" section in:\n{summary}");
        return string.Join("\n", lines.Skip(start + 1).TakeWhile(l => l.Length > 0));
    }

    [WindowsOnlyFact]
    public async Task AWorldFileLockedAfterThePlanLeavesThatWorldOutAndTheRestImports()
    {
        TypicalSource.Populate(_rig.Source);
        var plan = await _rig.Engine().PlanAsync(_rig.Request(options: WithWorlds), null, CancellationToken.None);

        ImportResult result;
        using (new FileStream(Path.Combine(_rig.Source, "saves", "World1", "region", "r.0.0.mca"), FileMode.Open, FileAccess.Read, FileShare.None))
            result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.False(Kit.Exists(_rig.GameDir(), "saves/World1"));
        Assert.True(Kit.Exists(_rig.GameDir(), "options.txt"));
        Assert.Empty(Directory.GetDirectories(ImportLayout.RunDir(_rig.Imports, plan.ImportId), "staging"));
    }
}
