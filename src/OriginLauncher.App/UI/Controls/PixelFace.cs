using System.Windows;
using System.Windows.Media;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// A procedural 8x8 coloured pixel head seeded from a player name: the launcher's
/// stand-in avatar (never a Mojang skin). Same seed, same face, everywhere.
/// Drawn as 64 frozen rectangles into one cached drawing; scales crisp.
/// </summary>
public sealed class PixelFace : FrameworkElement
{
    public static readonly DependencyProperty SeedProperty = DependencyProperty.Register(
        nameof(Seed), typeof(string), typeof(PixelFace),
        new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsRender, (d, _) => ((PixelFace)d)._drawing = null));

    public string Seed { get => (string)GetValue(SeedProperty); set => SetValue(SeedProperty, value); }

    private static readonly (int, int, int)[] Skins =
    {
        (198, 134, 98), (224, 172, 130), (141, 85, 58), (240, 200, 160), (176, 116, 80), (100, 62, 42),
    };
    private static readonly (int, int, int)[] Hairs =
    {
        (58, 36, 22), (24, 20, 20), (214, 170, 70), (150, 70, 36), (110, 110, 118), (190, 60, 52), (74, 52, 120),
    };
    private static readonly (int, int, int)[] Irises =
    {
        (60, 110, 200), (70, 150, 90), (96, 62, 36), (110, 130, 150),
    };

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
        // Minecraft-like colour: a skin tone, a hair colour, an iris colour (all picked from the name seed).
        var skin = Skins[(int)Math.Floor(R() * Skins.Length)];
        var hair = Hairs[(int)Math.Floor(R() * Hairs.Length)];
        var iris = Irises[(int)Math.Floor(R() * Irises.Length)];
        var g = new DrawingGroup();
        using (var dc = g.Open())
        {
            for (var i = 0; i < 8; i++)
            for (var j = 0; j < 8; j++)
            {
                var noise = (int)Math.Floor((R() - 0.5) * 12);
                var c = skin;
                var tint = noise;
                if (j < 2 || (j < 3 && (i < 1 || i > 6))) { c = hair; tint = (int)Math.Floor((R() - 0.5) * 16); }
                else if (j == 4 && (i == 1 || i == 6)) { c = (255, 255, 255); tint = -20; }
                else if (j == 4 && (i == 2 || i == 5)) { c = iris; tint = 0; }
                else if (j == 6 && i > 2 && i < 5) { c = (skin.Item1 * 6 / 10 + 40, skin.Item2 * 5 / 10, skin.Item3 * 5 / 10); tint = 0; }
                byte Ch(int v) => (byte)Math.Clamp(v + tint, 0, 255);
                var brush = new SolidColorBrush(Color.FromRgb(Ch(c.Item1), Ch(c.Item2), Ch(c.Item3)));
                brush.Freeze();
                dc.DrawRectangle(brush, null, new Rect(i, j, 1, 1));
            }
        }
        g.Freeze();
        return g;
    }
}
