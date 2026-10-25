using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using OriginLauncher.App.Core.Social;

namespace OriginLauncher.App.UI.Controls;

public sealed class ToastVm
{
    public required SocialToast Toast { get; init; }
    public required Geometry Icon { get; init; }
    /// <summary>The icon and its tile colour: one accent per kind (message, request, invite, warp).</summary>
    public required Brush Tint { get; init; }
    public required DispatcherTimer Timer { get; init; }

    public string Title => Toast.Title;
    public string Body => Toast.Body;

    /// <summary>Warp → Join, request / invite → Accept, DM → Reply (opens that chat); notices just open the drawer.</summary>
    public string? ActionText => Toast.Kind switch
    {
        SocialToastKind.Warp => "Join",
        SocialToastKind.Message => "Reply",
        SocialToastKind.FriendRequest or SocialToastKind.PartyInvite => "Accept",
        _ => null,
    };

    public bool HasAction => ActionText != null;
}

public partial class SocialToastHost : UserControl
{
    // Two on screen at most; the rest wait and show as "+N more" (the Play card stays uncovered).
    private const int MaxVisible = 2;

    private readonly ObservableCollection<ToastVm> _items = new();
    private readonly ToastQueue _waiting = new(capacity: 20);

    /// <summary>The card was clicked: open whatever it's about.</summary>
    public event Action<SocialToast>? Opened;

    /// <summary>JOIN / ACCEPT was clicked.</summary>
    public event Action<SocialToast>? ActionClicked;

    /// <summary>"+N more" was clicked: open the drawer.</summary>
    public event Action? MoreClicked;

    public SocialToastHost()
    {
        InitializeComponent();
        ToastList.ItemsSource = _items;
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

    private void Display(SocialToast toast, TimeSpan life)
    {
        var timer = new DispatcherTimer(DispatcherPriority.Background) { Interval = life };
        var vm = new ToastVm { Toast = toast, Icon = IconFor(toast.Kind), Tint = TintFor(toast.Kind), Timer = timer };
        timer.Tick += (_, _) => Remove(vm);
        _items.Add(vm);
        timer.Start();
        UpdateMore();
    }

    public void Clear()
    {
        _waiting.Clear();
        foreach (var vm in _items.ToList()) Remove(vm);
        UpdateMore();
    }

    private void Remove(ToastVm vm)
    {
        vm.Timer.Stop();
        _items.Remove(vm);
        // A place opened up: the next waiting toast that is still fresh takes it, for what is left of its life.
        while (_items.Count < MaxVisible && _waiting.TakeNext(DateTimeOffset.UtcNow) is { } next)
            Display(next.Toast, next.Left);
        UpdateMore();
    }

    private void UpdateMore()
    {
        _waiting.Purge(DateTimeOffset.UtcNow);
        MoreButton.Visibility = _waiting.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        MoreButton.Content = $"+{_waiting.Count} more";
    }

    private void More_Click(object sender, RoutedEventArgs e)
    {
        // Messages, requests, invites and the warp are all in the drawer; a Notice has no row there, so it keeps waiting.
        _waiting.DropDrawerKinds();
        UpdateMore();
        MoreClicked?.Invoke();
    }

    private Brush TintFor(SocialToastKind kind) => (Brush)FindResource(kind switch
    {
        SocialToastKind.Message => "Brush.Info",
        SocialToastKind.FriendRequest => "Brush.Success",
        SocialToastKind.PartyInvite => "Brush.Warning",
        SocialToastKind.Warp => "Brush.Accent",
        _ => "Brush.TextDim",
    });

    private Geometry IconFor(SocialToastKind kind) => (Geometry)FindResource(kind switch
    {
        SocialToastKind.Message => "Icon.Chat",
        SocialToastKind.FriendRequest => "Icon.UserPlus",
        _ => "Icon.Friends",
    });

    private static ToastVm? Vm(object sender) => (sender as FrameworkElement)?.DataContext as ToastVm;

    private void Toast_Click(object sender, MouseButtonEventArgs e)
    {
        if (Vm(sender) is not { } vm) return;
        Remove(vm);
        Opened?.Invoke(vm.Toast);
    }

    private void Action_Click(object sender, RoutedEventArgs e)
    {
        if (Vm(sender) is not { } vm) return;
        Remove(vm);
        // REPLY is "open the chat", the same as pressing the card.
        if (vm.Toast.Kind == SocialToastKind.Message) Opened?.Invoke(vm.Toast);
        else ActionClicked?.Invoke(vm.Toast);
    }

    private void Dismiss_Click(object sender, RoutedEventArgs e)
    {
        if (Vm(sender) is { } vm) Remove(vm);
    }

    // Reading a toast shouldn't race its timer.
    private void Toast_MouseEnter(object sender, MouseEventArgs e) => Vm(sender)?.Timer.Stop();

    private void Toast_MouseLeave(object sender, MouseEventArgs e)
    {
        if (Vm(sender) is { } vm && _items.Contains(vm)) vm.Timer.Start();
    }
}
