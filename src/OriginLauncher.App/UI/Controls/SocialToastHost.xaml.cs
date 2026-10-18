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
    public required DispatcherTimer Timer { get; init; }

    public string Title => Toast.Title;
    public string Body => Toast.Body;

    /// <summary>Warp → JOIN, request / invite → ACCEPT, DM → REPLY (opens that chat); notices just open the drawer.</summary>
    public string? ActionText => Toast.Kind switch
    {
        SocialToastKind.Warp => "JOIN",
        SocialToastKind.Message => "REPLY",
        SocialToastKind.FriendRequest or SocialToastKind.PartyInvite => "ACCEPT",
        _ => null,
    };

    public bool HasAction => ActionText != null;
}

public partial class SocialToastHost : UserControl
{
    // Two on screen at most; the rest wait and show as "+N more" (the Play card stays uncovered).
    private const int MaxVisible = 2;
    private const int MaxWaiting = 20;
    private static readonly TimeSpan ShortLife = TimeSpan.FromSeconds(7);
    // A warp stays up longer: the service keeps it for two minutes.
    private static readonly TimeSpan WarpLife = TimeSpan.FromSeconds(30);

    private readonly ObservableCollection<ToastVm> _items = new();
    private readonly Queue<SocialToast> _waiting = new();

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
            _waiting.Enqueue(toast);
            while (_waiting.Count > MaxWaiting) _waiting.Dequeue();
            UpdateMore();
            return;
        }
        Display(toast);
    }

    private void Display(SocialToast toast)
    {
        var timer = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = toast.Kind == SocialToastKind.Warp ? WarpLife : ShortLife,
        };
        var vm = new ToastVm { Toast = toast, Icon = IconFor(toast.Kind), Timer = timer };
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
        // A place opened up: the next waiting toast takes it.
        while (_items.Count < MaxVisible && _waiting.Count > 0) Display(_waiting.Dequeue());
        UpdateMore();
    }

    private void UpdateMore()
    {
        MoreButton.Visibility = _waiting.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        MoreButton.Content = $"+{_waiting.Count} more";
    }

    private void More_Click(object sender, RoutedEventArgs e)
    {
        // Everything waiting is also in the drawer (messages, requests, invites, the warp card).
        _waiting.Clear();
        UpdateMore();
        MoreClicked?.Invoke();
    }

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
