using System.Windows;
using System.Windows.Media;
using OriginLauncher.App.Core.Skins;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// A player's Minecraft head (their skin's face + hat layer, Core/Skins/PlayerHeads), drawn
/// square and nearest-neighbour so the 8x8 pixels stay crisp at any size. Shows the player's
/// default head (Steve/Alex by uuid) at once and swaps to the real one when it has loaded;
/// a blank or non-uuid value (offline, pending accounts) stays on the default head.
/// </summary>
public sealed class PlayerHead : FrameworkElement
{
    public static readonly DependencyProperty UuidProperty = DependencyProperty.Register(
        nameof(Uuid), typeof(string), typeof(PlayerHead),
        new FrameworkPropertyMetadata("", (d, _) => ((PlayerHead)d).Load()));

    /// <summary>The player's uuid (with or without dashes).</summary>
    public string Uuid { get => (string)GetValue(UuidProperty); set => SetValue(UuidProperty, value); }

    private ImageSource? _image;

    public PlayerHead()
    {
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        RenderOptions.SetEdgeMode(this, EdgeMode.Aliased);
        SnapsToDevicePixels = true;
    }

    private void Load()
    {
        var uuid = Uuid;
        _image = PlayerHeads.Loaded(uuid) ?? PlayerHeads.Default(uuid);
        InvalidateVisual();
        var load = PlayerHeads.Get(uuid);
        if (load.IsCompleted) { Show(uuid, load); return; }
        load.ContinueWith(t => Dispatcher.BeginInvoke(() => Show(uuid, t)), TaskScheduler.Default);
    }

    // A recycled row may have moved on to another player while the load ran: only the
    // current player's head is shown.
    private void Show(string uuid, Task<ImageSource?> load)
    {
        if (!load.IsCompletedSuccessfully || load.Result is not { } head || uuid != Uuid) return;
        if (ReferenceEquals(head, _image)) return;
        _image = head;
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        _image ??= PlayerHeads.Default(Uuid);
        var s = Math.Min(ActualWidth, ActualHeight);
        if (s <= 0) return;
        dc.DrawImage(_image, new Rect((ActualWidth - s) / 2, (ActualHeight - s) / 2, s, s));
    }
}
