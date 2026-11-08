using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using OriginLauncher.App.Core.Promos;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Shell;

// The top bar (DESIGN-SYSTEM.md 11.4, 11.5, 11.7): 48 tall, colour.nav (88 % on Home over the art, opaque on
// every other page) with a 1 px line hairline. A row: brand lockup (the Website link), the tabs centred in the
// space left between the two groups (at least 24 from each), then the right group: update chip, Store, Import,
// Friends, divider, account control, minimise, close. Empty space drags the window.
public sealed class TopBar : Border
{
    private const string WebsiteUrl = "https://originclient.org";
    private const string StoreUrl = "https://originclient.org/store";

    private readonly MainWindow _window;
    private readonly Dictionary<PageKey, (Pressable Tab, Ellipse Dot)> _tabs = new();
    private readonly Pressable _brand;
    private readonly Image _wordmark;
    private readonly IconView _brandArrow;
    private readonly Pressable _update;
    private readonly TextBlock _updateLabel;
    private string? _updateNote;
    // The bar's two surfaces (WPF BarSurface / BarGlass): opaque on every page but Home, colour.nav at 88 % over the art
    // on Home; a page switch crossfades them over 260 ms, quintic out (WPF MainWindow.SetBarSurface).
    private readonly Border _solid = new() { Opacity = 0 };
    private readonly Border _glass = new() { Background = Palette.Nav };
    private readonly Tween _solidMotion, _glassMotion;
    private readonly Pressable _updateIcon;
    private readonly Pressable _store;
    private readonly TextBlock _storeLabel;
    private readonly IconView _storeArrow;
    private readonly Ellipse _friendsDot;
    private readonly Pressable _account;
    private readonly StackPanel _tabRow;
    private readonly StackPanel _right;
    private readonly StackPanel _left;

    /// <summary>The account control (shots open its drawer through it).</summary>
    public Pressable AccountButton => _account;
    public Pressable FriendsButton { get; }
    public Pressable ImportButton { get; }
    public Pressable StoreButton => _store;
    public Pressable UpdateButton => _update;

    public TopBar(MainWindow window)
    {
        _window = window;
        Height = Sizes.Bar;
        BorderBrush = Palette.Line;
        BorderThickness = new Thickness(0, 0, 0, 1);

        // Brand lockup: logo 24, 6, wordmark 16 in a ghost hit area 32 tall; the external arrow on hover.
        _wordmark = Ui.Wordmark(Sizes.WordmarkBar);
        _brandArrow = Ui.Icon(Icons.ArrowUpRight, 12, Palette.TextMuted);
        _brandArrow.Opacity = 0;
        if (!Motion.Off) _brandArrow.Transitions = [new DoubleTransition { Property = OpacityProperty, Duration = Motion.Hover }];
        _brand = new Pressable(Look.Ghost, Ui.Row(6, Ui.Logo(Sizes.LogoBar), _wordmark, _brandArrow), "Open originclient.org")
        {
            Height = Sizes.ControlMd, Padding = new Thickness(6, 4), CornerRadius = new CornerRadius(Radius.Sm),
        };
        ToolTip.SetTip(_brand, "originclient.org");
        _brand.StateChanged += p => _brandArrow.Opacity = p.IsHovered || p.ForceHover ? 1 : 0;
        _brand.Click += (_, _) => ExternalLink.Open(new Uri(WebsiteUrl));
        _left = Ui.Row(0, _brand);
        _left.Margin = new Thickness(12, 0, 0, 0);

        // Tabs: md 32, pad X 12, type.label (0.14 em), gap 4; selected = text + a 4 px accent dot under the label.
        _tabRow = Ui.Row(4);
        foreach (var (key, label) in new[] { (PageKey.Home, "Home"), (PageKey.Mods, "Mods"), (PageKey.Profiles, "Profiles"), (PageKey.Settings, "Settings") })
        {
            var text = Ui.Eyebrow(label, null, TypeScale.NavTracking);
            text.ClearValue(TextBlock.ForegroundProperty);
            var dot = new Ellipse
            {
                Width = 4, Height = 4, Fill = Palette.Accent, HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 2),
                RenderTransformOrigin = RelativePoint.Center, RenderTransform = TransformOperations.Parse("scale(0)"),
            };
            if (!Motion.Off) dot.Transitions = [new TransformOperationsTransition { Property = RenderTransformProperty, Duration = Motion.Base }];
            var cell = new Panel();
            cell.Children.Add(text);
            cell.Children.Add(dot);
            text.HorizontalAlignment = HorizontalAlignment.Center;
            var tab = new Pressable(Look.Ghost, cell, label)
            {
                Height = Sizes.ControlMd, Padding = new Thickness(12, 0), CornerRadius = new CornerRadius(Radius.Sm),
            };
            tab.StateChanged += p =>
            {
                // Rest textDim, hover text + rgba(255,255,255,.04), selected text (no fill).
                p.Background = (p.IsHovered || p.ForceHover) && !p.IsSelected ? Palette.PillRest : Brushes.Transparent;
            };
            var k = key;
            tab.Click += (_, _) => _window.Navigate(k);
            _tabs[key] = (tab, dot);
            _tabRow.Children.Add(tab);
        }

        // Right group (pad 8, gaps 4).
        _updateLabel = Ui.BodyStrong("Update", Palette.HueGold);
        _update = new Pressable(Look.Ghost,
            Ui.Row(6, Ui.Icon(Icons.Download, Sizes.Icon, Palette.HueGold), _updateLabel),
            "Update ready: restart to install")
        {
            Height = Sizes.ControlMd, Padding = new Thickness(10, 0), CornerRadius = new CornerRadius(Radius.Sm),
            Background = Palette.ChipGold, IsVisible = false,
        };
        _update.StateChanged += p => p.Background = p.IsHovered || p.ForceHover ? Palette.ChipGoldHover : Palette.ChipGold;
        ToolTip.SetTip(_update, "Restart Origin to install the update");
        _update.Click += (_, _) => UpdateRequested?.Invoke();

        var updateGlyph = new Panel { Width = 16, Height = 16 };
        updateGlyph.Children.Add(Ui.Icon(Icons.Download, Sizes.Icon));
        updateGlyph.Children.Add(new Ellipse
        {
            Width = 8, Height = 8, Fill = Palette.HueGold, Stroke = Palette.Bg, StrokeThickness = 2,
            HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, -3, -3, 0),
        });
        _updateIcon = new Pressable(Look.Ghost, updateGlyph, "Update ready: restart to install")
        {
            Width = Sizes.ControlMd, Height = Sizes.ControlMd, CornerRadius = new CornerRadius(Radius.Sm), IsVisible = false,
        };
        _updateIcon.Center();
        ToolTip.SetTip(_updateIcon, "Restart Origin to install the update");
        _updateIcon.Click += (_, _) => UpdateRequested?.Invoke();

        _storeLabel = Ui.BodyStrong("Store"); // the Windows bar's Store reads "Store" in bodyStrong
        _storeArrow = Ui.Icon(Icons.ArrowUpRight, 12, Palette.TextMuted);
        _storeArrow.IsVisible = false;
        _store = new Pressable(Look.Ghost, Ui.Row(6, Ui.Icon(Icons.Store, Sizes.Icon, Palette.HueGold), _storeLabel, _storeArrow), "Open the Origin Store")
        {
            Height = Sizes.ControlMd, Padding = new Thickness(10, 0), CornerRadius = new CornerRadius(Radius.Sm),
        };
        ToolTip.SetTip(_store, "Origin Store");
        _store.StateChanged += p => _storeArrow.IsVisible = (p.IsHovered || p.ForceHover) && _storeLabel.IsVisible;
        _store.Click += (_, _) => ExternalLink.Open(new Uri(StoreUrl));

        ImportButton = Ui.IconButton(Icons.ImportIn, "Import from another launcher");
        ImportButton.Click += (_, _) => ImportRequested?.Invoke();

        var friendsGlyph = new Panel { Width = 16, Height = 16 };
        friendsGlyph.Children.Add(Ui.Icon(Icons.Friends, Sizes.Icon));
        _friendsDot = new Ellipse
        {
            Width = 7, Height = 7, Fill = Palette.Accent, IsVisible = false, IsHitTestVisible = false,
            HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, -1, -1, 0),
        };
        friendsGlyph.Children.Add(_friendsDot);
        FriendsButton = new Pressable(Look.Ghost, friendsGlyph, "Friends")
        {
            Width = Sizes.ControlMd, Height = Sizes.ControlMd, CornerRadius = new CornerRadius(Radius.Sm),
        };
        FriendsButton.Center();
        ToolTip.SetTip(FriendsButton, "Friends");
        FriendsButton.Click += (_, _) => FriendsRequested?.Invoke();

        _account = new Pressable(Look.Ghost) { Height = Sizes.ControlMd, CornerRadius = new CornerRadius(Radius.Sm) };
        _account.Click += (_, _) => AccountRequested?.Invoke();
        RefreshAccount();
        LauncherState.AccountsChanged += RefreshAccount;

        var minimise = Ui.IconButton(Icons.Minimize, "Minimise");
        minimise.Click += (_, _) => _window.WindowState = WindowState.Minimized;
        var close = Ui.IconButton(Icons.Close, "Close", look: Look.ChromeClose);
        close.Click += (_, _) => _window.Close();

        _right = Ui.Row(4, _update, _updateIcon, _store, ImportButton, FriendsButton,
            Ui.Divider(vertical: true).Margin(4, 0, 4, 0), _account, new Border { Width = 4 }, minimise, close);
        _right.Margin = new Thickness(0, 0, 8, 0);
        // The divider is rgba(255,255,255,.14): the same as lineStrong.

        var row = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto") };
        row.Children.Add(_left.At(0));
        row.Children.Add(_tabRow.At(1));
        row.Children.Add(_right.At(2));
        _tabRow.HorizontalAlignment = HorizontalAlignment.Center;
        _tabRow.Margin = new Thickness(24, 0);
        _solid.Background = new SolidColorBrush(Color.FromRgb(Palette.Nav.Color.R, Palette.Nav.Color.G, Palette.Nav.Color.B));
        _solidMotion = new Tween(_solid, opacityEasing: Motion.Out);
        _glassMotion = new Tween(_glass, opacityEasing: Motion.Out);
        Child = new Panel { Children = { _solid, _glass, row } };
        // Clicks on empty bar space drag the window; every control inside handles its own press first. The surfaces
        // draw the colour, so the bar itself is transparent (but still hit-tested for the drag).
        PointerPressed += (_, e) => { if (!e.Handled) _window.DragFrom(e); };
        Background = Brushes.Transparent;
        LauncherState.UpdateChanged += () => Fit(_window.Bounds.Width);
    }

    public event Action? UpdateRequested;
    public event Action? ImportRequested;
    public event Action? FriendsRequested;
    public event Action? AccountRequested;

    public void Select(PageKey key)
    {
        foreach (var (k, (tab, dot)) in _tabs)
        {
            tab.IsSelected = k == key;
            dot.RenderTransform = TransformOperations.Parse(k == key ? "scale(1)" : "scale(0)");
            tab.Apply();
            if (k == key) Avalonia.Controls.Documents.TextElement.SetForeground(tab, Palette.Text);
        }
        // Home: the nav at 88 % over the art; every other page: opaque, so a scrolled page never reads through.
        var home = key == PageKey.Home;
        _solidMotion.To(Motion.Ms(260), opacity: home ? 0 : 1);
        _glassMotion.To(Motion.Ms(260), opacity: home ? 1 : 0);
    }

    /// <summary>
    /// The update chip's one short line in place of "Update" (WPF ShowUpdateNote): "Updating..." while it downloads off
    /// Home's scene, "Update failed - retry" in the warning colour after a failure; null puts "Update" back. A note shows
    /// the full chip at every width, so the words are never hidden behind the compact icon.
    /// </summary>
    public void SetUpdateNote(string? text, IBrush? brush = null)
    {
        _updateNote = text;
        _updateLabel.Text = text ?? "Update";
        _updateLabel.Foreground = text == null ? Palette.HueGold : brush ?? Palette.TextDim;
        Avalonia.Automation.AutomationProperties.SetName(_update, text == "Update failed - retry"
            ? "Update failed, retry" : "Update ready: restart to install");
        Fit(_window.Bounds.Width);
    }

    /// <summary>The chip takes no clicks while its download runs (WPF UpdateBadge.IsEnabled).</summary>
    public void SetUpdateEnabled(bool enabled)
    {
        _update.IsEnabled = enabled;
        _updateIcon.IsEnabled = enabled;
    }

    /// <summary>Show friends online (the small dot on the Friends button).</summary>
    public void SetFriendsOnline(bool any) => _friendsDot.IsVisible = any;

    public void RefreshAccount()
    {
        var acc = LauncherState.Account;
        var chevron = Ui.Icon(Icons.ChevronDown, 12, Palette.TextDim);
        if (acc == null)
        {
            // Signed out: ghost md "Sign in" with the account icon.
            _account.Look = Look.Ghost;
            _account.Padding = new Thickness(10, 0);
            _account.BorderThickness = default;
            _account.Child = Ui.Row(6, Ui.Icon(Icons.Account, Sizes.Icon), Ui.BodyStrong("Sign in"), chevron);
            Avalonia.Automation.AutomationProperties.SetName(_account, "Sign in");
            _account.StateChanged -= PaintPill;
            return;
        }
        // Signed in (11.5): 4 inset, head 24 at radius 2, 8, name bodyStrong (max 160), 6, chevron 12, 10 right.
        _account.Look = Look.Plain;
        _account.Padding = new Thickness(4, 0, 10, 0);
        _account.BorderThickness = new Thickness(1);
        var head = Ui.Pixel(App.Core.Skins.PlayerHeads.Loaded(acc.Id) ?? App.Core.Skins.PlayerHeads.Default(acc.Id), Sizes.PillHead);
        var headBox = new Border { CornerRadius = new CornerRadius(2), ClipToBounds = true, Child = head };
        var name = Ui.Ellipsis(Ui.BodyStrong(acc.Gamertag), Sizes.AccountNameMax);
        var row = Ui.Row(0, headBox, name.Margin(8, 0, 6, 0), chevron);
        if (LauncherState.Settings.OfflineTestMode)
        {
            name.Text = "Offline";
            var withDot = new Panel { Width = 24, Height = 24 };
            withDot.Children.Add(headBox);
            withDot.Children.Add(new Ellipse
            {
                Width = 8, Height = 8, Fill = Palette.Warning, Stroke = Palette.Nav, StrokeThickness = 2,
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, -2, -2),
            });
            row = Ui.Row(0, withDot, name.Margin(8, 0, 6, 0), chevron);
        }
        _account.Child = row;
        Avalonia.Automation.AutomationProperties.SetName(_account, "Account: " + acc.Gamertag);
        _account.StateChanged -= PaintPill;
        _account.StateChanged += PaintPill;
        PaintPill(_account);
        if (App.Core.Skins.PlayerHeads.Loaded(acc.Id) == null)
            _ = LoadHeadAsync(acc.Id, head);
    }

    private static async Task LoadHeadAsync(string id, Image target)
    {
        try
        {
            var img = await App.Core.Skins.PlayerHeads.Get(id);
            if (img != null) Avalonia.Threading.Dispatcher.UIThread.Post(() => target.Source = img);
        }
        catch (Exception) { /* the default face stays */ }
    }

    // Pill: fill rgba(255,255,255,.04) + 1 px line; hover surfaceHover + lineStrong.
    private static void PaintPill(Pressable p)
    {
        var hot = p.IsHovered || p.ForceHover;
        p.Background = hot ? Palette.CardHover : Palette.PillRest;
        p.BorderBrush = hot ? Palette.LineStrong : Palette.Line;
        Avalonia.Controls.Documents.TextElement.SetForeground(p, Palette.Text);
    }

    /// <summary>The narrow-window order (11.4): chip to icon below 1100, Store to its icon when the tabs would
    /// come within 24 of the actions, wordmark hidden below 1000.</summary>
    public void Fit(double width)
    {
        if (width <= 0) return;
        var update = LauncherState.UpdateVersion != null;
        var wide = width >= Sizes.UpdateChipMinWindow || _updateNote != null;
        _update.IsVisible = update && wide;
        _updateIcon.IsVisible = update && !wide;
        _wordmark.IsVisible = width >= Sizes.WordmarkMinWindow;
        _storeLabel.IsVisible = true;
        Measure(new Avalonia.Size(width, Sizes.Bar));
        var used = _left.DesiredSize.Width + _right.DesiredSize.Width + _tabRow.DesiredSize.Width;
        if (used > width) _storeLabel.IsVisible = false;
    }
}
