using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using OriginSetup.Engine;

namespace OriginSetup.UI;

// CI-only: `OriginLauncher-Setup.exe --render-tour <folder>` renders every
// screen of the installer and uninstaller to PNGs (build-check uploads them
// as the "installer-screens" artifact), so the UI is reviewed as pictures on
// every change — nothing is installed or removed.
public static class RenderTour
{
    // Rendered at three display scales: the 150% pass in <dir> (the detail pass build-check uploads), then 100% in
    // <dir>/100pct and 200% in <dir>/200pct. Each pass gives the window's tree that DPI (Snap), so layout rounding and
    // text are laid out as on a monitor at that scale, not a 100% layout scaled up.
    private static double _scale = 1.5;

    public static void Run(string outDir, string self)
    {
        PageMotion.Instant = true;
        foreach (var (scale, sub) in new[] { (1.5, ""), (1.0, "100pct"), (2.0, "200pct") })
        {
            _scale = scale;
            Tour(sub.Length == 0 ? outDir : Path.Combine(outDir, sub), self);
        }
    }

    private static void Tour(string outDir, string self)
    {
        Directory.CreateDirectory(outDir);

        // A stand-in release: the tour never touches the network.
        const long mb = 1048576;
        var setup = new SetupWindow(self, sourceZip: null, lookUpRelease: false) { ShowActivated = false };
        setup.TourRelease(new LauncherRelease("1.0.40", "https://example.invalid/OriginLauncher-win-x64.zip", 126 * mb));
        setup.Show();
        setup.TourReady(null);                 Snap(setup, outDir, "01-ready-install");
        setup.TourKeyboardFocus(true);         Snap(setup, outDir, "02-ready-keyboard-focus");
        setup.TourKeyboardFocus(false);
        setup.TourReady("1.0.39");             Snap(setup, outDir, "03-ready-update");
        setup.TourReady("1.0.40");             Snap(setup, outDir, "04-ready-reinstall");
        setup.TourReady(null, @"D:\Games\Minecraft\Launchers\Origin Launcher\A Very Long Folder Name For A Player Who Likes Order\OriginLauncher");
                                               Snap(setup, outDir, "05-ready-long-path");
        setup.TourEditingPath(@"D:\Games\Minecraft\Launchers\Origin Launcher\A Very Long Folder Name For A Player Who Likes Order\OriginLauncher");
                                               Snap(setup, outDir, "21-ready-editing-path");
        setup.TourLocationProblem("Origin can't go in Program Files or Windows. It updates itself, which needs a folder you own.");
                                               Snap(setup, outDir, "06-ready-folder-problem");
        setup.TourInstalling(new InstallProgress(InstallStage.Downloading, 0.42, 53 * mb, 126 * mb, "1.0.40"), "Installing Origin");
                                               Snap(setup, outDir, "07-installing-download");
        setup.TourInstalling(new InstallProgress(InstallStage.Unpacking, 0.88, version: "1.0.40"), "Updating Origin");
                                               Snap(setup, outDir, "08-installing-unpack");
        setup.TourDone("Origin is installed", "Installed, version 1.0.40");
                                               Snap(setup, outDir, "09-done");
        setup.TourProblem();                   Snap(setup, outDir, "10-problem-offline");
        setup.TourOriginOpen();                Snap(setup, outDir, "11-origin-open");
        // Customize: closed is shot 01 (the footer's Customize button); open at its defaults, open with memory and
        // version changed (Use recommended turns on), and open with the keyboard ring on the memory slider.
        setup.TourCustomize(changed: false);   Snap(setup, outDir, "18-ready-customize-open");
        setup.TourCustomize(changed: true);    Snap(setup, outDir, "19-ready-customize-changed");
        setup.TourCustomize(changed: false, keyboardFocusOnSlider: true);
                                               Snap(setup, outDir, "20-ready-customize-keyboard-focus");
        setup.Close();

        var uninstall = new UninstallWindow(InstallLocation.Default) { ShowActivated = false };
        uninstall.Show();
        uninstall.TourConfirm(removeData: false, keyboardFocus: true);
                                               Snap(uninstall, outDir, "12-uninstall-confirm");
        uninstall.TourConfirm(removeData: true, keyboardFocus: false);
                                               Snap(uninstall, outDir, "13-uninstall-confirm-delete-data");
        uninstall.TourRemoving(0.6, removeData: false);
                                               Snap(uninstall, outDir, "14-uninstall-removing");
        uninstall.TourDone(keptData: true);    Snap(uninstall, outDir, "15-uninstall-done-kept");
        uninstall.TourDone(keptData: false);   Snap(uninstall, outDir, "16-uninstall-done-deleted");
        uninstall.TourProblem();               Snap(uninstall, outDir, "17-uninstall-problem");
        uninstall.Close();
    }

    private static void Snap(Window window, string dir, string name)
    {
        double scale = _scale;
        // The pass's DPI on the whole tree (what per-monitor DPI does on a scaled monitor), re-measured so the new
        // pixel-grid rounding applies; then let layout, bindings and the first render pass settle.
        if (Math.Abs(VisualTreeHelper.GetDpi(window).DpiScaleX - scale) > 0.001)
        {
            VisualTreeHelper.SetRootDpi(window, new DpiScale(scale, scale));
            Invalidate(window);
        }
        window.UpdateLayout();
        window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);

        var root = (FrameworkElement)window.Content;
        var bitmap = new RenderTargetBitmap((int)(root.ActualWidth * scale), (int)(root.ActualHeight * scale),
                                            96 * scale, 96 * scale, PixelFormats.Pbgra32);
        bitmap.Render(root);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(dir, name + ".png"));
        encoder.Save(file);
    }

    private static void Invalidate(DependencyObject d)
    {
        if (d is UIElement e)
        {
            e.InvalidateMeasure();
            e.InvalidateArrange();
            e.InvalidateVisual();
        }
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(d); i++) Invalidate(VisualTreeHelper.GetChild(d, i));
    }
}
