using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;

namespace OriginLauncher.App.UI;

/// <summary>
/// How every hover label behaves; how it LOOKS is the ToolTip style in Theme/ToolTips.xaml (tokens only).
/// ToolTipService settings are read from the control that owns the tip, so they cannot live in that style: they
/// are set once, as the defaults of every element, before the first window exists.
///
///   - Opens after 400 ms (the stock 500 ms feels slow on an icon rail) and stays up long enough to read.
///   - BetweenShowDelay: for 700 ms after one label closes, the next opens at once, so sliding across the
///     nav rail or the top-right icons reads each label without waiting again.
///   - Placement is below the control (a nav item asks for Right with ToolTipService.Placement), centred on
///     it and kept inside the launcher window, flipping above when there is no room below. The window is
///     chromeless, so a label that stuck out past its edge would hang in mid-air.
/// A control that wants no hover label simply has no ToolTip; one that already shows its words (a text
/// button) has none either. Icon-only controls always carry the label AND the same AutomationProperties.Name.
/// </summary>
internal static class ToolTipDefaults
{
    internal const int OpenDelayMs = 400;
    internal const int BetweenShowMs = 700;
    internal const int ShowDurationMs = 8000;
    /// <summary>Space between the control and its label, and the least space the label keeps to the window edge.</summary>
    internal const double Gap = 6;
    internal const double EdgeMargin = 6;

    private static bool _applied;

    internal static void Apply()
    {
        if (_applied) return; // OverrideMetadata throws if repeated
        _applied = true;
        ToolTipService.InitialShowDelayProperty.OverrideMetadata(typeof(DependencyObject), new FrameworkPropertyMetadata(OpenDelayMs));
        ToolTipService.BetweenShowDelayProperty.OverrideMetadata(typeof(DependencyObject), new FrameworkPropertyMetadata(BetweenShowMs));
        ToolTipService.ShowDurationProperty.OverrideMetadata(typeof(DependencyObject), new FrameworkPropertyMetadata(ShowDurationMs));
        ToolTipService.PlacementProperty.OverrideMetadata(typeof(DependencyObject), new FrameworkPropertyMetadata(PlacementMode.Bottom));
        EventManager.RegisterClassHandler(typeof(FrameworkElement), ToolTipService.ToolTipOpeningEvent, new ToolTipEventHandler(OnToolTipOpening));
    }

    /// <summary>
    /// Just BEFORE the label opens: a plain string tip becomes a ToolTip that asks for its position through a
    /// placement callback, so the popup is put in the right place the first time it appears (nothing is moved
    /// after it shows). Only the two placements this app defines are taken over: Bottom (the default) and
    /// Right. A control that asked for anything else (Top, Left, Mouse, Custom...), or whose ToolTip is already
    /// a ToolTip object, keeps exactly what it asked for. SetCurrentValue keeps a bound ToolTip ("{Binding
    /// JoinTip}") bound: the binding's next value is a string again and is wrapped on its next open.
    /// </summary>
    private static void OnToolTipOpening(object sender, ToolTipEventArgs e)
    {
        try
        {
            if (sender is not FrameworkElement owner || !ReferenceEquals(e.OriginalSource, owner)) return;
            if (owner.ToolTip is not string { Length: > 0 } text) return;
            var wanted = ToolTipService.GetPlacement(owner);
            if (wanted is not (PlacementMode.Bottom or PlacementMode.Right)) return;
            owner.SetCurrentValue(FrameworkElement.ToolTipProperty, new ToolTip
            {
                Content = text,
                PlacementTarget = owner,
                Placement = PlacementMode.Custom,
                CustomPopupPlacementCallback = (popup, target, _) => [PlaceNear(owner, popup, target, wanted)],
            });
        }
        catch (Exception ex)
        {
            // A label is never worth an exception out of a hover: leave the plain string tip as it was.
            System.Diagnostics.Debug.WriteLine("ToolTipDefaults.OnToolTipOpening: " + ex.Message);
        }
    }

    /// <summary>The popup's top-left, relative to the control's top-left (what a custom placement returns).</summary>
    private static CustomPopupPlacement PlaceNear(FrameworkElement owner, Size popup, Size target, PlacementMode wanted)
    {
        try
        {
            // Visual descendants of the window's content only: an adorner or a detached element has no box in it.
            if (Window.GetWindow(owner) is { Content: FrameworkElement root } && owner.IsDescendantOf(root))
            {
                var box = owner.TransformToAncestor(root).TransformBounds(new Rect(owner.RenderSize));
                var (mode, x, y) = Place(box, popup, new Size(root.ActualWidth, root.ActualHeight), wanted);
                var point = mode switch
                {
                    PlacementMode.Right => new Point(target.Width + x, y),
                    PlacementMode.Top => new Point(x, -popup.Height + y),
                    _ => new Point(x, target.Height + y),
                };
                return new CustomPopupPlacement(point, PopupPrimaryAxis.None);
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine("ToolTipDefaults.PlaceNear: " + ex.Message);
        }
        // No window to stay in (a menu, a drop-down) or something went wrong: centred below, WPF keeps it on screen.
        return new CustomPopupPlacement(new Point((target.Width - popup.Width) / 2, target.Height + Gap), PopupPrimaryAxis.None);
    }

    /// <summary>
    /// Where a label of <paramref name="tip"/> size goes for a control at <paramref name="box"/> in a window of
    /// <paramref name="window"/> size: the placement mode and the offsets from that mode's own anchor (Bottom
    /// anchors the label's left edge at the control's left edge, Right its top edge at the control's top).
    /// Pure (no window needed), but not unit-tested: the tests project has no WPF, so the placement is proven by the tooltip shots in launcher-shots.
    /// </summary>
    internal static (PlacementMode Mode, double X, double Y) Place(Rect box, Size tip, Size window, PlacementMode wanted)
    {
        if (wanted == PlacementMode.Right && box.Right + Gap + tip.Width <= window.Width - EdgeMargin)
        {
            // Beside the control, centred on it.
            var top = Math.Clamp(box.Top + (box.Height - tip.Height) / 2, EdgeMargin, Math.Max(EdgeMargin, window.Height - tip.Height - EdgeMargin));
            return (PlacementMode.Right, Gap, top - box.Top);
        }

        // Below the control, or above when the window ends first.
        var above = box.Bottom + Gap + tip.Height > window.Height - EdgeMargin && box.Top - Gap - tip.Height >= EdgeMargin;
        var left = Math.Clamp(box.Left + (box.Width - tip.Width) / 2, EdgeMargin, Math.Max(EdgeMargin, window.Width - tip.Width - EdgeMargin));
        return above
            ? (PlacementMode.Top, left - box.Left, -Gap)
            : (PlacementMode.Bottom, left - box.Left, Gap);
    }
}
