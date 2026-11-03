using System.Collections.ObjectModel;
using System.ComponentModel;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Presenters;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Media.Transformation;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.Mac.UI.Controls;

// The version picker (twin of the WPF UI/Controls/VersionSelectOverlay.xaml.cs; DESIGN-SYSTEM.md 11.3).
//   Step 1  the families as cards (art, range, name, a "2 of 10 installed" line).
//   Step 2  one family, expanded: the clicked card grows out of its grid slot into a centred panel (scale + move,
//           220 ms) while the other cards fade back. The panel holds Back ("All versions"), the family's art, range and
//           name, and ONE dropdown of its releases, newest first, on "Choose a version" until one is picked, with a
//           quiet emerald dot on the ones already on disk. Picking a release picks the version and that is all.
//           A click off the panel or Esc closes the picker to Home; Back shrinks the panel into its card again.
// A family with a single release has no step 2: choosing it picks it and closes.
// The keys, steps and picks are the shared VersionSelectModel.cs (unit-tested on Windows); this file draws what it
// answers. Install states come from InstalledVersions: StateOf is cache-only (never the disk on the UI thread); the
// disk is read by RefreshAllAsync on a worker thread each time the sheet opens. The control owns no setting: it raises
// VersionSelected and Home persists it.
public sealed partial class VersionSelectOverlay : UserControl
{
    private readonly List<FamilyTile> _tiles;
    private readonly VersionSelectNav _nav;
    private readonly ObservableCollection<ReleaseItem> _releases = new();
    private InstalledVersions _installs;
    private bool _syncing;
    private int _statesQueued;
    private long _panelRevision;

    private const double CardsDimmed = 0.12;

    public VersionSelectOverlay()
    {
        InitializeComponent();
        _tiles = VersionCatalog.Groups.Select(g => new FamilyTile(g)).ToList();
        _nav = new VersionSelectNav(VersionCatalog.Groups);
        FamilyList.ItemsSource = _tiles;
        ReleaseBox.ItemsSource = _releases;
        ReleaseBox.SelectionChanged += (_, _) =>
        {
            if (_syncing || ReleaseBox.SelectedIndex < 0) return;
            ChooseRelease(ReleaseBox.SelectedIndex);
        };
        FamiliesCaption.Text = $"{_tiles.Count} families, {VersionCatalog.SupportedVersions.Count} releases. Origin installs what each one needs.";
        Stage.SizeChanged += (_, _) => SizeCards();
        CloseButton.Click += (_, _) => CloseOverlay();
        BackButton.Click += (_, _) => Act(_nav.GoBack());
        Backdrop.PointerPressed += OnOutsidePress;
        PanelCatcher.PointerPressed += OnOutsidePress;
        // The sheet's own padding, around the panel, counts as "off the panel" in step 2 too.
        Sheet.PointerPressed += (_, e) =>
        {
            if (_nav.Step != SelectorStep.Releases || FamilyPanel.IsPointerOver) return;
            CloseOverlay();
            e.Handled = true;
        };
        KeyDown += OnSheetKey;
        _installs = InstalledVersions.Shared;
        _installs.Changed += OnInstallChanged;
        foreach (var tile in _tiles) tile.LoadAsync();
    }

    /// <summary>Raised when the player picks a specific version. Home persists it and its art follows behind the sheet.</summary>
    public event Action<string>? VersionSelected;

    public bool IsOpen => IsVisible;

    /// <summary>Where install states come from: the launcher's own, or (render shots) one over temp folders.</summary>
    internal InstalledVersions Installs
    {
        get => _installs;
        set
        {
            _installs.Changed -= OnInstallChanged;
            _installs = value;
            _installs.Changed += OnInstallChanged;
            ApplyStates();
        }
    }

    internal VersionSelectNav Nav => _nav;

    public void Show(string? currentVersion)
    {
        var current = VersionCatalog.IsSupported(currentVersion) ? currentVersion! : VersionCatalog.DefaultVersion;
        _nav.Open(current);
        ShowPickRing();
        ResetPanel();
        ApplyStates();
        _ = _installs.RefreshAllAsync();
        IsVisible = true;
        Dispatcher.UIThread.Post(() => FocusFamily(_nav.FamilyIndex));
    }

    /// <summary>Close from outside (a nav click while it is open): instant.</summary>
    public void Dismiss()
    {
        if (!IsOpen) return;
        ReleaseBox.IsDropDownOpen = false;
        IsVisible = false;
    }

    private void CloseOverlay()
    {
        ReleaseBox.IsDropDownOpen = false;
        IsVisible = false;
        Closed?.Invoke();
    }

    /// <summary>The picker went away on its own (pick, Esc, outside click): Home takes focus back.</summary>
    public event Action? Closed;

    // ---- what the model answered ----

    private void Act(NavResult result)
    {
        switch (result.Kind)
        {
            case NavKind.FamilyFocused:
                FocusFamily(result.FamilyIndex);
                break;
            case NavKind.OpenedFamily:
                BuildReleases(result.FamilyIndex);
                Expand(result.FamilyIndex, animate: true);
                Dispatcher.UIThread.Post(() => ReleaseBox.Focus());
                break;
            case NavKind.Select:
                ShowPick();
                VersionSelected?.Invoke(result.Version!);
                break;
            case NavKind.SelectAndClose:
                ShowPickRing();
                VersionSelected?.Invoke(result.Version!);
                CloseOverlay();
                break;
            case NavKind.OpenList:
                ReleaseBox.IsDropDownOpen = true;
                break;
            case NavKind.Back:
                ReleaseBox.IsDropDownOpen = false;
                Collapse(result.FamilyIndex, animate: true);
                FocusFamily(_nav.FamilyIndex);
                break;
            case NavKind.Close:
                CloseOverlay();
                break;
        }
    }

    // The ring on the family card that holds the player's version.
    private void ShowPickRing()
    {
        var current = _nav.CurrentVersion;
        foreach (var t in _tiles) t.IsPick = current != null && t.Group.Versions.Contains(current);
    }

    private void BuildReleases(int familyIndex)
    {
        var tile = _tiles[familyIndex];
        PanelImage.Source = tile.Colour;
        PanelRange.Text = tile.Range;
        PanelName.Text = tile.Name;
        _syncing = true;
        _releases.Clear();
        foreach (var v in tile.Group.Versions) _releases.Add(new ReleaseItem(v, _installs.StateOf(v)));
        ReleaseBox.SelectedIndex = _nav.ReleaseIndex;
        _syncing = false;
        SizeCards();
    }

    private void ShowPick()
    {
        _syncing = true;
        ReleaseBox.SelectedIndex = _nav.ReleaseIndex;
        _syncing = false;
        ShowPickRing();
    }

    private Button? CardAt(int index) =>
        FamilyList.ContainerFromIndex(index) is ContentPresenter { Child: Button b } ? b : null;

    private void FocusFamily(int index) => CardAt(index)?.Focus(NavigationMethod.Directional);

    // ---- the card <-> panel move ----

    // Family card <paramref name="index"/> in SheetContent's coordinates (null before layout).
    private Rect? CardRect(int index)
    {
        if (CardAt(index) is not { } card || card.Bounds.Width <= 0) return null;
        return card.TranslatePoint(default, SheetContent) is { } p ? new Rect(p, card.Bounds.Size) : null;
    }

    // The panel's resting place in SheetContent's coordinates (its arranged bounds, which leave out its transform).
    private Rect? PanelRect() => FamilyPanel.Bounds.Width <= 0 ? null : FamilyPanel.Bounds;

    private static ITransform PanelTransform(double x, double y, double sx, double sy)
    {
        var b = TransformOperations.CreateBuilder(2);
        b.AppendTranslate(x, y);
        b.AppendScale(sx, sy);
        return b.Build();
    }

    // Card -> panel. The panel is placed and takes input at once; the scale and move only follow.
    private void Expand(int index, bool animate)
    {
        ++_panelRevision;
        FamilyPanel.IsVisible = true;
        FamilyPanel.IsHitTestVisible = true;
        PanelCatcher.IsVisible = true;
        FamiliesPane.IsHitTestVisible = false;
        FamiliesPane.Opacity = CardsDimmed;
        var from = CardRect(index);
        var to = PanelRect();
        if (!animate || Motion.Off || from is not { } f || to is not { } t)
        {
            SetPanelInstant(PanelTransform(0, 0, 1, 1), 1);
            return;
        }
        // Start on the card exactly, then let the panel's transitions carry it to rest.
        SetPanelInstant(PanelTransform(f.X - t.X, f.Y - t.Y, f.Width / t.Width, f.Height / t.Height), 0);
        Dispatcher.UIThread.Post(() =>
        {
            FamilyPanel.RenderTransform = PanelTransform(0, 0, 1, 1);
            FamilyPanel.Opacity = 1;
        }, DispatcherPriority.Render);
    }

    // Panel -> card: the reverse. The cards take input again at once; the panel hides when its move ends.
    private void Collapse(int index, bool animate)
    {
        var revision = ++_panelRevision;
        FamilyPanel.IsHitTestVisible = false;
        PanelCatcher.IsVisible = false;
        FamiliesPane.IsHitTestVisible = true;
        FamiliesPane.Opacity = 1;
        var from = PanelRect();
        var to = CardRect(index);
        if (!animate || Motion.Off || from is not { } f || to is not { } t)
        {
            ResetPanel();
            return;
        }
        FamilyPanel.RenderTransform = PanelTransform(t.X - f.X, t.Y - f.Y, t.Width / f.Width, t.Height / f.Height);
        FamilyPanel.Opacity = 0;
        DispatcherTimer.RunOnce(() => { if (_panelRevision == revision) ResetPanel(); }, TimeSpan.FromMilliseconds(240));
    }

    // Sets the panel's transform and opacity with its transitions held back for this one change.
    private void SetPanelInstant(ITransform transform, double opacity)
    {
        var transitions = FamilyPanel.Transitions;
        FamilyPanel.Transitions = null;
        FamilyPanel.RenderTransform = transform;
        FamilyPanel.Opacity = opacity;
        FamilyPanel.Transitions = transitions;
    }

    private void ResetPanel()
    {
        ++_panelRevision;
        SetPanelInstant(PanelTransform(0, 0, 1, 1), 1);
        FamilyPanel.IsVisible = false;
        FamilyPanel.IsHitTestVisible = false;
        PanelCatcher.IsVisible = false;
        var transitions = FamiliesPane.Transitions;
        FamiliesPane.Transitions = null;
        FamiliesPane.Opacity = 1;
        FamiliesPane.Transitions = transitions;
        FamiliesPane.IsHitTestVisible = true;
    }

    // ---- install states ----

    private void OnInstallChanged(string version)
    {
        // Raised on a worker thread, up to once per version while a refresh runs: fold them into one UI pass.
        if (Interlocked.Exchange(ref _statesQueued, 1) == 1) return;
        Dispatcher.UIThread.Post(() =>
        {
            Interlocked.Exchange(ref _statesQueued, 0);
            ApplyStates();
        }, DispatcherPriority.Background);
    }

    /// <summary>Paint the cached install states (cache only, no disk).</summary>
    internal void ApplyStates()
    {
        foreach (var tile in _tiles)
            tile.SetBadge(VersionStatus.ForFamily(tile.Group.Versions.Select(_installs.StateOf).ToList()));
        foreach (var item in _releases)
            item.SetState(_installs.StateOf(item.Version));
    }

    // ---- sizing (the WPF SizeCards, the same numbers) ----

    // The sheet's fixed sizes (px): its own top + bottom margin (8 + 16), padding (24 + 24) and 1 px edges; step 1's
    // heading block (28 + 16 + 16 gap); a card's text under the art (10 + 22 + 2 + 16 + 4 + 16 + 12, the card's 1 px
    // edges) and the 12 px between rows; the panel's padding (16 + 16) and 1 px edges.
    private const double SheetChrome = 8 + 16 + 24 + 24 + 2;
    private const double SheetGutterX = 24 + 24, SheetInnerX = 24 + 24 + 2;
    private const double FamiliesHeader = 28 + 16 + 16, CardText = 10 + 22 + 2 + 16 + 4 + 16 + 12 + 2, RowGap = 12;
    private const double PanelEdges = 16 + 16 + 2, CardEdges = 2;
    private const int Columns = 4;

    private double Token(string key) => this.ThemeDouble(key);

    // The sheet follows the window: Size.Sheet.Share of its width, between Size.Sheet.Min and Size.Sheet.Max, never
    // wider than the window less the gutters. The cards stay 16:9; a window too short for two rows makes them narrower.
    private void SizeCards()
    {
        var stageW = Stage.Bounds.Width;
        var stageH = Stage.Bounds.Height;
        if (_tiles.Count == 0 || stageW <= 0 || stageH <= 0) return;
        var sheet = Math.Min(stageW - SheetGutterX, Math.Clamp(stageW * Token("Size.Sheet.Share"), Token("Size.Sheet.Min"), Token("Size.Sheet.Max")));
        var column = Math.Floor((sheet - SheetInnerX + RowGap) / Columns) - RowGap;
        var rows = (_tiles.Count + Columns - 1) / Columns;
        var fit = (stageH - SheetChrome - FamiliesHeader + RowGap) / rows - CardText - RowGap;
        if ((column - CardEdges) * 9 / 16 > fit) column = Math.Floor(fit * 16 / 9 + CardEdges);
        column = Math.Max(column, 96);
        var art = Math.Round((column - CardEdges) * 9 / 16);
        foreach (var tile in _tiles)
        {
            tile.CardWidth = column;
            tile.ThumbHeight = art;
        }
        Sheet.Width = Columns * column + (Columns - 1) * RowGap + SheetInnerX;

        var panel = Math.Round(Math.Clamp((Sheet.Width - SheetInnerX) * Token("Size.FamilyPanel.Share"), Token("Size.FamilyPanel.Min"), Token("Size.FamilyPanel.Max")));
        FamilyPanel.Width = panel;
        var inner = panel - PanelEdges;
        var block = FamiliesHeader + rows * (art + CardText) + (rows - 1) * RowGap;
        double words = 0;
        foreach (var child in FamilySide.Children)
        {
            if (ReferenceEquals(child, PanelArt)) continue;
            child.Measure(new Size(inner, double.PositiveInfinity));
            words += child.DesiredSize.Height;
        }
        PanelArt.Height = Math.Round(Math.Max(96, Math.Min(inner * 9 / 16, block - PanelEdges - words)));
    }

    // ---- events ----

    private void FamilyCard_Click(object? sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: FamilyTile tile }) ChooseFamily(_tiles.IndexOf(tile));
    }

    private void OnOutsidePress(object? sender, PointerPressedEventArgs e)
    {
        CloseOverlay();
        e.Handled = true;
    }

    private void OnSheetKey(object? sender, KeyEventArgs e)
    {
        if (HandleKey(e.Key, e.Source)) e.Handled = true;
    }

    /// <summary>One key press on the sheet; true when the sheet took it.</summary>
    internal bool HandleKey(Key key, object? source)
    {
        if (ReleaseBox.IsDropDownOpen) return false;
        NavKey? nav = key switch
        {
            Key.Left => NavKey.Left,
            Key.Right => NavKey.Right,
            Key.Up => NavKey.Up,
            Key.Down => NavKey.Down,
            Key.Home => NavKey.Home,
            Key.End => NavKey.End,
            Key.Escape => NavKey.Escape,
            Key.Back => NavKey.Backspace,
            Key.Enter => NavKey.Enter,
            Key.Space when source is Button { Tag: FamilyTile } => NavKey.Enter,
            Key.Space when _nav.Step == SelectorStep.Releases && source is ComboBox => NavKey.Enter,
            _ => null,
        };
        if (nav is not { } k) return false;
        // A focused button (Close, All versions) presses itself on Enter: Close must not open a card.
        if (k == NavKey.Enter && source is Button { Tag: not FamilyTile }) return false;
        Act(_nav.Press(k));
        return true;
    }

    internal void ChooseFamily(int index) => Act(_nav.ChooseFamily(index));
    internal void ChooseRelease(int index) => Act(_nav.ChooseRelease(index));

    // ---- render shots (UI/Shots/AreaAShots.cs) ----

    /// <summary>The badge text on a family card, for the shot report.</summary>
    internal string BadgeTextOf(string familyKey) => _tiles.First(t => t.Group.Key == familyKey).BadgeText;

    internal IReadOnlyList<(string Version, bool Dot)> DropdownItems => _releases.Select(r => (r.Version, r.Dot)).ToList();
    internal string? DropdownChoice => (ReleaseBox.SelectedItem as ReleaseItem)?.Version;

    /// <summary>Open the picker, optionally on family <paramref name="detailIndex"/> at step 2, with no motion.</summary>
    internal void TourOpen(int detailIndex, string? currentVersion = null)
    {
        if (!OriginPaths.IsScratchRoot) return;
        Show(currentVersion);
        SizeCards();
        if (detailIndex >= 0 && detailIndex < _tiles.Count && _tiles[detailIndex].Group.Versions.Count > 1)
        {
            _nav.ChooseFamily(detailIndex);
            BuildReleases(detailIndex);
            Expand(detailIndex, animate: false);
        }
    }

    internal void TourFocus(int index)
    {
        if (!OriginPaths.IsScratchRoot) return;
        if (_nav.Step == SelectorStep.Releases)
        {
            ReleaseBox.Focus(NavigationMethod.Tab);
            return;
        }
        for (var i = 0; i < 12 && _nav.FamilyIndex != index; i++)
            _nav.Press(_nav.FamilyIndex < index ? NavKey.Right : NavKey.Left);
        CardAt(index)?.Focus(NavigationMethod.Tab);
    }

    internal void TourDropdown(bool open)
    {
        if (OriginPaths.IsScratchRoot) ReleaseBox.IsDropDownOpen = open;
    }

    internal void TourClose()
    {
        if (!OriginPaths.IsScratchRoot) return;
        ReleaseBox.IsDropDownOpen = false;
        IsVisible = false;
    }

    /// <summary>Waits for every card's art (decoded off the UI thread) so a shot never catches an empty card.</summary>
    internal Task TourArtReady() => Task.WhenAll(_tiles.Select(t => t.Loaded));

    internal static IBrush DotBrush(StatusDot dot) => Find(dot switch
    {
        StatusDot.Success => "Brush.Success",
        StatusDot.Info => "Brush.Info",
        StatusDot.Warning => "Brush.Warning",
        _ => "Brush.TextMuted",
    });

    private static IBrush Find(string key) =>
        Application.Current!.TryGetResource(key, null, out var v) && v is IBrush b ? b : Brushes.Transparent;
}

/// <summary>One release in step 2's dropdown: the version, and whether it wears the quiet installed dot.</summary>
public sealed class ReleaseItem : INotifyPropertyChanged
{
    private bool _dot;

    public ReleaseItem(string version, InstallState state)
    {
        Version = version;
        _dot = VersionStatus.ShowsInstalledDot(state);
    }

    public string Version { get; }
    public bool Dot => _dot;

    public void SetState(InstallState state)
    {
        var dot = VersionStatus.ShowsInstalledDot(state);
        if (dot == _dot) return;
        _dot = dot;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Dot)));
    }

    // The text a typed letter searches by.
    public override string ToString() => Version;

    public event PropertyChangedEventHandler? PropertyChanged;
}

/// <summary>One family card's data. The art arrives after decode; ThumbHeight follows the card width (16:9).</summary>
public sealed class FamilyTile : INotifyPropertyChanged
{
    private Bitmap? _colour;
    private double _thumbHeight = 82, _cardWidth = double.NaN;
    private bool _isPick;
    private StatusLine? _badge;

    public FamilyTile(VersionGroup group) => Group = group;

    public VersionGroup Group { get; }
    public string Range => Group.Title;
    public string Name => Group.Tagline;
    public Bitmap? Colour { get => _colour; private set { _colour = value; Raise(nameof(Colour)); } }
    public Task Loaded { get; private set; } = Task.CompletedTask;

    public double CardWidth
    {
        get => _cardWidth;
        set { if (_cardWidth.Equals(value)) return; _cardWidth = value; Raise(nameof(CardWidth)); Raise(nameof(InnerWidth)); }
    }

    /// <summary>The width inside the card's 1 px edge: the card (face) itself stays CardWidth wide.</summary>
    public double InnerWidth => Math.Max(0, _cardWidth - 2);

    public double ThumbHeight
    {
        get => _thumbHeight;
        set { if (Math.Abs(_thumbHeight - value) < 0.5) return; _thumbHeight = value; Raise(nameof(ThumbHeight)); }
    }

    public bool IsPick
    {
        get => _isPick;
        set { if (_isPick == value) return; _isPick = value; Raise(nameof(IsPick)); }
    }

    public string BadgeText => _badge?.Text ?? "";
    public IBrush? BadgeDot => _badge == null ? null : VersionSelectOverlay.DotBrush(_badge.Dot);
    public double BadgeOpacity => _badge == null ? 0 : 1;

    public void SetBadge(StatusLine? badge)
    {
        if (Equals(_badge, badge)) return;
        _badge = badge;
        Raise(nameof(BadgeText));
        Raise(nameof(BadgeDot));
        Raise(nameof(BadgeOpacity));
    }

    // Full-colour art, decoded off the UI thread.
    public void LoadAsync()
    {
        var key = Group.Key;
        Loaded = Task.Run(() => ArtStage.Decode($"{key}-tc.jpg", 480))
            .ContinueWith(t => Dispatcher.UIThread.InvokeAsync(() => Colour = t.Result).GetTask()).Unwrap();
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
