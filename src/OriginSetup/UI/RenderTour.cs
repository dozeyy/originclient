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
        setup.TourWelcome(null);              Snap(setup, outDir, "01-welcome");
        setup.TourWelcome("1.0.39");          Snap(setup, outDir, "02-welcome-update");
        setup.TourCustomize(null);            Snap(setup, outDir, "03-customize");
        setup.TourCustomize("Origin can't go in Program Files or Windows — it updates itself, which needs a folder you own.");
                                              Snap(setup, outDir, "04-customize-problem");
        setup.TourInstalling(new InstallProgress(InstallStage.Downloading, 0.42, 53 * mb, 126 * mb, "1.0.40"));
                                              Snap(setup, outDir, "05-downloading");
        setup.TourInstalling(new InstallProgress(InstallStage.Unpacking, 0.88, version: "1.0.40"));
                                              Snap(setup, outDir, "06-unpacking");
        setup.TourDone();                     Snap(setup, outDir, "07-done");
        setup.TourProblem();                  Snap(setup, outDir, "08-problem-offline");
        setup.TourOriginOpen();               Snap(setup, outDir, "09-origin-open");
        setup.Close();

        var uninstall = new UninstallWindow(InstallLocation.Default) { ShowActivated = false };
        uninstall.Show();
                                              Snap(uninstall, outDir, "10-uninstall-confirm");
        uninstall.TourRemoving(0.6, removeData: false); Snap(uninstall, outDir, "11-uninstall-removing");
        uninstall.TourDone(keptData: true);   Snap(uninstall, outDir, "12-uninstall-done");
        uninstall.TourProblem();              Snap(uninstall, outDir, "13-uninstall-problem");
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
