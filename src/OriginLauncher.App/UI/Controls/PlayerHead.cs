using System.Windows;
using System.Windows.Media;
using OriginLauncher.App.Core.Skins;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// A player's Minecraft head (their skin's face + hat layer, Core/Skins/PlayerHeads), drawn
/// square and nearest-neighbour so the 8x8 pixels stay crisp at any size. Shows the player's
/// default head (Steve/Alex by uuid) at once and swaps to the real one when it has loaded;
/// a blank or non-uuid value (offline, pending accounts) stays on the default head.
/// <see cref="Crisp"/> keeps every face pixel a whole number of screen pixels (the account pill and rows);
/// <see cref="CornerRadius"/> rounds the head's own corners (concentric with its container).
/// </summary>
public sealed class PlayerHead : FrameworkElement
{
    public static readonly DependencyProperty UuidProperty = DependencyProperty.Register(
        nameof(Uuid), typeof(string), typeof(PlayerHead),
        new FrameworkPropertyMetadata("", (d, _) => ((PlayerHead)d).Load()));

    /// <summary>The player's uuid (with or without dashes).</summary>
    public string Uuid { get => (string)GetValue(UuidProperty); set => SetValue(UuidProperty, value); }

    public static readonly DependencyProperty CrispProperty = DependencyProperty.Register(
        nameof(Crisp), typeof(bool), typeof(PlayerHead),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty CornerRadiusProperty = DependencyProperty.Register(
        nameof(CornerRadius), typeof(CornerRadius), typeof(PlayerHead),
        new FrameworkPropertyMetadata(new CornerRadius(0), FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>
    /// Draw the face at the largest whole multiple of its 8 pixels that fits, in screen pixels, centred. At 100 % and
    /// 200 % that is the box itself (24 = 3 x 8); at 125 % or 150 % a 24 px box is 30 or 36 screen pixels, and
    /// stretching 8 pixels over those makes some of them wider than others, so the face is drawn at 24 or 32
    /// instead and the leftover is an even margin.
    /// </summary>
    public bool Crisp { get => (bool)GetValue(CrispProperty); set => SetValue(CrispProperty, value); }

    /// <summary>Rounds the head's corners (all four use the top-left value). No border, no circle: a small, square-ish radius.</summary>
    public CornerRadius CornerRadius { get => (CornerRadius)GetValue(CornerRadiusProperty); set => SetValue(CornerRadiusProperty, value); }

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
        var rect = new Rect((ActualWidth - s) / 2, (ActualHeight - s) / 2, s, s);
        if (Crisp)
        {
            var (left, top, side) = HeadFit.Square(ActualWidth, ActualHeight, VisualTreeHelper.GetDpi(this).PixelsPerDip);
            rect = new Rect(left, top, side, side);
        }
        var radius = CornerRadius.TopLeft;
        if (radius > 0) dc.PushClip(new RectangleGeometry(rect, radius, radius));
        dc.DrawImage(_image, rect);
        if (radius > 0) dc.Pop();
    }
}
