using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using Avalonia.VisualTree;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;
using TileState = OriginLauncher.App.UI.Controls.LauncherState;

namespace OriginLauncher.Desktop.Overlays;

// The Import page (the Avalonia twin of the WPF UI/Controls/ImportPanel): ImportView shows it in the sheet layer, over
// the page behind and under the top bar. Same flow, same words, same rules as the Windows page:
//
//   Launchers (step 1: a tile per launcher found) -> Scope (step 2: everything, or choose) -> Grid / Pick / Versions /
//   Version / Chooser / Single / Details -> Review (step 3) -> Importing -> Done / Failed (step 4).
//
// The choices live in ImportFlowModel / ImportGridModel and the words in ImportLogic (the WPF launcher's own files,
// linked, never copied); all work is the backend's (scan, count, plan, apply: async, cancellable, off the UI thread).
// Rules it keeps (the WPF page's):
//  * nothing starts until the page opens; closing cancels a scan, count or plan in flight;
//  * the newest action wins: a plan that lands after a newer one was asked for is dropped;
//  * the plan is the authority on what comes over: Review is drawn from it, never from the choices alone;
//  * while an import applies, ImportRunState says so (Home keeps Play off), Esc asks before cancelling, and a cancel
//    puts everything back (the engine's rollback);
//  * progress is shown from a 100 ms timer reading the latest report, never one UI hop per file.
// Unlike the WPF page there are no row view models: a pane is rebuilt from the model after each change (keyboard
// focus is carried over by the focused control's name), and only the Importing rows change in place.
internal sealed partial class ImportPage : UserControl
{
    public enum Pane { Launchers, Scope, Grid, Pick, Versions, Version, Review, Chooser, Single, Details, Importing, Done, Failed }

    /// <summary>What the page does as soon as the scan is in: just show it, import everything, or open the grid.</summary>
    public enum Intent { Detect, Everything, Choose }

    public event Action? CloseRequested;
    public event Action<ImportPlayRequest>? PlayRequested;
    public event Action? OpenProfilesRequested;
    public event Action? Imported;

    private const string AllBar = "all";
    private const string PickSettings = "Pick settings";
    private const string FooterCopiesNote = "Your other launchers are only read, never changed";

    private static string? NotSureNote(DetectedInstance i) =>
        i.McVersion != null && BulkRules.IsVersionUncertain(i) ? BulkRules.UncertainCaption : null;

    private readonly MainWindow _window;
    private readonly IImportBackend _backend;
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private readonly Latest<BulkProgress> _applyProgress = new();
    private readonly Latest<ImportProgress> _singleProgress = new();
    private readonly Latest<BulkPlanProgress> _planProgress = new();

    private ImportFlowModel _model = NewModel(Array.Empty<DetectedInstance>(), Array.Empty<string>());

    private static ImportFlowModel NewModel(IEnumerable<DetectedInstance> setups, IEnumerable<string> targets) =>
        new(setups, targets) { BringByDefault = ImportCategories.All };

    private Intent _pending = Intent.Detect;
    private bool _autoStart;
    private BulkImportPlan? _runPlan;
    private SourceKind? _picked;
    private ImportVerification? _report;
    private Pane _versionsFrom = Pane.Launchers;
    private Pane _pickFrom = Pane.Launchers;
    private Pane _reviewFrom = Pane.Versions;

    private CancellationTokenSource? _scanCts, _countCts, _planCts, _browseCts, _singleCts, _applyCts;
    private int _planSeq, _singleSeq;
    private bool _disposed, _started;
    private bool _scanning = true;
    private bool _planning, _planFailed, _singlePlanning;
    private string? _refusal;
    private bool _lostCollapsed, _lastRunSingle;
    private double _barFraction;
    private string? _stopping;

    private Pane _pane = Pane.Launchers;
    private Action? _footerPrimary, _footerSecondary;
    private string? _scanNote, _flash;
    private string? _versionKey, _chooserVersion;
    private Pane _chooserFrom = Pane.Review, _singleFrom = Pane.Review, _detailsFrom = Pane.Review;
    private ImportOptions _options = new();

    private BulkImportPlan? _plan;
    private string? _planKey, _planningKey;
    private ImportOptions? _planOptions, _planningOptions;
    private BulkImportPlan? _lastPlan;
    private bool _listChanged;
    private DetectedInstance? _single;
    private string? _singleTarget;
    private IReadOnlyList<string>? _singleTargets;
    private ImportPlan? _singlePlan;
    private string? _singleFailure;
    private BulkImportResult? _bulkResult;
    private ImportResult? _singleResult;
    private ImportDoneModel? _done;

    // ---- the frame (header, steps, notice, page, footer) ----
    private readonly Fit _column = new();
    private readonly Grid _header = new() { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, 0, 0, Space.S4) };
    private readonly TextBlock _title = Ui.Wrap(Ui.Heading(""));
    private readonly TextBlock _subtitle = Ui.Wrap(Ui.Body("", Palette.TextDim));
    private readonly StackPanel _statusRow = new() { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 14, 0, 0) };
    private readonly Avalonia.Controls.Shapes.Ellipse _statusDot = Ui.Dot(Palette.Accent, 8);
    private readonly LoadingSpinner _statusSpin = new(14) { Brush = Palette.TextDim };
    private readonly TextBlock _statusText = Ui.Caption("", Palette.TextDim);
    private readonly Pressable _bringButton, _browseButton, _backButton, _cancelButton;
    private readonly ContentControl _steps = new() { Margin = new Thickness(0, 0, 0, Space.S5) };
    private readonly Border _notice = new()
    {
        Background = Palette.Card, BorderBrush = Palette.Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(Radius.Md),
        Padding = new Thickness(12, 8), Margin = new Thickness(0, 0, 0, Space.S3), IsVisible = false,
    };
    private readonly TextBlock _noticeText = Ui.Wrap(Ui.Caption("", Palette.Warning));
    private readonly ScrollViewer _scroll = new()
    {
        VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
        HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
        Padding = new Thickness(0, 0, 8, 0),
    };
    private readonly Grid _footer = new() { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, Space.S4, 0, 0), MinHeight = Sizes.ControlMd };
    private readonly TextBlock _footerNote = ImportParts.Rich("", Palette.TextDim, TypeScale.Caption);
    private readonly Pressable _secondary;
    private readonly Pressable _primary;
    private readonly TextBlock _primaryText = Ui.Text("", TypeScale.Body, FontWeight.SemiBold);
    private readonly TextBlock _secondaryText = Ui.Text("", TypeScale.Body, FontWeight.SemiBold);
    private readonly IconView _primaryIcon = Ui.Icon(Icons.Import, Sizes.Icon);
    private readonly Dictionary<Pane, Vector> _offsets = new(); // Back returns to the same scroll position
    private Vector? _pendingOffset;
    private bool _goingBack;

    public bool IsApplying { get; private set; }
    public Pane Current => _pane;
    private bool Busy => _refusal != null;
    private string BusyReason => _refusal ?? "";
    private bool Allowed => ImportLogic.RowControlsAllowed(Busy, IsApplying);
    private string ScopeTitle => _model.Scope is { } kind ? ImportFlowModel.LauncherTitle(kind) : "All launchers";

    public ImportPage(MainWindow window, IImportBackend backend)
    {
        _window = window;
        _backend = backend;
        _tick.Tick += (_, _) => ShowLatestProgress();
        Background = Brushes.Transparent; // the page's presses land here, never on the page behind

        // Header: IMPORT, the poster title, a line or two, a status line; the page's small actions on the right.
        var left = new StackPanel();
        var eyebrow = Ui.Eyebrow("IMPORT", Palette.TextDim);
        eyebrow.Margin = new Thickness(0, 0, 0, 6);
        eyebrow.HorizontalAlignment = HorizontalAlignment.Left;
        left.Children.Add(eyebrow);
        left.Children.Add(_title);
        _subtitle.MaxWidth = 600;
        _subtitle.HorizontalAlignment = HorizontalAlignment.Left;
        _subtitle.Margin = new Thickness(0, 10, 0, 0);
        left.Children.Add(_subtitle);
        _statusDot.Margin = new Thickness(0, 1, 10, 0);
        _statusSpin.Margin = new Thickness(-3, 0, 7, 0);
        _statusSpin.VerticalAlignment = VerticalAlignment.Center;
        _statusText.VerticalAlignment = VerticalAlignment.Center;
        _statusRow.Children.Add(_statusDot);
        _statusRow.Children.Add(_statusSpin);
        _statusRow.Children.Add(_statusText);
        left.Children.Add(_statusRow);
        _header.Children.Add(left.At(0));

        _bringButton = HeaderButton("What to bring", null, "Pick what each version brings over", () => Bring());
        _browseButton = HeaderButton("Add a folder", Icons.Folder, "Point Origin at a setup's folder", () => _ = BrowseAsync());
        _backButton = HeaderButton("Back", null, "Back (Esc)", GoBack);
        _cancelButton = HeaderButton("Cancel", null, "Stop importing", () => _ = AskCancelAsync());
        Avalonia.Automation.AutomationProperties.SetName(_cancelButton, "Cancel import");
        var actions = Ui.Row(Space.S2, _bringButton, _browseButton, _backButton, _cancelButton);
        actions.VerticalAlignment = VerticalAlignment.Top;
        actions.Margin = new Thickness(24, 8, 0, 0);
        _header.Children.Add(actions.At(1));

        var noticeGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        noticeGrid.Children.Add(Ui.Icon(Icons.Alert, Sizes.IconSm, Palette.Warning).Align(HorizontalAlignment.Left, VerticalAlignment.Top).Margin(0, 2, 8, 0).At(0));
        noticeGrid.Children.Add(_noticeText.At(1));
        _notice.Child = noticeGrid;

        // Footer: what is ready on the left, the page's buttons on the right.
        _footerNote.VerticalAlignment = VerticalAlignment.Center;
        _footerNote.Margin = new Thickness(0, 0, Space.S4, 0);
        _footer.Children.Add(_footerNote.At(0));
        _secondary = new Pressable(Look.Secondary, _secondaryText)
        {
            Height = Sizes.ControlLg, Padding = new Thickness(14, 0), CornerRadius = new CornerRadius(Radius.Md),
            VerticalAlignment = VerticalAlignment.Center,
        }.Center();
        _secondary.Click += (_, _) => _footerSecondary?.Invoke();
        _primary = new Pressable(Look.Primary, Ui.Row(8, _primaryIcon, _primaryText))
        {
            Height = Sizes.ControlLg, Padding = new Thickness(14, 0), CornerRadius = new CornerRadius(Radius.Md), MinWidth = 120,
            VerticalAlignment = VerticalAlignment.Center,
        }.Center();
        _primary.Click += (_, _) => _footerPrimary?.Invoke();
        var buttons = Ui.Row(Space.S2, _secondary, _primary);
        buttons.VerticalAlignment = VerticalAlignment.Center;
        _footer.Children.Add(buttons.At(1));

        _column.Children.Add(_header);
        _column.Children.Add(_steps);
        _column.Children.Add(_notice);
        _column.Children.Add(_scroll);
        _column.Children.Add(_footer);
        _column.Body = _scroll;
        _column.MaxWidth = Sizes.PageMaxWidth;
        _column.HorizontalAlignment = HorizontalAlignment.Left;
        _column.Margin = new Thickness(Space.S8, 52, Space.S8, Space.S6);
        Content = _column;

        ShowLaunchers();
    }

    private static Pressable HeaderButton(string label, string? glyph, string tip, Action click)
    {
        var b = Ui.Button(label, Look.Secondary, glyph, Sizes.ControlMd);
        ToolTip.SetTip(b, tip);
        b.Click += (_, _) => click();
        return b;
    }

    // ---------------------------------------------------------------- hooks other code calls

    /// <summary>ImportView, when the page opens: starts the scan, then does what the player asked for.</summary>
    public void Start(Intent intent = Intent.Detect)
    {
        if (_started || _disposed) return;
        _started = true;
        _pending = intent;
        SetRefusal(ImportHooks.Refusal());
        _ = ScanAsync();
    }

    /// <summary>Why Import can't run right now (a game starting, an update), or null. ImportView pushes every change.</summary>
    public void SetRefusal(string? sentence)
    {
        if (_refusal == sentence) return;
        _refusal = sentence;
        BusyChanged();
    }

    private void BusyChanged()
    {
        RefreshNotice();
        UpdateImportAll();
        RefreshCurrent();
        UpdateFooterEnabled();
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _tick.Stop();
        foreach (var cts in new[] { _scanCts, _countCts, _planCts, _browseCts, _singleCts })
        {
            try { cts?.Cancel(); } catch (ObjectDisposedException) { }
        }
        // An import that is writing keeps going (the window still shows its result next time); ImportView asks first.
    }

    private static CancellationTokenSource Replace(ref CancellationTokenSource? field)
    {
        try { field?.Cancel(); } catch (ObjectDisposedException) { }
        field = new CancellationTokenSource();
        return field;
    }

    // ---------------------------------------------------------------- scan, then count

    private async Task ScanAsync()
    {
        _scanning = true;
        RenderLaunchers();
        var cts = Replace(ref _scanCts);
        var targetsTask = ImportHooks.TargetsAsync(_backend, cts.Token);
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
            targets = scan.Instances.Select(ImportFlowModel.KeyOf).Where(k => k != ImportFlowModel.UnknownKey).Distinct().ToList();
        }
        if (_disposed || cts.IsCancellationRequested) return;

        var model = NewModel(scan.Instances, targets);
        model.AlreadyImported = scan.AlreadyImported.ToList();
        model.Add(_model.Setups.Where(s => s.Kind == SourceKind.Folder));
        _model = model;
        _scanNote = scan.Notes.Count > 0 ? scan.Notes[0] : null;
        _scanning = false;
        _listChanged = true;
        RefreshCurrent();
        _ = CountAsync();
        ApplyPending();
    }

    private void ApplyPending()
    {
        var intent = _pending;
        _pending = Intent.Detect;
        if (_pane != Pane.Launchers || _model.Setups.Count == 0) return;
        if (intent == Intent.Detect || _model.LaunchersWithSetups() is not { Count: 1 } only) return;
        ChooseLauncher(only[0]);
        if (intent == Intent.Choose) ShowGrid();
        else FastImport(only[0]);
    }

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
        if (_pane == Pane.Grid) PrefetchPlan();
    }

    private void RefreshCurrent()
    {
        switch (_pane)
        {
            case Pane.Launchers: RenderLaunchers(); break;
            case Pane.Scope: RenderScope(); break;
            case Pane.Grid: RenderGrid(); break;
            case Pane.Pick: RenderPick(); break;
            case Pane.Versions: RenderVersions(); break;
            case Pane.Version when _versionKey != null: RenderVersion(); break;
            case Pane.Single: RenderSingle(); break;
        }
    }

    // ---------------------------------------------------------------- the frame

    private void ShowPane(Pane pane)
    {
        if (_pane != pane)
        {
            _offsets[_pane] = _scroll.Offset;
            _pendingOffset = _goingBack ? _offsets.GetValueOrDefault(pane) : default;
        }
        _goingBack = false;
        _pane = pane;
        _header.IsVisible = pane != Pane.Done;
        _backButton.IsVisible = CanGoBack(pane);
        _bringButton.IsVisible = pane == Pane.Pick;
        _browseButton.IsVisible = pane is Pane.Launchers or Pane.Pick or Pane.Versions;
        _cancelButton.IsVisible = pane == Pane.Importing;
        _steps.Content = ImportParts.Steps(pane switch
        {
            Pane.Launchers => 1,
            Pane.Review or Pane.Importing => 3,
            Pane.Details => _detailsFrom switch { Pane.Done => 4, Pane.Review => 3, _ => 2 },
            Pane.Done or Pane.Failed => 4,
            _ => 2,
        });
        if (pane is not (Pane.Launchers or Pane.Pick or Pane.Versions)) _flash = null;
        RefreshNotice();
    }

    private static bool CanGoBack(Pane pane) =>
        pane is Pane.Scope or Pane.Grid or Pane.Pick or Pane.Versions or Pane.Version or Pane.Review or Pane.Chooser or Pane.Single or Pane.Details;

    /// <summary>Puts a pane's content on the page; keyboard focus stays on the control of the same name, if it is still there.</summary>
    private void SetBody(Control content)
    {
        var focused = TopLevel.GetTopLevel(this)?.FocusManager?.GetFocusedElement() as Control;
        var name = focused != null && focused.FindAncestorOfType<ScrollViewer>() == _scroll ? focused.Name : null;
        var offset = _pendingOffset ?? _scroll.Offset; // a new pane: its saved place (or the top); a re-render: where it was
        _pendingOffset = null;
        _scroll.Content = content;
        Dispatcher.UIThread.Post(() =>
        {
            _scroll.Offset = offset;
            if (name != null && Find(content, name) is { } again) again.Focus(NavigationMethod.Tab);
        }, DispatcherPriority.Loaded);
    }

    private static Control? Find(Control root, string name)
    {
        if (root.Name == name) return root;
        foreach (var child in root.GetVisualChildren())
            if (child is Control c && Find(c, name) is { } hit) return hit;
        return null;
    }

    private void SetHeader(string title, string? subtitle, string? status = null, bool dot = true, bool compact = false)
    {
        _title.Text = title;
        _subtitle.Text = subtitle ?? "";
        _subtitle.IsVisible = !string.IsNullOrEmpty(subtitle);
        _subtitle.Margin = new Thickness(0, compact ? 4 : 10, 0, 0);
        _statusText.Text = status ?? "";
        _statusRow.IsVisible = !string.IsNullOrEmpty(status);
        var spinning = _scanning && _pane == Pane.Launchers;
        _statusDot.IsVisible = dot && !_scanning;
        _statusSpin.IsVisible = spinning;
    }

    /// <summary>The footer: a note on the left (**bold** numbers), up to two buttons. Engine words come in caps: set in sentence case once.</summary>
    private void SetFooter(string? note, string? secondaryText, Action? secondary, string? primaryText, Action? primary, bool icon = false)
    {
        ImportParts.SetRich(_footerNote, note ?? "");
        _footerNote.IsVisible = !string.IsNullOrEmpty(note);
        _secondaryText.Text = secondaryText == null ? "" : ImportLogic.Sentence(secondaryText);
        _secondary.IsVisible = secondaryText != null;
        _primaryText.Text = primaryText == null ? "" : ImportLogic.Sentence(primaryText);
        _primaryIcon.IsVisible = icon;
        _primary.IsVisible = primaryText != null;
        Avalonia.Automation.AutomationProperties.SetName(_primary, _primaryText.Text);
        Avalonia.Automation.AutomationProperties.SetName(_secondary, _secondaryText.Text);
        _footerSecondary = secondary;
        _footerPrimary = primary;
        _footer.IsVisible = note != null || secondaryText != null || primaryText != null;
        UpdateFooterEnabled();
    }

    private void SetPrimaryText(string text) => _primaryText.Text = ImportLogic.Sentence(text);

    private void UpdateFooterEnabled()
    {
        switch (_pane)
        {
            case Pane.Single:
                _primary.IsEnabled = !Busy && _singlePlan is { CanApply: true } p && !string.IsNullOrEmpty(p.TargetVersion);
                _secondary.IsEnabled = _singlePlan != null;
                break;
            case Pane.Review:
                _secondary.IsEnabled = true; // the primary is UpdateImportAll's
                break;
            case Pane.Versions:
                _primary.IsEnabled = _model.SelectedVersionCount > 0;
                break;
            case Pane.Launchers:
            case Pane.Pick:
                _primary.IsEnabled = _model.Setups.Count == 0 || (!Busy && !_autoStart && _model.GoingSetups().Count > 0);
                _secondary.IsEnabled = !_autoStart;
                break;
            case Pane.Grid:
                _primary.IsEnabled = !Busy && !_autoStart && _model.GridModel is { BringsNothing: false };
                break;
            case Pane.Failed:
                _primary.IsEnabled = !Busy;
                _secondary.IsEnabled = true;
                break;
            case Pane.Importing:
                _secondary.IsEnabled = _stopping == null;
                break;
            default:
                _primary.IsEnabled = true;
                _secondary.IsEnabled = true;
                break;
        }
    }

    private void RefreshNotice()
    {
        string? text = null;
        if (Busy && _pane is Pane.Launchers or Pane.Scope or Pane.Grid or Pane.Pick or Pane.Versions or Pane.Version or Pane.Review or Pane.Single or Pane.Failed)
            text = BusyReason;
        else if (_pane == Pane.Review && _planFailed)
            text = "Couldn't check your setups.";
        else if (_pane == Pane.Review && !_planning && _plan is { Blocked.Count: > 0 } plan)
            text = string.Join(" ", plan.Blocked);
        else if (_pane == Pane.Single && _singlePlan is { Blocked.Count: > 0 } single)
            text = string.Join(" ", single.Blocked);
        else if (_pane is Pane.Launchers or Pane.Pick or Pane.Versions && _flash != null)
            text = _flash;
        _noticeText.Text = text ?? "";
        _notice.IsVisible = !string.IsNullOrEmpty(text);
    }

    private static string N(int n) => Fmt.N(n);

    // ---------------------------------------------------------------- keys

    /// <summary>The page's keys (ImportView routes the window's key presses here while the page is open). True = handled.</summary>
    public bool HandleKey(Key key, KeyModifiers mods)
    {
        if (_window.DialogOpen) return false; // the confirm owns its keys
        if (key == Key.Escape)
        {
            if (IsApplying) _ = AskCancelAsync();
            else if (CanGoBack(_pane)) GoBack();
            else CloseRequested?.Invoke();
            return true;
        }
        if (key == Key.Enter && mods == KeyModifiers.None) return EnterPressed();
        if (key == Key.Left && mods.HasFlag(KeyModifiers.Alt) && CanGoBack(_pane) && !IsApplying)
        {
            GoBack();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- the column

    /// <summary>
    /// The page column: every row at its own height, the page itself (Body, a scroll viewer) at its content's height but
    /// never past what is left, so the footer sits 16 under short content and long lists scroll (the WPF page's
    /// content-sized grid with a star row).
    /// </summary>
    private sealed class Fit : Panel
    {
        public Control? Body { get; set; }

        protected override Size MeasureOverride(Size available)
        {
            double used = 0, width = 0;
            foreach (var child in Children)
            {
                if (ReferenceEquals(child, Body) || !child.IsVisible) continue;
                child.Measure(new Size(available.Width, double.PositiveInfinity));
                used += child.DesiredSize.Height;
                width = Math.Max(width, child.DesiredSize.Width);
            }
            if (Body != null)
            {
                Body.Measure(new Size(available.Width, Math.Max(0, available.Height - used)));
                used += Body.DesiredSize.Height;
                width = Math.Max(width, Body.DesiredSize.Width);
            }
            return new Size(double.IsInfinity(available.Width) ? width : available.Width, used);
        }

        protected override Size ArrangeOverride(Size final)
        {
            double y = 0;
            foreach (var child in Children)
            {
                if (!child.IsVisible) continue;
                var h = child.DesiredSize.Height;
                child.Arrange(new Rect(0, y, final.Width, h));
                y += h;
            }
            return final;
        }
    }

    /// <summary>The newest progress report, read by the 100 ms timer (never one UI hop per file).</summary>
    private sealed class Latest<T> : IProgress<T> where T : class
    {
        private T? _last;
        public T? Last { get => Volatile.Read(ref _last); set => Volatile.Write(ref _last, value); }
        public void Report(T value) => Last = value;
    }
}
