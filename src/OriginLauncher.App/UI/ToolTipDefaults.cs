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
        EventManager.RegisterClassHandler(typeof(ToolTip), ToolTip.OpenedEvent, new RoutedEventHandler(OnOpened));
    }

    private static void OnOpened(object sender, RoutedEventArgs e)
    {
        if (sender is not ToolTip tip) return;
        var owner = tip.PlacementTarget as FrameworkElement ?? tip.Parent as FrameworkElement;
        if (owner == null) return;
        var wanted = ToolTipService.GetPlacement(owner);
        if (wanted == PlacementMode.Mouse) return; // asked for explicitly: leave it where WPF puts it
        // Inside a popup (a menu, a drop-down) there is no window to stay in; the screen edge is handled by WPF.
        if (Window.GetWindow(owner) is not { Content: FrameworkElement root }) return;

        var box = owner.TransformToAncestor(root).TransformBounds(new Rect(owner.RenderSize));
        tip.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        var size = tip.DesiredSize;
        var (placement, x, y) = Place(box, size, new Size(root.ActualWidth, root.ActualHeight), wanted);
        tip.Placement = placement;
        tip.HorizontalOffset = x;
        tip.VerticalOffset = y;
    }

    /// <summary>
    /// Where a label of <paramref name="tip"/> size goes for a control at <paramref name="box"/> in a window of
    /// <paramref name="window"/> size: the placement mode and the offsets from that mode's own anchor (Bottom
    /// anchors the label's left edge at the control's left edge, Right its top edge at the control's top).
    /// Pure, so the tests check the clamping without a window.
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
