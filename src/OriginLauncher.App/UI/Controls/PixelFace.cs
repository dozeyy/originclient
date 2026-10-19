using System.Windows;
using System.Windows.Media;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// A procedural 8x8 grey pixel head seeded from a player name: the launcher's
/// stand-in avatar (never a Mojang skin). Same seed, same face, everywhere.
/// Drawn as 64 frozen rectangles into one cached drawing; scales crisp.
/// </summary>
public sealed class PixelFace : FrameworkElement
{
    public static readonly DependencyProperty SeedProperty = DependencyProperty.Register(
        nameof(Seed), typeof(string), typeof(PixelFace),
        new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsRender, (d, _) => ((PixelFace)d)._drawing = null));

    public string Seed { get => (string)GetValue(SeedProperty); set => SetValue(SeedProperty, value); }

    private DrawingGroup? _drawing;

    public PixelFace()
    {
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
        RenderOptions.SetEdgeMode(this, EdgeMode.Aliased);
        SnapsToDevicePixels = true;
    }

    protected override void OnRender(DrawingContext dc)
    {
        _drawing ??= Build(Seed ?? "");
        var s = Math.Min(ActualWidth, ActualHeight);
        if (s <= 0) return;
        dc.PushTransform(new ScaleTransform(s / 8, s / 8));
        dc.DrawDrawing(_drawing);
        dc.Pop();
    }

    // Port of the mockup's face(): FNV-1a seed into mulberry32, a base skin grey, hair rows, eyes, mouth.
    private static DrawingGroup Build(string seed)
    {
        uint h = 2166136261;
        foreach (var c in seed) h = (h ^ c) * 16777619;
        var a = (int)h;
        double R()
        {
            unchecked
            {
                a = a + 0x6D2B79F5;
                var t = (int)((uint)(a ^ (int)((uint)a >> 15)) * (uint)(1 | a));
                t = (t + (int)((uint)(t ^ (int)((uint)t >> 7)) * (uint)(61 | t))) ^ t;
                return (uint)(t ^ (int)((uint)t >> 14)) / 4294967296.0;
            }
        }
        var b = 80 + (int)Math.Floor(R() * 60);
        var hair = 30 + (int)Math.Floor(R() * 50);
        var g = new DrawingGroup();
        using (var dc = g.Open())
        {
            for (var i = 0; i < 8; i++)
            for (var j = 0; j < 8; j++)
            {
                var v = b + (int)Math.Floor((R() - 0.5) * 14);
                if (j < 2 || (j < 3 && (i < 1 || i > 6))) v = hair + (int)Math.Floor(R() * 10);
                if (j == 4 && (i == 2 || i == 5)) v = 228;
                if (j == 4 && (i == 1 || i == 6)) v = b + 18;
                if (j == 6 && i > 2 && i < 5) v = b - 30;
                var y = (byte)Math.Clamp(v, 0, 255);
                var brush = new SolidColorBrush(Color.FromRgb(y, y, y));
                brush.Freeze();
                dc.DrawRectangle(brush, null, new Rect(i, j, 1, 1));
            }
        }
        g.Freeze();
        return g;
    }
}
