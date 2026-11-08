using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// The Friends drawer's "..." menu: the twin of the WPF ContextMenu the drawer builds (Theme/Menus.xaml's Menu.Popup,
/// Menu.Item, Menu.Separator; here Theme/Social.axaml). A Popup on the window's overlay layer holding one card of item
/// buttons: a 26 px icon column, then the words; a danger item (Kick, Remove, Block) is red, icon and words.
///   Opens     under its anchor (its right edge on the anchor's right edge when the anchor is narrow, as WPF's Bottom
///             placement with the 196 px offset), or at the pointer for a right-click; rises 4 px while it fades in
///             over Motion.Enter (never animates out).
///   Keys      Up / Down light the next item, Enter or Space picks it, Esc closes.
///   Picking   remembers the item's action and runs it once the menu has closed (Background priority), so the focus
///             the menu gives back to its row can't steal the focus an action such as "Message" places.
/// </summary>
internal sealed class PopupMenu
{
    private const double MenuWidth = 196;

    private readonly Panel _host;
    private readonly Popup _popup;
    private readonly StackPanel _items = new();
    private readonly TranslateTransform _rise = new();
    private Action? _chosen;

    /// <summary>The menu closed (picked, Esc, a click outside, or <see cref="Close"/>), after its action was taken.</summary>
    public event Action? Closed;

    /// <summary>The visible card (render-shots crop to it).</summary>
    public Border Card { get; }

    public int Count => _items.Children.Count;
    public bool IsOpen => _popup.IsOpen;

    /// <param name="host">A panel in the window the menu belongs to: the popup joins its children while open.</param>
    public PopupMenu(Panel host)
    {
        _host = host;
        Card = new Border
        {
            Theme = Res.Theme("Menu.Popup"),
            Child = _items,
            Focusable = true,
            RenderTransform = _rise,
        };
        Card.KeyDown += Card_KeyDown;
        _popup = new Popup
        {
            Child = Card,
            IsLightDismissEnabled = true,
            ShouldUseOverlayLayer = true,
            Placement = PlacementMode.BottomEdgeAlignedLeft,
        };
        _popup.Closed += (_, _) => OnClosed();
    }

    public void AddItem(string header, string iconKey, Action action, bool danger = false)
    {
        var icon = new IconPath
        {
            Data = Res.Geo(iconKey),
            Theme = Res.Theme("Icon.Base"),
            Width = 14,
            Height = 14,
            Stroke = Res.Brush(danger ? "Brush.Danger" : "Brush.TextDim"),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var text = new TextBlock
        {
            Text = header,
            TextTrimming = TextTrimming.CharacterEllipsis,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("26,*") };
        grid.Children.Add(icon);
        grid.Children.Add(text.At(1));
        var item = new Button { Theme = Res.Theme("Menu.Item"), Content = grid };
        if (danger) item.Foreground = Res.Brush("Brush.Danger");
        Avalonia.Automation.AutomationProperties.SetName(item, header);
        item.Click += (_, _) =>
        {
            _chosen = action;
            _popup.IsOpen = false;
        };
        _items.Children.Add(item);
    }

    public void AddSeparator() => _items.Children.Add(new Border { Theme = Res.Theme("Menu.Separator") });

    /// <summary>Opens under <paramref name="target"/>, or at <paramref name="at"/> (a point in the target) for a right-click.</summary>
    public void Open(Control target, Point? at = null)
    {
        if (!_host.Children.Contains(_popup)) _host.Children.Add(_popup);
        _chosen = null;
        _popup.PlacementTarget = target;
        if (at is { } p)
        {
            _popup.PlacementRect = new Rect(p, new Size(1, 1));
            _popup.HorizontalOffset = 0;
            _popup.VerticalOffset = 0;
        }
        else
        {
            _popup.PlacementRect = null;
            _popup.HorizontalOffset = Math.Min(0, target.Bounds.Width - MenuWidth);
            // WPF's menu window carries a 4 px bleed above the card (Popup.Bleed): the card sits 4 px under the anchor.
            _popup.VerticalOffset = 4;
        }
        _popup.IsOpen = true;
        Card.Focus();
        var enter = (int)Res.Get<TimeSpan>("Motion.Enter").TotalMilliseconds;
        _ = MotionFx.Run(Card, Visual.OpacityProperty, 0, 1, enter);
        _ = MotionFx.Run(Card, _rise, TranslateTransform.YProperty, 4, 0, enter);
    }

    /// <summary>Closes without running anything.</summary>
    public void Close()
    {
        _chosen = null;
        if (_popup.IsOpen) _popup.IsOpen = false;
    }

    private void OnClosed()
    {
        _host.Children.Remove(_popup);
        var action = _chosen;
        _chosen = null;
        Closed?.Invoke();
        if (action != null) Dispatcher.UIThread.Post(action, DispatcherPriority.Background);
    }

    private void Card_KeyDown(object? sender, KeyEventArgs e)
    {
        var buttons = _items.Children.OfType<Button>().Where(b => b.IsEnabled).ToList();
        if (buttons.Count == 0) return;
        var current = buttons.FindIndex(b => b.IsFocused);
        switch (e.Key)
        {
            case Key.Down:
                buttons[current < 0 ? 0 : (current + 1) % buttons.Count].Focus(NavigationMethod.Directional);
                e.Handled = true;
                break;
            case Key.Up:
                buttons[current <= 0 ? buttons.Count - 1 : current - 1].Focus(NavigationMethod.Directional);
                e.Handled = true;
                break;
            case Key.Escape:
                Close();
                e.Handled = true;
                break;
        }
    }

    // Render harness only: the item a keyboard would light first.
    internal void TourHighlightFirst() => _items.Children.OfType<Button>().FirstOrDefault()?.Focus(NavigationMethod.Directional);
}
