using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// Step 3: Review (what the plan brings, from the plan itself), one setup on its own (Single) and Details.
internal sealed partial class ImportPage
{
    private const string Checking = "Checking what can come over...";
    private const string CheckFailed = "Couldn't check your setups. Press TRY AGAIN.";
    private const string SkippedTitle = "LEFT OUT";

    private static readonly Comparer<string> Newest = Comparer<string>.Create((a, b) => McVersion.Compare(b, a));

    // ---------------------------------------------------------------- 3. review

    private void OpenReview(bool reuseFailure = false)
    {
        // The page's version ticks belong to the pick pages; Import everything and the grid decide by themselves.
        if (_model.Mode == ImportMode.Ticks && _model.SelectedVersionCount == 0) return;
        if (_pane is Pane.Launchers or Pane.Scope or Pane.Grid or Pane.Pick or Pane.Versions) _reviewFrom = _pane;
        ShowPane(Pane.Review);
        SetFooter(null, "Details", () => OpenDetails(), "Import", ImportAll);
        var key = _model.SelectionKey();
        var options = _model.Options(_options);
        if (reuseFailure && _planFailed && !_planning && _planKey == key) RenderReviewPane();
        else if (_plan != null && !_listChanged && _planKey == key && Equals(_planOptions, options)) RenderReviewPane();
        else if (_planning && !_listChanged && _planningKey == key && Equals(_planningOptions, options)) RenderReviewPane();
        else _ = ReplanAsync();
        UpdateImportAll();
        RefreshNotice();
    }

    /// <summary>Review as things stand: the plan's rows, the planning line, or the failure line.</summary>
    private void RenderReviewPane()
    {
        if (_pane != Pane.Review) return;
        SetHeader("Review", ReviewSubtitle());
        if (_planning) SetBody(PlanningRow());
        else if (_plan != null) SetBody(ReviewBody(_plan));
        else
        {
            var list = ImportRows.List();
            if (_planFailed) list.Children.Add(ImportRows.Hint(CheckFailed));
            SetBody(list);
        }
    }

    private string ReviewSubtitle()
    {
        if (_planning || _plan == null) return Checking;
        // Distinct sources: a setup lending its settings to another version is still one setup.
        var setups = ImportManifest.DistinctSetups(ImportManifest.From(_plan));
        return $"{ImportLogic.Plural(setups, "setup", "setups")} into {ImportLogic.Plural(_plan.Groups.Count, "version", "versions")}";
    }

    private static Control PlanningRow()
    {
        var list = ImportRows.List();
        list.Children.Add(PlanningLine());
        return list;
    }

    private static Border PlanningLine()
    {
        var spin = new LoadingSpinner(14) { Brush = Palette.TextDim, VerticalAlignment = VerticalAlignment.Center };
        return ImportRows.Static(Ui.Row(10, spin, Ui.Caption(Checking, Palette.TextDim).Align(HorizontalAlignment.Left)));
    }

    // The Versions page plans what is ticked in the background, so Review usually opens with its plan ready.
    private void PrefetchPlan()
    {
        if (_planning || _model.SelectedVersionCount == 0) return;
        var key = _model.SelectionKey();
        var options = _model.Options(_options);
        if (_plan != null && !_listChanged && _planKey == key && Equals(_planOptions, options)) return;
        _ = ReplanAsync();
    }

    private async Task ReplanAsync()
    {
        if (_disposed) return;
        var seq = ++_planSeq;
        var cts = Replace(ref _planCts);
        var key = _model.SelectionKey();
        var options = _model.Options(_options);
        var selection = _model.Selection();
        var grid = _model.Grid();
        // The settings picks of the Version pages belong to Import everything; the grid and the fast path decide their own.
        var choices = _model.Mode == ImportMode.Ticks ? new Dictionary<string, string>(_model.Choices) : new Dictionary<string, string>();
        _planningKey = key;
        _planningOptions = options;
        _plan = null;
        _planning = true;
        _planFailed = false;
        _planProgress.Last = null;
        _tick.Start();
        RenderReviewPane();
        UpdateImportAll();
        RefreshNotice();

        // Same options as the last plan and the same setups found: only the selection changed, so only the groups whose
        // setups changed are planned again. A switch change (Details, What to bring) or a new folder plans afresh.
        var basis = _lastPlan is { Request: { } r } last && r.Options == options && !_listChanged ? last : null;
        _listChanged = false;
        BulkImportPlan? plan;
        try
        {
            // A recovery of an interrupted import (at launcher start) finishes before anything is planned.
            await ImportStartup.Recovery;
            plan = await Task.Run(() => PlanWithChoicesAsync(basis, selection, options, grid, choices, cts.Token), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception)
        {
            plan = null;
        }
        if (_disposed || seq != _planSeq) return; // a newer action won

        _planning = false;
        StopTickIfIdle();
        _planFailed = plan == null;
        _plan = plan;
        _planKey = key;
        _planOptions = options;
        if (plan != null) _lastPlan = plan;
        // The grid's Settings tip names the setup this plan really uses: the setups it can't take drop out.
        if (plan != null && _model.GridModel is { } gridModel && gridModel.SetUnavailable(plan.Skipped) && _pane == Pane.Grid) RenderGrid();
        RenderReviewPane();
        UpdateImportAll();
        RefreshNotice();
        if (_pane == Pane.Details && _detailsFrom == Pane.Review) RenderDetails();
        FinishAutoStart(plan);
    }

    // Import everything / Import selected was pressed before the plan landed: it starts the moment the plan can go. A plan
    // that can't (disk space, nothing to import, a failed check) or a refusal opens Review, which says why.
    private void FinishAutoStart(BulkImportPlan? plan)
    {
        if (!_autoStart) return;
        _autoStart = false;
        if (_pane is not (Pane.Launchers or Pane.Grid or Pane.Pick))
        {
            RefreshCurrent();
            UpdateFooterEnabled();
            return;
        }
        if (plan is { CanApply: true } && !Busy && ImportLogic.CheckStart(IsApplying, null) == ImportLogic.StartKind.Start)
            StartBulk(plan);
        else
            OpenReview(reuseFailure: true);
    }

    // Plans, then re-applies every settings winner the player picked (a fresh plan forgets them).
    private async Task<BulkImportPlan> PlanWithChoicesAsync(BulkImportPlan? basis, BulkSelection selection, ImportOptions options,
        ImportGrid? grid, IReadOnlyDictionary<string, string> choices, CancellationToken ct)
    {
        var plan = basis != null
            ? await _backend.ReplanSelectionAsync(basis, selection, grid, _planProgress, ct)
            : await _backend.PlanBulkAsync(selection, options, grid, _planProgress, ct);
        foreach (var version in plan.Groups.Select(g => g.Version).ToList())
        {
            if (!choices.TryGetValue(version, out var id)) continue;
            var group = plan.Groups.FirstOrDefault(g => g.Version == version);
            if (group == null || group.Winner.BulkId() == id) continue;
            var picked = group.Setups.Select(s => s.Setup).FirstOrDefault(s => s.BulkId() == id);
            if (picked != null) plan = await _backend.ReplanGroupAsync(plan, version, picked, ct);
        }
        return plan;
    }

    // What lands where, what needs a look, what is left out and why: all from the plan.
    private Control ReviewBody(BulkImportPlan plan)
    {
        var page = ImportRows.List();
        var setups = plan.AllSetups.ToList();
        int Ops(PlanItemKind kind) => setups.Sum(p => p.Plan.Ops.Count(o => o.Item == kind));
        // The one list of copies this plan makes: the rows below, the Importing rows and Done all come from it.
        var mappings = ImportManifest.From(plan);

        page.Children.Add(ImportParts.Section("COMING OVER"));
        page.Children.Add(ImportRows.Stat("Minecraft versions", N(plan.Groups.Count)));
        page.Children.Add(ImportRows.Stat("Setups", N(ImportManifest.DistinctSetups(mappings))));
        void Stat(string label, int n)
        {
            if (n > 0) page.Children.Add(ImportRows.Stat(label, N(n)));
        }
        Stat("Mods", setups.Sum(p => p.Plan.Mods.Count(m => m.IsCopied)));
        Stat("Resource packs", Ops(PlanItemKind.ResourcePack));
        Stat("Shader packs", Ops(PlanItemKind.ShaderPack));
        Stat("Worlds", Ops(PlanItemKind.World));
        Stat("Screenshots", Ops(PlanItemKind.Screenshot));
        if (plan.BytesToCopy > 0) page.Children.Add(ImportRows.Stat("To copy", ImportSummary.Megabytes(plan.BytesToCopy)));
        page.Children.Add(ImportRows.Hint("Each version lands in Origin's own folder for it, ready to play from Home. Each setup with mods becomes its own profile. Your other launchers are only read."));

        // The profiles this import makes (or reuses), so the player sees them before pressing Import.
        var profiles = setups.Where(p => p.Plan.ProfileId != null).ToList();
        if (profiles.Count > 0)
        {
            page.Children.Add(ImportParts.Section("PROFILES", profiles.Count));
            foreach (var p in profiles)
                page.Children.Add(ImportRows.Static(ImportRows.Words(p.Plan.ProfileName ?? p.Setup.Name,
                    $"Minecraft {p.Plan.TargetVersion} - {ImportLogic.Plural(p.Plan.Mods.Count(m => m.IsCopied), "mod", "mods")}"
                    + (p.Plan.ProfileReused ? " - already in Origin, reused" : " - new profile"))));
        }

        var attention = new List<Control>();
        foreach (var note in plan.Notes) attention.Add(ImportRows.Static(ImportRows.Words(note, null)));
        // One line per problem, naming every setup it is in.
        var problems = setups
            .SelectMany(p => p.Plan.Lines.Where(l => l.Section == SummarySection.NeedsAttention).Select(l => (Line: l, p.Setup.Name)))
            .GroupBy(x => (x.Line.Name, Reason: ImportLogic.StatusLine(x.Line.Reason)));
        foreach (var problem in problems)
            attention.Add(ImportRows.Static(ImportRows.Words(problem.Key.Name,
                ImportFlowModel.JoinFew(problem.Select(x => x.Name).Distinct().ToList()), null, problem.Key.Reason)));
        if (attention.Count > 0)
        {
            page.Children.Add(ImportParts.Section("NEEDS ATTENTION", attention.Count, gold: true));
            foreach (var row in attention) page.Children.Add(row);
        }

        foreach (var group in plan.Groups.OrderBy(g => g.Version, Newest))
        {
            page.Children.Add(ImportParts.Section($"MINECRAFT {group.Version}", group.Setups.Count));
            var capable = group.Setups.Count(s => s.Role != SetupRole.PacksOnly);
            for (var slot = 0; slot < group.Setups.Count; slot++)
            {
                var planned = group.Setups[slot];
                var mapping = ImportManifest.Of(mappings, group.Version, slot)!;
                var isWinner = mapping.IsSettingsWinner;
                var lines = new List<string>();
                if (planned.Role == SetupRole.PacksOnly) lines.Add(BulkRules.ForgeCaption);
                if (group.NeedsChoice && isWinner) lines.Add(PickSettings);
                if (NotSureNote(planned.Setup) is { } notSure) lines.Add(notSure);
                page.Children.Add(SetupRow(planned.Setup, mapping.Caption, lines.Count == 0 ? null : string.Join("\n", lines),
                    canTick: true, choice: isWinner && capable > 1 ? group.Version : null, noTick: true, title: mapping.Title,
                    key: "r" + mapping.Id));
            }
        }

        var builtIn = setups.Sum(p => p.Plan.Mods.Count(m => m.Outcome == ModOutcome.BuiltIn));
        if (builtIn > 0)
        {
            page.Children.Add(ImportParts.Section("ALREADY IN ORIGIN"));
            page.Children.Add(ImportRows.Hint($"{ImportLogic.Plural(builtIn, "mod is", "mods are")} part of Origin already, so {(builtIn == 1 ? "it isn't" : "they aren't")} copied."));
        }

        var left = new List<Control>();
        // What each version leaves out, one line per choice ("Worlds" off everywhere is one line naming every version).
        var offs = plan.Groups.OrderBy(g => g.Version, Newest)
            .Select(g => (g.Version, Names: ImportFlowModel.LeftOutNames(_model.BringFor(g.Version))))
            .Where(x => x.Names.Length > 0)
            .GroupBy(x => x.Names);
        foreach (var off in offs)
            left.Add(ImportRows.Static(ImportRows.Words(off.Key,
                $"Minecraft {ImportFlowModel.JoinFew(off.Select(x => x.Version).ToList())} - switched off in What to bring")));
        foreach (var skipped in plan.Skipped.Where(_model.Shows))
            left.Add(ImportRows.Static(ImportRows.Words(skipped.Setup.Name,
                ImportLogic.GroupTitle(skipped.Setup.SourceLabel) + (skipped.Setup.McVersion == null ? "" : " - " + skipped.Setup.McVersion),
                null, skipped.Reason)));
        if (left.Count > 0)
        {
            page.Children.Add(ImportParts.Section(SkippedTitle, left.Count, gold: true));
            foreach (var row in left) page.Children.Add(row);
        }
        return page;
    }

    private void UpdateImportAll()
    {
        if (_pane != Pane.Review) return;
        var importable = _plan?.Groups.Sum(g => g.Setups.Count) ?? 0;
        var (label, enabled) = ImportLogic.ImportAllButton(_planning, _planFailed, _plan is { CanApply: true }, importable, Busy);
        SetPrimaryText(label);
        _primary.IsEnabled = enabled;
    }

    private void StopTickIfIdle()
    {
        if (!IsApplying && !_planning && !_singlePlanning) _tick.Stop();
    }

    private void ImportAll()
    {
        if (_pane != Pane.Review || !_primary.IsEnabled) return;
        if (_planFailed)
        {
            _ = ReplanAsync();
            return;
        }
        if (_plan is { CanApply: true } plan) StartBulk(plan);
    }

    // ---------------------------------------------------------------- one setup on its own

    // The Minecraft versions an import can go into, read once and off the UI thread.
    private Task<IReadOnlyList<string>>? _targetsTask;
    private Dropdown? _targetBox;

    private async Task<IReadOnlyList<string>> TargetsAsync()
    {
        var task = _targetsTask ??= ImportHooks.TargetsAsync(_backend, CancellationToken.None);
        try
        {
            return await task;
        }
        catch
        {
            if (ReferenceEquals(_targetsTask, task)) _targetsTask = null; // never cache a failure
            throw;
        }
    }

    // A setup on its own brings what its version brings (the same switches), into the version the player picks.
    private ImportOptions SingleOptions(DetectedInstance setup) => _options with
    {
        CopyWorlds = true, CopyScreenshots = true, BringByVersion = null,
        LeaveOut = ImportCategories.All & ~_model.BringFor(ImportFlowModel.KeyOf(setup)),
    };

    private void OpenSingle(DetectedInstance setup)
    {
        if (IsApplying) return;
        if (_pane is Pane.Version or Pane.Review or Pane.Pick) _singleFrom = _pane;
        _single = setup;
        _singlePlan = null;
        _singleFailure = null;
        _singlePlanning = false;
        _singleSeq++;
        try { _singleCts?.Cancel(); } catch (ObjectDisposedException) { }
        _singleTarget = null;
        _singleTargets = null;

        var box = new Dropdown(Sizes.ControlLg, null, "Pick a version") { Name = "single-target", IsEnabled = false };
        box.Selected += value =>
        {
            if (_single == null || value == _singleTarget) return;
            _singleTarget = value;
            RenderSingle();
            _ = ReplanSingleAsync();
        };
        _targetBox = box;

        _pendingOffset = default; // a fresh setup starts at the top
        ShowPane(Pane.Single);
        SetSingleFooter();
        RenderSingle();
        _ = LoadTargetsAsync(setup);
    }

    private void SetSingleFooter() =>
        SetFooter(null, "Details", () => OpenDetails(), "Import", () => { if (_primary.IsEnabled) StartSingle(); });

    // VERSION (the target picker and its note), WHAT TO BRING, then the plan: COMING OVER and NEEDS ATTENTION.
    private void RenderSingle()
    {
        if (_pane != Pane.Single || _single is not { } setup || _targetBox is not { } box) return;
        SetHeader(setup.Name, ImportLogic.GroupTitle(setup.SourceLabel) + (setup.McVersion == null ? "" : " - " + setup.McVersion));
        var page = ImportRows.List();
        page.Children.Add(ImportParts.Section("VERSION"));
        (box.Parent as Panel)?.Children.Remove(box); // one picker for the setup: it keeps its state across re-renders
        box.Width = 320; // one steady field whatever it shows ("Pick a version", "1.21.1"), never a bare chevron
        box.HorizontalAlignment = HorizontalAlignment.Left;
        box.Margin = new Thickness(0, 2, 0, 0);
        page.Children.Add(box);
        if (TargetNote() is { } note)
        {
            note.Margin = new Thickness(2, 6, 0, 0);
            page.Children.Add(note);
        }
        page.Children.Add(ImportParts.Section("WHAT TO BRING"));
        page.Children.Add(Bar("s|" + ImportFlowModel.KeyOf(setup)));

        if (_singlePlanning)
        {
            page.Children.Add(ImportParts.Section("COMING OVER"));
            page.Children.Add(PlanningLine());
        }
        else if (_singlePlan is { } plan)
        {
            AddSinglePlan(page, plan);
        }
        else if (_singleFailure != null)
        {
            page.Children.Add(ImportRows.Hint($"Couldn't check this setup ({_singleFailure})."));
        }
        SetBody(page);
        UpdateFooterEnabled();
    }

    private TextBlock? TargetNote()
    {
        if (_single == null || _singleTargets == null) return null;
        if (_singleTarget == null)
            return Ui.Wrap(Ui.Caption(_single.McVersion == null ? "Version unknown." : $"{_single.McVersion} isn't supported.", Palette.Warning));
        if (_single.McVersion != null && _singleTarget != _single.McVersion)
            return Ui.Wrap(Ui.Caption($"Mods built for {_single.McVersion} are left out.", Palette.TextMuted));
        return null;
    }

    // Fills the version list once the targets are known, then plans when the setup's own version is one of them.
    private async Task LoadTargetsAsync(DetectedInstance setup)
    {
        IReadOnlyList<string> raw;
        try
        {
            raw = await TargetsAsync();
        }
        catch (Exception)
        {
            raw = Array.Empty<string>();
        }
        if (_disposed || _pane != Pane.Single || !ReferenceEquals(_single, setup) || _targetBox is not { } box) return; // moved on

        var targets = raw.OrderBy(v => v, Newest).ToList();
        var supported = setup.McVersion != null && targets.Contains(setup.McVersion);
        _singleTargets = targets;
        box.Items = targets.Select(v => new DropItem(v, "Minecraft " + v)).ToList();
        box.SetValue(supported ? setup.McVersion : null);
        box.IsEnabled = true;
        _singleTarget = supported ? setup.McVersion : null;
        if (supported) _ = ReplanSingleAsync();
        else RenderSingle();
    }

    private async Task ReplanSingleAsync()
    {
        if (_single == null || _singleTarget == null) return;
        var seq = ++_singleSeq;
        var cts = Replace(ref _singleCts);
        _singlePlan = null;
        _singleFailure = null;
        _singlePlanning = true;
        _tick.Start();
        RenderSingle();

        var source = _single;
        var target = _singleTarget;
        var options = SingleOptions(source);
        ImportPlan? plan = null;
        string? failure = null;
        try
        {
            await ImportStartup.Recovery;
            plan = await Task.Run(() => _backend.PlanAsync(source, target, options, null, cts.Token), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception ex)
        {
            failure = ImportErrors.Plain(ex);
        }
        if (_disposed || seq != _singleSeq) return;

        _singlePlanning = false;
        StopTickIfIdle();
        _singlePlan = plan;
        _singleFailure = plan == null ? failure ?? "unknown" : null;
        if (_pane == Pane.Details && _detailsFrom == Pane.Single) RenderDetails();
        else RenderSingle();
        RefreshNotice();
        UpdateFooterEnabled();
    }

    private static void AddSinglePlan(StackPanel page, ImportPlan plan)
    {
        page.Children.Add(ImportParts.Section("COMING OVER"));
        void Stat(string label, int count)
        {
            if (count > 0) page.Children.Add(ImportRows.Stat(label, N(count)));
        }
        var copied = plan.Mods.Count(m => m.IsCopied);
        var builtIn = plan.Mods.Count(m => m.Outcome == ModOutcome.BuiltIn);
        page.Children.Add(ImportRows.Stat("Mods", N(copied) + (builtIn > 0 ? $" ({builtIn} already in Origin)" : "")));
        Stat("Settings files", plan.Ops.Count(o => o.Item is PlanItemKind.Setting or PlanItemKind.Config));
        Stat("Resource packs", plan.Ops.Count(o => o.Item == PlanItemKind.ResourcePack));
        Stat("Shader packs", plan.Ops.Count(o => o.Item == PlanItemKind.ShaderPack));
        Stat("Worlds", plan.Ops.Count(o => o.Item == PlanItemKind.World));
        Stat("Screenshots", plan.Ops.Count(o => o.Item == PlanItemKind.Screenshot));
        Stat("Distant Horizons / Voxy terrain", plan.Caches.Count(c => c.WillImport));
        if (plan.GameFiles is { MetadataUnavailable: false, ToDownloadBytes: > 0 } files)
            page.Children.Add(ImportRows.Hint($"{ImportSummary.Megabytes(files.ToDownloadBytes)} to download on first Play."));

        var attention = plan.Lines.Where(l => l.Section == SummarySection.NeedsAttention).ToList();
        if (attention.Count > 0)
        {
            page.Children.Add(ImportParts.Section("NEEDS ATTENTION", attention.Count, gold: true));
            foreach (var l in attention) page.Children.Add(ImportRows.Static(ImportRows.Words(l.Name, null, null, ImportLogic.StatusLine(l.Reason))));
        }
    }

    // ---------------------------------------------------------------- details

    private Toggle? _packsToggle, _keepToggle, _terrainToggle;

    private void OpenDetails()
    {
        if (_pane is not (Pane.Review or Pane.Single or Pane.Done)) return;
        _detailsFrom = _pane;
        _pendingOffset = default;
        ShowPane(Pane.Details);
        SetHeader("Details", _detailsFrom switch
        {
            Pane.Done => "What happened to each setup",
            Pane.Single => _single?.Name,
            _ => "Options and what each setup brings",
        });
        SetFooter(null, null, null, null, null);
        RenderDetails();
    }

    private void RenderDetails()
    {
        if (_pane != Pane.Details) return;
        var page = ImportRows.List();
        if (_detailsFrom != Pane.Done)
        {
            // Worlds and screenshots are "What to bring" switches; these are the rest.
            page.Children.Add(ImportParts.Section("OPTIONS"));
            _packsToggle = OptionToggle("Copy unused packs", _options.CopyUnusedPacks, "opt-packs");
            _keepToggle = OptionToggle("Keep Origin's settings", _options.KeepMyOriginSettings, "opt-keep");
            _terrainToggle = OptionToggle("Replace far-view terrain", _options.ReplaceExistingCaches, "opt-terrain");
            page.Children.Add(ImportRows.Option("Copy unused packs", "Also bring packs you weren't using.", _packsToggle));
            page.Children.Add(ImportRows.Option("Keep Origin's settings", "Don't replace settings Origin has.", _keepToggle));
            page.Children.Add(ImportRows.Option("Replace far-view terrain", "Distant Horizons, Voxy. Saved first.", _terrainToggle));
        }
        switch (_detailsFrom)
        {
            case Pane.Review: AddBulkPlanDetails(page); break;
            case Pane.Single: AddSinglePlanDetails(page); break;
            default: AddResultDetails(page); break;
        }
        SetBody(page);
    }

    private Toggle OptionToggle(string label, bool on, string name)
    {
        var t = new Toggle(label) { IsOn = on, Name = name };
        t.Toggled += _ => OptionChanged();
        return t;
    }

    private void OptionChanged()
    {
        if (_packsToggle == null || _keepToggle == null || _terrainToggle == null) return;
        _options = _options with
        {
            CopyUnusedPacks = _packsToggle.IsOn,
            KeepMyOriginSettings = _keepToggle.IsOn,
            ReplaceExistingCaches = _terrainToggle.IsOn,
        };
        // Re-plan whatever is behind this page; the page itself is rebuilt when the new plan lands.
        if (_detailsFrom == Pane.Review) _ = ReplanAsync();
        else if (_detailsFrom == Pane.Single) _ = ReplanSingleAsync();
    }

    private void AddBulkPlanDetails(StackPanel page)
    {
        if (_plan == null)
        {
            page.Children.Add(ImportRows.Hint(Checking));
            return;
        }
        var mappings = ImportManifest.From(_plan);
        foreach (var group in _plan.Groups.OrderBy(g => g.Version, Newest))
        {
            page.Children.Add(ImportParts.Section($"MINECRAFT {group.Version}", group.Setups.Count));
            for (var slot = 0; slot < group.Setups.Count; slot++)
            {
                var planned = group.Setups[slot];
                var mapping = ImportManifest.Of(mappings, group.Version, slot)!;
                // One status line: the Forge caption, else the detector's full "not sure" reason.
                var warning = planned.Role == SetupRole.PacksOnly ? BulkRules.ForgeCaption
                    : BulkRules.IsVersionUncertain(planned.Setup) ? BulkRules.UncertainReason(planned.Setup) : null;
                page.Children.Add(ImportRows.Static(ImportRows.Words(mapping.Title, mapping.Caption, null, warning)));
            }
        }
        var skipped = _plan.Skipped.Where(_model.Shows).ToList();
        if (skipped.Count > 0)
        {
            page.Children.Add(ImportParts.Section(SkippedTitle, skipped.Count, gold: true));
            foreach (var s in skipped)
                page.Children.Add(ImportRows.Static(ImportRows.Words(s.Setup.Name, ImportLogic.Describe(s.Setup, 0), null, s.Reason)));
        }
    }

    // Every item the plan knows about, with what happens and why: nothing is dropped without a line here.
    private void AddSinglePlanDetails(StackPanel page)
    {
        if (_singlePlan is not { } plan)
        {
            page.Children.Add(ImportRows.Hint(_singlePlanning ? Checking : $"Couldn't check this setup ({_singleFailure})."));
            return;
        }
        page.Children.Add(ImportParts.Section("MODS", plan.Mods.Count));
        if (plan.Mods.Count == 0) page.Children.Add(ImportRows.Hint("No mods in this setup."));
        foreach (var m in plan.Mods)
            page.Children.Add(ImportRows.Report(m.DisplayName ?? m.ModId ?? m.FileName, m.Reason, null, ModStatus(m.Outcome), ModStatusBrush(m.Outcome)));

        var rest = plan.Lines.Where(l => l.Kind != PlanItemKind.Mod).ToList();
        var reusedFiles = rest.Where(l => l.Section == SummarySection.ReusedGameFiles).ToList();
        var other = rest.Where(l => l.Section != SummarySection.ReusedGameFiles).ToList();
        var count = other.Count + (reusedFiles.Count > 0 ? 1 : 0);
        if (count == 0) return;
        page.Children.Add(ImportParts.Section("SETTINGS, PACKS AND MORE", count));
        if (reusedFiles.Count > 0)
            page.Children.Add(ImportRows.Report(
                $"{ImportLogic.Plural(reusedFiles.Count, "file", "files")} Minecraft needs (already on {BulkSummary.ThisComputer})",
                $"Copied from another launcher on {BulkSummary.ThisComputer} ({ImportSummary.Megabytes(reusedFiles.Sum(l => l.Bytes))}), checked before use.",
                null, "Reused", Palette.Info));
        foreach (var l in other)
            page.Children.Add(ImportRows.Report(l.Name, l.Reason, null, SectionStatus(l.Section), SectionBrush(l.Section)));
    }

    private void AddResultDetails(StackPanel page)
    {
        if (_bulkResult is { } bulk)
        {
            foreach (var version in bulk.Versions)
            {
                var landed = version.Setups.Select((x, slot) => (Outcome: x, Slot: slot))
                    .Where(x => x.Outcome.Kind is SetupResultKind.Imported or SetupResultKind.ImportedPartly).ToList();
                if (landed.Count == 0) continue;
                page.Children.Add(ImportParts.Section($"MINECRAFT {version.Version}", landed.Count));
                foreach (var x in landed)
                {
                    // Outcome i of a version is copy i of the plan (ImportManifest): the exact source and Origin versions.
                    var mapping = ImportManifest.Of(_runMappings, version.Version, x.Slot);
                    page.Children.Add(ImportRows.Static(ImportRows.Words(mapping?.Title ?? x.Outcome.Setup.Name,
                        mapping?.Caption ?? ImportLogic.RoleCaption(x.Outcome.Role, null), null,
                        x.Outcome.Role == SetupRole.PacksOnly ? BulkRules.ForgeCaption : null)));
                }
            }
            AddLostRows(page, collapsible: false);
            return;
        }

        var view = ImportSummaryView.Parse(_singleResult?.SummaryText);
        foreach (var block in view.Blocks)
        {
            if (block.Lines.Count == 0) continue;
            page.Children.Add(ImportParts.Section(block.Title.ToUpperInvariant(), block.Lines.Count, gold: block.Section == SummarySection.NeedsAttention));
            foreach (var l in block.Lines.Take(200)) page.Children.Add(ImportRows.Static(ImportRows.Words(l, null)));
            if (block.Lines.Count > 200)
                page.Children.Add(ImportRows.Hint($"and {block.Lines.Count - 200} more: open the import log to see them all."));
        }
        if (page.Children.Count == 0) page.Children.Add(ImportRows.Hint("Nothing to show."));
    }

    // "LEFT OUT - n": every setup or item that didn't come over (where it was, then the reason). On Done the label
    // folds the list when it is long.
    private void AddLostRows(StackPanel page, bool collapsible)
    {
        if (_done is not { Lost.Count: > 0 } m) return;
        if (collapsible)
        {
            var label = ImportParts.Section(SkippedTitle, m.Lost.Count, gold: true);
            label.Margin = default;
            var chevron = Ui.Icon(Icons.ChevronDown, 12, Palette.Warning);
            chevron.RenderTransform = _lostCollapsed ? new RotateTransform(-90) : null;
            var head = new Pressable(Look.Plain, Ui.Row(6, label, chevron), (_lostCollapsed ? "Show " : "Hide ") + "what was left out")
            {
                Name = "lost-toggle", Padding = new Thickness(4, 2), Margin = new Thickness(-4, 12, 0, 4),
                HorizontalAlignment = HorizontalAlignment.Left, CornerRadius = new CornerRadius(Radius.Sm),
            };
            head.Click += (_, _) =>
            {
                _lostCollapsed = !_lostCollapsed;
                RenderDone();
            };
            page.Children.Add(head);
            if (_lostCollapsed) return;
        }
        else
        {
            page.Children.Add(ImportParts.Section(SkippedTitle, m.Lost.Count, gold: true));
        }
        foreach (var l in m.Lost) page.Children.Add(ImportRows.Static(ImportRows.Words(l.Title, l.Caption, null, l.Reason)));
    }

    private static string ModStatus(ModOutcome o) => o switch
    {
        ModOutcome.Copied => "Imported",
        ModOutcome.CopiedDisabled => "Imported, off",
        ModOutcome.CopiedAsBefore => "Imported, off as before",
        ModOutcome.BuiltIn => "Built in",
        _ => "Not imported",
    };

    private static IBrush ModStatusBrush(ModOutcome o) => o switch
    {
        ModOutcome.Copied => Palette.Success,
        ModOutcome.CopiedDisabled => Palette.Warning,
        ModOutcome.CopiedAsBefore => Palette.TextDim,
        ModOutcome.BuiltIn => Palette.Info,
        _ => Palette.TextMuted,
    };

    private static string SectionStatus(SummarySection s) => s switch
    {
        SummarySection.Imported => "Will import",
        SummarySection.ReusedGameFiles or SummarySection.ReusedCaches => "Reused",
        SummarySection.NeedsAttention => "Check",
        _ => "Not imported",
    };

    private static IBrush SectionBrush(SummarySection s) => s switch
    {
        SummarySection.Imported => Palette.Success,
        SummarySection.ReusedGameFiles or SummarySection.ReusedCaches => Palette.Info,
        SummarySection.NeedsAttention => Palette.Warning,
        _ => Palette.TextMuted,
    };
}
