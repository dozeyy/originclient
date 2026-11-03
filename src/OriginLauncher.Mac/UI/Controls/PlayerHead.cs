using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using OriginLauncher.App.Core.Skins;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// Twin of the WPF UI/Controls/PlayerHead.cs: a player's Minecraft head (Core/Skins/PlayerHeads), drawn square and
/// nearest-neighbour so the 8x8 face stays crisp at any size. The default head (Steve/Alex by uuid) shows at once and
/// the real one swaps in when it has loaded; a blank or non-uuid value (offline, pending) stays on the default.
/// <see cref="Crisp"/> keeps every face pixel a whole number of screen pixels (HeadFit, shared with WPF);
/// <see cref="CornerRadius"/> rounds the head's own corners (concentric with its container).
/// </summary>
public sealed class PlayerHead : Control
{
    public static readonly StyledProperty<string?> UuidProperty =
        AvaloniaProperty.Register<PlayerHead, string?>(nameof(Uuid), "");
    public static readonly StyledProperty<bool> CrispProperty =
        AvaloniaProperty.Register<PlayerHead, bool>(nameof(Crisp));
    public static readonly StyledProperty<CornerRadius> CornerRadiusProperty =
        AvaloniaProperty.Register<PlayerHead, CornerRadius>(nameof(CornerRadius));

    /// <summary>The player's uuid (with or without dashes).</summary>
    public string? Uuid { get => GetValue(UuidProperty); set => SetValue(UuidProperty, value); }
    public bool Crisp { get => GetValue(CrispProperty); set => SetValue(CrispProperty, value); }
    /// <summary>All four corners use the top-left value: a small, square-ish radius, never a circle.</summary>
    public CornerRadius CornerRadius { get => GetValue(CornerRadiusProperty); set => SetValue(CornerRadiusProperty, value); }

    private Bitmap? _image;

    static PlayerHead()
    {
        AffectsRender<PlayerHead>(CrispProperty, CornerRadiusProperty);
    }

    public PlayerHead()
    {
        RenderOptions.SetBitmapInterpolationMode(this, BitmapInterpolationMode.None);
        Load();
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == UuidProperty) Load();
    }

    private void Load()
    {
        var uuid = Uuid ?? "";
        _image = PlayerHeads.Loaded(uuid) ?? PlayerHeads.Default(uuid);
        InvalidateVisual();
        var load = PlayerHeads.Get(uuid);
        if (load.IsCompleted) { Show(uuid, load); return; }
        load.ContinueWith(t => Dispatcher.UIThread.Post(() => Show(uuid, t)), TaskScheduler.Default);
    }

    // A recycled row may have moved on to another player while the load ran: only the current player's head shows.
    private void Show(string uuid, Task<Bitmap?> load)
    {
        if (!load.IsCompletedSuccessfully || load.Result is not { } head || uuid != (Uuid ?? "")) return;
        if (ReferenceEquals(head, _image)) return;
        _image = head;
        InvalidateVisual();
    }

    public override void Render(DrawingContext context)
    {
        _image ??= PlayerHeads.Default(Uuid);
        double w = Bounds.Width, h = Bounds.Height;
        var s = Math.Min(w, h);
        if (s <= 0 || _image == null) return;
        var rect = new Rect((w - s) / 2, (h - s) / 2, s, s);
        if (Crisp)
        {
            var scale = TopLevel.GetTopLevel(this)?.RenderScaling ?? 1;
            var (left, top, side) = HeadFit.Square(w, h, scale);
            rect = new Rect(left, top, side, side);
        }
        var radius = CornerRadius.TopLeft;
        using (radius > 0 ? context.PushClip(new RoundedRect(rect, radius)) : default(DrawingContext.PushedState?))
        {
            context.DrawImage(_image, new Rect(_image.Size), rect);
        }
    }
}
