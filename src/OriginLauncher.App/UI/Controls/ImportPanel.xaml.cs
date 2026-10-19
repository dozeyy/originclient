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

// The Import drawer (MainWindow hosts it like the Friends drawer). Lunar's "import from launcher", in Origin's look:
//
//   Launchers (every launcher Origin knows, found or not, with a count)
//     -> Versions ("Import from all versions", "What to bring" once for all, one row per Minecraft version)
//       -> Version (what this version brings, which setups, whose settings)       [side page]
//     -> Review (what comes over, what is left out and why) -> Importing (one bar, cancel) -> Done / Nothing changed
//   side pages behind the back arrow: whose settings (Chooser), one setup on its own (Single), Details.
//
// The choices live in ImportFlowModel (pure, tested); the engine gets them as a BulkSelection plus ImportOptions.
// All work is the backend's (scan, count, plan, apply: each async, cancellable, off the UI thread); this file only
// shows what it returns. Rules it keeps:
//  * nothing starts until the drawer opens (the scan is NOT part of launcher startup);
//  * the newest action wins: closing cancels a scan, count or plan in flight; a plan that finishes after a newer one
//    was asked for is dropped; Browse has its own token, so it never cancels the main scan;
//  * rows that stay on screen while the player ticks change in place (the list never rebuilds under the pointer);
//  * the plan is the authority on what comes over: Review is drawn from it, never from the choices alone;
//  * while an import is applying, ImportRunState says so (Home keeps Play disabled), Esc and the window's close
//    ask before cancelling, and a cancel puts everything back (the engine's rollback);
//  * progress is shown from a 100 ms timer reading the latest report, never one UI hop per file.
public partial class ImportPanel : UserControl, IDisposable, IImportCloseHost
{
    public enum Pane { Launchers, Versions, Version, Review, Chooser, Single, Details, Importing, Done, Failed }

    /// <summary>The drawer wants to go away (Close, Done, Esc, scrim).</summary>
    public event EventHandler? CloseRequested;

    /// <summary>An import finished and was kept: Home and Profiles re-read the settings.</summary>
    public event EventHandler? Imported;

    /// <summary>
    /// A row's short "not sure" caption for a setup whose Minecraft version or loader the detector could not be certain of
    /// (DetectedInstance.VersionCertain / LoaderCertain, via BulkRules), or null. The full reason (UncertainReason) is in Details.
    /// </summary>
    private static string? NotSureNote(DetectedInstance i) =>
        i.McVersion != null && BulkRules.IsVersionUncertain(i) ? BulkRules.UncertainCaption : null;

    private const string AllBar = "all";
    private const string PickSettings = "Pick settings";

    private string? _stopping; // set once the player chose STOP: the rollback is running, CANCEL is off

    private readonly IImportBackend _backend;
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private readonly Latest<BulkProgress> _applyProgress = new();
    private readonly Latest<ImportProgress> _singleProgress = new();
    private readonly Latest<BulkPlanProgress> _planProgress = new();

    // The live rows of the current pages: they change in place while shown.
    private readonly Dictionary<SourceKind, LauncherTileVm> _tiles = new();
    private readonly Dictionary<string, VersionRowVm> _versionRows = new(StringComparer.Ordinal);
    private readonly Dictionary<string, SetupRowVm> _versionSetupRows = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, CategoryBarVm> _bars = new(StringComparer.Ordinal);
    private SelectAllVm? _selectAll;
    private ImportNoteVm? _versionNote;
    private string? _tileKeys;
    private bool _tilesEmpty;
    private string? _versionsKey;

    private ImportFlowModel _model = new(Array.Empty<DetectedInstance>(), Array.Empty<string>());

    private CancellationTokenSource? _scanCts;
    private CancellationTokenSource? _countCts;
    private CancellationTokenSource? _planCts;
    private CancellationTokenSource? _browseCts;
    private CancellationTokenSource? _singleCts;
    private CancellationTokenSource? _applyCts;
    private int _planSeq;
    private int _singleSeq;
    private bool _disposed;
    private bool _started;
    private bool _scanning = true;
    private bool _planning;
    private bool _planFailed;
    private bool _singlePlanning;
    private bool _suppressTarget;
    private string? _refusal; // the wiring's sentence (LaunchRunState.ImportRefusal()); null = Import is allowed
    private bool _wantPrimaryFocus;
    private bool _lostCollapsed;
    private bool _lastRunSingle;
    private double _barFraction;

    private Pane _pane = Pane.Launchers;
    private Button? _primary;
    private Action? _footerPrimary;
    private Action? _footerSecondary;
    private string? _scanNote;
    private string? _flash;
    private List<ImportOptionVm> _optionRows = new();
    private string? _versionKey;           // the Version page's version
    private string? _chooserVersion;
    private Pane _chooserFrom = Pane.Review;
    private Pane _singleFrom = Pane.Review;
    private Pane _detailsFrom = Pane.Review;
    private ImportOptions _options = new();

    private BulkImportPlan? _plan;
    private string? _planKey;              // the selection the plan was made for (ImportFlowModel.SelectionKey)
    private ImportOptions? _planOptions;   // ... and the options
    private string? _planningKey;
    private ImportOptions? _planningOptions;
    private BulkImportPlan? _lastPlan;     // the newest plan that landed: a selection change re-plans from it (only the groups that changed)
    private bool _listChanged;             // Browse added setups: the next plan starts from scratch (the old plan's request lacks them)
    private DetectedInstance? _single;
    private string? _singleTarget;
    private ImportPlan? _singlePlan;
    private string? _singleFailure;
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
        ShowLaunchers();
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
    /// LaunchRunState.Changed and UpdateRunState.Changed.
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

    // The row controls (tick boxes, CHANGE): off while Import is refused or an import writes. Never tied to a BLOCKED plan
    // ("Not enough free disk space"): the player must be able to untick versions until it fits. IMPORT follows the plan.
    private void UpdateAllowed() => Allowed = ImportLogic.RowControlsAllowed(Busy, IsApplying);

    // ---------------------------------------------------------------- scan, then count

    private async Task ScanAsync()
    {
        _scanning = true;
        RenderLaunchers();
        var cts = Replace(ref _scanCts);
        var targetsTask = TargetsAsync(); // in parallel: which versions Origin can take
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

        IReadOnlyList<string> targets;
        try
        {
            targets = await targetsTask;
        }
        catch (Exception)
        {
            // Unknown: every version found is offered, and the engine's plan says which can't come over (and why).
            targets = scan.Instances.Select(ImportFlowModel.KeyOf).Where(k => k != ImportFlowModel.UnknownKey).Distinct().ToList();
        }
        if (_disposed || cts.IsCancellationRequested) return;

        var model = new ImportFlowModel(scan.Instances, targets);
        model.Add(_model.Setups.Where(s => s.Kind == SourceKind.Folder)); // a folder Browse found while the scan ran
        _model = model;
        _scanNote = scan.Notes.Count > 0 ? scan.Notes[0] : null;
        _scanning = false;
        _listChanged = true;
        RefreshCurrent();
        UpdateScanSpinner();
        _ = CountAsync();
    }

    // Which launchers are installed and what each setup holds (worlds, mods, packs): folder listings only, off the UI
    // thread, after the scan. The tiles and rows fill their counts in when it lands; nothing waits for it.
    private async Task CountAsync()
    {
        var cts = Replace(ref _countCts);
        var model = _model;
        var setups = model.Setups.ToList();
        try
        {
            var installed = await Task.Run(() => _backend.GetInstalledLaunchersAsync(cts.Token), cts.Token);
            if (_disposed || cts.IsCancellationRequested || !ReferenceEquals(model, _model)) return;
            model.Installed = installed;
            RefreshCurrent();
            var contents = await Task.Run(() => _backend.GetContentsAsync(setups, cts.Token), cts.Token);
            if (_disposed || cts.IsCancellationRequested || !ReferenceEquals(model, _model)) return;
            Merge(contents);
        }
        catch (Exception)
        {
            // no counts: the rows just don't show them
        }
    }

    private void Merge(IReadOnlyDictionary<string, SetupContents> found)
    {
        var merged = new Dictionary<string, SetupContents>(_model.Contents, StringComparer.Ordinal);
        foreach (var (id, contents) in found) merged[id] = contents;
        _model.Contents = merged;
        RefreshCurrent();
    }

    // Brings whatever page is showing up to date with the model (a count landed, a folder was added).
    private void RefreshCurrent()
    {
        switch (_pane)
        {
            case Pane.Launchers: RenderLaunchers(); break;
            case Pane.Versions: ShowVersions(); break;
            case Pane.Version when _versionKey != null: RefreshVersion(); break;
            case Pane.Single: RefreshBars(); break;
        }
    }

    // ---------------------------------------------------------------- panes

    private static Visibility V(bool on) => on ? Visibility.Visible : Visibility.Collapsed;

    private void ShowPane(Pane pane)
    {
        _pane = pane;
        LaunchersList.Visibility = V(pane == Pane.Launchers);
        VersionsList.Visibility = V(pane == Pane.Versions);
        VersionList.Visibility = V(pane == Pane.Version);
        ReviewPane.Visibility = V(pane == Pane.Review);
        PageGrid.Visibility = V(pane is Pane.Chooser or Pane.Single or Pane.Details or Pane.Done);
        SingleTop.Visibility = V(pane == Pane.Single);
        ProgressPane.Visibility = V(pane == Pane.Importing);
        FailedPane.Visibility = V(pane == Pane.Failed);
        BackButton.Visibility = V(CanGoBack(pane));
        BrowseIconButton.Visibility = V(pane is Pane.Launchers or Pane.Versions);
        if (pane is not Pane.Single) PlanningRow.Visibility = Visibility.Collapsed;
        if (pane is not Pane.Review) ShowReviewPlanning(false);
        if (pane is not (Pane.Launchers or Pane.Versions)) _flash = null;
        UpdateScanSpinner();
        RefreshNotice();
    }

    private static bool CanGoBack(Pane pane) =>
        pane is Pane.Versions or Pane.Version or Pane.Review or Pane.Chooser or Pane.Single or Pane.Details;

    private void UpdateScanSpinner()
    {
        var on = _scanning && _pane == Pane.Launchers;
        ScanSpinner.Visibility = V(on);
        if (on) StartSpin(ScanSpin); else StopSpin(ScanSpin);
    }

    // The header keeps a two-line height on every page (HeaderSpacer), so the page below never jumps; a page with no
    // state line has its title centred against the close button instead of sitting on an empty line.
    private void SetHeader(string title, string? subtitle)
    {
        HeaderTitle.Text = title;
        HeaderSubtitle.Text = subtitle ?? "";
        HeaderSubtitle.Visibility = V(!string.IsNullOrEmpty(subtitle));
    }

    // The footer's note: one line in the 380 px drawer. The other launchers are only read.
    private const string FooterCopiesNote = "Copies, never moves";

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
        _primary = primaryText != null ? FooterPrimary : null;
        UpdateFooterEnabled();
    }

    private void UpdateFooterEnabled()
    {
        switch (_pane)
        {
            case Pane.Single:
                FooterPrimary.IsEnabled = !Busy && _singlePlan is { CanApply: true } p && !string.IsNullOrEmpty(p.TargetVersion);
                FooterSecondary.IsEnabled = _singlePlan != null;
                break;
            case Pane.Review:
                FooterSecondary.IsEnabled = true; // the primary (IMPORT n) is UpdateImportAll's
                break;
            case Pane.Versions:
                FooterPrimary.IsEnabled = _model.SelectedVersionCount > 0;
                break;
            case Pane.Failed:
                FooterPrimary.IsEnabled = !Busy;
                FooterSecondary.IsEnabled = true;
                break;
            case Pane.Importing:
                FooterSecondary.IsEnabled = _stopping == null;
                break;
            default:
                FooterPrimary.IsEnabled = true;
                FooterSecondary.IsEnabled = true;
                break;
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
        if (Busy && _pane is Pane.Launchers or Pane.Versions or Pane.Version or Pane.Review or Pane.Single or Pane.Failed)
            text = BusyReason;
        else if (_pane == Pane.Review && _planFailed)
            text = "Couldn't check your setups.";
        else if (_pane == Pane.Review && !_planning && _plan is { Blocked.Count: > 0 } plan)
            text = string.Join(" ", plan.Blocked);
        else if (_pane == Pane.Single && _singlePlan is { Blocked.Count: > 0 } single)
            text = string.Join(" ", single.Blocked);
        else if (_pane is Pane.Launchers or Pane.Versions && _flash != null)
            text = _flash;
        NoticeText.Text = text ?? "";
        NoticeText.Foreground = (Brush)FindResource("Brush.Warning");
        NoticeIcon.Stroke = NoticeText.Foreground;
        NoticeStrip.Visibility = V(!string.IsNullOrEmpty(text));
    }

    private static void AssignPos(IReadOnlyList<CardRowVm> rows)
    {
        for (var i = 0; i < rows.Count; i++)
            rows[i].Pos = rows.Count == 1 ? CardPos.Only : i == 0 ? CardPos.First : i == rows.Count - 1 ? CardPos.Last : CardPos.Middle;
    }

    private static T? Row<T>(object sender) where T : class => (sender as FrameworkElement)?.DataContext as T;

    private static string GroupTitle(string label) => ImportLogic.GroupTitle(label);

    /// <summary>"1.21.1 - Fabric - 12 mods" (ASCII only; the mod count only once the plan knows it).</summary>
    internal static string Describe(DetectedInstance setup, int mods) => ImportLogic.Describe(setup, mods);

    private string ScopeTitle => _model.Scope is { } kind ? ImportFlowModel.LauncherTitle(kind) : "All launchers";

    // ---------------------------------------------------------------- 1. launchers

    private void ShowLaunchers()
    {
        ShowPane(Pane.Launchers);
        RenderLaunchers(rebuild: true);
        WantFocus();
    }

    // Every launcher Origin can import from, found or not. The tiles change in place while the scan and count land;
    // the list is rebuilt only when a tile comes or goes (Browse adds "Picked folders") or the empty note does.
    private void RenderLaunchers(bool rebuild = false)
    {
        var tiles = _model.Tiles(_scanning);
        var nothing = !_scanning && _model.Setups.Count == 0;
        var keys = string.Join(",", tiles.Select(t => t.Kind));
        if (rebuild || keys != _tileKeys || nothing != _tilesEmpty || _tiles.Count == 0)
        {
            _tiles.Clear();
            var items = new List<RowVm> { new ImportSectionVm { Id = "from", Title = "IMPORT FROM" } };
            var cards = new List<CardRowVm>();
            foreach (var tile in tiles)
            {
                var vm = new LauncherTileVm { Kind = tile.Kind, Title = tile.Title };
                Fill(vm, tile);
                _tiles[tile.Kind] = vm;
                cards.Add(vm);
            }
            AssignPos(cards);
            items.AddRange(cards);
            if (nothing)
            {
                var text = "Nothing found to import. Pick a setup's folder with Browse, or play once in your other launcher first.";
                if (!string.IsNullOrEmpty(_scanNote)) text = _scanNote + " " + text;
                items.Add(new HintVm { Id = "nothing", Text = text, Pos = CardPos.Only });
            }
            LaunchersList.ItemsSource = items;
            _tileKeys = keys;
            _tilesEmpty = nothing;
        }
        else
        {
            foreach (var tile in tiles)
                if (_tiles.TryGetValue(tile.Kind, out var vm)) Fill(vm, tile);
        }
        if (_pane != Pane.Launchers) return;

        SetHeader("Import", _scanning ? "Looking for your setups"
            : nothing ? "Nothing found"
            : $"{ImportLogic.Plural(_model.Setups.Count, "setup", "setups")} found");
        if (_scanning) SetFooter(FooterCopiesNote, null, null, null, null);
        else if (nothing) SetFooter(null, null, null, "BROWSE FOR A FOLDER", () => _ = BrowseAsync());
        else SetFooter(FooterCopiesNote, null, null, "ALL LAUNCHERS", () => OpenLauncher(null));
        TryFocusPrimary();
    }

    private static void Fill(LauncherTileVm vm, LauncherTile tile)
    {
        vm.Caption = tile.Caption;
        vm.Enabled = tile.Enabled;
        vm.Tip = tile.State switch
        {
            LauncherState.Found => $"Import from {tile.Title}",
            LauncherState.NotInstalled => $"{tile.Title} isn't installed on this PC",
            LauncherState.Empty => $"Nothing to import from {tile.Title}",
            _ => "Still looking...",
        };
    }

    private void Tile_Click(object sender, RoutedEventArgs e)
    {
        if (Row<LauncherTileVm>(sender) is { Enabled: true } tile) OpenLauncher(tile.Kind);
    }

    private void OpenLauncher(SourceKind? kind)
    {
        if (_scanning || _model.Setups.Count == 0) return;
        if (_model.Scope != kind) _versionsKey = null; // another launcher: a fresh list
        _model.SetScope(kind);
        ShowVersions();
        WantFocus();
    }

    // ---------------------------------------------------------------- 2. versions

    private void ShowVersions()
    {
        var versions = _model.Versions();
        var key = (_model.Scope?.ToString() ?? "all") + "|" + string.Join(",", versions.Select(v => v.Key + ":" + v.Setups.Count));
        if (_pane != Pane.Versions) ShowPane(Pane.Versions);
        if (key != _versionsKey) BuildVersions(versions);
        _versionsKey = key;
        RefreshVersions();
        PrefetchPlan();
    }

    private void BuildVersions(IReadOnlyList<VersionEntry> versions)
    {
        _versionRows.Clear();
        var items = new List<RowVm>
        {
            new ImportSectionVm { Id = "bring", Title = "WHAT TO BRING" },
            NewBar(AllBar),
        };

        var supported = versions.Where(v => v.Supported).ToList();
        items.Add(new ImportSectionVm { Id = "versions", Title = "VERSIONS", Count = supported.Count });
        if (supported.Count == 0)
        {
            _selectAll = null;
            items.Add(new HintVm
            {
                Id = "none", Pos = CardPos.Only,
                Text = "None of these versions can come over as they are. Open one below to pick a version for it.",
            });
        }
        else
        {
            _selectAll = new SelectAllVm();
            var cards = new List<CardRowVm> { _selectAll };
            foreach (var version in supported) cards.Add(NewVersionRow(version));
            AssignPos(cards);
            items.AddRange(cards);
        }

        var cannot = versions.Where(v => !v.Supported).ToList();
        if (cannot.Count > 0)
        {
            items.Add(new ImportSectionVm { Id = "cannot", Title = "CAN'T COME OVER", Count = cannot.Count, Gold = true });
            var cards = cannot.Select(v => (CardRowVm)NewVersionRow(v)).ToList();
            AssignPos(cards);
            items.AddRange(cards);
        }
        VersionsList.ItemsSource = items;
    }

    private VersionRowVm NewVersionRow(VersionEntry version)
    {
        var row = new VersionRowVm { VersionKey = version.Key, Title = version.Title, CanTick = version.Supported };
        _versionRows[version.Key] = row;
        return row;
    }

    // Every version row, the select-all row and the "what to bring" switches, in place.
    private void RefreshVersions()
    {
        var versions = _model.Versions();
        foreach (var version in versions)
        {
            if (!_versionRows.TryGetValue(version.Key, out var row)) continue;
            row.Caption = _model.VersionCaption(version);
            row.Contents = _model.ContentsLine(version.Setups);
            if (!version.Supported)
            {
                row.Warning = version.Reason;
                continue;
            }
            row.Ticked = _model.IsSelected(version) && _model.Going(version).Count > 0;
            row.Warning = row.Ticked && _model.Winner(version).NeedsChoice ? PickSettings : null;
            row.Resync();
        }

        var supported = versions.Count(v => v.Supported);
        var selected = _model.SelectedVersionCount;
        if (_selectAll != null)
        {
            _selectAll.State = selected == 0 ? false : selected == supported ? true : null;
            _selectAll.Caption = selected == supported
                ? (supported == 1 ? "The one version" : $"All {supported} versions")
                : $"{selected} of {supported} versions";
            _selectAll.Resync();
        }
        RefreshBars();

        if (_pane != Pane.Versions) return;
        SetHeader(ScopeTitle, _model.Summary(_model.InScope.ToList()));
        SetFooter(supported == 0 ? null : $"{selected} of {ImportLogic.Plural(supported, "version", "versions")}",
            null, null, "REVIEW", OpenReview);
    }

    private void SelectAll_Click(object sender, RoutedEventArgs e)
    {
        if (!ReferenceEquals(e.Source, sender)) return; // the tick box's own click (below) bubbles here too
        ToggleAllVersions();
    }

    private void SelectAllTick_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        ToggleAllVersions();
    }

    private void ToggleAllVersions()
    {
        if (!Allowed) return;
        var on = _model.SelectedVersionCount == 0 || _selectAll?.State != true;
        _model.SetAllVersions(on);
        if (on)
            foreach (var version in _model.Versions().Where(v => v.Supported && _model.Going(v).Count == 0))
                foreach (var setup in version.Setups) _model.SetTicked(setup, true);
        RefreshVersions();
    }

    private void VersionRow_Click(object sender, RoutedEventArgs e)
    {
        if (!ReferenceEquals(e.Source, sender)) return;
        if (Row<VersionRowVm>(sender) is { } row) OpenVersion(row.VersionKey);
    }

    private void VersionTick_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (!Allowed || Row<VersionRowVm>(sender) is not { CanTick: true } row) return;
        SetVersionOn(row.VersionKey, !row.Ticked);
        RefreshVersions();
    }

    // A version in or out. In with none of its setups ticked: all of them come back (a tick means "bring it").
    private void SetVersionOn(string key, bool on)
    {
        _model.SetSelected(key, on);
        if (on && _model.Version(key) is { } version && _model.Going(version).Count == 0)
            foreach (var setup in version.Setups) _model.SetTicked(setup, true);
    }

    // ---------------------------------------------------------------- what to bring (the switches)

    // The switches of one bar: AllBar (every version at once), "v|1.21.1" (the Version page) or "s|1.21.1" (one setup on
    // its own: the same choice as its version's, one choice per version).
    private CategoryBarVm NewBar(string id)
    {
        var chips = CategoryFilter.Each
            .Select(c => new CategoryChipVm { Category = c, Label = CategoryFilter.Name(c) })
            .ToList();
        var bar = new CategoryBarVm { Id = id, Chips = chips, Pos = CardPos.Only };
        _bars[id] = bar;
        UpdateBar(bar);
        return bar;
    }

    private static string? BarVersion(string id) => id == AllBar ? null : id[2..];

    private IReadOnlyCollection<DetectedInstance> BarSetups(string id)
    {
        if (id.StartsWith("s|", StringComparison.Ordinal)) return _single == null ? Array.Empty<DetectedInstance>() : new[] { _single };
        if (BarVersion(id) is { } key)
        {
            if (_model.Version(key) is not { } version) return Array.Empty<DetectedInstance>();
            var ticked = version.Setups.Where(_model.IsTicked).ToList();
            return ticked.Count > 0 ? ticked : version.Setups;
        }
        var going = _model.AllGoing();
        return going.Count > 0 ? going : _model.Versions().Where(v => v.Supported).SelectMany(v => v.Setups).ToList();
    }

    private void UpdateBar(CategoryBarVm bar)
    {
        var setups = BarSetups(bar.Id);
        var key = BarVersion(bar.Id);
        foreach (var chip in bar.Chips)
        {
            chip.Count = _model.CountText(setups, chip.Category);
            chip.Enabled = _model.Has(setups, chip.Category);
            chip.IsOn = key == null ? _model.BringsAll(chip.Category) : (_model.BringFor(key) & chip.Category) != 0;
            chip.Resync();
        }
    }

    private void RefreshBars()
    {
        foreach (var bar in _bars.Values) UpdateBar(bar);
    }

    private void Chip_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<CategoryChipVm>(sender) is not { } chip) return;
        var bar = _bars.Values.FirstOrDefault(b => b.Chips.Contains(chip));
        if (bar == null || !Allowed) return;
        var on = chip.IsOn != true; // mixed (some versions) -> on for all
        if (BarVersion(bar.Id) is { } key) _model.SetBring(key, chip.Category, on);
        else _model.SetBringAll(chip.Category, on);
        RefreshBars();
        if (_pane == Pane.Version) RefreshVersion();
        if (bar.Id.StartsWith("s|", StringComparison.Ordinal)) _ = ReplanSingleAsync();
    }

    // ---------------------------------------------------------------- 2b. one version

    private void OpenVersion(string key)
    {
        if (_model.Version(key) is not { } version) return;
        _versionKey = key;
        _versionSetupRows.Clear();
        foreach (var id in _bars.Keys.Where(k => k.StartsWith("v|", StringComparison.Ordinal)).ToList()) _bars.Remove(id);

        var items = new List<RowVm>();
        if (version.Supported)
        {
            items.Add(new ImportSectionVm { Id = "bring", Title = "WHAT TO BRING" });
            items.Add(NewBar("v|" + key));
            items.Add(new ImportSectionVm { Id = "setups", Title = "SETUPS", Count = version.Setups.Count });
            _versionNote = new ImportNoteVm { Id = "note" };
            var cards = new List<CardRowVm> { _versionNote };
            foreach (var setup in version.Setups)
            {
                var row = new SetupRowVm { Setup = setup, Caption = Evidence(setup), CanTick = true };
                _versionSetupRows[row.Id] = row;
                cards.Add(row);
            }
            AssignPos(cards);
            items.AddRange(cards);
        }
        else
        {
            _versionNote = null;
            items.Add(new HintVm
            {
                Id = "why", Pos = CardPos.Only,
                Text = version.Version == null
                    ? "Origin couldn't tell which Minecraft version these setups are. Open one to pick a version for it."
                    : $"Origin can't run Minecraft {version.Version}. Open a setup to bring it into a version Origin has.",
            });
            items.Add(new ImportSectionVm { Id = "setups", Title = "SETUPS", Count = version.Setups.Count });
            var cards = version.Setups.Select(setup => (CardRowVm)new SetupRowVm
            {
                Setup = setup, Caption = Describe(setup, 0), CanTick = false, Dimmed = true,
                Warning = setup.Problem ?? version.Reason,
            }).ToList();
            AssignPos(cards);
            items.AddRange(cards);
        }
        VersionList.ItemsSource = items;
        ShowPane(Pane.Version);
        RefreshVersion();
        WantFocus();
    }

    // The Version page's rows, note and footer, in place (a tick, a switch, a settings pick).
    private void RefreshVersion()
    {
        if (_versionKey == null || _model.Version(_versionKey) is not { } version) return;
        if (_pane == Pane.Version) SetHeader("Minecraft " + version.Title, ScopeTitle);
        if (!version.Supported)
        {
            if (_pane == Pane.Version) SetFooter(null, null, null, null, null);
            return;
        }

        var preview = _model.Winner(version);
        var going = _model.Going(version);
        var selected = _model.IsSelected(version);
        foreach (var setup in version.Setups)
        {
            if (!_versionSetupRows.TryGetValue(setup.BulkId(), out var row)) continue;
            var lines = new List<string>();
            var isWinner = preview.Winner != null && preview.Winner.BulkId() == row.Id;
            if (version.Version != null && BulkRules.IsPacksOnly(setup, version.Version)) lines.Add(BulkRules.ForgeCaption);
            if (isWinner && preview.NeedsChoice) lines.Add(PickSettings);
            if (NotSureNote(setup) is { } notSure) lines.Add(notSure);
            row.Ticked = _model.IsTicked(setup);
            row.Warning = lines.Count == 0 ? null : string.Join("\n", lines);
            row.ChoiceVersion = isWinner && preview.Participants.Count > 1 ? version.Key : null;
        }
        if (_versionNote != null)
        {
            _versionNote.Text = !selected ? "Not part of this import. Press INCLUDE to bring it."
                : going.Count == 0 ? "Tick a setup to bring this version."
                : preview.Winner == null ? "These setups bring packs only: Origin's own settings stay."
                : going.Count == 1 ? $"Everything comes from {preview.Winner.Name}."
                : preview.NeedsChoice ? $"Settings from {preview.Winner.Name} (suggested): press CHANGE to pick. Mods and packs from every ticked setup."
                : $"Settings from {preview.Winner.Name}. Mods and packs from every ticked setup.";
        }
        RefreshBars();

        if (_pane != Pane.Version) return;
        var on = selected && going.Count > 0;
        SetFooter(_model.VersionCaption(version), null, null, on ? "DONE" : "INCLUDE", () =>
        {
            if (on)
            {
                GoBack();
                return;
            }
            SetVersionOn(version.Key, true);
            RefreshVersion();
        });
    }

    private void SetupRow_Click(object sender, RoutedEventArgs e)
    {
        // A press on one of the row's own controls bubbles here too: only the row itself opens the setup.
        if (!ReferenceEquals(e.Source, sender)) return;
        if (Row<SetupRowVm>(sender) is { } row) OpenSingle(row.Setup);
    }

    private void SetupTick_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<SetupRowVm>(sender) is not { } row) return;
        if (!Allowed)
        {
            row.Ticked = _model.IsTicked(row.Setup);
            return;
        }
        _model.SetTicked(row.Setup, row.Ticked); // the tick box wrote its new value into the row (two-way)
        if (row.Ticked && _versionKey != null) _model.SetSelected(_versionKey, true);
        RefreshVersion();
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

    // ---------------------------------------------------------------- whose settings

    private void OpenChooser(string version)
    {
        if (_model.Version(version) is not { } entry) return;
        var preview = _model.Winner(entry);
        if (preview.Participants.Count == 0) return;
        _chooserVersion = version;
        if (_pane is Pane.Version or Pane.Review) _chooserFrom = _pane;

        var ids = new HashSet<string>(preview.Participants.Select(p => p.BulkId()), StringComparer.OrdinalIgnoreCase);
        var ordered = preview.Pick.Options.Where(o => ids.Contains(o.BulkId()))
            .Concat(preview.Participants.Where(p => preview.Pick.Options.All(o => o.BulkId() != p.BulkId())))
            .Select(o => preview.Participants.First(p => p.BulkId() == o.BulkId()))
            .ToList();
        var suggested = preview.Pick.Preselected?.BulkId();
        var cards = ordered.Select(setup => new ChooseRowVm
        {
            Setup = setup,
            Caption = (suggested == setup.BulkId() ? "Suggested - " : "") + Evidence(setup),
            Suggested = suggested == setup.BulkId(),
            Selected = preview.Winner?.BulkId() == setup.BulkId(),
        }).ToList<CardRowVm>();
        AssignPos(cards);
        PageList.ItemsSource = cards;

        ShowPane(Pane.Chooser);
        SetHeader($"{entry.Title} settings", "Use settings from");
        SetFooter(null, null, null, null, null);
    }

    private void ChooseRow_Click(object sender, RoutedEventArgs e)
    {
        if (Row<ChooseRowVm>(sender) is not { } row || _chooserVersion is not { } version) return;
        _model.Choices[version] = row.Setup.BulkId();
        GoBack(); // Review re-plans: the choice is part of its key
    }

    /// <summary>"Lunar - 38 h played - 9 days ago": the evidence for picking one setup's settings.</summary>
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

    // ---------------------------------------------------------------- 3. review

    private void OpenReview()
    {
        if (_model.SelectedVersionCount == 0) return;
        ShowPane(Pane.Review);
        SetHeader("Review", ReviewSubtitle());
        SetFooter(null, "DETAILS", () => OpenDetails(), "IMPORT", ImportAll);
        var key = _model.SelectionKey();
        var options = _model.Options(_options);
        if (_plan != null && !_listChanged && _planKey == key && Equals(_planOptions, options))
        {
            RenderReview(_plan);
        }
        else if (_planning && !_listChanged && _planningKey == key && Equals(_planningOptions, options))
        {
            ReviewList.ItemsSource = new List<RowVm>();
            ShowReviewPlanning(true); // the plan for exactly this is already on its way
        }
        else
        {
            _ = ReplanAsync();
        }
        UpdateImportAll();
        RefreshNotice();
        WantFocus();
    }

    private string ReviewSubtitle()
    {
        if (_planning || _plan == null) return "Checking what can come over...";
        var setups = _plan.Groups.Sum(g => g.Setups.Count);
        return $"{ImportLogic.Plural(setups, "setup", "setups")} into {ImportLogic.Plural(_plan.Groups.Count, "version", "versions")}";
    }

    private void ShowReviewPlanning(bool on)
    {
        ReviewPlanning.Visibility = V(on);
        if (on) StartSpin(ReviewSpin); else StopSpin(ReviewSpin);
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
        var choices = new Dictionary<string, string>(_model.Choices);
        _planningKey = key;
        _planningOptions = options;
        _plan = null;
        _planning = true;
        _planFailed = false;
        _planProgress.Last = null;
        _tick.Start();
        if (_pane == Pane.Review)
        {
            ReviewList.ItemsSource = new List<RowVm>();
            ShowReviewPlanning(true);
            SetHeader("Review", ReviewSubtitle());
        }
        UpdateImportAll();
        RefreshNotice();

        // Same options as the last plan and the same setups found: only the selection changed, so only the groups whose
        // setups changed are planned again. A switch change (Details, What to bring) or a new folder (Browse) plans afresh.
        var basis = _lastPlan is { Request: { } r } last && r.Options == options && !_listChanged ? last : null;
        _listChanged = false;
        BulkImportPlan? plan;
        try
        {
            // A recovery of an interrupted import (at launcher start) finishes before anything is planned.
            await ImportStartup.Recovery.ConfigureAwait(true);
            plan = await Task.Run(() => PlanWithChoicesAsync(basis, selection, options, choices, cts.Token), cts.Token);
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
        if (_pane == Pane.Review)
        {
            ShowReviewPlanning(false);
            SetHeader("Review", ReviewSubtitle());
            if (plan != null) RenderReview(plan);
            else ReviewList.ItemsSource = new List<RowVm> { new HintVm { Id = "fail", Pos = CardPos.Only, Text = "Couldn't check your setups. Press TRY AGAIN." } };
        }
        UpdateImportAll();
        RefreshNotice();
        if (_pane == Pane.Details && _detailsFrom == Pane.Review) BuildDetails();
    }

    // Plans, then re-applies every settings winner the player picked (a fresh plan forgets them).
    private async Task<BulkImportPlan> PlanWithChoicesAsync(BulkImportPlan? basis, BulkSelection selection, ImportOptions options,
        IReadOnlyDictionary<string, string> choices, CancellationToken ct)
    {
        var plan = basis != null
            ? await _backend.ReplanSelectionAsync(basis, selection, _planProgress, ct)
            : await _backend.PlanBulkAsync(selection, options, _planProgress, ct);
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
    private void RenderReview(BulkImportPlan plan)
    {
        var items = new List<RowVm>();
        var setups = plan.AllSetups.ToList();
        int Ops(PlanItemKind kind) => setups.Sum(p => p.Plan.Ops.Count(o => o.Item == kind));

        items.Add(new ImportSectionVm { Id = "coming", Title = "COMING OVER" });
        var stats = new List<CardRowVm>
        {
            new ImportStatVm { Id = "versions", Label = "Minecraft versions", Value = N(plan.Groups.Count) },
            new ImportStatVm { Id = "setups", Label = "Setups", Value = N(setups.Count) },
        };
        void Stat(string id, string label, int n)
        {
            if (n > 0) stats.Add(new ImportStatVm { Id = id, Label = label, Value = N(n) });
        }
        Stat("mods", "Mods", setups.Sum(p => p.Plan.Mods.Count(m => m.IsCopied)));
        Stat("resource", "Resource packs", Ops(PlanItemKind.ResourcePack));
        Stat("shader", "Shader packs", Ops(PlanItemKind.ShaderPack));
        Stat("worlds", "Worlds", Ops(PlanItemKind.World));
        Stat("screenshots", "Screenshots", Ops(PlanItemKind.Screenshot));
        if (plan.BytesToCopy > 0)
            stats.Add(new ImportStatVm { Id = "size", Label = "To copy", Value = ImportSummary.Megabytes(plan.BytesToCopy) });
        AssignPos(stats);
        items.AddRange(stats);
        items.Add(new HintVm
        {
            Id = "where", Pos = CardPos.Only,
            Text = "Each version lands in Origin's own setup for it (find them in Profiles). Your other launchers are only read.",
        });

        var attention = new List<CardRowVm>();
        for (var i = 0; i < plan.Notes.Count; i++)
            attention.Add(new ImportLineVm { Id = "n" + i, Title = plan.Notes[i] });
        // One line per problem, naming every setup it is in ("Create" turned off in five setups is one line, not five).
        var problems = setups
            .SelectMany(p => p.Plan.Lines.Where(l => l.Section == SummarySection.NeedsAttention).Select(l => (Line: l, p.Setup.Name)))
            .GroupBy(x => (x.Line.Name, Reason: ImportLogic.StatusLine(x.Line.Reason)));
        foreach (var problem in problems)
            attention.Add(new ImportLineVm
            {
                Id = $"a{attention.Count}", Title = problem.Key.Name, Warning = problem.Key.Reason,
                Caption = ImportFlowModel.JoinFew(problem.Select(x => x.Name).Distinct().ToList()),
            });
        if (attention.Count > 0)
        {
            items.Add(new ImportSectionVm { Id = "attention", Title = "NEEDS ATTENTION", Count = attention.Count, Gold = true });
            AssignPos(attention);
            items.AddRange(attention);
        }

        foreach (var group in plan.Groups.OrderByDescending(g => g.Version, Comparer<string>.Create(McVersion.Compare)))
        {
            items.Add(new ImportSectionVm { Id = "v" + group.Version, Title = $"MINECRAFT {group.Version}", Count = group.Setups.Count });
            var capable = group.Setups.Count(s => s.Role != SetupRole.PacksOnly);
            var rows = new List<CardRowVm>();
            foreach (var planned in group.Setups)
            {
                var isWinner = group.Winner.BulkId() == planned.Setup.BulkId();
                var lines = new List<string>();
                if (planned.Role == SetupRole.PacksOnly) lines.Add(BulkRules.ForgeCaption);
                if (group.NeedsChoice && isWinner) lines.Add(PickSettings);
                if (NotSureNote(planned.Setup) is { } notSure) lines.Add(notSure);
                rows.Add(new SetupRowVm
                {
                    Setup = planned.Setup, NoTick = true, CanTick = true,
                    Caption = $"{planned.Setup.SourceLabel} - {ImportLogic.RoleCaption(planned.Role, planned.Plan.Mods.Count(m => m.IsCopied))}",
                    Warning = lines.Count == 0 ? null : string.Join("\n", lines),
                    ChoiceVersion = isWinner && capable > 1 ? group.Version : null,
                });
            }
            AssignPos(rows);
            items.AddRange(rows);
        }

        var builtIn = setups.Sum(p => p.Plan.Mods.Count(m => m.Outcome == ModOutcome.BuiltIn));
        if (builtIn > 0)
        {
            items.Add(new ImportSectionVm { Id = "builtin", Title = "ALREADY IN ORIGIN" });
            items.Add(new HintVm
            {
                Id = "builtin", Pos = CardPos.Only,
                Text = $"{ImportLogic.Plural(builtIn, "mod is", "mods are")} part of Origin already, so {(builtIn == 1 ? "it isn't" : "they aren't")} copied.",
            });
        }

        var left = new List<CardRowVm>();
        // What each version leaves out, one line per choice ("Worlds" off everywhere is one line naming every version).
        var offs = plan.Groups.OrderByDescending(g => g.Version, Comparer<string>.Create(McVersion.Compare))
            .Select(g => (g.Version, Names: ImportFlowModel.LeftOutNames(_model.BringFor(g.Version))))
            .Where(x => x.Names.Length > 0)
            .GroupBy(x => x.Names);
        foreach (var off in offs)
            left.Add(new ImportLineVm
            {
                Id = "c" + off.Key, Title = off.Key,
                Caption = $"Minecraft {ImportFlowModel.JoinFew(off.Select(x => x.Version).ToList())} - switched off in What to bring",
            });
        foreach (var skipped in plan.Skipped.Where(_model.Shows))
            left.Add(new ImportLineVm
            {
                Id = "k" + skipped.Setup.BulkId(), Title = skipped.Setup.Name,
                Caption = GroupTitle(skipped.Setup.SourceLabel) + (skipped.Setup.McVersion == null ? "" : " - " + skipped.Setup.McVersion),
                Warning = skipped.Reason,
            });
        if (left.Count > 0)
        {
            items.Add(new ImportSectionVm { Id = "left", Title = "LEFT OUT", Count = left.Count, Gold = true });
            AssignPos(left);
            items.AddRange(left);
        }
        ReviewList.ItemsSource = items;
    }

    private static string N(int n) => n.ToString(CultureInfo.InvariantCulture);

    private void UpdateImportAll()
    {
        UpdateAllowed();
        if (_pane != Pane.Review) return;
        var importable = _plan?.Groups.Sum(g => g.Setups.Count) ?? 0;
        var (label, enabled) = ImportLogic.ImportAllButton(_planning, _planFailed, _plan is { CanApply: true }, importable, Busy);
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
        if (_pane != Pane.Review || !FooterPrimary.IsEnabled) return;
        if (_planFailed)
        {
            _ = ReplanAsync();
            return;
        }
        if (_plan is { CanApply: true } plan) StartBulk(plan);
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

    // A setup on its own brings what its version brings (the same switches), into the version the player picks.
    private ImportOptions SingleOptions(DetectedInstance setup) => _options with
    {
        CopyWorlds = true, CopyScreenshots = true, BringByVersion = null,
        LeaveOut = ImportCategories.All & ~_model.BringFor(ImportFlowModel.KeyOf(setup)),
    };

    private void OpenSingle(DetectedInstance setup)
    {
        if (IsApplying) return;
        if (_pane is Pane.Version or Pane.Review) _singleFrom = _pane;
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
        foreach (var id in _bars.Keys.Where(k => k.StartsWith("s|", StringComparison.Ordinal)).ToList()) _bars.Remove(id);
        SingleBring.Content = NewBar("s|" + ImportFlowModel.KeyOf(setup));

        ShowPane(Pane.Single);
        SetSingleHeader();
        SetFooter(null, "DETAILS", () => OpenDetails(), "IMPORT", () => { if (FooterPrimary.IsEnabled) StartSingle(); });
        TargetNote.Visibility = Visibility.Collapsed;
        WantFocus();
        _ = LoadTargetsAsync(setup);
    }

    private void SetSingleHeader()
    {
        if (_single == null) return;
        SetHeader(_single.Name, GroupTitle(_single.SourceLabel) + (_single.McVersion == null ? "" : " - " + _single.McVersion));
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
            // The picker already says "Pick a version": the note only says why.
            text = _single.McVersion == null ? "Version unknown." : $"{_single.McVersion} isn't supported.";
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
        var options = SingleOptions(source);
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
        var items = new List<RowVm> { new ImportSectionVm { Id = "coming", Title = "COMING OVER" } };
        var stats = new List<CardRowVm>();
        void Stat(string id, string label, int count, bool always = false)
        {
            if (count > 0 || always) stats.Add(new ImportStatVm { Id = id, Label = label, Value = N(count) });
        }
        var copied = plan.Mods.Count(m => m.IsCopied);
        var builtIn = plan.Mods.Count(m => m.Outcome == ModOutcome.BuiltIn);
        stats.Add(new ImportStatVm
        {
            Id = "mods", Label = "Mods",
            Value = N(copied) + (builtIn > 0 ? $" ({builtIn} already in Origin)" : ""),
        });
        Stat("settings", "Settings files", plan.Ops.Count(o => o.Item is PlanItemKind.Setting or PlanItemKind.Config));
        Stat("resource", "Resource packs", plan.Ops.Count(o => o.Item == PlanItemKind.ResourcePack));
        Stat("shader", "Shader packs", plan.Ops.Count(o => o.Item == PlanItemKind.ShaderPack));
        Stat("worlds", "Worlds", plan.Ops.Count(o => o.Item == PlanItemKind.World));
        Stat("screenshots", "Screenshots", plan.Ops.Count(o => o.Item == PlanItemKind.Screenshot));
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
            var rows = attention.Select((l, i) => new ImportLineVm { Id = "a" + i, Title = l.Name, Warning = ImportLogic.StatusLine(l.Reason) }).ToList<CardRowVm>();
            AssignPos(rows);
            items.AddRange(rows);
        }
        PageList.ItemsSource = items;
    }

    // ---------------------------------------------------------------- details

    private void OpenDetails()
    {
        if (_pane is not (Pane.Review or Pane.Single or Pane.Done)) return;
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
    }

    private void BuildDetails()
    {
        var items = new List<RowVm>();
        if (_detailsFrom != Pane.Done)
        {
            // Worlds and screenshots are "What to bring" switches now; these are the rest.
            items.Add(new ImportSectionVm { Id = "options", Title = "OPTIONS" });
            _optionRows = new List<ImportOptionVm>
            {
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
            case Pane.Review: AddBulkPlanDetails(items); break;
            case Pane.Single: AddSinglePlanDetails(items); break;
            default: AddResultDetails(items); break;
        }
        PageList.ItemsSource = items;
    }

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
                    Caption = ImportLogic.RoleCaption(planned.Role, mods),
                    // One status line: the Forge caption, else the detector's full "not sure" reason (the row has the short form).
                    Warning = planned.Role == SetupRole.PacksOnly ? BulkRules.ForgeCaption
                        : BulkRules.IsVersionUncertain(planned.Setup) ? BulkRules.UncertainReason(planned.Setup) : null,
                });
            }
            AssignPos(rows);
            items.AddRange(rows);
        }
        var skipped = _plan.Skipped.Where(_model.Shows).ToList();
        if (skipped.Count > 0)
        {
            items.Add(new ImportSectionVm { Id = "skipped", Title = SkippedTitle, Count = skipped.Count, Gold = true });
            var rows = skipped.Select(s => new ImportLineVm { Id = "k" + s.Setup.BulkId(), Title = s.Setup.Name, Caption = Describe(s.Setup, 0), Warning = s.Reason }).ToList<CardRowVm>();
            AssignPos(rows);
            items.AddRange(rows);
        }
    }

    // Every item the plan knows about, with what happens and why: nothing is dropped without a line here.
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
                var landed = version.Setups.Where(x => x.Kind is SetupResultKind.Imported or SetupResultKind.ImportedPartly).ToList();
                if (landed.Count == 0) continue;
                items.Add(new ImportSectionVm { Id = "v" + version.Version, Title = $"MINECRAFT {version.Version}", Count = landed.Count });
                var rows = landed.Select(x => new ImportLineVm
                {
                    Id = "r" + x.Setup.BulkId(), Title = x.Setup.Name, Caption = ImportLogic.RoleCaption(x.Role, null),
                    Warning = x.Role == SetupRole.PacksOnly ? BulkRules.ForgeCaption : null,
                }).ToList<CardRowVm>();
                AssignPos(rows);
                items.AddRange(rows);
            }
            AddLostRows(items, "dlost", collapsible: false);
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

    // The one name for what didn't come over, on every page that lists it after a run (Done, Done's details).
    private const string SkippedTitle = "LEFT OUT";

    // "LEFT OUT - n": every setup or item that didn't come over, one row each (where it was, then the reason).
    private void AddLostRows(List<RowVm> items, string id, bool collapsible)
    {
        if (_done is not { Lost.Count: > 0 } m) return;
        items.Add(new ImportSectionVm
        {
            Id = id, Title = SkippedTitle, Count = m.Lost.Count, Gold = true, Collapsible = collapsible,
            Collapsed = collapsible && _lostCollapsed,
        });
        if (collapsible && _lostCollapsed) return;
        var rows = m.Lost.Select((l, i) => new ImportLineVm { Id = id + i, Title = l.Title, Caption = l.Caption, Warning = l.Reason }).ToList<CardRowVm>();
        AssignPos(rows);
        items.AddRange(rows);
    }

    private void Option_Click(object sender, RoutedEventArgs e)
    {
        if (_optionRows.Count != 4) return;
        bool On(string id) => _optionRows.First(o => o.Id == id).IsOn;
        _options = _options with
        {
            CopyUnusedPacks = On("packs"),
            KeepMyOriginSettings = On("keep"),
            ReplaceServerList = On("servers"),
            ReplaceExistingCaches = On("terrain"),
        };
        // Re-plan whatever is behind this page; the page itself is rebuilt when the new plan lands.
        if (_detailsFrom == Pane.Review) _ = ReplanAsync();
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
            case Pane.Versions:
                ShowLaunchers();
                break;
            case Pane.Version:
                ShowVersions();
                WantFocus();
                break;
            case Pane.Review:
                ShowVersions(); // a plan in flight keeps going: Review reuses it if nothing changes
                WantFocus();
                break;
            case Pane.Chooser:
                ReturnTo(_chooserFrom);
                break;
            case Pane.Single:
                _singleSeq++;
                try { _singleCts?.Cancel(); } catch (ObjectDisposedException) { }
                _singlePlanning = false;
                StopTickIfIdle();
                StopSpin(PlanSpin);
                ReturnTo(_singleFrom);
                break;
            case Pane.Details:
                BackFromDetails();
                break;
        }
    }

    private void ReturnTo(Pane pane)
    {
        if (pane == Pane.Version && _versionKey != null) OpenVersion(_versionKey);
        else OpenReview();
    }

    private void BackFromDetails()
    {
        switch (_detailsFrom)
        {
            case Pane.Single when _single != null:
                ShowPane(Pane.Single);
                SetSingleHeader();
                if (_singlePlan != null) RenderSinglePlan(_singlePlan);
                else PageList.ItemsSource = new List<RowVm>();
                PlanningRow.Visibility = V(_singlePlanning);
                SetFooter(null, "DETAILS", () => OpenDetails(), "IMPORT", () => { if (FooterPrimary.IsEnabled) StartSingle(); });
                break;
            case Pane.Done:
                RenderDone();
                break;
            default:
                OpenReview();
                break;
        }
    }

    // ---------------------------------------------------------------- Browse

    private void Browse_Click(object sender, RoutedEventArgs e) => _ = BrowseAsync();

    private async Task BrowseAsync()
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog { Title = "Pick a Minecraft setup folder" };
        if (dialog.ShowDialog(Window.GetWindow(this)) != true || string.IsNullOrWhiteSpace(dialog.FolderName)) return;
        await AddFolderAsync(dialog.FolderName);
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

    private void BeginApply(string caption)
    {
        _barFraction = 0;
        _stopping = null;
        SetBar(0);
        ProgressSetup.Text = "";
        ProgressDetail.Text = "";
        _applyProgress.Last = null;
        _singleProgress.Last = null;
        ShowPane(Pane.Importing);
        SetHeader("Importing", caption);
        SetFooter(null, "CANCEL", () => _ = AskCancelAsync(), null, null);
        _tick.Start();
    }

    // The callers check IsApplying / Busy first; the method itself starts with the run marker, nothing before it.
    private async Task RunBulkAsync(BulkImportPlan plan)
    {
        using var run = ImportHooks.EnterRun();
        var cts = new CancellationTokenSource();
        _applyCts = cts;
        _lastRunSingle = false;
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
            // Drop the field before disposing so STOP can never cancel a disposed source.
            if (ReferenceEquals(_applyCts, cts)) _applyCts = null;
            cts.Dispose();
            IsApplying = false;
            UpdateAllowed();
            StopTickIfIdle();
        }
        if (_disposed) return;
        // The Done page lists only what was part of this import: another launcher's setups never were.
        result = _model.ForDone(result);
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
            // Drop the field before disposing so STOP can never cancel a disposed source.
            if (ReferenceEquals(_applyCts, cts)) _applyCts = null;
            cts.Dispose();
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
        if (cancel && IsApplying)
        {
            _stopping = "Stopping...";
            HeaderSubtitle.Text = _stopping;
            HeaderSubtitle.Visibility = Visibility.Visible;
            FooterSecondary.IsEnabled = false; // one STOP is enough: the engine is putting everything back
            _applyCts?.Cancel();
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
                if (_stopping == null) HeaderSubtitle.Text = $"Setup {b.SetupIndex + 1} of {b.SetupCount}";
                ProgressSetup.Text = b.SetupLabel;
                ProgressDetail.Text = StageText(b.Inner.Stage) + (string.IsNullOrEmpty(b.Inner.Item) ? "" : ": " + b.Inner.Item);
            }
            else if (_singleProgress.Last is { } p)
            {
                fraction = p.Stage == ImportStage.Finishing ? 1 : Inner(p);
                if (_stopping == null) HeaderSubtitle.Text = "Setup 1 of 1";
                ProgressSetup.Text = _single == null ? "" : $"{_single.SourceLabel}: {_single.Name}";
                ProgressDetail.Text = StageText(p.Stage) + (string.IsNullOrEmpty(p.Item) ? "" : ": " + p.Item);
            }
            if (fraction is { } f)
            {
                _barFraction = Math.Max(_barFraction, Math.Clamp(f, 0, 1)); // never backwards when a step counts its own files
                SetBar(_barFraction);
            }
        }
        else if (_pane == Pane.Review && _planning && _planProgress.Last is { } plan && plan.Total > 0)
        {
            HeaderSubtitle.Text = $"Checking {Math.Min(plan.Done + 1, plan.Total)} of {plan.Total}...";
            HeaderSubtitle.Visibility = Visibility.Visible;
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

    private void ProgressTrack_SizeChanged(object sender, SizeChangedEventArgs e) => SetBar(_barFraction);

    private void SetBar(double fraction)
    {
        var inner = Math.Max(0, ProgressTrack.ActualWidth - 2);
        ProgressFill.Width = inner * fraction;
        ProgressPercent.Text = Math.Round(fraction * 100).ToString("0", CultureInfo.InvariantCulture) + "% overall";
    }

    // ---------------------------------------------------------------- 5. done / nothing changed

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
        var items = new List<RowVm> { new ImportTitleVm { Sub = "Find them in Profiles." } };
        if (m.VersionLines.Count > 0) items.Add(new ImportSectionVm { Id = "came", Title = "CAME OVER", Count = m.VersionLines.Count });
        var lines = m.VersionLines.Select(v => new ImportLineVm { Id = "v" + v.Version, Title = "Minecraft " + v.Version, Caption = v.Text }).ToList<CardRowVm>();
        AssignPos(lines);
        items.AddRange(lines);
        AddLostRows(items, "lost", collapsible: true);
        PageList.ItemsSource = items;
        ShowPane(Pane.Done);
        SetHeader("Done", $"{ImportLogic.Plural(m.Brought, "setup", "setups")} brought over");
        SetFooter(null, "DETAILS", () => OpenDetails(), "CLOSE", () => CloseRequested?.Invoke(this, EventArgs.Empty));
        WantFocus();
    }

    private void ShowFailed(ImportDoneModel m)
    {
        ShowPane(Pane.Failed);
        SetHeader("Import", "Nothing changed");
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
        WantFocus();
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
        if (_model.SelectedVersionCount > 0) OpenReview();
        else ShowLaunchers();
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

    /// <summary>MainWindow's Esc: a prompt closes first, a running import asks, a page with a back arrow goes back, otherwise the drawer closes.</summary>
    public bool HandleEscape()
    {
        if (Confirm.Visibility == Visibility.Visible)
        {
            Confirm.Dismiss();
            return true;
        }
        if (CanGoBack(_pane) && !IsApplying)
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
        // Alt+Left goes back, like a browser.
        if (e.Key == Key.System && e.SystemKey == Key.Left && CanGoBack(_pane) && !IsApplying
            && Confirm.Visibility != Visibility.Visible)
        {
            e.Handled = true;
            GoBack();
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
        StopSpin(ReviewSpin);
        StopSpin(PlanSpin);
    }

    /// <summary>Cancels a scan, a count, a plan, a browse and stops the timer. Never cancels an apply: that is the engine's rollback to run.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _tick.Stop();
        StopSpinners();
        if (!IsApplying) Confirm.Dismiss();
        foreach (var cts in new[] { _scanCts, _countCts, _planCts, _browseCts, _singleCts })
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

    /// <summary>Opens a launcher's versions (null: every launcher), as a tile press would.</summary>
    internal void TourOpenLauncher(SourceKind? kind) => OpenLauncher(kind);

    internal void TourOpenVersion(string key) => OpenVersion(key);

    internal void TourOpenReview() => OpenReview();

    /// <summary>Ticks or unticks one version, as its tick box would.</summary>
    internal void TourSetVersion(string key, bool on)
    {
        SetVersionOn(key, on);
        RefreshVersions();
    }

    internal void TourToggleAllVersions() => ToggleAllVersions();

    /// <summary>Switches a "what to bring" category for every version, as the all-versions chip would.</summary>
    internal void TourBringAll(ImportCategories category, bool on)
    {
        _model.SetBringAll(category, on);
        RefreshBars();
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

    internal void TourSecondary() => _footerSecondary?.Invoke();

    internal void TourPrimaryFooter() => _footerPrimary?.Invoke();

    internal void TourAddFolder(string folder) => _ = AddFolderAsync(folder);

    /// <summary>Scrolls whichever list the page shows.</summary>
    internal void TourScroll(double offset) => ScrollOf(CurrentList())?.ScrollToVerticalOffset(offset);

    internal void TourRefusal(string? sentence) => SetRefusal(sentence);

    private ItemsControl CurrentList() => _pane switch
    {
        Pane.Launchers => LaunchersList,
        Pane.Versions => VersionsList,
        Pane.Version => VersionList,
        Pane.Review => ReviewList,
        _ => PageList,
    };

    /// <summary>Puts focus on the first version row (the render then presses Tab, which lands on that row's tick box).</summary>
    internal void TourFocusFirstRow()
    {
        if (FindFirst<Button>(VersionsList, b => b.DataContext is VersionRowVm) is { } row) row.Focus();
    }

    /// <summary>Puts focus on the footer's secondary button (the render then presses Tab, which lands on the primary).</summary>
    internal void TourFocusFooterSecondary() => FooterSecondary.Focus();

    /// <summary>The first version row's tick box and the footer's primary: where the two focus shots expect the Tab to land.</summary>
    internal FrameworkElement? TourFirstTick() =>
        FindFirst<CheckBox>(VersionsList, c => c.DataContext is VersionRowVm);

    internal FrameworkElement TourFooterPrimary() => FooterPrimary;

    private static T? FindFirst<T>(DependencyObject root, Func<T, bool> match) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is T t && match(t)) return t;
            if (FindFirst(child, match) is { } deeper) return deeper;
        }
        return null;
    }

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
