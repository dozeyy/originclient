namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The Home numeral's placement maths, pure (no WPF) so the tests link it. The numeral stands in the free band
/// between the bottom of the top bar and the top of the Home strip: its figures are centred in that band both
/// ways, and sized by DESIGN-SYSTEM 11.6 (figures 32 % of the window's height, ink at most 2/3 of its width),
/// held to a share of the band so a short window keeps air above and below it. Nothing here scales a glyph
/// sideways: the only inputs are the face's metrics, so the type keeps its natural width.
/// </summary>
internal static class NumeralBand
{
    /// <summary>type.numeral: the figures' height (cap height) is this share of the window's height.</summary>
    public const double FigureShareOfHeight = 0.32;

    /// <summary>The ink (first figure's left edge to the last one's right edge) is at most this share of the width.</summary>
    public const double InkShareOfWidth = 2.0 / 3.0;

    /// <summary>The figures never fill more than this share of the band, so a short window keeps air around them.</summary>
    public const double FigureShareOfBand = 0.6;

    /// <summary>type.numeral tracking, in em (-0.04), applied between the figures.</summary>
    public const double Tracking = -0.04;

    /// <summary>
    /// The band from the bar's bottom edge to the strip's top edge. A strip that has not reported yet (NaN), or a
    /// window too short to leave any band, gives the whole space under the bar so the numeral still has a place.
    /// </summary>
    public static (double Top, double Bottom) Band(double windowHeight, double barBottom, double stripTop)
    {
        var top = Math.Max(0, barBottom);
        var bottom = double.IsNaN(stripTop) ? windowHeight : Math.Min(windowHeight, stripTop);
        return bottom - top < 1 ? (top, Math.Max(top + 1, windowHeight)) : (top, bottom);
    }

    /// <summary>The font size: the smaller of the height rule, the band rule and the width rule.</summary>
    /// <param name="capsEm">The figures' height as a fraction of the em.</param>
    /// <param name="inkEm">The ink width of the whole text in em (with tracking).</param>
    public static double FontSize(double windowWidth, double windowHeight, double bandTop, double bandBottom, double capsEm, double inkEm)
    {
        var caps = capsEm > 0 ? capsEm : 0.73;
        var figures = Math.Min(windowHeight * FigureShareOfHeight, Math.Max(0, bandBottom - bandTop) * FigureShareOfBand);
        var byHeight = figures / caps;
        var byWidth = inkEm > 0 ? windowWidth * InkShareOfWidth / inkEm : byHeight;
        return Math.Max(1, Math.Min(byHeight, byWidth));
    }

    /// <summary>The y of the middle of the figures: the middle of the band.</summary>
    public static double MiddleY(double bandTop, double bandBottom) => (bandTop + bandBottom) / 2;

    /// <summary>The x of the ink's left edge when the ink is centred across the window.</summary>
    public static double InkLeft(double windowWidth, double inkWidth) => (windowWidth - inkWidth) / 2;
}
