namespace OriginLauncher.App.UI.Controls;

// The maths behind the art-aware scrim under a version number on a card (DESIGN-SYSTEM.md 11.6, U3.4).
//
// The number stands in a radial shade that grows from the card's bottom-left corner (NumeralShade). A fixed shade
// is too heavy on dark art and too weak on light art, so the shade's overall strength is chosen PER ART: the least
// that keeps the neutral-white number at the target contrast over the art actually under it. The answer is one
// number per card art, worked out once when the art decodes (off the UI thread); drawing it later is a gradient
// fill with no per-frame work.
//
// No WPF types in here: the unit tests (src/OriginLauncher.Tests) link this file as it is.

/// <summary>The radial shade's alpha profile: elliptical, centred on the bottom-left corner. Radii are fractions of
/// the card's width and height; the alpha runs Peak at the centre, Mid at MidAt, 0 at the edge (stops, linear
/// between them). The same numbers build the gradient brush, so what is measured is what is drawn.</summary>
public readonly record struct NumeralShade(double Peak, double Mid, double MidAt, double RadiusX, double RadiusY)
{
    /// <summary>The shade alpha (0..1) at a point given as fractions of the card, measured from the bottom-left corner
    /// (fx to the right, fy up). At full strength.</summary>
    public double AlphaAt(double fx, double fy)
    {
        var t = Math.Sqrt(Square(fx / RadiusX) + Square(fy / RadiusY));
        if (t >= 1) return 0;
        if (t <= MidAt) return Peak + (Mid - Peak) * (t / MidAt);
        return Mid * (1 - (t - MidAt) / (1 - MidAt));
    }

    private static double Square(double v) => v * v;
}

/// <summary>A coarse, averaged copy of a card's art: Cols x Rows cells, three sRGB channels (0..1) each. Built once
/// per art from the decoded thumbnail.</summary>
public sealed class CardArtGrid
{
    public CardArtGrid(int cols, int rows, double[] rgb)
    {
        Cols = cols;
        Rows = rows;
        Rgb = rgb;
    }

    public int Cols { get; }
    public int Rows { get; }
    /// <summary>Row-major r,g,b triples.</summary>
    public double[] Rgb { get; }

    /// <summary>Averages a BGRA (or BGR32) pixel buffer down to cols x rows blocks.</summary>
    public static CardArtGrid FromBgra(byte[] pixels, int width, int height, int stride, int cols, int rows)
    {
        var rgb = new double[cols * rows * 3];
        for (var row = 0; row < rows; row++)
        {
            var y0 = row * height / rows;
            var y1 = Math.Max(y0 + 1, (row + 1) * height / rows);
            for (var col = 0; col < cols; col++)
            {
                var x0 = col * width / cols;
                var x1 = Math.Max(x0 + 1, (col + 1) * width / cols);
                double r = 0, g = 0, b = 0;
                var n = 0;
                for (var y = y0; y < y1 && y < height; y++)
                    for (var x = x0; x < x1 && x < width; x++)
                    {
                        var i = y * stride + x * 4;
                        b += pixels[i];
                        g += pixels[i + 1];
                        r += pixels[i + 2];
                        n++;
                    }
                var o = (row * cols + col) * 3;
                if (n == 0) continue;
                rgb[o] = r / n / 255.0;
                rgb[o + 1] = g / n / 255.0;
                rgb[o + 2] = b / n / 255.0;
            }
        }
        return new CardArtGrid(cols, rows, rgb);
    }
}

/// <summary>What the scrim has to hold: the number's ink and the shade colour (sRGB 0..1), the contrast to reach, the
/// weakest strength allowed (so every card shares one family of ground) and the zone of the art the number can occupy
/// (fractions of the card from the bottom-left: ZoneWidth of the width, ZoneHeight of the height).</summary>
public readonly record struct ScrimSpec(
    NumeralShade Shade,
    double InkR, double InkG, double InkB,
    double ShadeR, double ShadeG, double ShadeB,
    double TargetContrast,
    double Floor,
    double ZoneWidth,
    double ZoneHeight);

/// <summary>One art's scrim: the strength to draw the shade at (Floor..1) and the art's mean luminance in the zone
/// (0 dark .. 1 light), kept for the shot report.</summary>
public readonly record struct ScrimResult(double Strength, double ZoneLuminance);

public static class CardArtScrim
{
    /// <summary>WCAG relative luminance of an sRGB colour (channels 0..1).</summary>
    public static double Luminance(double r, double g, double b) =>
        0.2126 * Linear(r) + 0.7152 * Linear(g) + 0.0722 * Linear(b);

    private static double Linear(double c) => c <= 0.04045 ? c / 12.92 : Math.Pow((c + 0.055) / 1.055, 2.4);

    /// <summary>WCAG contrast ratio of two luminances.</summary>
    public static double Contrast(double l1, double l2)
    {
        var hi = Math.Max(l1, l2);
        var lo = Math.Min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    /// <summary>The luminance of an art colour after the shade at <paramref name="alpha"/> is laid over it (WPF blends
    /// in sRGB, so the blend is in sRGB too).</summary>
    public static double Shaded(double r, double g, double b, ScrimSpec spec, double alpha) =>
        Luminance(r + (spec.ShadeR - r) * alpha, g + (spec.ShadeG - g) * alpha, b + (spec.ShadeB - b) * alpha);

    /// <summary>The weakest shade strength (Floor..1) that keeps the ink at the target contrast over every cell the
    /// number can sit on. Where even full strength cannot get there (the shade fades out away from the corner) the
    /// answer is 1: as strong as the design allows.</summary>
    public static ScrimResult Analyse(CardArtGrid art, ScrimSpec spec)
    {
        var inkLum = Luminance(spec.InkR, spec.InkG, spec.InkB);
        // The brightest background the ink may stand on, from contrast = (ink + .05) / (bg + .05).
        var maxBg = (inkLum + 0.05) / spec.TargetContrast - 0.05;
        var strength = spec.Floor;
        double lumSum = 0;
        var cells = 0;
        for (var row = 0; row < art.Rows; row++)
        {
            var fy = 1 - (row + 0.5) / art.Rows;
            if (fy > spec.ZoneHeight) continue;
            for (var col = 0; col < art.Cols; col++)
            {
                var fx = (col + 0.5) / art.Cols;
                if (fx > spec.ZoneWidth) continue;
                var o = (row * art.Cols + col) * 3;
                double r = art.Rgb[o], g = art.Rgb[o + 1], b = art.Rgb[o + 2];
                lumSum += Luminance(r, g, b);
                cells++;
                var profile = spec.Shade.AlphaAt(fx, fy);
                var need = AlphaNeeded(r, g, b, spec, maxBg);
                if (need <= 0) continue;
                strength = profile <= 1e-6 ? 1 : Math.Max(strength, Math.Min(1, need / profile));
            }
        }
        return new ScrimResult(Math.Clamp(strength, spec.Floor, 1), cells == 0 ? 0 : lumSum / cells);
    }

    /// <summary>The worst (lowest) ink contrast over the zone's cells with the shade drawn at <paramref name="strength"/>;
    /// the model's own check of <see cref="Analyse"/> (the shot harness measures the real rendered pixels as well).</summary>
    public static double WorstContrast(CardArtGrid art, ScrimSpec spec, double strength)
    {
        var inkLum = Luminance(spec.InkR, spec.InkG, spec.InkB);
        var worst = double.MaxValue;
        for (var row = 0; row < art.Rows; row++)
        {
            var fy = 1 - (row + 0.5) / art.Rows;
            if (fy > spec.ZoneHeight) continue;
            for (var col = 0; col < art.Cols; col++)
            {
                var fx = (col + 0.5) / art.Cols;
                if (fx > spec.ZoneWidth) continue;
                var o = (row * art.Cols + col) * 3;
                var lum = Shaded(art.Rgb[o], art.Rgb[o + 1], art.Rgb[o + 2], spec, strength * spec.Shade.AlphaAt(fx, fy));
                worst = Math.Min(worst, Contrast(inkLum, lum));
            }
        }
        return worst == double.MaxValue ? double.PositiveInfinity : worst;
    }

    // The least shade alpha that brings this colour down to maxBg (bisection: the shade is darker than any art that
    // needs it, so luminance only falls as alpha rises). 0 = already readable, 1 = unreachable.
    private static double AlphaNeeded(double r, double g, double b, ScrimSpec spec, double maxBg)
    {
        if (Shaded(r, g, b, spec, 0) <= maxBg) return 0;
        if (Shaded(r, g, b, spec, 1) > maxBg) return 1;
        double lo = 0, hi = 1;
        for (var i = 0; i < 24; i++)
        {
            var mid = (lo + hi) / 2;
            if (Shaded(r, g, b, spec, mid) <= maxBg) hi = mid; else lo = mid;
        }
        return hi;
    }
}
