using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Regression tests for the 2026-10-19 review of claude/import-stress (MED 1, MED 2, LOW 3, LOW 4), named after what
// must stay true. Every folder is a temp fake.
public class ImportReviewRound4Tests : IDisposable
{
    private readonly Rig _rig = new();
    private static readonly ImportOptions WithWorlds = new() { CopyWorlds = true };

    public void Dispose() => _rig.Dispose();

    private static SetupOutcome Outcome(BulkImportResult result, string name) =>
        result.Versions.SelectMany(v => v.Setups).Single(s => s.Setup.Name == name);

    // ------------------------------------------------------------ MED 1: TRY AGAIN never revives a stale group

    [Fact]
    public async Task Med1_TryAgainAfterAStalePlanPlansTheChangedFolderAgainSoAnUndoneWorldComesBack()
    {
        using var f = new BulkFixture();
        f.Add("Hypixel");
        var engine = f.Engine();
        var world = f.Target("1.21.1", "saves/World1");
        var first = await f.Plan(engine, f.Request(options: WithWorlds));
        Assert.True((await engine.ApplyBulkAsync(first, f.Rig.Settings, null, CancellationToken.None)).Succeeded);
        Assert.True(Directory.Exists(world));
        // Planned while World1 is in Origin: "already in Origin, left as it is".
        var beforeUndo = await f.Plan(engine, f.Request(options: WithWorlds));
        Assert.Equal(ImportState.RolledBack, (await engine.RollbackAsync(first.RunId, f.Rig.Settings)).State);
        Assert.False(Directory.Exists(world));
        var refused = await engine.ApplyBulkAsync(beforeUndo, f.Rig.Settings, null, CancellationToken.None);
        Assert.Equal(ImportTargetLocks.StalePlan, refused.Error);

        // TRY AGAIN: the panel re-plans from the refused plan, with the launcher settings read again (ImportBackend.Fresh).
        var fresh = new BulkImportPlan
        {
            RunId = beforeUndo.RunId, PlannedAt = beforeUndo.PlannedAt,
            Request = beforeUndo.Request! with { },
        };
        fresh.Groups.AddRange(beforeUndo.Groups);
        var again = await engine.ReplanSelectionAsync(fresh, fresh.Request!.Selection, null, CancellationToken.None);
        var result = await engine.ApplyBulkAsync(again, f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.True(File.Exists(Path.Combine(world, "level.dat"))); // it used to stay gone while the summary said "already in Origin"
    }

    [Fact]
    public async Task Med1_AnUnchangedGroupIsStillReusedWhenPlanningAgain()
    {
        using var f = new BulkFixture();
        f.Add("A");
        f.Add("Old", mc: "1.20.4");
        var engine = f.Engine();
        var plan = await f.Plan(engine);

        var again = await engine.ReplanSelectionAsync(plan, plan.Request!.Selection, null, CancellationToken.None);

        Assert.Same(plan.Groups.Single(g => g.Version == "1.21.1"), again.Groups.Single(g => g.Version == "1.21.1"));
    }

    // ------------------------------------------------------------ MED 2: a single import says what it left out

    [Fact]
    public async Task Med2_AModThatVanishedBeforeASingleImportIsNamedOnTheDonePage()
    {
        TypicalSource.Populate(_rig.Source);
        var plan = await _rig.Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);
        var mod = plan.Ops.First(o => o.Kind == OpKind.CopyFile && o.Item == PlanItemKind.Mod);
        File.Delete(mod.Source!); // or an antivirus has it open: the same "couldn't read it" path

        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        var done = ImportDoneModel.FromSingle(plan, result);

        Assert.True(result.Succeeded, result.Error);
        Assert.Contains(result.LeftOut, l => l.Name == mod.Label);
        var lost = Assert.Single(done.Lost);
        Assert.Equal(mod.Label, lost.Title);
        Assert.Contains("Couldn't read it", lost.Reason);
    }

    [Fact]
    public async Task Med2_ASingleImportWithNothingLeftOutListsNothing()
    {
        TypicalSource.Populate(_rig.Source);
        var plan = await _rig.Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);

        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.Empty(result.LeftOut);
        Assert.Empty(ImportDoneModel.FromSingle(plan, result).Lost);
    }

    [Fact]
    public async Task Med2_AFileLeftOutOfABulkImportIsNamedOnTheDonePageToo()
    {
        using var f = new BulkFixture();
        f.Add("A");
        var engine = f.Engine();
        var plan = await f.Plan(engine);
        var op = plan.AllSetups.Single().Plan.Ops.First(o => o.Kind == OpKind.CopyFile && o.Item == PlanItemKind.Mod);
        File.Delete(op.Source!);

        var result = await engine.ApplyBulkAsync(plan, f.Rig.Settings, null, CancellationToken.None);
        var done = ImportDoneModel.FromBulk(result);

        Assert.True(result.Succeeded, result.Error);
        Assert.Contains(done.Lost, l => l.Title == op.Label && l.Reason.Contains("Couldn't read it"));
    }

    // ------------------------------------------------------------ LOW 3: "N screenshots" counts what came

    [Fact]
    public async Task Low3_AScreenshotLeftOutAtApplyLowersTheCount()
    {
        foreach (var name in new[] { "a.png", "b.png", "c.png" }) Kit.Write(_rig.Source, "screenshots/" + name, name);
        var plan = await _rig.Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.Contains(plan.Lines, l => l.Name == "3 screenshots");
        File.Delete(Path.Combine(_rig.Source, "screenshots", "b.png"));

        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.Contains("2 screenshots", result.SummaryText);
        Assert.DoesNotContain("3 screenshots", result.SummaryText);
        Assert.Contains("screenshots/b.png", result.SummaryText);
    }

    [Fact]
    public async Task Low3_AScreenshotLeftOutOfABulkImportLowersItsCount()
    {
        using var f = new BulkFixture();
        var a = f.Add("A");
        Kit.Write(f.Dir(a), "screenshots/a.png", "A");
        Kit.Write(f.Dir(a), "screenshots/b.png", "BB");
        var engine = f.Engine();
        var plan = await f.Plan(engine);
        File.Delete(f.Dir(a, "screenshots/a.png"));

        var result = await engine.ApplyBulkAsync(plan, f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var lines = Outcome(result, "A").Lines!;
        Assert.Contains(lines, l => l.Section == SummarySection.Imported && l.Name == "1 screenshot");
        Assert.DoesNotContain(lines, l => l.Name == "2 screenshots");
    }

    // ------------------------------------------------------------ LOW 4: a client's game directory is read like the setup

    [Fact]
    public void Low4_LinksBelowTheSharedGameFolderAreCheckedLikeTheSetupsOwn()
    {
        var profile = Path.Combine(_rig.Root, "lunar", "profiles", "1.21");
        var shared = Path.Combine(_rig.Root, "game");
        var elsewhere = Path.Combine(_rig.Root, "elsewhere");
        Kit.Write(elsewhere, "level.dat", "SOMEONE ELSE'S");
        Directory.CreateDirectory(profile);
        ImportFixRoundTests.Link(Path.Combine(shared, "saves", "World1"), elsewhere);
        var fs = new SourceFs { InstanceRoot = profile, SharedRoot = shared };

        Assert.False(fs.Allow(Path.Combine(shared, "saves", "World1", "level.dat"))); // through a linked world: refused
        Assert.True(fs.IsLinkFree(Path.Combine(shared, "saves")));                     // saves/ itself may be moved
    }

    [Fact]
    public void Low4_AMovedScreenshotsFolderIsFollowedLikeSaves()
    {
        var elsewhere = Path.Combine(_rig.Root, "d-drive-shots");
        Kit.Write(elsewhere, "a.png", "A");
        ImportFixRoundTests.Link(Path.Combine(_rig.Source, "screenshots"), elsewhere);
        var fs = new SourceFs { InstanceRoot = _rig.Source };

        Assert.True(fs.Allow(Path.Combine(_rig.Source, "screenshots", "a.png")));
    }

    [Theory]
    [InlineData(".")]
    [InlineData("relative/minecraft")]
    [InlineData(@"\\server\share\minecraft")]
    [InlineData("//server/share/minecraft")]
    public async Task Low4_LunarGameDirectoryMustBeALocalFullPath(string gameDirectory)
    {
        var pc = Path.Combine(_rig.Root, "pc");
        var minecraft = Path.Combine(pc, "AppData", ".minecraft");
        var lunar = Path.Combine(pc, "User", ".lunarclient");
        Kit.Write(minecraft, "launcher_profiles.json", "{\"profiles\":{}}");
        Kit.WriteBytes(lunar, "profiles/1.21/mods/fabric-1.21.1/lithium.jar", Kit.FabricJar("lithium", "0.15.0", minecraft: "~1.21.1"));
        Kit.Write(lunar, "settings/launcher.json",
            "{\"settings\":{\"gameDirectory\":" + System.Text.Json.JsonSerializer.Serialize(gameDirectory) + "}}");
        var roots = ImportRoots.Sources(new MachineFolders(Path.Combine(pc, "AppData"), Path.Combine(pc, "Local"), Path.Combine(pc, "User"), Path.Combine(pc, "Docs")));

        var scan = await _rig.Engine().ScanAsync(roots, ImportEngine.DefaultSources(), null, CancellationToken.None);

        var setup = Assert.Single(scan.Instances, s => s.ModsDir != null);
        Assert.Equal(minecraft, setup.SharedGameDir);
    }
}
