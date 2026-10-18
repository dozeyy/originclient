using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Shapes;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The game's Origin loading screen, drawn by the launcher (launch experience
/// v2, docs/MOTION.md): the same charcoal, the same four orbiting rings at the
/// same wall-clock angles, the same vignette, pixel "ORIGIN", bar and corner
/// brackets, laid out with the game's own formulas
/// (OriginScreenRenderer.renderLoading0 + rings.json, 1.21.1) in Minecraft GUI
/// units. When the game window fades in on top of it the two pictures
/// coincide, so the player sees one scene, not a window appearing.
///
/// It also plays the Play press (<see cref="Assemble"/>): the rings start as a
/// small Origin mark where the Play button was, spin up and fly out into the
/// orbit — the game's title-screen settle, run the other way.
///
/// Every value here is mirrored from the game; change them there first. Ticks
/// on CompositionTarget.Rendering only while visible.
/// </summary>
public sealed class LoadingScene : Grid
{
    // rings.json + tools/loading-screen/generate_textures.py, rings A..D:
    // stroke and blur are in ring-texture pixels (768 px texture, ellipse = 0.9 of it).
    private readonly record struct RingSpec(double WidthFrac, double Opacity, double Angle0, double PeriodS,
        bool Reverse, double StrokeTex, double BlurTex);

    private static readonly RingSpec[] Rings =
    [
        new(0.59, 0.42, 0, 40, false, 3.2, 0),
        new(0.81, 0.34, 45, 65, true, 2.8, 0),
        new(1.02, 0.26, 100, 90, false, 2.5, 1.4),
        new(1.27, 0.20, 15, 120, true, 2.2, 2.4),
    ];

    private const double TexEllipse = 0.9 * 768;
    private const double HeightRatio = 0.37;

    /// <summary>The Play press, mark to orbit: the game's title settle (SETTLE_MS) length.</summary>
    public const double AssembleMs = 1100;

    // The mark's rings as fractions of its size (OriginScreenRenderer.LOGO_RING),
    // OriginMark's ellipse aspect and stroke (180x64, 3 px).
    private static readonly double[] MarkRing = [0.90, 0.75, 0.60];
    private const double MarkAspect = 64.0 / 180.0;
    private const double MarkStroke = 3.0 / 180.0;
    // Turns each mark ring spins (in its orbit's direction) on its way out.
    private static readonly double[] SpinTurns = [1.0, 1.25, 1.5];

    // "ORIGIN" exactly as the game blits it from Minecraft's ascii.png (31 x 7
    // font pixels, 4 GUI units each), read off a filmed dev-shots run.
    private static readonly string[] Word =
    [
        ".###..####..###..####.###.#...#",
        "#...#.#...#..#..#......#..##..#",
        "#...#.####...#..#..##..#..#.#.#",
        "#...#.#...#..#..#...#..#..#..##",
        "#...#.#...#..#..#...#..#..#...#",
        "#...#.#...#..#..#...#..#..#...#",
        ".###..#...#.###..###..###.#...#",
    ];

    // OriginTheme (game) = Theme/Colors.xaml (launcher).
    private static readonly Brush BgBrush = Frozen(new SolidColorBrush(Color.FromRgb(0x0E, 0x11, 0x10)));
    private static readonly Brush TextBrush = Frozen(new SolidColorBrush(Color.FromRgb(0xF1, 0xE9, 0xD2)));
    private static readonly Brush TrackBrush = Frozen(new SolidColorBrush(Color.FromArgb(0x29, 0xFF, 0xFF, 0xFF)));
    private static readonly Brush AccentBrush = Frozen(new SolidColorBrush(Color.FromRgb(0x5F, 0xD0, 0x9B)));
    private static readonly Brush GlowBrush = Frozen(new SolidColorBrush(Color.FromArgb(0x59, 0x5F, 0xD0, 0x9B)));
    private static readonly Brush BracketBrush = Frozen(new SolidColorBrush(Color.FromArgb(0x52, 0x7D, 0x89, 0x7E)));
    private static readonly Color MarkColor = Color.FromRgb(0xF1, 0xE9, 0xD2);

    private readonly Layer _backdrop = new();
    private readonly Layer[] _rings = new Layer[Rings.Length];
    private readonly BlurEffect?[] _blur = new BlurEffect?[Rings.Length];
    private readonly Rectangle _vignette = new() { Fill = VignetteBrush() };
    private readonly Layer _front = new();
    private bool _ticking;

    // This frame's layout: DIPs per GUI unit, the screen in GUI units, device px per DIP.
    private double _u = 1, _w = 320, _h = 240, _dpd = 1;
    private long _now;

    private bool _assembling;
    private long _assembleAt;
    private Point _from;
    private double _fromSize;
    private readonly double[] _spinOffset = new double[3];

    private double _fill = -1;
    private long _fillAt;

    public LoadingScene()
    {
        IsHitTestVisible = false;
        ClipToBounds = true;
        _backdrop.Paint = dc => dc.DrawRectangle(BgBrush, null, new Rect(_backdrop.RenderSize));
        Children.Add(_backdrop);
        for (var i = 0; i < Rings.Length; i++)
        {
            var ring = i;
            _rings[i] = new Layer { Paint = dc => PaintRing(dc, ring) };
            if (Rings[i].BlurTex > 0)
                _rings[i].Effect = _blur[i] = new BlurEffect { RenderingBias = RenderingBias.Performance };
            Children.Add(_rings[i]);
        }
        Children.Add(_vignette);
        _front.Paint = PaintFront;
        Children.Add(_front);
        IsVisibleChanged += (_, _) => SyncTicking();
        Loaded += (_, _) => SyncTicking();
        Unloaded += (_, _) => SyncTicking();
    }

    /// <summary>The game's GUI scale, device pixels per GUI unit; 0 = Minecraft's auto rule for this size.</summary>
    public int GuiScale { get; set; }

    /// <summary>
    /// The window glide's in-between layout: DIPs per GUI unit and the screen
    /// in GUI units, interpolated between the two ends. Null: lay out exactly
    /// as the game would at this size.
    /// </summary>
    public (double Unit, double Width, double Height)? LayoutOverride { get; set; }

    /// <summary>The bar: a real 0..1 fraction (eased like the game's), or null for the game's sweep.</summary>
    public double? Progress { get; set; }

    /// <summary>Starts the Play press: the rings leave a mark of <paramref name="size"/> DIPs centred on <paramref name="from"/> (scene coordinates).</summary>
    public void Assemble(Point from, double size)
    {
        _assembling = SystemParameters.ClientAreaAnimation;
        if (!_assembling) return;
        _assembleAt = NowMs();
        _from = from;
        _fromSize = Math.Max(8, size);
        for (var i = 0; i < _spinOffset.Length; i++)
        {
            var r = Rings[i];
            var dir = r.Reverse ? -1 : 1;
            // From flat (0 degrees, the mark's rings stacked into one line) to the
            // ring's live orbit angle, the short way, plus whole spins.
            var toFlat = Wrap180(0 - WallClockAngle(_assembleAt, r));
            _spinOffset[i] = toFlat - dir * 360 * SpinTurns[i];
        }
    }

    /// <summary>The rings exactly where the game's are: angle0 + wall-clock time / period (RingField.wallClockAngle).</summary>
    public static double WallClockAngle(long nowMs, double angle0, double periodS, bool reverse)
    {
        var revs = nowMs / 1000.0 / periodS;
        return (angle0 + (reverse ? -revs : revs) * 360.0) % 360.0;
    }

    private static double WallClockAngle(long nowMs, RingSpec r) => WallClockAngle(nowMs, r.Angle0, r.PeriodS, r.Reverse);

    /// <summary>Minecraft's Window.calculateScale with GUI scale "Auto" and no forced Unicode font.</summary>
    public static int AutoGuiScale(int widthPx, int heightPx)
    {
        var i = 1;
        while (i < widthPx && i < heightPx && widthPx / (i + 1) >= 320 && heightPx / (i + 1) >= 240) i++;
        return i;
    }

    /// <summary>The DIPs per GUI unit the scene would use at its current size: the glide's starting point.</summary>
    public (double Unit, double Width, double Height) CurrentLayout()
    {
        Relayout();
        return (_u, _w, _h);
    }

    private void SyncTicking()
    {
        var want = IsLoaded && IsVisible;
        if (want == _ticking) return;
        _ticking = want;
        if (want) CompositionTarget.Rendering += OnRendering;
        else CompositionTarget.Rendering -= OnRendering;
    }

    private void OnRendering(object? sender, EventArgs e)
    {
        _now = NowMs();
        Relayout();
        AdvanceFill();

        var a = AssembleT();
        _backdrop.Opacity = _vignette.Opacity = Smooth(a / 0.4);
        _front.Opacity = Smooth((a - 0.6) / 0.4);
        for (var i = 0; i < Rings.Length; i++)
        {
            if (_blur[i] is { } blur)
                blur.Radius = Rings[i].BlurTex * 3 * (Rings[i].WidthFrac * _w * 0.99 / TexEllipse) * _u;
            _rings[i].InvalidateVisual();
        }
        _front.InvalidateVisual();
    }

    private void Relayout()
    {
        var source = PresentationSource.FromVisual(this);
        var d = source?.CompositionTarget?.TransformToDevice.M11 ?? 1;
        _dpd = d > 0 ? d : 1;
        if (LayoutOverride is { } o)
        {
            (_u, _w, _h) = o;
            return;
        }
        var wPx = Math.Max(1, (int)Math.Round(ActualWidth * _dpd));
        var hPx = Math.Max(1, (int)Math.Round(ActualHeight * _dpd));
        var scale = GuiScale > 0 ? GuiScale : AutoGuiScale(wPx, hPx);
        // Window.getGuiScaledWidth: rounded up.
        _w = Math.Ceiling(wPx / (double)scale);
        _h = Math.Ceiling(hPx / (double)scale);
        _u = scale / _dpd;
    }

    // The game eases its bar towards the real value (tau 140 ms); a sweep is -1.
    private void AdvanceFill()
    {
        var dt = Math.Clamp(_now - _fillAt, 0, 100);
        _fillAt = _now;
        if (Progress is not { } p)
        {
            _fill = -1;
            return;
        }
        p = Math.Clamp(p, 0, 1);
        _fill = _fill < 0 ? p : p + (_fill - p) * Math.Exp(-dt / 140.0);
    }

    private double AssembleT()
    {
        if (!_assembling) return 1;
        var t = (_now - _assembleAt) / AssembleMs;
        if (t >= 1) _assembling = false;
        return Math.Clamp(t, 0, 1);
    }

    private void PaintRing(DrawingContext dc, int i)
    {
        var r = Rings[i];
        var a = _assembling ? Math.Clamp((_now - _assembleAt) / AssembleMs, 0, 1) : 1;
        var center = new Point(_w / 2, _h / 2);
        var major = r.WidthFrac * _w * 0.99;
        var aspect = HeightRatio;
        var angle = WallClockAngle(_now, r);
        var alpha = r.Opacity;
        var stroke = r.StrokeTex * major / TexEllipse;
        var color = Colors.White;

        if (a < 1)
        {
            if (i >= _spinOffset.Length)
            {
                // The back ring isn't part of the mark: it fades in behind the others as they land.
                var k = Smooth((a - 0.45) / 0.55);
                alpha *= k;
                major *= 0.9 + 0.1 * k;
                stroke *= 0.9 + 0.1 * k;
            }
            else
            {
                var fly = EaseInOut(a);
                var from = new Point(_from.X / _u, _from.Y / _u);
                var m0 = _fromSize / _u * MarkRing[i];
                center = new Point(Lerp(from.X, center.X, fly), Lerp(from.Y, center.Y, fly));
                stroke = Lerp(m0 * MarkStroke, stroke, fly);
                major = Math.Exp(Lerp(Math.Log(m0), Math.Log(major), fly));
                aspect = Lerp(MarkAspect, HeightRatio, fly);
                // Spin-up: fast as it leaves the button, slowing into the orbit's own speed.
                angle += (1 - EaseOutCubic(a)) * _spinOffset[i];
                alpha = Lerp(0.95, r.Opacity, fly) * Smooth(a / 0.08);
                color = LerpColor(MarkColor, Colors.White, fly);
            }
        }
        if (alpha <= 0.002) return;

        var u = _u;
        var pen = new Pen(new SolidColorBrush(color) { Opacity = alpha }, Math.Max(0.5, stroke * u));
        var c = new Point(center.X * u, center.Y * u);
        // The texture's ellipse is drawn inside its outline: the stroke's outer edge is the major axis.
        var rx = Math.Max(0.1, major * u / 2 - pen.Thickness / 2);
        var ry = Math.Max(0.1, major * aspect * u / 2 - pen.Thickness / 2);
        dc.PushTransform(new RotateTransform(angle % 360.0, c.X, c.Y));
        dc.DrawEllipse(null, pen, c, rx, ry);
        dc.Pop();
    }

    private void PaintFront(DrawingContext dc)
    {
        double w = _w, h = _h;
        var cx = w / 2;
        var cy = h * 0.48;

        // ORIGIN: centred on (cx, cy), caps top 4 font px above it, 4 GUI units per font px.
        var x0 = cx - 15 * 4;
        var top = cy - 4 * 4;
        for (var row = 0; row < Word.Length; row++)
        {
            var line = Word[row];
            for (var col = 0; col < line.Length; col++)
            {
                if (line[col] != '#') continue;
                var end = col;
                while (end + 1 < line.Length && line[end + 1] == '#') end++;
                Fill(dc, TextBrush, x0 + col * 4, top + row * 4, x0 + (end + 1) * 4, top + (row + 1) * 4);
                col = end;
            }
        }

        // The bar (drawBar / drawIndeterminateBar): word-width, well under the mark.
        var barW = JRound(w * 0.24);
        var barH = Math.Max(3, JRound(h * 0.012));
        var by = JRound(cy + h * 0.135 * 1.15);
        var bx = JRound(cx - barW / 2.0);
        Fill(dc, TrackBrush, bx, by, bx + barW, by + barH);
        if (_fill >= 0)
        {
            var fillW = JRound(barW * _fill);
            if (fillW > 0)
            {
                Fill(dc, GlowBrush, bx - 1, by - 1, bx + fillW + 1, by + barH + 1);
                Fill(dc, AccentBrush, bx, by, bx + fillW, by + barH);
            }
        }
        else
        {
            var segW = Math.Max(8, (int)(barW * 0.32));
            var travel = Math.Max(1, barW - segW);
            var t = _now % 1800L / 1800.0;
            var eased = 0.5 - 0.5 * Math.Cos(t * 2.0 * Math.PI);
            var segX = bx + JRound(eased * travel);
            Fill(dc, GlowBrush, segX - 1, by - 1, segX + segW + 1, by + barH + 1);
            Fill(dc, AccentBrush, segX, by, segX + segW, by + barH);
        }

        // Corner brackets (drawCornerBrackets).
        var inset = Math.Max(10, JRound(w * 0.022));
        var len = Math.Max(10, JRound(w * 0.018));
        var th = Math.Max(1, JRound(w * 0.0015));
        Fill(dc, BracketBrush, inset, inset, inset + len, inset + th);
        Fill(dc, BracketBrush, inset, inset, inset + th, inset + len);
        Fill(dc, BracketBrush, w - inset - len, inset, w - inset, inset + th);
        Fill(dc, BracketBrush, w - inset - th, inset, w - inset, inset + len);
        Fill(dc, BracketBrush, inset, h - inset - th, inset + len, h - inset);
        Fill(dc, BracketBrush, inset, h - inset - len, inset + th, h - inset);
        Fill(dc, BracketBrush, w - inset - len, h - inset - th, w - inset, h - inset);
        Fill(dc, BracketBrush, w - inset - th, h - inset - len, w - inset, h - inset);
    }

    // A GUI-unit rectangle, edges snapped to device pixels the way the game's
    // rasteriser lands them (pixel centres inside the quad).
    private void Fill(DrawingContext dc, Brush brush, double x0, double y0, double x1, double y1)
    {
        var k = _u * _dpd;
        var l = Math.Round(x0 * k) / _dpd;
        var t = Math.Round(y0 * k) / _dpd;
        var r = Math.Round(x1 * k) / _dpd;
        var b = Math.Round(y1 * k) / _dpd;
        if (r > l && b > t) dc.DrawRectangle(brush, null, new Rect(l, t, r - l, b - t));
    }

    // generate_textures.py make_vignette: clear to 0.45 of the half-diagonal,
    // then t^2 up to 55% black in the corners, stretched to the screen.
    private static Brush VignetteBrush()
    {
        var brush = new RadialGradientBrush { RadiusX = Math.Sqrt(0.5), RadiusY = Math.Sqrt(0.5) };
        for (var k = 0; k <= 8; k++)
        {
            var t = k / 8.0;
            brush.GradientStops.Add(new GradientStop(Color.FromArgb((byte)Math.Round(t * t * 0.55 * 255), 0, 0, 0), 0.45 + 0.55 * t));
        }
        brush.Freeze();
        return brush;
    }

    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    private static int JRound(double v) => (int)Math.Floor(v + 0.5); // Java's Math.round
    private static double Lerp(double a, double b, double t) => a + (b - a) * t;
    private static double Smooth(double t) { t = Math.Clamp(t, 0, 1); return t * t * (3 - 2 * t); }
    private static double EaseOutCubic(double t) { t = 1 - Math.Clamp(t, 0, 1); return 1 - t * t * t; }
    private static double EaseInOut(double t)
    {
        t = Math.Clamp(t, 0, 1);
        return t < 0.5 ? 4 * t * t * t : 1 - Math.Pow(-2 * t + 2, 3) / 2;
    }
    private static double Wrap180(double deg) => ((deg % 360) + 540) % 360 - 180;

    private static Color LerpColor(Color a, Color b, double t) => Color.FromRgb(
        (byte)Math.Round(Lerp(a.R, b.R, t)), (byte)Math.Round(Lerp(a.G, b.G, t)), (byte)Math.Round(Lerp(a.B, b.B, t)));

    private static Brush Frozen(SolidColorBrush b)
    {
        b.Freeze();
        return b;
    }

    // One drawn layer of the scene; the Grid stretches each over the whole scene.
    private sealed class Layer : FrameworkElement
    {
        public Action<DrawingContext>? Paint;
        protected override void OnRender(DrawingContext dc) => Paint?.Invoke(dc);
    }
}
