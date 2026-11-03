using Avalonia;
using Avalonia.Media;
using OriginLauncher.App.Core;
using OriginLauncher.Desktop.Shots;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Shell;

// The launcher UI's entry point, called by the host's Main (src/OriginLauncher.Linux/Program.cs).
//   (no args)                  the launcher window
//   --render-shots <dir>       every screen and state as PNGs, headless (no window, no display server),
//                              made-up data in a temp scratch root, no network, exits by itself
public static class DesktopEntry
{
    public static int Run(string[] args)
    {
        var render = Array.IndexOf(args, "--render-shots");
        if (render >= 0)
        {
            if (render + 1 >= args.Length)
            {
                Console.Error.WriteLine("usage: --render-shots <dir>");
                return 2;
            }
            return ShotRunner.Run(Path.GetFullPath(args[render + 1]), args);
        }
        return RunWindow(args);
    }

    private static FontManagerOptions Fonts => new()
    {
        DefaultFamilyName = Assets.Uri("Assets/Fonts").ToString() + "#Inter",
        FontFallbacks = [new FontFallback { FontFamily = new FontFamily("DejaVu Sans") }],
    };

    public static AppBuilder Builder() => AppBuilder.Configure<DesktopApp>().With(Fonts);

    private static FileStream? _instanceLock;

    private static int RunWindow(string[] args)
    {
        StartupTrace.Init(args);
        OriginPaths.EnsureScaffold();
        // One launcher at a time (the Windows launcher's single-instance mutex): an exclusive lock on a file in the
        // data folder. A second start just exits; the first window stays.
        try
        {
            _instanceLock = new FileStream(Path.Combine(OriginPaths.Root, ".launcher.lock"), FileMode.OpenOrCreate,
                FileAccess.ReadWrite, FileShare.None);
        }
        catch (IOException)
        {
            Console.Error.WriteLine("Origin is already open.");
            return 0;
        }

        if (DeveloperAccess.NeedsReset(App.Core.Updates.UpdateService.IsDevBuild, SettingsStore.Load()))
            SettingsStore.Update(DeveloperAccess.ClearOfflineTest);
        try { App.Core.Import.Launcher.ImportStartup.StartRecovery(); }
        catch (Exception ex) { StartupTrace.Mark("import recovery failed: " + ex.Message); }
        App.Core.LogRetention.StartPrune(); // old per-launch game logs, launcher errors and crash logs
        App.Core.GameFolders.Launcher.AccountFolders.EnsureMigrated(App.Core.GameFolders.Launcher.AccountFolders.SelectedKey());
        TaskScheduler.UnobservedTaskException += (_, e) => e.SetObserved();

        DesktopApp.CreateWindow = () => new MainWindow();
        // Crashes in the launcher itself (Windows/CrashReporter): a UI-thread exception is recoverable (reported,
        // the launcher keeps running); an unhandled one anywhere else is fatal. Unobserved task faults are swallowed.
        Avalonia.Threading.Dispatcher.UIThread.UnhandledException += (_, e) =>
        {
            Windows.CrashReporter.Report(e.Exception, fatal: false);
            e.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception ex) Windows.CrashReporter.Report(ex, fatal: true);
        };
        System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (_, e) => e.SetObserved();

        return Builder().UsePlatformDetect().StartWithClassicDesktopLifetime(args);
    }
}
