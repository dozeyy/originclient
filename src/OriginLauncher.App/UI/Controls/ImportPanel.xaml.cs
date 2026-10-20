using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Documents;
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

// The Import page (MainWindow shows it in place of the page behind it, under the top bar). "Easiest import from
// another launcher ever", in Origin's look:
//
//   Launchers = Detect (a tile per launcher found: logo, folder, counts, a tick; the rest in one slim row)
//     -> Import everything: plans in the background, then straight to Importing (Review only when it can't go)
//     -> Pick ("Choose what to import": a card per launcher, a row per setup, ticks at both levels) -> Import selected
//     -> Versions ("What to bring" once for all, one row per Minecraft version)
//       -> Version (what this version brings, which setups, whose settings)       [side page]
//       -> Review (what comes over, what is left out and why) -> Import
//   Importing (one big bar, a row per setup, cancel) -> Done (what came over, the new profiles) / Nothing changed
//   side pages behind Back: whose settings (Chooser), one setup on its own (Single), Details.
//
// The choices live in ImportFlowModel (pure, tested); the engine gets them as a BulkSelection plus ImportOptions.
// All work is the backend's (scan, count, plan, apply: each async, cancellable, off the UI thread); this file only
// shows what it returns. Rules it keeps:
//  * nothing starts until the page opens (the scan is NOT part of launcher startup);
//  * the newest action wins: closing cancels a scan, count or plan in flight; a plan that finishes after a newer one
//    was asked for is dropped; Browse has its own token, so it never cancels the main scan;
//  * rows that stay on screen while the player ticks change in place (the list never rebuilds under the pointer);
//  * the plan is the authority on what comes over: Review is drawn from it, never from the choices alone;
//  * while an import is applying, ImportRunState says so (Home keeps Play disabled), Esc and the window's close
//    ask before cancelling, and a cancel puts everything back (the engine's rollback);
//  * progress is shown from a 100 ms timer reading the latest report, never one UI hop per file.
public partial class ImportPanel : UserControl, IDisposable, IImportCloseHost
{
    public enum Pane { Launchers, Grid, Pick, Versions, Version, Review, Chooser, Single, Details, Importing, Done, Failed }

    /// <summary>What the page does as soon as the scan is in: just show it (Detect), import everything, or open the pick list.</summary>
    public enum Intent { Detect, Everything, Choose }

    /// <summary>The page wants to go away (Failed's Close, Esc on the first page).</summary>
    public event EventHandler? CloseRequested;

    /// <summary>
    /// "Play" on Done: MainWindow selects this Minecraft version on Home and closes the page. Imports make no profiles, so
    /// the plain version is what plays.
    /// </summary>
    public event EventHandler<string>? PlayRequested;

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
    private readonly Dictionary<SourceKind, PickGroupVm> _pickGroups = new();
    private readonly Dictionary<string, PickRowVm> _pickRows = new(StringComparer.OrdinalIgnoreCase);
    private string? _pickKeys;
    private List<ProgressRowVm> _progressRows = new();
    private readonly Dictionary<string, VersionRowVm> _versionRows = new(StringComparer.Ordinal);
    private readonly Dictionary<string, SetupRowVm> _versionSetupRows = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, CategoryBarVm> _bars = new(StringComparer.Ordinal);
    private SelectAllVm? _selectAll;
    private ImportNoteVm? _versionNote;
    private string? _tileKeys;
    private string? _versionsKey;

    private ImportFlowModel _model = NewModel(Array.Empty<DetectedInstance>(), Array.Empty<string>());

    // "Bring it all over": worlds come too unless the player switches them off in What to bring.
    private static ImportFlowModel NewModel(IEnumerable<DetectedInstance> setups, IEnumerable<string> targets) =>
        new(setups, targets) { BringByDefault = ImportCategories.All };

    private Intent _pending = Intent.Detect; // what to do when the scan lands
    private bool _autoStart;                 // Import pressed: start as soon as the plan for exactly this selection lands
    private BulkImportPlan? _runPlan;        // the plan being (or last) applied: Importing's rows and Done's profiles read it
    private Pane _versionsFrom = Pane.Launchers;
    private Pane _pickFrom = Pane.Launchers;   // Pick single setups opens from Detect's grid or the grid's own link
    private string? _gridRowKeys;
    private List<GridRowVm> _gridRows = new();
    private List<GridTabVm> _gridTabs = new();
    private Pane _reviewFrom = Pane.Versions;

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
            if (e.NewFocus is not (Button { Name: "CancelButton" or "BrowseButton" or "BackButton" or "BringButton" })) _wantPrimaryFocus = false;
        }), true);
        ShowLaunchers();
    }

    // ---------------------------------------------------------------- hooks other code calls

    /// <summary>MainWindow, when the page opens: starts the scan, then does what the player asked for. Never called at launcher startup.</summary>
    public void Start(Intent intent = Intent.Detect)
    {
        if (_started || _disposed) return;
        _started = true;
        _pending = intent;
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

        var model = NewModel(scan.Instances, targets);
        model.Add(_model.Setups.Where(s => s.Kind == SourceKind.Folder)); // a folder Browse found while the scan ran
        _model = model;
        _scanNote = scan.Notes.Count > 0 ? scan.Notes[0] : null;
        _scanning = false;
        _listChanged = true;
        RefreshCurrent();
        UpdateScanSpinner();
        _ = CountAsync();
        ApplyPending();
    }

    // What the player asked for when the page opened (the Home card's buttons), now that there is something to act on;
    // otherwise the plan for the default selection is prepared in the background so Import everything starts at once.
    private void ApplyPending()
    {
        var intent = _pending;
        _pending = Intent.Detect;
        if (_pane != Pane.Launchers || _model.Setups.Count == 0) return;
        if (intent == Intent.Choose) ShowPick();
        else if (intent == Intent.Everything && _model.GoingSetups().Count > 0) ImportNow();
        else PrefetchPlan();
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
        // The count can re-pick the grid's Mods default: plan the new ticks now, so Import is ready at once.
        if (_pane == Pane.Grid) PrefetchPlan();
    }

    // Brings whatever page is showing up to date with the model (a count landed, a folder was added).
    private void RefreshCurrent()
    {
        switch (_pane)
        {
            case Pane.Launchers: RenderLaunchers(); break;
            case Pane.Grid: RenderGrid(); break;
            case Pane.Pick: RenderPick(); break;
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
        DetectPane.Visibility = V(pane == Pane.Launchers);
        PickList.Visibility = V(pane == Pane.Pick);
        GridPane.Visibility = V(pane == Pane.Grid);
        Legacy.Visibility = V(pane is Pane.Versions or Pane.Version or Pane.Review or Pane.Chooser or Pane.Single or Pane.Details);
        VersionsList.Visibility = V(pane == Pane.Versions);
        VersionList.Visibility = V(pane == Pane.Version);
        ReviewPane.Visibility = V(pane == Pane.Review);
        PageGrid.Visibility = V(pane is Pane.Chooser or Pane.Single or Pane.Details);
        SingleTop.Visibility = V(pane == Pane.Single);
        ProgressPane.Visibility = V(pane == Pane.Importing);
        DonePane.Visibility = V(pane == Pane.Done);
        FailedPane.Visibility = V(pane == Pane.Failed);
        HeaderBlock.Visibility = V(pane != Pane.Done); // Done carries its own title in its card
        BackButton.Visibility = V(CanGoBack(pane));
        BringButton.Visibility = V(pane is Pane.Launchers or Pane.Pick);
        BrowseButton.Visibility = V(pane is Pane.Launchers or Pane.Pick or Pane.Versions);
        CancelButton.Visibility = V(pane == Pane.Importing);
        if (pane is not Pane.Single) PlanningRow.Visibility = Visibility.Collapsed;
        if (pane is not Pane.Review) ShowReviewPlanning(false);
        if (pane is not (Pane.Launchers or Pane.Pick or Pane.Versions)) _flash = null;
        UpdateScanSpinner();
        RefreshNotice();
    }

    private static bool CanGoBack(Pane pane) =>
        pane is Pane.Grid or Pane.Pick or Pane.Versions or Pane.Version or Pane.Review or Pane.Chooser or Pane.Single or Pane.Details;

    private void UpdateScanSpinner()
    {
        var on = _scanning && _pane == Pane.Launchers;
        ScanSpinner.Visibility = V(on);
        if (on) StartSpin(ScanSpin); else StopSpin(ScanSpin);
    }

    // The header: the poster title, an optional line or two under it, and an optional status line (a dot, or a spinner
    // while looking, and a few words). The page below starts at the same place on every page.
    /// <param name="compact">The grid page: a smaller poster title (the dialog-title size) and the line close under it, so the grid gets the room.</param>
    private void SetHeader(string title, string? subtitle, string? status = null, bool dot = true, bool compact = false)
    {
        HeaderTitle.Text = title.ToUpperInvariant();
        if (compact)
        {
            HeaderTitle.FontSize = (double)FindResource("FontSize.DialogTitle");
            HeaderTitle.LineHeight = HeaderTitle.FontSize;
            HeaderSubtitle.Margin = new Thickness(0, 4, 0, 0);
        }
        else
        {
            HeaderTitle.ClearValue(TextBlock.FontSizeProperty);
            HeaderTitle.ClearValue(TextBlock.LineHeightProperty);
            HeaderSubtitle.ClearValue(MarginProperty);
        }
        HeaderSubtitle.Text = subtitle ?? "";
        HeaderSubtitle.Visibility = V(!string.IsNullOrEmpty(subtitle));
        StatusText.Text = status ?? "";
        StatusRow.Visibility = V(!string.IsNullOrEmpty(status));
        StatusDot.Visibility = V(dot && !_scanning);
        UpdateScanSpinner();
    }

    // The footer's note on the Detect page: the other launchers are only read, never changed.
    private const string FooterCopiesNote = "Your other launchers are only read, never changed";

    /// <summary>
    /// The footer: a note on the left (**bold** marks the numbers), up to two buttons on the right. Button words from the
    /// engine and the older pages are caps ("IMPORT 12"): they are set in sentence case here, once.
    /// </summary>
    private void SetFooter(string? note, string? secondaryText, Action? secondary, string? primaryText, Action? primary, bool icon = false)
    {
        SetRich(FooterNote, note ?? "");
        FooterNote.Visibility = V(!string.IsNullOrEmpty(note));
        FooterSecondary.Content = secondaryText == null ? null : ImportLogic.Sentence(secondaryText);
        FooterSecondary.Visibility = V(secondaryText != null);
        PrimaryText.Text = primaryText == null ? "" : ImportLogic.Sentence(primaryText);
        PrimaryIcon.Visibility = V(icon);
        FooterPrimary.Visibility = V(primaryText != null);
        _footerSecondary = secondary;
        _footerPrimary = primary;
        Footer.Visibility = V(note != null || secondaryText != null || primaryText != null);
        _primary = primaryText != null ? FooterPrimary : null;
        UpdateFooterEnabled();
    }

    private void SetPrimaryText(string text) => PrimaryText.Text = ImportLogic.Sentence(text);

    // A line with **bold** parts (the numbers in "17 setups, 54 worlds and 320 mods ready to import").
    private void SetRich(TextBlock block, string text)
    {
        block.Inlines.Clear();
        var bold = false;
        foreach (var part in text.Split("**"))
        {
            if (part.Length > 0)
            {
                var run = new Run(part);
                if (bold)
                {
                    run.FontWeight = FontWeights.Bold;
                    run.SetResourceReference(TextElement.ForegroundProperty, "Brush.Text");
                }
                block.Inlines.Add(run);
            }
            bold = !bold;
        }
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
            case Pane.Launchers:
            case Pane.Pick:
                // Import needs something going, no refusal and no start already on its way; Choose is always open.
                // (Nothing found: the primary is "Add a folder", always open.)
                FooterPrimary.IsEnabled = _model.Setups.Count == 0 || (!Busy && !_autoStart && _model.GoingSetups().Count > 0);
                FooterSecondary.IsEnabled = !_autoStart;
                break;
            case Pane.Grid:
                FooterPrimary.IsEnabled = !Busy && !_autoStart && _model.GridModel is { BringsNothing: false };
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
        if (Busy && _pane is Pane.Launchers or Pane.Grid or Pane.Pick or Pane.Versions or Pane.Version or Pane.Review or Pane.Single or Pane.Failed)
            text = BusyReason;
        else if (_pane == Pane.Review && _planFailed)
            text = "Couldn't check your setups.";
        else if (_pane == Pane.Review && !_planning && _plan is { Blocked.Count: > 0 } plan)
            text = string.Join(" ", plan.Blocked);
        else if (_pane == Pane.Single && _singlePlan is { Blocked.Count: > 0 } single)
            text = string.Join(" ", single.Blocked);
        else if (_pane is Pane.Launchers or Pane.Pick or Pane.Versions && _flash != null)
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

    // ---------------------------------------------------------------- 1. detect

    private void ShowLaunchers()
    {
        ShowPane(Pane.Launchers);
        RenderLaunchers(rebuild: true);
        WantFocus();
    }

    // The launchers found, as tiles (logo, folder, three counts, a tick); the ones that weren't found as one slim row.
    // The tiles change in place while the scan and the count land; the list is rebuilt only when a tile comes or goes
    // (Add a folder makes "Picked folders").
    private void RenderLaunchers(bool rebuild = false)
    {
        var tiles = _model.Tiles(_scanning);
        var found = tiles.Where(t => t.State == LauncherState.Found).ToList();
        var missing = tiles.Where(t => t.State is LauncherState.NotInstalled or LauncherState.Empty).ToList();
        var nothing = !_scanning && found.Count == 0;
        var keys = string.Join(",", found.Select(t => t.Kind));
        if (rebuild || keys != _tileKeys)
        {
            _tiles.Clear();
            var list = new List<LauncherTileVm>();
            foreach (var tile in found)
            {
                var vm = new LauncherTileVm { Kind = tile.Kind, Title = tile.Title };
                _tiles[tile.Kind] = vm;
                list.Add(vm);
            }
            TilesList.ItemsSource = list;
            _tileKeys = keys;
        }
        foreach (var tile in found)
            if (_tiles.TryGetValue(tile.Kind, out var vm)) Fill(vm, tile);

        MissingBar.Visibility = V(!_scanning && missing.Count > 0);
        if (!_scanning && missing.Count > 0)
        {
            MissingLogos.ItemsSource = missing.Take(8).Select(t => new MissingLauncherVm { Kind = t.Kind, Title = t.Title }).ToList();
            SetRich(MissingText, MissingSentence(missing, _model.Installed != null));
        }
        ScanNote.Text = _scanning ? "" : _scanNote ?? "";
        ScanNote.Visibility = V(!_scanning && !string.IsNullOrEmpty(_scanNote));
        if (_pane != Pane.Launchers) return;

        var launchers = found.Count(t => t.Kind != SourceKind.Folder);
        if (_scanning)
            SetHeader("Bring it all over", "Looking through your other launchers...", "Looking...", dot: false);
        else if (nothing)
            SetHeader("Bring it all over", "Nothing found to import. Add a setup's folder, or play once in your other launcher first.",
                $"Scan complete - {ImportFlowModel.TileOrder.Count} launchers checked");
        else
            SetHeader("Bring it all over",
                (launchers > 0 ? $"Found {ImportLogic.Plural(launchers, "launcher", "launchers")} on this PC. " : "Found the folder you picked. ")
                + "Your settings, mods, worlds and servers come straight into Origin, no profiles to set up. The other launchers are never changed.",
                $"Scan complete - {ImportFlowModel.TileOrder.Count} launchers checked");

        if (_scanning) SetFooter(FooterCopiesNote, null, null, null, null);
        else if (nothing) SetFooter(FooterCopiesNote, null, null, "Add a folder", () => _ = BrowseAsync());
        else if (_model.LaunchersWithSetups() is { Count: 1 } only)
        {
            // One other launcher: the one-click path is the page's main button.
            var kind = only[0];
            SetFooter(ReadyNote(_model.FastSetups(kind), "ready to import"), "Choose what to import", ShowGrid,
                _autoStart ? "Checking..." : "Bring everything from " + ImportGridModel.ShortName(kind), () => FastImport(kind), icon: !_autoStart);
        }
        else
        {
            SetFooter(ReadyNote(_model.GoingSetups(), "ready to import"), "Choose what to import", ShowGrid,
                _autoStart ? "Checking..." : "Import everything", ImportNow, icon: !_autoStart);
        }
        if (_autoStart) ShowChecking();
        TryFocusPrimary();
    }

    // "MultiMC wasn't found on this PC" / "Lunar Client, Feather Client and 3 more weren't found on this PC".
    // A launcher that is installed but empty says so instead ("has nothing to import").
    internal static string MissingSentence(IReadOnlyList<LauncherTile> missing, bool installedKnown)
    {
        var names = missing.Select(t => "**" + t.Title + "**").ToList();
        var list = ImportFlowModel.JoinFew(names);
        var many = missing.Count > 1;
        var allNot = !installedKnown || missing.All(t => t.State == LauncherState.NotInstalled);
        var allEmpty = installedKnown && missing.All(t => t.State == LauncherState.Empty);
        var verb = allNot ? (many ? "weren't found on this PC" : "wasn't found on this PC")
            : allEmpty ? (many ? "have nothing to import" : "has nothing to import")
            : "have nothing to import on this PC";
        return $"{list} {verb}";
    }

    // "17 setups, 54 worlds and 320 mods ready to import": what is going, with **bold** numbers (SetRich).
    private string ReadyNote(IReadOnlyList<DetectedInstance> going, string verb)
    {
        if (going.Count == 0) return "Nothing is ticked yet";
        static string Part(int n, string one, string many) => $"**{n}** {(n == 1 ? one : many)}";
        var parts = new List<string> { Part(going.Count, "setup", "setups") };
        if (_model.Counted(going))
        {
            var worlds = _model.Total(going, ImportCategories.Worlds);
            var mods = _model.Total(going, ImportCategories.Mods);
            if (worlds > 0) parts.Add(Part(worlds, "world", "worlds"));
            if (mods > 0) parts.Add(Part(mods, "mod", "mods"));
        }
        return ImportLogic.JoinParts(parts) + " " + verb;
    }

    private void Fill(LauncherTileVm vm, LauncherTile tile)
    {
        var mine = _model.Setups.Where(s => s.Kind == tile.Kind).ToList();
        var counted = _model.Counted(mine);
        vm.Caption = tile.Caption;
        vm.Enabled = true;
        vm.NotFound = false;
        vm.PathText = ImportLogic.ShortPath(ImportLogic.CommonFolder(mine.Select(s => s.GameDir).ToList()));
        vm.SetupsText = N(mine.Count);
        vm.WorldsText = counted ? N(_model.Total(mine, ImportCategories.Worlds)) : "...";
        vm.ModsText = counted ? N(_model.Total(mine, ImportCategories.Mods)) : "...";
        vm.CanTick = mine.Any(_model.CanGo);
        vm.CanFast = vm.CanTick;
        vm.Ticked = _model.GoingState(mine);
        vm.Tip = vm.CanTick ? "Press to take " + tile.Title + " in or out of Import everything"
            : "Origin can't run any of these versions yet";
        vm.Resync();
    }

    // A press on the tile ticks it (a tile is a switch for "Import everything"); the tick box is the keyboard stop.
    private void Tile_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (IsWithin<ButtonBase>(e.OriginalSource, (DependencyObject)sender)) return; // the tick's (or Bring everything's) own click handles it
        if (Row<LauncherTileVm>(sender) is { CanTick: true } tile) ToggleTile(tile);
    }

    // "Bring everything from <launcher>": every setup of that launcher, nothing from the others.
    private void TileFast_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<LauncherTileVm>(sender) is { } tile) FastImport(tile.Kind);
    }

    private void TileTick_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<LauncherTileVm>(sender) is { } tile) ToggleTile(tile);
    }

    // All of a launcher's setups in or out. Some in -> all in (a press never leaves the answer half-way).
    private void ToggleTile(LauncherTileVm tile)
    {
        if (!Allowed || _autoStart || !tile.CanTick)
        {
            tile.Resync();
            return;
        }
        var mine = _model.Setups.Where(s => s.Kind == tile.Kind).ToList();
        var on = _model.GoingState(mine) != true;
        foreach (var setup in mine) _model.SetGoing(setup, on);
        RenderLaunchers();
        PrefetchPlan();
    }

    private static bool IsWithin<T>(object? source, DependencyObject stop) where T : DependencyObject
    {
        for (var d = source as DependencyObject; d != null && !ReferenceEquals(d, stop);
             d = d is Visual ? VisualTreeHelper.GetParent(d) : LogicalTreeHelper.GetParent(d))
        {
            if (d is T) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Import everything / Import selected

    // One press: the plan for exactly what is going is made (it was usually made already, in the background) and the
    // import starts the moment it lands. Only a plan that can't go (disk space, nothing to import, a failed check) stops at
    // Review, which says why.
    private void ImportNow()
    {
        if (!CanStartImport() || _model.GoingSetups().Count == 0) return;
        _model.UseTicks();
        StartImport();
    }

    // The one-click path: every setup of one launcher, its most played setup's settings on every version (the engine's
    // "same settings everywhere"), its most played setup's mods per version, the union of its packs, worlds and servers.
    private void FastImport(SourceKind launcher)
    {
        if (!CanStartImport() || _model.FastSetups(launcher).Count == 0) return;
        _model.UseFast(launcher);
        StartImport();
    }

    // Import on the grid: who brings what per version, as ticked.
    private void ImportFromGrid()
    {
        if (!CanStartImport() || _model.GridModel is not { BringsNothing: false }) return;
        _model.UseGrid();
        StartImport();
    }

    private bool CanStartImport() =>
        _pane is Pane.Launchers or Pane.Grid or Pane.Pick && !_autoStart && !_scanning
        && ImportLogic.CheckStart(IsApplying, Busy ? BusyReason : null) == ImportLogic.StartKind.Start;

    private void StartImport()
    {
        var key = _model.SelectionKey();
        var options = _model.Options(_options);
        if (_plan is { } ready && !_listChanged && _planKey == key && Equals(_planOptions, options))
        {
            if (ready.CanApply) StartBulk(ready);
            else OpenReview();
            return;
        }
        _autoStart = true;
        ShowChecking();
        if (_planning && !_listChanged && _planningKey == key && Equals(_planningOptions, options)) return; // on its way
        _ = ReplanAsync();
    }

    // The page while the plan for Import is made: the button says so, nothing else can be pressed.
    private void ShowChecking()
    {
        StatusText.Text = "Checking what can come over...";
        StatusRow.Visibility = Visibility.Visible;
        StatusDot.Visibility = Visibility.Collapsed;
        SetPrimaryText("Checking...");
        PrimaryIcon.Visibility = Visibility.Collapsed;
        UpdateFooterEnabled();
    }

    // ---------------------------------------------------------------- 1a. choose what to import: the grid

    // One screen for someone mixing launchers: a row per launcher, a tick per thing, version tabs when there is more than
    // one version. Everything lands in plain Origin; no profile is made. The grid is made once and kept while the page
    // lives, so Back and a second visit keep the ticks.
    private void ShowGrid()
    {
        if (_scanning || _model.Setups.Count == 0 || _autoStart) return;
        _model.UseGrid();
        ShowPane(Pane.Grid);
        RenderGrid(rebuild: true);
        WantFocus();
        PrefetchPlan();
    }

    private void RenderGrid(bool rebuild = false)
    {
        if (_model.GridModel is not { } grid) return;

        if (rebuild || _gridTabs.Count != grid.Tabs.Count)
        {
            _gridTabs = grid.Tabs.Select(t => new GridTabVm { Tab = t, Title = ImportGridModel.TabTitle(t) }).ToList();
            GridTabs.ItemsSource = _gridTabs;
            GridHeads.ItemsSource = ImportGridModel.Columns.Select(c => ImportGridModel.ColumnTitle(c).ToUpperInvariant()).ToList();
        }
        GridTabs.Visibility = V(grid.ShowTabs);
        foreach (var tab in _gridTabs)
        {
            tab.Selected = tab.Tab == grid.Tab;
            tab.Resync();
        }

        var kinds = grid.Rows();
        var keys = grid.Tab + "|" + string.Join(",", kinds);
        if (rebuild || keys != _gridRowKeys)
        {
            _gridRows = kinds.Select(kind => new GridRowVm
            {
                Kind = kind, Title = ImportFlowModel.LauncherTitle(kind),
                Cells = ImportGridModel.Columns.Select(c => new GridCellVm { Kind = kind, Column = c }).ToList(),
            }).ToList();
            AssignPos(_gridRows.ToList<CardRowVm>());
            GridRows.ItemsSource = _gridRows;
            _gridRowKeys = keys;
        }
        foreach (var row in _gridRows)
        {
            row.Caption = grid.RowCaption(row.Kind);
            foreach (var cell in row.Cells)
            {
                var state = grid.Cell(row.Kind, cell.Column);
                cell.On = state.On;
                cell.Enabled = state.Enabled;
                cell.Tip = state.Tip;
                cell.Name = $"{row.Title}: {ImportGridModel.ColumnTitle(cell.Column)}";
                cell.Resync();
            }
        }

        var warning = grid.Warning();
        GridWarning.Text = warning ?? "";
        GridWarningRow.Visibility = V(warning != null);
        if (_pane != Pane.Grid) return;

        SetHeader("Choose what to import", "Tick what to bring over.", compact: true);
        SetFooter(GridNote(grid), null, null, _autoStart ? "Checking..." : "Import", ImportFromGrid, icon: !_autoStart);
        if (_autoStart) ShowChecking();
        TryFocusPrimary();
    }

    // "Settings from **Prism / MultiMC**" (one launcher for every version); "Origin keeps its own settings" with none.
    private static string GridNote(ImportGridModel grid)
    {
        if (grid.BringsNothing) return "Nothing is ticked yet";
        return grid.SettingsLauncher is { } kind
            ? $"Settings from **{ImportFlowModel.LauncherTitle(kind)}**"
            : "Origin keeps its own settings";
    }

    private void GridCell_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<GridCellVm>(sender) is not { } cell || _model.GridModel is not { } grid) return;
        if (!Allowed || _autoStart || !cell.Enabled)
        {
            cell.Resync();
            return;
        }
        grid.Toggle(cell.Kind, cell.Column);
        RenderGrid();
        UpdateFooterEnabled();
        PrefetchPlan();
    }

    private void GridTab_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<GridTabVm>(sender) is not { } tab || _model.GridModel is not { } grid) return;
        grid.SetTab(tab.Tab);
        RenderGrid();
    }

    private void GridSetups_Click(object sender, RoutedEventArgs e) => ShowPick();

    // ---------------------------------------------------------------- 1b. choose what to import

    private void ShowPick()
    {
        if (_scanning || _model.Setups.Count == 0 || _autoStart) return;
        if (_pane is Pane.Launchers or Pane.Grid) _pickFrom = _pane;
        _model.UseTicks();
        ShowPane(Pane.Pick);
        RenderPick(rebuild: true);
        WantFocus();
    }

    // A card per launcher: its tick, logo, name and counts; under it a row per setup (tick, name, what it holds, its
    // Minecraft version). Ticks change the rows in place; the list is rebuilt only when the setups themselves change.
    private void RenderPick(bool rebuild = false)
    {
        var groups = _model.Tiles(false).Where(t => t.State == LauncherState.Found).ToList();
        var keys = string.Join(",", _model.Setups.Select(s => s.BulkId()));
        if (rebuild || keys != _pickKeys)
        {
            _pickGroups.Clear();
            _pickRows.Clear();
            var items = new List<RowVm>();
            foreach (var tile in groups)
            {
                var mine = _model.Setups.Where(s => s.Kind == tile.Kind).ToList();
                var group = new PickGroupVm
                {
                    Kind = tile.Kind, Title = tile.Title, Pos = CardPos.First,
                    Gap = items.Count == 0 ? new Thickness(0) : new Thickness(0, 16, 0, 0),
                };
                _pickGroups[tile.Kind] = group;
                items.Add(group);
                for (var i = 0; i < mine.Count; i++)
                {
                    var row = new PickRowVm { Setup = mine[i], CanTick = _model.CanGo(mine[i]), Pos = i == mine.Count - 1 ? CardPos.Last : CardPos.Middle };
                    _pickRows[row.Id] = row;
                    items.Add(row);
                }
            }
            PickList.ItemsSource = items;
            _pickKeys = keys;
        }

        foreach (var (id, row) in _pickRows)
        {
            row.Caption = PickCaption(row.Setup);
            row.Ticked = _model.IsGoing(row.Setup);
            row.Resync();
        }
        foreach (var tile in groups)
        {
            if (!_pickGroups.TryGetValue(tile.Kind, out var group)) continue;
            var mine = _model.Setups.Where(s => s.Kind == tile.Kind).ToList();
            var parts = new List<string> { $"{mine.Count(_model.IsGoing)} of {mine.Count} selected" };
            if (_model.Counted(mine))
            {
                var worlds = _model.Total(mine, ImportCategories.Worlds);
                var mods = _model.Total(mine, ImportCategories.Mods);
                if (worlds > 0) parts.Add(ImportLogic.Plural(worlds, "world", "worlds"));
                if (mods > 0) parts.Add(ImportLogic.Plural(mods, "mod", "mods"));
            }
            group.Caption = string.Join(" - ", parts);
            group.CanTick = mine.Any(_model.CanGo);
            group.State = _model.GoingState(mine);
            group.Resync();
        }
        if (_pane != Pane.Pick) return;

        SetHeader("Pick single setups",
            "Tick the setups to bring. Each one goes into Origin's own folder for its Minecraft version, no profiles. Setups on a version Origin can't run yet are listed, but stay out.");
        SetFooter(ReadyNote(_model.GoingSetups(), "selected"), null, null, _autoStart ? "Checking..." : "Import selected", ImportNow, icon: !_autoStart);
        if (_autoStart) ShowChecking();
        TryFocusPrimary();
    }

    // "23 mods, 3 worlds" once counted; a setup Origin can't run says why instead.
    private string PickCaption(DetectedInstance setup)
    {
        if (!_model.CanGo(setup))
            return ImportFlowModel.KeyOf(setup) == ImportFlowModel.UnknownKey
                ? "Origin couldn't tell which Minecraft version this is"
                : $"Origin can't run {setup.McVersion} yet";
        if (!_model.Counted(new[] { setup })) return "Counting...";
        var line = _model.ContentsLine(new[] { setup });
        return string.IsNullOrEmpty(line) ? "Settings only" : line;
    }

    private void GroupTick_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<PickGroupVm>(sender) is not { } group) return;
        if (!Allowed || _autoStart || !group.CanTick)
        {
            group.Resync();
            return;
        }
        var mine = _model.Setups.Where(s => s.Kind == group.Kind).ToList();
        var on = _model.GoingState(mine) != true;
        foreach (var setup in mine) _model.SetGoing(setup, on);
        RenderPick();
        PrefetchPlan();
    }

    private void PickTick_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<PickRowVm>(sender) is { } row) TogglePick(row);
    }

    // A press on the row ticks it; a setup Origin can't run opens its own page, to pick a version for it.
    private void PickRow_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (IsWithin<ButtonBase>(e.OriginalSource, (DependencyObject)sender)) return; // the tick or the arrow handled it
        if (Row<PickRowVm>(sender) is not { } row) return;
        if (row.CanTick) TogglePick(row);
        else OpenSingle(row.Setup);
    }

    private void PickReview_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<PickRowVm>(sender) is { } row) OpenSingle(row.Setup);
    }

    private void TogglePick(PickRowVm row)
    {
        if (!Allowed || _autoStart || !row.CanTick)
        {
            row.Resync();
            return;
        }
        _model.SetGoing(row.Setup, !_model.IsGoing(row.Setup));
        RenderPick();
        PrefetchPlan();
    }

    // "What to bring" (the header button): what each version brings, and one row per Minecraft version.
    private void Bring_Click(object sender, RoutedEventArgs e)
    {
        if (_scanning || _model.Setups.Count == 0 || _autoStart) return;
        _versionsFrom = _pane;
        OpenLauncher(null);
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
        SetHeader(_model.Scope == null ? "What to bring" : ScopeTitle, _model.Summary(_model.InScope.ToList()));
        SetFooter(supported == 0 ? null : $"{selected} of {ImportLogic.Plural(supported, "version", "versions")}",
            null, null, "Review", () => OpenReview());
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

    // reuseFailure: Import everything's own plan failed or can't go; Review shows that instead of planning the same thing again.
    private void OpenReview(bool reuseFailure = false)
    {
        if (_model.SelectedVersionCount == 0) return;
        if (_pane is Pane.Launchers or Pane.Grid or Pane.Pick or Pane.Versions) _reviewFrom = _pane;
        ShowPane(Pane.Review);
        SetHeader("Review", ReviewSubtitle());
        SetFooter(null, "DETAILS", () => OpenDetails(), "IMPORT", ImportAll);
        var key = _model.SelectionKey();
        var options = _model.Options(_options);
        if (reuseFailure && _planFailed && !_planning && _planKey == key)
        {
            ReviewList.ItemsSource = new List<RowVm> { new HintVm { Id = "fail", Pos = CardPos.Only, Text = "Couldn't check your setups. Press TRY AGAIN." } };
        }
        else if (_plan != null && !_listChanged && _planKey == key && Equals(_planOptions, options))
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
        FinishAutoStart(plan);
    }

    // Import everything / Import selected was pressed before the plan landed: it starts the moment the plan can go. A plan
    // that can't (disk space, nothing to import, a failed check) or a refusal (a game starting) opens Review, which says why.
    private void FinishAutoStart(BulkImportPlan? plan)
    {
        if (!_autoStart) return;
        _autoStart = false;
        if (_pane is not (Pane.Launchers or Pane.Grid or Pane.Pick))
        {
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
            Text = "Each version lands in Origin's own folder for it, ready to play from Home. No profiles are made. Your other launchers are only read.",
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
        if (_pane is Pane.Version or Pane.Review or Pane.Pick) _singleFrom = _pane;
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
        if (_optionRows.Count != 3) return;
        bool On(string id) => _optionRows.First(o => o.Id == id).IsOn;
        _options = _options with
        {
            CopyUnusedPacks = On("packs"),
            KeepMyOriginSettings = On("keep"),
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
            case Pane.Grid:
                ShowLaunchers();
                break;
            case Pane.Pick:
                if (_pickFrom == Pane.Grid) ShowGrid(); else ShowLaunchers();
                break;
            case Pane.Versions:
                if (_versionsFrom == Pane.Pick) ShowPick(); else ShowLaunchers();
                break;
            case Pane.Version:
                ShowVersions();
                WantFocus();
                break;
            case Pane.Review:
                // a plan in flight keeps going: the next Import reuses it if nothing changes
                if (_reviewFrom == Pane.Pick) ShowPick();
                else if (_reviewFrom == Pane.Grid) ShowGrid();
                else if (_reviewFrom == Pane.Launchers) ShowLaunchers();
                else
                {
                    ShowVersions();
                    WantFocus();
                }
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
        else if (pane == Pane.Pick) ShowPick();
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

    private const string ImportingNote = "Your worlds, mods and settings are being copied into Origin. Your other launchers are only read, never changed.";

    // One row per setup (in the plan's order, which is BulkProgress.SetupIndex's): Done before the current one, Waiting after.
    private void BeginApply(IEnumerable<DetectedInstance> setups)
    {
        _barFraction = 0;
        _stopping = null;
        _activeRow = -1;
        SetBar(0);
        ProgressSetup.Text = "";
        ProgressDetail.Text = "";
        _applyProgress.Last = null;
        _singleProgress.Last = null;
        _progressRows = setups.Select(s => new ProgressRowVm { Setup = s }).ToList();
        ProgressList.ItemsSource = _progressRows;
        CancelButton.IsEnabled = true;
        ShowPane(Pane.Importing);
        SetHeader("Importing", ImportingNote);
        SetFooter(null, null, null, null, null);
        _tick.Start();
    }

    private int _activeRow = -1;
    private ImportPlan? _runSingle; // the single import being (or last) applied: Done's profile card reads it

    // The callers check IsApplying / Busy first; the method itself starts with the run marker, nothing before it.
    private async Task RunBulkAsync(BulkImportPlan plan)
    {
        using var run = ImportHooks.EnterRun();
        var cts = new CancellationTokenSource();
        _applyCts = cts;
        _lastRunSingle = false;
        _runPlan = plan;
        _runSingle = null;
        BeginApply(plan.AllSetups.Select(p => p.Setup));
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
        _runPlan = null;
        _runSingle = plan;
        BeginApply(new[] { plan.Source });
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
            CancelButton.IsEnabled = false; // one STOP is enough: the engine is putting everything back
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
                ProgressSetup.Text = b.SetupLabel;
                ProgressDetail.Text = $"{Math.Min(b.SetupIndex + 1, b.SetupCount)} of {ImportLogic.Plural(b.SetupCount, "setup", "setups")}";
                UpdateProgressRows(b.SetupIndex, b.SetupCount, b.Inner);
            }
            else if (_singleProgress.Last is { } p)
            {
                fraction = p.Stage == ImportStage.Finishing ? 1 : Inner(p);
                ProgressSetup.Text = _single == null ? "" : $"{_single.SourceLabel}: {_single.Name}";
                ProgressDetail.Text = "1 of 1 setup";
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
                HeaderSubtitle.Text = text;
                HeaderSubtitle.Visibility = Visibility.Visible;
            }
            else if (_autoStart && _pane is Pane.Launchers or Pane.Grid or Pane.Pick)
            {
                StatusText.Text = text;
            }
        }
    }

    // The rows follow the engine's report: finished ones tick, the current one shows its step and a small bar, the rest wait.
    private void UpdateProgressRows(int index, int count, ImportProgress inner)
    {
        if (_progressRows.Count != count) return;
        for (var i = 0; i < count; i++)
        {
            var row = _progressRows[i];
            if (i < index)
            {
                row.State = ProgressRowVm.Phase.Done;
                row.Status = "Done";
                row.Fraction = 1;
            }
            else if (i == index)
            {
                row.State = ProgressRowVm.Phase.Active;
                row.Fraction = Inner(inner);
                row.Status = StageText(inner.Stage) + (inner.Total > 1 && inner.Stage != ImportStage.Finishing ? $" {Math.Min(inner.Done, inner.Total)}/{inner.Total}" : "");
            }
            else
            {
                row.State = ProgressRowVm.Phase.Waiting;
                row.Status = "Waiting";
                row.Fraction = 0;
            }
        }
        if (index != _activeRow)
        {
            _activeRow = index;
            // keep the current row on screen (rows are all the same height, so the share of the extent is its place)
            if (ScrollOf(ProgressList) is { ScrollableHeight: > 0 } scroll)
                scroll.ScrollToVerticalOffset(Math.Max(0, (index - 1) * scroll.ExtentHeight / count));
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
        ProgressPercent.Text = Math.Round(fraction * 100).ToString("0", CultureInfo.InvariantCulture);
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

    // "You're all set": what came over (counts from the plan of what was applied), a card per version it went to, a line
    // that the other launchers were not touched, and a line for what was left out. Only real data: a zero is not shown.
    private void RenderDone()
    {
        if (_done is not { } m) return;

        // The setups that landed, with their plans.
        var landed = new List<(DetectedInstance Setup, ImportPlan Plan)>();
        if (_bulkResult is { } bulk && _runPlan is { } run)
        {
            var ids = new HashSet<string>(bulk.Versions.SelectMany(v => v.Setups)
                .Where(s => s.Kind is SetupResultKind.Imported or SetupResultKind.ImportedPartly)
                .Select(s => s.Setup.BulkId()), StringComparer.OrdinalIgnoreCase);
            landed.AddRange(run.AllSetups.Where(p => ids.Contains(p.Setup.BulkId())).Select(p => (p.Setup, p.Plan)));
        }
        else if (_runSingle is { } single && _singleResult is { Succeeded: true })
        {
            landed.Add((single.Source, single));
        }

        var worlds = landed.Sum(l => l.Plan.Ops.Count(o => o.Item == PlanItemKind.World));
        var mods = landed.Sum(l => l.Plan.Mods.Count(x => x.IsCopied));

        // The versions that got something, the one played most first (Play opens it): imports make no profiles.
        var versions = DoneVersionList(landed);
        _playVersion = versions.FirstOrDefault().Version;

        var stats = new List<ImportStatVm>
        {
            new() { Id = "versions", Label = versions.Count == 1 ? "VERSION" : "VERSIONS", Value = N(versions.Count) },
        };
        if (worlds > 0) stats.Add(new ImportStatVm { Id = "worlds", Label = worlds == 1 ? "WORLD" : "WORLDS", Value = N(worlds) });
        if (mods > 0) stats.Add(new ImportStatVm { Id = "mods", Label = mods == 1 ? "MOD" : "MODS", Value = N(mods) });
        DoneStats.ItemsSource = stats;

        var cards = versions.Take(MaxVersionCards)
            .Select(v => new VersionCardVm
            {
                Name = "Minecraft " + v.Version,
                Caption = "From " + ImportFlowModel.JoinFew(v.Launchers),
                Kind = v.SettingsFrom,
            }).ToList();
        if (versions.Count > cards.Count)
            cards.Add(new VersionCardVm { Name = $"+{versions.Count - cards.Count} more", Caption = "", IsMore = true });
        DoneVersions.ItemsSource = cards;
        DoneVersionsTitle.Visibility = V(cards.Count > 0);
        DoneVersions.Visibility = V(cards.Count > 0);

        DoneNotes.Children.Clear();
        DoneNotes.Children.Add(NoteLine("Nothing was changed in your other launchers. They still work exactly as before.", "Import.Icon.Shield", muted: false));
        foreach (var lost in m.Lost.Take(3))
            DoneNotes.Children.Add(NoteLine(LostSentence(lost), "Icon.Globe", muted: false));
        if (m.Lost.Count > 3)
            DoneNotes.Children.Add(NoteLine($"and {m.Lost.Count - 3} more left out: open Details to see them.", "Icon.Globe", muted: true));

        DoneNotes.Children.Add(DetailsLink());

        ShowPane(Pane.Done);
        if (_playVersion is { } play)
            SetFooter($"Everything is in Origin, no profile needed. **Play** starts Minecraft {play}.",
                "Done", () => CloseRequested?.Invoke(this, EventArgs.Empty), "Play", () => PlayRequested?.Invoke(this, play));
        else
            SetFooter(null, null, null, "Done", () => CloseRequested?.Invoke(this, EventArgs.Empty));
        WantFocus();
    }

    // At most this many version cards, then one "+N more" card (two rows of four).
    private const int MaxVersionCards = 7;

    // The version Play on Done opens: the most played version that got something (null before a result).
    private string? _playVersion;

    // Per version that landed: its launchers (by title, tile order) and whose settings it got (the group's winner).
    // The most played first (the playtime of its most played setup), then the newest.
    private List<(string Version, IReadOnlyList<string> Launchers, SourceKind SettingsFrom)> DoneVersionList(
        IReadOnlyList<(DetectedInstance Setup, ImportPlan Plan)> landed)
    {
        var list = new List<(string Version, IReadOnlyList<string> Launchers, SourceKind SettingsFrom, long Played)>();
        foreach (var byVersion in landed.Where(l => !string.IsNullOrEmpty(l.Plan.TargetVersion)).GroupBy(l => l.Plan.TargetVersion!))
        {
            var setups = byVersion.Select(l => l.Setup).ToList();
            var winner = _runPlan?.Groups.FirstOrDefault(g => g.Version == byVersion.Key)?.Winner ?? setups[0];
            var launchers = setups.Select(s => s.Kind).Distinct()
                .OrderBy(k => ImportFlowModel.TileOrder.ToList().IndexOf(k))
                .Select(k => ImportFlowModel.LauncherTitle(k)).ToList();
            list.Add((byVersion.Key, launchers, winner.Kind, setups.Max(s => s.PlaytimeSeconds ?? 0)));
        }
        return list.OrderByDescending(v => v.Played).ThenBy(v => v.Version, BulkRanking.NewestFirst)
            .Select(v => (v.Version, v.Launchers, v.SettingsFrom)).ToList();
    }

    // Details stays one press away on Done (the footer holds Done and Play).
    private UIElement DetailsLink()
    {
        var button = new Button
        {
            Content = "See what came over",
            Style = (Style)FindResource("Import.HeaderButton"),
            HorizontalAlignment = HorizontalAlignment.Left,
            Margin = new Thickness(-12, 6, 0, 0),
            ToolTip = "Every file that came over, and what was left out",
        };
        System.Windows.Automation.AutomationProperties.SetName(button, "See what came over");
        button.Click += (_, _) => OpenDetails();
        return button;
    }

    // "RLCraft redux (Prism - 1.20.6): Origin doesn't support this version yet."
    private static string LostSentence((string Title, string? Caption, string Reason) lost)
    {
        var where = string.IsNullOrEmpty(lost.Caption) ? "" : $" ({lost.Caption})";
        var reason = lost.Reason.TrimEnd();
        if (reason.Length > 0 && !".!?".Contains(reason[^1])) reason += ".";
        return $"{lost.Title}{where}: {reason}";
    }

    // One quiet line with a small icon (the shield, the globe) under the profile cards.
    private UIElement NoteLine(string text, string icon, bool muted)
    {
        var row = new Grid { Margin = new Thickness(0, 6, 0, 6) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var path = new System.Windows.Shapes.Path
        {
            Style = (Style)FindResource("Icon.Base"),
            Data = (Geometry)FindResource(icon),
            Width = 16, Height = 16, Margin = new Thickness(0, 2, 12, 0), VerticalAlignment = VerticalAlignment.Top,
        };
        path.SetResourceReference(System.Windows.Shapes.Shape.StrokeProperty, "Brush.TextDim");
        row.Children.Add(path);
        var block = new TextBlock
        {
            Text = text, Style = (Style)FindResource("Text.Body"), TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.None,
        };
        block.SetResourceReference(TextBlock.ForegroundProperty, muted ? "Brush.TextMuted" : "Brush.TextDim");
        Grid.SetColumn(block, 1);
        row.Children.Add(block);
        return row;
    }

    private void ShowFailed(ImportDoneModel m)
    {
        ShowPane(Pane.Failed);
        SetHeader("Import", "Nothing changed");
        // Red only when it really failed; a stop the player asked for is neutral.
        var stopped = m.Cancelled && string.IsNullOrEmpty(m.Error);
        var accent = (Brush)FindResource(stopped ? "Brush.TextMuted" : "Brush.Danger");
        FailedPane.BorderBrush = stopped ? (Brush)FindResource("Brush.Stroke") : accent;
        FailedBadge.Background = accent;
        FailedTitle.Foreground = stopped ? (Brush)FindResource("Brush.TextDim") : accent;
        FailedTitle.Text = stopped ? "Import stopped" : "Import failed";
        FailedReason.Text = stopped ? "You stopped it." : string.IsNullOrEmpty(m.Error) ? "The import didn't finish." : m.Error;
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
        ShowLaunchers();
        if (_model.GoingSetups().Count > 0) ImportNow();
    }

    // ---------------------------------------------------------------- buttons, keys

    // The header's Cancel while importing: asks "Stop importing?" first.
    private void Cancel_Click(object sender, RoutedEventArgs e) => _ = AskCancelAsync();

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

    /// <summary>"Choose what to import": the grid, as the footer button would.</summary>
    internal void TourShowGrid() => ShowGrid();

    /// <summary>A press on one grid tick (a launcher's row, a column), on the current tab.</summary>
    internal void TourGridToggle(SourceKind kind, GridColumn column)
    {
        if (_model.GridModel is not { } grid) return;
        grid.Toggle(kind, column);
        RenderGrid();
        UpdateFooterEnabled();
    }

    /// <summary>Turns one grid tick fully on (presses it until it reads on; a greyed one is left alone).</summary>
    internal void TourGridTurnOn(SourceKind kind, GridColumn column)
    {
        if (_model.GridModel is not { } grid) return;
        for (var i = 0; i < 2 && grid.Cell(kind, column) is { Enabled: true, On: not true }; i++) grid.Toggle(kind, column);
        RenderGrid();
        UpdateFooterEnabled();
    }

    /// <summary>The grid as text (tab, each row's cells and tips, the warning) for the shot run's evidence file.</summary>
    internal string TourGridDump()
    {
        if (_model.GridModel is not { } grid) return "no grid";
        var sb = new System.Text.StringBuilder();
        var shown = grid.Tab;
        foreach (var tab in grid.Tabs.DefaultIfEmpty(grid.Tab))
        {
            grid.SetTab(tab);
            sb.AppendLine("[" + ImportGridModel.TabTitle(tab) + "]");
            foreach (var kind in grid.Rows())
            {
                sb.Append("  ").Append(kind).Append(": ");
                foreach (var col in ImportGridModel.Columns)
                {
                    var c = grid.Cell(kind, col);
                    sb.Append(col).Append('=').Append(c.On switch { true => "on", false => "off", null => "some" })
                      .Append(c.Enabled ? "" : "(grey: " + c.Tip + ")").Append("  ");
                }
                sb.AppendLine();
            }
            sb.AppendLine("  warning: " + (grid.Warning() ?? "none"));
        }
        grid.SetTab(shown);
        return sb.ToString();
    }

    /// <summary>A version tab of the grid ("all" = All versions).</summary>
    internal void TourGridTab(string tab)
    {
        _model.GridModel?.SetTab(tab);
        RenderGrid();
    }

    /// <summary>"Bring everything from" a launcher's tile.</summary>
    internal void TourFastImport(SourceKind kind) => FastImport(kind);

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
    internal void TourScroll(double offset)
    {
        if (_pane == Pane.Launchers) DetectPane.ScrollToVerticalOffset(offset);
        else if (_pane == Pane.Done) DonePane.ScrollToVerticalOffset(offset);
        else ScrollOf(CurrentList())?.ScrollToVerticalOffset(offset);
    }

    /// <summary>"Choose what to import", as the footer's button opens it.</summary>
    internal void TourShowPick() => ShowPick();

    /// <summary>"Import everything" / "Import selected", as the footer's primary does.</summary>
    internal void TourImportNow() => ImportNow();

    /// <summary>A launcher's tile (or the whole launcher in Choose) in or out of the import, as its tick does.</summary>
    internal void TourToggleLauncher(SourceKind kind)
    {
        var mine = _model.Setups.Where(s => s.Kind == kind).ToList();
        var on = _model.GoingState(mine) != true;
        foreach (var setup in mine) _model.SetGoing(setup, on);
        RefreshCurrent();
    }

    /// <summary>The header's "What to bring" button.</summary>
    internal void TourBring() => Bring_Click(this, new RoutedEventArgs());

    /// <summary>Opens a single review from the pick list (the arrow on a setup row).</summary>
    internal void TourOpenPickReview(string name)
    {
        if (_model.Setups.FirstOrDefault(s => s.Name == name) is { } setup) OpenSingle(setup);
    }

    internal void TourRefusal(string? sentence) => SetRefusal(sentence);

    private ItemsControl CurrentList() => _pane switch
    {
        Pane.Launchers => TilesList,
        Pane.Pick => PickList,
        Pane.Importing => ProgressList,
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
