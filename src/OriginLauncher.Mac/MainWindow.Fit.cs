using Avalonia;
using Avalonia.Controls;
using OriginLauncher.App.Core;
using OriginLauncher.App.UI;

namespace OriginLauncher.Mac;

// Where the window opens, and what follows a move to a screen of another scale (twins of the WPF MainWindow.Placement
// and its OnDpiChanged handlers).
public sealed partial class MainWindow
{
    // The designed size and minimum (MainWindow.axaml), the numbers WindowFit shrinks.
    private const double DesignWidth = 1180, DesignHeight = 740, DesignMinWidth = 960, DesignMinHeight = 600;

    private void HookFit()
    {
        // A real start fits the window to the screen it opens on before it is shown (CenterScreen then centres the
        // fitted box). The headless runs (render shots, motion check, plans) size their own windows.
        try
        {
            if (!OriginPaths.IsScratchRoot && (Screens.ScreenFromWindow(this) ?? Screens.Primary) is { } screen)
                FitTo(screen.WorkingArea, screen.Scaling);
        }
        catch (Exception ex)
        {
            // No screen to ask (a display waking up): the designed size and CenterScreen stand, as before.
            System.Diagnostics.Debug.WriteLine("MainWindow fit skipped: " + ex.Message);
        }
        // Dragged to a Retina or a non-Retina screen: the backdrop picks the art for the new pixel density and the
        // picker re-snaps its cards, as on a resize (macOS keeps the size in points, so no resize comes).
        ScalingChanged += (_, _) =>
        {
            Stage.RefitForScale();
            Home.RefitForScale();
        };
    }

    /// <summary>
    /// The designed 1180 x 740 shrunk to <paramref name="workingArea"/> (the screen without the menu bar and the Dock:
    /// NSScreen.visibleFrame), the minimum giving way when the screen is smaller still (UI/WindowFit, the same rule and
    /// tests as Windows). A MacBook with "Larger Text" (1280 x 800 or 1024 x 640 points) or a Dock at the bottom has less
    /// than 740 points free: the Play strip ran under the Dock and the window could be wider than the screen.
    /// <paramref name="scaling"/> turns the working area into window units (1 on a Mac: Avalonia reports points there).
    /// </summary>
    internal void FitTo(PixelRect workingArea, double scaling)
    {
        if (!(scaling > 0)) return;
        var work = new WindowFit.Box(workingArea.X / scaling, workingArea.Y / scaling, workingArea.Width / scaling, workingArea.Height / scaling);
        if (WindowFit.Centre(work, DesignWidth, DesignHeight, DesignMinWidth, DesignMinHeight) is not { } fit) return;
        MinWidth = fit.MinWidth;
        MinHeight = fit.MinHeight;
        Width = fit.Window.Width;
        Height = fit.Window.Height;
    }
}
