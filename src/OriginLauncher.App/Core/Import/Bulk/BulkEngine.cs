using System.Diagnostics;
using System.IO;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import;

// "Import everything": every selected setup of every launcher, each into its own Minecraft version, in ONE
// undoable transaction. Planning reuses the single-import planners setup by setup (a role decides what each
// setup brings); apply runs every setup's ops inside one ImportTransaction, so a cancel, a hard failure or a
// crash anywhere puts every version back exactly as it was. The rules, by decision:
//  B3  a setup qualifies only when its version is EXACTLY one Origin supports — never remapped. Anything else is
//      skipped with a sentence, and nothing of a skipped setup is opened after the scan.
//  B4  Forge / NeoForge on a Fabric version brings packs only; on 1.8.9 (Origin runs Forge there) it is a full setup.
//  B6  per version the settings winner is VersionPicker.Pick over the setups that can carry settings; an unclear
//      pick is preselected and flagged (NeedsChoice), never a reason to block.
//  B9  order: groups newest version first; in a group the winner first, then BulkRanking.
//  B10 one DestinationLedger per target folder, shared by the group's setups.
//  B13 game-file reuse (IVersionWideContributor) runs once per version, with one metadata session for the plan.
//  B14 apply takes each gated version's gate once, ascending, after Begin; releases all after commit or rollback.
//  B16 one transaction per run; soft source problems skip an item, hard destination failures roll back everything.
public sealed partial class ImportEngine
{
    // ------------------------------------------------------------------ plan

    /// <summary>
    /// Plans every selected setup and groups them per Minecraft version. <paramref name="progress"/> reports each setup
    /// the moment it is skipped or planned (in apply order), so a panel can fill in while the rest still plans.
    /// </summary>
    public Task<BulkImportPlan> PlanBulkAsync(BulkRequest request, IProgress<BulkPlanProgress>? progress, CancellationToken ct) =>
        Task.Run(() => PlanBulkCore(request, null, progress, ct), ct);

    /// <summary>The player picked a different settings winner for one version: only that version's group is planned again.</summary>
    public Task<BulkImportPlan> ReplanGroupAsync(BulkImportPlan plan, string version, DetectedInstance newWinner,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct)
    {
        var request = plan.Request ?? throw new InvalidOperationException("This plan can't be planned again (it wasn't made by the engine).");
        var choices = new Dictionary<string, string>(request.WinnerChoices, StringComparer.Ordinal) { [version] = newWinner.BulkId() };
        return Task.Run(() => PlanBulkCore(request with { WinnerChoices = choices }, plan, progress, ct), ct);
    }

    /// <summary>The player ticked or unticked setups: only the version groups whose setups changed are planned again.</summary>
    public Task<BulkImportPlan> ReplanSelectionAsync(BulkImportPlan plan, BulkSelection selection,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct)
    {
        var request = plan.Request ?? throw new InvalidOperationException("This plan can't be planned again (it wasn't made by the engine).");
        return Task.Run(() => PlanBulkCore(request with { Selection = selection }, plan, progress, ct), ct);
    }

    private sealed class PlanCounter
    {
        public int Done;
        public int Total;
    }

    private async Task<BulkImportPlan> PlanBulkCore(BulkRequest request, BulkImportPlan? previous,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct)
    {
        var watch = Stopwatch.StartNew();
        var plan = new BulkImportPlan { RunId = previous?.RunId ?? _env.NewId(), Request = request };
        var counter = new PlanCounter { Total = request.Setups.Count };

        void Skip(DetectedInstance setup, string reason, SkipKind kind)
        {
            var skipped = new SkippedSetup(setup, reason, kind);
            plan.Skipped.Add(skipped);
            progress?.Report(new BulkPlanProgress(++counter.Done, counter.Total, setup.BulkLabel(), Skipped: skipped));
        }

        // ---- whole-run blockers (B21) ----
        var account = request.AccountKey;
        var accountOk = !string.IsNullOrWhiteSpace(account) && InstanceLayout.IsAccountKey(account);
        if (!accountOk) plan.Blocked.Add("Sign in (or turn on offline test mode) first.");

        var ranked = request.Setups.OrderBy(s => s, BulkRanking.Order).ToList();
        var selected = ranked.Where(request.Selection.Includes).ToList();
        if (selected.Count == 0) plan.Blocked.Add("Pick at least one setup to import.");

        // Every scanned setup appears exactly once: the ones left out are listed with why.
        foreach (var setup in ranked.Where(s => !request.Selection.Includes(s)))
        {
            if (request.Selection.IsUnticked(setup)) Skip(setup, "You left this setup out.", SkipKind.Unticked);
            else Skip(setup, $"Not part of this import: only {LauncherName(request.Selection.Launcher)} setups were picked.", SkipKind.NotSelected);
        }

        // ---- qualify (B3, B21): version first (no IO at all), then the folder ----
        var qualified = new List<DetectedInstance>();
        foreach (var setup in selected)
        {
            ct.ThrowIfCancellationRequested();
            var version = setup.McVersion;
            if (setup.Problem != null || string.IsNullOrWhiteSpace(version)) Skip(setup, BulkRules.UnknownVersionReason, SkipKind.UnknownVersion);
            else if (!request.SupportedTargets.Contains(version)) Skip(setup, BulkRules.UnsupportedReason(version), SkipKind.Unsupported);
            else if (!Directory.Exists(setup.GameDir)) Skip(setup, "Couldn't find this setup's folder, so it can't come over.", SkipKind.FolderMissing);
            else if (Overlaps(setup.GameDir, _env.InstancesRoot) || Overlaps(setup.GameDir, _env.ImportsRoot))
                Skip(setup, "This folder is part of Origin's own game folders, so it can't be imported from.", SkipKind.InsideOrigin);
            else if (_env.IsGameRunning(setup.GameDir))
                Skip(setup, "Minecraft is running from this setup. Close it, then plan the import again to bring it over.", SkipKind.SourceRunning);
            else qualified.Add(setup);
        }
        if (!accountOk) return plan; // no account, no target folders: nothing more to plan

        // ---- one group per exact version, newest first (B5, B9) ----
        var metadata = new MetadataCache(); // B13: one metadata session for every version in this plan
        foreach (var bucket in qualified.GroupBy(s => s.McVersion!, StringComparer.Ordinal).OrderBy(g => g.Key, BulkRanking.NewestFirst))
        {
            ct.ThrowIfCancellationRequested();
            var version = bucket.Key;
            var targetDir = InstanceLayout.GameDir(_env.InstancesRoot, version, account!);
            if (Directory.Exists(targetDir) && _env.IsGameRunning(targetDir))
            {
                foreach (var setup in bucket)
                    Skip(setup, $"Close Minecraft first: Origin's Minecraft {version} is still running. Plan the import again after closing it to bring this setup over.",
                        SkipKind.TargetRunning);
                continue;
            }

            var members = bucket.ToList();
            while (members.Count > 0)
            {
                var (winner, pick, needsChoice) = PickWinner(request, version, members);
                var order = OrderGroup(members, winner);
                if (previous != null && Reusable(previous, request, version, order, winner) is { } same)
                {
                    // Unchanged: its plans stand as they are. Only the choice flag follows the request (the player
                    // confirming the preselected winner clears it).
                    same.NeedsChoice = needsChoice;
                    plan.Groups.Add(same);
                    foreach (var planned in same.Setups)
                        progress?.Report(new BulkPlanProgress(++counter.Done, counter.Total, planned.Setup.BulkLabel(), Planned: planned));
                    break;
                }

                var (group, failedWinner) = await PlanGroupAsync(request, plan.RunId, version, targetDir, order, winner, pick, needsChoice,
                    metadata, counter, Skip, progress, ct).ConfigureAwait(false);
                if (failedWinner != null)
                {
                    // The winner couldn't be planned (it changed since the scan): pick again from the rest, from scratch.
                    members.Remove(failedWinner);
                    continue;
                }
                if (group.Setups.Count > 0) plan.Groups.Add(group);
                break;
            }
        }

        // ---- the run as a whole ----
        plan.BytesToCopy = plan.AllSetups.Sum(s => s.Plan.BytesToCopy) + plan.Groups.Sum(g => g.VersionPlan?.Ops.Sum(o => o.Bytes) ?? 0);
        if (!request.PlayWithExternalMods && plan.AllSetups.Any(s => s.Plan.ProfileId != null))
            plan.Notes.Add("The imported mods are in new profiles, but \"Play with my own mods\" is off, so they won't load until you turn it on.");
        if (selected.Count > 0 && plan.Groups.Count == 0)
            plan.Blocked.Add("None of the setups you picked can come over. The list below says why.");

        // B15: ONE space check for the whole run (each version's game files counted once), plus 10%.
        var needed = (long)(plan.BytesToCopy * 1.1);
        var free = _env.FreeSpace(_env.InstancesRoot);
        if (plan.Groups.Count > 0 && needed > free)
            plan.Blocked.Add($"Not enough free disk space: importing everything needs about {ImportSummary.Megabytes(needed)} and {ImportSummary.Megabytes(free)} is free."
                + (request.Options.CopyWorlds ? " Leaving worlds out would make it much smaller." : ""));

        watch.Stop();
        _env.Log?.Invoke($"import bulk plan: {plan.AllSetups.Count()} setups in {plan.Groups.Count} versions, {plan.Skipped.Count} skipped, "
            + $"{metadata.Requests} metadata requests, {watch.ElapsedMilliseconds} ms");
        return plan;
    }

    private static string LauncherName(SourceKind? kind) => kind switch
    {
        SourceKind.OfficialLauncher => "Minecraft Launcher",
        SourceKind.Prism => "Prism / MultiMC",
        SourceKind.Modrinth => "Modrinth",
        SourceKind.CurseForge => "CurseForge",
        _ => "the picked launcher's",
    };

    // B6: the winner among the setups that can carry settings (not packs-only). The player's choice wins; an
    // unclear pick uses the picker's preselected setup and is flagged so the panel can offer the choice.
    private static (DetectedInstance? Winner, PickResult Pick, bool NeedsChoice) PickWinner(BulkRequest request, string version,
        List<DetectedInstance> members)
    {
        var participants = members.Where(s => !BulkRules.IsPacksOnly(s, version)).OrderBy(s => s, BulkRanking.Order).ToList();
        if (participants.Count == 0) return (null, new PickResult(PickMode.None, Array.Empty<DetectedInstance>(), null), false);
        var pick = VersionPicker.Pick(participants);
        if (request.WinnerChoices.TryGetValue(version, out var chosenId)
            && participants.FirstOrDefault(p => string.Equals(p.BulkId(), chosenId, StringComparison.OrdinalIgnoreCase)) is { } chosen)
            return (chosen, pick, false);
        var winner = pick.Preselected ?? participants[0];
        return (winner, pick, pick.Mode == PickMode.Choose);
    }

    // B9: the winner first, then everyone else in BulkRanking order.
    private static List<DetectedInstance> OrderGroup(List<DetectedInstance> members, DetectedInstance? winner)
    {
        var order = new List<DetectedInstance>();
        if (winner != null) order.Add(winner);
        order.AddRange(members.Where(s => !ReferenceEquals(s, winner)).OrderBy(s => s, BulkRanking.Order));
        return order;
    }

    // A group of the previous plan that is exactly what would be planned now (same setups in the same order, same
    // winner, same switches and account) is kept: re-planning one version never re-reads the others.
    private static VersionGroup? Reusable(BulkImportPlan previous, BulkRequest request, string version, List<DetectedInstance> order,
        DetectedInstance? winner)
    {
        var old = previous.Groups.FirstOrDefault(g => g.Version == version);
        var before = previous.Request;
        if (old == null || before == null) return null;
        if (before.Options != request.Options || before.AccountKey != request.AccountKey) return null;
        if (old.Setups.Count != order.Count) return null;
        for (var i = 0; i < order.Count; i++)
            if (old.Setups[i].Setup.BulkId() != order[i].BulkId() || !Equals(old.Setups[i].Setup, order[i])) return null;
        var oldWinner = old.Setups.FirstOrDefault(s => s.Role == SetupRole.Full)?.Setup;
        return Equals(oldWinner?.BulkId(), winner?.BulkId()) ? old : null;
    }

    // Returns the planned group, or (when the settings winner itself can't be planned) the winner that failed: the
    // caller then picks a new winner and plans the group again, so no setup's settings role rests on a missing setup.
    private async Task<(VersionGroup Group, DetectedInstance? FailedWinner)> PlanGroupAsync(BulkRequest request, string runId, string version, string targetDir,
        List<DetectedInstance> order, DetectedInstance? winner, PickResult pick, bool needsChoice, MetadataCache metadata,
        PlanCounter counter, Action<DetectedInstance, string, SkipKind> skip, IProgress<BulkPlanProgress>? progress, CancellationToken ct)
    {
        var group = new VersionGroup
        {
            Version = version, TargetGameDir = targetDir, Winner = winner ?? order[0], Pick = pick, NeedsChoice = needsChoice,
        };
        var ledger = new DestinationLedger(); // B10: one per target folder, shared by the group
        var plannedProfiles = new List<ModProfile>();
        var facts = request.Facts?.Invoke(version) ?? new TargetFacts();

        for (var position = 0; position < order.Count; position++)
        {
            var setup = order[position];
            ct.ThrowIfCancellationRequested();
            var role = BulkRules.IsPacksOnly(setup, version) ? SetupRole.PacksOnly
                : ReferenceEquals(setup, winner) ? SetupRole.Full : SetupRole.ModsAndPacks;
            var r = new ImportRequest
            {
                Source = setup, TargetVersion = version, AccountKey = request.AccountKey, Options = request.Options,
                SupportedTargets = request.SupportedTargets, BundledOriginJarPath = facts.BundledOriginJarPath,
                CatalogMods = facts.CatalogMods, TargetHasIris = facts.TargetHasIris,
                // B11: unique among the version's existing profiles AND the ones this run already named.
                ExistingProfiles = request.ExistingProfiles.Concat(plannedProfiles).ToList(),
                PlayWithExternalMods = request.PlayWithExternalMods,
                SelectedVersion = request.SelectedVersion, SelectedProfileId = request.SelectedProfileId,
                Role = role, Ledger = ledger, ImportId = runId, Bulk = true, Metadata = metadata,
                SettingsWinnerLabel = role == SetupRole.Full ? null : winner?.BulkLabel(),
                SetupKey = $"{version}#{position}", // who owns a ledger claim (labels can repeat; positions can't)
            };

            var setupPlan = BuildPlan(r, null, ct, out var fs);
            // Per-setup contributors only (caches); game-file reuse runs once for the version below (B13).
            // A packs-only setup brings nothing else, so nothing else of it is read.
            if (setupPlan.CanApply && role != SetupRole.PacksOnly)
                foreach (var contributor in _env.Contributors.Where(c => c is not IVersionWideContributor))
                    await contributor.ContributeAsync(setupPlan, r, fs, ct).ConfigureAwait(false);
            Finish(setupPlan, fs, checkSpace: false);
            if (!setupPlan.CanApply)
            {
                // It changed between the check above and its planning (its folder went away, a game started).
                skip(setup, "Couldn't read this setup while planning, so it can't come over. Plan the import again to retry it.", SkipKind.FolderMissing);
                if (role == SetupRole.Full) return (group, setup);
                continue;
            }

            if (setupPlan.ProfileId != null)
                plannedProfiles.Add(new ModProfile { Id = setupPlan.ProfileId, Name = setupPlan.ProfileName!, Version = version });
            string? note = null;
            if (role == SetupRole.PacksOnly)
            {
                note = BulkRules.ForgeNote;
                setupPlan.Lines.Insert(0, new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Mod, BulkRules.ForgeNote));
            }
            var uncertain = BulkRules.IsVersionUncertain(setup);
            if (uncertain)
                setupPlan.Lines.Insert(0, new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Setting,
                    $"Not sure this is Minecraft {version}", BulkRules.UncertainReason(setup)));

            var planned = new PlannedSetup(setup, role, setupPlan, note, uncertain);
            group.Setups.Add(planned);
            progress?.Report(new BulkPlanProgress(++counter.Done, counter.Total, setup.BulkLabel(), Planned: planned));
        }

        // B13: the version's shared work (game-file reuse), once for the whole group.
        var versionWide = _env.Contributors.OfType<IVersionWideContributor>().ToList();
        if (group.Setups.Count > 0 && versionWide.Count > 0)
        {
            ct.ThrowIfCancellationRequested();
            var anchor = group.Setups[0].Setup;
            var versionPlan = new ImportPlan
            {
                ImportId = runId, Source = anchor, TargetVersion = version, Options = request.Options, TargetGameDir = targetDir,
            };
            var versionRequest = new ImportRequest
            {
                Source = anchor, TargetVersion = version, AccountKey = request.AccountKey, Options = request.Options,
                SupportedTargets = request.SupportedTargets, ImportId = runId, Bulk = true, Metadata = metadata,
            };
            // No instance root: the files a version needs come from the launchers' shared folders, verified by SHA-1.
            var versionFs = new SourceFs(_env.Guard, _env.OnSourceOpen);
            foreach (var contributor in versionWide)
                await contributor.ContributeAsync(versionPlan, versionRequest, versionFs, ct).ConfigureAwait(false);
            versionPlan.BytesToCopy = versionPlan.Ops.Sum(o => o.Bytes);
            group.VersionPlan = versionPlan;
            group.GameFileOp = versionPlan.Ops.FirstOrDefault(o => o.Item == PlanItemKind.GameFile) ?? versionPlan.Ops.FirstOrDefault();
            group.GameFiles = versionPlan.GameFiles;
            group.Lines.AddRange(versionPlan.Lines);
        }
        return (group, null);
    }

    // ------------------------------------------------------------------ apply

    /// <summary>
    /// Applies a bulk plan in ONE transaction. Always completes with a result: whatever goes wrong, every version
    /// is put back and the caller gets a sentence and a state, never a faulted task.
    /// </summary>
    public Task<BulkImportResult> ApplyBulkAsync(BulkImportPlan plan, IImportSettings settings,
        IProgress<BulkProgress>? progress, CancellationToken ct) =>
        Task.Run(() =>
        {
            try
            {
                return ApplyBulk(plan, settings, progress, ct);
            }
            catch (Exception ex) when (ex is not OutOfMemoryException)
            {
                return new BulkImportResult
                {
                    RunId = plan.RunId, State = ImportState.RolledBack, Skipped = plan.Skipped.ToList(),
                    Error = $"The import didn't finish ({ImportErrors.Plain(ex)}). If anything was changed, Origin puts it back the next time it starts.",
                };
            }
        }, CancellationToken.None);

    /// <summary>Origin's own game for a version started while the import ran: the whole run is put back.</summary>
    private sealed class TargetStartedException : Exception
    {
        public TargetStartedException(string version)
            : base($"Origin's Minecraft {version} started while importing") { }
    }

    private sealed class SetupState
    {
        public SetupResultKind Kind = SetupResultKind.Failed;
        public string? Message;
        public readonly List<SummaryLine> Extra = new();

        // Plan lines a landed stand-in made untrue ("already brought over from X"): left out of the result.
        public readonly HashSet<SummaryLine> Superseded = new(ReferenceEqualityComparer.Instance);
    }

    private BulkImportResult ApplyBulk(BulkImportPlan plan, IImportSettings settings, IProgress<BulkProgress>? progress, CancellationToken ct)
    {
        if (!plan.CanApply)
            return new BulkImportResult
            {
                RunId = plan.RunId, State = ImportState.Planned, Skipped = plan.Skipped.ToList(),
                Error = plan.Blocked.Count > 0 ? string.Join(" ", plan.Blocked) : "There's nothing to import.",
            };

        // The plan can be minutes old: Origin's game started since must stop the run before it changes anything.
        foreach (var group in plan.Groups)
            if (Directory.Exists(group.TargetGameDir) && _env.IsGameRunning(group.TargetGameDir))
                return new BulkImportResult
                {
                    RunId = plan.RunId, State = ImportState.Planned, Skipped = plan.Skipped.ToList(),
                    Error = $"Close Minecraft first: Origin's Minecraft {group.Version} is still running.",
                };

        var setups = plan.AllSetups.ToList();
        var states = setups.ToDictionary(s => s, _ => new SetupState());
        var versionExtra = plan.Groups.ToDictionary(g => g, _ => new List<SummaryLine>());
        // B14: only versions whose files are written into the shared version folder take that version's gate,
        // each exactly once (the gates are not reentrant), always in ascending version order.
        var gated = plan.Groups.Where(g => g.VersionPlan is { ProvisioningGate: not null, Ops.Count: > 0 })
            .OrderBy(g => g.Version, BulkRanking.Ascending).ToList();

        var logRoot = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(_env.InstancesRoot)) ?? _env.InstancesRoot;
        using var tx = new ImportTransaction(_env.ImportsRoot, logRoot, plan.RunId, _env.Clock,
            $"{setups.Count} {(setups.Count == 1 ? "setup" : "setups")}", null, _env.Guard);
        tx.Journal.TargetVersions = plan.Groups.Select(g => g.Version).ToList();
        tx.Journal.TargetGameDirs = plan.Groups.Select(g => g.TargetGameDir).ToList();
        tx.Journal.SetupCount = setups.Count;
        tx.Journal.GateVersions = gated.Select(g => g.Version).ToList();

        var held = new List<IAsyncDisposable>();
        // Claims whose owner didn't write them (its setup was skipped, or its copy failed on the source side): a later
        // setup's stand-in (PlannedOp.Fallback) for the same claim then lands its copy, once. Per version group: a
        // claim belongs to one target folder, so another group's released claim never fires a stand-in here.
        var released = new HashSet<string>(StringComparer.Ordinal);
        var landedByStandIn = new HashSet<string>(StringComparer.Ordinal);
        var bytesTotal = plan.BytesToCopy;
        long bytesDone = 0;
        var index = 0;
        var label = "";
        void Report(ImportProgress inner, string? waiting = null) =>
            progress?.Report(new BulkProgress(index, setups.Count, label, inner, bytesDone, bytesTotal, waiting));
        var inner = new InlineProgress(p => Report(p));
        try
        {
            tx.Begin();
            foreach (var group in gated)
            {
                var waiting = $"Waiting for Minecraft {group.Version} to finish installing";
                label = waiting;
                Report(new ImportProgress(ImportStage.GameFiles, 0, 0, waiting), waiting);
                held.Add(group.VersionPlan!.ProvisioningGate!.AcquireAsync(group.Version, ct).GetAwaiter().GetResult());
            }
            // With the gates held a launch can't be half-started: "not running" now stays true (F1's order).
            foreach (var group in plan.Groups)
                if (Directory.Exists(group.TargetGameDir) && _env.IsGameRunning(group.TargetGameDir))
                    throw new TargetStartedException(group.Version);

            foreach (var group in plan.Groups)
            {
                released.Clear();
                landedByStandIn.Clear();
                // The version's shared work first (game-file reuse), once for the group.
                if (group.VersionPlan is { } versionPlan)
                {
                    label = $"Minecraft {group.Version}";
                    var versionFs = new SourceFs(_env.Guard, _env.OnSourceOpen);
                    foreach (var op in versionPlan.Ops)
                    {
                        ct.ThrowIfCancellationRequested();
                        Report(new ImportProgress(StageOf(op.Item), 0, versionPlan.Ops.Count, op.Label));
                        Execute(op, versionPlan, tx, versionFs, ct, versionExtra[group], inner);
                        bytesDone += op.Bytes;
                    }
                }

                foreach (var planned in group.Setups)
                {
                    ct.ThrowIfCancellationRequested();
                    var state = states[planned];
                    var setupPlan = planned.Plan;
                    label = planned.Setup.BulkLabel();
                    Report(new ImportProgress(ImportStage.Planning, 0, setupPlan.Ops.Count, label));

                    // B17: a game started from the SOURCE since the plan: only that setup is left out.
                    if (Directory.Exists(planned.Setup.GameDir) && _env.IsGameRunning(planned.Setup.GameDir))
                    {
                        state.Kind = SetupResultKind.Skipped;
                        state.Message = "Minecraft was started from this setup while importing, so it was skipped.";
                        state.Extra.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Setting,
                            "Minecraft was started from this setup while importing, so it was skipped. Close it and import it again."));
                        bytesDone += setupPlan.BytesToCopy;
                        foreach (var claimed in setupPlan.Ops.Where(o => o.ClaimKey != null && !o.Fallback)) released.Add(claimed.ClaimKey!);
                        index++;
                        continue;
                    }
                    // ...but Origin's own game for the version starting means its folder may be in use: everything goes back.
                    if (Directory.Exists(group.TargetGameDir) && _env.IsGameRunning(group.TargetGameDir))
                        throw new TargetStartedException(group.Version);

                    var fs = new SourceFs(_env.Guard, _env.OnSourceOpen) { InstanceRoot = planned.Setup.GameDir };
                    for (var i = 0; i < setupPlan.Ops.Count; i++)
                    {
                        var op = setupPlan.Ops[i];
                        ct.ThrowIfCancellationRequested();
                        // A stand-in runs only when its claim was released, and only the first one per claim.
                        if (op.Fallback && !(released.Contains(op.ClaimKey!) && landedByStandIn.Add(op.ClaimKey!))) continue;
                        Report(new ImportProgress(StageOf(op.Item), i, setupPlan.Ops.Count, op.Label));
                        try
                        {
                            Execute(op, setupPlan, tx, fs, ct, state.Extra, inner);
                            if (op.Fallback)
                            {
                                state.Extra.Add(new SummaryLine(SummarySection.Imported, op.Item, op.Label,
                                    "came over from this setup, because the setup it was coming from was left out"));
                                if (op.DeferredLine != null) state.Superseded.Add(op.DeferredLine);
                            }
                        }
                        catch (Exception ex) when (IsSourceProblem(op, ex))
                        {
                            // B16: a soft SOURCE problem (gone, unreadable, in use) leaves that item out, with a line —
                            // and its claim passes to the next setup that has the same thing.
                            state.Extra.Add(new SummaryLine(SummarySection.NotImported, op.Item, op.Label,
                                "Couldn't read it in your old setup (it may be in use, or gone), so it was left out."));
                            if (op.ClaimKey != null)
                            {
                                released.Add(op.ClaimKey);
                                if (op.Fallback) landedByStandIn.Remove(op.ClaimKey);
                            }
                        }
                        if (!op.Fallback) bytesDone += op.Bytes;
                    }
                    state.Kind = planned.Role == SetupRole.PacksOnly ? SetupResultKind.ImportedPartly : SetupResultKind.Imported;
                    index++;
                }
            }

            label = "Finishing";
            Report(new ImportProgress(ImportStage.Finishing, 0, 0));
            ct.ThrowIfCancellationRequested(); // the last chance to cancel: past here the settings change is made
            var delta = BuildDelta(plan, states);
            if (delta != null)
            {
                tx.RecordSettingsDelta(delta);
                settings.Apply(delta);
            }
            tx.Commit();
            _env.Log?.Invoke($"import bulk apply: {tx.Journal.Entries.Count} journal entries, {tx.JournalBytesWritten} journal bytes written, "
                + $"{ImportSummary.Megabytes(bytesDone)} copied");
            return Finished(plan, ImportState.Committed, states, versionExtra, cancelled: false, error: null);
        }
        catch (OperationCanceledException)
        {
            return BulkRolledBack(plan, tx, settings, states, versionExtra, cancelled: true, error: null);
        }
        catch (TargetStartedException ex)
        {
            return BulkRolledBack(plan, tx, settings, states, versionExtra, cancelled: false,
                error: $"Close Minecraft first: {ex.Message}. Everything was put back the way it was.");
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            return BulkRolledBack(plan, tx, settings, states, versionExtra, cancelled: false,
                error: $"The import didn't finish ({ImportErrors.Plain(ex)}). Everything was put back the way it was.");
        }
        finally
        {
            for (var i = held.Count - 1; i >= 0; i--) ReleaseGate(held[i]);
        }
    }

    // B16: which failures are the SOURCE's fault (that item is left out) and which are the destination's (everything
    // goes back). The transaction says so itself: every source read (a file copy, each file of a world or folder
    // pack) turns its failures into SourceUnreadableException; anything else — a write, a move, a full disk, a
    // contributor's own step (game files and caches handle their source problems themselves) — is the destination's.
    private static bool IsSourceProblem(PlannedOp op, Exception ex) =>
        ex is SourceUnreadableException && !ImportFiles.IsDiskFull(ex) && op.Kind != OpKind.Custom;

    // B12: ONE settings change for the run: every new profile, and the selection moves to the first setup (in
    // BulkRanking order) that got a profile — else just to that setup's version. PlayWithExternalMods is never touched.
    private static SettingsDelta? BuildDelta(BulkImportPlan plan, Dictionary<PlannedSetup, SetupState> states)
    {
        var imported = plan.AllSetups
            .Where(s => states[s].Kind is SetupResultKind.Imported or SetupResultKind.ImportedPartly)
            .ToList();
        if (imported.Count == 0) return null;
        var profiles = imported.Where(s => s.Plan.ProfileId != null)
            .Select(s => new ModProfile { Id = s.Plan.ProfileId!, Name = s.Plan.ProfileName!, Version = s.Plan.TargetVersion })
            .ToList();
        var ranked = imported.OrderBy(s => s.Setup, BulkRanking.Order).ToList();
        var select = ranked.FirstOrDefault(s => s.Plan.ProfileId != null) ?? ranked[0];
        return new SettingsDelta
        {
            AddProfiles = profiles.Count > 0 ? profiles : null,
            SetSelectedVersion = select.Plan.TargetVersion,
            SetSelectedProfileId = select.Plan.ProfileId,
            PreviousSelectedVersion = plan.Request?.SelectedVersion,
            PreviousSelectedProfileId = plan.Request?.SelectedProfileId,
        };
    }

    private BulkImportResult BulkRolledBack(BulkImportPlan plan, ImportTransaction tx, IImportSettings settings,
        Dictionary<PlannedSetup, SetupState> states, Dictionary<VersionGroup, List<SummaryLine>> versionExtra, bool cancelled, string? error)
    {
        var problems = tx.Rollback(settings); // does not throw
        foreach (var state in states.Values)
        {
            state.Kind = SetupResultKind.Failed;
            state.Message = "Put back: the import didn't finish.";
        }
        if (problems.Count > 0)
            error = string.Join(" ", new[] { error }.Where(e => e != null).Concat(new[] { "Some changes couldn't be undone:" }).Concat(problems));
        return Finished(plan, ImportState.RolledBack, states, versionExtra, cancelled, error);
    }

    // The structured result (B20), its summary text from the same data, and imports/<run>/summary.txt.
    private BulkImportResult Finished(BulkImportPlan plan, ImportState state, Dictionary<PlannedSetup, SetupState> states,
        Dictionary<VersionGroup, List<SummaryLine>> versionExtra, bool cancelled, string? error)
    {
        var versions = plan.Groups.Select(g =>
        {
            var outcomes = g.Setups.Select(s =>
            {
                var st = states[s];
                return new SetupOutcome(s.Setup, s.Role, st.Kind, st.Message, null,
                    s.Plan.Lines.Where(l => !st.Superseded.Contains(l)).Concat(st.Extra).ToList());
            }).ToList();
            var winner = g.Setups.FirstOrDefault(s => s.Role == SetupRole.Full);
            var settingsFrom = winner != null && states[winner].Kind == SetupResultKind.Imported ? winner.Setup.BulkLabel() : null;
            string? settingsNote = null;
            if (state == ImportState.Committed && winner != null && states[winner].Kind == SetupResultKind.Skipped)
            {
                // The winner was left out at apply time: nobody's settings came over (the others never planned theirs),
                // and every "settings come from <winner>" line the plan wrote is now untrue — it says so instead.
                var label = winner.Setup.BulkLabel();
                settingsNote = $"didn't come over: {label} was left out while importing";
                var planned = new SummaryLine(SummarySection.NotImported, PlanItemKind.Setting, $"Settings for Minecraft {g.Version} come from {label}");
                var actual = planned with { Name = $"Settings for Minecraft {g.Version} {settingsNote}" };
                outcomes = outcomes.Select(o => o.Lines == null ? o : o with { Lines = o.Lines.Select(l => l == planned ? actual : l).ToList() }).ToList();
            }
            // The plan's file lines are summed up by GameFiles; only its other lines and the apply's own travel on.
            var lines = g.Lines.Where(l => l.Kind != PlanItemKind.GameFile).Concat(versionExtra[g]).ToList();
            return new VersionOutcome(g.Version, outcomes, settingsFrom, g.GameFiles, lines, settingsNote);
        }).ToList();
        var draft = new BulkImportResult
        {
            RunId = plan.RunId, State = state, Cancelled = cancelled, Error = error, Versions = versions,
            Skipped = plan.Skipped.ToList(), Notes = plan.Notes.ToList(),
            DidntComeOver = plan.Skipped.Select(k => new NotImportedItem(k.Setup.BulkLabel(), "Whole setup", k.Reason)).ToList(),
        };
        var text = BulkSummary.Render(draft);
        WriteSummary(plan.RunId, text);
        return new BulkImportResult
        {
            RunId = draft.RunId, State = draft.State, Cancelled = draft.Cancelled, Error = draft.Error, Versions = draft.Versions,
            Skipped = draft.Skipped, Notes = draft.Notes, DidntComeOver = draft.DidntComeOver, SummaryText = text,
        };
    }

    /// <summary>A synchronous IProgress (Progress&lt;T&gt; posts to a context, which would reorder the per-setup reports).</summary>
    private sealed class InlineProgress : IProgress<ImportProgress>
    {
        private readonly Action<ImportProgress> _report;
        public InlineProgress(Action<ImportProgress> report) => _report = report;
        public void Report(ImportProgress value) => _report(value);
    }
}
