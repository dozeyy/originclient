using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Styling;
using Avalonia.Threading;
using Avalonia.VisualTree;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// Toasts for social events and notices (twin of the WPF SocialToastHost): bottom-centre, Card fill + strong line,
/// Radius.Md, Shadow.Overlay, an optional secondary action, gone after their life (the shared ToastQueue rules). At
/// most two show, newest last, with "+N more" for the rest. Each fades in and rises 4 px (Motion.Enter) and pauses its
/// timer while the pointer is on it. The host has no background, so empty space never blocks a click.
/// Built in code: four controls per card, no binding layer to keep in step with the WPF template.
/// </summary>
public sealed class SocialToastHost : UserControl
{
    // Two on screen at most; the rest wait and show as "+N more" (the Play card stays uncovered).
    private const int MaxVisible = 2;

    private sealed record Shown(SocialToast Toast, Border Card, DispatcherTimer Timer);

    private readonly List<Shown> _items = new();
    private readonly ToastQueue _waiting = new(capacity: 20);
    private readonly StackPanel _list = new();
    private readonly Button _more;

    /// <summary>The card was clicked: open whatever it's about.</summary>
    public event Action<SocialToast>? Opened;

    /// <summary>Join / Accept was clicked.</summary>
    public event Action<SocialToast>? ActionClicked;

    /// <summary>"+N more" was clicked.</summary>
    public event Action? MoreClicked;

    public SocialToastHost()
    {
        _more = new Button
        {
            Theme = Res<ControlTheme>("Social.Action"),
            Margin = new Thickness(0, 8, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Right,
            IsVisible = false,
        };
        _more.Click += (_, _) =>
        {
            // A Notice has no row in the drawer, so it keeps waiting; every other kind lives there.
            _waiting.DropDrawerKinds();
            UpdateMore();
            MoreClicked?.Invoke();
        };
        Content = new StackPanel { Children = { _list, _more } };
    }

    public void Show(SocialToast toast)
    {
        if (_items.Count >= MaxVisible)
        {
            _waiting.Enqueue(toast, DateTimeOffset.UtcNow);
            UpdateMore();
            return;
        }
        Display(toast, ToastQueue.LifeOf(toast.Kind));
    }

    public void Clear()
    {
        _waiting.Clear();
        foreach (var s in _items.ToList()) Remove(s);
        UpdateMore();
    }

    private void Display(SocialToast toast, TimeSpan life)
    {
        var timer = new DispatcherTimer(DispatcherPriority.Background) { Interval = life };
        var card = BuildCard(toast, out var action, out var dismiss);
        var shown = new Shown(toast, card, timer);
        timer.Tick += (_, _) => Remove(shown);
        card.PointerReleased += (_, e) =>
        {
            if (e.InitialPressMouseButton != MouseButton.Left || e.Source is Visual v && IsInButton(v)) return;
            Remove(shown);
            Opened?.Invoke(toast);
        };
        // Reading a toast shouldn't race its timer.
        card.PointerEntered += (_, _) => timer.Stop();
        card.PointerExited += (_, _) => { if (_items.Contains(shown)) timer.Start(); };
        if (action != null)
            action.Click += (_, _) =>
            {
                Remove(shown);
                // Reply is "open the chat", the same as pressing the card.
                if (toast.Kind == SocialToastKind.Message) Opened?.Invoke(toast);
                else ActionClicked?.Invoke(toast);
            };
        dismiss.Click += (_, _) => Remove(shown);

        _items.Add(shown);
        _list.Children.Add(card);
        Enter(card);
        timer.Start();
        UpdateMore();
    }

    private void Remove(Shown s)
    {
        s.Timer.Stop();
        if (!_items.Remove(s)) return;
        _list.Children.Remove(s.Card);
        // A place opened up: the next waiting toast that is still fresh takes it, for what is left of its life.
        while (_items.Count < MaxVisible && _waiting.TakeNext(DateTimeOffset.UtcNow) is { } next)
            Display(next.Toast, next.Left);
        UpdateMore();
    }

    private void UpdateMore()
    {
        _waiting.Purge(DateTimeOffset.UtcNow);
        _more.IsVisible = _waiting.Count > 0;
        _more.Content = $"+{_waiting.Count} more";
    }

    private Border BuildCard(SocialToast toast, out Button? action, out Button dismiss)
    {
        var tint = Res<IBrush>(toast.Kind switch
        {
            SocialToastKind.Message => "Brush.Info",
            SocialToastKind.FriendRequest => "Brush.Success",
            SocialToastKind.PartyInvite => "Brush.Warning",
            SocialToastKind.Warp => "Brush.Accent",
            _ => "Brush.TextDim",
        });
        var icon = Res<Geometry>(toast.Kind switch
        {
            SocialToastKind.Message => "Icon.Chat",
            SocialToastKind.FriendRequest => "Icon.UserPlus",
            _ => "Icon.Friends",
        });
        var xs = Res<CornerRadius>("Radius.Xs");
        var tile = new Border
        {
            Width = 28, Height = 28, Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center,
            Background = Res<IBrush>("Brush.CardAlt"), CornerRadius = xs,
            Child = new Grid
            {
                Children =
                {
                    new Border { Background = tint, Opacity = 0.2, CornerRadius = xs },
                    new IconPath { Theme = Res<ControlTheme>("Icon.Small"), Data = icon, Stroke = tint,
                        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
                },
            },
        };
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 8, 0) };
        text.Children.Add(new TextBlock
        {
            Text = toast.Title, Theme = Res<ControlTheme>("Text.BodyStrong"), LineHeight = 18,
            TextTrimming = TextTrimming.CharacterEllipsis,
        });
        text.Children.Add(new TextBlock
        {
            Text = toast.Body, Theme = Res<ControlTheme>("Social.Caption"), TextWrapping = TextWrapping.Wrap,
            TextTrimming = TextTrimming.CharacterEllipsis, MaxHeight = 36,
        });
        // Warp -> Join, request / invite -> Accept, DM -> Reply (opens that chat); notices just open the drawer.
        var actionText = toast.Kind switch
        {
            SocialToastKind.Warp => "Join",
            SocialToastKind.Message => "Reply",
            SocialToastKind.FriendRequest or SocialToastKind.PartyInvite => "Accept",
            _ => null,
        };
        action = actionText == null ? null : new Button
        {
            Content = actionText, Theme = Res<ControlTheme>("Button.Secondary.Small"), VerticalAlignment = VerticalAlignment.Center,
        };
        dismiss = new Button { Theme = Res<ControlTheme>("Social.Icon"), Margin = new Thickness(8, 0, 0, 0) };
        Avalonia.Automation.AutomationProperties.SetName(dismiss, "Dismiss");
        var glyph = new IconPath { Theme = Res<ControlTheme>("Icon.Small"), Data = Res<Geometry>("Icon.Close") };
        glyph.Bind(IconPath.StrokeProperty, dismiss.GetObservable(ForegroundProperty));
        dismiss.Content = glyph;

        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto") };
        grid.Children.Add(tile);
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        if (action != null)
        {
            Grid.SetColumn(action, 2);
            grid.Children.Add(action);
        }
        Grid.SetColumn(dismiss, 3);
        grid.Children.Add(dismiss);

        return new Border
        {
            Margin = new Thickness(0, 8, 0, 0), Padding = new Thickness(12, 8), Cursor = new Cursor(StandardCursorType.Hand),
            Background = Res<IBrush>("Brush.Card"), BorderBrush = Res<IBrush>("Brush.Stroke"), BorderThickness = new Thickness(1),
            CornerRadius = Res<CornerRadius>("Radius.Md"), BoxShadow = Res<BoxShadows>("Shadow.Overlay"),
            Child = grid,
        };
    }

    // Fades in and rises 4 px over Motion.Enter (no motion: it is simply there).
    private void Enter(Border card)
    {
        if (Motion.Off) return;
        var shift = new TranslateTransform(0, 4);
        card.RenderTransform = shift;
        card.Opacity = 0;
        var d = Res<TimeSpan>("Motion.Enter");
        card.Transitions = new Transitions { new DoubleTransition { Property = OpacityProperty, Duration = d } };
        shift.Transitions = new Transitions
        {
            new DoubleTransition { Property = TranslateTransform.YProperty, Duration = d, Easing = new CubicEaseOut() },
        };
        Dispatcher.UIThread.Post(() =>
        {
            card.Opacity = 1;
            shift.Y = 0;
        }, DispatcherPriority.Render);
    }

    private static bool IsInButton(Visual v)
    {
        for (Visual? p = v; p != null; p = p.GetVisualParent())
            if (p is Button) return true;
        return false;
    }

    // Built before it joins the window, so the app resources answer (a FindResource miss is UnsetValue, not null).
    private T Res<T>(string key) => this.TryFindResource(key, out var v) && v is T t ? t
        : Application.Current!.TryGetResource(key, null, out var a) && a is T u ? u
        : throw new InvalidOperationException($"Theme key {key} is missing");
}
