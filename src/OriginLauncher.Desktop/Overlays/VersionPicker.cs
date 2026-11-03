using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Media.Transformation;
using Avalonia.Threading;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shots;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// The version picker (the Windows launcher's VersionSelectOverlay, DESIGN-SYSTEM.md 11.3), in the sheet layer under
// the bar. Step 1: "Choose a version" over a 4-column grid of family cards (art, range, name, install status), a ring
// on the family holding the saved version. Clicking a family with several releases expands it (animated from its
// card) into a panel with one compact release dropdown; picking a release IS the selection (Picked), the picker stays
// up. A one-release family is picked and closed in one click. Esc or a click outside returns Home (Dismissed); Back
// ("All versions") returns to the grid. Nothing here installs or launches, and nothing says "Download".
// The keys and the step logic are the shared Core state machine (UI/Controls/VersionSelectModel.cs), not retyped here.
public sealed class VersionPicker : Panel
{
    // The card grid's fixed chrome (the WPF overlay's SizeCards constants): sheet padding + header, the card text
    // block, the gaps, the panel and card edges.
    private const double SheetChrome = 74, SheetGutterX = 48, SheetInnerX = 50, FamiliesHeader = 60, CardText = 84,
        RowGap = 12, PanelEdges = 34, CardEdges = 2, CardsDimmed = 0.12;

    private readonly VersionSelectNav _nav = new(VersionCatalog.Groups, 4);
    private readonly List<FamilyCard> _cards = new();
    private readonly Grid _cardGrid = new() { RowSpacing = RowGap, ColumnSpacing = RowGap };
    private readonly Panel _stage = new() { Margin = new Thickness(0, Space.S2, 0, 0) };
    private readonly Border _sheet;
    private readonly Grid _familiesPane = new() { RowDefinitions = new RowDefinitions("Auto,*") };
    private readonly Border _panelCatcher = new() { Background = Brushes.Transparent, IsVisible = false };
    private readonly Border _panel;
    private readonly StackPanel _panelSide;
    private readonly Border _panelArt = new() { CornerRadius = new CornerRadius(Radius.Md), ClipToBounds = true };
    private readonly Image _panelImage = new() { Stretch = Stretch.UniformToFill };
    private readonly TextBlock _panelRange = Ui.Caption("");
    private readonly TextBlock _panelName = Ui.Ellipsis(Ui.Heading(""));
    private readonly Dropdown _releases = new(Sizes.ControlLg, null, "Choose a version") { Placeholder = "Choose a version" };
    private readonly Pressable _back = Ui.Button("All versions", Look.Ghost, Icons.Back, Sizes.ControlMd);
    private readonly Pressable _close = Ui.IconButton(Icons.Close, "Close");
    private bool _syncingReleases;

    public event Action<string>? Picked;
    public event Action? Dismissed;

    /// <summary>Where install states come from (the shared disk check; render runs pass a made-up one).</summary>
    internal InstalledVersions Installs { get; }

    public VersionPicker(string selected) : this(selected, InstalledVersions.Shared) { }

    internal VersionPicker(string selected, InstalledVersions installs)
    {
        Installs = installs;
        Focusable = true;
        _nav.Open(VersionCatalog.IsSupported(selected) ? selected : VersionCatalog.DefaultVersion);

        // The scrim over the page: a click on it returns Home.
        var backdrop = new Border { Background = Palette.Scrim };
        backdrop.PointerPressed += (_, e) => { Dismissed?.Invoke(); e.Handled = true; };
        Children.Add(backdrop);

        // Header: "Choose a version" + the counts, Close top right.
        var title = Ui.Heading("Choose a version");
        var caption = Ui.Caption($"{VersionCatalog.Groups.Count} families, {VersionCatalog.SupportedVersions.Count} releases. Origin installs what each one needs.");
        caption.Margin = new Thickness(0, 2, 0, 0);
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, 0, 0, Space.S4) };
        header.Children.Add(Ui.Column(0, title, caption).At(0));
        _close.VerticalAlignment = VerticalAlignment.Top;
        _close.Margin = new Thickness(0, -4, -8, 0);
        _close.Click += (_, _) => Dismissed?.Invoke();
        header.Children.Add(_close.At(1));
        _familiesPane.Children.Add(header.At(0, 0));
        _familiesPane.Children.Add(_cardGrid.At(0, 1));
        _familiesPane.Transitions = [new DoubleTransition { Property = OpacityProperty, Duration = Motion.Enter }];

        for (var i = 0; i < 4; i++) _cardGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Auto));
        var groups = VersionCatalog.Groups;
        for (var r = 0; r < (groups.Count + 3) / 4; r++) _cardGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        for (var i = 0; i < groups.Count; i++)
        {
            var card = new FamilyCard(groups[i]);
            var index = i;
            card.Click += (_, _) => Act(_nav.ChooseFamily(index));
            _cards.Add(card);
            _cardGrid.Children.Add(card.At(i % 4, i / 4));
        }

        // Step 2: the family panel over the dimmed cards; a click beside it closes the picker.
        _panelCatcher.PointerPressed += (_, e) => { Dismissed?.Invoke(); e.Handled = true; };
        RenderOptions.SetBitmapInterpolationMode(_panelImage, BitmapInterpolationMode.HighQuality);
        _panelArt.Child = _panelImage;
        _back.Margin = new Thickness(-8, -4, 0, Space.S2);
        _back.Padding = new Thickness(8, 0, 12, 0);
        _back.Click += (_, _) => Act(_nav.GoBack());
        _panelRange.Margin = new Thickness(0, Space.S3, 0, 0);
        _panelName.Margin = new Thickness(0, 2, 0, 0);
        _releases.Margin = new Thickness(0, Space.S4, 0, 0);
        _releases.HorizontalAlignment = HorizontalAlignment.Stretch;
        _releases.Selected += OnReleaseChosen;
        _panelSide = Ui.Column(0, _back, _panelArt, _panelRange, _panelName, _releases);
        _panel = new Border
        {
            Background = Palette.Card, BorderBrush = Palette.Line, BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(Radius.Lg), Padding = new Thickness(Space.S4), Child = _panelSide,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            BoxShadow = Palette.OverlayShadow, IsVisible = false, RenderTransformOrigin = RelativePoint.TopLeft,
        };

        var inner = new Panel();
        inner.Children.Add(_familiesPane);
        inner.Children.Add(_panelCatcher);
        inner.Children.Add(_panel);
        _sheet = new Border
        {
            Background = Palette.Sheet, BorderBrush = Palette.Line, BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(Radius.Dialog), Padding = new Thickness(Space.S6), BoxShadow = Palette.OverlayShadow,
            Child = inner, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(Space.S6, Space.S2, Space.S6, Space.S4),
        };
        // A click on the sheet itself is not a click outside.
        _sheet.PointerPressed += (_, e) => e.Handled = true;
        _stage.Children.Add(_sheet);
        Children.Add(_stage);

        ApplyStates();
        ShowPick();
        _stage.SizeChanged += (_, _) => SizeCards();
        AttachedToVisualTree += (_, _) =>
        {
            Installs.Changed += OnInstallChanged;
            if (!ShotRunner.Active) _ = Installs.RefreshAllAsync();
            Dispatcher.UIThread.Post(() => _cards[_nav.FamilyIndex].Focus(), DispatcherPriority.Input);
        };
        DetachedFromVisualTree += (_, _) => Installs.Changed -= OnInstallChanged;
    }

    // ---- Steps ----

    private void Act(NavResult r)
    {
        switch (r.Kind)
        {
            case NavKind.FamilyFocused:
                _cards[r.FamilyIndex].Focus();
                break;
            case NavKind.OpenedFamily:
                BuildReleases();
                Expand(r.FamilyIndex);
                Dispatcher.UIThread.Post(() => _releases.Trigger.Focus(), DispatcherPriority.Input);
                break;
            case NavKind.Select when r.Version != null:
                ShowPick();
                Picked?.Invoke(r.Version);
                break;
            case NavKind.SelectAndClose when r.Version != null:
                ShowPick();
                Picked?.Invoke(r.Version);
                Dismissed?.Invoke();
                break;
            case NavKind.OpenList:
                _releases.SetOpen(true);
                break;
            case NavKind.Back:
                Collapse();
                _cards[r.FamilyIndex].Focus();
                break;
            case NavKind.Close:
                Dismissed?.Invoke();
                break;
        }
    }

    private void OnReleaseChosen(string version)
    {
        if (_syncingReleases) return;
        var index = -1;
        var versions = _nav.Family.Versions;
        for (var i = 0; i < versions.Count; i++) if (versions[i] == version) index = i;
        Act(_nav.ChooseRelease(index));
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (e.Handled || _releases.IsOpen) return;
        NavKey? key = e.Key switch
        {
            Key.Left => NavKey.Left,
            Key.Right => NavKey.Right,
            Key.Up => NavKey.Up,
            Key.Down => NavKey.Down,
            Key.Home => NavKey.Home,
            Key.End => NavKey.End,
            Key.Escape => NavKey.Escape,
            Key.Back => NavKey.Backspace,
            _ => null,
        };
        if (key is not { } k) return; // Enter and Space are each control's own click
        var result = _nav.Press(k);
        if (result.Kind == NavKind.None) return;
        Act(result);
        e.Handled = true;
    }

    private void BuildReleases()
    {
        var family = _nav.Family;
        _panelImage.Source = Assets.Thumb(family.Key);
        _panelRange.Text = Range(family);
        _panelName.Text = family.Tagline;
        _syncingReleases = true;
        _releases.Items = family.Versions
            .Select(v => new DropItem(v, v, null, VersionStatus.ShowsInstalledDot(Installs.StateOf(v)) ? Palette.HueEmerald : null))
            .ToList();
        _releases.SetValue(_nav.Release);
        _syncingReleases = false;
    }

    // Step 2 opens out of its card: the panel starts on the card's rect and grows into place (220 ms), the cards dim.
    private void Expand(int familyIndex)
    {
        _panelCatcher.IsVisible = true;
        _panel.IsVisible = true;
        _familiesPane.Opacity = CardsDimmed;
        _familiesPane.IsHitTestVisible = false;
        if (Motion.Off) return;
        Dispatcher.UIThread.Post(() =>
        {
            var card = _cards[familyIndex];
            if (card.TranslatePoint(new Point(0, 0), _panel.Parent as Visual ?? _panel) is not { } from
                || _panel.TranslatePoint(new Point(0, 0), _panel.Parent as Visual ?? _panel) is not { } to
                || _panel.Bounds.Width <= 0 || _panel.Bounds.Height <= 0) return;
            _panel.Transitions = null;
            var sx = card.Bounds.Width / _panel.Bounds.Width;
            var sy = card.Bounds.Height / _panel.Bounds.Height;
            _panel.RenderTransform = TransformOperations.Parse(
                $"translate({Fmt.N(from.X - to.X, "0.##")}px, {Fmt.N(from.Y - to.Y, "0.##")}px) scale({Fmt.N(sx, "0.###")}, {Fmt.N(sy, "0.###")})");
            _panel.Opacity = 0;
            Dispatcher.UIThread.Post(() =>
            {
                _panel.Transitions =
                [
                    new TransformOperationsTransition { Property = RenderTransformProperty, Duration = Motion.Enter, Easing = new CubicEaseOut() },
                    new DoubleTransition { Property = OpacityProperty, Duration = Motion.Ms(110), Easing = new CubicEaseOut() },
                ];
                _panel.RenderTransform = TransformOperations.Identity;
                _panel.Opacity = 1;
            }, DispatcherPriority.Render);
        }, DispatcherPriority.Loaded);
    }

    private void Collapse()
    {
        _releases.SetOpen(false);
        _panel.Transitions = null;
        _panel.RenderTransform = null;
        _panel.Opacity = 1;
        _panel.IsVisible = false;
        _panelCatcher.IsVisible = false;
        _familiesPane.Opacity = 1;
        _familiesPane.IsHitTestVisible = true;
    }

    // ---- Cards ----

    /// <summary>The ring goes on the family holding the saved version.</summary>
    private void ShowPick()
    {
        var current = _nav.CurrentVersion;
        foreach (var card in _cards) card.IsCurrent = current != null && card.Group.Versions.Contains(current);
    }

    private void ApplyStates()
    {
        foreach (var card in _cards)
            card.ShowStatus(VersionStatus.ForFamily(card.Group.Versions.Select(Installs.StateOf).ToList()));
    }

    private void OnInstallChanged(string version) => Dispatcher.UIThread.Post(() =>
    {
        ApplyStates();
        if (_panel.IsVisible) BuildReleases();
    });

    private void SizeCards()
    {
        var stageW = _stage.Bounds.Width;
        var stageH = _stage.Bounds.Height;
        if (_cards.Count == 0 || stageW <= 0 || stageH <= 0) return;
        var sheet = Math.Min(stageW - SheetGutterX, Math.Clamp(stageW * Sizes.SheetShare, Sizes.SheetMin, Sizes.SheetMax));
        var column = Math.Floor((sheet - SheetInnerX + RowGap) / 4) - RowGap;
        var rows = (_cards.Count + 3) / 4;
        var fit = (stageH - SheetChrome - FamiliesHeader + RowGap) / rows - CardText - RowGap;
        if ((column - CardEdges) * 9 / 16 > fit) column = Math.Floor(fit * 16 / 9 + CardEdges);
        column = Math.Max(column, 96);
        var art = Math.Round((column - CardEdges) * 9 / 16);
        foreach (var card in _cards) card.Fit(column, art);
        _sheet.Width = 4 * column + 3 * RowGap + SheetInnerX;

        // Step 2's panel: a share of the sheet, never taller than the cards' block (the sheet keeps its size).
        var panel = Math.Round(Math.Clamp((_sheet.Width - SheetInnerX) * Sizes.FamilyPanelShare, Sizes.FamilyPanelMin, Sizes.FamilyPanelMax));
        _panel.Width = panel;
        var inner = panel - PanelEdges;
        var block = FamiliesHeader + rows * (art + CardText) + (rows - 1) * RowGap;
        double words = 0;
        foreach (var child in _panelSide.Children)
        {
            if (ReferenceEquals(child, _panelArt)) continue;
            child.Measure(new Size(inner, double.PositiveInfinity));
            words += child.DesiredSize.Height;
        }
        _panelArt.Height = Math.Round(Math.Max(96, Math.Min(inner * 9 / 16, block - PanelEdges - words)));
    }

    /// <summary>"1.21 - 1.21.11": the catalog's range with an ASCII dash (UI strings stay ASCII).</summary>
    private static string Range(VersionGroup g) => g.Title.Replace('\u2013', '-').Replace('\u2014', '-');

    // ---- Render runs ----

    internal IReadOnlyList<Pressable> Cards => _cards;
    internal Dropdown Releases => _releases;

    /// <summary>Opens a family's panel as a click on its card would.</summary>
    internal void OpenFamily(string key)
    {
        for (var i = 0; i < _cards.Count; i++)
            if (_cards[i].Group.Key == key) { Act(_nav.ChooseFamily(i)); return; }
    }

    /// <summary>Opens the release list in the window itself (a render run has no popup windows).</summary>
    internal void OpenReleaseList()
    {
        foreach (var c in _releases.Children)
            if (c is Popup p) p.ShouldUseOverlayLayer = true;
        _releases.SetOpen(true);
    }

    // One family card: art (16:9, the "SELECTED" capsule on the saved family), range, name, install status.
    // Hover lifts the face 4 px and lightens it; the ring sits 4 px outside the card, 2 px accent.
    private sealed class FamilyCard : Pressable
    {
        private readonly Border _ring = new()
        {
            Margin = new Thickness(-4), CornerRadius = new CornerRadius(Radius.RingLg), BorderThickness = new Thickness(2),
            BorderBrush = Palette.Accent, IsVisible = false, IsHitTestVisible = false,
        };
        private readonly Border _face;
        private readonly Border _art = new() { CornerRadius = new CornerRadius(Radius.Lg, Radius.Lg, 0, 0), ClipToBounds = true };
        private readonly Border _tag;
        private readonly StackPanel _statusRow;
        private readonly Avalonia.Controls.Shapes.Ellipse _statusDot = Ui.Dot(Palette.TextMuted);
        private readonly TextBlock _statusText = Ui.Caption("");

        public VersionGroup Group { get; }

        public FamilyCard(VersionGroup group) : base(Look.Plain, null, group.Tagline)
        {
            Group = group;
            CornerRadius = new CornerRadius(Radius.Lg);
            var image = new Image { Source = Assets.Thumb(group.Key), Stretch = Stretch.UniformToFill };
            RenderOptions.SetBitmapInterpolationMode(image, BitmapInterpolationMode.HighQuality);
            _tag = Ui.Capsule(new Border
            {
                Height = Sizes.Badge, Padding = new Thickness(8, 0), Background = Palette.Brush("ScrimMid"),
                Child = Ui.Eyebrow("Selected", Palette.Text, TypeScale.LabelTracking),
                HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(Space.S2), IsVisible = false,
            });
            var artPanel = new Panel();
            artPanel.Children.Add(image);
            artPanel.Children.Add(_tag);
            _art.Child = artPanel;

            var range = Ui.Ellipsis(Ui.Text(Range(group), TypeScale.Title, FontWeight.Bold, Palette.Text, -0.005));
            var name = Ui.Ellipsis(Ui.Caption(group.Tagline));
            name.Margin = new Thickness(0, 2, 0, 0);
            _statusText.Foreground = Palette.TextDim;
            _statusRow = Ui.Row(6, _statusDot, _statusText);
            _statusRow.Margin = new Thickness(0, 4, 0, 0);
            var words = Ui.Column(0, range, name, _statusRow);
            words.Margin = new Thickness(12, 10, 12, 12);

            _face = new Border
            {
                Background = Palette.Card, BorderBrush = Palette.Line, BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(Radius.Lg), ClipToBounds = true, Child = Ui.Column(0, _art, words),
                Transitions =
                [
                    new TransformOperationsTransition { Property = RenderTransformProperty, Duration = Motion.Hover },
                ],
            };
            var host = new Panel();
            host.Children.Add(_ring);
            host.Children.Add(_face);
            Child = host;
            StateChanged += _ => Paint();
            Paint();
        }

        public bool IsCurrent
        {
            get => _ring.IsVisible;
            set { _ring.IsVisible = value; _tag.IsVisible = value; }
        }

        public void Fit(double width, double art)
        {
            Width = width;
            _art.Height = art;
        }

        /// <summary>The status line; null keeps its height empty (not checked yet).</summary>
        public void ShowStatus(StatusLine? line)
        {
            _statusRow.Opacity = line == null ? 0 : 1;
            if (line == null) return;
            _statusText.Text = line.Text;
            _statusDot.Fill = line.Dot switch
            {
                StatusDot.Success => Palette.Success,
                StatusDot.Info => Palette.Info,
                StatusDot.Warning => Palette.Warning,
                _ => Palette.TextMuted,
            };
        }

        private void Paint()
        {
            var hot = (IsHovered || ForceHover) && IsEffectivelyEnabled;
            _face.Background = hot ? Palette.CardHover : Palette.Card;
            _face.BorderBrush = hot ? Palette.LineStrong : Palette.Line;
            _face.RenderTransform = TransformOperations.Parse(hot ? "translateY(-4px)" : "translateY(0px)");
        }
    }
}
