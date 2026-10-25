using System.Globalization;
using System.Windows;
using System.Windows.Media;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// A version number set into a card's art (DESIGN-SYSTEM.md 11.6, U3.4): the ground-line rule of the Home numeral at
/// card size. Bottom-left, a radial shade from the corner under it, figures 25 % of the art's height, Inter 800 at its
/// natural width, -0.04 em, neutral white with a 1 px drawn shadow. The range ("1.21 - 1.21.11") stands beside it on
/// the same baseline when the card is wide enough. Lay it over the art, filling the card; it never takes input.
///
/// The shade's strength (<see cref="Strength"/>) is the per-art answer from <see cref="CardArtScrim"/>, worked out once
/// when the art decodes; everything here is drawn from cached geometry, so a frame costs one gradient fill and a few
/// glyph draws, and the layout is rebuilt only when the text, the size or the DPI changes. Everything it draws comes
/// from a token (Theme/Colors.xaml, Typography.xaml, Spacing.xaml).
/// </summary>
public sealed class VersionCardNumeral : FrameworkElement
{
    public static readonly DependencyProperty TextProperty = DependencyProperty.Register(
        nameof(Text), typeof(string), typeof(VersionCardNumeral),
        new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsRender, OnLayoutInput));

    public static readonly DependencyProperty RangeProperty = DependencyProperty.Register(
        nameof(Range), typeof(string), typeof(VersionCardNumeral),
        new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsRender, OnLayoutInput));

    /// <summary>1 = the design's full corner shade; the art-aware value is between Numeral.Shade.Floor and 1.</summary>
    public static readonly DependencyProperty StrengthProperty = DependencyProperty.Register(
        nameof(Strength), typeof(double), typeof(VersionCardNumeral),
        new FrameworkPropertyMetadata(1.0, FrameworkPropertyMetadataOptions.AffectsRender, (d, _) => ((VersionCardNumeral)d)._shade = null));

    private Layout? _layout;
    private Brush? _shade;
    private double _layoutWidth, _layoutHeight, _layoutDpi;

    public VersionCardNumeral()
    {
        IsHitTestVisible = false;
        Focusable = false;
        SnapsToDevicePixels = true;
    }

    public string Text { get => (string)GetValue(TextProperty); set => SetValue(TextProperty, value); }
    public string Range { get => (string)GetValue(RangeProperty); set => SetValue(RangeProperty, value); }
    public double Strength { get => (double)GetValue(StrengthProperty); set => SetValue(StrengthProperty, value); }

    /// <summary>Where the figures' ink sits, in this element's coordinates (the shot harness measures contrast there).</summary>
    internal Rect InkBounds
    {
        get
        {
            EnsureLayout();
            return _layout?.Ink ?? Rect.Empty;
        }
    }

    /// <summary>True when the range was drawn in the art (the card is wide enough and it fits beside the figures).</summary>
    internal bool RangeShown
    {
        get
        {
            EnsureLayout();
            return _layout?.RangeAt != null;
        }
    }

    private static void OnLayoutInput(DependencyObject d, DependencyPropertyChangedEventArgs e) => ((VersionCardNumeral)d)._layout = null;

    protected override void OnRender(DrawingContext dc)
    {
        var w = ActualWidth;
        var h = ActualHeight;
        if (w <= 0 || h <= 0) return;

        _shade ??= BuildShade(Strength);
        dc.DrawRectangle(_shade, null, new Rect(0, 0, w, h));

        EnsureLayout();
        if (_layout is not { } layout) return;
        // The legibility shadow first (same glyphs, +1 px down), then the ink on top.
        foreach (var g in layout.Shadows) dc.DrawText(g.Text, g.At);
        foreach (var g in layout.Figures) dc.DrawText(g.Text, g.At);
        if (layout.RangeText != null && layout.RangeAt is { } at) dc.DrawText(layout.RangeText, at);
    }

    protected override void OnRenderSizeChanged(SizeChangedInfo sizeInfo)
    {
        base.OnRenderSizeChanged(sizeInfo);
        _layout = null;
    }

    private void EnsureLayout()
    {
        var w = ActualWidth;
        var h = ActualHeight;
        var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        if (_layout != null && w == _layoutWidth && h == _layoutHeight && dpi == _layoutDpi) return;
        _layoutWidth = w;
        _layoutHeight = h;
        _layoutDpi = dpi;
        _layout = w > 0 && h > 0 && !string.IsNullOrEmpty(Text) ? BuildLayout(Text, Range, w, h, dpi) : null;
    }

    // ---- tokens (read once; the resource dictionaries are on the UI thread, which is where this runs) ----

    private static double Num(string key) => (double)Application.Current.FindResource(key);

    private static Color BgColor => (Color)Application.Current.FindResource("Color.Bg");

    /// <summary>The scrim maths' inputs from the tokens. Call on the UI thread; the result is plain data, safe to take
    /// to a worker thread.</summary>
    internal static ScrimSpec Spec()
    {
        var ink = (Color)Application.Current.FindResource("Color.NumeralInk");
        var bg = BgColor;
        return new ScrimSpec(
            new NumeralShade(Num("Numeral.Shade.Peak"), Num("Numeral.Shade.Mid"), Num("Numeral.Shade.MidAt"),
                Num("Numeral.Shade.RadiusX"), Num("Numeral.Shade.RadiusY")),
            ink.R / 255.0, ink.G / 255.0, ink.B / 255.0,
            bg.R / 255.0, bg.G / 255.0, bg.B / 255.0,
            Num("Numeral.Contrast"), Num("Numeral.Shade.Floor"), Num("Numeral.Zone.Width"), Num("Numeral.Zone.Height"));
    }

    private static Brush BuildShade(double strength)
    {
        var bg = BgColor;
        var peak = Num("Numeral.Shade.Peak");
        var mid = Num("Numeral.Shade.Mid");
        Color Stop(double a) => Color.FromArgb((byte)Math.Round(255 * Math.Clamp(a * strength, 0, 1)), bg.R, bg.G, bg.B);
        var brush = new RadialGradientBrush
        {
            Center = new Point(0, 1),
            GradientOrigin = new Point(0, 1),
            RadiusX = Num("Numeral.Shade.RadiusX"),
            RadiusY = Num("Numeral.Shade.RadiusY"),
        };
        brush.GradientStops.Add(new GradientStop(Stop(peak), 0));
        brush.GradientStops.Add(new GradientStop(Stop(mid), Num("Numeral.Shade.MidAt")));
        brush.GradientStops.Add(new GradientStop(Stop(0), 1));
        brush.Freeze();
        return brush;
    }

    // ---- layout ----

    private sealed record Glyph(FormattedText Text, Point At);

    private sealed record Layout(Glyph[] Figures, Glyph[] Shadows, Rect Ink, FormattedText? RangeText, Point? RangeAt);

    private static Typeface? _face;
    private static GlyphTypeface? _glyphs;

    private static Typeface Face => _face ??= new Typeface((FontFamily)Application.Current.FindResource("Font.Primary"),
        FontStyles.Normal, FontWeights.ExtraBold, FontStretches.Normal);

    private static Layout BuildLayout(string text, string range, double w, double h, double dpi)
    {
        _glyphs ??= Face.TryGetGlyphTypeface(out var gt) ? gt : null;
        var ink = (Brush)Application.Current.FindResource("Brush.NumeralInk");
        var shadow = (Brush)Application.Current.FindResource("Brush.NumeralShadow");
        var gutter = Num("Space.3");
        var ground = Num("Space.3");
        var track = Num("Numeral.Tracking");
        var figureEm = Num("Numeral.FigureEm");

        // Figure height = 25 % of the art, so the font size follows (Inter's figures are 0.7275 em).
        var size = h * Num("Numeral.FigureShare") / figureEm;
        var (advances, lsb, rsb) = Metrics(text, size, track);
        var inkWidth = advances.Sum() - lsb - rsb;
        // Never wider than 2/3 of the art: past that the font shrinks to fit (ink scales with the font size).
        var cap = w * Num("Numeral.InkMaxShare");
        if (inkWidth > cap && inkWidth > 0)
        {
            size *= cap / inkWidth;
            (advances, lsb, rsb) = Metrics(text, size, track);
            inkWidth = advances.Sum() - lsb - rsb;
        }

        // The first figure's INK sits on the gutter (not its text box), the baseline on the ground line.
        var baseline = h - ground;
        var x = gutter - lsb;
        var glyphs = new Glyph[text.Length];
        var shadows = new Glyph[text.Length];
        for (var i = 0; i < text.Length; i++)
        {
            var ft = new FormattedText(text[i].ToString(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, Face, size, ink, dpi);
            var sft = new FormattedText(text[i].ToString(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, Face, size, shadow, dpi);
            var top = baseline - ft.Baseline;
            glyphs[i] = new Glyph(ft, new Point(x, top));
            shadows[i] = new Glyph(sft, new Point(x, top + 1));
            x += advances[i];
        }

        var figureHeight = figureEm * size;
        var inkRect = new Rect(gutter, baseline - figureHeight, Math.Max(0, inkWidth), figureHeight);

        // The range, on the numeral's baseline, one gutter after it; only on a card wide enough, and only if it fits.
        FormattedText? rangeText = null;
        Point? rangeAt = null;
        if (!string.IsNullOrEmpty(range) && w >= Num("Numeral.RangeMinCard"))
        {
            var caption = new Typeface((FontFamily)Application.Current.FindResource("Font.Primary"),
                FontStyles.Normal, FontWeights.Normal, FontStretches.Normal);
            var rft = new FormattedText(range, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, caption,
                Num("FontSize.Caption"), (Brush)Application.Current.FindResource("Brush.OnArt"), dpi);
            var rx = inkRect.Right + gutter;
            if (rx + rft.WidthIncludingTrailingWhitespace + gutter <= w)
            {
                rangeText = rft;
                rangeAt = new Point(rx, baseline - rft.Baseline);
            }
        }

        return new Layout(glyphs, shadows, inkRect, rangeText, rangeAt);
    }

    // Advance of each character with the tracking after it (all but the last), plus the first figure's left and the
    // last figure's right side bearing at this size: the ink is the sum of the advances minus those two bearings.
    private static (double[] Advances, double Lsb, double Rsb) Metrics(string text, double size, double track)
    {
        var advances = new double[text.Length];
        double lsb = 0, rsb = 0;
        for (var i = 0; i < text.Length; i++)
        {
            double adv = size * 0.6, l = 0, r = 0;
            if (_glyphs != null && _glyphs.CharacterToGlyphMap.TryGetValue(text[i], out var index))
            {
                adv = _glyphs.AdvanceWidths[index] * size;
                l = _glyphs.LeftSideBearings[index] * size;
                r = _glyphs.RightSideBearings[index] * size;
            }
            advances[i] = adv + (i < text.Length - 1 ? track * size : 0);
            if (i == 0) lsb = l;
            if (i == text.Length - 1) rsb = r;
        }
        return (advances, lsb, rsb);
    }
}
