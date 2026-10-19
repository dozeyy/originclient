using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.UI;

/// <summary>
/// CI screen render (RenderShots): the hover labels. A real tooltip opens in its own popup window, which a
/// picture of the main window does not contain, so each shot builds the SAME ToolTip control the app uses (the
/// global style in Theme/ToolTips.xaml applies to it as it would to any tip), lays it out, draws it and puts that picture in the window's own
/// visual tree where ToolTipDefaults.Place puts the real one, and snaps. The owner's real ToolTip string is
/// what is shown, so a missing or wrong label shows up here. A label that would not fit inside the window
/// throws, which fails the launcher-shots run.
/// </summary>
internal static class TooltipShots
{
    internal static void Run(MainWindow window, string dir, LauncherSettings demoProfiles, Func<ModProfile, int> demoCount)
    {
        // A nav rail item: the label sits beside the rail.
        window.TourGoTo(0);
        RenderShots.Pump(500);
        Shot(window, dir, "30-tooltip-nav", (FrameworkElement)window.FindName("NavMods"));

        // The window's own Close, in the top-right corner: centred under the button, kept inside the window.
        Shot(window, dir, "31-tooltip-close", FindIconButton(window, "Close"));

        // An icon inside a page: the profile card's trash icon.
        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        RenderShots.Pump(700);
        Shot(window, dir, "32-tooltip-in-page", FindIconButton(window, "Delete"));
    }

    private static void Shot(MainWindow window, string dir, string name, FrameworkElement owner)
    {
        var root = (Panel)window.Content;
        var text = owner.ToolTip as string ?? throw new InvalidOperationException($"{name}: the control has no string ToolTip");
        // A ToolTip refuses any parent, so it is laid out on its own and its picture (1:1, 96 dpi like the
        // shot) goes into the window; the style comes from the app resources as it does for a real tip.
        var tip = new ToolTip { Content = text };
        tip.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        var size = tip.DesiredSize;
        tip.Arrange(new Rect(size));
        tip.UpdateLayout();
        var bitmap = new RenderTargetBitmap((int)Math.Ceiling(size.Width), (int)Math.Ceiling(size.Height), 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(tip);
        var picture = new Image { Source = bitmap, Width = size.Width, Height = size.Height, Stretch = Stretch.None, SnapsToDevicePixels = true };
        var overlay = new Canvas { IsHitTestVisible = false };
        Panel.SetZIndex(overlay, int.MaxValue);
        Grid.SetRowSpan(overlay, 99);
        Grid.SetColumnSpan(overlay, 99);
        overlay.Children.Add(picture);
        root.Children.Add(overlay);
        try
        {
            window.UpdateLayout();
            var box = owner.TransformToAncestor(root).TransformBounds(new Rect(owner.RenderSize));
            var wanted = ToolTipService.GetPlacement(owner);
            var (mode, x, y) = ToolTipDefaults.Place(box, size, new Size(root.ActualWidth, root.ActualHeight), wanted);
            // The popup's own anchors (see ToolTipDefaults.Place): Bottom = under the control, Top = above, Right = beside.
            var (left, top) = mode switch
            {
                PlacementMode.Right => (box.Right + x, box.Top + y),
                PlacementMode.Top => (box.Left + x, box.Top - size.Height + y),
                _ => (box.Left + x, box.Bottom + y),
            };
            if (left < 0 || top < 0 || left + size.Width > root.ActualWidth || top + size.Height > root.ActualHeight)
                throw new InvalidOperationException($"{name}: label \"{text}\" at ({left:0},{top:0}) {size.Width:0}x{size.Height:0} sticks out of the {root.ActualWidth:0}x{root.ActualHeight:0} window");
            Canvas.SetLeft(picture, left);
            Canvas.SetTop(picture, top);
            window.UpdateLayout();
            RenderShots.Snap(window, dir, name);
        }
        finally
        {
            root.Children.Remove(overlay);
        }
    }

    /// <summary>The first visible button whose label is <paramref name="label"/> (its tooltip), in tree order.</summary>
    private static FrameworkElement FindIconButton(DependencyObject parent, string label)
    {
        if (parent is ButtonBase { IsVisible: true } button && button.ToolTip as string == label) return button;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
            if (TryFind(VisualTreeHelper.GetChild(parent, i), label) is { } found) return found;
        throw new InvalidOperationException($"no visible button labelled \"{label}\"");
    }

    private static FrameworkElement? TryFind(DependencyObject parent, string label)
    {
        if (parent is ButtonBase { IsVisible: true } button && button.ToolTip as string == label) return button;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
            if (TryFind(VisualTreeHelper.GetChild(parent, i), label) is { } found) return found;
        return null;
    }
}
