using OriginLauncher.App.Core.Social;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The toasts waiting behind the two that are on screen ("+N more"). Rules, kept here so
/// they can be unit-tested (ToastQueueTests):
/// - a toast that waited out its whole lifetime is never shown late (it is simply gone);
/// - one that is shown after waiting only gets what is left of its lifetime;
/// - when too many wait, the oldest toast the drawer also shows (message, request,
///   invite, warp) is dropped first and counted in <see cref="Overflow"/>, so "+N" stays true;
/// - pressing "+N more" opens the drawer, so only the toasts the drawer shows are cleared:
///   a Notice (why Join failed) has no drawer row and keeps waiting for its turn.
/// </summary>
public sealed class ToastQueue
{
    private static readonly TimeSpan ShortLife = TimeSpan.FromSeconds(5); // spec: a toast stays 5 s
    // A warp stays up longer: the service keeps it for two minutes.
    private static readonly TimeSpan WarpLife = TimeSpan.FromSeconds(30);

    private readonly List<(SocialToast Toast, DateTimeOffset At)> _items = new();
    private readonly int _capacity;

    public ToastQueue(int capacity) => _capacity = capacity;

    /// <summary>Toasts dropped because too many were waiting.</summary>
    public int Overflow { get; private set; }

    /// <summary>What "+N more" says: those waiting plus those dropped for lack of room.</summary>
    public int Count => _items.Count + Overflow;

    public static TimeSpan LifeOf(SocialToastKind kind) => kind == SocialToastKind.Warp ? WarpLife : ShortLife;

    /// <summary>Does the drawer show this kind of toast (so dropping the toast loses nothing)?</summary>
    public static bool InDrawer(SocialToastKind kind) => kind != SocialToastKind.Notice;

    public void Enqueue(SocialToast toast, DateTimeOffset now)
    {
        _items.Add((toast, now));
        while (_items.Count > _capacity)
        {
            var drop = _items.FindIndex(i => InDrawer(i.Toast.Kind));
            _items.RemoveAt(drop >= 0 ? drop : 0);
            Overflow++;
        }
    }

    /// <summary>Forgets toasts whose lifetime ran out while they waited.</summary>
    public void Purge(DateTimeOffset now)
    {
        _items.RemoveAll(i => now - i.At >= LifeOf(i.Toast.Kind));
        if (_items.Count == 0) Overflow = 0;
    }

    /// <summary>The next toast to show and how long it still has; null when nothing (live) is waiting.</summary>
    public (SocialToast Toast, TimeSpan Left)? TakeNext(DateTimeOffset now)
    {
        Purge(now);
        if (_items.Count == 0) return null;
        var (toast, at) = _items[0];
        _items.RemoveAt(0);
        if (_items.Count == 0) Overflow = 0;
        return (toast, LifeOf(toast.Kind) - (now - at));
    }

    /// <summary>"+N more" was pressed: the drawer now shows what it shows; Notices stay.</summary>
    public void DropDrawerKinds()
    {
        _items.RemoveAll(i => InDrawer(i.Toast.Kind));
        Overflow = 0;
    }

    public void Clear()
    {
        _items.Clear();
        Overflow = 0;
    }
}
