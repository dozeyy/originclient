using System.Text.Json;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Profiles;
using Xunit;

namespace OriginLauncher.Tests.Import;

// "Import everything", applying: ONE transaction for the whole run (cancel, a hard failure or a crash anywhere puts
// every version back), the version gates (ascending, once each), the run's one settings change, and the undo.
public class BulkApplyTests : IDisposable
{
    private readonly BulkFixture _f = new();

    public void Dispose() => _f.Dispose();

    private string TreeNow => Kit.TreeHash(_f.Rig.Instances, mtimes: true);

    // Three versions, two setups on 1.21.1; each version also gets the fake "version work" (with its gate).
    private (ImportEngine Engine, RecordingGate Gate, FakeVersionWork Work) ThreeVersions(params IImportPlanContributor[] extra)
    {
        _f.Add("A", "1.21.1", playtimeHours: 30);
        _f.Add("B", "1.21.1", playtimeHours: 20);
        _f.Add("C", "1.20.4", playtimeHours: 10);
        _f.Add("D", "1.16.5", playtimeHours: 5);
        var gate = new RecordingGate();
        var work = new FakeVersionWork(_f.Rig.Instances, gate);
        var engine = _f.Engine(new IImportPlanContributor[] { work }.Concat(extra).ToList(), versionGate: gate);
        return (engine, gate, work);
    }

    private static BulkProgress? First(SyncBulkProgress<BulkProgress> p, Func<BulkProgress, bool> match)
    {
        lock (p.Seen) return p.Seen.FirstOrDefault(match);
    }

    // ------------------------------------------------------------ the happy path

    [Fact]
    public async Task EverythingComesOverInOneRunWithOneSettingsChangeAndOneImportsFolder()
    {
        var (engine, gate, work) = ThreeVersions();
        var sources = _f.SourceHashes();
        var plan = await _f.Plan(engine);
        var progress = new SyncBulkProgress<BulkProgress>();

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, progress, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(1, _f.Rig.Settings.ApplyCalls);                                   // B12: ONE settings change for the run
        var settings = _f.Rig.Settings.Load();
        // Every setup that brought mods is its own profile (decision 04); the player's pick stays theirs.
        var made = result.Versions.SelectMany(v => v.Setups).Where(s => s.ProfileId != null).ToList();
        Assert.NotEmpty(made);
        Assert.Equal(made.Select(s => s.ProfileId).OrderBy(i => i), settings.Profiles.Select(p => p.Id).OrderBy(i => i));
        Assert.Equal("1.21.11", settings.SelectedVersion);
        Assert.Null(settings.SelectedProfileId);
        // mymod is built for 1.21.1: it lands in a 1.21.1 setup's profile, never in the version's shared folder.
        Assert.Contains(made, s => File.Exists(Path.Combine(s.ProfileModsDir!, "mymod.jar")) && s.ProfileModsDir!.Contains(Path.Combine("1.21.1", "profiles")));
        Assert.False(File.Exists(Path.Combine(_f.Rig.Instances, "1.21.1", "mods", "mymod.jar")));
        foreach (var v in new[] { "1.21.1", "1.20.4", "1.16.5" })
        {
            Assert.True(File.Exists(_f.Target(v, "options.txt")), v);
            Assert.True(File.Exists(work.FileFor(v)), v);
        }
        Assert.Equal(new[] { plan.RunId }, Directory.GetDirectories(_f.Rig.Imports).Select(Path.GetFileName));
        Assert.True(File.Exists(ImportLayout.Summary(_f.Rig.Imports, plan.RunId)));
        var journal = ImportJournalStore.Load(ImportLayout.Journal(_f.Rig.Imports, plan.RunId))!;
        Assert.Equal(ImportState.Committed, journal.State);
        Assert.Equal(new[] { "1.21.1", "1.20.4", "1.16.5" }, journal.TargetVersions);
        Assert.Equal(plan.Groups.Select(g => g.TargetGameDir), journal.TargetGameDirs);
        Assert.Equal(4, journal.SetupCount);
        Assert.Equal(sources, _f.SourceHashes());                                       // sources untouched
        // B18: progress per setup, the bar weighted by bytes, never past the total
        Assert.Equal(Enumerable.Range(0, plan.AllSetups.Count()), progress.Seen.Where(p => p.Inner.Stage == ImportStage.Planning).Select(p => p.SetupIndex));
        Assert.All(progress.Seen, p => Assert.InRange(p.BytesDone, 0, p.BytesTotal));
        Assert.Equal(plan.BytesToCopy, progress.Seen[^1].BytesTotal);
    }

    [Fact]
    public async Task NoCredentialFileIsEverOpened()
    {
        var (engine, _, _) = ThreeVersions();
        var plan = await _f.Plan(engine);
        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.NotEmpty(_f.Rig.Opened);
        Assert.DoesNotContain(_f.Rig.Opened, p => CredentialGuard.Default.IsDenied(Path.GetFileName(p)));
        Assert.DoesNotContain(_f.Rig.Opened, p => Path.GetFileName(p) == "discord-token.json");
        Assert.False(File.Exists(_f.Target("1.21.1", "config/discord-token.json")));
    }

    // ------------------------------------------------------------ gates (B14)

    [Fact]
    public async Task EachGatedVersionsGateIsTakenOnceAscendingAndReleasedAfterCommit()
    {
        var (engine, gate, _) = ThreeVersions();
        var plan = await _f.Plan(engine);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(new[] { "1.16.5", "1.20.4", "1.21.1" }, gate.Acquired);
        Assert.Equal(3, gate.Released.Count);
        Assert.Equal(new[] { "1.16.5", "1.20.4", "1.21.1" }, ImportJournalStore.Load(ImportLayout.Journal(_f.Rig.Imports, plan.RunId))!.GateVersions);
    }

    [Fact]
    public async Task GatesAreReleasedAfterARollbackToo()
    {
        var (engine, gate, _) = ThreeVersions(new StepFor("D", _ => throw new IOException("the disk said no")));
        var plan = await _f.Plan(engine);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.Equal(3, gate.Acquired.Count);
        Assert.Equal(3, gate.Released.Count);
        foreach (var v in new[] { "1.16.5", "1.20.4", "1.21.1" }) Assert.Equal(1, gate.LockFor(v).CurrentCount); // all free again
    }

    [Fact]
    public async Task AVersionBeingInstalledMakesTheRunWaitAndTheWaitCanBeCancelled()
    {
        var (engine, gate, _) = ThreeVersions();
        var before = TreeNow;
        var plan = await _f.Plan(engine);
        await gate.LockFor("1.20.4").WaitAsync();                                   // someone is installing 1.20.4
        using var cts = new CancellationTokenSource();
        var progress = new SyncBulkProgress<BulkProgress>(p => { if (p.WaitingFor?.Contains("1.20.4") == true) cts.CancelAfter(200); });

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, progress, cts.Token);

        Assert.NotNull(First(progress, p => p.WaitingFor == "Waiting for Minecraft 1.20.4 to finish installing"));
        Assert.True(result.Cancelled);
        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.Equal(new[] { "1.16.5" }, gate.Acquired);                              // waited at 1.20.4, never took it
        Assert.Equal(new[] { "1.16.5" }, gate.Released);
        Assert.Equal(before, TreeNow);
        gate.LockFor("1.20.4").Release();
    }

    // ------------------------------------------------------------ one transaction (B16, B17)

    [Fact]
    public async Task CancellingAfterSomeSetupsPutsEveryVersionBackExactly()
    {
        var (engine, _, _) = ThreeVersions();
        Kit.Write(_f.Target("1.21.1"), "options.txt", "fov:1.0\n");                     // Origin already has settings
        var plan = await _f.Plan(engine);
        var before = TreeNow;
        var settingsBefore = _f.Rig.Settings.FileHash();
        using var cts = new CancellationTokenSource();
        var progress = new SyncBulkProgress<BulkProgress>(p => { if (p.SetupIndex == 2) cts.Cancel(); }); // after A and B

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, progress, cts.Token);

        Assert.True(result.Cancelled);
        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.Equal(before, TreeNow);
        Assert.Equal(settingsBefore, _f.Rig.Settings.FileHash());
        Assert.All(result.Versions.SelectMany(v => v.Setups), s => Assert.Equal(SetupResultKind.Failed, s.Kind));
        Assert.StartsWith("Cancelled", BulkSummary.ToView(result).Header);
    }

    [Fact]
    public async Task AHardFailureInTheLastSetupRollsBackTheWholeRun()
    {
        var (engine, _, _) = ThreeVersions();
        var plan = await _f.Plan(engine);
        var last = plan.AllSetups.Last();
        var victim = last.Plan.Ops.First(o => o.Kind == OpKind.CopyFile && o.Item == PlanItemKind.Mod);
        Directory.CreateDirectory(victim.Dest);                                     // a folder in the way of a file: a DESTINATION failure
        var before = TreeNow;
        var settingsBefore = _f.Rig.Settings.FileHash();

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.False(result.Cancelled);
        Assert.Contains("Everything was put back the way it was.", result.Error);
        Assert.Equal(before, TreeNow);
        Assert.Equal(settingsBefore, _f.Rig.Settings.FileHash());
    }

    [Fact]
    public async Task AFileThatVanishedFromASourceIsLeftOutWithALine()
    {
        var (engine, _, _) = ThreeVersions();
        var plan = await _f.Plan(engine);
        var a = plan.AllSetups.Single(s => s.Setup.Name == "A");                   // 1.21.1's most played setup brings the mods
        var op = a.Plan.Ops.First(o => o.Kind == OpKind.CopyFile && o.Item == PlanItemKind.Mod);
        File.Delete(op.Source!);                                                    // a SOURCE problem: soft

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var outcome = result.Versions.SelectMany(v => v.Setups).Single(s => s.Setup.Name == "A");
        Assert.Equal(SetupResultKind.Imported, outcome.Kind);
        Assert.Contains(outcome.Lines!, l => l.Section == SummarySection.NotImported && l.Name == op.Label
                                             && l.Reason!.StartsWith("Couldn't read it in your old setup", StringComparison.Ordinal));
        Assert.False(File.Exists(op.Dest));
    }

    [Fact]
    public async Task AGameStartedFromOneSourceMidRunSkipsOnlyThatSetup()
    {
        var (engine, _, _) = ThreeVersions();
        var plan = await _f.Plan(engine);
        var c = plan.AllSetups.Single(s => s.Setup.Name == "C");
        var progress = new SyncBulkProgress<BulkProgress>(p => { if (p.SetupIndex == 1) _f.Rig.RunningDirs.Add(c.Setup.GameDir); });

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, progress, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var outcomes = result.Versions.SelectMany(v => v.Setups).ToLookup(s => s.Setup.Name);
        Assert.Equal(SetupResultKind.Skipped, outcomes["C"].Single().Kind);
        Assert.Contains(outcomes["C"].Single().Lines!, l => l.Section == SummarySection.NeedsAttention && l.Name.StartsWith("Minecraft was started from this setup", StringComparison.Ordinal));
        Assert.Equal(SetupResultKind.Imported, outcomes["A"].Single().Kind);
        Assert.Equal(SetupResultKind.Imported, outcomes["D"].Single().Kind);
        Assert.False(File.Exists(_f.Target("1.20.4", "options.txt")));               // nothing of C landed
        Assert.All(c.Plan.Ops.Where(o => o.Dest != null && o.Kind == OpKind.CopyFile), o => Assert.False(File.Exists(o.Dest), o.Dest));
    }

    [Fact]
    public async Task OriginsGameStartingMidRunRollsEverythingBack()
    {
        var (engine, _, _) = ThreeVersions();
        Directory.CreateDirectory(_f.Rig.GameDir("1.20.4"));                         // Origin has played 1.20.4 before
        var plan = await _f.Plan(engine);
        var before = TreeNow;
        var progress = new SyncBulkProgress<BulkProgress>(p => { if (p.SetupIndex == 2) _f.Rig.RunningDirs.Add(_f.Rig.GameDir("1.20.4")); });

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, progress, CancellationToken.None);

        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.StartsWith("Close Minecraft first: Origin's Minecraft 1.20.4 started while importing", result.Error);
        Assert.Equal(before, TreeNow);
    }

    [Fact]
    public async Task ACrashMidRunIsRecoveredAtTheNextStart()
    {
        // "Crash": an OutOfMemoryException is the one thing the engine never catches, so the run stops with its
        // journal still saying Applying and nothing rolled back — exactly what a killed process leaves.
        var (engine, gate, _) = ThreeVersions(new StepFor("C", _ => throw new OutOfMemoryException("simulated crash")));
        var plan = await _f.Plan(engine);
        var before = TreeNow;
        var settingsBefore = _f.Rig.Settings.FileHash();

        await Assert.ThrowsAsync<OutOfMemoryException>(() => engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None));

        Assert.NotEqual(before, TreeNow);                                           // A and B had landed
        Assert.Equal(ImportState.Applying, ImportJournalStore.Load(ImportLayout.Journal(_f.Rig.Imports, plan.RunId))!.State);
        Assert.Equal(3, gate.Released.Count);                                       // the gates are not left held

        var recovered = ImportRecovery.Recover(_f.Rig.Imports, Path.Combine(_f.Rig.Root, "OriginLauncher"), _f.Rig.Settings, null, CancellationToken.None);

        Assert.Equal(1, recovered);
        Assert.Equal(before, TreeNow);
        Assert.Equal(settingsBefore, _f.Rig.Settings.FileHash());
    }

    // ------------------------------------------------------------ undo (B14, B16)

    [Fact]
    public async Task UndoingAFinishedRunRestoresEveryVersionTakesTheSameGatesAndRefusesWhileAGameRuns()
    {
        var (engine, gate, work) = ThreeVersions();
        var before = TreeNow;
        var settingsBefore = _f.Rig.Settings.FileHash();
        var plan = await _f.Plan(engine);
        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        gate.Acquired.Clear();
        gate.Released.Clear();

        _f.Rig.RunningDirs.Add(_f.Rig.GameDir("1.16.5"));
        var refused = await engine.RollbackAsync(plan.RunId, _f.Rig.Settings);
        Assert.Equal(ImportState.Planned, refused.State);
        Assert.StartsWith("Close Minecraft first", refused.Error);
        Assert.True(File.Exists(work.FileFor("1.16.5")));                             // nothing touched
        _f.Rig.RunningDirs.Clear();
        gate.Acquired.Clear();
        gate.Released.Clear();

        var undone = await engine.RollbackAsync(plan.RunId, _f.Rig.Settings);

        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.Null(undone.Error);
        Assert.Equal(new[] { "1.16.5", "1.20.4", "1.21.1" }, gate.Acquired);         // the same set, the same order
        Assert.Equal(3, gate.Released.Count);
        Assert.Equal(before, TreeNow);
        Assert.Equal(settingsBefore, _f.Rig.Settings.FileHash());
    }

    [Fact]
    public async Task AnOldSingleImportJournalWithOneAddProfileStillReverts()
    {
        // A journal written before bulk imports existed: no TargetVersions / SetupCount / AddProfiles fields.
        var id = new string('a', 32);
        var created = Path.Combine(_f.Rig.GameDir("1.21.1"), "options.txt");
        Kit.Write(_f.Rig.GameDir("1.21.1"), "options.txt", "fov:1\n");
        var profile = new ModProfile { Id = new string('b', 32), Name = "Prism: Old", Version = "1.21.1" };
        var delta = new SettingsDelta { AddProfile = profile, SetSelectedVersion = "1.21.1", PreviousSelectedVersion = "1.21.11" };
        _f.Rig.Settings.Apply(delta);
        var json = JsonSerializer.Serialize(new
        {
            ImportId = id, State = "Committed", StartedUtc = "2026-01-01T00:00:00Z", FinishedUtc = DateTime.UtcNow.AddMinutes(5),
            SourceName = "Old", TargetVersion = "1.21.1", TargetGameDir = _f.Rig.GameDir("1.21.1"),
            Entries = new object[]
            {
                new { Kind = "created", Path = created },
                new { Kind = "settings-delta", Delta = new { AddProfile = new { profile.Id, profile.Name, profile.Version }, SetSelectedVersion = "1.21.1", PreviousSelectedVersion = "1.21.11" } },
            },
        });
        Kit.Write(_f.Rig.Imports, $"{id}/journal.json", json);
        Assert.Single(_f.Rig.Settings.Load().Profiles);

        var undone = await _f.Engine().RollbackAsync(id, _f.Rig.Settings);

        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.Null(undone.Error);
        Assert.False(File.Exists(created));
        Assert.Empty(_f.Rig.Settings.Load().Profiles);
        Assert.Equal("1.21.11", _f.Rig.Settings.Load().SelectedVersion);
    }

    [Fact]
    public async Task AnImportLeavesTheSelectedVersionAloneTheDonePagesPlayButtonPicksIt()
    {
        _f.Add("Plain", "1.20.4", populate: false);
        Kit.Write(_f.Setups[0].GameDir, "options.txt", "fov:0.5\n");
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("1.21.11", _f.Rig.Settings.Load().SelectedVersion);
        Assert.Equal(0, _f.Rig.Settings.ApplyCalls);
        Assert.Empty(_f.Rig.Settings.Load().Profiles);
        Assert.Contains("fov:0.5", File.ReadAllText(_f.Target("1.20.4", "options.txt")));
    }
}
