namespace OriginLauncher.App.UI.Pages;

/// <summary>
/// Which rail item is lit on the Settings page (pure: numbers in, an index out, so a test can hold it).
/// The rail is the page's section list. Two things light an item: the player clicked it
/// (<see cref="Pinned"/>), or the player scrolled and the section under the top edge changed
/// (<see cref="Active"/>). A click always wins until the player scrolls on their own.
/// </summary>
public sealed class SettingsRailSpy
{
    /// <summary>How far below the top edge a section may start and still count as "the one in view".</summary>
    public const double Lead = 60;

    /// <summary>The top bar's height: content above this line is hidden under the bar, so a section there is not "in view".</summary>
    public const double TopInset = 56;

    /// <summary>Space kept above a section when a click scrolls it into place.</summary>
    public const double Margin = 24;

    /// <summary>The rail item the player last clicked; null once they scroll by hand.</summary>
    public int? Pinned { get; private set; }

    /// <summary>
    /// The last section whose top is at or above the top edge (plus <see cref="Lead"/>); the first one when none is.
    /// At the very end of the page the short last sections can never reach the top edge, so the last-above-the-edge rule
    /// would light the wrong neighbour: there the first section whose top is inside the window (below the top bar) is lit.
    /// </summary>
    public static int Active(IReadOnlyList<double> sectionTops, double verticalOffset, double scrollableHeight)
    {
        if (scrollableHeight > 0 && verticalOffset >= scrollableHeight - 1)
            for (var i = 0; i < sectionTops.Count; i++)
                if (sectionTops[i] >= verticalOffset + TopInset) return i;
        var current = 0;
        for (var i = 0; i < sectionTops.Count; i++)
            if (sectionTops[i] <= verticalOffset + Lead) current = i;
        return current;
    }

    /// <summary>
    /// Where a click on <paramref name="sectionTop"/> scrolls to: the section sits <see cref="Margin"/> below the edge, but
    /// never past the end of the page. The last sections are shorter than the window, so they cannot reach
    /// the edge; that is why a click has to be remembered (<see cref="Click"/>) instead of re-derived from the offset.
    /// </summary>
    public static double TargetOffset(double sectionTop, double scrollableHeight) =>
        Math.Clamp(sectionTop - Margin, 0, Math.Max(0, scrollableHeight));

    /// <summary>The player clicked rail item <paramref name="index"/>.</summary>
    public void Click(int index) => Pinned = index;

    /// <summary>The player scrolled by hand (wheel, keys, touch, a drag): the click no longer decides.</summary>
    public void UserScrolled() => Pinned = null;

    /// <summary>The page was opened again: start from the top with nothing remembered.</summary>
    public void Reset() => Pinned = null;

    /// <summary>The item to light for this scroll position: the clicked one while a click stands, else the section in view.</summary>
    public int Resolve(IReadOnlyList<double> sectionTops, double verticalOffset, double scrollableHeight) =>
        Pinned is { } pinned && pinned >= 0 && pinned < sectionTops.Count ? pinned : Active(sectionTops, verticalOffset, scrollableHeight);
}
