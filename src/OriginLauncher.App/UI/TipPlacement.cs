namespace OriginLauncher.App.UI;

/// <summary>
/// Where a hover label goes, on every launcher (WPF UI/ToolTipDefaults, the Mac's UI/ToolTipRules): below the control
/// and centred on it, flipped above when the window ends first, slid sideways so it never hangs past the window's
/// edge (the windows are chromeless on Windows; a label sticking out of any launcher window reads as a stray popup).
/// A control that asks for Right gets the label beside it, centred, when it fits. Pure (doubles only, no UI framework),
/// so OriginLauncher.Tests covers it and every launcher places labels by the same numbers.
/// </summary>
internal static class TipPlacement
{
    /// <summary>Space between the control and its label.</summary>
    internal const double Gap = 8;

    /// <summary>The least space a label keeps to the window's edge.</summary>
    internal const double EdgeMargin = 6;

    internal enum Side { Below, Above, Right }

    /// <summary>A control's box in window coordinates.</summary>
    internal readonly record struct Box(double Left, double Top, double Width, double Height)
    {
        public double Right => Left + Width;
        public double Bottom => Top + Height;
    }

    /// <summary>
    /// The side, and the label's offset from that side's anchor: Below anchors the label's top-left at the control's
    /// bottom-left, Above anchors the label's bottom-left at the control's top-left, Right anchors its top-left at the
    /// control's top-right.
    /// </summary>
    internal static (Side Side, double X, double Y) Place(Box control, double tipWidth, double tipHeight,
        double windowWidth, double windowHeight, bool wantRight = false)
    {
        if (wantRight && control.Right + Gap + tipWidth <= windowWidth - EdgeMargin)
        {
            var top = Math.Clamp(control.Top + (control.Height - tipHeight) / 2, EdgeMargin,
                Math.Max(EdgeMargin, windowHeight - tipHeight - EdgeMargin));
            return (Side.Right, Gap, top - control.Top);
        }

        var above = control.Bottom + Gap + tipHeight > windowHeight - EdgeMargin && control.Top - Gap - tipHeight >= EdgeMargin;
        var left = Math.Clamp(control.Left + (control.Width - tipWidth) / 2, EdgeMargin,
            Math.Max(EdgeMargin, windowWidth - tipWidth - EdgeMargin));
        return above ? (Side.Above, left - control.Left, -Gap) : (Side.Below, left - control.Left, Gap);
    }
}
