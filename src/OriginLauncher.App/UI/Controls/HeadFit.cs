namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// Where a player's 8 x 8 face is drawn when it must stay crisp (PlayerHead.Crisp): the largest whole multiple of 8
/// screen pixels that fits the box, centred on whole screen pixels. Pure arithmetic, so a test can pin it down.
/// </summary>
public static class HeadFit
{
    /// <summary>The face's pixels per side in the 8 x 8 head.</summary>
    public const int Pixels = 8;

    /// <summary>
    /// Left, top and side, in layout units, of the square to draw in a <paramref name="width"/> x <paramref name="height"/>
    /// box at <paramref name="pixelsPerDip"/> screen pixels per layout unit (1 at 100 %, 1.5 at 150 %). A box under 8
    /// screen pixels is drawn whole (nothing to snap); otherwise each face pixel is the same whole number of screen pixels.
    /// </summary>
    public static (double Left, double Top, double Side) Square(double width, double height, double pixelsPerDip)
    {
        if (pixelsPerDip <= 0) pixelsPerDip = 1;
        var box = Math.Min(width, height) * pixelsPerDip;
        var side = box < Pixels ? box : Math.Floor(box / Pixels) * Pixels;
        var left = Math.Round((width * pixelsPerDip - side) / 2) / pixelsPerDip;
        var top = Math.Round((height * pixelsPerDip - side) / 2) / pixelsPerDip;
        return (left, top, side / pixelsPerDip);
    }
}
