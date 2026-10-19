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
public partial class ImportPanel : UserControl, IDisposable, IImportCloseHost
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
        i.McVersion != null && i.Confidence == Confidence.Inferred ? $"Not sure: Origin guessed {i.McVersion}." : null;

    private const string ForgeFallback = "Forge mods can't come over (Fabric only, except 1.8.9).";

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
    private string? _refusal; // the wiring's sentence (LaunchRunState.ImportRefusal()); null = Import is allowed
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
    private ImportDoneModel? _done;

    /// <summary>Import buttons are on (off while a game starts or an import writes). The row and section buttons bind to it.</summary>
    public static readonly DependencyProperty AllowedProperty =
        DependencyProperty.Register(nameof(Allowed), typeof(bool), typeof(ImportPanel), new PropertyMetadata(true));

    public bool Allowed
    {
        get => (bool)GetValue(AllowedProperty);
        private set => SetValue(AllowedProperty, value);
    }

    /// <summary>True from the moment the import starts writing until the engine has finished or rolled back.</summary>
    public bool IsApplying { get; private set; }

    public Pane Current => _pane;

    public ImportPanel(IImportBackend backend)
    {
        _backend = backend;
        InitializeComponent();
        _tick.Tick += (_, _) => ShowLatestProgress();
        Unloaded += (_, _) => StopSpinners();
        // The primary button takes the focus when it becomes available, unless the player already went somewhere
        // else (a tick box, a row): the landing spots (Close, Browse, Back) don't count.
        AddHandler(Keyboard.GotKeyboardFocusEvent, new KeyboardFocusChangedEventHandler((_, e) =>
        {
            if (e.NewFocus is not (Button { Name: "CloseButton" or "BrowseIconButton" or "BackButton" })) _wantPrimaryFocus = false;
        }), true);
        ShowScanning();
    }

    // ---------------------------------------------------------------- hooks other code calls

    /// <summary>MainWindow, when the drawer opens: starts the scan. Never called at launcher startup.</summary>
    public void Start()
    {
        if (_started || _disposed) return;
        _started = true;
        SetRefusal(ImportHooks.Refusal());
        _ = ScanAsync();
    }

    /// <summary>
    /// The wiring's one answer to "why can't Import run right now?": the sentence from LaunchRunState.ImportRefusal()
    /// (a launch is starting or an update is in progress), or null when it can. MainWindow calls this on
    /// LaunchRunState.Changed and UpdateRunState.Changed. WIRING: those two types are on claude/import-wiring.
    /// </summary>
    public void SetRefusal(string? sentence)
    {
        if (_refusal == sentence) return;
        _refusal = sentence;
        BusyChanged();
    }

    private bool Busy => _refusal != null;

    private string BusyReason => _refusal ?? "";

    /// <summary>
    /// IImportCloseHost: asks "Stop importing?" (destructive confirm, focus on KEEP GOING) and returns the answer.
    /// True = the player chose to cancel: the cancel has only been STARTED, the engine rolls back and the pane then shows
    /// "Nothing changed" (Import cancelled), so a caller that must wait for the rollback (the window's close) awaits
    /// that itself. False = keep importing. Also false when a confirm is already open or nothing is importing.
    /// </summary>
    public Task<bool> ConfirmCancelAsync() => AskCancelAsync();

    private void BusyChanged()
    {
        UpdateAllowed();
        RefreshNotice();
        UpdateImportAll();
        UpdateFooterEnabled();
    }

    // One disabled set for every reason Import can't run: a launch or update (refusal), an import writing, or a plan that is blocked.
    private void UpdateAllowed() => Allowed = !Busy && !IsApplying && _plan is not { Blocked.Count: > 0 };

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

    private bool _listDirty;

    private void ShowOverview()
    {
        var rebuild = _listDirty;
        if (rebuild) BuildOverview();
        ShowPane(Pane.Overview);
        SetHeader("Import", OverviewSubtitle());
        SetFooter("Other launchers stay as they are.", "DETAILS", () => OpenDetails(), "IMPORT", ImportAll);
        _primary = FooterPrimary;
        UpdateImportAll();
        WantFocus();
        if (rebuild) _ = ReplanAsync();
    }

    private string OverviewSubtitle() => $"{ImportLogic.Plural(_all.Count, "setup", "setups")} found";

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
            FooterPrimary.IsEnabled = !Busy && _singlePlan is { CanApply: true } p && !string.IsNullOrEmpty(p.TargetVersion);
            FooterSecondary.IsEnabled = _singlePlan != null;
        }
        else if (_pane == Pane.Overview)
        {
            FooterSecondary.IsEnabled = true; // the primary (IMPORT n) is UpdateImportAll's
        }
        else
        {
            FooterPrimary.IsEnabled = !(Busy && _pane == Pane.Failed);
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
        if (Busy && _pane is Pane.Overview or Pane.Single or Pane.Failed or Pane.Empty)
            text = BusyReason;
        else if (_pane == Pane.Overview && _planFailed)
            text = "Couldn't check your setups.";
        else if (_pane == Pane.Overview && _plan is { Blocked.Count: > 0 } plan)
            text = string.Join(" ", plan.Blocked);
        else if (_pane == Pane.Single && _singlePlan is { Blocked.Count: > 0 } single)
            text = string.Join(" ", single.Blocked);
        else if (_pane is Pane.Overview or Pane.Empty && _flash != null)
            text = _flash;
        NoticeText.Text = text ?? "";
        NoticeText.Foreground = (Brush)FindResource("Brush.Warning");
        NoticeIcon.Stroke = NoticeText.Foreground;
        NoticeStrip.Visibility = V(!string.IsNullOrEmpty(text));
    }

    // ---------------------------------------------------------------- overview

    private static int GroupOrder(string label) => label switch
    {
        "Minecraft" => 0, "Prism" => 1, "Modrinth" => 2, "CurseForge" => 3, _ => 4,
    };

    private static string GroupTitle(string label) => ImportLogic.GroupTitle(label);

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
        _listDirty = false;
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
    internal static string Describe(DetectedInstance setup, int mods) => ImportLogic.Describe(setup, mods);

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
                if (choose) lines.Add("Pick settings");
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
        UpdateAllowed();
        if (_pane != Pane.Overview) return;
        var importable = _plan?.Groups.Sum(g => g.Setups.Count) ?? 0;
        var everything = _rows.Values.Where(r => r.CanTick).All(r => r.Ticked);
        var (label, enabled) = ImportLogic.ImportAllButton(_planning, _planFailed, _plan is { CanApply: true }, importable, everything, Busy);
        FooterPrimary.Content = label;
        FooterPrimary.IsEnabled = enabled;
        TryFocusPrimary();
    }

    private void StopTickIfIdle()
    {
        if (!IsApplying && !_planning && !_singlePlanning) _tick.Stop();
    }

    private void ImportAll()
    {
        if (_pane != Pane.Overview || !FooterPrimary.IsEnabled) return;
        if (_planFailed)
        {
            _ = ReplanAsync();
            return;
        }
        if (_plan is { CanApply: true } plan) StartBulk(plan);
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

    private static T? Row<T>(object sender) where T : class => (sender as FrameworkElement)?.DataContext as T;

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
            Setup = setup, Caption = (group.Pick.Preselected != null && group.Pick.Preselected.BulkId() == setup.BulkId() ? "Suggested - " : "") + Evidence(setup),
            Suggested = group.Pick.Preselected != null && group.Pick.Preselected.BulkId() == setup.BulkId(),
            Selected = group.Winner.BulkId() == setup.BulkId(),
        }).ToList<CardRowVm>();
        AssignPos(cards);
        PageList.ItemsSource = cards;

        ShowPane(Pane.Chooser);
        SetHeader($"{version} settings", "Use settings from");
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
        StopTickIfIdle();
        _planFailed = plan == null;
        _plan = plan;
        if (plan != null) ApplyPlan(plan);
        UpdateImportAll();
        RefreshNotice();
    }

    /// <summary>"38 h played - 9 days ago": the evidence for picking one setup's settings.</summary>
    private static string Evidence(DetectedInstance setup)
    {
        var parts = new List<string> { setup.SourceLabel };
        if (setup.PlaytimeSeconds is > 0) parts.Add(Playtime(setup.PlaytimeSeconds.Value));
        if (setup.LastPlayed is { } last && last > DateTimeOffset.MinValue.AddYears(2)) parts.Add(Ago(last));
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

    // The Minecraft versions an import can go into, read once and off the UI thread (never SupportedTargets directly).
    private Task<IReadOnlyList<string>>? _targetsTask;

    private async Task<IReadOnlyList<string>> TargetsAsync()
    {
        var task = _targetsTask ??= ImportHooks.TargetsAsync(_backend, CancellationToken.None);
        try
        {
            return await task;
        }
        catch
        {
            if (ReferenceEquals(_targetsTask, task)) _targetsTask = null; // never cache a failure: the next setup tries again
            throw;
        }
    }

    private void OpenSingle(DetectedInstance setup)
    {
        if (IsApplying) return;
        _single = setup;
        _singlePlan = null;
        _singleSeq++;
        try { _singleCts?.Cancel(); } catch (ObjectDisposedException) { }
        PageList.ItemsSource = new List<RowVm>();

        _singleTarget = null;
        _suppressTarget = true;
        TargetBox.ItemsSource = null;
        _suppressTarget = false;
        TargetBox.IsEnabled = false;

        ShowPane(Pane.Single);
        SetHeader(setup.Name, GroupTitle(setup.SourceLabel) + (setup.McVersion == null ? "" : " - " + setup.McVersion));
        SetFooter(null, "DETAILS", () => OpenDetails(), "IMPORT", () => { if (FooterPrimary.IsEnabled) StartSingle(); });
        _primary = FooterPrimary;
        TargetNote.Visibility = Visibility.Collapsed;
        WantFocus();
        UpdateFooterEnabled();
        _ = LoadTargetsAsync(setup);
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
        if (_disposed || _pane != Pane.Single || !ReferenceEquals(_single, setup)) return; // the player moved on

        var targets = raw.OrderByDescending(v => v, Comparer<string>.Create(McVersion.Compare)).ToList();
        var supported = setup.McVersion != null && targets.Contains(setup.McVersion);
        _suppressTarget = true;
        TargetBox.ItemsSource = targets;
        TargetBox.SelectedItem = supported ? setup.McVersion : null;
        _suppressTarget = false;
        TargetBox.IsEnabled = true;
        _singleTarget = supported ? setup.McVersion : null;
        UpdateTargetNote();
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
                ? "Version unknown. Pick one."
                : $"{_single.McVersion} isn't supported. Pick another.";
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
        if (_pane == Pane.Single)
        {
            PageList.ItemsSource = new List<RowVm>();
            PlanningRow.Visibility = Visibility.Visible;
            StartSpin(PlanSpin);
        }
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
        _singleFailure = plan == null ? failure : null;
        if (_pane == Pane.Details && _detailsFrom == Pane.Single)
        {
            BuildDetails(); // the Details page stays; it just gets the new plan (or says it couldn't)
        }
        else if (plan == null)
        {
            if (_pane == Pane.Single)
                PageList.ItemsSource = new List<RowVm> { new HintVm { Id = "fail", Text = $"Couldn't check this setup ({failure}).", Pos = CardPos.Only } };
            UpdateFooterEnabled();
            return;
        }
        else if (_pane == Pane.Single) RenderSinglePlan(plan);
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
        var builtIn = plan.Mods.Count(m => m.Outcome == ModOutcome.BuiltIn);
        stats.Add(new ImportStatVm
        {
            Id = "mods", Label = "Mods",
            Value = copied.ToString(CultureInfo.InvariantCulture) + (builtIn > 0 ? $" ({builtIn} already in Origin)" : ""),
        });
        Stat("settings", "Settings files", plan.Ops.Count(o => o.Item is PlanItemKind.Setting or PlanItemKind.Config));
        Stat("resource", "Resource packs", plan.Ops.Count(o => o.Item == PlanItemKind.ResourcePack));
        Stat("shader", "Shader packs", plan.Ops.Count(o => o.Item == PlanItemKind.ShaderPack));
        if (plan.Options.CopyWorlds || plan.Ops.Any(o => o.Item == PlanItemKind.World))
            Stat("worlds", "Worlds", plan.Ops.Count(o => o.Item == PlanItemKind.World), always: true);
        Stat("terrain", "Distant Horizons / Voxy terrain", plan.Caches.Count(c => c.WillImport));
        // The download size is the last line of the same card.
        if (plan.GameFiles is { MetadataUnavailable: false, ToDownloadBytes: > 0 } files)
            stats.Add(new HintVm { Id = "download", Text = $"{ImportSummary.Megabytes(files.ToDownloadBytes)} to download on first Play." });
        AssignPos(stats);
        items.AddRange(stats);

        var attention = plan.Lines.Where(l => l.Section == SummarySection.NeedsAttention).ToList();
        if (attention.Count > 0)
        {
            items.Add(new ImportSectionVm { Id = "attention", Title = "NEEDS ATTENTION", Count = attention.Count, Gold = true });
            var rows = attention.Select((l, i) => new ImportLineVm { Id = "a" + i, Title = l.Name, Warning = l.Reason }).ToList<CardRowVm>();
            AssignPos(rows);
            items.AddRange(rows);
        }
        PageList.ItemsSource = items;

        SetFooter(null, "DETAILS", () => OpenDetails(), "IMPORT", () => { if (FooterPrimary.IsEnabled) StartSingle(); });
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
                new() { Id = "worlds", Label = "Copy worlds", Caption = "Off: worlds can be big.", IsOn = _options.CopyWorlds },
                new() { Id = "packs", Label = "Copy unused packs", Caption = "Also bring packs you weren't using.", IsOn = _options.CopyUnusedPacks },
                new() { Id = "keep", Label = "Keep Origin's settings", Caption = "Don't replace settings Origin has.", IsOn = _options.KeepMyOriginSettings },
                new() { Id = "servers", Label = "Replace server list", Caption = "Origin's list is saved first.", IsOn = _options.ReplaceServerList },
                new() { Id = "terrain", Label = "Replace far-view terrain", Caption = "Distant Horizons, Voxy. Saved first.", IsOn = _options.ReplaceExistingCaches },
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
            var rows = new List<CardRowVm>();
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
    private string? _singleFailure;

    private void AddSinglePlanDetails(List<RowVm> items)
    {
        if (_singlePlan is not { } plan)
        {
            items.Add(new HintVm
            {
                Id = "noplan", Pos = CardPos.Only,
                Text = _singlePlanning ? "Checking what can come over..." : $"Couldn't check this setup ({_singleFailure}).",
            });
            return;
        }
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
                Id = "reused", Title = $"{ImportLogic.Plural(reusedFiles.Count, "file", "files")} Minecraft needs (already on this PC)",
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
                items.Add(new ImportSectionVm { Id = "didnt", Title = "NOT IMPORTED", Count = bulk.DidntComeOver.Count, Gold = true });
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
        var added = 0;
        foreach (var instance in found.Instances)
        {
            if (_all.Any(a => string.Equals(a.GameDir, instance.GameDir, StringComparison.OrdinalIgnoreCase))) continue;
            _all.Add(instance);
            added++;
        }
        _flash = added == 0 ? "That folder is already in the list." : null;
        if (_pane is Pane.Overview or Pane.Empty && added > 0) ShowOverviewOrEmpty();
        else
        {
            if (added > 0) _listDirty = true; // the player is on a side page: the overview is rebuilt when they get back
            RefreshNotice();
        }
    }

    // ---------------------------------------------------------------- importing

    private void BeginApply(string caption)
    {
        _barFraction = 0;
        SetBar(0);
        ProgressCaption.Text = caption;
        ProgressSetup.Text = "";
        ProgressDetail.Text = "";
        _applyProgress.Last = null;
        _singleProgress.Last = null;
        ShowPane(Pane.Importing);
        SetHeader("Import", null);
        SetFooter(null, "CANCEL", () => _ = AskCancelAsync(), null, null);
        _primary = null;
        _tick.Start();
    }

    // The callers check IsApplying / Busy first; the method itself starts with the run marker, nothing before it.
    private async Task RunBulkAsync(BulkImportPlan plan)
    {
        using var run = ImportHooks.EnterRun();
        var cts = new CancellationTokenSource();
        _applyCts = cts;
        BeginApply("Getting ready...");
        IsApplying = true;
        UpdateAllowed();

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
            IsApplying = false;
            UpdateAllowed();
            StopTickIfIdle();
        }
        if (_disposed) return;
        _bulkResult = result;
        _singleResult = null;
        ShowResult(ImportDoneModel.FromBulk(result));
    }

    private async Task RunSingleAsync(ImportPlan plan)
    {
        using var run = ImportHooks.EnterRun();
        var cts = new CancellationTokenSource();
        _applyCts = cts;
        BeginApply("Getting ready...");
        IsApplying = true;
        UpdateAllowed();

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
            IsApplying = false;
            UpdateAllowed();
            StopTickIfIdle();
        }
        if (_disposed) return;
        _singleResult = result;
        _bulkResult = null;
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
    private async Task<bool> AskCancelAsync()
    {
        if (!IsApplying || Confirm.Visibility == Visibility.Visible) return false;
        // A destructive confirm opens on "Keep importing", so a double tap of Enter never cancels.
        var cancel = await Confirm.ShowAsync("Stop importing?",
            "Anything copied so far is undone.", "STOP", "KEEP GOING", destructive: true);
        if (cancel && IsApplying) _applyCts?.Cancel();
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
                ProgressCaption.Text = $"Setup {b.SetupIndex + 1} of {b.SetupCount}";
                ProgressSetup.Text = b.SetupLabel;
                ProgressDetail.Text = StageText(b.Inner.Stage) + (string.IsNullOrEmpty(b.Inner.Item) ? "" : ": " + b.Inner.Item);
            }
            else if (_singleProgress.Last is { } p)
            {
                fraction = p.Stage == ImportStage.Finishing ? 1 : Inner(p);
                ProgressCaption.Text = "Setup 1 of 1";
                ProgressSetup.Text = _single == null ? "" : $"{_single.SourceLabel}: {_single.Name}";
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
        ProgressPercent.Text = Math.Round(fraction * 100).ToString("0", CultureInfo.InvariantCulture) + "% overall";
    }

    // ---------------------------------------------------------------- done / nothing changed

    private void ShowResult(ImportDoneModel model)
    {
        Confirm.Dismiss(); // the import ended while "Stop importing?" was still up
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
            new ImportTitleVm { Title = $"Done: {ImportLogic.Plural(m.Brought, "setup", "setups")} brought over", Sub = "Find them in Profiles." },
        };
        var lines = m.VersionLines.Select(v => new ImportLineVm { Id = "v" + v.Version, Title = v.Version, Caption = v.Text }).ToList<CardRowVm>();
        AssignPos(lines);
        items.AddRange(lines);
        if (m.Lost.Count > 0)
        {
            items.Add(new ImportSectionVm
            {
                Id = "lost", Title = "SKIPPED", Count = m.Lost.Count, Gold = true, Collapsible = true, Collapsed = _lostCollapsed,
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
        SetHeader("Import", null);
        SetFooter(null, "DETAILS", () => OpenDetails(), "CLOSE", () => CloseRequested?.Invoke(this, EventArgs.Empty));
        _primary = FooterPrimary;
        WantFocus();
    }

    private void ShowFailed(ImportDoneModel m)
    {
        ShowPane(Pane.Failed);
        SetHeader("Import", null);
        if (m.Cancelled && string.IsNullOrEmpty(m.Error))
        {
            FailedReason.Text = "You stopped it.";
            FailedReason.Foreground = (Brush)FindResource("Brush.TextDim");
        }
        else
        {
            FailedReason.Text = string.IsNullOrEmpty(m.Error) ? "The import didn't finish." : m.Error;
            FailedReason.Foreground = (Brush)FindResource("Brush.Warning");
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

    private void Close_Click(object sender, RoutedEventArgs e) => _ = RequestClose();

    /// <summary>
    /// Closes the drawer, unless an import is writing: then it asks "Stop importing?" and returns the answer.
    /// True = closed, or the player chose to cancel: the cancel is only STARTED, the engine rolls back and the pane then
    /// shows "Nothing changed" (Import cancelled), so the drawer is still open when this returns. False = keep importing.
    /// A window-close host must await the rollback itself (the wiring's IImportCloseHost.IsApplying turning false).
    /// </summary>
    public async Task<bool> RequestClose()
    {
        if (IsApplying) return await AskCancelAsync();
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
        if (_pane is Pane.Chooser or Pane.Single or Pane.Details && !IsApplying)
        {
            GoBack();
            return true;
        }
        _ = RequestClose();
        return true;
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
        if (!IsApplying) Confirm.Dismiss();
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

    internal void TourImportAll() => ImportAll();

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

    internal void TourRefusal(string? sentence) => SetRefusal(sentence);

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
