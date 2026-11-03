using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// Back, Add a folder, step 4 (Importing, then Done or Nothing changed) and the shot run's hooks.
internal sealed partial class ImportPage
{
    // ---------------------------------------------------------------- back

    private void GoBack()
    {
        _goingBack = true;
        switch (_pane)
        {
            case Pane.Scope:
                ShowLaunchers();
                break;
            case Pane.Grid:
                ShowScope();
                break;
            case Pane.Pick:
                if (_pickFrom == Pane.Grid) ShowGrid(); else ShowLaunchers();
                break;
            case Pane.Versions:
                if (_versionsFrom == Pane.Pick) ShowPick(); else ShowLaunchers();
                break;
            case Pane.Version:
                ShowVersions();
                break;
            case Pane.Review:
                // a plan in flight keeps going: the next Import reuses it if nothing changes
                if (_reviewFrom == Pane.Pick) ShowPick();
                else if (_reviewFrom == Pane.Grid) ShowGrid();
                else if (_reviewFrom == Pane.Scope) ShowScope();
                else if (_reviewFrom == Pane.Launchers) ShowLaunchers();
                else ShowVersions();
                break;
            case Pane.Chooser:
                ReturnTo(_chooserFrom);
                break;
            case Pane.Single:
                _singleSeq++;
                try { _singleCts?.Cancel(); } catch (ObjectDisposedException) { }
                _singlePlanning = false;
                StopTickIfIdle();
                ReturnTo(_singleFrom);
                break;
            case Pane.Details:
                BackFromDetails();
                break;
        }
        _goingBack = false;
    }

    private void ReturnTo(Pane pane)
    {
        if (pane == Pane.Version && _versionKey != null) OpenVersion(_versionKey);
        else if (pane == Pane.Pick) ShowPick();
        else OpenReview();
    }

    private void BackFromDetails()
    {
        switch (_detailsFrom)
        {
            case Pane.Single when _single != null:
                ShowPane(Pane.Single);
                SetSingleFooter();
                RenderSingle();
                break;
            case Pane.Done:
                RenderDone();
                break;
            default:
                OpenReview();
                break;
        }
    }

    // ---------------------------------------------------------------- add a folder

    private async Task BrowseAsync()
    {
        if (TopLevel.GetTopLevel(this)?.StorageProvider is not { CanPickFolder: true } storage) return;
        IReadOnlyList<IStorageFolder> picked;
        try
        {
            picked = await storage.OpenFolderPickerAsync(new FolderPickerOpenOptions { Title = "Pick a Minecraft setup folder", AllowMultiple = false });
        }
        catch (Exception)
        {
            return;
        }
        if (picked.Count == 0 || picked[0].TryGetLocalPath() is not { Length: > 0 } folder) return;
        await AddFolderAsync(folder);
    }

    private async Task AddFolderAsync(string folder)
    {
        var cts = Replace(ref _browseCts); // its own token: the main scan and plan are never cancelled by Browse
        FolderInspection found;
        try
        {
            found = await Task.Run(() => ImportHooks.InspectPickedFolderAsync(_backend, folder, cts.Token), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception)
        {
            found = new FolderInspection { Message = FolderInspection.NotAnInstance };
        }
        if (_disposed || cts.IsCancellationRequested) return;

        if (found.Instances.Count == 0)
        {
            _flash = (found.Message ?? FolderInspection.NotAnInstance).TrimEnd('.') + ".";
            RefreshNotice();
            return;
        }
        var fresh = found.Instances.ToList();
        var added = _model.Add(fresh); // one folder is one setup
        _flash = added == 0 ? "That folder is already in the list." : null;
        if (added > 0)
        {
            _listChanged = true;
            RefreshCurrent();
            try
            {
                var contents = await Task.Run(() => _backend.GetContentsAsync(fresh, cts.Token), cts.Token);
                if (!_disposed && !cts.IsCancellationRequested) Merge(contents);
            }
            catch (Exception)
            {
                // no counts for the new folder
            }
        }
        RefreshNotice();
    }

    // ---------------------------------------------------------------- 4. importing

    private const string ImportingNote = "Your worlds, mods and settings are being copied into Origin. Your other launchers are only read, never changed.";

    private IReadOnlyList<ImportMapping> _runMappings = Array.Empty<ImportMapping>(); // the copies of the run being (or last) applied
    private ImportPlan? _runSingle; // the single import being (or last) applied
    private readonly List<ProgressRow> _progressRows = new();
    private int _activeRow = -1;
    private readonly TextBlock _progressPercent = Ui.Display("0");
    private readonly TextBlock _progressSetup = Ui.Ellipsis(Ui.Body("", Palette.TextDim));
    private readonly TextBlock _progressDetail = Ui.Caption("", Palette.TextMuted);
    private readonly Grid _progressTrack = Track(Sizes.Progress, out _);

    /// <summary>A capsule track whose first column is the filled share (star widths: no size events needed).</summary>
    private static Grid Track(double height, out Border fill)
    {
        fill = new Border { Background = Palette.Accent, CornerRadius = new CornerRadius(height / 2) };
        var grid = new Grid
        {
            Height = height, ColumnDefinitions = new ColumnDefinitions("0*,1*"), ClipToBounds = true,
        };
        var track = new Border { Background = Palette.ProgressTrack, CornerRadius = new CornerRadius(height / 2) };
        Grid.SetColumnSpan(track, 2);
        grid.Children.Add(track);
        grid.Children.Add(fill.At(0));
        return grid;
    }

    private static void SetTrack(Grid track, double fraction)
    {
        var f = Math.Clamp(fraction, 0, 1);
        track.ColumnDefinitions[0].Width = new GridLength(f, GridUnitType.Star);
        track.ColumnDefinitions[1].Width = new GridLength(1 - f, GridUnitType.Star);
    }

    // One row per copy of the plan (ImportManifest, in the plan's order, which is BulkProgress.SetupIndex's).
    private void BeginApply(IEnumerable<ImportMapping> mappings)
    {
        _barFraction = 0;
        _stopping = null;
        _activeRow = -1;
        SetBar(0);
        _progressSetup.Text = "";
        _progressDetail.Text = "";
        _applyProgress.Last = null;
        _singleProgress.Last = null;
        _progressRows.Clear();
        foreach (var m in mappings) _progressRows.Add(new ProgressRow(m));

        var page = ImportRows.List(Sizes.PageMaxWidth);
        var top = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        var pct = Ui.Row(2, _progressPercent, Ui.Heading("%", Palette.TextDim).Margin(0, 0, 0, 3));
        pct.VerticalAlignment = VerticalAlignment.Bottom;
        top.Children.Add(pct.At(0));
        _progressSetup.HorizontalAlignment = HorizontalAlignment.Right;
        _progressSetup.VerticalAlignment = VerticalAlignment.Bottom;
        _progressSetup.Margin = new Thickness(16, 0, 0, 6);
        top.Children.Add(_progressSetup.At(1));
        var card = Ui.Card(Ui.Column(10, top, _progressTrack, _progressDetail), 20);
        card.Margin = new Thickness(0, 0, 0, Space.S4);
        page.Children.Add(card);
        foreach (var row in _progressRows) page.Children.Add(row.Root);

        _pendingOffset = default;
        ShowPane(Pane.Importing);
        SetHeader("Importing", ImportingNote);
        SetFooter(null, null, null, null, null);
        _cancelButton.IsEnabled = true;
        SetBody(page);
        _tick.Start();
    }

    // The callers check IsApplying / Busy first; the method itself starts with the run marker, nothing before it.
    private async Task RunBulkAsync(BulkImportPlan plan)
    {
        using var run = ImportHooks.EnterRun();
        var cts = new CancellationTokenSource();
        _applyCts = cts;
        _lastRunSingle = false;
        _runPlan = plan;
        _runSingle = null;
        _runMappings = ImportManifest.From(plan);
        IsApplying = true;
        BeginApply(_runMappings);

        BulkImportResult result;
        try
        {
            result = await Task.Run(() => _backend.ApplyBulkAsync(plan, _applyProgress, cts.Token));
        }
        catch (OperationCanceledException)
        {
            result = new BulkImportResult { RunId = plan.RunId, State = ImportState.RolledBack, Cancelled = true };
        }
        catch (Exception ex)
        {
            result = new BulkImportResult
            {
                RunId = plan.RunId, State = ImportState.RolledBack,
                Error = $"{ImportErrors.Plain(ex)}. If anything changed, Origin undoes it at the next start.",
            };
        }
        finally
        {
            // Drop the field before disposing so STOP can never cancel a disposed source.
            if (ReferenceEquals(_applyCts, cts)) _applyCts = null;
            cts.Dispose();
            IsApplying = false;
            StopTickIfIdle();
        }
        if (_disposed) return;
        // The Done page lists only what was part of this import.
        result = _model.ForDone(result);
        // Step 4: what landed is read again from disk and each profile made is checked like a launch, off the UI thread,
        // while the page still shows Importing. A check that can't run leaves the report out, never the result.
        _report = null;
        if (result.Succeeded)
        {
            _progressDetail.Text = "Checking what came over...";
            try { _report = await Task.Run(() => _backend.VerifyAsync(plan, result, CancellationToken.None)); }
            catch (Exception) { _report = null; }
            if (_disposed) return;
        }
        _bulkResult = result;
        _singleResult = null;
        ShowResult(ImportDoneModel.FromBulk(result));
    }

    private async Task RunSingleAsync(ImportPlan plan)
    {
        using var run = ImportHooks.EnterRun();
        var cts = new CancellationTokenSource();
        _applyCts = cts;
        _lastRunSingle = true;
        _runPlan = null;
        _runSingle = plan;
        _runMappings = new[] { ImportManifest.From(plan) };
        IsApplying = true;
        BeginApply(_runMappings);

        ImportResult result;
        try
        {
            result = await Task.Run(() => _backend.ApplyAsync(plan, _singleProgress, cts.Token));
        }
        catch (Exception ex)
        {
            result = new ImportResult
            {
                ImportId = plan.ImportId, State = ImportState.RolledBack, Cancelled = ex is OperationCanceledException,
                Error = ex is OperationCanceledException ? null
                    : $"{ImportErrors.Plain(ex)}. If anything changed, Origin undoes it at the next start.",
            };
        }
        finally
        {
            if (ReferenceEquals(_applyCts, cts)) _applyCts = null;
            cts.Dispose();
            IsApplying = false;
            StopTickIfIdle();
        }
        if (_disposed) return;
        _singleResult = result;
        _bulkResult = null;
        _report = null;
        ShowResult(ImportDoneModel.FromSingle(plan, result));
    }

    private void StartBulk(BulkImportPlan plan)
    {
        if (ImportLogic.CheckStart(IsApplying, Busy ? BusyReason : null) != ImportLogic.StartKind.Start) return;
        _ = RunBulkAsync(plan);
    }

    private void StartSingle()
    {
        if (_singlePlan is not { CanApply: true } plan || IsApplying || Busy) return;
        _ = RunSingleAsync(plan);
    }

    /// <summary>Asks "Stop importing?". True = the player chose to stop (the engine is rolling back); false = keep importing.</summary>
    public async Task<bool> AskCancelAsync()
    {
        if (!IsApplying || _window.DialogOpen) return false;
        // A destructive confirm: a double tap of Enter never cancels.
        var cancel = await Confirm.AskAsync(_window, "Stop importing?", "Anything copied so far is undone.", "Stop", "Keep going", danger: true);
        if (cancel && IsApplying)
        {
            _stopping = "Stopping...";
            _subtitle.Text = _stopping;
            _subtitle.IsVisible = true;
            _cancelButton.IsEnabled = false; // one Stop is enough: the engine is putting everything back
            try { _applyCts?.Cancel(); } catch (ObjectDisposedException) { }
        }
        return cancel;
    }

    private void ShowLatestProgress()
    {
        if (_pane == Pane.Importing)
        {
            double? fraction = null;
            if (_applyProgress.Last is { } b)
            {
                fraction = b.BytesTotal > 0
                    ? b.BytesDone / (double)b.BytesTotal
                    : (b.SetupIndex + Inner(b.Inner)) / Math.Max(1, b.SetupCount);
                _progressSetup.Text = b.SetupLabel;
                _progressDetail.Text = $"{Math.Min(b.SetupIndex + 1, b.SetupCount)} of {ImportLogic.Plural(b.SetupCount, "setup", "setups")}";
                UpdateProgressRows(b.SetupIndex, b.SetupCount, b.Inner);
            }
            else if (_singleProgress.Last is { } p)
            {
                fraction = p.Stage == ImportStage.Finishing ? 1 : Inner(p);
                _progressSetup.Text = _single == null ? "" : $"{_single.SourceLabel}: {_single.Name}";
                _progressDetail.Text = "1 of 1 setup";
                UpdateProgressRows(0, 1, p);
            }
            if (fraction is { } f)
            {
                _barFraction = Math.Max(_barFraction, Math.Clamp(f, 0, 1)); // never backwards when a step counts its own files
                SetBar(_barFraction);
            }
        }
        else if (_planning && _planProgress.Last is { Total: > 0 } plan)
        {
            var text = $"Checking {Math.Min(plan.Done + 1, plan.Total)} of {plan.Total}...";
            if (_pane == Pane.Review)
            {
                _subtitle.Text = text;
                _subtitle.IsVisible = true;
            }
            else if (_autoStart && _pane is Pane.Launchers or Pane.Grid or Pane.Pick)
            {
                _statusText.Text = text;
            }
        }
    }

    // The rows follow the engine's report: finished ones tick, the current one shows its step and a small bar, the rest wait.
    private void UpdateProgressRows(int index, int count, ImportProgress inner)
    {
        if (_progressRows.Count != count) return;
        for (var i = 0; i < count; i++)
        {
            if (i < index) _progressRows[i].Set(ProgressRow.Phase.Done, "Done", 1);
            else if (i == index)
                _progressRows[i].Set(ProgressRow.Phase.Active,
                    StageText(inner.Stage) + (inner.Total > 1 && inner.Stage != ImportStage.Finishing ? $" {Math.Min(inner.Done, inner.Total)}/{inner.Total}" : ""),
                    Inner(inner));
            else _progressRows[i].Set(ProgressRow.Phase.Waiting, "Waiting", 0);
        }
        if (index != _activeRow && index >= 0 && index < count)
        {
            _activeRow = index;
            _progressRows[index].Root.BringIntoView(); // keep the current row on screen
        }
    }

    private static double Inner(ImportProgress p) => p.Total > 0 ? Math.Clamp(p.Done / (double)p.Total, 0, 1) : 0;

    private static string StageText(ImportStage s) => s switch
    {
        ImportStage.Mods => "Copying mods",
        ImportStage.Settings => "Copying settings",
        ImportStage.Packs => "Copying packs",
        ImportStage.Worlds => "Copying worlds",
        ImportStage.Screenshots => "Copying screenshots",
        ImportStage.GameFiles => "Reusing files Minecraft needs",
        ImportStage.Caches => "Moving Distant Horizons / Voxy terrain",
        ImportStage.Finishing => "Finishing up",
        _ => "Getting ready",
    };

    private void SetBar(double fraction)
    {
        SetTrack(_progressTrack, fraction);
        _progressPercent.Text = Math.Round(fraction * 100).ToString("0", System.Globalization.CultureInfo.InvariantCulture);
    }

    /// <summary>One setup of the run: its logo and name, then a tick (done), a small bar (now) or nothing (waiting), and its step.</summary>
    private sealed class ProgressRow
    {
        public enum Phase { Waiting, Active, Done }

        public readonly Border Root;
        private readonly Border _done;
        private readonly Grid _track;
        private readonly TextBlock _status;
        private Phase _phase = (Phase)(-1);

        public ProgressRow(ImportMapping mapping)
        {
            var setup = mapping.Setup;
            var name = Ui.Ellipsis(Ui.BodyStrong(mapping.Title ?? setup.Name));
            var words = Ui.Column(1, name, Ui.Caption(ImportLogic.GroupTitle(setup.SourceLabel), Palette.TextDim));
            words.VerticalAlignment = VerticalAlignment.Center;
            _done = new Border
            {
                Width = 22, Height = 22, CornerRadius = new CornerRadius(Radius.Sm), Background = Palette.Success,
                Child = Ui.Icon(Icons.Check, Sizes.IconSm, Palette.OnAccent).Align(HorizontalAlignment.Center),
                HorizontalAlignment = HorizontalAlignment.Right,
            };
            _track = Track(4, out _);
            _track.Width = 160;
            _track.HorizontalAlignment = HorizontalAlignment.Right;
            _track.VerticalAlignment = VerticalAlignment.Center;
            _status = Ui.Text("Waiting", TypeScale.Caption, FontWeight.SemiBold, Palette.TextMuted);
            _status.TextAlignment = TextAlignment.Right;
            _status.HorizontalAlignment = HorizontalAlignment.Right;
            _status.VerticalAlignment = VerticalAlignment.Center;
            _status.Width = 170;
            _status.TextTrimming = TextTrimming.CharacterEllipsis;

            var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto") };
            var logo = ImportParts.Logo(setup.Kind, 32, setup.Name);
            logo.Margin = new Thickness(0, 0, 12, 0);
            grid.Children.Add(logo.At(0));
            grid.Children.Add(words.At(1));
            var mark = new Panel { Margin = new Thickness(16, 0, 16, 0), VerticalAlignment = VerticalAlignment.Center };
            mark.Children.Add(_done);
            mark.Children.Add(_track);
            grid.Children.Add(mark.At(2));
            grid.Children.Add(_status.At(3));
            Root = new Border
            {
                Padding = new Thickness(14, 10), MinHeight = 40, CornerRadius = new CornerRadius(Radius.Md), Child = grid,
            };
            Set(Phase.Waiting, "Waiting", 0);
        }

        public void Set(Phase phase, string status, double fraction)
        {
            _status.Text = status;
            SetTrack(_track, fraction);
            if (phase == _phase) return;
            _phase = phase;
            Root.Background = phase == Phase.Waiting ? Brushes.Transparent : Palette.Card;
            _status.Foreground = phase == Phase.Waiting ? Palette.TextMuted : phase == Phase.Done ? Palette.TextDim : Palette.Text;
            _done.IsVisible = phase == Phase.Done;
            _track.IsVisible = phase == Phase.Active;
        }
    }

    // ---------------------------------------------------------------- 5. done / nothing changed

    private string? _playVersion;
    private const int MaxVersionCards = 7; // then one "+N more" card (two rows of four)
    private const int ReportRows = 8;      // per bucket; the rest are one press away (See what came over)

    private static readonly (VerifyBucket Bucket, string Title, string Empty)[] ReportBuckets =
    {
        (VerifyBucket.Succeeded, "CAME OVER", "Nothing was copied."),
        (VerifyBucket.Skipped, "SKIPPED", "Nothing was skipped."),
        (VerifyBucket.Unsupported, "NOT SUPPORTED", "Everything here is something Origin imports."),
        (VerifyBucket.Failed, "FAILED", "Nothing failed. Everything was found again after importing."),
    };

    private void ShowResult(ImportDoneModel model)
    {
        if (_window.DialogOpen) _window.CloseDialog(); // the import ended while "Stop importing?" was still up
        _done = model;
        if (!model.Succeeded)
        {
            ShowFailed(model);
            return;
        }
        Imported?.Invoke();
        _lostCollapsed = model.Lost.Count > 3;
        _pendingOffset = default;
        RenderDone();
    }

    // "You're all set": what came over (counts from the plan of what was applied), a card per version it went to, the
    // report, a line that the other launchers were not touched. Only real data: a zero is not shown.
    private void RenderDone()
    {
        if (_done is not { } m) return;

        // The copies that landed, with their plans. Outcome i of group g is copy (g, i): the engine reports in order.
        var landed = new List<(ImportMapping Mapping, ImportPlan Plan)>();
        if (_bulkResult is { } bulk && _runPlan is { } run)
        {
            foreach (var version in bulk.Versions)
            {
                var group = run.Groups.FirstOrDefault(g => g.Version == version.Version);
                if (group == null) continue;
                for (var slot = 0; slot < version.Setups.Count && slot < group.Setups.Count; slot++)
                {
                    if (version.Setups[slot].Kind is not (SetupResultKind.Imported or SetupResultKind.ImportedPartly)) continue;
                    if (ImportManifest.Of(_runMappings, group.Version, slot) is { } mapping) landed.Add((mapping, group.Setups[slot].Plan));
                }
            }
        }
        else if (_runSingle is { } single && _singleResult is { Succeeded: true })
        {
            landed.Add((ImportManifest.From(single), single));
        }

        var worlds = landed.Sum(l => l.Plan.Ops.Count(o => o.Item == PlanItemKind.World));
        var mods = landed.Sum(l => l.Plan.Mods.Count(x => x.IsCopied));
        var versions = DoneVersionList(landed);
        _playVersion = versions.FirstOrDefault().Version;

        var page = ImportRows.List(Sizes.PageMaxWidth);
        page.Spacing = 0;

        // The hero: the logo, IMPORT FINISHED, "You're all set", the counts.
        // The counts share the hero's width, one equal box each (the WPF hero's UniformGrid).
        var boxes = new List<Border> { DoneStat(N(versions.Count), versions.Count == 1 ? "VERSION" : "VERSIONS") };
        if (worlds > 0) boxes.Add(DoneStat(N(worlds), worlds == 1 ? "WORLD" : "WORLDS"));
        if (mods > 0) boxes.Add(DoneStat(N(mods), mods == 1 ? "MOD" : "MODS"));
        var stats = new Grid { Margin = new Thickness(0, 18, 0, 0) };
        for (var i = 0; i < boxes.Count; i++)
        {
            stats.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
            if (i < boxes.Count - 1) boxes[i].Margin = new Thickness(0, 0, Space.S1, 0);
            stats.Children.Add(boxes[i].At(i));
        }
        var badge = Ui.Row(8, Ui.Tile(Icons.Check, Palette.TileEmerald, Palette.HueEmerald, 22), Ui.Eyebrow("IMPORT FINISHED", Palette.Success));
        var words = Ui.Column(6, badge, Ui.Heading("You're all set"), stats);
        var hero = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        var logo = Ui.Logo(Sizes.LogoHero);
        logo.Margin = new Thickness(0, 4, 32, 0);
        logo.VerticalAlignment = VerticalAlignment.Top;
        hero.Children.Add(logo.At(0));
        hero.Children.Add(words.At(1));
        var heroCard = new Border
        {
            Background = Palette.Card, CornerRadius = new CornerRadius(Radius.Lg), Padding = new Thickness(32, 28), Child = hero,
        };
        page.Children.Add(heroCard);

        if (versions.Count > 0)
        {
            var title = Ui.Eyebrow("READY TO PLAY", Palette.TextDim);
            title.Margin = new Thickness(0, 24, 0, 10);
            title.HorizontalAlignment = HorizontalAlignment.Left;
            page.Children.Add(title);
            var cards = new UniformGrid { Columns = 4 };
            foreach (var v in versions.Take(MaxVersionCards)) cards.Children.Add(VersionCard("Minecraft " + v.Version, "From " + v.From, v.SettingsFrom));
            if (versions.Count > MaxVersionCards) cards.Children.Add(VersionCard($"+{versions.Count - MaxVersionCards} more", null, null));
            page.Children.Add(cards);
        }

        AddReport(page);

        var notes = new StackPanel { Margin = new Thickness(0, 18, 0, 0) };
        notes.Children.Add(ImportParts.Note("Nothing was changed in your other launchers. They still work exactly as before.", null, shield: true));
        // Without the step-4 report (the single-setup path, or a check that couldn't run), the engine's own lines.
        if (_report == null)
        {
            foreach (var lost in m.Lost.Take(3)) notes.Children.Add(ImportParts.Note(LostSentence(lost), Icons.Globe));
            if (m.Lost.Count > 3)
                notes.Children.Add(ImportParts.Note($"and {m.Lost.Count - 3} more left out: open Details to see them.", Icons.Globe, Palette.TextMuted));
        }
        var link = ImportParts.Link("See what came over", "Every file that came over, and what was left out");
        link.Name = "done-details";
        link.HorizontalAlignment = HorizontalAlignment.Left;
        link.Margin = new Thickness(-12, 6, 0, 0);
        link.Click += (_, _) => OpenDetails();
        notes.Children.Add(link);
        page.Children.Add(notes);

        ShowPane(Pane.Done);
        SetBody(page);

        // A single import already made and selected its profile, so its Play is the version's; a bulk one selects
        // nothing, so Play names the one profile to start, or sends the player to Profiles to pick.
        var done = ImportLogic.DoneAction(_playVersion, _bulkResult != null
            ? ImportLogic.DoneProfiles(_bulkResult, _report) : Array.Empty<ImportLogic.DoneProfile>());
        Action close = () => CloseRequested?.Invoke();
        switch (done.Kind)
        {
            case ImportLogic.DoneKind.Play:
                SetFooter(done.Note, "Done", close, done.Label, () => PlayRequested?.Invoke(new ImportPlayRequest(done.Version!, done.ProfileId)));
                break;
            case ImportLogic.DoneKind.OpenProfiles:
                SetFooter(done.Note, "Done", close, done.Label, () => OpenProfilesRequested?.Invoke());
                break;
            default:
                SetFooter(null, null, null, done.Label, close);
                break;
        }
    }

    private static Border DoneStat(string value, string label)
    {
        var number = Ui.Display(value);
        number.HorizontalAlignment = HorizontalAlignment.Left;
        var caption = Ui.Eyebrow(label, Palette.TextDim);
        caption.HorizontalAlignment = HorizontalAlignment.Left;
        return new Border
        {
            Background = Palette.Bg, CornerRadius = new CornerRadius(Radius.Md), Padding = new Thickness(14, 12), MinWidth = 96,
            Child = Ui.Column(2, number, caption),
        };
    }

    // A version it went to: the logo of the launcher whose settings it got, "Minecraft 1.21.1", "From Lunar 1.8.9".
    private static Border VersionCard(string name, string? caption, SourceKind? kind)
    {
        var words = Ui.Column(2, Ui.Ellipsis(Ui.BodyStrong(name)));
        if (!string.IsNullOrEmpty(caption)) words.Children.Add(Ui.Ellipsis(Ui.Caption(caption, Palette.TextDim)));
        words.VerticalAlignment = VerticalAlignment.Center;
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        if (kind is { } k)
        {
            var logo = ImportParts.Logo(k, 36, name);
            logo.Margin = new Thickness(0, 0, 12, 0);
            grid.Children.Add(logo.At(0));
        }
        grid.Children.Add(words.At(1));
        return new Border
        {
            Background = Palette.Card, CornerRadius = new CornerRadius(Radius.Md), Padding = new Thickness(14, 12), MinHeight = 60,
            Margin = new Thickness(0, 0, Space.S2, Space.S2), Child = grid,
        };
    }

    // Each profile the import made (can it start as it is?), then what came over, what was skipped, what Origin doesn't
    // support and what failed, each line with its reason: all from the verifier's re-read (ImportVerifier).
    private void AddReport(StackPanel page)
    {
        if (_report is not { } report)
        {
            AddLostRows(page, collapsible: true);
            return;
        }
        if (report.Profiles.Count > 0)
        {
            var rows = ReportSection(page, "PROFILES", report.Profiles.Count);
            foreach (var profile in report.Profiles) rows.Children.Add(ProfileRow(profile));
        }
        foreach (var (bucket, title, empty) in ReportBuckets)
        {
            var items = report.In(bucket).ToList();
            var rows = ReportSection(page, title, items.Count);
            if (items.Count == 0) rows.Children.Add(ReportLine(empty, null, null, muted: true));
            foreach (var item in items.Take(ReportRows))
                rows.Children.Add(ReportLine(item.Name, item.Setup, item.Reason, warn: bucket == VerifyBucket.Failed));
            if (items.Count > ReportRows)
                rows.Children.Add(ReportLine($"and {items.Count - ReportRows} more", null, "See what came over lists every one.", muted: true));
        }
    }

    // A section's label ("SKIPPED  3") and its card; returns the card's row stack.
    private static StackPanel ReportSection(StackPanel page, string title, int count)
    {
        var label = Ui.Eyebrow(count > 0 ? $"{title}  {N(count)}" : title, Palette.TextDim);
        label.HorizontalAlignment = HorizontalAlignment.Left;
        label.Margin = new Thickness(0, 20, 0, 8);
        page.Children.Add(label);
        var rows = new StackPanel();
        page.Children.Add(new Border { Background = Palette.Card, CornerRadius = new CornerRadius(Radius.Lg), Padding = new Thickness(16, 8), Child = rows });
        return rows;
    }

    // One report line: what, from which setup (muted), and why (the reason in the warning colour for a failure).
    private static Control ReportLine(string name, string? setup, string? reason, bool warn = false, bool muted = false)
    {
        var line = new StackPanel { Margin = new Thickness(0, 6) };
        var title = Ui.Wrap(Ui.Body("", muted ? Palette.TextMuted : Palette.Text));
        title.Inlines = new Avalonia.Controls.Documents.InlineCollection { new Avalonia.Controls.Documents.Run(name) };
        if (!string.IsNullOrEmpty(setup))
            title.Inlines.Add(new Avalonia.Controls.Documents.Run("  " + setup) { Foreground = Palette.TextMuted });
        line.Children.Add(title);
        if (!string.IsNullOrEmpty(reason))
        {
            var why = Ui.Wrap(Ui.Caption(reason, warn ? Palette.Warning : Palette.TextDim));
            why.Margin = new Thickness(0, 2, 0, 0);
            line.Children.Add(why);
        }
        return line;
    }

    // A profile the import made: its name, version and mods, and whether it can start as it is. One that can't says a
    // repaired copy can be made, with the Fix Compatibility button when that feature is wired (ImportHooks).
    private Control ProfileRow(ProfileCheck profile)
    {
        var row = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), Margin = new Thickness(0, 8) };
        var tile = profile.CanStart
            ? Ui.Tile(Icons.Check, Palette.TileEmerald, Palette.HueEmerald)
            : Ui.Tile(Icons.Alert, Palette.TileGold, Palette.HueGold);
        tile.Margin = new Thickness(0, 0, 12, 0);
        tile.VerticalAlignment = VerticalAlignment.Top;
        row.Children.Add(tile.At(0));

        var text = new StackPanel();
        text.Children.Add(Ui.Ellipsis(Ui.BodyStrong(profile.ProfileName)));
        var caption = $"Minecraft {profile.Version} - {ImportLogic.Plural(profile.Mods, "mod", "mods")}"
            + (profile.Reused ? " - already in Origin, reused" : " - new profile")
            + (profile.CanStart ? " - ready to start" : "");
        text.Children.Add(Ui.Wrap(Ui.Caption(caption, Palette.TextDim)).Margin(0, 2, 0, 0));
        if (profile.FixLine is { } fixLine)
        {
            var why = string.Join(" ", profile.Problems.Take(2));
            text.Children.Add(Ui.Wrap(Ui.Caption(fixLine + (why.Length > 0 ? " " + why : ""), Palette.Warning)).Margin(0, 4, 0, 0));
        }
        row.Children.Add(text.At(1));

        if (!profile.CanStart && ImportHooks.FixCompatibility is { } fix)
        {
            var label = Ui.Text("Fix Compatibility", TypeScale.Caption, FontWeight.SemiBold);
            var button = new Pressable(Look.Secondary, label, "Fix Compatibility: " + profile.ProfileName)
            {
                Name = "fix-" + profile.ProfileId, Height = Sizes.ControlSm, Padding = new Thickness(12, 0),
                CornerRadius = new CornerRadius(Radius.Md), Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center,
            }.Center();
            ToolTip.SetTip(button, "Make a repaired copy of this profile that can start");
            button.Click += async (_, _) =>
            {
                button.IsEnabled = false;
                label.Text = "Making a repaired copy...";
                try
                {
                    await fix(profile.Version, profile.ProfileId);
                    if (_disposed) return;
                    label.Text = "Repaired copy made";
                    Imported?.Invoke();
                }
                catch (Exception)
                {
                    if (_disposed) return;
                    label.Text = "Try Fix Compatibility again";
                    button.IsEnabled = true;
                }
            };
            row.Children.Add(button.At(2));
        }
        return row;
    }

    // Per Origin version that landed: where its copies came from and whose settings it got (the group's winner). The
    // most played first (the playtime of its most played setup), then the newest.
    private List<(string Version, string From, SourceKind SettingsFrom)> DoneVersionList(
        IReadOnlyList<(ImportMapping Mapping, ImportPlan Plan)> landed)
    {
        var list = new List<(string Version, string From, SourceKind SettingsFrom, long Played)>();
        foreach (var byVersion in landed.Where(l => !string.IsNullOrEmpty(l.Mapping.TargetVersion)).GroupBy(l => l.Mapping.TargetVersion))
        {
            var mappings = byVersion.Select(l => l.Mapping).ToList();
            var winner = _runPlan?.Groups.FirstOrDefault(g => g.Version == byVersion.Key)?.Winner ?? mappings[0].Setup;
            list.Add((byVersion.Key, ImportManifest.FromText(mappings), winner.Kind, mappings.Max(m => m.Setup.PlaytimeSeconds ?? 0)));
        }
        return list.OrderByDescending(v => v.Played).ThenBy(v => v.Version, BulkRanking.NewestFirst)
            .Select(v => (v.Version, v.From, v.SettingsFrom)).ToList();
    }

    // "RLCraft redux (Prism - 1.20.6): Origin doesn't support this version yet."
    private static string LostSentence((string Title, string? Caption, string Reason) lost)
    {
        var where = string.IsNullOrEmpty(lost.Caption) ? "" : $" ({lost.Caption})";
        var reason = lost.Reason.TrimEnd();
        if (reason.Length > 0 && !".!?".Contains(reason[^1])) reason += ".";
        return $"{lost.Title}{where}: {reason}";
    }

    private void ShowFailed(ImportDoneModel m)
    {
        _pendingOffset = default;
        ShowPane(Pane.Failed);
        SetHeader("Nothing changed", null);
        // The soft danger tile only when it really failed; a stop the player asked for gets a neutral tile. The card and
        // the title stay neutral either way (Workbench: colour lives in the small tile, never a red frame).
        var stopped = m.Cancelled && string.IsNullOrEmpty(m.Error);
        var tile = stopped
            ? Ui.Tile(Icons.Close, Palette.CardHover, Palette.TextDim, 40)
            : Ui.Tile(Icons.Alert, Palette.DangerSoft, Palette.DangerText, 40);
        tile.Margin = new Thickness(0, 0, 16, 0);
        tile.VerticalAlignment = VerticalAlignment.Top;
        var words = Ui.Column(4, Ui.Title(stopped ? "Import stopped" : "Import failed"),
            Ui.Wrap(Ui.Body(stopped ? "You stopped it." : string.IsNullOrEmpty(m.Error) ? "The import didn't finish." : m.Error, Palette.TextDim)));
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        grid.Children.Add(tile.At(0));
        grid.Children.Add(words.At(1));
        var page = ImportRows.List(Sizes.PageMaxWidth);
        page.Children.Add(Ui.Card(grid, 24));
        SetBody(page);
        SetFooter(null, "Close", () => CloseRequested?.Invoke(), "Try again", TryAgain);
    }

    private void TryAgain()
    {
        _bulkResult = null;
        _singleResult = null;
        if (_lastRunSingle && _single != null)
        {
            OpenSingle(_single);
            return;
        }
        _plan = null; // a fresh plan: the last one was used
        ShowLaunchers();
        if (_model.GoingSetups().Count > 0) ImportNow();
    }

    /// <summary>Enter runs the page's one primary action unless focus is on something that uses Enter itself.</summary>
    private bool EnterPressed()
    {
        if (_window.DialogOpen || !_primary.IsVisible || !_primary.IsEffectivelyEnabled) return false;
        var focused = TopLevel.GetTopLevel(this)?.FocusManager?.GetFocusedElement();
        if (focused is Pressable or TextBox or Dropdown) return false;
        _footerPrimary?.Invoke();
        return true;
    }

    // ---------------------------------------------------------------- the shot run (Shots/ImportShots.cs)

    internal void TourOpenLauncher(SourceKind? kind) => OpenLauncher(kind);
    internal void TourOpenVersion(string key) => OpenVersion(key);
    internal void TourOpenReview() => OpenReview();
    internal void TourShowGrid() => ShowGrid();

    internal void TourGridTurnOn(SourceKind kind, GridColumn column)
    {
        if (_model.GridModel is not { } grid) return;
        for (var i = 0; i < 2 && grid.Cell(kind, column) is { Enabled: true, On: not true }; i++) grid.Toggle(kind, column);
        RenderGrid();
        UpdateFooterEnabled();
    }

    internal void TourGridToggle(SourceKind kind, GridColumn column)
    {
        if (_model.GridModel is not { } grid) return;
        grid.Toggle(kind, column);
        RenderGrid();
        UpdateFooterEnabled();
    }

    internal void TourGridTab(string tab)
    {
        _model.GridModel?.SetTab(tab);
        RenderGrid();
    }

    internal void TourChooseLauncher(SourceKind kind) => ChooseLauncher(kind);
    internal void TourFastImport(SourceKind kind) => FastImport(kind);

    internal void TourSetVersion(string key, bool on)
    {
        SetVersionOn(key, on);
        RefreshCurrent();
    }

    internal void TourToggleAllVersions() => ToggleAllVersions();

    internal void TourBringAll(ImportCategories category, bool on)
    {
        _model.SetBringAll(category, on);
        RefreshCurrent();
    }

    internal void TourImportAll() => ImportAll();
    internal void TourAskCancel() => _ = AskCancelAsync();
    internal void TourOpenChooser(string version) => OpenChooser(version);
    internal void TourOpenDetails() => OpenDetails();

    internal void TourOpenSingle(string name)
    {
        if (_model.Setups.FirstOrDefault(s => s.Name == name) is { } setup) OpenSingle(setup);
    }

    internal void TourBack() => GoBack();
    /// <summary>The progress timer's work, now (the headless render loop runs no DispatcherTimer ticks).</summary>
    internal void TourTick() => ShowLatestProgress();
    internal void TourSecondary() => _footerSecondary?.Invoke();
    internal void TourPrimaryFooter() => _footerPrimary?.Invoke();
    internal void TourAddFolder(string folder) => _ = AddFolderAsync(folder);
    internal void TourShowPick() => ShowPick();
    internal void TourBring() => Bring();
    internal void TourRefusal(string? sentence) => SetRefusal(sentence);

    /// <summary>Scrolls the page to an offset (the shots of a list's bottom pass a big number).</summary>
    internal void TourScroll(double offset)
    {
        _scroll.UpdateLayout();
        _scroll.Offset = new Vector(0, Math.Min(offset, Math.Max(0, _scroll.Extent.Height - _scroll.Viewport.Height)));
    }

    /// <summary>The first version row's tick box and the footer's primary: where the two focus shots put the ring.</summary>
    internal Pressable? TourFirstTick() =>
        _scroll.Content is Control c ? FindFirst<Tick>(c, t => t.Name != "tick-all") : null;

    internal Pressable TourFooterPrimary() => _primary;

    private static T? FindFirst<T>(Control root, Func<T, bool> match) where T : Control
    {
        if (root is T t && match(t)) return t;
        foreach (var child in Avalonia.VisualTree.VisualExtensions.GetVisualChildren(root))
            if (child is Control c && FindFirst(c, match) is { } hit) return hit;
        return null;
    }
}
