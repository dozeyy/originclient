using System.Diagnostics;
using System.Text;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Import.Mods;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Regression tests for the review of Engine A: one (or more) per finding, named after what must stay true.
public class ImportFixRoundTests : IDisposable
{
    private readonly Rig _rig = new();

    public void Dispose()
    {
        foreach (var path in _restore) try { RestoreAccess(path); } catch (Exception) { }
        _rig.Dispose();
    }

    private readonly List<string> _restore = new();

    private string Dst(string rel, string version = "1.21.1") => Path.Combine(_rig.GameDir(version), rel.Replace('/', Path.DirectorySeparatorChar));
    private string Src(string rel) => Path.Combine(_rig.Source, rel.Replace('/', Path.DirectorySeparatorChar));

    private async Task<(ImportPlan Plan, ImportResult Result)> Run(ImportRequest? request = null, ImportEngine? engine = null)
    {
        engine ??= _rig.Engine();
        var plan = await engine.PlanAsync(request ?? _rig.Request(), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        return (plan, await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None));
    }

    private sealed class ThrowingSettings : IImportSettings
    {
        private readonly Func<Exception>? _apply;
        private readonly Func<Exception>? _revert;
        public ThrowingSettings(Func<Exception>? apply, Func<Exception>? revert) { _apply = apply; _revert = revert; }
        public void Apply(SettingsDelta delta) { if (_apply != null) throw _apply(); }
        public void Revert(SettingsDelta delta) { if (_revert != null) throw _revert(); }
    }

    // ------------------------------------------------------------ 1: the guard decides before anything reads a pack

    [Fact]
    public async Task APackWhoseNameLooksLikeACredentialIsSkippedNotImportedAndNeverFailsTheImport()
    {
        // "Authentic" and "Session" trip the credential name pattern.
        Kit.Write(_rig.Source, "options.txt",
            "fov:0.5\nresourcePacks:[\"vanilla\",\"file/Faithful.zip\",\"file/Faithful Authentic.zip\"]\n");
        Kit.WriteBytes(_rig.Source, "resourcepacks/Faithful.zip", Kit.Zip(Kit.Text("pack.mcmeta", "ok")));
        Kit.WriteBytes(_rig.Source, "resourcepacks/Faithful Authentic.zip", Kit.Zip(Kit.Text("pack.mcmeta", "denied")));
        Kit.WriteBytes(_rig.GameDir(), "resourcepacks/Faithful Authentic.zip", Kit.Zip(Kit.Text("pack.mcmeta", "denied"))); // would be byte-compared
        Kit.Write(_rig.Source, "config/iris.properties", "shaderPack=SessionShaders.zip\nenableShaders=true\n");
        Kit.WriteBytes(_rig.Source, "shaderpacks/SessionShaders.zip", Kit.Zip(Kit.Text("a", "b")));
        Kit.Write(_rig.Source, "shaderpacks/SessionShaders.zip.txt", "X=1\n");

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);                                    // no "left alone for safety" throw, no rollback
        Assert.Contains("Faithful Authentic.zip", plan.SkippedForSafety);
        Assert.Contains("SessionShaders.zip", plan.SkippedForSafety);
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NotImported && l.Name == "SessionShaders.zip" && l.Reason!.Contains("Skipped for safety"));
        var packs = File.ReadAllLines(Dst("options.txt")).Single(l => l.StartsWith("resourcePacks:"));
        Assert.Equal("resourcePacks:[\"vanilla\",\"file/Faithful.zip\"]", packs);       // the denied pack is not referenced
        Assert.False(File.Exists(Dst("shaderpacks/SessionShaders.zip")));
        Assert.False(File.Exists(Dst("shaderpacks/SessionShaders.zip.txt")));
        if (File.Exists(Dst("config/iris.properties")))
            Assert.DoesNotContain("Session", File.ReadAllText(Dst("config/iris.properties")));     // and shaders are not pointed at it
        // the recording hook never saw a denied file opened — not even to compare it
        Assert.DoesNotContain(_rig.Opened, p => Path.GetFileName(p).Contains("Authentic") || Path.GetFileName(p).Contains("Session"));
    }

    [Fact]
    public async Task AnOpThatReachesApplyWithADeniedSourceIsSkippedAndSummarisedNotThrown()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        Kit.Write(_rig.Source, "somefile.txt", "ok");
        var denied = Kit.Write(_rig.Source, "my-token.json", "SECRET-TOKEN-BYTES");
        var engine = _rig.Engine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        plan.Ops.Add(new PlannedOp { Kind = OpKind.CopyFile, Item = PlanItemKind.Config, Label = "my-token.json", Source = denied, Dest = Dst("config/my-token.json") });
        plan.Ops.Add(new PlannedOp { Kind = OpKind.CopyFile, Item = PlanItemKind.Config, Label = "somefile.txt", Source = Src("somefile.txt"), Dest = Dst("config/somefile.txt") });

        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.False(File.Exists(Dst("config/my-token.json")));
        Assert.True(File.Exists(Dst("config/somefile.txt")));                            // the rest still lands
        Assert.Contains("my-token.json", result.SummaryText);
        Assert.DoesNotContain("SECRET-TOKEN-BYTES", result.SummaryText);
        Assert.DoesNotContain(_rig.Opened, p => Path.GetFileName(p) == "my-token.json");
    }

    [Fact]
    public void TheTransactionsOwnCopyPathRefusesACredentialFileEvenIfAskedDirectly()
    {
        var denied = Kit.Write(_rig.Source, "launcher_accounts.json", "SECRET");
        using var tx = new ImportTransaction(_rig.Imports, _rig.Root, ProfileId(), () => DateTime.UtcNow, "x", "1.21.1");
        tx.Begin();

        var ex = Assert.Throws<IOException>(() => tx.CopyFile(denied, Path.Combine(_rig.Root, "dest", "launcher_accounts.json"), false));

        Assert.Contains("left alone for safety", ex.Message);
        Assert.False(File.Exists(Path.Combine(_rig.Root, "dest", "launcher_accounts.json")));
    }

    private static string ProfileId() => OriginLauncher.App.Core.Profiles.ProfileRules.NewId();

    // ------------------------------------------------------------ 2: links inside an instance are never followed

    internal static void Link(string link, string target)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(link)!);
        if (OperatingSystem.IsWindows())
        {
            var start = new ProcessStartInfo("cmd.exe", $"/c mklink /J \"{link}\" \"{target}\"")
                { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
            using var process = Process.Start(start)!;
            process.WaitForExit();
            Assert.True(process.ExitCode == 0, "couldn't create a junction for the test");
        }
        else
        {
            Directory.CreateSymbolicLink(link, target);
        }
    }

    [Fact]
    public async Task ALinkNestedInsideAWorldIsNotFollowedAndIsListedNeedsAttention()
    {
        var profile = Path.Combine(_rig.Root, "somebody-elses-profile");
        Kit.Write(profile, "Documents/private.txt", "PRIVATE-FILE-CONTENT");
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        Kit.Write(_rig.Source, "saves/World1/level.dat", "LEVEL");
        Kit.Write(_rig.Source, "saves/World1/region/r.0.0.mca", "REGION");
        Link(Src("saves/World1/escape"), profile);
        Link(Src("saves/World1/loop"), Src("saves/World1"));                              // points at its own parent

        var (plan, result) = await Run(_rig.Request(options: new ImportOptions { CopyWorlds = true }));

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("REGION", Kit.Read(_rig.GameDir(), "saves/World1/region/r.0.0.mca"));
        Assert.False(Directory.Exists(Dst("saves/World1/escape")));
        Assert.DoesNotContain(Directory.EnumerateFiles(_rig.Instances, "private.txt", SearchOption.AllDirectories), _ => true);
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Name.Contains("Linked item left out") && l.Name.Contains("escape"));
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Name.Contains("loop"));
    }

    [Fact]
    public async Task ATopLevelLinkedFolderTheInstancePointsAtIsFollowed()
    {
        // A common Prism setup: saves/ (and config/) are links to somewhere shared. That is the player's own instance, so it is imported.
        var shared = Path.Combine(_rig.Root, "shared-config");
        Kit.Write(shared, "somemod.json", "{\"a\":1}");
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        Link(Src("config"), shared);

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("{\"a\":1}", File.ReadAllText(Dst("config/somemod.json")));
        Assert.DoesNotContain(plan.Lines, l => l.Name.Contains("Linked item left out"));
    }

    // ------------------------------------------------------------ 3: an unreadable folder is reported, never silently empty

    internal static void DenyAccess(string dir)
    {
        if (OperatingSystem.IsWindows())
        {
            using var process = Process.Start(new ProcessStartInfo("icacls", $"\"{dir}\" /deny \"*S-1-1-0:(OI)(CI)(RX)\"")
                { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true })!;
            process.WaitForExit();
            Assert.True(process.ExitCode == 0, "couldn't deny access for the test");
        }
        else
        {
            File.SetUnixFileMode(dir, UnixFileMode.None);
        }
    }

    internal static void RestoreAccess(string dir)
    {
        if (OperatingSystem.IsWindows())
        {
            using var process = Process.Start(new ProcessStartInfo("icacls", $"\"{dir}\" /remove:d \"*S-1-1-0\"")
                { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true })!;
            process.WaitForExit();
        }
        else
        {
            File.SetUnixFileMode(dir, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
    }

    [Fact]
    // 2026-10-19 stress round: a single import now follows the bulk rule (B16) — the world is LEFT OUT, never half
    // copied, and the rest of the import still lands (it used to roll everything back over one unreadable folder).
    public async Task AFolderThatCantBeReadLeavesTheWorldOutInsteadOfCopyingHalfAWorld()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        Kit.Write(_rig.Source, "saves/World1/level.dat", "LEVEL");
        Kit.Write(_rig.Source, "saves/World1/region/r.0.0.mca", "REGION");
        Kit.Write(_rig.Source, "saves/World1/playerdata/a.dat", "PLAYER");
        var locked = Src("saves/World1/region");
        DenyAccess(locked);
        _restore.Add(locked);

        var engine = _rig.Engine();
        var plan = await engine.PlanAsync(_rig.Request(options: new ImportOptions { CopyWorlds = true }), null, CancellationToken.None);
        Assert.Contains(plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Name.StartsWith("Couldn't read:"));   // said at plan time
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.True(result.Succeeded, result.Error);
        Assert.False(Directory.Exists(Path.Combine(_rig.GameDir(), "saves", "World1")));  // no half world anywhere
        Assert.Contains("fov:0.5", File.ReadAllText(Path.Combine(_rig.GameDir(), "options.txt")));
        Assert.Contains("World1: Couldn't read it in your old setup", result.SummaryText);
        Assert.Empty(Directory.EnumerateFileSystemEntries(ImportLayout.RunDir(_rig.Imports, plan.ImportId)).Where(p => Path.GetFileName(p) == "staging"));
    }

    [Fact]
    public void TheWalkContinuesPastAnUnreadableFolderAndRemembersIt()
    {
        var root = Path.Combine(_rig.Root, "walk");
        Kit.Write(root, "good/a.txt", "a");
        Kit.Write(root, "locked/b.txt", "b");
        Kit.Write(root, "zzz/c.txt", "c");
        var locked = Path.Combine(root, "locked");
        DenyAccess(locked);
        _restore.Add(locked);
        var fs = new SourceFs();

        var files = fs.EnumerateFiles(root, recursive: true).Select(Path.GetFileName).OrderBy(n => n).ToList();

        Assert.Equal(new[] { "a.txt", "c.txt" }, files);                                 // the rest is still returned
        Assert.Contains("walk/locked", fs.UnreadablePaths);                              // and the loss is on record
    }

    // ------------------------------------------------------------ 4: the game starting after the plan stops the apply

    [Fact]
    public async Task AGameStartedAfterThePlanRefusesTheApplyBeforeAnythingChanges()
    {
        TypicalSource.Populate(_rig.Source);
        var engine = _rig.Engine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.True(plan.CanApply);
        var settingsBefore = _rig.Settings.FileHash();

        _rig.RunningDirs.Add(_rig.Source);
        var sourceRunning = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        _rig.RunningDirs.Clear();
        Directory.CreateDirectory(_rig.GameDir());
        _rig.RunningDirs.Add(_rig.GameDir());
        var destinationRunning = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        foreach (var refused in new[] { sourceRunning, destinationRunning })
        {
            Assert.False(refused.Succeeded);
            Assert.Equal(ImportState.Planned, refused.State);
            Assert.StartsWith("Close Minecraft first", refused.Error);
        }
        Assert.False(File.Exists(Dst("options.txt")));
        Assert.False(Directory.Exists(_rig.Imports));                                     // not even a journal was started
        Assert.Equal(settingsBefore, _rig.Settings.FileHash());
    }

    // ------------------------------------------------------------ 5, 6: undoing a finished import never destroys anything

    [Fact]
    public async Task UndoingAnImportWhoseBackupsWereClearedIsRefusedAndNothingChanges()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        Kit.Write(_rig.Source, "config/somemod.json", "{\"a\":1}");
        Kit.Write(_rig.GameDir(), "config/somemod.json", "{\"a\":999}");
        var engine = _rig.Engine();
        var (plan, result) = await Run(engine: engine);
        Assert.True(result.Succeeded);
        Directory.Delete(ImportLayout.Backup(_rig.Imports, plan.ImportId), recursive: true);   // what pruning older imports does
        var after = Kit.TreeHash(_rig.Instances, mtimes: true);

        var undo = await engine.RollbackAsync(plan.ImportId, _rig.Settings);

        Assert.Equal(ImportState.Planned, undo.State);
        Assert.Contains("too old to undo", undo.Error);
        Assert.Equal(after, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal(ImportState.Committed, ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!.State);
    }

    [Fact]
    public async Task UndoingAnImportIsRefusedWhenThePlayerHasUsedWhatItCreatedSince()
    {
        TypicalSource.Populate(_rig.Source);
        var engine = _rig.Engine();
        var (plan, result) = await Run(_rig.Request(options: new ImportOptions { CopyWorlds = true }), engine);
        Assert.True(result.Succeeded);
        var finished = ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!.FinishedUtc!.Value;

        // The player plays the imported world (a changed file inside the created folder) and edits their settings.
        var played = Dst("saves/World1/region/r.0.0.mca");
        File.WriteAllText(played, "PROGRESS MADE AFTER THE IMPORT");
        File.SetLastWriteTimeUtc(played, finished.AddMinutes(30));
        File.SetLastWriteTimeUtc(Dst("options.txt"), finished.AddMinutes(31));
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);

        var undo = await engine.RollbackAsync(plan.ImportId, _rig.Settings);

        Assert.Equal(ImportState.Planned, undo.State);
        Assert.Contains("changed after the import", undo.Error);
        Assert.Contains("World1", undo.Error);
        Assert.Contains("options.txt", undo.Error);
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));              // the player's progress is untouched
        Assert.Equal("PROGRESS MADE AFTER THE IMPORT", File.ReadAllText(played));
    }

    [Fact]
    public async Task AnUntouchedFinishedImportCanStillBeUndone()
    {
        TypicalSource.Populate(_rig.Source);
        var engine = _rig.Engine();
        var (plan, result) = await Run(_rig.Request(options: new ImportOptions { CopyWorlds = true }), engine);
        Assert.True(result.Succeeded);

        var undo = await engine.RollbackAsync(plan.ImportId, _rig.Settings);

        Assert.Equal(ImportState.RolledBack, undo.State);
        Assert.Null(undo.Error);
        Assert.False(Directory.Exists(_rig.Instances));
    }

    // ------------------------------------------------------------ 7: a failing rollback never escapes as an exception

    [Fact]
    public async Task AnImportWithNoModsNeverTouchesTheLauncherSettingsSoALockedSettingsFileCantFailIt()
    {
        TypicalSource.Populate(_rig.Source, withMods: false);
        Kit.Write(_rig.GameDir(), "options.txt", "fov:1.0\n");
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);
        var settings = new ThrowingSettings(() => new InvalidOperationException("the settings file is locked"),
            () => new InvalidOperationException("still locked"));
        var engine = _rig.Engine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);

        var result = await engine.ApplyAsync(plan, settings, null, CancellationToken.None);   // no mods: no profile, no selection change

        Assert.Equal(ImportState.Committed, result.State);
        Assert.Null(plan.SettingsDelta);
        var undo = await engine.RollbackAsync(plan.ImportId, settings);
        Assert.Equal(ImportState.RolledBack, undo.State);
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));              // the files came back exactly
        Assert.Equal(ImportState.RolledBack, ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!.State);
        Assert.True(File.Exists(ImportLayout.Summary(_rig.Imports, plan.ImportId)));
    }

    // With mods the import adds a profile: a locked settings file then rolls the whole import back (files and the
    // half-made profile folder), as a result, never an exception.
    [Fact]
    public async Task ALockedSettingsFileRollsAnImportWithModsBackWholeAndNeverThrows()
    {
        TypicalSource.Populate(_rig.Source);
        Kit.Write(_rig.GameDir(), "options.txt", "fov:1.0\n");
        var before = Kit.TreeHash(_rig.Instances, mtimes: true);
        var settings = new ThrowingSettings(() => new InvalidOperationException("the settings file is locked"),
            () => new InvalidOperationException("still locked"));
        var engine = _rig.Engine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.NotNull(plan.SettingsDelta?.AddProfile);

        var result = await engine.ApplyAsync(plan, settings, null, CancellationToken.None);

        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.NotNull(result.Error);
        Assert.Equal(before, Kit.TreeHash(_rig.Instances, mtimes: true));              // no profile folder left behind
        Assert.Equal(ImportState.RolledBack, ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!.State);
    }

    [Fact]
    public async Task AJournalThatCannotBeWrittenStillGivesAnImportResultNotAFaultedTask()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        const string fixedId = "0123456789abcdef0123456789abcdef";
        var engine = new ImportEngine(new ImportEnvironment
        {
            InstancesRoot = _rig.Instances, ImportsRoot = _rig.Imports, NewId = () => fixedId,
        });
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Directory.CreateDirectory(Path.Combine(ImportLayout.RunDir(_rig.Imports, fixedId), "journal.json")); // a folder where the journal file must go

        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.NotNull(result.Error);
        Assert.False(File.Exists(Dst("options.txt")));                                    // nothing was written without a journal
    }

    // ------------------------------------------------------------ 8: never import from Origin's own folders

    [Theory]
    [InlineData("target")]       // the very folder the import writes to
    [InlineData("parent")]       // a folder that contains it
    [InlineData("other")]        // another Origin account / version folder
    [InlineData("instances")]    // Origin's whole instances folder
    public async Task ASourceThatIsOrContainsOriginsOwnFoldersIsBlocked(string which)
    {
        Directory.CreateDirectory(_rig.GameDir());
        Directory.CreateDirectory(_rig.GameDir("1.20.4"));
        Kit.Write(_rig.GameDir(), "options.txt", "fov:0.5\n");
        var folder = which switch
        {
            "target" => _rig.GameDir(),
            "parent" => Path.GetDirectoryName(_rig.GameDir())!,
            "other" => _rig.GameDir("1.20.4"),
            _ => _rig.Instances,
        };
        var request = _rig.Request() with { Source = _rig.SourceInstance() with { GameDir = folder } };

        var plan = await _rig.Engine().PlanAsync(request, null, CancellationToken.None);

        Assert.False(plan.CanApply);
        Assert.Contains(plan.Blocked, b => b.Contains("part of Origin's own game folders"));
        Assert.Empty(plan.Ops);
    }

    [Fact]
    public async Task ASourceElsewhereIsNotBlockedByTheOverlapRuleEvenWithASimilarName()
    {
        // "src-instance" next to (not inside) the Origin folders, and a sibling whose name merely starts the same.
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var lookalike = Path.Combine(Path.GetDirectoryName(_rig.Instances)!, "instances-backup");
        Kit.Write(lookalike, "options.txt", "fov:0.5\n");
        var request = _rig.Request() with { Source = _rig.SourceInstance() with { GameDir = lookalike } };

        Assert.True((await _rig.Engine().PlanAsync(request, null, CancellationToken.None)).CanApply);
        Assert.True((await _rig.Engine().PlanAsync(_rig.Request(), null, CancellationToken.None)).CanApply);
    }

    // ------------------------------------------------------------ 9: a hostile jar can't exhaust the stack or memory

    private static byte[] NestedChain(int levels)
    {
        var jar = Kit.FabricJar("level" + levels);
        for (var i = levels - 1; i >= 0; i--)
            jar = Kit.FabricJar("level" + i, nested: new[] { ("META-INF/jars/inner.jar", jar) });
        return jar;
    }

    [Fact]
    public void NestedJarsAreReadAtMostTwoLevelsDeep()
    {
        var info = ModMetadataReader.Read(new MemoryStream(NestedChain(40)), "deep.jar");   // 40 deep: a quine in spirit

        Assert.Equal("level0", info.Id);
        Assert.Equal(new[] { "level1", "level2" }, info.Nested.Select(n => n.Id).OrderBy(n => n));
    }

    [Fact]
    public void TheBytesBufferedForNestedJarsAreCappedPerTopLevelJar()
    {
        var big = Kit.FabricJar("big", nested: new[] { ("META-INF/jars/x.jar", Kit.Zip(Kit.Text("padding.bin", new string('p', 4096)))) });
        var outer = Kit.FabricJar("outer", nested: new[] { ("META-INF/jars/big.jar", big) });

        var unlimited = ModMetadataReader.Read(new MemoryStream(outer), "outer.jar");
        var starved = ModMetadataReader.Read(new MemoryStream(outer), "outer.jar", nestedByteBudget: 100);

        Assert.Contains(unlimited.Nested, n => n.Id == "big");
        Assert.Empty(starved.Nested);                                                   // over budget: not read, not an error
        Assert.Equal("outer", starved.Id);
    }

    // ------------------------------------------------------------ 10: honest, path-free messages

    [Fact]
    public async Task AFailureIsNotReportedAsCancelledAndNoAbsolutePathReachesTheTextTheUserSees()
    {
        Kit.Write(_rig.Source, "options.txt", "fov:0.5\n");
        var secretPath = Path.Combine(_rig.Root, "very-private-folder", "settings.json");
        // A destination step that fails with an absolute path in its message (as .NET's IO errors do).
        var failing = EngineBKit.Engine(_rig, new IImportPlanContributor[] { new FailingStep(() => new IOException($"Couldn't write '{secretPath}'")) });
        var plan = await failing.PlanAsync(_rig.Request(), null, CancellationToken.None);

        var failed = await failing.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);

        Assert.StartsWith("Failed", failed.SummaryText.Split('\n').Select(l => l.Trim()).First(l => l.StartsWith("Failed") || l.StartsWith("Cancelled")));
        Assert.DoesNotContain("Cancelled", failed.SummaryText);
        Assert.False(failed.Cancelled);
        foreach (var text in new[] { failed.Error!, failed.SummaryText })
        {
            Assert.DoesNotContain(_rig.Root, text);
            Assert.DoesNotContain("very-private-folder", text);
        }
        Assert.Contains("settings.json", failed.Error);                                  // the file NAME is still useful

        using var cts = new CancellationTokenSource();
        var engine = _rig.Engine();
        var plan2 = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        var cancelled = await engine.ApplyAsync(plan2, new ThrowingSettings(null, () => new IOException("stuck")),
            new SyncProgress(p => { if (p.Stage == ImportStage.Finishing) cts.Cancel(); }), cts.Token);
        Assert.True(cancelled.Cancelled);
        Assert.Contains("Cancelled", cancelled.SummaryText);
    }

    [Theory]
    [InlineData("Access to the path 'C:\\Users\\Will\\AppData\\Local\\OriginLauncher\\instances\\1.21.1\\options.txt' is denied.", "options.txt")]
    [InlineData("Could not find file '/home/will/.local/share/origin/imports/abc/journal.json'.", "journal.json")]
    [InlineData("The process cannot access the file because it is being used by another process.", "being used by another process")]
    public void ErrorTextKeepsTheFileNameAndDropsTheMachinePath(string message, string mustKeep)
    {
        var plain = ImportErrors.Plain(message);
        Assert.Contains(mustKeep, plain);
        Assert.DoesNotContain("Users", plain);
        Assert.DoesNotContain("/home", plain);
        Assert.DoesNotContain("AppData", plain);
    }

    // ------------------------------------------------------------ the server list: merged, never replaced

    [Fact]
    public async Task ImportedServersAreAddedAfterOriginsOwnAndAServerOriginHasIsNeverAddedTwice()
    {
        Kit.WriteBytes(_rig.Source, "servers.dat", Kit.ServersDat(("Hypixel", "MC.Hypixel.net:25565"), ("Friends", "10.0.0.5")));
        Kit.WriteBytes(_rig.GameDir(), "servers.dat", Kit.ServersDat(("My Hypixel", "mc.hypixel.net"), ("Local", "localhost")));

        var (plan, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        // Origin's servers stay first and as they were; only the one Origin didn't have is appended.
        Assert.Equal(new[] { ("My Hypixel", "mc.hypixel.net"), ("Local", "localhost"), ("Friends", "10.0.0.5") }, Kit.Servers(Dst("servers.dat")));
        Assert.Contains(plan.Lines, l => l.Name == "servers.dat (server list)" && l.Reason == "1 server added after the ones Origin has");
        // The old list is backed up, so an undo puts Origin's exactly back.
        var undone = await _rig.Engine().RollbackAsync(plan.ImportId, _rig.Settings);
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.Equal(new[] { ("My Hypixel", "mc.hypixel.net"), ("Local", "localhost") }, Kit.Servers(Dst("servers.dat")));
    }

    [Fact]
    public async Task WhenOriginHasNoServerListTheImportedOneIsWrittenAndAnIdenticalOneChangesNothing()
    {
        Kit.WriteBytes(_rig.Source, "servers.dat", Kit.ServersDat(("Hypixel", "mc.hypixel.net")));

        var (_, fresh) = await Run();
        Assert.True(fresh.Succeeded);
        Assert.Equal(new[] { ("Hypixel", "mc.hypixel.net") }, Kit.Servers(Dst("servers.dat")));
        var stamp = File.GetLastWriteTimeUtc(Dst("servers.dat"));

        var (again, same) = await Run();                                                // every server is there already
        Assert.True(same.Succeeded);
        Assert.Equal(stamp, File.GetLastWriteTimeUtc(Dst("servers.dat")));
        Assert.Contains(again.Lines, l => l.Name == "servers.dat (server list)" && l.Reason == "every server is already in Origin's list");
    }

    [Fact]
    public async Task AnUnreadableOriginServerListIsLeftAsItIsWithALine()
    {
        Kit.WriteBytes(_rig.Source, "servers.dat", Kit.ServersDat(("Hypixel", "mc.hypixel.net")));
        Kit.Write(_rig.GameDir(), "servers.dat", "NOT NBT");

        var (_, result) = await Run();

        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("NOT NBT", File.ReadAllText(Dst("servers.dat")));
    }
}
