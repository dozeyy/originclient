using System.Diagnostics;
using OriginLauncher.App.Core.Import;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Screenshots come over from every launcher (Will, 2026-10-19): on by default, untickable like the other options,
// copied never moved, a repeat import adds nothing, and a big folder plans on name + size alone (no hashing).
public class ScreenshotImportTests : IDisposable
{
    private readonly Rig _rig = new();

    public void Dispose() => _rig.Dispose();

    private string Shots => Path.Combine(_rig.Source, "screenshots");
    private string OriginShots => Path.Combine(_rig.GameDir(), "screenshots");

    private async Task<ImportPlan> Plan(ImportOptions? options = null) =>
        await _rig.Engine().PlanAsync(_rig.Request(options: options), null, CancellationToken.None);

    private async Task<ImportPlan> ImportOnce(ImportOptions? options = null)
    {
        var plan = await Plan(options);
        var result = await _rig.Engine().ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        return plan;
    }

    [Fact]
    public async Task ScreenshotsAreCopiedByDefaultAndTheSourceIsUntouched()
    {
        Kit.Write(_rig.Source, "screenshots/2026-10-01_12.00.00.png", "PNG-A");
        Kit.Write(_rig.Source, "screenshots/2026-10-02_12.00.00.jpg", "JPG-B");
        Kit.Write(_rig.Source, "screenshots/desktop.ini", "junk");          // not a picture
        Kit.Write(_rig.Source, "screenshots/old/nested.png", "NESTED");     // the game writes them flat
        var before = Kit.TreeHash(_rig.Source, mtimes: true);
        Assert.True(new ImportOptions().CopyScreenshots);

        var plan = await ImportOnce();

        Assert.Equal("PNG-A", Kit.Read(OriginShots, "2026-10-01_12.00.00.png"));
        Assert.Equal("JPG-B", Kit.Read(OriginShots, "2026-10-02_12.00.00.jpg"));
        Assert.False(Kit.Exists(OriginShots, "desktop.ini"));
        Assert.False(Directory.Exists(Path.Combine(OriginShots, "old")));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.Imported && l.Kind == PlanItemKind.Screenshot && l.Name == "2 screenshots");
        Assert.Equal(before, Kit.TreeHash(_rig.Source, mtimes: true)); // copied, never moved
    }

    [Fact]
    public async Task UntickingScreenshotsCopiesNone()
    {
        Kit.Write(_rig.Source, "screenshots/a.png", "A");

        var plan = await ImportOnce(new ImportOptions { CopyScreenshots = false });

        Assert.DoesNotContain(plan.Ops, o => o.Item == PlanItemKind.Screenshot);
        Assert.DoesNotContain(plan.Lines, l => l.Kind == PlanItemKind.Screenshot);
        Assert.False(Directory.Exists(OriginShots));
    }

    [Fact]
    public async Task ImportingAgainAddsNoSecondCopy()
    {
        Kit.Write(_rig.Source, "screenshots/a.png", "A");
        Kit.Write(_rig.Source, "screenshots/b.png", "BB");
        await ImportOnce();

        var again = await ImportOnce();

        Assert.DoesNotContain(again.Ops, o => o.Item == PlanItemKind.Screenshot);
        Assert.Contains(again.Lines, l => l.Kind == PlanItemKind.Screenshot && l.Reason == "already in Origin, left as they are");
        Assert.Equal(new[] { "a.png", "b.png" }, Directory.GetFiles(OriginShots).Select(Path.GetFileName).OrderBy(n => n));
    }

    [Fact]
    public async Task ADifferentPictureWithTheSameNameLandsBesideOriginsOwnNeverOverIt()
    {
        Kit.Write(OriginShots, "a.png", "ORIGIN-OWN");
        Kit.Write(_rig.Source, "screenshots/a.png", "IMPORTED-ONE");

        await ImportOnce();
        Assert.Equal("ORIGIN-OWN", Kit.Read(OriginShots, "a.png"));
        Assert.Equal("IMPORTED-ONE", Kit.Read(OriginShots, "a (imported).png"));

        // A repeat finds it under its "(imported)" name and adds nothing.
        var again = await ImportOnce();
        Assert.DoesNotContain(again.Ops, o => o.Item == PlanItemKind.Screenshot);
        Assert.Equal(2, Directory.GetFiles(OriginShots).Length);
    }

    [Fact]
    public async Task UndoRemovesTheCopiedScreenshotsAndKeepsOriginsOwn()
    {
        Kit.Write(OriginShots, "mine.png", "MINE");
        Kit.Write(_rig.Source, "screenshots/a.png", "A");
        var plan = await ImportOnce();

        var undone = await _rig.Engine().RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Equal(ImportState.RolledBack, undone.State);

        Assert.Equal(new[] { "mine.png" }, Directory.GetFiles(OriginShots).Select(Path.GetFileName));
        Assert.True(Kit.Exists(_rig.Source, "screenshots/a.png"));
    }

    [Fact]
    public async Task AFolderOfThousandsPlansWithoutReadingAnyPicture()
    {
        Directory.CreateDirectory(Shots);
        for (var i = 0; i < 4000; i++)
            File.WriteAllBytes(Path.Combine(Shots, $"2026-01-01_00.{i:D4}.png"), new byte[16]);

        var clock = Stopwatch.StartNew();
        var plan = await Plan();
        clock.Stop();

        Assert.Equal(4000, plan.Ops.Count(o => o.Item == PlanItemKind.Screenshot));
        Assert.Single(plan.Lines, l => l.Kind == PlanItemKind.Screenshot);  // one line, not 4000
        Assert.DoesNotContain(_rig.Opened, p => p.StartsWith(Shots, StringComparison.OrdinalIgnoreCase)); // listed, never opened
        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(20), $"planning took {clock.Elapsed}");
    }

    [Fact]
    public async Task TwoSetupsSharingOneScreenshotsFolderCopyItOnce()
    {
        using var f = new BulkFixture();
        var shared = Path.Combine(f.Rig.Root, "shared-shots");
        var first = f.Add("One");
        var second = f.Add("Two");
        Kit.Write(f.Dir(first), "screenshots/same.png", "SAME");
        Kit.Write(f.Dir(second), "screenshots/same.png", "SAME");       // the same picture in both
        Kit.Write(f.Dir(second), "screenshots/other.png", "OTHER-PIC");
        f.Setups[0] = first with { SharedGameDir = shared };
        f.Setups[1] = second with { SharedGameDir = shared };
        Kit.Write(shared, "screenshots/shared.png", "SHARED");

        var engine = f.Engine();
        var plan = await f.Plan(engine);
        var result = await engine.ApplyBulkAsync(plan, f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var landed = Directory.GetFiles(f.Target("1.21.1", "screenshots")).Select(Path.GetFileName).ToList();
        Assert.Equal(new[] { "shared.png" }, landed); // both read the shared folder: one copy, no "(imported)"
    }

    [Fact]
    public async Task TheSamePictureInTwoSetupsLandsOnceAndADifferentOneBesideIt()
    {
        using var f = new BulkFixture();
        var first = f.Add("One");
        var second = f.Add("Two");
        Kit.Write(f.Dir(first), "screenshots/same.png", "SAME");
        Kit.Write(f.Dir(second), "screenshots/same.png", "SAME");     // the same picture, copied between launchers
        Kit.Write(f.Dir(first), "screenshots/clash.png", "FIRST");
        Kit.Write(f.Dir(second), "screenshots/clash.png", "SECOND!"); // a different picture under the same name

        var engine = f.Engine();
        var result = await engine.ApplyBulkAsync(await f.Plan(engine), f.Rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        var landed = Directory.GetFiles(f.Target("1.21.1", "screenshots")).Select(Path.GetFileName).OrderBy(n => n).ToList();
        Assert.Equal(new[] { "clash (imported).png", "clash.png", "same.png" }, landed);
    }
}
