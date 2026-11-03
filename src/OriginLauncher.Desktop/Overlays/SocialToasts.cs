using System.Runtime.CompilerServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// Social toasts (WPF UI/Controls/SocialToastHost): a message, a friend request, a party invite, a warp or a
// notice, bottom centre above Home's strip. Two on screen at most; the rest wait in the shared ToastQueue and show
// as "+N more" (which opens the drawer, where they all are anyway). Each toast: a hue tile for its kind, title,
// one line, an action (Reply / Accept / Join) and a dismiss X; clicking the card opens the drawer on it. A toast
// lives 5 s (a warp 30 s); hovering holds it. While the friends drawer is open the stack centres on the space
// left of it, and friend requests / invites don't toast at all (the drawer shows them).
// The host sits in the window's root panel just under the resize grips (MainWindow owns no social code).
internal sealed class SocialToasts : StackPanel
{
    private const int MaxVisible = 2;
    private static readonly ConditionalWeakTable<MainWindow, SocialToasts> Hosts = new();

    private readonly MainWindow _window;
    private readonly ToastQueue _queue = new(20);
    private readonly StackPanel _cards = new() { Spacing = Space.S2 };
    private readonly Pressable _more;
    private readonly TextBlock _moreText = Ui.Text("", TypeScale.ControlSm, FontWeight.SemiBold);

    public static SocialToasts Of(MainWindow window)
    {
        if (Hosts.TryGetValue(window, out var host)) return host;
        host = new SocialToasts(window);
        Hosts.Add(window, host);
        if (window.Content is Panel root) root.Children.Insert(Math.Max(0, root.Children.Count - 1), host);
        return host;
    }

    private SocialToasts(MainWindow window)
    {
        _window = window;
        Width = Sizes.SocialToast;
        Spacing = Space.S2;
        HorizontalAlignment = HorizontalAlignment.Center;
        VerticalAlignment = VerticalAlignment.Bottom;
        _more = new Pressable(Look.Secondary, _moreText, "Show all in Friends")
        {
            Height = Sizes.ControlSm, Padding = new Thickness(10, 0), CornerRadius = new CornerRadius(Radius.Sm),
            HorizontalAlignment = HorizontalAlignment.Center, IsVisible = false,
        }.Center();
        _more.Click += (_, _) =>
        {
            _queue.DropDrawerKinds();
            FriendsDrawer.OpenOn(_window);
            Pump();
        };
        Children.Add(_cards);
        Children.Add(_more);
        window.PageChanged += _ => Place();
        Place();
    }

    /// <summary>The friends drawer opened or closed: re-centre, and drop what the drawer now shows.</summary>
    public void DrawerChanged()
    {
        if (FriendsDrawer.Shown != null)
            foreach (var card in _cards.Children.OfType<Border>().Where(c => c.Tag is SocialToast t && Hides(t.Kind)).ToList())
                _cards.Children.Remove(card);
        Place();
        Pump();
    }

    private static bool Hides(SocialToastKind kind) => kind is SocialToastKind.FriendRequest or SocialToastKind.PartyInvite;

    private void Place()
    {
        var right = FriendsDrawer.Shown != null ? Sizes.Drawer : 0;
        var bottom = _window.Page == PageKey.Home ? 104 : Space.S6;
        Margin = new Thickness(0, 0, right, bottom);
    }

    public void Show(SocialToast toast)
    {
        if (FriendsDrawer.Shown != null && Hides(toast.Kind)) return;
        // Reading that chat right now: no toast for its own messages.
        if (toast.Kind == SocialToastKind.Message && FriendsDrawer.Shown is { } d && d.IsVisible && toast.Uuid != null
            && d.OpenKey == toast.Uuid) return;
        _queue.Enqueue(toast, DateTimeOffset.UtcNow);
        Pump();
    }

    private void Pump()
    {
        while (_cards.Children.Count < MaxVisible && _queue.TakeNext(DateTimeOffset.UtcNow) is { } next)
            _cards.Children.Add(Card(next.Toast, next.Left));
        var waiting = _queue.Count;
        _more.IsVisible = waiting > 0;
        _moreText.Text = $"+{waiting} more";
        IsVisible = _cards.Children.Count > 0 || waiting > 0;
    }

    private Border Card(SocialToast toast, TimeSpan life)
    {
        var (tile, ink, glyph) = toast.Kind switch
        {
            SocialToastKind.Message => (Palette.TileSky, Palette.HueSky, Icons.Chat),
            SocialToastKind.FriendRequest => (Palette.TileEmerald, Palette.HueEmerald, Icons.UserPlus),
            SocialToastKind.PartyInvite => (Palette.TileGold, Palette.HueGold, Icons.Friends),
            SocialToastKind.Warp => ((IBrush)Palette.AccentSoft, (IBrush)Palette.Accent, Icons.Friends),
            _ => (Palette.TileCoral, Palette.HueCoral, Icons.Alert),
        };
        var actionText = toast.Kind switch
        {
            SocialToastKind.Warp => "Join",
            SocialToastKind.Message => "Reply",
            SocialToastKind.FriendRequest or SocialToastKind.PartyInvite => "Accept",
            _ => null,
        };

        Border card = null!;
        var text = Ui.Column(1,
            Ui.Ellipsis(Ui.Text(toast.Title, TypeScale.Body, FontWeight.SemiBold, Palette.Text)),
            Wrap2(Ui.Caption(toast.Body)));
        text.VerticalAlignment = VerticalAlignment.Center;
        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto"), ColumnSpacing = 10 };
        g.Children.Add(Ui.Tile(glyph, tile, ink).At(0));
        g.Children.Add(text.At(1));
        if (actionText != null)
        {
            var action = Ui.Button(actionText, Look.Secondary, height: Sizes.ControlSm).Tip(actionText);
            action.Click += (_, _) => { Dismiss(card); _ = RunAsync(toast, primary: true); };
            g.Children.Add(action.At(2));
        }
        var close = Ui.IconButton(Icons.Close, "Dismiss", Sizes.ControlSm);
        close.Click += (_, _) => Dismiss(card);
        g.Children.Add(close.At(3));

        card = new Border
        {
            Background = Palette.Card, BorderBrush = Palette.LineStrong, BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(Radius.Md), BoxShadow = Palette.OverlayShadow, Padding = new Thickness(Space.S3, Space.S2),
            Cursor = new Cursor(StandardCursorType.Hand), Child = g, Tag = toast,
        };
        card.PointerReleased += (_, e) =>
        {
            if (e.Handled) return;
            Dismiss(card);
            _ = RunAsync(toast, primary: false);
        };

        // Life: hover holds it; leaving starts the full life again. Render runs keep every toast.
        IDisposable? timer = null;
        void Arm(TimeSpan t)
        {
            timer?.Dispose();
            if (!Shots.ShotRunner.Active) timer = DispatcherTimer.RunOnce(() => Dismiss(card), t < TimeSpan.FromSeconds(1) ? TimeSpan.FromSeconds(1) : t);
        }
        card.PointerEntered += (_, _) => timer?.Dispose();
        card.PointerExited += (_, _) => Arm(ToastQueue.LifeOf(toast.Kind));
        card.DetachedFromVisualTree += (_, _) => timer?.Dispose();
        Arm(life);
        return card;
    }

    private void Dismiss(Border card)
    {
        if (!_cards.Children.Remove(card)) return;
        Pump();
    }

    /// <summary>The card (open it in the drawer) or its action (reply, accept, join), as on Windows.</summary>
    private async Task RunAsync(SocialToast toast, bool primary)
    {
        var service = SocialHub.Service;
        switch (toast.Kind)
        {
            case SocialToastKind.Message:
                FriendsDrawer.OpenOn(_window, toast.Uuid);
                return;
            case SocialToastKind.Warp when primary:
                await FriendsDrawer.JoinAsync(_window, toast.Title, toast.Server, toast.Version);
                return;
            case SocialToastKind.PartyInvite when primary && toast.PartyId != null:
                await Report(await service.AcceptPartyAsync(toast.PartyId), null);
                return;
            case SocialToastKind.FriendRequest when primary && toast.Uuid != null:
                await Report(await service.RespondAsync(toast.Uuid, true), null);
                return;
            case SocialToastKind.Notice:
                return;
            default:
                FriendsDrawer.OpenOn(_window);
                return;
        }
    }

    private Task Report(SocialActionResult result, string? success)
    {
        if (!result.Ok) FriendsDrawer.OpenOn(_window).ShowNotice(result.Error ?? "Something went wrong. Please try again.", error: true);
        else if (success != null) FriendsDrawer.Shown?.ShowNotice(success, error: false);
        return Task.CompletedTask;
    }

    /// <summary>Render runs: put these toasts up as they'd arrive.</summary>
    public void ShowAll(IEnumerable<SocialToast> toasts)
    {
        foreach (var t in toasts) Show(t);
    }

    // The body wraps to two lines at most, then ends in dots (WPF: Wrap + CharacterEllipsis, MaxHeight 36).
    private static TextBlock Wrap2(TextBlock t)
    {
        t.TextWrapping = TextWrapping.Wrap;
        t.TextTrimming = TextTrimming.CharacterEllipsis;
        t.MaxLines = 2;
        return t;
    }
}
