using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.GameFiles;
using Xunit;
using Xunit.Abstractions;

namespace OriginLauncher.Tests.Import;

// Regression tests for the round-2 review follow-ups (F1-F8), one or more per finding, named after what must stay true.
public class ImportReviewRound3Tests : IDisposable
{
    private readonly Rig _rig = new();
    private readonly Mojang _mojang = new();
    private readonly ITestOutputHelper _out;

    public ImportReviewRound3Tests(ITestOutputHelper output) => _out = output;

    public void Dispose() => _rig.Dispose();

    private string Official => Path.Combine(_rig.Root, "dotminecraft");

    private ImportEngine GameFileEngine(IVersionGate? planGate = null, IVersionGate? undoGate = null) =>
        EngineBKit.Engine(_rig, new IImportPlanContributor[]
        {
            new GameFileContributor(new GameFileRoots { InstancesRoot = _rig.Instances, OfficialMinecraftDir = Official }, _mojang.Http, planGate),
        }, versionGate: undoGate);

    private string VersionFile(string rel) => Path.Combine(_rig.Instances, "1.21.1", rel.Replace('/', Path.DirectorySeparatorChar));

    // ------------------------------------------------------------ F1: the gate first, then "is the game running?"

    [Fact]
    public async Task F1_ApplyLooksForARunningGameOnlyOnceItHoldsTheVersionGate()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        _mojang.WriteOfficial(Official);
        Directory.CreateDirectory(_rig.GameDir());
        var gate = new FakeGate();
        var engine = GameFileEngine(gate);
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);

        var launch = await gate.AcquireAsync("1.21.1", CancellationToken.None);   // a launch of 1.21.1 is starting...
        var apply = engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        await Task.Delay(200);
        Assert.False(apply.IsCompleted);                                           // the import waits for the gate
        _rig.RunningDirs.Add(_rig.GameDir());                                       // ...and the game is up before it lets go
        await launch.DisposeAsync();
        var result = await apply;

        Assert.Equal(ImportState.Planned, result.State);
        Assert.Equal("Close Minecraft first: Origin's game is still running.", result.Error);
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal(2, gate.Released);
    }

    [Fact]
    public async Task F1_UndoLooksForARunningGameOnlyOnceItHoldsTheVersionGate()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var gate = new FakeGate();
        var engine = EngineBKit.Engine(_rig, Array.Empty<IImportPlanContributor>(), versionGate: gate);
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.True((await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None)).Succeeded);
        var options = Path.Combine(_rig.GameDir(), "options.txt");

        var launch = await gate.AcquireAsync("1.21.1", CancellationToken.None);
        var undo = engine.RollbackAsync(plan.ImportId, _rig.Settings);
        await Task.Delay(200);
        Assert.False(undo.IsCompleted);
        _rig.RunningDirs.Add(_rig.GameDir());
        await launch.DisposeAsync();
        var refused = await undo;

        Assert.Equal(ImportState.Planned, refused.State);
        Assert.StartsWith("Close Minecraft first", refused.Error);
        Assert.True(File.Exists(options));                                          // nothing undone under a running game
        Assert.Equal(gate.Acquired, gate.Released);
    }

    // ------------------------------------------------------------ F2: an interrupted undo of a finished import keeps game files

    private sealed class SnapshotSettings : IImportSettings
    {
        private readonly IImportSettings _inner;
        private readonly Action _onRevert;
        public SnapshotSettings(IImportSettings inner, Action onRevert) { _inner = inner; _onRevert = onRevert; }
        public void Apply(SettingsDelta delta) => _inner.Apply(delta);
        public void Revert(SettingsDelta delta) { _onRevert(); _inner.Revert(delta); }
    }

    [Fact]
    public async Task F2_AnUndoOfAFinishedImportCutShortIsFinishedByRecoveryWithTheGameFilesKept()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        _mojang.WriteOfficial(Official);
        var engine = GameFileEngine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.True((await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None)).Succeeded);
        var journalPath = ImportLayout.Journal(_rig.Imports, plan.ImportId);

        // Imports no longer change launcher settings, but one made by an older launcher has a settings step in its
        // journal (first, so the undo reaches it last): its revert is where the journal is read mid-undo.
        var old = ImportJournalStore.Load(journalPath)!;
        old.Entries.Insert(0, new JournalEntry { Kind = JournalEntryKinds.SettingsDelta, Delta = new SettingsDelta() });
        ImportJournalStore.Save(old, journalPath);

        // The journal as it is on disk while the undo runs (what a crash or a failed final save would leave).
        string? midUndo = null;
        var settings = new SnapshotSettings(_rig.Settings, () => midUndo ??= File.ReadAllText(journalPath));
        Assert.Equal(ImportState.RolledBack, (await engine.RollbackAsync(plan.ImportId, settings)).State);

        Assert.NotNull(midUndo);
        File.WriteAllText(journalPath, midUndo);                                     // "the undo never finished"
        var cut = ImportJournalStore.Load(journalPath)!;
        Assert.Equal(ImportState.Applying, cut.State);                                // recovery will pick it up...
        Assert.True(cut.UndoOfCommitted);                                            // ...knowing it was a finished import's undo

        var recovered = ImportRecovery.Recover(_rig.Imports, Path.Combine(_rig.Root, "OriginLauncher"), _rig.Settings, null, CancellationToken.None);

        Assert.Equal(1, recovered);
        foreach (var rel in _mojang.Required)
            Assert.True(File.Exists(VersionFile(rel)), rel);                          // the reused game files stay
        Assert.Equal(ImportState.RolledBack, ImportJournalStore.Load(journalPath)!.State);
    }

    // ------------------------------------------------------------ F3: linked first-level settings files are followed

    [Fact]
    public void F3_ALinkedOptionsFileAtTheInstanceRootIsFollowedButNotADeeperOne()
    {
        var real = Kit.Write(_rig.Root, "shared/options.txt", "fov:0.5\n");
        var link = Path.Combine(_rig.Source, "options.txt");
        var deeper = Path.Combine(_rig.Source, "config", "linked.json");
        Directory.CreateDirectory(Path.GetDirectoryName(deeper)!);
        try
        {
            File.CreateSymbolicLink(link, real);
            File.CreateSymbolicLink(deeper, real);
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or IOException)
        {
            // Windows without Developer Mode can't make a file symlink; CI (Linux) runs this for real.
            _out.WriteLine($"file symlinks unavailable here ({ex.GetType().Name}); not exercised on this machine");
            return;
        }
        var fs = new SourceFs { InstanceRoot = _rig.Source };

        Assert.True(fs.Allow(link));                                                 // first-level settings file: followed
        Assert.Equal("fov:0.5\n", fs.ReadText(link));
        Assert.False(fs.Allow(Path.Combine(_rig.Source, "config", "linked.json")));  // config/ is followed, a link inside it is not
    }

    // ------------------------------------------------------------ F4: game files never block the undo of a finished import

    [Fact]
    public async Task F4_AGameFileChangedAfterTheImportDoesNotBlockItsUndo()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        _mojang.WriteOfficial(Official);
        var engine = GameFileEngine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.True((await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None)).Succeeded);
        File.SetLastWriteTimeUtc(VersionFile("assets/indexes/17.json"), DateTime.UtcNow.AddHours(1)); // a launch rewrote it

        var journal = ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!;
        Assert.Null(ImportUndo.CannotUndoReason(journal));
        var undone = await engine.RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.True(File.Exists(VersionFile("assets/indexes/17.json")));             // and it stays
    }

    // ------------------------------------------------------------ F5: a vanished file is never a reason to throw

    [Fact]
    public void F5_SizesOfMissingFilesAreNullOrZeroNeverAnException()
    {
        var missing = Path.Combine(_rig.Root, "gone.jar");
        Assert.Null(ImportFiles.SizeOrNull(missing));
        Assert.Equal(0, ImportFiles.SizeOf(missing));
        Assert.Null(ImportFiles.SizeOrNull(_rig.Root));                              // a folder is not a file size
        Assert.Null(ImportFiles.SizeOrNull("bad\0path"));
        var real = Kit.Write(_rig.Root, "here.jar", "12345");
        Assert.Equal(5, ImportFiles.SizeOrNull(real));
    }

    // ------------------------------------------------------------ F6: a junctioned assets / libraries folder still feeds reuse

    [Fact]
    public void F6_AVerifiedGameFileUnderAJunctionedFolderIsAllowedOnlyTheLeafIsChecked()
    {
        var elsewhere = Path.Combine(_rig.Root, "big-drive-assets");
        var file = Kit.Write(elsewhere, "objects/ab/abcdef", "ASSET");
        var mc = Path.Combine(_rig.Root, "dotminecraft-linked");
        Directory.CreateDirectory(mc);
        ImportFixRoundTests.Link(Path.Combine(mc, "assets"), elsewhere);
        var candidate = Path.Combine(mc, "assets", "objects", "ab", "abcdef");
        Assert.True(File.Exists(candidate));
        var fs = new SourceFs { InstanceRoot = mc };

        Assert.True(fs.AllowVerifiedGameFile(candidate));                            // its SHA-1 proves it; the folder link is fine
        Assert.False(fs.Allow(candidate));                                           // the ordinary rule still refuses it
        Assert.False(fs.AllowVerifiedGameFile(Path.Combine(mc, "assets")));          // the leaf itself being a link: refused
        Assert.Equal("ASSET", File.ReadAllText(file));
    }

    // ------------------------------------------------------------ F7: no link probe per walked file

    [Fact]
    public void F7_CopyingATreeDoesNotProbeEveryFileForLinksAgain()
    {
        for (var i = 0; i < 200; i++) Kit.Write(_rig.Source, $"saves/W/region/r.{i}.mca", "R" + i);
        var fs = new SourceFs { InstanceRoot = _rig.Source };
        using var tx = new ImportTransaction(_rig.Imports, _rig.Root, new string('c', 32), () => DateTime.UtcNow, "x", "1.21.1");
        tx.Begin();

        tx.CopyTree(fs, Path.Combine(_rig.Source, "saves", "W"), Path.Combine(_rig.GameDir(), "saves", "W"), CancellationToken.None);

        Assert.Equal(200, Directory.GetFiles(Path.Combine(_rig.GameDir(), "saves", "W", "region")).Length);
        Assert.InRange(fs.LinkProbes, 0, 5);                                         // the start folder only, not 200 files
        tx.Rollback(null);
    }

    // ------------------------------------------------------------ F8: quoted paths with apostrophes are cut down whole

    [Theory]
    [InlineData(@"Access to the path 'C:\Users\O'Neil\saves\world.dat' is denied.", "Access to the path \"world.dat\" is denied")]
    [InlineData(@"Could not find file 'C:\Users\Will Henry\it's mine\options.txt'.", "Could not find file \"options.txt\"")]
    [InlineData(@"Copy 'C:\a\one.txt' to 'C:\b\two.txt' failed", "Copy \"one.txt\" to \"two.txt\" failed")]
    [InlineData("Expected '/' or '\\' here", "Expected '/' or '\\' here")]
    [InlineData("The file \"C:\\Users\\Will\\x.json\" is busy", "The file \"x.json\" is busy")]
    public void F8_QuotedPathsAreTrimmedToTheirNameEvenWithApostrophes(string message, string expected) =>
        Assert.Equal(expected, ImportErrors.Plain(message));
}
