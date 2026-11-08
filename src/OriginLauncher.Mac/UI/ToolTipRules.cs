using Avalonia;
using Avalonia.Controls;
using Avalonia.Data;
using Avalonia.Controls.Primitives.PopupPositioning;
using OriginLauncher.App.UI;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// How every hover label behaves, the Windows launcher's rules (src/OriginLauncher.App/UI/ToolTipDefaults.cs); how it
/// LOOKS is the ToolTip theme in Theme/Surfaces.axaml. Hooked once, before the first window
/// exists, and worn by every control that gets a label (the twin of WPF's OverrideMetadata):
///   - opens after 300 ms (Avalonia's own 400 ms felt slow on the bar's icons), and for 700 ms after one label closes
///     the next opens at once, so sliding across the bar's icons reads each label without waiting again;
///   - below the control, centred on it, flipped above when the window ends first and slid sideways so it stays
///     inside the window (UI/TipPlacement, the numbers Windows uses), never at the pointer as Avalonia places it.
/// A control that asks for its own Placement keeps it.
/// </summary>
internal static class ToolTipRules
{
    private static bool _applied;

    internal static void Apply()
    {
        if (_applied) return;
        _applied = true;
        // The attached properties' defaults belong to Control itself (Avalonia refuses a second default there), so the
        // rules go on each control the moment it gets a label, at style priority: a value the control sets itself wins.
        ToolTip.TipProperty.Changed.AddClassHandler<Control>((control, e) =>
        {
            if (e.NewValue != null) Wear(control);
        });
    }

    private static void Wear(Control c)
    {
        c.SetValue(ToolTip.ShowDelayProperty, 300, BindingPriority.Style);
        c.SetValue(ToolTip.BetweenShowDelayProperty, 700, BindingPriority.Style);
        c.SetValue(ToolTip.PlacementProperty, PlacementMode.Custom, BindingPriority.Style);
        c.SetValue(ToolTip.VerticalOffsetProperty, 0d, BindingPriority.Style);
        c.SetValue(ToolTip.HorizontalOffsetProperty, 0d, BindingPriority.Style);
        c.SetValue(ToolTip.CustomPopupPlacementCallbackProperty, Place, BindingPriority.Style);
    }

    // The label's top-left (or bottom-left, above) against the control's, from the shared rule. Anything unexpected
    // (no window yet, a detached control) falls back to centred below, which Avalonia keeps on screen.
    private static void Place(CustomPopupPlacement p)
    {
        var size = p.Target.Bounds.Size;
        p.AnchorRectangle = new Rect(size);
        p.Anchor = PopupAnchor.TopLeft;
        p.Gravity = PopupGravity.BottomRight;
        p.ConstraintAdjustment = PopupPositionerConstraintAdjustment.None;
        try
        {
            if (TopLevel.GetTopLevel(p.Target) is { } top && p.Target.TranslatePoint(default, top) is { } at)
            {
                var (side, x, y) = TipPlacement.Place(new TipPlacement.Box(at.X, at.Y, size.Width, size.Height),
                    p.PopupSize.Width, p.PopupSize.Height, top.Bounds.Width, top.Bounds.Height);
                p.Offset = side == TipPlacement.Side.Above
                    ? new Point(x, -p.PopupSize.Height + y)
                    : new Point(x, size.Height + y);
                return;
            }
        }
        catch (Exception ex)
        {
            // A label is never worth an exception out of a hover.
            System.Diagnostics.Debug.WriteLine("ToolTipRules.Place: " + ex.Message);
        }
        p.Offset = new Point((size.Width - p.PopupSize.Width) / 2, size.Height + TipPlacement.Gap);
        p.ConstraintAdjustment = PopupPositionerConstraintAdjustment.SlideX | PopupPositionerConstraintAdjustment.FlipY;
    }
}
