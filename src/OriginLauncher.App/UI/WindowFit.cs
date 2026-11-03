namespace OriginLauncher.App.UI;

/// <summary>
/// Where the launcher window opens: its designed size (1180 x 740), shrunk to fit the monitor's work area (the
/// screen minus the taskbar) and centred in it. WPF's CenterScreen never shrinks a window, so on a 1920 x 1080 laptop
/// at 150 % (a work area of about 1280 x 672 DIP) the 740-tall window hung ~34 DIP above the screen (window buttons
/// and tabs out of reach) and its Play strip ran under the taskbar. The window's minimum size gives way too when the
/// work area is smaller than it (1366 x 768 at 125 %: 584 tall against a 600 minimum), because a window the player can
/// see whole beats a layout 16 px roomier with Play under the taskbar. All values are DIPs. Pure, so
/// OriginLauncher.Tests covers it; MainWindow.Placement.cs reads the work area and applies the result.
/// </summary>
internal static class WindowFit
{
    internal readonly record struct Box(double Left, double Top, double Width, double Height);

    internal readonly record struct Result(Box Window, double MinWidth, double MinHeight);

    /// <summary>The window's opening box inside <paramref name="work"/>, or null when the work area is unusable (keep WPF's own placement).</summary>
    internal static Result? Centre(Box work, double width, double height, double minWidth, double minHeight)
    {
        if (!(work.Width > 0) || !(work.Height > 0) || double.IsInfinity(work.Width) || double.IsInfinity(work.Height))
            return null;

        var minW = Math.Min(minWidth, work.Width);
        var minH = Math.Min(minHeight, work.Height);
        var w = Math.Max(minW, Math.Min(width, work.Width));
        var h = Math.Max(minH, Math.Min(height, work.Height));
        // Whole DIPs, so the centred window never sits on a half pixel at 100 %.
        var left = Math.Floor(work.Left + (work.Width - w) / 2);
        var top = Math.Floor(work.Top + (work.Height - h) / 2);
        return new Result(new Box(left, top, w, h), minW, minH);
    }

    /// <summary>
    /// Where the window goes when maximised (Aero snap to the top edge, Win+Up): the work area of the monitor it is
    /// on. The window is chromeless (WindowStyle None + WindowChrome), so Windows' default maximised box is the whole
    /// monitor plus the resize border: it covered the taskbar and spilled a few pixels past every edge. Answers
    /// WM_GETMINMAXINFO, whose ptMaxPosition is relative to the monitor's own top-left (not the desktop) and whose
    /// values are device pixels, so <paramref name="monitor"/> and <paramref name="work"/> are the raw GetMonitorInfo
    /// rectangles. Null when they're unusable (Windows' own default stands).
    /// </summary>
    internal static Box? Maximised(Box monitor, Box work)
    {
        if (!(monitor.Width > 0) || !(monitor.Height > 0) || !(work.Width > 0) || !(work.Height > 0))
            return null;
        return new Box(work.Left - monitor.Left, work.Top - monitor.Top, work.Width, work.Height);
    }
}
