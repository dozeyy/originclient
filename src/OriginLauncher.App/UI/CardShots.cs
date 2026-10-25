using System.Windows;
using System.Windows.Media;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.App.UI;

// Render mode only (UI/SelectorShots.cs): two helpers the version picker's pictures share. The picker no longer has a
// numeral over its card art, so the contrast measurement that lived here went with it; the shots themselves
// (step 1 and step 2 at 960x600, 1180x740 and 1920x1080) are taken by SelectorShots.
internal static class CardShots
{
    // Open the picker (on a family, or on the grid for -1) and settle it at the picture size.
    internal static void Open(MainWindow window, VersionSelectOverlay overlay, int index, Size size, bool inProcess)
    {
        window.TourShowVersions(index);
        RenderShots.Pump(600);
        if (!inProcess) return;
        // The overlay sizes its cards from its own width and height when a layout pass reports them, which a hand
        // layout does not trigger: run the sizing by hand and lay out again, until nothing moves.
        for (var i = 0; i < 3; i++)
        {
            window.TourLayoutAt(size);
            overlay.TourRelayout();
        }
        window.TourLayoutAt(size);
    }

    internal static T? Find<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T hit) return hit;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            if (Find<T>(VisualTreeHelper.GetChild(root, i)) is { } found) return found;
        return null;
    }
}
