using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Caches;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Profiles;
using Xunit;

namespace OriginLauncher.Tests.Import;

// "Import everything", planning: which setups qualify, how they group per version, who wins the settings, what each
// role brings, and how setups of one version share a folder without landing on each other.
public class BulkPlanTests : IDisposable
{
    private readonly BulkFixture _f = new();

    public void Dispose() => _f.Dispose();

    // By the setup itself, not its id: SameNameWorlds... hands the engine two setups on ONE folder on purpose (a scan
    // merges those into one setup, SetupResolver, so they share the folder-only id).
    private static PlannedSetup Planned(BulkImportPlan plan, DetectedInstance setup) =>
        plan.AllSetups.Single(s => s.Setup == setup);

    // ------------------------------------------------------------ composition (B2, B5, B9)

    [Fact]
    public async Task FiveSetupsOnThreeVersionsMakeThreeGroupsNewestFirstAndEveryPlanSharesTheRunId()
    {
        _f.Add("A", "1.21.1", playtimeHours: 10);
        _f.Add("B", "1.21.1", playtimeHours: 2);
        _f.Add("C", "1.20.4");
        _f.Add("D", "1.20.4", kind: SourceKind.Modrinth);
        _f.Add("E", "1.16.5");

        var plan = await _f.Plan(_f.Engine());

        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        Assert.Equal(new[] { "1.21.1", "1.20.4", "1.16.5" }, plan.Groups.Select(g => g.Version));
        Assert.Equal(5, plan.AllSetups.Count());
        Assert.All(plan.AllSetups, s => Assert.Equal(plan.RunId, s.Plan.ImportId));
        Assert.All(plan.Groups, g => Assert.Equal(_f.Rig.GameDir(g.Version), g.TargetGameDir));
        Assert.All(plan.Groups, g => Assert.All(g.Setups, s => Assert.Equal(g.TargetGameDir, s.Plan.TargetGameDir)));
        Assert.Empty(plan.Skipped);
        Assert.True(ImportLayout.IsValidId(plan.RunId));
    }

    [Fact]
    public async Task ProgressReportsEverySetupOnceInApplyOrderAndTheFirstArrivesBeforeTheRest()
    {
        _f.Add("A", "1.21.1");
        _f.Add("B", "1.20.4");
        _f.Add("U", "1.21.5");
        var progress = new SyncBulkProgress<BulkPlanProgress>();

        var plan = await _f.Plan(_f.Engine(), progress: progress);

        Assert.Equal(new[] { 1, 2, 3 }, progress.Seen.Select(p => p.Done));
        Assert.All(progress.Seen, p => Assert.Equal(3, p.Total));
        Assert.Equal("Prism: U", progress.Seen[0].SetupLabel);                      // a skip is reported at once
        Assert.NotNull(progress.Seen[0].Skipped);
        Assert.Equal(new[] { "Prism: A", "Prism: B" }, progress.Seen.Skip(1).Select(p => p.SetupLabel));
        Assert.All(progress.Seen.Skip(1), p => Assert.NotNull(p.Planned));
        Assert.Equal(plan.AllSetups.Select(s => s.Setup.Name), progress.Seen.Skip(1).Select(p => p.Planned!.Setup.Name));
    }

    [Fact]
    public async Task PlanningCanBeCancelledBetweenSetups()
    {
        _f.Add("A", "1.21.1");
        _f.Add("B", "1.21.1");
        using var cts = new CancellationTokenSource();
        var progress = new SyncBulkProgress<BulkPlanProgress>(p => { if (p.Planned != null) cts.Cancel(); });

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => _f.Engine().PlanBulkAsync(_f.Request(), progress, cts.Token));
        Assert.Single(progress.Seen);
    }

    // ------------------------------------------------------------ selection (B1)

    [Fact]
    public async Task ALauncherSelectionTakesOnlyThatLauncherAndListsTheRestOnce()
    {
        var prism = _f.Add("A", "1.21.1", kind: SourceKind.Prism);
        var modrinth = _f.Add("B", "1.21.1", kind: SourceKind.Modrinth);
        var unticked = _f.Add("C", "1.21.1", kind: SourceKind.Prism);

        var plan = await _f.Plan(_f.Engine(), _f.Request(BulkSelection.OfLauncher(SourceKind.Prism).Without(unticked.BulkId())));

        Assert.Equal(new[] { prism.BulkId() }, plan.AllSetups.Select(s => s.Setup.BulkId()));
        Assert.Equal(SkipKind.NotSelected, plan.Skipped.Single(s => s.Setup == modrinth).Kind);
        Assert.Equal(SkipKind.Unticked, plan.Skipped.Single(s => s.Setup == unticked).Kind);
        Assert.Equal("You left this setup out.", plan.Skipped.Single(s => s.Setup == unticked).Reason);
    }

    [Fact]
    public async Task WholeRunBlockersNoAccountAndNothingSelected()
    {
        var a = _f.Add("A", "1.21.1");

        var noAccount = await _f.Plan(_f.Engine(), _f.Request(account: null));
        Assert.Contains("Sign in (or turn on offline test mode) first.", noAccount.Blocked);
        Assert.False(noAccount.CanApply);

        var nothing = await _f.Plan(_f.Engine(), _f.Request(BulkSelection.All().Without(a.BulkId())));
        Assert.Contains("Pick at least one setup to import.", nothing.Blocked);
        Assert.False(nothing.CanApply);
    }

    // ------------------------------------------------------------ qualify (B3, B21)

    [Fact]
    public async Task AnUnsupportedVersionIsSkippedWithTheExactSentenceAndNothingOfItIsOpened()
    {
        var unsupported = _f.Add("Old", "1.21.5");
        _f.Add("A", "1.21.1");

        var plan = await _f.Plan(_f.Engine());

        var skipped = plan.Skipped.Single();
        Assert.Same(unsupported, skipped.Setup);
        Assert.Equal(SkipKind.Unsupported, skipped.Kind);
        Assert.Equal("Origin doesn't support Minecraft 1.21.5, so this setup can't come over.", skipped.Reason);
        Assert.DoesNotContain(_f.Rig.Opened, p => p.StartsWith(unsupported.GameDir, StringComparison.OrdinalIgnoreCase));
        Assert.Contains(_f.Rig.Opened, p => p.StartsWith(_f.Setups[1].GameDir, StringComparison.OrdinalIgnoreCase)); // the other one was read
    }

    [Theory]
    [InlineData(null, null)]
    [InlineData("1.21.1", "Couldn't read details")]
    public async Task AnUnknownVersionIsSkippedWithTheExactSentence(string? mc, string? problem)
    {
        var unknown = _f.Add("Mystery", mc, problem: problem);

        var plan = await _f.Plan(_f.Engine());

        var skipped = plan.Skipped.Single();
        Assert.Equal(SkipKind.UnknownVersion, skipped.Kind);
        Assert.Equal("Origin couldn't tell which Minecraft version this setup is, so it can't come over.", skipped.Reason);
        Assert.DoesNotContain(_f.Rig.Opened, p => p.StartsWith(unknown.GameDir, StringComparison.OrdinalIgnoreCase));
        Assert.False(plan.CanApply);
    }

    [Fact]
    public async Task AVersionIsNeverRemappedToANearbySupportedOne()
    {
        _f.Add("Near", "1.21.5"); // 1.21.4 IS supported: still skipped, never imported "as" 1.21.4

        var plan = await _f.Plan(_f.Engine());

        Assert.Empty(plan.Groups);
        Assert.Equal(SkipKind.Unsupported, plan.Skipped.Single().Kind);
        Assert.False(Directory.Exists(_f.Rig.GameDir("1.21.4")));
    }

    [Fact]
    public async Task PerSetupProblemsMoveOnlyThatSetupToDidntComeOver()
    {
        var missing = _f.Add("Gone", "1.21.1", populate: false);
        Directory.Delete(missing.GameDir);
        var inside = _f.Add("Inside", "1.21.1", gameDir: Path.Combine(_f.Rig.Instances, "1.21.1", "accounts", "x"));
        var running = _f.Add("Running", "1.21.1");
        _f.Rig.RunningDirs.Add(running.GameDir);
        _f.Add("Fine", "1.21.1");

        var plan = await _f.Plan(_f.Engine());

        Assert.Equal(SkipKind.FolderMissing, plan.Skipped.Single(s => s.Setup == missing).Kind);
        Assert.Equal(SkipKind.InsideOrigin, plan.Skipped.Single(s => s.Setup == inside).Kind);
        Assert.Equal(SkipKind.SourceRunning, plan.Skipped.Single(s => s.Setup == running).Kind);
        Assert.Equal("Fine", plan.AllSetups.Single().Setup.Name);
        Assert.True(plan.CanApply);
    }

    [Fact]
    public async Task OriginsGameRunningForOneVersionSkipsThatVersionOnly()
    {
        _f.Add("A", "1.21.1");
        var b = _f.Add("B", "1.20.4");
        Directory.CreateDirectory(_f.Rig.GameDir("1.20.4"));
        _f.Rig.RunningDirs.Add(_f.Rig.GameDir("1.20.4"));

        var plan = await _f.Plan(_f.Engine());

        Assert.Equal(new[] { "1.21.1" }, plan.Groups.Select(g => g.Version));
        var skipped = plan.Skipped.Single(s => s.Setup == b);
        Assert.Equal(SkipKind.TargetRunning, skipped.Kind);
        Assert.StartsWith("Close Minecraft first: Origin's Minecraft 1.20.4 is still running.", skipped.Reason);
    }

    // ------------------------------------------------------------ the single import still works (B1)

    [Fact]
    public async Task TheSingleSetupFlowWithATargetVersionOverrideIsUnchanged()
    {
        TypicalSource.Populate(_f.Rig.Source);
        var engine = _f.Rig.Engine();

        var plan = await engine.PlanAsync(_f.Rig.Request(target: "1.21.4", mc: "1.21.1"), null, CancellationToken.None);
        Assert.True(plan.CanApply, string.Join("; ", plan.Blocked));
        Assert.Equal("1.21.4", plan.TargetVersion);
        Assert.Equal(_f.Rig.GameDir("1.21.4"), plan.TargetGameDir);

        var result = await engine.ApplyAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        Assert.True(File.Exists(_f.Target("1.21.4", "options.txt")));
        Assert.Equal("1.21.11", _f.Rig.Settings.Load().SelectedVersion);           // imports never move the player's pick
    }

    // ------------------------------------------------------------ the settings winner (B6)

    [Fact]
    public async Task ATenHourSetupClearlyBeatsAFiveHourOne()
    {
        var less = _f.Add("Less", playtimeHours: 5);
        var more = _f.Add("More", playtimeHours: 10);

        var group = (await _f.Plan(_f.Engine())).Groups.Single();

        Assert.Same(more, group.Winner);
        Assert.False(group.NeedsChoice);
        Assert.Equal(PickMode.Auto, group.Pick.Mode);
        Assert.Equal(new[] { more, less }, group.Setups.Select(s => s.Setup));
        Assert.Equal(new[] { SetupRole.Full, SetupRole.ModsAndPacks }, group.Setups.Select(s => s.Role));
    }

    [Fact]
    public async Task ASinglePinnedSetupBeatsMorePlaytime()
    {
        _f.Add("Played", playtimeHours: 100);
        var pinned = _f.Add("Pinned", playtimeHours: 1, pinned: true);

        var group = (await _f.Plan(_f.Engine())).Groups.Single();

        Assert.Same(pinned, group.Winner);
        Assert.False(group.NeedsChoice);
    }

    public static IEnumerable<object?[]> Unclear()
    {
        yield return new object?[] { 10L, 8L, null, null };                                           // under 1.5x
        yield return new object?[] { null, null, DateTimeOffset.Parse("2026-01-02Z"), DateTimeOffset.Parse("2026-01-01Z") }; // recency only
        yield return new object?[] { null, null, null, null };                                         // no data at all
    }

    [Theory]
    [MemberData(nameof(Unclear))]
    public async Task AnUnclearWinnerIsPreselectedFlaggedAndNeverBlocks(long? hoursA, long? hoursB, DateTimeOffset? playedA, DateTimeOffset? playedB)
    {
        _f.Add("A", playtimeHours: hoursA, lastPlayed: playedA);
        _f.Add("B", playtimeHours: hoursB, lastPlayed: playedB);

        var plan = await _f.Plan(_f.Engine());
        var group = plan.Groups.Single();

        Assert.True(group.NeedsChoice);
        Assert.Equal(PickMode.Choose, group.Pick.Mode);
        Assert.Same(group.Pick.Preselected, group.Winner);
        Assert.Equal(SetupRole.Full, group.Setups[0].Role);
        Assert.Same(group.Winner, group.Setups[0].Setup);
        Assert.True(plan.CanApply);                                                   // preselected, not blocked
    }

    [Fact]
    public async Task ThePlayersChoiceRePlansOnlyThatVersionsGroup()
    {
        var a = _f.Add("A", "1.21.1", playtimeHours: 10);
        var b = _f.Add("B", "1.21.1", playtimeHours: 9);
        _f.Add("C", "1.20.4");
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);
        var other = plan.Groups.Single(g => g.Version == "1.20.4");
        Assert.Same(a, plan.Groups[0].Winner);
        _f.Rig.Opened.Clear();

        var next = await engine.ReplanGroupAsync(plan, "1.21.1", b, null, CancellationToken.None);

        Assert.Equal(plan.RunId, next.RunId);
        var group = next.Groups.Single(g => g.Version == "1.21.1");
        Assert.Same(b, group.Winner);
        Assert.False(group.NeedsChoice);
        Assert.Equal(SetupRole.Full, Planned(next, b).Role);
        Assert.Equal(SetupRole.ModsAndPacks, Planned(next, a).Role);
        Assert.Same(other, next.Groups.Single(g => g.Version == "1.20.4"));           // untouched: the same object
        Assert.DoesNotContain(_f.Rig.Opened, p => p.StartsWith(_f.Setups[2].GameDir, StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task UntickingASetupRePlansOnlyItsVersionsGroup()
    {
        var a = _f.Add("A", "1.21.1", playtimeHours: 10);
        _f.Add("B", "1.21.1", playtimeHours: 1);
        _f.Add("C", "1.20.4");
        var engine = _f.Engine();
        var plan = await _f.Plan(engine);
        var other = plan.Groups.Single(g => g.Version == "1.20.4");

        var next = await engine.ReplanSelectionAsync(plan, BulkSelection.All().Without(a.BulkId()), null, CancellationToken.None);

        Assert.Equal("B", next.Groups.Single(g => g.Version == "1.21.1").Winner.Name);
        Assert.Same(other, next.Groups.Single(g => g.Version == "1.20.4"));
        Assert.Equal(SkipKind.Unticked, next.Skipped.Single().Kind);
    }

    // ------------------------------------------------------------ roles (B4, B7, B8)

    [Fact]
    public async Task ANonWinnerBringsModsAndPacksButNeverSettings()
    {
        var winner = _f.Add("Winner", playtimeHours: 10);
        // Another launcher, with its Mods ticked too (one launcher's mods per version by default).
        var second = _f.Add("Second", playtimeHours: 1, kind: SourceKind.Modrinth);
        Kit.Write(second.GameDir, "config/onlysecond.json", "{\"second\":1}");
        Kit.Write(second.GameDir, "config/somemod.json", "{\"a\":\"second\"}");    // the winner has it too
        Kit.Write(second.GameDir, "config/existing.json", "{\"b\":\"second\"}");
        Kit.Write(_f.Target("1.21.1"), "config/existing.json", "{\"b\":\"origin\"}"); // Origin already has it
        Kit.Write(_f.Target("1.21.1"), "config/sodium-options.json", "{}");
        Kit.WriteBytes(second.GameDir, "resourcepacks/SecondPack.zip", Kit.Zip(Kit.Text("pack.mcmeta", "second")));
        Kit.Write(second.GameDir, "options.txt", TypicalSource.Options.Replace("file/FolderPack", "file/SecondPack.zip"));
        Kit.Write(second.GameDir, "optionsof.txt", "ofFastRender:true");
        Kit.WriteBytes(second.GameDir, "mods/secondmod.jar", Kit.FabricJar("secondmod", "1.0")); // a mod only it has
        var grid = new ImportGrid().Set("1.21.1", SourceKind.Prism, ImportGrid.Things).Set("1.21.1", SourceKind.Modrinth, ImportGrid.Things);

        var plan = await _f.Plan(_f.Engine(), _f.Request(grid: grid));
        var w = Planned(plan, winner).Plan;
        var s = Planned(plan, second);

        Assert.Equal(SetupRole.ModsAndPacks, s.Role);
        Assert.DoesNotContain(s.Plan.Ops, o => o.Kind is OpKind.WriteOptions or OpKind.MergeProperties or OpKind.MergeJson);
        Assert.DoesNotContain(s.Plan.Ops, o => Path.GetFileName(o.Dest) is "optionsof.txt" or "options.txt" or "optionsshaders.txt");
        Assert.Contains(s.Plan.Ops, o => o.Kind == OpKind.MergeServers);             // its Servers cell is ticked: its servers join the list
        var secondConfigs = s.Plan.Ops.Where(o => o.Item == PlanItemKind.Config && !o.Fallback).Select(o => Path.GetFileName(o.Dest)).ToList();
        Assert.Equal(new[] { "onlysecond.json" }, secondConfigs);                     // only where nobody else writes
        Assert.Contains(s.Plan.Ops, o => o.Fallback && Path.GetFileName(o.Dest) == "somemod.json"); // a stand-in, should the winner drop out
        Assert.DoesNotContain(s.Plan.Ops, o => o.Fallback && Path.GetFileName(o.Dest) == "existing.json"); // Origin's own: never
        Assert.Contains(s.Plan.Ops, o => o.Item == PlanItemKind.Mod);                 // its own mods
        Assert.False(s.PrimaryMods);
        Assert.Contains(s.Plan.Ops, o => o.Label == "SecondPack.zip");                // its packs are copied...
        var options = w.Ops.Single(o => o.Kind == OpKind.WriteOptions);
        Assert.DoesNotContain("SecondPack", options.Extra);                            // ...but never selected
        Assert.Contains(s.Plan.Lines, l => l.Name == "Settings for Minecraft 1.21.1 come from Prism: Winner");

        var result = await _f.Engine().ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("{\"second\":1}", File.ReadAllText(_f.Target("1.21.1", "config/onlysecond.json")));
        Assert.Equal("{\"a\":1}", File.ReadAllText(_f.Target("1.21.1", "config/somemod.json")));       // the winner's
        Assert.Equal("{\"b\":\"origin\"}", File.ReadAllText(_f.Target("1.21.1", "config/existing.json"))); // Origin's, untouched
        Assert.True(File.Exists(_f.Target("1.21.1", "resourcepacks/SecondPack.zip")));
    }

    [Fact]
    public async Task ForgeOnAFabricVersionBringsPacksOnlyWithTheExactSentence()
    {
        _f.Add("Fabric", "1.20.1", playtimeHours: 1);
        var forge = _f.Add("Forgey", "1.20.1", loader: LoaderKind.Forge, playtimeHours: 100);
        Kit.WriteBytes(forge.GameDir, "resourcepacks/Faithful.zip", Kit.Zip(Kit.Text("pack.mcmeta", "forge's own")));

        var plan = await _f.Plan(_f.Engine());
        var p = Planned(plan, forge);

        const string sentence = "Forge mods can't come over. Origin runs Fabric, except on 1.8.9.";
        Assert.Equal(SetupRole.PacksOnly, p.Role);
        Assert.Equal(sentence, p.Note);
        Assert.Contains(p.Plan.Lines, l => l.Section == SummarySection.NeedsAttention && l.Name == sentence);
        Assert.Equal("Fabric", plan.Groups.Single().Winner.Name);                      // never the settings winner
        Assert.All(p.Plan.Ops, o => Assert.True(o.Item is PlanItemKind.ResourcePack or PlanItemKind.ShaderPack, o.Label));
        Assert.Contains(p.Plan.Ops, o => o.Item == PlanItemKind.ResourcePack);
        Assert.DoesNotContain(_f.Rig.Opened, f => f.StartsWith(_f.Dir(forge, "mods"), StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(_f.Rig.Opened, f => f.StartsWith(_f.Dir(forge, "saves"), StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task ForgeOn189IsAFullSetupAndBringsItsForgeMods()
    {
        var forge = _f.Add("Legacy", "1.8.9", loader: LoaderKind.Forge, populate: false);
        Kit.Write(forge.GameDir, "options.txt", "fov:0.5\n");
        Kit.WriteBytes(forge.GameDir, "mods/oldmod.jar", Kit.Zip(Kit.Text("mcmod.info", """[{"modid":"oldmod","name":"Old Mod","version":"1.0","mcversion":"1.8.9"}]""")));

        var plan = await _f.Plan(_f.Engine());
        var p = Planned(plan, forge);

        Assert.Equal(SetupRole.Full, p.Role);
        Assert.Null(p.Note);
        Assert.Contains(p.Plan.Ops, o => o.Item == PlanItemKind.Mod && o.Label == "oldmod.jar");
    }

    [Fact]
    public async Task AnUncertainVersionIsListedButStillComesOver()
    {
        // Uncertain = the detector's VersionCertain/LoaderCertain (not Confidence): a certain setup is never flagged.
        Assert.False(BulkRules.IsVersionUncertain(new DetectedInstance { Kind = SourceKind.Prism, Name = "x", GameDir = "x", McVersion = "1.21.1", Confidence = Confidence.Inferred }));
        Assert.True(BulkRules.IsVersionUncertain(new DetectedInstance { Kind = SourceKind.Prism, Name = "x", GameDir = "x", McVersion = "1.21.1", LoaderCertain = false }));
        var guessed = _f.Add("Guess", "1.21.1", confidence: Confidence.Inferred);

        var plan = await _f.Plan(_f.Engine());
        var p = Planned(plan, guessed);

        Assert.True(p.VersionUncertain);
        Assert.True(BulkRules.IsVersionUncertain(guessed));
        var line = p.Plan.Lines.Single(l => l.Name == "Not sure this is Minecraft 1.21.1");
        Assert.Equal(SummarySection.NeedsAttention, line.Section);
        Assert.Equal(BulkFixture.GuessReason, line.Reason); // the detector's own reason, not a stock sentence
        Assert.True(plan.CanApply);
    }

    // ------------------------------------------------------------ one folder, many setups (B10, B11)

    [Fact]
    public async Task AnIdenticalPackLandsOnceAndDifferentOnesGetImportedNames()
    {
        var a = _f.Add("A", playtimeHours: 40);
        var b = _f.Add("B", playtimeHours: 30);
        var c = _f.Add("C", playtimeHours: 20);
        var d = _f.Add("D", playtimeHours: 10);
        Kit.WriteBytes(c.GameDir, "resourcepacks/Faithful.zip", Kit.Zip(Kit.Text("pack.mcmeta", "faithful-C")));
        Kit.WriteBytes(d.GameDir, "resourcepacks/Faithful.zip", Kit.Zip(Kit.Text("pack.mcmeta", "faithful-D")));

        var plan = await _f.Plan(_f.Engine());

        Assert.Contains(Planned(plan, a).Plan.Ops, o => o.Label == "Faithful.zip");
        Assert.DoesNotContain(Planned(plan, b).Plan.Ops, o => !o.Fallback && o.Label.StartsWith("Faithful", StringComparison.Ordinal));
        Assert.Single(Planned(plan, b).Plan.Ops, o => o.Fallback && o.Label == "Faithful.zip");
        Assert.Contains(Planned(plan, b).Plan.Lines, l => l.Name == "Faithful.zip" && l.Reason == "already brought over from Prism: A");
        Assert.Contains(Planned(plan, c).Plan.Ops, o => o.Label == "Faithful (imported).zip");
        Assert.Contains(Planned(plan, d).Plan.Ops, o => o.Label == "Faithful (imported 2).zip");

        var result = await _f.Engine().ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(File.ReadAllBytes(_f.Dir(a, "resourcepacks/Faithful.zip")), File.ReadAllBytes(_f.Target("1.21.1", "resourcepacks/Faithful.zip")));
        Assert.Equal(File.ReadAllBytes(_f.Dir(c, "resourcepacks/Faithful.zip")), File.ReadAllBytes(_f.Target("1.21.1", "resourcepacks/Faithful (imported).zip")));
        Assert.Equal(File.ReadAllBytes(_f.Dir(d, "resourcepacks/Faithful.zip")), File.ReadAllBytes(_f.Target("1.21.1", "resourcepacks/Faithful (imported 2).zip")));
        Assert.Equal(3, Directory.GetFiles(_f.Target("1.21.1", "resourcepacks"), "Faithful*").Length);
    }

    [Fact]
    public async Task SameNameWorldsNeverMergeAndASharedFolderIsCopiedOnce()
    {
        var a = _f.Add("A", playtimeHours: 30);
        var b = _f.Add("B", playtimeHours: 20);
        var shared = _f.Add("Shared", kind: SourceKind.OfficialLauncher, playtimeHours: 10, gameDir: a.GameDir); // same folder as A
        Kit.Write(b.GameDir, "saves/World1/level.dat", "LEVEL-B");

        var plan = await _f.Plan(_f.Engine(), _f.Request(options: new ImportOptions { CopyWorlds = true }));

        Assert.Contains(Planned(plan, a).Plan.Ops, o => o.Item == PlanItemKind.World && o.Label == "World1");
        Assert.Contains(Planned(plan, b).Plan.Ops, o => o.Item == PlanItemKind.World && o.Label == "World1 (imported)");
        Assert.DoesNotContain(Planned(plan, shared).Plan.Ops, o => o.Item == PlanItemKind.World && !o.Fallback);
        Assert.Contains(Planned(plan, shared).Plan.Lines, l => l.Kind == PlanItemKind.World && l.Reason == "already brought over from Prism: A");

        var result = await _f.Engine().ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        Assert.Equal("LEVEL", File.ReadAllText(_f.Target("1.21.1", "saves/World1/level.dat")));
        Assert.Equal("LEVEL-B", File.ReadAllText(_f.Target("1.21.1", "saves/World1 (imported)/level.dat")));
        Assert.Equal(2, Directory.GetDirectories(_f.Target("1.21.1", "saves")).Length);
    }

    [Fact]
    public async Task ACacheIsBroughtOnceByTheFirstSetupThatClaimsIt()
    {
        var first = _f.Add("First", playtimeHours: 20);
        var second = _f.Add("Second", playtimeHours: 10, kind: SourceKind.Modrinth); // another launcher: both bring mods
        foreach (var s in new[] { first, second })
        {
            Kit.WriteBytes(s.GameDir, "mods/dh.jar", Kit.FabricJar("distanthorizons", "2.3.3-b", name: "Distant Horizons"));
            Kit.WriteBytes(s.GameDir, "Distant_Horizons_server_data/Hyp/aaa@minecraft@@overworld/DistantHorizons.sqlite", EngineBKit.Sqlite(s.Name));
        }

        var plan = await _f.Plan(_f.Engine(new IImportPlanContributor[] { new CacheContributor() }), _f.Request(grid: _f.AllTicked()));

        Assert.Single(Planned(plan, first).Plan.Ops, o => o.Item == PlanItemKind.Cache);
        Assert.DoesNotContain(Planned(plan, second).Plan.Ops, o => o.Item == PlanItemKind.Cache && !o.Fallback);
        Assert.Contains(Planned(plan, second).Plan.Lines, l => l.Section == SummarySection.NotImported && l.Reason == "Another of your setups already brings this one.");

        var result = await _f.Engine(new IImportPlanContributor[] { new CacheContributor() }).ApplyBulkAsync(plan, _f.Rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded, result.Error);
        Assert.Equal(EngineBKit.Sqlite("First"), File.ReadAllBytes(_f.Target("1.21.1", "Distant_Horizons_server_data/Hyp/aaa@minecraft@@overworld/DistantHorizons.sqlite")));
    }

    // ------------------------------------------------------------ files Minecraft needs (B13) and space (B15)

    [Fact]
    public async Task GameFilesArePlannedOncePerVersionWithinTheMetadataBound()
    {
        var mojang = new Mojang();
        var official = Path.Combine(_f.Rig.Root, "official-mc");
        mojang.WriteOfficial(official);
        var roots = new GameFileRoots { InstancesRoot = _f.Rig.Instances, OfficialMinecraftDir = official };
        _f.Add("A", "1.21.1");
        _f.Add("B", "1.21.1");
        _f.Add("C", "1.21.1");
        _f.Add("D", "1.20.4");
        _f.Add("E", "1.16.5");
        var gate = new FakeGate();

        var plan = await _f.Plan(_f.Engine(new IImportPlanContributor[] { new GameFileContributor(roots, mojang.Http, gate) }));

        var group = plan.Groups.Single(g => g.Version == "1.21.1");
        Assert.NotNull(group.GameFileOp);
        Assert.Equal(mojang.Files.Count, group.GameFiles!.ReusedCount);
        Assert.All(plan.AllSetups, s => Assert.DoesNotContain(s.Plan.Ops, o => o.Item == PlanItemKind.GameFile));
        Assert.All(plan.Groups.Where(g => g.Version != "1.21.1"), g => Assert.True(g.GameFiles!.MetadataUnavailable));
        Assert.True(mojang.Http.Requests.Count <= 2 + 3 * plan.Groups.Count, $"{mojang.Http.Requests.Count} requests");
        Assert.Equal(mojang.Http.Requests.Count, mojang.Http.Requests.Select(r => r.Url).Distinct().Count()); // each document once
        Assert.Equal(plan.AllSetups.Sum(s => s.Plan.BytesToCopy) + group.GameFileOp!.Bytes, plan.BytesToCopy);
    }

    [Fact]
    public async Task OneSpaceCheckCoversTheWholeRunAndSuggestsLeavingWorldsOut()
    {
        _f.Add("A", "1.21.1");
        _f.Add("B", "1.20.4");
        var full = await _f.Plan(_f.Engine());
        var needed = (long)(full.BytesToCopy * 1.1);
        var each = full.AllSetups.Max(s => s.Plan.BytesToCopy);
        Assert.True(each * 1.1 < needed - 10);

        var tight = await _f.Plan(_f.Engine(freeSpace: _ => needed - 10)); // enough for any one setup, not for all
        Assert.Single(tight.Blocked);
        Assert.StartsWith("Not enough free disk space: importing everything needs about", tight.Blocked[0]);
        Assert.All(tight.AllSetups, s => Assert.True(s.Plan.CanApply));                // no per-setup checks

        var worlds = await _f.Plan(_f.Engine(freeSpace: _ => 1), _f.Request(options: new ImportOptions { CopyWorlds = true }));
        Assert.EndsWith("Leaving worlds out would make it much smaller.", worlds.Blocked.Single());
    }
}
