namespace OriginLauncher.App.Core;

/// <summary>
/// Home's status line under Play, the same rule on all three launchers (WPF, Mac, Linux). The line wraps at the status
/// width (400 px) instead of ending in "..." after one row: the running line carries notices that end with what to do
/// ("Add a matching Iris to turn them back on"), and that part was cut off with no tooltip (bug run 7, WP-2).
/// </summary>
public static class StatusLineRule
{
    /// <summary>Rows any line may take; past them it ends in "..." (the tooltip has the rest). An error keeps its own
    /// fixed two-row slot (Size.StatusLinesMax), so the card rises by the same amount for every error.</summary>
    public const int MaxRows = 3;

    // About what one row holds at the status width in the caption face (the 07b shot's measure: ~5.5 px a
    // character, 400 px). Lines longer than that may wrap or end in "...", so they carry the whole text as a tooltip;
    // a short "Ready to play" gets none.
    private const int OneRowChars = 64;

    /// <summary>True when the line should carry its whole text as a tooltip: every error, and any line that may not
    /// fit on one row.</summary>
    public static bool NeedsTooltip(string? text, bool isError) =>
        !string.IsNullOrEmpty(text) && (isError || text.Length > OneRowChars);
}
