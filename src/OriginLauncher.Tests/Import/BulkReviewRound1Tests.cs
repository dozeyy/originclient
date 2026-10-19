using System.Diagnostics;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using Xunit;
using Xunit.Abstractions;

namespace OriginLauncher.Tests.Import;

// Regression tests for the first code review of the bulk engine (a1fb0f34), named after what must stay true.
public class BulkReviewRound1Tests : IDisposable
{
    private readonly BulkFixture _f = new();
    private readonly ITestOutputHelper _out;
    private readonly List<string> _restore = new();

    public BulkReviewRound1Tests(ITestOutputHelper output) => _out = output;

    public void Dispose()
    {
        foreach (var path in _restore) try { ImportFixRoundTests.RestoreAccess(path); } catch (Exception) { }
        _f.Dispose();
    }

    private static SetupOutcome Outcome(BulkImportResult result, string name) =>
        result.Versions.SelectMany(v => v.Setups).Single(s => s.Setup.Name == name);

    // ------------------------------------------------------------ HIGH 2: the journal grows linearly

    [Fact]
    public async Task High2_ABigRunWritesItsJournalInLinearBytesAndAppliesInBoundedTime()
    {
        var versions = new[] { "1.21.11", "1.21.4", "1.21.1", "1.20.4", "1.20.1", "1.16.5" };
        for (var i = 0; i < 50; i++)
        {
            var setup = _f.Add($"Big {i:D2}", versions[i % versions.Length], populate: false, playtimeHours: 50 - i);
            Kit.Write(setup.GameDir, "options.txt", "fov:0.5\n");
            for (var j = 0; j < 200; j++) Kit.Write(setup.GameDir, $"config/s{i:D2}/f{j:D3}.json", $"{{\"v\":{j}}}");
        }
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);
        var ops = plan.AllSetups.Sum(s => s.Plan.Ops.Count);
        Assert.True(ops >= 10000, $"{ops} ops");

        var watch = Stopwatch.StartNew();
        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        watch.Stop();

        Assert.True(result.Succeeded, result.Error);
        var line = _f.Rig.LogLines.Single(l => l.StartsWith("import bulk apply:", StringComparison.Ordinal));
        var written = long.Parse(Regex.Match(line, @"(\d+) journal bytes written").Groups[1].Value);
        var finalSize = new FileInfo(ImportLayout.Journal(_f.Rig.Imports, plan.RunId)).Length;
        _out.WriteLine($"apply: {ops} ops in {watch.ElapsedMilliseconds} ms; journal {written} bytes written for a final journal of {finalSize} bytes ({(double)written / finalSize:0.0}x)");
        _out.WriteLine(line);
        // Linear: the appended entries once, plus a handful of whole snapshots (begin, settings, commit). Rewriting the
        // journal per op would be thousands of times the final size.
        Assert.InRange(written, finalSize, 4 * finalSize);
        Assert.InRange(watch.ElapsedMilliseconds, 0, 120_000);
        // The commit's snapshot holds every entry: no entries log is left behind.
        Assert.Empty(Directory.GetFiles(ImportLayout.RunDir(_f.Rig.Imports, plan.RunId), "entries-*.log"));
        Assert.Equal(ImportState.Committed, ImportJournalStore.Load(ImportLayout.Journal(_f.Rig.Imports, plan.RunId))!.State);
    }

    [Fact]
    public void High2_ATornLastLineOfTheEntriesLogIsDroppedAndTheRestReplays()
    {
        var run = Path.Combine(_f.Rig.Imports, new string('d', 32));
        Directory.CreateDirectory(run);
        var journal = new ImportJournal { ImportId = new string('d', 32), State = ImportState.Applying, EntriesLog = "entries-x.log" };
        journal.Entries.Add(new JournalEntry { Kind = JournalEntryKinds.Created, Path = "/a" });
        ImportJournalStore.Save(journal, Path.Combine(run, ImportLayout.JournalFile));
        File.WriteAllText(Path.Combine(run, "entries-x.log"),
            ImportJournalStore.Line(new JournalEntry { Kind = JournalEntryKinds.Created, Path = "/b" }) + "\n"
            + ImportJournalStore.Line(new JournalEntry { Kind = JournalEntryKinds.Created, Path = "/c" }) + "\n"
            + ImportJournalStore.Line(new JournalEntry { Kind = JournalEntryKinds.Forget, Path = "/b" }) + "\n"
            + "{\"Kind\":\"created\",\"Pa");                                         // the crash hit mid-line

        var loaded = ImportJournalStore.Load(Path.Combine(run, ImportLayout.JournalFile))!;

        Assert.Equal(new[] { "/a", "/c" }, loaded.Entries.Select(e => e.Path));
    }

    // ------------------------------------------------------------ MEDIUM 3: source read failures are soft

    [Fact]
    public async Task Medium3_AWorldWithAFileThatCantBeReadIsLeftOutAndTheRunStillCommits()
    {
        var a = _f.Add("A", playtimeHours: 10);
        var b = _f.Add("B", "1.20.4");
        var bad = Kit.Write(a.GameDir, "saves/World1/region/r.1.1.mca", "LOCKED");
        var engine = _f.Engine();
        var plan = await _f.Plan(engine, _f.Request(options: new ImportOptions { CopyWorlds = true }));
        ImportFixRoundTestsDenyFile(bad);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Contains(Outcome(result, "A").Lines!, l => l.Kind == PlanItemKind.World && l.Section == SummarySection.NotImported
                                                       && l.Reason!.StartsWith("Couldn't read it in your old setup", StringComparison.Ordinal));
        Assert.False(Directory.Exists(_f.Target("1.21.1", "saves/World1")));          // never half a world
        Assert.True(File.Exists(_f.Target("1.21.1", "options.txt")));                 // the rest of A came over
        Assert.True(File.Exists(_f.Target("1.20.4", "saves/World1/level.dat")));      // and B's world
    }

    [WindowsOnlyFact]
    public async Task Medium3_ASourceFileAnotherProgramHoldsLockedIsLeftOutNotARollback()
    {
        var a = _f.Add("A");
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);
        var op = plan.AllSetups.Single().Plan.Ops.First(o => o.Kind == OpKind.CopyFile && o.Item == PlanItemKind.ResourcePack);

        using (new FileStream(op.Source!, FileMode.Open, FileAccess.ReadWrite, FileShare.None))   // another program has it
        {
            var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

            Assert.True(result.Succeeded, result.Error);
            Assert.Contains(Outcome(result, "A").Lines!, l => l.Name == op.Label && l.Section == SummarySection.NotImported);
            Assert.False(File.Exists(op.Dest));
        }
    }

    [Fact]
    public async Task Medium3_ADestinationWriteFailureIsStillARollback()
    {
        var a = _f.Add("A");
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);
        var op = plan.AllSetups.Single().Plan.Ops.First(o => o.Kind == OpKind.CopyFile && o.Item == PlanItemKind.Mod);
        Directory.CreateDirectory(op.Dest);                                          // something in the way at the destination

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);

        Assert.Equal(ImportState.RolledBack, result.State);
    }

    private void ImportFixRoundTestsDenyFile(string file)
    {
        if (OperatingSystem.IsWindows())
        {
            using var process = Process.Start(new ProcessStartInfo("icacls", $"\"{file}\" /deny \"*S-1-1-0:(R)\"")
                { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true })!;
            process.WaitForExit();
            Assert.True(process.ExitCode == 0, "couldn't deny access for the test");
        }
        else
        {
            File.SetUnixFileMode(file, UnixFileMode.None);
        }
        _restore.Add(file);
    }

    // ------------------------------------------------------------ MEDIUM 4: the settings line tells the truth

    [Fact]
    public async Task Medium4_AWinnerSkippedAtApplyMakesTheSettingsLinesSaySoInsteadOfNamingIt()
    {
        var a = _f.Add("A", playtimeHours: 30);
        _f.Add("B", playtimeHours: 10);
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);
        _f.Rig.RunningDirs.Add(a.GameDir);

        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        var view = BulkSummary.ToView(result);

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("Minecraft 1.21.1: settings didn't come over: Prism: A was left out while importing", view.Versions[0].SettingsLine);
        var b = view.Versions[0].Setups.Single(s => s.Label == "Prism: B");
        Assert.Contains("Settings for Minecraft 1.21.1 didn't come over: Prism: A was left out while importing", b.NotImported);
        Assert.DoesNotContain(b.NotImported, l => l.Contains("come from", StringComparison.Ordinal));
    }

    // ------------------------------------------------------------ LOW 5: a winner that fails while planning is replaced

    [Fact]
    public async Task Low5_AWinnerThatFailsWhilePlanningIsReplacedByTheNextSetup()
    {
        var a = _f.Add("A", playtimeHours: 30);
        var b = _f.Add("B", playtimeHours: 20);
        _f.Add("C", playtimeHours: 10);
        var checks = 0;
        // A's game starts between the qualify check and its planning (the second time anyone asks about A).
        var engine = _f.Engine(isRunning: dir => dir == a.GameDir && Interlocked.Increment(ref checks) > 1);

        var plan = await _f.Plan(engine);

        var group = plan.Groups.Single();
        Assert.Same(b, group.Winner);
        Assert.Equal(new[] { "B", "C" }, group.Setups.Select(s => s.Setup.Name));
        Assert.Equal(new[] { SetupRole.Full, SetupRole.ModsAndPacks }, group.Setups.Select(s => s.Role));
        Assert.Contains(group.Setups[1].Plan.Lines, l => l.Name == "Settings for Minecraft 1.21.1 come from Prism: B");
        Assert.Single(plan.Skipped, s => s.Setup == a);
    }

    // ------------------------------------------------------------ LOW 6: identical packs dedupe by content, not landing name

    [Fact]
    public async Task Low6_AnIdenticalPackDedupesEvenWhenTheFirstCopyWasRenamed()
    {
        _f.Add("A", playtimeHours: 30);
        var b = _f.Add("B", playtimeHours: 10);
        Kit.WriteBytes(_f.Target("1.21.1"), "resourcepacks/Faithful.zip", Kit.Zip(Kit.Text("pack.mcmeta", "Origin's own, different")));
        var engine = _f.Engine();

        var plan = await _f.Plan(engine);

        var bPlan = plan.AllSetups.Single(s => s.Setup == b).Plan;
        Assert.Contains(plan.AllSetups.First().Plan.Ops, o => o.Label == "Faithful (imported).zip" && !o.Fallback);
        Assert.DoesNotContain(bPlan.Ops, o => !o.Fallback && o.Label.StartsWith("Faithful", StringComparison.Ordinal));
        Assert.Contains(bPlan.Lines, l => l.Name == "Faithful (imported).zip" && l.Reason == "already brought over from Prism: A");
        var result = await engine.ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(2, Directory.GetFiles(_f.Target("1.21.1", "resourcepacks"), "Faithful*").Length); // Origin's + one import
    }

    // ------------------------------------------------------------ LOW 7: confirming the preselected winner clears the choice

    [Fact]
    public async Task Low7_ConfirmingThePreselectedWinnerClearsNeedsChoice()
    {
        _f.Add("A", playtimeHours: 10);
        _f.Add("B", playtimeHours: 8);
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);
        var group = plan.Groups.Single();
        Assert.True(group.NeedsChoice);

        var next = await engine.ReplanGroupAsync(plan, "1.21.1", group.Winner, null, CancellationToken.None);

        Assert.False(next.Groups.Single().NeedsChoice);
        Assert.Same(group.Winner, next.Groups.Single().Winner);
    }
}
