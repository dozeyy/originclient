using Avalonia;
using Avalonia.Media;
using OriginLauncher.App.Core;
using OriginLauncher.Desktop.Shots;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Shell;

// The launcher UI's entry point, called by the host's Main (src/OriginLauncher.Linux/Program.cs).
//   (no args)                  the launcher window
//   --motion-check [--root d]  CI: animations with motion on, real click paths, headless (Shots/MotionCheck.cs)
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
        // CI only: the animations with motion on and the real click paths, headless (Shots/MotionCheck.cs).
        var motion = Array.IndexOf(args, "--motion-check");
        if (motion >= 0)
        {
            var root = Array.IndexOf(args, "--root") is var r and >= 0 && r + 1 < args.Length ? args[r + 1] : null;
            return MotionCheck.Run(root);
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
    private static string? _socketPath;

    /// <summary>Starts answering a second start ("come to the front"); called once the main window has opened.</summary>
    internal static void ListenForSecondStart()
    {
        if (Interlocked.Exchange(ref _socketPath, null) is { } path)
            SingleInstance.Listen(path, () => MainWindow.Current?.BringForward());
    }

    private const string StartFailedTitle = "Origin Launcher couldn't start";

    // The launcher window. Everything from the first line runs inside one catch (the Windows App's "must still reach the
    // crash reporter instead of a launcher that silently never opens"): a failure before the window exists - a data
    // folder that can't be written, no X display, Avalonia failing to start - saves a crash log (the data folder, else
    // the temp folder) and says so in a desktop message box (Core LinuxDesktop.ShowError), never just nothing.
    private static int RunWindow(string[] args)
    {
        try
        {
            return StartWindow(args);
        }
        catch (Exception ex)
        {
            string report;
            try { report = Windows.CrashReporter.BuildReport(ex, fatal: true); }
            catch (Exception) { report = ex.ToString(); }
            var saved = Windows.CrashReporter.TryWriteLog(report) ?? TryWriteTemp(report);
            App.Core.Desktop.LinuxDesktop.ShowError(StartFailedTitle,
                $"Something went wrong while Origin was starting ({ex.GetType().Name}: {ex.Message})."
                + (saved != null ? $" The details are saved in {saved}." : ""));
            return 1;
        }
    }

    private static string? TryWriteTemp(string report)
    {
        try
        {
            var path = Path.Combine(Path.GetTempPath(), $"origin-launcher-crash_{DateTime.Now:yyyyMMdd_HHmmss}.log");
            File.WriteAllText(path, report);
            return path;
        }
        catch (Exception) { return null; }
    }

    private static int StartWindow(string[] args)
    {
        StartupTrace.Init(args);
        // The launcher is an X11 app, on Wayland desktops through XWayland (started by GNOME, KDE and the other
        // mainstream Wayland desktops on their own). Kept on X11 on purpose, after checking Avalonia 12.1.5's own
        // Wayland backend (Avalonia.Wayland, new in 12.1.0): the in-launcher Microsoft sign-in page (Avalonia's
        // NativeWebView on Linux, GtkX11WebViewAdapter) embeds WebKitGTK through GDK's X11 backend only, and under
        // Avalonia.Wayland it has nothing but an "ExperimentalOffscreen" adapter, so going native would cost the sign-in
        // page its stable path; the Wayland backend is also two months old, and a chromeless window that moves itself
        // (centring, the bar's drag) is exactly what Wayland gives clients least control over. The cost of X11:
        // fractional scaling on a Wayland desktop is XWayland's (it can look softer), and a session without XWayland
        // can't open the window, which is said in plain words here instead of failing silently.
        if (App.Core.Desktop.WindowSession.WindowBlocker(Environment.GetEnvironmentVariable("DISPLAY"),
                Environment.GetEnvironmentVariable("WAYLAND_DISPLAY")) is { } blocker)
        {
            App.Core.Desktop.LinuxDesktop.ShowError(StartFailedTitle, blocker);
            return 1;
        }

        OriginPaths.EnsureScaffold();
        // One launcher at a time (the Windows launcher's single-instance mutex): an exclusive lock on a file in the
        // data folder. A second start asks the open one to come to the front (Shell/SingleInstance) and exits.
        var socketPath = SingleInstance.SocketPath(OriginPaths.Root, Environment.GetEnvironmentVariable("XDG_RUNTIME_DIR"));
        try
        {
            _instanceLock = new FileStream(Path.Combine(OriginPaths.Root, ".launcher.lock"), FileMode.OpenOrCreate,
                FileAccess.ReadWrite, FileShare.None);
        }
        catch (IOException)
        {
            Console.Error.WriteLine(SingleInstance.TrySignal(socketPath)
                ? "Origin is already open: brought it to the front."
                : "Origin is already open.");
            return 0;
        }
        // Listened on only once the window is open (MainWindow.Opened -> ListenForSecondStart): the listener thread posts to
        // the UI dispatcher, which must not be touched before Avalonia has started on this thread. A second start in
        // between finds no socket and just reports "already open".
        _socketPath = socketPath;

        // Linux's "animations off" (GNOME-family gsettings, KDE's instant animation speed), the twin of Windows'
        // "Show animations" check in the WPF App: every Motion duration becomes 0 before any view is built.
        if (App.Core.Desktop.LinuxDesktop.ReducedMotion()) Motion.Off = true;

        // "Open Origin when you log in" follows the launcher when the .AppImage is moved or renamed: the autostart entry
        // is re-pointed at what runs now (UPG-5). A dev build never touches the player's real autostart entry.
        if (!App.Core.Updates.UpdateService.IsDevBuild && Environment.ProcessPath is { } exe)
            App.Core.Install.InstallRegistry.RefreshStartAtLogin(exe);

        if (DeveloperAccess.NeedsReset(App.Core.Updates.UpdateService.IsDevBuild, SettingsStore.Load()))
        {
            // settings.json busy right now (antivirus, a sync tool): SettingsStore refuses instead of guessing. Never
            // fatal at startup; a file that can't be read loads as defaults (offline test off) anyway, and the
            // next start clears it.
            try { SettingsStore.Update(DeveloperAccess.ClearOfflineTest); }
            catch (Exception ex) when (ex is System.IO.IOException or UnauthorizedAccessException) { }
        }
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

        // WM_CLASS = the desktop entry's id (tools/linux/files/org.originclient.Launcher.desktop, StartupWMClass), so
        // GNOME/KDE docks match the running window to Origin's pinned icon instead of showing an unknown second app.
        return Builder()
            .With(new X11PlatformOptions { WmClass = WmClass })
            .UsePlatformDetect()
            .StartWithClassicDesktopLifetime(args);
    }

    /// <summary>The X11 window class; tools/release/check_consistency.py keeps the .desktop file's StartupWMClass equal.</summary>
    public const string WmClass = "org.originclient.Launcher";
}
