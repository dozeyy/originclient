using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Controls;

// The version selector (Phase 3, U3.2; DESIGN-SYSTEM.md 11.3): a sheet over Home's art in two steps.
//   Step 1  the eight families as cards (art, the number set into it, a "2 of 10 installed" badge).
//   Step 2  one family, expanded (Will, 2026-10-25): the clicked card grows out of its grid slot into a centred panel
//           (scale + move, 220 ms, Motion.Emphasized) while the other cards fade back. The panel holds Back ("All
//           versions"), the family's art, range and name, and ONE compact dropdown of its releases, newest first, with
//           the current one preselected and a quiet emerald dot on the ones already on disk. Picking a release in it
//           picks the version and that is all: no Play, no Install, no "download" words (Play installs what it needs).
//           A click off the panel or Esc closes the picker to Home; Back shrinks the panel into its card again.
// A family with a single release has no step 2: choosing it picks it and closes.
//
// The keys, what each step shows and what counts as "a pick" live in VersionSelectModel.cs (unit-tested); this file
// draws and carries out what the model answers. Install states come from InstalledVersions: StateOf is cache-only,
// so reading them on the UI thread never touches the disk; the disk is read by RefreshAllAsync on a worker thread
// every time the sheet opens, and the service raises Changed when an answer moves (after an install or a launch).
// The control owns no setting: it raises VersionSelected and Home persists it (SettingsStore.Update in
// HomePage.OnVersionSelected). Input is never held for an animation: every state change takes effect at once and the
// motion only follows it.
public partial class VersionSelectOverlay : UserControl
{
    private readonly List<FamilyTile> _tiles;
    private readonly VersionSelectNav _nav;
    private readonly ObservableCollection<ReleaseItem> _releases = new();
    private InstalledVersions _installs;
    private bool _syncing;
    private long _sceneRevision, _panelRevision;
    private int _statesQueued;

    // The card-to-panel move: 220 ms each way, the backdrop cards fade to this much.
    private const double PanelMs = 220, CardsDimmed = 0.12;

    public VersionSelectOverlay()
    {
        InitializeComponent();
        _tiles = VersionCatalog.Groups.Select(g => new FamilyTile(g)).ToList();
        _nav = new VersionSelectNav(VersionCatalog.Groups);
        FamilyList.ItemsSource = _tiles;
        FamilyList.SelectionChanged += (_, _) => { if (!_syncing) ShowPickRing(); };
        ReleaseBox.ItemsSource = _releases;
        FamiliesCaption.Text = $"{_tiles.Count} families, {VersionCatalog.SupportedVersions.Count} releases. Origin installs what each one needs.";
        // The sheet's size follows the free band under the top bar (the window), not its own content.
        Stage.SizeChanged += (_, _) => SizeCards();
        PreviewKeyDown += OnSheetKey;
        // The sheet's own padding, around the panel, counts as "off the panel" in step 2 too.
        Sheet.MouseLeftButtonDown += (_, e) =>
        {
            if (_nav.Step != SelectorStep.Releases || FamilyPanel.IsMouseOver) return;
            CloseOverlay();
            e.Handled = true;
        };
        _installs = InstalledVersions.Shared;
        _installs.Changed += OnInstallChanged;
        // Thumbnails decode off the UI thread (frozen bitmaps).
        foreach (var tile in _tiles) tile.LoadAsync();
    }

    /// <summary>Raised when the player picks a specific version (a release in step 2, or a single-release family).
    /// Home persists it and its art follows live behind the sheet.</summary>
    public event Action<string>? VersionSelected;

    public bool IsOpen => Visibility == Visibility.Visible;

    /// <summary>Where the selector reads install states from. The launcher's own (<see cref="InstalledVersions.Shared"/>);
    /// the UI tests and the screenshot run swap in one over temp folders.</summary>
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

    /// <summary>The model the keys go through (the UI tests read the step and the highlight from it).</summary>
    internal VersionSelectNav Nav => _nav;

    public void Show(string? currentVersion)
    {
        var current = VersionCatalog.IsSupported(currentVersion) ? currentVersion! : VersionCatalog.DefaultVersion;
        _nav.Open(current);
        ShowPickRing();
        ResetPanel();
        // Whatever the cache holds shows at once; the disk read refreshes it a moment later (and Changed re-applies).
        ApplyStates();
        _ = _installs.RefreshAllAsync();

        ++_sceneRevision;
        Visibility = Visibility.Visible;
        Scene.BeginAnimation(OpacityProperty, null);
        OverlayTranslate.BeginAnimation(TranslateTransform.YProperty, null);
        if (Motion.Off)
        {
            Scene.Opacity = 1;
            OverlayTranslate.Y = 0;
        }
        else
        {
            // Fades in while rising 20 px: the view arrives, it does not slide over the art like a sheet.
            Scene.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 260, Motion.Out));
            OverlayTranslate.BeginAnimation(TranslateTransform.YProperty, Motion.FromTo(20, 0, 420, Motion.Expo));
        }

        Dispatcher.BeginInvoke(() => FocusFamily(_nav.FamilyIndex));
    }

    /// <summary>Close the picker from outside (a nav click while it is open). Instant: Home is leaving the window,
    /// and a fade on a page that is not shown never finishes (it would leave the picker "open" for good).</summary>
    public void Dismiss()
    {
        if (!IsOpen) return;
        ++_sceneRevision;
        ReleaseBox.IsDropDownOpen = false;
        Scene.BeginAnimation(OpacityProperty, null);
        OverlayTranslate.BeginAnimation(TranslateTransform.YProperty, null);
        Visibility = Visibility.Collapsed;
    }

    private void CloseOverlay()
    {
        var sceneRevision = ++_sceneRevision;
        ReleaseBox.IsDropDownOpen = false;
        if (Motion.Off)
        {
            Visibility = Visibility.Collapsed;
            return;
        }
        var fade = Motion.To(0, 200, Motion.In);
        fade.Completed += (_, _) =>
        {
            if (_sceneRevision == sceneRevision) Visibility = Visibility.Collapsed;
        };
        Scene.BeginAnimation(OpacityProperty, fade, HandoffBehavior.SnapshotAndReplace);
        OverlayTranslate.BeginAnimation(TranslateTransform.YProperty, Motion.To(12, 200, Motion.In), HandoffBehavior.SnapshotAndReplace);
    }

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
                Dispatcher.BeginInvoke(() => ReleaseBox.Focus());
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

    // The ring on the family card that holds the player's version (the sheet's "pick"). A click on a card selects it
    // in the ListBox for a moment; this puts the ring back where the pick is.
    private void ShowPickRing()
    {
        var current = _nav.CurrentVersion;
        var tile = current == null ? null : _tiles.FirstOrDefault(t => t.Group.Versions.Contains(current));
        _syncing = true;
        FamilyList.SelectedItem = tile;
        _syncing = false;
    }

    // Step 2 shows a family: its art and words, and its releases in the dropdown with the current one chosen.
    private void BuildReleases(int familyIndex)
    {
        var tile = _tiles[familyIndex];
        FamilySide.DataContext = tile;
        _syncing = true;
        _releases.Clear();
        foreach (var v in tile.Group.Versions) _releases.Add(new ReleaseItem(v, _installs.StateOf(v)));
        ReleaseBox.SelectedIndex = _nav.ReleaseIndex;
        _syncing = false;
        SizeCards();
    }

    // The pick moved to a release (a key or a click): show it in the dropdown, and the ring on its card.
    private void ShowPick()
    {
        _syncing = true;
        ReleaseBox.SelectedIndex = _nav.ReleaseIndex;
        _syncing = false;
        ShowPickRing();
    }

    private void FocusFamily(int index) =>
        (FamilyList.ItemContainerGenerator.ContainerFromIndex(index) as UIElement ?? FamilyList).Focus();

    // ---- the card <-> panel move ----

    // Family card <paramref name="index"/>'s face, in SheetContent's coordinates (Rect.Empty before it is laid out).
    private Rect CardRect(int index)
    {
        if (FamilyList.ItemContainerGenerator.ContainerFromIndex(index) is not ListBoxItem item) return Rect.Empty;
        var face = item.Template?.FindName("Face", item) as FrameworkElement ?? item;
        if (face.ActualWidth <= 0 || !face.IsDescendantOf(SheetContent)) return Rect.Empty;
        return face.TransformToAncestor(SheetContent).TransformBounds(new Rect(0, 0, face.ActualWidth, face.ActualHeight));
    }

    // The panel's resting place in SheetContent's coordinates (it is SheetContent's child): its arranged offset,
    // which leaves out the scale and shift it is drawn with.
    private Rect PanelRect() =>
        FamilyPanel.ActualWidth <= 0 ? Rect.Empty
            : new Rect((Point)VisualTreeHelper.GetOffset(FamilyPanel), new Size(FamilyPanel.ActualWidth, FamilyPanel.ActualHeight));

    // Card -> panel. The panel is placed and takes input at once; the scale and move only follow. Starting from the
    // card's rect, the panel's transform (scale about its top-left, then a shift) maps it onto that card exactly.
    private void Expand(int index, bool animate)
    {
        ++_panelRevision;
        StopPanelMotion();
        FamilyPanel.Visibility = Visibility.Visible;
        FamilyPanel.IsHitTestVisible = true;
        PanelCatcher.Visibility = Visibility.Visible;
        FamiliesPane.IsHitTestVisible = false;
        UpdateLayout();

        var from = CardRect(index);
        var to = PanelRect();
        if (!animate || Motion.Off || !IsOpen || from.IsEmpty || to.IsEmpty)
        {
            SetPanel(1, 1, 0, 0, 1);
            FamiliesPane.Opacity = CardsDimmed;
            return;
        }
        Animate(PanelScale, ScaleTransform.ScaleXProperty, from.Width / to.Width, 1);
        Animate(PanelScale, ScaleTransform.ScaleYProperty, from.Height / to.Height, 1);
        Animate(PanelShift, TranslateTransform.XProperty, from.X - to.X, 0);
        Animate(PanelShift, TranslateTransform.YProperty, from.Y - to.Y, 0);
        // The panel's own face fades in over the first half, so the stretched words never read.
        FamilyPanel.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, PanelMs / 2, Motion.Out));
        FamiliesPane.BeginAnimation(OpacityProperty, Motion.FromTo(FamiliesPane.Opacity, CardsDimmed, PanelMs, Motion.Emphasized));
    }

    // Panel -> card: the reverse. The cards take input again at once; the panel hides when its move ends (unless the
    // player opened a family again meanwhile).
    private void Collapse(int index, bool animate)
    {
        var revision = ++_panelRevision;
        FamilyPanel.IsHitTestVisible = false;
        PanelCatcher.Visibility = Visibility.Collapsed;
        FamiliesPane.IsHitTestVisible = true;
        var from = PanelRect();
        var to = CardRect(index);
        StopPanelMotion();
        if (!animate || Motion.Off || !IsOpen || from.IsEmpty || to.IsEmpty)
        {
            ResetPanel();
            return;
        }
        SetPanel(1, 1, 0, 0, 1);
        Animate(PanelScale, ScaleTransform.ScaleXProperty, 1, to.Width / from.Width);
        Animate(PanelScale, ScaleTransform.ScaleYProperty, 1, to.Height / from.Height);
        Animate(PanelShift, TranslateTransform.XProperty, 0, to.X - from.X);
        Animate(PanelShift, TranslateTransform.YProperty, 0, to.Y - from.Y);
        var fade = Motion.FromTo(1, 0, PanelMs / 2, Motion.In, PanelMs / 2);
        fade.Completed += (_, _) =>
        {
            if (_panelRevision == revision) ResetPanel();
        };
        FamilyPanel.BeginAnimation(OpacityProperty, fade);
        FamiliesPane.BeginAnimation(OpacityProperty, Motion.FromTo(FamiliesPane.Opacity, 1, PanelMs, Motion.Emphasized));
    }

    private static void Animate(Animatable target, DependencyProperty property, double from, double to) =>
        target.BeginAnimation(property, Motion.FromTo(from, to, PanelMs, Motion.Emphasized));

    // Clears every running panel animation, keeping nothing of it (the next step sets its own start values).
    private void StopPanelMotion()
    {
        PanelScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
        PanelScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        PanelShift.BeginAnimation(TranslateTransform.XProperty, null);
        PanelShift.BeginAnimation(TranslateTransform.YProperty, null);
        FamilyPanel.BeginAnimation(OpacityProperty, null);
        var cards = FamiliesPane.Opacity;
        FamiliesPane.BeginAnimation(OpacityProperty, null);
        FamiliesPane.Opacity = cards;
    }

    private void SetPanel(double scaleX, double scaleY, double x, double y, double opacity)
    {
        PanelScale.ScaleX = scaleX;
        PanelScale.ScaleY = scaleY;
        PanelShift.X = x;
        PanelShift.Y = y;
        FamilyPanel.Opacity = opacity;
    }

    // Step 1 at rest: no panel, the cards fully shown and taking input.
    private void ResetPanel()
    {
        ++_panelRevision;
        StopPanelMotion();
        SetPanel(1, 1, 0, 0, 1);
        FamilyPanel.Visibility = Visibility.Hidden;
        FamilyPanel.IsHitTestVisible = false;
        PanelCatcher.Visibility = Visibility.Collapsed;
        FamiliesPane.Opacity = 1;
        FamiliesPane.IsHitTestVisible = true;
    }

    // ---- install states ----

    private void OnInstallChanged(string version)
    {
        // Raised on a worker thread, up to once per version while a refresh runs: fold them into one UI pass.
        if (Interlocked.Exchange(ref _statesQueued, 1) == 1) return;
        Dispatcher.BeginInvoke(DispatcherPriority.Background, () =>
        {
            Interlocked.Exchange(ref _statesQueued, 0);
            ApplyStates();
        });
    }

    /// <summary>Paint the cached install states (cache only, no disk): the badge on every card and the dot of every
    /// release of the open family. A version not read yet shows nothing.</summary>
    internal void ApplyStates()
    {
        foreach (var tile in _tiles)
            tile.SetBadge(VersionStatus.ForFamily(tile.Group.Versions.Select(_installs.StateOf).ToList()));
        foreach (var item in _releases)
            item.SetState(_installs.StateOf(item.Version));
    }

    // ---- sizing ----

    // The sheet's fixed sizes (px), so the cards fit a short window without scrolling: the sheet's own top + bottom
    // margin (8 + 16), padding (24 + 24) and 1 px edges; step 1's heading block (28 + 16 + 16 gap); a card's text under
    // the art (10 + 22 range line + 2 + 16 name + 4 + 16 status + 12, and the card's own 1 px edges) and the 12 px
    // between rows; the panel's padding (16 + 16) and 1 px edges.
    private const double SheetChrome = 8 + 16 + 24 + 24 + 2;
    private const double SheetGutterX = 24 + 24, SheetInnerX = 24 + 24 + 2;
    private const double FamiliesHeader = 28 + 16 + 16, CardText = 10 + 22 + 2 + 16 + 4 + 16 + 12 + 2, RowGap = 12;
    private const double PanelEdges = 16 + 16 + 2;
    private const double CardEdges = 2;
    private const int Columns = 4;

    private double Token(string key) => (double)FindResource(key);

    // The sheet follows the window: Size.Sheet.Share of its width, between Size.Sheet.Min and Size.Sheet.Max, never
    // wider than the window less the gutters. The cards stay 16:9 art at whatever width that gives; when the window is
    // too short for two rows at that width the cards (and so the sheet) get narrower instead of squashing the art.
    private void SizeCards()
    {
        var stageW = Stage.ActualWidth;
        var stageH = Stage.ActualHeight;
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

        // Step 2's panel: a share of the sheet (Size.FamilyPanel tokens), and never taller than the cards' block, so
        // the sheet keeps its size between the steps. Its art is 16:9 at that width, shorter when the window is.
        var panel = Math.Round(Math.Clamp((Sheet.Width - SheetInnerX) * Token("Size.FamilyPanel.Share"), Token("Size.FamilyPanel.Min"), Token("Size.FamilyPanel.Max")));
        FamilyPanel.Width = panel;
        var inner = panel - PanelEdges;
        var block = FamiliesHeader + rows * (art + CardText) + (rows - 1) * RowGap;
        // Everything in the panel but the art, measured at its width (the words, Back and the dropdown).
        double words = 0;
        foreach (UIElement child in FamilySide.Children)
        {
            if (ReferenceEquals(child, PanelArt)) continue;
            child.Measure(new Size(inner, double.PositiveInfinity));
            words += child.DesiredSize.Height;
        }
        var detail = Math.Max(96, Math.Min(inner * 9 / 16, block - PanelEdges - words));
        foreach (var tile in _tiles) tile.DetailArtHeight = Math.Round(detail);
    }

    // ---- events ----

    private void FamilyItem_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem { DataContext: FamilyTile tile }) return;
        ChooseFamily(_tiles.IndexOf(tile));
    }

    private void ReleaseBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || ReleaseBox.SelectedIndex < 0) return;
        ChooseRelease(ReleaseBox.SelectedIndex);
    }

    private void Close_Click(object sender, RoutedEventArgs e) => CloseOverlay();

    private void Back_Click(object sender, RoutedEventArgs e) => Act(_nav.GoBack());

    private void Backdrop_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        CloseOverlay();
        e.Handled = true;
    }

    private void OnSheetKey(object sender, KeyEventArgs e)
    {
        if (HandleKey(e.Key, e.OriginalSource)) e.Handled = true;
    }

    /// <summary>One key press on the sheet. True when the sheet took it (the caller marks the event handled). The UI tests
    /// call this directly, so the keys run through the same code as a real press without an input stack.</summary>
    internal bool HandleKey(Key wpfKey, object? source)
    {
        // The open dropdown owns its keys: arrows move in it, Enter takes the release, Esc closes just the list.
        if (ReleaseBox.IsDropDownOpen) return false;
        var key = wpfKey switch
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
            // Space opens the focused family card, like Enter (a button presses itself on Space).
            Key.Space when source is ListBoxItem { DataContext: FamilyTile } => NavKey.Enter,
            // Space on the closed dropdown opens it, like Enter.
            Key.Space when _nav.Step == SelectorStep.Releases && source is ComboBox => NavKey.Enter,
            _ => (NavKey?)null,
        };
        if (key is not { } nav) return false;
        // A focused button (Close, All versions) presses itself on Enter: Close must not open a card.
        if (nav == NavKey.Enter && source is System.Windows.Controls.Primitives.ButtonBase) return false;
        Act(_nav.Press(nav));
        return true;
    }

    /// <summary>A click on family card <paramref name="index"/> (the UI tests and the click handler share it).</summary>
    internal void ChooseFamily(int index) => Act(_nav.ChooseFamily(index));

    /// <summary>A pick of release <paramref name="index"/> of the open family in the dropdown.</summary>
    internal void ChooseRelease(int index) => Act(_nav.ChooseRelease(index));

    /// <summary>The badge text on a family card ("" while nothing is shown). For the tests and the shot report.</summary>
    internal string BadgeTextOf(string familyKey) => _tiles.First(t => t.Group.Key == familyKey).BadgeText;

    /// <summary>The open family's releases as the dropdown lists them, each with whether it wears the installed dot.</summary>
    internal IReadOnlyList<(string Version, bool Dot)> DropdownItems =>
        _releases.Select(r => (r.Version, r.DotVisibility == Visibility.Visible)).ToList();

    /// <summary>The release the dropdown shows chosen (null when no family is open).</summary>
    internal string? DropdownChoice => (ReleaseBox.SelectedItem as ReleaseItem)?.Version;

    /// <summary>Whether the panel is up and taking input (step 2).</summary>
    internal bool PanelShown => FamilyPanel.Visibility == Visibility.Visible && FamilyPanel.IsHitTestVisible;

    /// <summary>Whether the dropdown's list is open.</summary>
    internal bool DropdownOpen => ReleaseBox.IsDropDownOpen;

    // Render mode only (UI/RenderShots.cs): open the picker, optionally on one
    // family (index into VersionCatalog.Groups) at step 2, expanded with no motion. They do nothing outside a
    // render-shots run (OriginPaths.IsScratchRoot). Nothing here picks a version.
    internal void TourOpen(int detailIndex, string? currentVersion = null)
    {
        if (!OriginPaths.IsScratchRoot) return;
        Show(currentVersion);
        if (detailIndex >= 0 && detailIndex < _tiles.Count && _tiles[detailIndex].Group.Versions.Count > 1)
        {
            _nav.ChooseFamily(detailIndex);
            BuildReleases(detailIndex);
            Expand(detailIndex, animate: false);
        }
    }

    // Render mode only: the keyboard on a family card (step 1) or on the dropdown (step 2), so the shots show focus.
    internal void TourFocus(int index)
    {
        if (!OriginPaths.IsScratchRoot) return;
        if (_nav.Step == SelectorStep.Releases)
        {
            ReleaseBox.Focus();
        }
        else
        {
            for (var i = 0; i < 12 && _nav.FamilyIndex != index; i++)
                _nav.Press(_nav.FamilyIndex < index ? NavKey.Right : NavKey.Left);
            FocusFamily(index);
        }
    }

    // Render mode only: open or close step 2's dropdown list (a popup window; the shot draws it in place).
    internal ComboBox? TourDropdown(bool open)
    {
        if (!OriginPaths.IsScratchRoot) return null;
        ReleaseBox.IsDropDownOpen = open;
        return ReleaseBox;
    }

    internal void TourClose()
    {
        if (!OriginPaths.IsScratchRoot) return;
        ++_sceneRevision;
        ReleaseBox.IsDropDownOpen = false;
        Visibility = Visibility.Collapsed;
    }

    // Render mode only: size the cards now (a hand layout at a size the desktop cannot hold reports no SizeChanged).
    internal void TourRelayout()
    {
        if (OriginPaths.IsScratchRoot) SizeCards();
    }

    // Render mode only: family card <paramref name="index"/>'s container, so a shot can draw its keyboard focus frame.
    internal FrameworkElement? TourFamilyItem(int index) =>
        OriginPaths.IsScratchRoot ? FamilyList.ItemContainerGenerator.ContainerFromIndex(index) as FrameworkElement : null;

    // The brush for a status dot, from the same tokens as every other dot (DESIGN-SYSTEM.md 11.9).
    private static Brush DotBrush(StatusDot dot) => BrushOf(dot switch
    {
        StatusDot.Success => "Brush.Success",
        StatusDot.Info => "Brush.Info",
        StatusDot.Warning => "Brush.Warning",
        _ => "Brush.TextMuted",
    });

    private static Brush BrushOf(string key) => (Brush)Application.Current.FindResource(key);

    // One release in step 2's dropdown: the version, and whether it wears the quiet installed dot.
    private sealed class ReleaseItem : INotifyPropertyChanged
    {
        private bool _dot;

        public ReleaseItem(string version, InstallState state)
        {
            Version = version;
            _dot = VersionStatus.ShowsInstalledDot(state);
        }

        public string Version { get; }
        public Visibility DotVisibility => _dot ? Visibility.Visible : Visibility.Collapsed;

        public void SetState(InstallState state)
        {
            var dot = VersionStatus.ShowsInstalledDot(state);
            if (dot == _dot) return;
            _dot = dot;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(DotVisibility)));
        }

        // The automation name and the text a typed letter searches by.
        public override string ToString() => Version;

        public event PropertyChangedEventHandler? PropertyChanged;
    }

    // One family tile's data. The thumbnail arrives after decode; ThumbHeight follows the card width so the art keeps
    // 16:9 at every window size.
    private sealed class FamilyTile : INotifyPropertyChanged
    {
        private ImageSource? _colour;
        private double _thumbHeight = 82, _detailArtHeight = 200, _cardWidth = double.NaN;
        private StatusLine? _badge;

        public FamilyTile(VersionGroup group)
        {
            Group = group;
        }

        public VersionGroup Group { get; }
        public string Range => Group.Title;
        public string Name => Group.Tagline;
        public string Description => Group.Description;
        public ImageSource? Colour { get => _colour; private set { _colour = value; Raise(nameof(Colour)); } }
        // The card's width, a whole number of pixels (NaN until the first sizing pass). Left to the layout it is a quarter of
        // a width that is not always a multiple of four, and layout rounding then rounds every card up past its cell.
        public double CardWidth
        {
            get => _cardWidth;
            set { if (_cardWidth.Equals(value)) return; _cardWidth = value; Raise(nameof(CardWidth)); }
        }
        public double ThumbHeight
        {
            get => _thumbHeight;
            set { if (Math.Abs(_thumbHeight - value) < 0.5) return; _thumbHeight = value; Raise(nameof(ThumbHeight)); }
        }
        // Step 2's large card.
        public double DetailArtHeight
        {
            get => _detailArtHeight;
            set { if (Math.Abs(_detailArtHeight - value) < 0.5) return; _detailArtHeight = value; Raise(nameof(DetailArtHeight)); }
        }
        // The quiet status line under the card: nothing until every release of the family has been read.
        public string BadgeText => _badge?.Text ?? "";
        public Brush? BadgeDot => _badge == null ? null : DotBrush(_badge.Dot);
        public Visibility BadgeVisibility => _badge == null ? Visibility.Hidden : Visibility.Visible;
        public void SetBadge(StatusLine? badge)
        {
            if (Equals(_badge, badge)) return;
            _badge = badge;
            Raise(nameof(BadgeText));
            Raise(nameof(BadgeDot));
            Raise(nameof(BadgeVisibility));
        }

        // Full-colour art, decoded off the UI thread (a frozen bitmap).
        public void LoadAsync()
        {
            var key = Group.Key;
            _ = Task.Run(() => ArtStage.Decode($"{key}-tc.jpg", 480))
                .ContinueWith(t => Colour = t.Result, TaskScheduler.FromCurrentSynchronizationContext());
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        private void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

