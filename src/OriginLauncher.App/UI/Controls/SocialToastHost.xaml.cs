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
    private const int MaxVisible = 3;
    private static readonly TimeSpan ShortLife = TimeSpan.FromSeconds(7);
    // A warp stays up longer: the service keeps it for two minutes.
    private static readonly TimeSpan WarpLife = TimeSpan.FromSeconds(30);

    private readonly ObservableCollection<ToastVm> _items = new();

    /// <summary>The card was clicked: open whatever it's about.</summary>
    public event Action<SocialToast>? Opened;

    /// <summary>JOIN / ACCEPT was clicked.</summary>
    public event Action<SocialToast>? ActionClicked;

    public SocialToastHost()
    {
        InitializeComponent();
        ToastList.ItemsSource = _items;
    }

    public void Show(SocialToast toast)
    {
        while (_items.Count >= MaxVisible) Remove(_items[0]);

        var timer = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = toast.Kind == SocialToastKind.Warp ? WarpLife : ShortLife,
        };
        var vm = new ToastVm { Toast = toast, Icon = IconFor(toast.Kind), Timer = timer };
        timer.Tick += (_, _) => Remove(vm);
        _items.Add(vm);
        timer.Start();
    }

    public void Clear()
    {
        foreach (var vm in _items.ToList()) Remove(vm);
    }

    private void Remove(ToastVm vm)
    {
        vm.Timer.Stop();
        _items.Remove(vm);
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
