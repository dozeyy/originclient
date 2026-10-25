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
//   Step 2  one family: its card large with what it added, then its releases newest first, each with its install
//           state (Installed / Update ready / Downloads when you press Play / Finishes installing...), and Play.
// A family with a single release has no step 2: choosing it picks it and closes.
//
// The keys, what each step shows and what counts as "a pick" live in VersionSelectModel.cs (unit-tested); this file
// draws and carries out what the model answers. Install states come from InstalledVersions: StateOf is cache-only,
// so reading them on the UI thread never touches the disk; the disk is read by RefreshAllAsync on a worker thread
// every time the sheet opens, and the service raises Changed when an answer moves (after an install or a launch).
// The control owns no launch logic beyond raising LaunchRequested, and no setting: it raises VersionSelected and
// Home persists it (SettingsStore.Update in HomePage.OnVersionSelected).
public partial class VersionSelectOverlay : UserControl
{
    private readonly List<FamilyTile> _tiles;
    private readonly VersionSelectNav _nav;
    private readonly ObservableCollection<ReleaseRow> _rows = new();
    private InstalledVersions _installs;
    private bool _syncing;
    private long _sceneRevision, _paneRevision;
    private int _statesQueued;

    public VersionSelectOverlay()
    {
        InitializeComponent();
        _tiles = VersionCatalog.Groups.Select(g => new FamilyTile(g)).ToList();
        _nav = new VersionSelectNav(VersionCatalog.Groups);
        FamilyList.ItemsSource = _tiles;
        FamilyList.SelectionChanged += (_, _) => { if (!_syncing) ShowPickRing(); };
        ReleaseList.ItemsSource = _rows;
        FamiliesCaption.Text = $"{_tiles.Count} families, {VersionCatalog.SupportedVersions.Count} releases. Origin installs what each one needs.";
        FamilyList.SizeChanged += (_, _) => SizeCards();
        Body.SizeChanged += (_, e) => { if (e.HeightChanged) SizeCards(); };
        PreviewKeyDown += OnSheetKey;
        _installs = InstalledVersions.Shared;
        _installs.Changed += OnInstallChanged;
        // Thumbnails decode off the UI thread at the size they draw (frozen bitmaps); the number's corner shade
        // for each art is worked out there too, once (the token spec is read here, on the UI thread).
        var scrimSpec = VersionCardNumeral.Spec();
        foreach (var tile in _tiles) tile.LoadAsync(scrimSpec);
    }

    /// <summary>Raised when the player picks a specific version (a release in step 2, or a single-release family).
    /// Home persists it and its art follows live behind the sheet.</summary>
    public event Action<string>? VersionSelected;

    /// <summary>Raised when the player clicks Play on a version.</summary>
    public event Action<string>? LaunchRequested;

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
        ShowPane(SelectorStep.Families, animate: false);
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
        Scene.BeginAnimation(OpacityProperty, null);
        OverlayTranslate.BeginAnimation(TranslateTransform.YProperty, null);
        Visibility = Visibility.Collapsed;
    }

    private void CloseOverlay()
    {
        var sceneRevision = ++_sceneRevision;
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
                ShowPane(SelectorStep.Releases, animate: true);
                Dispatcher.BeginInvoke(() => FocusRow(_nav.ReleaseIndex));
                break;
            case NavKind.Select:
                ShowPick(result.Version!);
                VersionSelected?.Invoke(result.Version!);
                break;
            case NavKind.SelectAndClose:
                ShowPickRing();
                VersionSelected?.Invoke(result.Version!);
                CloseOverlay();
                break;
            case NavKind.Play:
                Launch();
                break;
            case NavKind.Back:
                ShowPane(SelectorStep.Families, animate: true);
                Dispatcher.BeginInvoke(() => FocusFamily(_nav.FamilyIndex));
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

    // Step 2 shows a family: its card and words on the left, its releases (with their states) on the right.
    private void BuildReleases(int familyIndex)
    {
        var tile = _tiles[familyIndex];
        FamilySide.DataContext = tile;
        _syncing = true;
        _rows.Clear();
        foreach (var v in tile.Group.Versions) _rows.Add(new ReleaseRow(v, VersionStatus.ForRelease(_installs.StateOf(v))));
        ReleaseList.SelectedIndex = _nav.ReleaseIndex;
        _syncing = false;
        LaunchText.Text = $"Play {_nav.Release}";
        SizeCards();
    }

    // The highlight moved to a release (a key or a click): show it, keep the Play label on it, and keep Home's art in step.
    private void ShowPick(string version)
    {
        _syncing = true;
        ReleaseList.SelectedIndex = _nav.ReleaseIndex;
        _syncing = false;
        LaunchText.Text = $"Play {version}";
        ShowPickRing();
    }

    private void FocusFamily(int index) =>
        (FamilyList.ItemContainerGenerator.ContainerFromIndex(index) as UIElement ?? FamilyList).Focus();

    private void FocusRow(int index) =>
        (ReleaseList.ItemContainerGenerator.ContainerFromIndex(index) as UIElement ?? ReleaseList).Focus();

    // Crossfade between the two panes (motion.scene). The leaving pane stops taking input at once and is hidden
    // when its fade ends (unless another switch happened meanwhile); hidden panes still count for the sheet's size.
    private void ShowPane(SelectorStep step, bool animate)
    {
        var incoming = step == SelectorStep.Releases ? ReleasesPane : FamiliesPane;
        var outgoing = step == SelectorStep.Releases ? FamiliesPane : ReleasesPane;
        var revision = ++_paneRevision;
        incoming.BeginAnimation(OpacityProperty, null);
        outgoing.BeginAnimation(OpacityProperty, null);
        incoming.Visibility = Visibility.Visible;
        incoming.IsHitTestVisible = true;
        outgoing.IsHitTestVisible = false;
        if (!animate || Motion.Off || !IsOpen)
        {
            incoming.Opacity = 1;
            outgoing.Opacity = 0;
            outgoing.Visibility = Visibility.Hidden;
            return;
        }
        incoming.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 260, Motion.Out));
        var fade = Motion.To(0, 140, Motion.In);
        fade.Completed += (_, _) =>
        {
            if (_paneRevision == revision) outgoing.Visibility = Visibility.Hidden;
        };
        outgoing.BeginAnimation(OpacityProperty, fade);
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

    /// <summary>Paint the cached install states (cache only, no disk): the badge on every card and the row of every
    /// release of the open family. A version not read yet shows nothing.</summary>
    internal void ApplyStates()
    {
        foreach (var tile in _tiles)
            tile.SetBadge(VersionStatus.ForFamily(tile.Group.Versions.Select(_installs.StateOf).ToList()));
        foreach (var row in _rows)
            row.SetStatus(VersionStatus.ForRelease(_installs.StateOf(row.Version)));
    }

    // ---- sizing ----

    // The sheet's fixed heights (px), so the cards and the release list fit a short window without scrolling:
    // the sheet's own top + bottom margin (8 + 16) and padding (24 + 24), step 1's heading block (28 + 16 + 16 gap),
    // a card's text under the art (8 + 22 range line + 2 + 32 for a name on two lines) and the 12 px between rows;
    // step 2's header (32 + 12), the family words under its card (8 + 22 + 4 + about 170 of description) and the
    // Play row (12 + 36).
    private const double SheetChrome = 8 + 16 + 24 + 24 + 2; // + the sheet's 1 px border
    private const double FamiliesHeader = 28 + 16 + 16, CardText = 8 + 22 + 2 + 32, RowGap = 12;
    private const double ReleasesHeader = 36 + 12, FamilyWords = 8 + 22 + 4 + 170, PlayRow = 12 + 36;
    private const int Columns = 4;

    private void SizeCards()
    {
        if (_tiles.Count == 0 || FamilyList.ActualWidth <= 0) return;
        // The list reaches 12 px past the sheet's edge (cancelling the last card's gap): a card is a quarter of it
        // minus its own gap, and its art is 16:10.
        var column = Math.Floor(FamilyList.ActualWidth / Columns) - RowGap;
        var height = column * 10 / 16;
        var rows = (_tiles.Count + Columns - 1) / Columns;
        var viewport = Body.ViewportHeight;
        if (viewport > 0)
            height = Math.Min(height, (viewport - SheetChrome - FamiliesHeader + RowGap) / rows - CardText - RowGap);
        height = Math.Round(Math.Max(48, height));
        // The range goes into the art when the card is wide enough for it, on every card alike (the rows stay level).
        var rangeInArt = column >= (double)FindResource("Numeral.RangeMinCard");
        foreach (var tile in _tiles)
        {
            tile.CardWidth = column;
            tile.ThumbHeight = height;
            tile.RangeInArt = rangeInArt;
        }

        // Step 2: the family card is the side column's width at 16:10, shorter when the window is.
        var side = ((GridLength)FindResource("Size.SheetSide")).Value;
        var detail = side * 10 / 16;
        if (viewport > 0) detail = Math.Clamp(viewport - SheetChrome - ReleasesHeader - FamilyWords, 96, detail);
        foreach (var tile in _tiles) tile.DetailArtHeight = Math.Round(detail);
        // Ten releases fit; more (none today) scroll inside the list, never push Play off the sheet.
        if (viewport > 0) ReleaseList.MaxHeight = Math.Max(120, viewport - SheetChrome - ReleasesHeader - PlayRow);
    }

    // ---- events ----

    private void Launch()
    {
        if (_nav.Step != SelectorStep.Releases) return;
        LaunchRequested?.Invoke(_nav.Release);
        CloseOverlay();
    }

    private void FamilyItem_Click(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem { DataContext: FamilyTile tile }) return;
        ChooseFamily(_tiles.IndexOf(tile));
    }

    private void ReleaseList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || ReleaseList.SelectedIndex < 0) return;
        ChooseRelease(ReleaseList.SelectedIndex);
    }

    private void ReleaseRow_DoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (sender is not ListBoxItem { DataContext: ReleaseRow row } || row.Version != _nav.Release) return;
        Launch();
        e.Handled = true;
    }

    private void Launch_Click(object sender, RoutedEventArgs e) => Launch();

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
            _ => (NavKey?)null,
        };
        if (key is not { } nav) return false;
        // A focused button (Close, All versions, Play) presses itself on Enter: Close must not launch.
        if (nav == NavKey.Enter && source is System.Windows.Controls.Primitives.ButtonBase) return false;
        Act(_nav.Press(nav));
        return true;
    }

    /// <summary>A click on family card <paramref name="index"/> (the UI tests and the click handler share it).</summary>
    internal void ChooseFamily(int index) => Act(_nav.ChooseFamily(index));

    /// <summary>A click on release row <paramref name="index"/> of the open family.</summary>
    internal void ChooseRelease(int index) => Act(_nav.ChooseRelease(index));

    /// <summary>The badge text on a family card ("" while nothing is shown). For the tests and the shot report.</summary>
    internal string BadgeTextOf(string familyKey) => _tiles.First(t => t.Group.Key == familyKey).BadgeText;

    /// <summary>The status text on a release row of the open family ("" while nothing is shown).</summary>
    internal string RowStatusOf(string version) => _rows.FirstOrDefault(r => r.Version == version)?.StatusText ?? "";

    // Render mode only (UI/RenderShots.cs): open the picker, optionally on one
    // family (index into VersionCatalog.Groups) at step 2. They do nothing outside a
    // render-shots run (OriginPaths.IsScratchRoot). Nothing here picks a version.
    internal void TourOpen(int detailIndex)
    {
        if (!OriginPaths.IsScratchRoot) return;
        Show(null);
        if (detailIndex >= 0 && detailIndex < _tiles.Count && _tiles[detailIndex].Group.Versions.Count > 1)
        {
            _nav.ChooseFamily(detailIndex);
            BuildReleases(detailIndex);
            ShowPane(SelectorStep.Releases, animate: false);
        }
    }

    // Render mode only: the keyboard on a family card (step 1) or a release row (step 2), so the shots show focus.
    internal void TourFocus(int index)
    {
        if (!OriginPaths.IsScratchRoot) return;
        if (_nav.Step == SelectorStep.Releases)
        {
            _nav.ChooseRelease(index);
            ShowPick(_nav.Release);
            FocusRow(index);
        }
        else
        {
            for (var i = 0; i < 12 && _nav.FamilyIndex != index; i++)
                _nav.Press(_nav.FamilyIndex < index ? NavKey.Right : NavKey.Left);
            FocusFamily(index);
        }
    }

    internal void TourClose()
    {
        if (!OriginPaths.IsScratchRoot) return;
        ++_sceneRevision;
        Visibility = Visibility.Collapsed;
    }

    // Render mode only: size the cards now (a hand layout at a size the desktop cannot hold reports no SizeChanged).
    internal void TourRelayout()
    {
        if (OriginPaths.IsScratchRoot) SizeCards();
    }

    // Render mode only: each card's key, its container and the numbers the scrim was worked out from, in catalogue
    // order (UI/CardShots.cs measures the rendered number's contrast on them).
    internal IReadOnlyList<(string Key, FrameworkElement? Item, double ZoneLuminance, double Strength, double Reach)> TourCards() =>
        _tiles.Select(t => (t.Group.Key, FamilyList.ItemContainerGenerator.ContainerFromItem(t) as FrameworkElement,
            t.ZoneLuminance, t.ScrimStrength, t.ScrimReach)).ToList();

    // Render mode only: put other art on one card (the shipped art is all dark; the shots also need light art) and
    // work its scrim out exactly as for real art.
    internal void TourSwapArt(int index, BitmapSource art)
    {
        if (!OriginPaths.IsScratchRoot || index < 0 || index >= _tiles.Count) return;
        _tiles[index].Swap(art, VersionCardNumeral.Spec());
    }

    // Render mode only: the real art back on every card.
    internal void TourRestoreArt()
    {
        if (!OriginPaths.IsScratchRoot) return;
        var spec = VersionCardNumeral.Spec();
        foreach (var t in _tiles) t.LoadAsync(spec);
    }

    // The brush for a status dot, from the same tokens as every other dot (DESIGN-SYSTEM.md 11.9).
    private static Brush DotBrush(StatusDot dot) => (Brush)Application.Current.FindResource(dot switch
    {
        StatusDot.Success => "Brush.Success",
        StatusDot.Info => "Brush.Info",
        StatusDot.Warning => "Brush.Warning",
        _ => "Brush.TextMuted",
    });

    // One release row's data: the version and what is known about it on disk.
    private sealed class ReleaseRow : INotifyPropertyChanged
    {
        private StatusLine? _status;

        public ReleaseRow(string version, StatusLine? status)
        {
            Version = version;
            _status = status;
        }

        public string Version { get; }
        public string StatusText => _status?.Text ?? "";
        public Brush? DotBrush => _status == null ? null : VersionSelectOverlay.DotBrush(_status.Dot);
        public Visibility StatusVisibility => _status == null ? Visibility.Collapsed : Visibility.Visible;

        public void SetStatus(StatusLine? status)
        {
            if (Equals(_status, status)) return;
            _status = status;
            Raise(nameof(StatusText));
            Raise(nameof(DotBrush));
            Raise(nameof(StatusVisibility));
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        private void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }

    // One family tile's data. The thumbnails arrive after decode; ThumbHeight
    // follows the row width so the art keeps 16:10 at every window size.
    private sealed class FamilyTile : INotifyPropertyChanged
    {
        private ImageSource? _grey, _colour;
        private double _thumbHeight = 82, _detailArtHeight = 200, _cardWidth = double.NaN;
        private bool _rangeInArt;
        private double _scrimStrength = 1, _scrimReach = 1;
        private StatusLine? _badge;

        public FamilyTile(VersionGroup group)
        {
            Group = group;
        }

        public VersionGroup Group { get; }
        public string Range => Group.Title;
        public string Name => Group.Tagline;
        public string Description => Group.Description;
        public ImageSource? Grey { get => _grey; private set { _grey = value; Raise(nameof(Grey)); } }
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
        // The badge on the card: nothing until every release of the family has been read.
        public string BadgeText => _badge?.Text ?? "";
        public Brush? BadgeDot => _badge == null ? null : DotBrush(_badge.Dot);
        public Visibility BadgeVisibility => _badge == null ? Visibility.Collapsed : Visibility.Visible;
        public void SetBadge(StatusLine? badge)
        {
            if (Equals(_badge, badge)) return;
            _badge = badge;
            Raise(nameof(BadgeText));
            Raise(nameof(BadgeDot));
            Raise(nameof(BadgeVisibility));
        }
        // The number set into the art is the family's key ("1.21", "26"); the range beside it or under the card.
        public string Number => Group.Key;
        public string ArtRange => _rangeInArt ? Group.Title : "";
        public Visibility RangeBelowVisibility => _rangeInArt ? Visibility.Collapsed : Visibility.Visible;
        public bool RangeInArt
        {
            get => _rangeInArt;
            set
            {
                if (_rangeInArt == value) return;
                _rangeInArt = value;
                Raise(nameof(ArtRange));
                Raise(nameof(RangeBelowVisibility));
            }
        }
        // How strong the corner shade under the number is for this art (CardArtScrim), and the art's mean
        // luminance where the number stands (only the shot report reads that).
        public double ScrimStrength { get => _scrimStrength; private set { _scrimStrength = value; Raise(nameof(ScrimStrength)); } }
        public double ScrimReach { get => _scrimReach; private set { _scrimReach = value; Raise(nameof(ScrimReach)); } }
        public double ZoneLuminance { get; private set; }

        public void LoadAsync(ScrimSpec spec)
        {
            var key = Group.Key;
            _ = Task.Run(() => Work(ArtStage.Decode($"{key}-tg.jpg", 320), ArtStage.Decode($"{key}-tc.jpg", 320), spec))
                .ContinueWith(t => Apply(t.Result), TaskScheduler.FromCurrentSynchronizationContext());
        }

        // Render mode only (VersionSelectOverlay.TourSwapArt): put other art on the card and work its scrim out the
        // same way the real art's is.
        public void Swap(BitmapSource art, ScrimSpec spec) => Apply(Work(art, art, spec));

        private static (ImageSource? Grey, ImageSource? Colour, ScrimResult Result) Work(BitmapSource? grey, BitmapSource? colour, ScrimSpec spec)
        {
            // Both states of the thumb sit under the same number, so the shade must hold for the brighter one.
            var result = new[] { grey, colour }.Select(b => Scrim(b, spec)).Where(r => r != null).Select(r => r!.Value)
                .DefaultIfEmpty(new ScrimResult(1, 1, 0)).MaxBy(r => (r.Reach, r.Strength));
            return (grey, colour, result);
        }

        private void Apply((ImageSource? Grey, ImageSource? Colour, ScrimResult Result) w)
        {
            Grey = w.Grey;
            Colour = w.Colour;
            ZoneLuminance = w.Result.ZoneLuminance;
            ScrimReach = w.Result.Reach;
            ScrimStrength = w.Result.Strength;
        }

        // The art-aware shade for one decoded thumb: read its pixels (frozen, so any thread may), average them to a
        // coarse grid and ask CardArtScrim how strong the corner shade must be. Null if the art did not decode.
        private static ScrimResult? Scrim(BitmapSource? bmp, ScrimSpec spec)
        {
            if (bmp == null) return null;
            BitmapSource bgra = bmp.Format == PixelFormats.Bgra32 || bmp.Format == PixelFormats.Bgr32
                ? bmp : new FormatConvertedBitmap(bmp, PixelFormats.Bgra32, null, 0);
            var width = bgra.PixelWidth;
            var height = bgra.PixelHeight;
            var stride = width * 4;
            var pixels = new byte[stride * height];
            bgra.CopyPixels(pixels, stride, 0);
            return CardArtScrim.Analyse(CardArtGrid.FromBgra(pixels, width, height, stride, GridCols, GridRows), spec);
        }

        private const int GridCols = 32, GridRows = 18;

        public event PropertyChangedEventHandler? PropertyChanged;
        private void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
