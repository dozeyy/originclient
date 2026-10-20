using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Controls;

// The version picker (Poster): a whole-window view over Home's art, no header, just a
// floating X. One row of families (newest first), each a grey thumbnail that turns to
// colour when hovered or picked; under it the picked family's versions as chips, then
// Play. Browsing raises VersionSelected, so Home's art and family label follow
// live behind the dim. Keys: Left/Right change family, Enter plays, Esc closes.
// The control owns no launch logic beyond raising LaunchRequested.
public partial class VersionSelectOverlay : UserControl
{
    private readonly List<FamilyTile> _tiles;
    private bool _syncing;
    private long _sceneRevision;
    private string? _version;

    public VersionSelectOverlay()
    {
        InitializeComponent();
        _tiles = VersionCatalog.Groups.Select(g => new FamilyTile(g)).ToList();
        FamilyList.ItemsSource = _tiles;
        FamilyList.SizeChanged += (_, _) => SizeThumbs();
        PreviewKeyDown += OnSheetKey;
        // Thumbnails decode off the UI thread at the size they draw (frozen bitmaps).
        foreach (var tile in _tiles) tile.LoadAsync();
    }

    /// <summary>Raised when the player picks a specific version (keeps Home's
    /// current selection in sync as they browse).</summary>
    public event Action<string>? VersionSelected;

    /// <summary>Raised when the player clicks Play on a version.</summary>
    public event Action<string>? LaunchRequested;

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(string? currentVersion)
    {
        var current = VersionCatalog.IsSupported(currentVersion) ? currentVersion! : VersionCatalog.DefaultVersion;
        foreach (var tile in _tiles) tile.IsCurrent = tile.Group.Versions.Contains(current);
        SelectFamily(_tiles.First(t => t.IsCurrent), current, raise: false);

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

        Dispatcher.BeginInvoke(() =>
            (FamilyList.ItemContainerGenerator.ContainerFromItem(FamilyList.SelectedItem) as UIElement ?? FamilyList).Focus());
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

    // ---- selection ----

    // Make a family the picked one: its chips, its eyebrow, and (unless asked for)
    // a version in it - the given one, else its newest.
    private void SelectFamily(FamilyTile tile, string? version, bool raise)
    {
        _syncing = true;
        FamilyList.SelectedItem = tile;
        _syncing = false;
        FamilyList.ScrollIntoView(tile);

        ChipsLabel.Text = FamilyTheme.Of(tile.Group.Newest).Label;
        ChipsPanel.Children.Clear();
        var pick = version != null && tile.Group.Versions.Contains(version) ? version : tile.Group.Newest;
        foreach (var v in tile.Group.Versions)
        {
            var chip = new RadioButton
            {
                Content = v,
                GroupName = "OriginVersionChip",
                Style = (Style)FindResource("VersionChip"),
                IsChecked = v == pick,
            };
            System.Windows.Automation.AutomationProperties.SetName(chip, $"Minecraft {v}");
            chip.Checked += (_, _) => { if (!_syncing) PickVersion(v, raise: true); };
            chip.MouseDoubleClick += (_, _) => Launch();
            ChipsPanel.Children.Add(chip);
        }
        PickVersion(pick, raise);
    }

    private void PickVersion(string version, bool raise)
    {
        _version = version;
        LaunchText.Text = $"Play {version}";
        LaunchButton.IsEnabled = true;
        if (raise) VersionSelected?.Invoke(version);
    }

    private void SizeThumbs()
    {
        if (_tiles.Count == 0 || FamilyList.ActualWidth <= 0) return;
        // One row when the window is wide enough for readable tiles, else two: a whole-window view
        // should fill its space with bigger art, not shrink eight thumbnails into a strip.
        var columns = FamilyList.ActualWidth >= OneRowMinWidth ? _tiles.Count : (_tiles.Count + 1) / 2;
        if (columns != _columns)
        {
            _columns = columns;
            var panel = new FrameworkElementFactory(typeof(UniformGrid));
            panel.SetValue(UniformGrid.ColumnsProperty, columns);
            FamilyList.ItemsPanel = new ItemsPanelTemplate(panel);
            // New panel, new containers: put the keyboard back on the picked family.
            if (IsOpen) Dispatcher.BeginInvoke(() =>
                (FamilyList.ItemContainerGenerator.ContainerFromItem(FamilyList.SelectedItem) as UIElement)?.Focus());
        }
        // Each column is the row's share minus the tile's 8 px right margin; 16:9 thumbs.
        var column = FamilyList.ActualWidth / columns - 8;
        var height = Math.Round(Math.Max(48, column * 9 / 16));
        foreach (var tile in _tiles) tile.ThumbHeight = height;
    }

    // Below this list width (px) the eight families wrap to two rows of four.
    private const double OneRowMinWidth = 1120;
    private int _columns;

    private void StepFamily(int delta)
    {
        var index = FamilyList.SelectedIndex < 0 ? 0 : FamilyList.SelectedIndex;
        var next = index + delta;
        // A row step that would leave the grid does nothing (it must not jump to the last tile).
        if (Math.Abs(delta) > 1 && (next < 0 || next >= _tiles.Count)) return;
        next = Math.Clamp(next, 0, _tiles.Count - 1);
        if (next == index) return;
        SelectFamily(_tiles[next], null, raise: true);
        (FamilyList.ItemContainerGenerator.ContainerFromIndex(next) as UIElement)?.Focus();
    }

    private void Launch()
    {
        if (_version == null) return;
        LaunchRequested?.Invoke(_version);
        CloseOverlay();
    }

    // ---- events ----

    private void FamilyList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || FamilyList.SelectedItem is not FamilyTile tile) return;
        SelectFamily(tile, null, raise: true);
    }

    private void Launch_Click(object sender, RoutedEventArgs e) => Launch();

    private void Close_Click(object sender, RoutedEventArgs e) => CloseOverlay();

    private void Backdrop_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        CloseOverlay();
        e.Handled = true;
    }

    private void OnSheetKey(object sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Escape: CloseOverlay(); break;
            case Key.Left: StepFamily(-1); break;
            case Key.Right: StepFamily(1); break;
            // Two rows: Up / Down move a whole row. One row: nothing to do, the keys fall through.
            case Key.Up when _columns > 0 && _columns < _tiles.Count: StepFamily(-_columns); break;
            case Key.Down when _columns > 0 && _columns < _tiles.Count: StepFamily(_columns); break;
            // Enter plays from anywhere in the view, except on a focused button,
            // which presses itself (Close must not launch).
            case Key.Enter when e.OriginalSource is not Button: Launch(); break;
            default: return;
        }
        e.Handled = true;
    }

    // Render mode only (UI/RenderShots.cs): open the picker, optionally on one
    // family (index into VersionCatalog.Groups). They do nothing outside a
    // render-shots run (OriginPaths.IsScratchRoot): picking a family raises
    // VersionSelected, which saves the player's version.
    internal void TourOpen(int detailIndex)
    {
        if (!OriginPaths.IsScratchRoot) return;
        Show(null);
        if (detailIndex >= 0 && detailIndex < _tiles.Count)
            SelectFamily(_tiles[detailIndex], null, raise: true);
    }

    internal void TourClose()
    {
        if (!OriginPaths.IsScratchRoot) return;
        ++_sceneRevision;
        Visibility = Visibility.Collapsed;
    }

    // One family tile's data. The thumbnails arrive after decode; ThumbHeight
    // follows the row width so the art keeps 16:9 at every window size.
    private sealed class FamilyTile : INotifyPropertyChanged
    {
        private ImageSource? _grey, _colour;
        private double _thumbHeight = 82;
        private bool _current;

        public FamilyTile(VersionGroup group)
        {
            Group = group;
            var accent = new SolidColorBrush(FamilyTheme.Of(group.Newest).Accent);
            accent.Freeze();
            Accent = accent;
        }

        public VersionGroup Group { get; }
        public Brush Accent { get; }
        public string Range => Group.Title;
        public string Name => Group.Tagline;
        public ImageSource? Grey { get => _grey; private set { _grey = value; Raise(nameof(Grey)); } }
        public ImageSource? Colour { get => _colour; private set { _colour = value; Raise(nameof(Colour)); } }
        public double ThumbHeight
        {
            get => _thumbHeight;
            set { if (Math.Abs(_thumbHeight - value) < 0.5) return; _thumbHeight = value; Raise(nameof(ThumbHeight)); }
        }
        public bool IsCurrent
        {
            get => _current;
            set { _current = value; Raise(nameof(CurrentVisibility)); }
        }
        public Visibility CurrentVisibility => _current ? Visibility.Visible : Visibility.Collapsed;

        public void LoadAsync()
        {
            var key = Group.Key;
            _ = Task.Run(() => (ArtStage.Decode($"{key}-tg.jpg", 320), ArtStage.Decode($"{key}-tc.jpg", 320)))
                .ContinueWith(t =>
                {
                    Grey = t.Result.Item1;
                    Colour = t.Result.Item2;
                }, TaskScheduler.FromCurrentSynchronizationContext());
        }

        public event PropertyChangedEventHandler? PropertyChanged;
        private void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
