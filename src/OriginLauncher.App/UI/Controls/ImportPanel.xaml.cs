using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.UI.Controls;

// The Import drawer (MainWindow hosts it like the Friends drawer).
//
//   scan (auto, when the drawer opens) -> Overview: every setup found, "Import all" on top
//     -> Importing (one bar) -> Done / Nothing changed
//   side pages behind a back arrow: whose settings (Chooser), one setup on its own (Single), Details.
//
// All work is the backend's (scan, plan, apply: each async, cancellable, off the UI thread); this file only
// shows what it returns. Rules it keeps:
//  * nothing starts until the drawer opens (the scan is NOT part of launcher startup);
//  * the newest action wins: closing cancels a scan or plan in flight; a plan that finishes after a newer one
//    was asked for is dropped; Browse has its own token, so it never cancels the main scan;
//  * the plan is the authority: rows show only the version and loader until the bulk plan says what comes over,
//    then the roles, counts and problems fill in (rows change in place, the list never rebuilds under the pointer);
//  * while an import is applying, ImportRunState says so (Home keeps Play disabled), Esc and the window's close
//    ask before cancelling, and a cancel puts everything back (the engine's rollback);
//  * progress is shown from a 100 ms timer reading the latest report, never one UI hop per file.
public partial class ImportPanel : UserControl, IDisposable
{
    public enum Pane { Scanning, Empty, Overview, Chooser, Single, Details, Importing, Done, Failed }

    /// <summary>The drawer wants to go away (Close, Done, Esc, scrim).</summary>
    public event EventHandler? CloseRequested;

    /// <summary>An import finished and was kept: Home and Profiles re-read the settings.</summary>
    public event EventHandler? Imported;

    /// <summary>
    /// The "not sure" evidence slot: a plain sentence for a setup whose Minecraft version may be wrong ("Not sure this is
    /// 1.20.1: the game last ran 1.20.4."), or null. WIRING (detection-accuracy agent): replace <see cref="DefaultNotSure"/>
    /// with a read of the detector's VersionCertain flag and its reason once those exist on DetectedInstance. The CI
    /// render swaps it for made-up evidence (UI/ImportShots.cs).
    /// </summary>
    internal static Func<DetectedInstance, string?> NotSureNote { get; set; } = DefaultNotSure;

    private static string? DefaultNotSure(DetectedInstance i) =>
        i.McVersion != null && i.Confidence == Confidence.Inferred ? $"Not sure this is {i.McVersion}: Origin guessed it." : null;

    private const string ForgeFallback = "Forge mods can't come over. Origin runs Fabric, except on 1.8.9.";

    private readonly IImportBackend _backend;
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private readonly Latest<BulkProgress> _applyProgress = new();
    private readonly Latest<ImportProgress> _singleProgress = new();
    private readonly Latest<BulkPlanProgress> _planProgress = new();
    private readonly List<DetectedInstance> _all = new();
    private readonly HashSet<string> _unticked = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, SetupRowVm> _rows = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, string> _choice = new(); // version -> the BulkId the player picked for its settings

    private CancellationTokenSource? _scanCts;
    private CancellationTokenSource? _planCts;
    private CancellationTokenSource? _browseCts;
    private CancellationTokenSource? _singleCts;
    private CancellationTokenSource? _applyCts;
    private int _planSeq;
    private int _singleSeq;
    private bool _disposed;
    private bool _started;
    private bool _planning;
    private bool _planFailed;
    private bool _singlePlanning;
    private bool _suppressTarget;
    private bool _launchBusy;
    private bool _wantPrimaryFocus;
    private bool _lostCollapsed;
    private double _barFraction;

    private Pane _pane = Pane.Scanning;
    private Button? _primary;
    private Action? _footerPrimary;
    private Action? _footerSecondary;
    private string? _scanNote;
    private string? _flash;
    private List<RowVm> _overviewItems = new();
    private List<ImportOptionVm> _optionRows = new();
    private string? _chooserVersion;
    private ImportOptions _options = new();

    private BulkImportPlan? _plan;
    private DetectedInstance? _single;
    private string? _singleTarget;
    private ImportPlan? _singlePlan;
    private BulkImportResult? _bulkResult;
    private ImportResult? _singleResult;
    private DoneModel? _done;

    /// <summary>True from the moment the import starts writing until the engine has finished or rolled back.</summary>
    public bool IsApplying { get; private set; }

    public Pane Current => _pane;

    public ImportPanel(IImportBackend backend)
    {
        _backend = backend;
        InitializeComponent();
        _tick.Tick += (_, _) => ShowLatestProgress();
        Unloaded += (_, _) => StopSpinners();
        ShowScanning();
    }

    // ---------------------------------------------------------------- hooks other code calls

    /// <summary>MainWindow, when the drawer opens: starts the scan. Never called at launcher startup.</summary>
    public void Start()
    {
        if (_started || _disposed) return;
        _started = true;
        _ = ScanAsync();
    }

    /// <summary>
    /// A game is starting: Import turns off until it has opened (a launch would race the import).
    /// WIRING (import-wiring agent): MainWindow calls this from LaunchRunState.Changed once that exists.
    /// </summary>
    public void SetLaunchBusy(bool busy)
    {
        if (_launchBusy == busy) return;
        _launchBusy = busy;
        RefreshNotice();
        UpdateImportAll();
        UpdateFooterEnabled();
    }

    // ---------------------------------------------------------------- scan

    private async Task ScanAsync()
    {
        ShowScanning();
        var cts = Replace(ref _scanCts);
        ScanResult scan;
        try
        {
            scan = await Task.Run(() => _backend.ScanAsync(null, cts.Token), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception)
        {
            // a scan problem is "nothing found", never a crash of the drawer
            scan = new ScanResult();
            scan.Notes.Add("Couldn't look through your other launchers.");
        }
        if (_disposed || cts.IsCancellationRequested) return;

        _all.Clear();
        _all.AddRange(scan.Instances);
        _scanNote = scan.Notes.Count > 0 ? scan.Notes[0] : null;
        ShowOverviewOrEmpty();
    }

    private void ShowOverviewOrEmpty()
    {
        if (_all.Count == 0)
        {
            ShowEmpty();
            return;
        }
        BuildOverview();
        ShowOverview();
        _ = ReplanAsync();
    }

    // ---------------------------------------------------------------- panes

    private static Visibility V(bool on) => on ? Visibility.Visible : Visibility.Collapsed;

    private void ShowPane(Pane pane)
    {
        _pane = pane;
        ScanPane.Visibility = V(pane == Pane.Scanning);
        EmptyPane.Visibility = V(pane == Pane.Empty);
        OverviewPane.Visibility = V(pane == Pane.Overview);
        PageGrid.Visibility = V(pane is Pane.Chooser or Pane.Single or Pane.Details or Pane.Done);
        SingleTop.Visibility = V(pane == Pane.Single);
        ProgressPane.Visibility = V(pane == Pane.Importing);
        FailedPane.Visibility = V(pane == Pane.Failed);
        BackButton.Visibility = V(pane is Pane.Chooser or Pane.Single or Pane.Details);
        BrowseIconButton.Visibility = V(pane == Pane.Overview);
        if (pane == Pane.Scanning) StartSpin(ScanSpin); else StopSpin(ScanSpin);
        if (pane is not Pane.Single) PlanningRow.Visibility = Visibility.Collapsed;
        if (pane is not (Pane.Overview or Pane.Empty)) _flash = null;
        RefreshNotice();
    }

    private void SetHeader(string title, string? subtitle)
    {
        HeaderTitle.Text = title;
        HeaderSubtitle.Text = string.IsNullOrEmpty(subtitle) ? " " : subtitle;
    }

    private void ShowScanning()
    {
        ShowPane(Pane.Scanning);
        SetHeader("Import", "Looking for your setups");
        SetFooter(null, null, null, null, null);
        _primary = null;
    }

    private void ShowEmpty()
    {
        ShowPane(Pane.Empty);
        SetHeader("Import", null);
        EmptyNote.Text = _scanNote ?? "";
        EmptyNote.Visibility = V(!string.IsNullOrEmpty(_scanNote));
        SetFooter(null, null, null, null, null);
        _primary = EmptyBrowseButton;
        WantFocus();
    }

    private void ShowOverview()
    {
        ShowPane(Pane.Overview);
        SetHeader("Import", OverviewSubtitle());
        SetFooter("Your other launchers stay as they are.", "DETAILS", () => OpenDetails(), null, null);
        _primary = ImportAllButton;
        UpdateImportAll();
        WantFocus();
    }

    private string OverviewSubtitle() => $"{_all.Count} {(_all.Count == 1 ? "setup" : "setups")} found";

    private void SetFooter(string? note, string? secondaryText, Action? secondary, string? primaryText, Action? primary)
    {
        FooterNote.Text = note ?? "";
        FooterNote.Visibility = V(!string.IsNullOrEmpty(note));
        FooterSecondary.Content = secondaryText;
        FooterSecondary.Visibility = V(secondaryText != null);
        FooterPrimary.Content = primaryText;
        FooterPrimary.Visibility = V(primaryText != null);
        _footerSecondary = secondary;
        _footerPrimary = primary;
        Footer.Visibility = V(note != null || secondaryText != null || primaryText != null);
        if (primaryText != null) _primary = FooterPrimary;
        UpdateFooterEnabled();
    }

    private void UpdateFooterEnabled()
    {
        if (_pane == Pane.Single)
        {
            FooterPrimary.IsEnabled = !_launchBusy && _singlePlan is { CanApply: true } p && !string.IsNullOrEmpty(p.TargetVersion);
            FooterSecondary.IsEnabled = _singlePlan != null;
        }
        else
        {
            FooterPrimary.IsEnabled = !(_launchBusy && _pane == Pane.Failed);
            FooterSecondary.IsEnabled = true;
        }
        TryFocusPrimary();
    }

    private void FooterPrimary_Click(object sender, RoutedEventArgs e) => _footerPrimary?.Invoke();

    private void FooterSecondary_Click(object sender, RoutedEventArgs e) => _footerSecondary?.Invoke();

    // The drawer's first actionable control gets the focus when a page shows (and once it is enabled).
    private void WantFocus()
    {
        _wantPrimaryFocus = true;
        Dispatcher.BeginInvoke(DispatcherPriority.Input, TryFocusPrimary);
    }

    private void TryFocusPrimary()
    {
        if (!_wantPrimaryFocus || _disposed || Confirm.Visibility == Visibility.Visible) return;
        if (_primary is { IsVisible: true, IsEnabled: true } button)
        {
            _wantPrimaryFocus = false;
            button.Focus();
        }
    }

    private void RefreshNotice()
    {
        string? text = null;
        if (_launchBusy && _pane is Pane.Overview or Pane.Single or Pane.Failed or Pane.Empty)
            text = "Minecraft is starting. Import is off until it opens.";
        else if (_pane == Pane.Overview && _planFailed)
            text = "Couldn't check your setups.";
        else if (_pane == Pane.Overview && _plan is { Blocked.Count: > 0 } plan)
            text = string.Join(" ", plan.Blocked);
        else if (_pane == Pane.Single && _singlePlan is { Blocked.Count: > 0 } single)
            text = string.Join(" ", single.Blocked);
        else if (_pane is Pane.Overview or Pane.Empty && _flash != null)
            text = _flash;
        NoticeText.Text = text ?? "";
        NoticeText.Foreground = (Brush)FindResource(_planFailed && _pane == Pane.Overview ? "Brush.Danger" : "Brush.Warning");
        NoticeIcon.Stroke = NoticeText.Foreground;
        NoticeStrip.Visibility = V(!string.IsNullOrEmpty(text));
    }

    // ---------------------------------------------------------------- overview

    private static int GroupOrder(string label) => label switch
    {
        "Minecraft" => 0, "Prism" => 1, "Modrinth" => 2, "CurseForge" => 3, _ => 4,
    };

    private static string GroupTitle(string label) => label switch
    {
        "Minecraft" => "Minecraft launcher",
        "Prism" => "Prism Launcher",
        "Modrinth" => "Modrinth App",
        "Folder" => "Picked folders",
        _ => label,
    };

    private static void AssignPos(IReadOnlyList<CardRowVm> rows)
    {
        for (var i = 0; i < rows.Count; i++)
            rows[i].Pos = rows.Count == 1 ? CardPos.Only : i == 0 ? CardPos.First : i == rows.Count - 1 ? CardPos.Last : CardPos.Middle;
    }

    private void BuildOverview()
    {
        _rows.Clear();
        var items = new List<RowVm>();
        foreach (var group in _all.GroupBy(i => i.SourceLabel).OrderBy(g => GroupOrder(g.Key)))
        {
            var members = group
                .OrderByDescending(i => i.LastPlayed ?? DateTimeOffset.MinValue)
                .ThenByDescending(i => i.PlaytimeSeconds ?? 0)
                .ThenBy(i => i.Name, StringComparer.OrdinalIgnoreCase)
                .ToList();
            var title = GroupTitle(group.Key);
            items.Add(new ImportSectionVm
            {
                Id = group.Key, Title = title.ToUpperInvariant(), Count = members.Count,
                ActionText = "IMPORT", ActionTip = $"Import everything from {title}", Launcher = members[0].Kind,
            });
            var cards = new List<CardRowVm>();
            foreach (var setup in members)
            {
                var row = NewRow(setup);
                _rows[row.Id] = row;
                cards.Add(row);
            }
            AssignPos(cards);
            items.AddRange(cards);
        }
        _overviewItems = items;
        OverviewList.ItemsSource = items;
    }

    private SetupRowVm NewRow(DetectedInstance setup)
    {
        var row = new SetupRowVm { Setup = setup, Caption = Describe(setup, 0) };
        row.Ticked = !_unticked.Contains(row.Id);
        if (setup.McVersion == null)
        {
            row.Warning = setup.Problem ?? DetectedInstance.CouldntRead;
            row.CanTick = false;
            row.Dimmed = true;
        }
        else
        {
            row.Warning = NotSureNote(setup);
        }
        return row;
    }

    /// <summary>"1.21.1 - Fabric - 12 mods" (ASCII only; the mod count only once the plan knows it).</summary>
    internal static string Describe(DetectedInstance setup, int mods)
    {
        var parts = new List<string>();
        if (setup.McVersion != null) parts.Add(setup.McVersion);
        if (setup.Loader is not (LoaderKind.None or LoaderKind.Unknown)) parts.Add(setup.Loader.ToString());
        if (mods > 0) parts.Add($"{mods} {(mods == 1 ? "mod" : "mods")}");
        return string.Join(" - ", parts);
    }

    private BulkSelection CurrentSelection()
    {
        var selection = BulkSelection.All();
        foreach (var id in _unticked) selection = selection.Without(id);
        return selection;
    }

    private async Task ReplanAsync()
    {
        if (_disposed) return;
        var seq = ++_planSeq;
        var cts = Replace(ref _planCts);
        _plan = null;
        _planning = true;
        _planFailed = false;
        _planProgress.Last = null;
        _tick.Start();
        UpdateImportAll();
        RefreshNotice();

        var selection = CurrentSelection();
        var options = _options;
        var choices = new Dictionary<string, string>(_choice);
        BulkImportPlan? plan = null;
        try
        {
            // A recovery of an interrupted import (at launcher start) finishes before anything is planned.
            await ImportStartup.Recovery.ConfigureAwait(true);
            plan = await Task.Run(() => PlanWithChoicesAsync(selection, options, choices, cts.Token), cts.Token);
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
        if (plan != null) ApplyPlan(plan);
        if (_pane == Pane.Overview) SetHeader("Import", OverviewSubtitle());
        UpdateImportAll();
        RefreshNotice();
        if (_pane == Pane.Details && _detailsFrom == Pane.Overview) BuildDetails();
    }

    // Plans, then re-applies every settings winner the player picked (a fresh plan forgets them).
    private async Task<BulkImportPlan> PlanWithChoicesAsync(BulkSelection selection, ImportOptions options,
        IReadOnlyDictionary<string, string> choices, CancellationToken ct)
    {
        var plan = await _backend.PlanBulkAsync(selection, options, _planProgress, ct);
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

    // The plan decides what each row says. Rows change in place; a setup the plan did not cover (unticked) keeps its last words.
    private void ApplyPlan(BulkImportPlan plan)
    {
        foreach (var group in plan.Groups)
        {
            foreach (var planned in group.Setups)
            {
                if (!_rows.TryGetValue(planned.Setup.BulkId(), out var row)) continue;
                var lines = new List<string>();
                if (planned.Role == SetupRole.PacksOnly)
                    lines.Add(string.IsNullOrWhiteSpace(planned.Note) ? ForgeFallback : planned.Note!);
                var choose = group.NeedsChoice && group.Winner.BulkId() == row.Id;
                if (choose) lines.Add("Pick whose settings to use");
                if (NotSureNote(planned.Setup) is { } notSure) lines.Add(notSure);
                row.Caption = Describe(planned.Setup, planned.Plan.Mods.Count(m => m.IsCopied));
                row.Warning = lines.Count == 0 ? null : string.Join("\n", lines);
                row.ChoiceVersion = choose ? group.Version : null;
                row.CanTick = true;
                row.Dimmed = false;
            }
        }
        foreach (var skipped in plan.Skipped)
        {
            if (!_rows.TryGetValue(skipped.Setup.BulkId(), out var row)) continue;
            row.Caption = Describe(skipped.Setup, 0);
            row.Warning = skipped.Reason;
            row.ChoiceVersion = null;
            row.CanTick = false;
            row.Dimmed = true;
        }
    }

    private void UpdateImportAll()
    {
        if (_pane != Pane.Overview) return;
        string label;
        var enabled = false;
        if (_planning)
        {
            label = "CHECKING...";
        }
        else if (_planFailed)
        {
            label = "CHECK AGAIN";
            enabled = !_launchBusy;
        }
        else
        {
            var importable = _plan?.Groups.Sum(g => g.Setups.Count) ?? 0;
            var everything = _rows.Values.Where(r => r.CanTick).All(r => r.Ticked);
            label = importable == 0 ? "IMPORT" : everything ? "IMPORT ALL" : $"IMPORT {importable} {(importable == 1 ? "SETUP" : "SETUPS")}";
            enabled = _plan is { CanApply: true } && !_launchBusy;
        }
        ImportAllButton.Content = label;
        ImportAllButton.IsEnabled = enabled;
        TryFocusPrimary();
    }

    private void StopTickIfIdle()
    {
        if (!IsApplying && !_planning && !_singlePlanning) _tick.Stop();
    }

    private void ImportAll_Click(object sender, RoutedEventArgs e)
    {
        if (!ImportAllButton.IsEnabled) return;
        if (_planFailed)
        {
            _ = ReplanAsync();
            return;
        }
        if (_plan is { CanApply: true } plan) _ = RunBulkAsync(plan);
    }

    private void SetupRow_Click(object sender, RoutedEventArgs e)
    {
        // A press on one of the row's own controls bubbles here too: only the row itself opens the review.
        if (!ReferenceEquals(e.Source, sender)) return;
        if (Row<SetupRowVm>(sender) is { } row) OpenSingle(row.Setup);
    }

    private void SetupTick_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<SetupRowVm>(sender) is not { } row) return;
        if (row.Ticked) _unticked.Remove(row.Id); else _unticked.Add(row.Id);
        _ = ReplanAsync();
    }

    private void SetupChange_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<SetupRowVm>(sender) is { ChoiceVersion: { } version }) OpenChooser(version);
    }

    private void Section_Click(object sender, RoutedEventArgs e)
    {
        if (Row<ImportSectionVm>(sender) is { Collapsible: true } && _pane == Pane.Done)
        {
            _lostCollapsed = !_lostCollapsed;
            RenderDone();
        }
    }

    private void SectionAction_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<ImportSectionVm>(sender) is { Launcher: { } kind } && !IsApplying && !_launchBusy) _ = RunLauncherAsync(kind);
    }

    private static T? Row<T>(object sender) where T : class => (sender as FrameworkElement)?.DataContext as T;

    // ---------------------------------------------------------------- one launcher

    // "IMPORT" on a launcher's heading: plan just that launcher's ticked setups, then import them.
    private async Task RunLauncherAsync(SourceKind kind)
    {
        var selection = BulkSelection.OfLauncher(kind);
        foreach (var id in _unticked) selection = selection.Without(id);
        var options = _options;
        var choices = new Dictionary<string, string>(_choice);

        var seq = ++_planSeq;
        var cts = Replace(ref _planCts);
        _planning = true;
        ShowPane(Pane.Importing);
        SetHeader("Import", "Getting ready");
        SetFooter(null, null, null, null, null);
        _primary = null;
        CancelButton.IsEnabled = false;
        ProgressCaption.Text = "Checking what can come over...";
        ProgressDetail.Text = "";
        _barFraction = 0;
        SetBar(0);

        BulkImportPlan? plan = null;
        try
        {
            await ImportStartup.Recovery.ConfigureAwait(true);
            plan = await Task.Run(() => PlanWithChoicesAsync(selection, options, choices, cts.Token), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception)
        {
            plan = null;
        }
        if (_disposed || seq != _planSeq) return;
        _planning = false;
        CancelButton.IsEnabled = true;

        if (plan is not { CanApply: true })
        {
            // Back to the overview with the reason on the notice line.
            _flash = plan == null ? "Couldn't check your setups." : plan.Blocked.Count > 0 ? string.Join(" ", plan.Blocked) : "Nothing from that launcher can come over.";
            ShowOverview();
            RefreshNotice();
            _ = ReplanAsync();
            return;
        }
        await RunBulkAsync(plan);
    }

    // ---------------------------------------------------------------- whose settings

    private void OpenChooser(string version)
    {
        var group = _plan?.Groups.FirstOrDefault(g => g.Version == version);
        if (group == null) return;
        _chooserVersion = version;

        var capable = group.Setups.Where(s => s.Role != SetupRole.PacksOnly).Select(s => s.Setup).ToList();
        var ordered = group.Pick.Options.Where(o => capable.Any(c => c.BulkId() == o.BulkId()))
            .Concat(capable.Where(c => group.Pick.Options.All(o => o.BulkId() != c.BulkId())))
            .Select(o => capable.First(c => c.BulkId() == o.BulkId()))
            .ToList();
        var cards = ordered.Select(setup => new ChooseRowVm
        {
            Setup = setup, Caption = Evidence(setup),
            Suggested = group.Pick.Preselected != null && group.Pick.Preselected.BulkId() == setup.BulkId(),
            Selected = group.Winner.BulkId() == setup.BulkId(),
        }).ToList<CardRowVm>();
        AssignPos(cards);
        PageList.ItemsSource = cards;

        ShowPane(Pane.Chooser);
        SetHeader($"{version} settings", "Pick whose settings to use");
        SetFooter(null, null, null, null, null);
        _primary = null;
    }

    private void ChooseRow_Click(object sender, RoutedEventArgs e)
    {
        if (Row<ChooseRowVm>(sender) is { } row && _chooserVersion is { } version) _ = ChooseWinnerAsync(version, row.Setup);
    }

    private async Task ChooseWinnerAsync(string version, DetectedInstance winner)
    {
        if (_plan is not { } current) return;
        _choice[version] = winner.BulkId();
        var seq = ++_planSeq;
        var cts = Replace(ref _planCts);
        _plan = null;
        _planning = true;
        _planFailed = false;
        ShowOverview();
        SetHeader("Import", OverviewSubtitle());
        UpdateImportAll();

        BulkImportPlan? plan = null;
        try
        {
            plan = await Task.Run(() => _backend.ReplanGroupAsync(current, version, winner, cts.Token), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception)
        {
            plan = null;
        }
        if (_disposed || seq != _planSeq) return;
        _planning = false;
        _planFailed = plan == null;
        _plan = plan;
        if (plan != null) ApplyPlan(plan);
        UpdateImportAll();
        RefreshNotice();
    }

    /// <summary>"38 h played - 9 days ago": the evidence for picking one setup's settings.</summary>
    private static string Evidence(DetectedInstance setup)
    {
        var parts = new List<string>();
        if (setup.PlaytimeSeconds is > 0) parts.Add(Playtime(setup.PlaytimeSeconds.Value) + " played");
        if (setup.LastPlayed is { } last && last > DateTimeOffset.MinValue.AddYears(2)) parts.Add(Ago(last));
        if (parts.Count == 0) parts.Add(Describe(setup, 0));
        return string.Join(" - ", parts);
    }

    private static string Playtime(long seconds)
    {
        if (seconds < 3600) return Math.Max(1, seconds / 60) + " min";
        var hours = seconds / 3600.0;
        return (hours < 10 ? hours.ToString("0.#", CultureInfo.InvariantCulture) : Math.Round(hours).ToString("0", CultureInfo.InvariantCulture)) + " h";
    }

    private static string Ago(DateTimeOffset when)
    {
        var span = DateTimeOffset.UtcNow - when;
        if (span.TotalMinutes < 2) return "just now";
        if (span.TotalHours < 1) return $"{(int)span.TotalMinutes} min ago";
        if (span.TotalDays < 1) return $"{(int)span.TotalHours} h ago";
        if (span.TotalDays < 2) return "yesterday";
        if (span.TotalDays < 60) return $"{(int)span.TotalDays} days ago";
        return $"{(int)(span.TotalDays / 30)} months ago";
    }

    // ---------------------------------------------------------------- one setup on its own

    private void OpenSingle(DetectedInstance setup)
    {
        if (IsApplying) return;
        _single = setup;
        _singlePlan = null;
        _singleSeq++;
        try { _singleCts?.Cancel(); } catch (ObjectDisposedException) { }
        PageList.ItemsSource = new List<RowVm>();

        var targets = _backend.SupportedTargets.OrderByDescending(v => v, Comparer<string>.Create(McVersion.Compare)).ToList();
        var supported = setup.McVersion != null && targets.Contains(setup.McVersion);
        _suppressTarget = true;
        TargetBox.ItemsSource = targets;
        TargetBox.SelectedItem = supported ? setup.McVersion : null;
        _suppressTarget = false;
        _singleTarget = supported ? setup.McVersion : null;

        ShowPane(Pane.Single);
        SetHeader(setup.Name, GroupTitle(setup.SourceLabel) + (setup.McVersion == null ? "" : " - " + setup.McVersion));
        SetFooter(null, "DETAILS", () => OpenDetails(), "IMPORT", () => { if (FooterPrimary.IsEnabled) _ = RunSingleAsync(); });
        _primary = FooterPrimary;
        UpdateTargetNote();
        WantFocus();
        if (supported) _ = ReplanSingleAsync();
        else UpdateFooterEnabled();
    }

    private void UpdateTargetNote()
    {
        string? text = null;
        var brush = "Brush.Warning";
        if (_single == null) return;
        if (_singleTarget == null)
        {
            text = _single.McVersion == null
                ? "Origin couldn't tell the Minecraft version. Pick one."
                : $"Origin doesn't support Minecraft {_single.McVersion}. Pick another version.";
        }
        else if (_single.McVersion != null && _singleTarget != _single.McVersion)
        {
            text = $"Mods built for {_single.McVersion} are left out.";
            brush = "Brush.TextMuted";
        }
        TargetNote.Text = text ?? "";
        TargetNote.Foreground = (Brush)FindResource(brush);
        TargetNote.Visibility = V(text != null);
    }

    private void TargetBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressTarget || TargetBox.SelectedItem is not string picked || _single == null) return;
        _singleTarget = picked;
        UpdateTargetNote();
        _ = ReplanSingleAsync();
    }

    private async Task ReplanSingleAsync()
    {
        if (_single == null || _singleTarget == null) return;
        var seq = ++_singleSeq;
        var cts = Replace(ref _singleCts);
        _singlePlan = null;
        _singlePlanning = true;
        PageList.ItemsSource = new List<RowVm>();
        PlanningRow.Visibility = Visibility.Visible;
        StartSpin(PlanSpin);
        _tick.Start();
        UpdateFooterEnabled();

        var source = _single;
        var target = _singleTarget;
        var options = _options;
        ImportPlan? plan = null;
        string? failure = null;
        try
        {
            await ImportStartup.Recovery.ConfigureAwait(true);
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
        StopSpin(PlanSpin);
        PlanningRow.Visibility = Visibility.Collapsed;
        _singlePlan = plan;
        if (plan == null)
        {
            PageList.ItemsSource = new List<RowVm> { new HintVm { Id = "fail", Text = $"Couldn't check this setup ({failure}).", Pos = CardPos.Only } };
            UpdateFooterEnabled();
            return;
        }
        if (_pane == Pane.Single) RenderSinglePlan(plan);
        else if (_pane == Pane.Details && _detailsFrom == Pane.Single) BuildDetails();
        RefreshNotice();
        UpdateFooterEnabled();
    }

    private void RenderSinglePlan(ImportPlan plan)
    {
        var items = new List<RowVm>();
        var stats = new List<CardRowVm>();
        void Stat(string id, string label, int count, bool always = false)
        {
            if (count > 0 || always)
                stats.Add(new ImportStatVm { Id = id, Label = label, Value = count.ToString(CultureInfo.InvariantCulture) });
        }
        var copied = plan.Mods.Count(m => m.IsCopied);
        Stat("mods", "Mods", copied, always: true);
        Stat("builtin", "Already in Origin", plan.Mods.Count(m => m.Outcome == ModOutcome.BuiltIn));
        Stat("settings", "Settings files", plan.Ops.Count(o => o.Item is PlanItemKind.Setting or PlanItemKind.Config));
        Stat("resource", "Resource packs", plan.Ops.Count(o => o.Item == PlanItemKind.ResourcePack));
        Stat("shader", "Shader packs", plan.Ops.Count(o => o.Item == PlanItemKind.ShaderPack));
        if (plan.Options.CopyWorlds || plan.Ops.Any(o => o.Item == PlanItemKind.World))
            Stat("worlds", "Worlds", plan.Ops.Count(o => o.Item == PlanItemKind.World), always: true);
        Stat("files", "Files Minecraft needs (already on this PC)", plan.GameFiles?.ReusedCount ?? 0);
        Stat("terrain", "Distant Horizons / Voxy terrain", plan.Caches.Count(c => c.WillImport));
        AssignPos(stats);
        items.AddRange(stats);

        if (plan.GameFiles is { MetadataUnavailable: false, ToDownloadBytes: > 0 } files)
            items.Add(new HintVm { Id = "download", Pos = CardPos.Only, Text = $"About {ImportSummary.Megabytes(files.ToDownloadBytes)} downloads the first time you press Play." });

        var attention = plan.Lines.Where(l => l.Section == SummarySection.NeedsAttention).ToList();
        if (attention.Count > 0)
        {
            items.Add(new ImportSectionVm { Id = "attention", Title = "NEEDS ATTENTION", Count = attention.Count, Gold = true });
            var rows = attention.Select((l, i) => new ImportLineVm { Id = "a" + i, Title = l.Name, Warning = l.Reason }).ToList<CardRowVm>();
            AssignPos(rows);
            items.AddRange(rows);
        }
        PageList.ItemsSource = items;

        SetFooter(plan.ProfileName != null && plan.CanApply ? $"Mods go in a profile called \"{plan.ProfileName}\"." : null,
            "DETAILS", () => OpenDetails(), "IMPORT", () => { if (FooterPrimary.IsEnabled) _ = RunSingleAsync(); });
        _primary = FooterPrimary;
    }

    // ---------------------------------------------------------------- details

    private Pane _detailsFrom = Pane.Overview;

    private void OpenDetails()
    {
        if (_pane is not (Pane.Overview or Pane.Single or Pane.Done)) return;
        _detailsFrom = _pane;
        BuildDetails();
        ShowPane(Pane.Details);
        SetHeader("Details", _detailsFrom switch
        {
            Pane.Done => "What happened to each setup",
            Pane.Single => _single?.Name,
            _ => "Options and what each setup brings",
        });
        SetFooter(null, null, null, null, null);
        _primary = null;
    }

    private void BuildDetails()
    {
        var items = new List<RowVm>();
        if (_detailsFrom != Pane.Done)
        {
            items.Add(new ImportSectionVm { Id = "options", Title = "OPTIONS" });
            _optionRows = new List<ImportOptionVm>
            {
                new() { Id = "worlds", Label = "Copy my single-player worlds", Caption = "Off by default: worlds can be big.", IsOn = _options.CopyWorlds },
                new() { Id = "packs", Label = "Copy unused packs", Caption = "Also bring packs you weren't using.", IsOn = _options.CopyUnusedPacks },
                new() { Id = "keep", Label = "Keep my current Origin settings", Caption = "Don't replace settings Origin already has.", IsOn = _options.KeepMyOriginSettings },
                new() { Id = "servers", Label = "Replace server list", Caption = "Use the old list. Origin's is saved first.", IsOn = _options.ReplaceServerList },
                new() { Id = "terrain", Label = "Replace existing Distant Horizons / Voxy terrain", Caption = "Origin's existing terrain is saved first.", IsOn = _options.ReplaceExistingCaches },
            };
            AssignPos(_optionRows.ToList<CardRowVm>());
            items.AddRange(_optionRows);
        }

        switch (_detailsFrom)
        {
            case Pane.Overview: AddBulkPlanDetails(items); break;
            case Pane.Single: AddSinglePlanDetails(items); break;
            default: AddResultDetails(items); break;
        }
        PageList.ItemsSource = items;
    }

    private static string RoleText(SetupRole role) => role switch
    {
        SetupRole.Full => "Settings, mods and packs",
        SetupRole.ModsAndPacks => "Mods and packs",
        _ => "Packs only",
    };

    private void AddBulkPlanDetails(List<RowVm> items)
    {
        if (_plan == null)
        {
            items.Add(new HintVm { Id = "wait", Pos = CardPos.Only, Text = "Checking what can come over..." });
            return;
        }
        foreach (var group in _plan.Groups.OrderByDescending(g => g.Version, Comparer<string>.Create(McVersion.Compare)))
        {
            items.Add(new ImportSectionVm { Id = "v" + group.Version, Title = $"MINECRAFT {group.Version}", Count = group.Setups.Count });
            var rows = new List<CardRowVm>
            {
                new HintVm { Id = "s" + group.Version, Text = $"Settings from {group.Winner.Name}" },
            };
            foreach (var planned in group.Setups)
            {
                var mods = planned.Plan.Mods.Count(m => m.IsCopied);
                rows.Add(new ImportLineVm
                {
                    Id = "p" + planned.Setup.BulkId(), Title = planned.Setup.Name,
                    Caption = RoleText(planned.Role) + (mods > 0 ? $" - {mods} {(mods == 1 ? "mod" : "mods")}" : ""),
                    Warning = planned.Role == SetupRole.PacksOnly ? (string.IsNullOrWhiteSpace(planned.Note) ? ForgeFallback : planned.Note) : null,
                });
            }
            AssignPos(rows);
            items.AddRange(rows);
        }
        if (_plan.Skipped.Count > 0)
        {
            items.Add(new ImportSectionVm { Id = "skipped", Title = "CAN'T COME OVER", Count = _plan.Skipped.Count, Gold = true });
            var rows = _plan.Skipped.Select(s => new ImportLineVm { Id = "k" + s.Setup.BulkId(), Title = s.Setup.Name, Caption = Describe(s.Setup, 0), Warning = s.Reason }).ToList<CardRowVm>();
            AssignPos(rows);
            items.AddRange(rows);
        }
    }

    // Every item the plan knows about, with what happens and why: nothing is dropped without a line here.
    private void AddSinglePlanDetails(List<RowVm> items)
    {
        if (_singlePlan is not { } plan) return;
        items.Add(new ImportSectionVm { Id = "mods", Title = "MODS", Count = plan.Mods.Count });
        if (plan.Mods.Count == 0)
        {
            items.Add(new HintVm { Id = "nomods", Pos = CardPos.Only, Text = "No mods in this setup." });
        }
        else
        {
            var rows = plan.Mods.Select((m, i) => new ImportLineVm
            {
                Id = "m" + i, Title = m.DisplayName ?? m.ModId ?? m.FileName, Caption = m.Reason,
                Status = ModStatus(m.Outcome), StatusBrush = Res(ModStatusBrush(m.Outcome)),
            }).ToList<CardRowVm>();
            AssignPos(rows);
            items.AddRange(rows);
        }

        var rest = plan.Lines.Where(l => l.Kind != PlanItemKind.Mod).ToList();
        var reusedFiles = rest.Where(l => l.Section == SummarySection.ReusedGameFiles).ToList();
        var other = rest.Where(l => l.Section != SummarySection.ReusedGameFiles).ToList();
        var count = other.Count + (reusedFiles.Count > 0 ? 1 : 0);
        if (count == 0) return;
        items.Add(new ImportSectionVm { Id = "rest", Title = "SETTINGS, PACKS AND MORE", Count = count });
        var more = new List<CardRowVm>();
        if (reusedFiles.Count > 0)
            more.Add(new ImportLineVm
            {
                Id = "reused", Title = $"{reusedFiles.Count} files Minecraft needs (already on this PC)",
                Caption = $"Copied from another launcher on this PC ({ImportSummary.Megabytes(reusedFiles.Sum(l => l.Bytes))}), checked before use.",
                Status = "Reused", StatusBrush = Res("Brush.Info"),
            });
        for (var i = 0; i < other.Count; i++)
            more.Add(new ImportLineVm
            {
                Id = "o" + i, Title = other[i].Name, Caption = other[i].Reason,
                Status = SectionStatus(other[i].Section), StatusBrush = Res(SectionBrush(other[i].Section)),
            });
        AssignPos(more);
        items.AddRange(more);
    }

    private void AddResultDetails(List<RowVm> items)
    {
        if (_bulkResult is { } bulk)
        {
            foreach (var version in bulk.Versions)
            {
                items.Add(new ImportSectionVm { Id = "v" + version.Version, Title = $"MINECRAFT {version.Version}", Count = version.Setups.Count });
                var rows = version.Setups.Select(s => new ImportLineVm
                {
                    Id = "r" + s.Setup.BulkId(), Title = s.Setup.Name, Caption = OutcomeText(s),
                    Warning = s.Kind is SetupResultKind.Skipped or SetupResultKind.Failed ? s.Message ?? "Couldn't come over." : null,
                }).ToList<CardRowVm>();
                AssignPos(rows);
                items.AddRange(rows);
            }
            if (bulk.DidntComeOver.Count > 0)
            {
                items.Add(new ImportSectionVm { Id = "didnt", Title = "DIDN'T COME OVER", Count = bulk.DidntComeOver.Count, Gold = true });
                var rows = bulk.DidntComeOver.Select((n, i) => new ImportLineVm { Id = "d" + i, Title = n.Name, Caption = n.SetupLabel, Warning = n.Reason }).ToList<CardRowVm>();
                AssignPos(rows);
                items.AddRange(rows);
            }
            return;
        }

        var view = ImportSummaryView.Parse(_singleResult?.SummaryText);
        foreach (var block in view.Blocks)
        {
            if (block.Lines.Count == 0) continue;
            items.Add(new ImportSectionVm { Id = "b" + block.Title, Title = block.Title.ToUpperInvariant(), Count = block.Lines.Count, Gold = block.Section == SummarySection.NeedsAttention });
            var rows = block.Lines.Take(200).Select((l, i) => new ImportLineVm { Id = $"{block.Title}{i}", Title = l }).ToList<CardRowVm>();
            AssignPos(rows);
            items.AddRange(rows);
            if (block.Lines.Count > 200) items.Add(new HintVm { Id = "more" + block.Title, Pos = CardPos.Only, Text = $"and {block.Lines.Count - 200} more: open the import log to see them all." });
        }
        if (items.Count == 0) items.Add(new HintVm { Id = "nothing", Pos = CardPos.Only, Text = "Nothing to show." });
    }

    private static string OutcomeText(SetupOutcome s) => s.Kind switch
    {
        SetupResultKind.Imported => RoleText(s.Role),
        SetupResultKind.ImportedPartly => RoleText(s.Role),
        SetupResultKind.Skipped => "Skipped",
        _ => "Put back",
    };

    private void Option_Click(object sender, RoutedEventArgs e)
    {
        if (_optionRows.Count != 5) return;
        bool On(string id) => _optionRows.First(o => o.Id == id).IsOn;
        _options = new ImportOptions
        {
            CopyWorlds = On("worlds"),
            CopyUnusedPacks = On("packs"),
            KeepMyOriginSettings = On("keep"),
            ReplaceServerList = On("servers"),
            ReplaceExistingCaches = On("terrain"),
        };
        // Re-plan whatever is behind this page; the page itself is rebuilt when the new plan lands.
        if (_detailsFrom == Pane.Overview) _ = ReplanAsync();
        else if (_detailsFrom == Pane.Single) _ = ReplanSingleAsync();
    }

    private static string ModStatus(ModOutcome o) => o switch
    {
        ModOutcome.Copied => "Imported",
        ModOutcome.CopiedDisabled => "Imported, off",
        ModOutcome.CopiedAsBefore => "Imported, off as before",
        ModOutcome.BuiltIn => "Built in",
        _ => "Not imported",
    };

    private static string ModStatusBrush(ModOutcome o) => o switch
    {
        ModOutcome.Copied => "Brush.Success",
        ModOutcome.CopiedDisabled => "Brush.Warning",
        ModOutcome.CopiedAsBefore => "Brush.TextDim",
        ModOutcome.BuiltIn => "Brush.Info",
        _ => "Brush.TextMuted",
    };

    private static string SectionStatus(SummarySection s) => s switch
    {
        SummarySection.Imported => "Will import",
        SummarySection.ReusedGameFiles or SummarySection.ReusedCaches => "Reused",
        SummarySection.NeedsAttention => "Check",
        _ => "Not imported",
    };

    private static string SectionBrush(SummarySection s) => s switch
    {
        SummarySection.Imported => "Brush.Success",
        SummarySection.ReusedGameFiles or SummarySection.ReusedCaches => "Brush.Info",
        SummarySection.NeedsAttention => "Brush.Warning",
        _ => "Brush.TextMuted",
    };

    private static Brush Res(string key) => (Brush)Application.Current.FindResource(key);

    // ---------------------------------------------------------------- back

    private void Back_Click(object sender, RoutedEventArgs e) => GoBack();

    private void GoBack()
    {
        switch (_pane)
        {
            case Pane.Chooser:
                ShowOverview();
                break;
            case Pane.Single:
                _singleSeq++;
                try { _singleCts?.Cancel(); } catch (ObjectDisposedException) { }
                _singlePlanning = false;
                StopTickIfIdle();
                StopSpin(PlanSpin);
                ShowOverview();
                break;
            case Pane.Details:
                BackFromDetails();
                break;
        }
    }

    private void BackFromDetails()
    {
        switch (_detailsFrom)
        {
            case Pane.Single when _single != null:
                ShowPane(Pane.Single);
                SetHeader(_single.Name, GroupTitle(_single.SourceLabel) + (_single.McVersion == null ? "" : " - " + _single.McVersion));
                if (_singlePlan != null) RenderSinglePlan(_singlePlan);
                else PageList.ItemsSource = new List<RowVm>();
                PlanningRow.Visibility = V(_singlePlanning);
                _primary = FooterPrimary;
                UpdateFooterEnabled();
                break;
            case Pane.Done:
                RenderDone();
                break;
            default:
                ShowOverview();
                break;
        }
    }

    // ---------------------------------------------------------------- Browse

    private async void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog { Title = "Pick a Minecraft setup folder" };
        if (dialog.ShowDialog(Window.GetWindow(this)) != true || string.IsNullOrWhiteSpace(dialog.FolderName)) return;

        var folder = dialog.FolderName;
        var cts = Replace(ref _browseCts); // its own token: the main scan and plan are never cancelled by Browse
        FolderInspection found;
        try
        {
            found = await Task.Run(() => _backend.InspectFolderAsync(folder, cts.Token), cts.Token);
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
        var added = 0;
        foreach (var instance in found.Instances)
        {
            if (_all.Any(a => string.Equals(a.GameDir, instance.GameDir, StringComparison.OrdinalIgnoreCase))) continue;
            _all.Add(instance);
            added++;
        }
        _flash = added == 0 ? "That folder is already in the list." : null;
        if (_pane is Pane.Overview or Pane.Empty && added > 0) ShowOverviewOrEmpty();
        else RefreshNotice();
    }

    // ---------------------------------------------------------------- importing

    private void BeginApply(string caption)
    {
        _barFraction = 0;
        SetBar(0);
        ProgressCaption.Text = caption;
        ProgressDetail.Text = "";
        CancelButton.IsEnabled = true;
        _applyProgress.Last = null;
        _singleProgress.Last = null;
        ShowPane(Pane.Importing);
        SetHeader("Import", "Importing");
        SetFooter(null, null, null, null, null);
        _primary = null;
        _tick.Start();
    }

    private async Task RunBulkAsync(BulkImportPlan plan)
    {
        if (IsApplying || _launchBusy) return;
        var cts = new CancellationTokenSource();
        _applyCts = cts;
        BeginApply("Getting ready...");
        IsApplying = true;
        ImportRunState.Begin(); // WIRING (import-wiring agent): becomes `using var _ = ImportRunState.Enter();` once that exists

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
                Error = $"The import didn't finish ({ImportErrors.Plain(ex)}). If anything was changed, Origin puts it back the next time it starts.",
            };
        }
        finally
        {
            IsApplying = false;
            ImportRunState.End();
            StopTickIfIdle();
        }
        if (_disposed) return;
        _bulkResult = result;
        _singleResult = null;
        ShowResult(DoneModel.FromBulk(result));
    }

    private async Task RunSingleAsync()
    {
        if (_singlePlan is not { CanApply: true } plan || IsApplying || _launchBusy) return;
        var cts = new CancellationTokenSource();
        _applyCts = cts;
        BeginApply("Getting ready...");
        IsApplying = true;
        ImportRunState.Begin(); // WIRING (import-wiring agent): becomes `using var _ = ImportRunState.Enter();` once that exists

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
                    : $"The import didn't finish ({ImportErrors.Plain(ex)}). If anything was changed, Origin puts it back the next time it starts.",
            };
        }
        finally
        {
            IsApplying = false;
            ImportRunState.End();
            StopTickIfIdle();
        }
        if (_disposed) return;
        _singleResult = result;
        _bulkResult = null;
        ShowResult(DoneModel.FromSingle(plan, result));
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => _ = AskCancelAsync();

    private async Task AskCancelAsync()
    {
        if (!IsApplying || Confirm.Visibility == Visibility.Visible) return;
        // A destructive confirm opens on "Keep importing", so a double tap of Enter never cancels.
        var cancel = await Confirm.ShowAsync("Cancel the import?",
            "Everything copied so far is put back the way it was.", "Cancel import", "Keep importing", destructive: true);
        if (cancel && IsApplying) _applyCts?.Cancel();
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
                ProgressCaption.Text = $"Setup {b.SetupIndex + 1} of {b.SetupCount} - {b.SetupLabel}";
                ProgressDetail.Text = StageText(b.Inner.Stage) + (string.IsNullOrEmpty(b.Inner.Item) ? "" : ": " + b.Inner.Item);
            }
            else if (_singleProgress.Last is { } p)
            {
                fraction = p.Stage == ImportStage.Finishing ? 1 : Inner(p);
                var label = _single == null ? "" : $" - {_single.SourceLabel}: {_single.Name}";
                ProgressCaption.Text = "Setup 1 of 1" + label;
                ProgressDetail.Text = StageText(p.Stage) + (string.IsNullOrEmpty(p.Item) ? "" : ": " + p.Item);
            }
            if (fraction is { } f)
            {
                _barFraction = Math.Max(_barFraction, Math.Clamp(f, 0, 1)); // never backwards when a step counts its own files
                SetBar(_barFraction);
            }
        }
        else if (_pane == Pane.Overview && _planning && _planProgress.Last is { } plan && plan.Total > 0)
        {
            HeaderSubtitle.Text = $"Checking {Math.Min(plan.Done + 1, plan.Total)} of {plan.Total}...";
        }
    }

    private static double Inner(ImportProgress p) => p.Total > 0 ? Math.Clamp(p.Done / (double)p.Total, 0, 1) : 0;

    private static string StageText(ImportStage s) => s switch
    {
        ImportStage.Mods => "Copying mods",
        ImportStage.Settings => "Copying settings",
        ImportStage.Packs => "Copying packs",
        ImportStage.Worlds => "Copying worlds",
        ImportStage.GameFiles => "Reusing files Minecraft needs",
        ImportStage.Caches => "Moving Distant Horizons / Voxy terrain",
        ImportStage.Finishing => "Finishing up",
        _ => "Getting ready",
    };

    private void ProgressTrack_SizeChanged(object sender, SizeChangedEventArgs e) => SetBar(_barFraction);

    private void SetBar(double fraction)
    {
        var inner = Math.Max(0, ProgressTrack.ActualWidth - 2);
        ProgressFill.Width = inner * fraction;
        ProgressPercent.Text = Math.Round(fraction * 100).ToString("0", CultureInfo.InvariantCulture) + "%";
    }

    // ---------------------------------------------------------------- done / nothing changed

    /// <summary>What the Done page says, whichever way the import ran (one setup or many).</summary>
    private sealed class DoneModel
    {
        public bool Succeeded { get; init; }
        public bool Cancelled { get; init; }
        public string? Error { get; init; }
        public string Id { get; init; } = "";
        public int Brought { get; set; }
        public List<(string Version, string Text)> VersionLines { get; } = new();
        public List<(string Title, string? Caption, string Reason)> Lost { get; } = new();

        public static DoneModel FromBulk(BulkImportResult r)
        {
            var m = new DoneModel { Succeeded = r.Succeeded, Cancelled = r.Cancelled, Error = r.Error, Id = r.RunId };
            if (!m.Succeeded) return m;
            var landed = new List<string>();
            foreach (var version in r.Versions)
            {
                var ok = version.Setups.Where(s => s.Kind is SetupResultKind.Imported or SetupResultKind.ImportedPartly).ToList();
                landed.AddRange(ok.Select(s => s.Setup.Name));
                m.Brought += ok.Count;
                if (ok.Count > 0)
                {
                    var full = ok.FirstOrDefault(s => s.Role == SetupRole.Full);
                    var tail = full != null ? $", settings from {full.Setup.Name}" : ok.All(s => s.Role == SetupRole.PacksOnly) ? ", packs only" : "";
                    m.VersionLines.Add((version.Version, $"{ok.Count} {(ok.Count == 1 ? "setup" : "setups")}{tail}"));
                }
                foreach (var s in version.Setups.Where(s => s.Kind is SetupResultKind.Skipped or SetupResultKind.Failed))
                    m.Lost.Add((s.Setup.Name, $"{GroupTitle(s.Setup.SourceLabel)}" + (s.Setup.McVersion == null ? "" : " - " + s.Setup.McVersion), s.Message ?? "Couldn't come over."));
            }
            // A whole setup the engine only reports in its "didn't come over" list.
            foreach (var item in r.DidntComeOver)
            {
                if (landed.Any(n => item.SetupLabel.Contains(n, StringComparison.OrdinalIgnoreCase))) continue;
                if (m.Lost.Any(l => item.SetupLabel.Contains(l.Title, StringComparison.OrdinalIgnoreCase) || l.Title.Contains(item.SetupLabel, StringComparison.OrdinalIgnoreCase))) continue;
                m.Lost.Add((item.SetupLabel, null, item.Reason));
            }
            return m;
        }

        public static DoneModel FromSingle(ImportPlan plan, ImportResult r)
        {
            var m = new DoneModel { Succeeded = r.Succeeded, Cancelled = r.Cancelled, Error = r.Error, Id = r.ImportId };
            if (!m.Succeeded) return m;
            m.Brought = 1;
            m.VersionLines.Add((plan.TargetVersion, $"1 setup, settings from {plan.Source.Name}"));
            return m;
        }
    }

    private void ShowResult(DoneModel model)
    {
        _done = model;
        if (!model.Succeeded)
        {
            ShowFailed(model);
            return;
        }
        Imported?.Invoke(this, EventArgs.Empty);
        _lostCollapsed = model.Lost.Count > 3;
        RenderDone();
    }

    private void RenderDone()
    {
        if (_done is not { } m) return;
        var items = new List<RowVm>
        {
            new ImportTitleVm { Title = $"Done: {m.Brought} {(m.Brought == 1 ? "setup" : "setups")} brought over" },
        };
        var lines = m.VersionLines.Select(v => new ImportLineVm { Id = "v" + v.Version, Title = v.Version, Caption = v.Text }).ToList<CardRowVm>();
        AssignPos(lines);
        if (lines.Count > 0) items.Add(new ImportSectionVm { Id = "versions", Title = "MINECRAFT", Count = lines.Count });
        items.AddRange(lines);
        if (m.Lost.Count > 0)
        {
            items.Add(new ImportSectionVm
            {
                Id = "lost", Title = $"{m.Lost.Count} COULDN'T COME OVER", Gold = true, Collapsible = true, Collapsed = _lostCollapsed,
            });
            if (!_lostCollapsed)
            {
                var rows = m.Lost.Select((l, i) => new ImportLineVm { Id = "l" + i, Title = l.Title, Caption = l.Caption, Warning = l.Reason }).ToList<CardRowVm>();
                AssignPos(rows);
                items.AddRange(rows);
            }
        }
        PageList.ItemsSource = items;
        ShowPane(Pane.Done);
        SetHeader("Import", "Done");
        SetFooter(null, "VIEW DETAILS", () => OpenDetails(), "CLOSE", () => CloseRequested?.Invoke(this, EventArgs.Empty));
        _primary = FooterPrimary;
        WantFocus();
    }

    private void ShowFailed(DoneModel m)
    {
        ShowPane(Pane.Failed);
        SetHeader("Import", null);
        if (m.Cancelled && string.IsNullOrEmpty(m.Error))
        {
            FailedReason.Text = "Import cancelled.";
            FailedReason.Foreground = (Brush)FindResource("Brush.TextDim");
        }
        else
        {
            FailedReason.Text = string.IsNullOrEmpty(m.Error) ? "The import didn't finish." : m.Error;
            FailedReason.Foreground = (Brush)FindResource("Brush.Danger");
        }
        SetFooter(null, "CLOSE", () => CloseRequested?.Invoke(this, EventArgs.Empty), "TRY AGAIN", TryAgain);
        _primary = FooterPrimary;
        WantFocus();
    }

    private void TryAgain()
    {
        _bulkResult = null;
        _singleResult = null;
        if (_all.Count == 0) ShowEmpty();
        else
        {
            ShowOverview();
            _ = ReplanAsync();
        }
    }

    // ---------------------------------------------------------------- buttons, keys

    private void Close_Click(object sender, RoutedEventArgs e) => RequestClose();

    /// <summary>Closes unless an import is writing (then it asks about cancelling instead). True when it closed or asked.</summary>
    public bool RequestClose()
    {
        if (IsApplying)
        {
            _ = AskCancelAsync();
            return true;
        }
        CloseRequested?.Invoke(this, EventArgs.Empty);
        return true;
    }

    /// <summary>MainWindow's Esc: a prompt closes first, a running import asks, a side page goes back, otherwise the drawer closes.</summary>
    public bool HandleEscape()
    {
        if (Confirm.Visibility == Visibility.Visible)
        {
            Confirm.Dismiss();
            return true;
        }
        if (IsApplying) return RequestClose();
        if (_pane is Pane.Chooser or Pane.Single or Pane.Details)
        {
            GoBack();
            return true;
        }
        return RequestClose();
    }

    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        // Enter runs the page's one primary action unless focus is on something that uses Enter itself.
        if (e.Key == Key.Enter && Confirm.Visibility != Visibility.Visible
            && _primary is { IsVisible: true, IsEnabled: true } primary
            && Keyboard.FocusedElement is not (ButtonBase or ComboBox or TextBoxBase))
        {
            e.Handled = true;
            primary.RaiseEvent(new RoutedEventArgs(ButtonBase.ClickEvent));
            return;
        }
        base.OnPreviewKeyDown(e);
    }

    // ---------------------------------------------------------------- small helpers

    // The newest action wins: starts a fresh token and cancels whatever the previous one was doing.
    private static CancellationTokenSource Replace(ref CancellationTokenSource? slot)
    {
        var old = slot;
        var fresh = new CancellationTokenSource();
        slot = fresh;
        try { old?.Cancel(); } catch (ObjectDisposedException) { }
        return fresh;
    }

    private void StartSpin(RotateTransform transform)
    {
        if (!SystemParameters.ClientAreaAnimation) return;
        if (TryFindResource("Motion.Base") is Duration { HasTimeSpan: true } d && d.TimeSpan == TimeSpan.Zero) return; // reduced motion
        transform.BeginAnimation(RotateTransform.AngleProperty,
            new DoubleAnimation(0, 360, TimeSpan.FromMilliseconds(900)) { RepeatBehavior = RepeatBehavior.Forever });
    }

    private static void StopSpin(RotateTransform transform) => transform.BeginAnimation(RotateTransform.AngleProperty, null);

    private void StopSpinners()
    {
        StopSpin(ScanSpin);
        StopSpin(PlanSpin);
    }

    /// <summary>Cancels a scan, a plan, a browse and stops the timer. Never cancels an apply: that is the engine's rollback to run.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _tick.Stop();
        StopSpinners();
        foreach (var cts in new[] { _scanCts, _planCts, _browseCts, _singleCts })
        {
            try { cts?.Cancel(); } catch (ObjectDisposedException) { }
        }
    }

    private sealed class Latest<T> : IProgress<T> where T : class
    {
        private T? _last;
        public T? Last { get => Volatile.Read(ref _last); set => Volatile.Write(ref _last, value); }
        public void Report(T value) => Last = value;
    }

    // ---------------------------------------------------------------- CI screen render (UI/ImportShots.cs)

    internal void TourImportAll() => ImportAll_Click(ImportAllButton, new RoutedEventArgs());

    internal void TourAskCancel() => _ = AskCancelAsync();

    internal void TourOpenChooser(string version) => OpenChooser(version);

    internal void TourOpenDetails() => OpenDetails();

    internal void TourOpenSingle(string name)
    {
        if (_all.FirstOrDefault(s => s.Name == name) is { } setup) OpenSingle(setup);
    }

    internal void TourBack() => GoBack();

    internal void TourSecondary() => _footerSecondary?.Invoke();

    internal void TourPrimaryFooter() => _footerPrimary?.Invoke();

    internal void TourScrollOverview(double offset) => ScrollOf(OverviewList)?.ScrollToVerticalOffset(offset);

    internal void TourScrollPage(double offset) => ScrollOf(PageList)?.ScrollToVerticalOffset(offset);

    internal void TourLaunchBusy(bool busy) => SetLaunchBusy(busy);

    private static ScrollViewer? ScrollOf(ItemsControl list) => FindScroll(list);

    private static ScrollViewer? FindScroll(DependencyObject root)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is ScrollViewer sv) return sv;
            if (FindScroll(child) is { } deeper) return deeper;
        }
        return null;
    }
}
