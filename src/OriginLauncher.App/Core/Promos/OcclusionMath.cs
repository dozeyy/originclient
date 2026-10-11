namespace OriginLauncher.App.Core.Promos;

/// <summary>A screen rectangle in physical pixels, right/bottom exclusive (Win32 RECT semantics).</summary>
public readonly record struct ScreenRect(int Left, int Top, int Right, int Bottom)
{
    public bool IsEmpty => Right <= Left || Bottom <= Top;
    public long Area => IsEmpty ? 0 : (long)(Right - Left) * (Bottom - Top);

    public ScreenRect Intersect(ScreenRect other) => new(
        Math.Max(Left, other.Left), Math.Max(Top, other.Top),
        Math.Min(Right, other.Right), Math.Min(Bottom, other.Bottom));
}

/// <summary>
/// How much of the banner other windows hide. Modrinth's app sums the
/// overlap of each window above it; that double-counts windows that overlap
/// EACH OTHER (two stacked windows over a quarter of the banner read as half).
/// This computes the exact union instead — coordinate compression over the
/// few rectangles that actually intersect the banner, which is a handful even
/// on a busy desktop, so it stays cheap at a 1.5 s poll.
/// </summary>
public static class OcclusionMath
{
    /// <summary>
    /// At or above this covered fraction the banner counts as hidden. 0.5 is
    /// the industry (IAB/MRC) viewability line — "at least half the pixels on
    /// screen" — and is stricter than "fully covered": the banner pauses
    /// sooner, never later.
    /// </summary>
    public const double HiddenThreshold = 0.5;

    public static bool IsHidden(ScreenRect target, IReadOnlyList<ScreenRect> occluders) =>
        CoveredFraction(target, occluders) >= HiddenThreshold;

    /// <summary>Fraction (0..1) of <paramref name="target"/> covered by the union of <paramref name="occluders"/>.</summary>
    public static double CoveredFraction(ScreenRect target, IReadOnlyList<ScreenRect> occluders)
    {
        if (target.IsEmpty) return 0;

        var clipped = new List<ScreenRect>(occluders.Count);
        foreach (var r in occluders)
        {
            var hit = r.Intersect(target);
            if (!hit.IsEmpty) clipped.Add(hit);
        }
        if (clipped.Count == 0) return 0;

        var xs = new SortedSet<int> { target.Left, target.Right };
        var ys = new SortedSet<int> { target.Top, target.Bottom };
        foreach (var r in clipped)
        {
            xs.Add(r.Left); xs.Add(r.Right);
            ys.Add(r.Top); ys.Add(r.Bottom);
        }
        var x = xs.ToArray();
        var y = ys.ToArray();

        long covered = 0;
        for (var i = 0; i < x.Length - 1; i++)
        {
            for (var j = 0; j < y.Length - 1; j++)
            {
                // A grid cell is either fully inside a rectangle or fully
                // outside it, so testing its top-left corner is exact.
                foreach (var r in clipped)
                {
                    if (x[i] >= r.Left && x[i] < r.Right && y[j] >= r.Top && y[j] < r.Bottom)
                    {
                        covered += (long)(x[i + 1] - x[i]) * (y[j + 1] - y[j]);
                        break;
                    }
                }
            }
        }
        return Math.Min(1.0, (double)covered / target.Area);
    }
}
