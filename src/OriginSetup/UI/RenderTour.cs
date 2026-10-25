using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using OriginSetup.Engine;

namespace OriginSetup.UI;

// CI-only: `OriginLauncher-Setup.exe --render-tour <folder>` renders every
// screen of the installer and uninstaller to PNGs (build-check uploads them
// as the "installer-screens" artifact), so the UI is reviewed as pictures on
// every change — nothing is installed or removed. Rendered at 1.5x for detail.
public static class RenderTour
{
    public static void Run(string outDir, string self)
    {
        Directory.CreateDirectory(outDir);
        PageMotion.Instant = true;

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
        // Let layout, bindings and the first render pass settle.
        window.UpdateLayout();
        window.Dispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);

        const double scale = 1.5;
        var root = (FrameworkElement)window.Content;
        var bitmap = new RenderTargetBitmap((int)(root.ActualWidth * scale), (int)(root.ActualHeight * scale),
                                            96 * scale, 96 * scale, PixelFormats.Pbgra32);
        bitmap.Render(root);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(dir, name + ".png"));
        encoder.Save(file);
    }
}
