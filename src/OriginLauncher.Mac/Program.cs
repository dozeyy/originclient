using Avalonia;
using OriginLauncher.App.Core;
using OriginLauncher.Mac.UI;
using OriginLauncher.Mac.UI.Windows;

namespace OriginLauncher.Mac;

internal static class Program
{
    // None of these switches opens a window:
    //   --self-test [--network]              checks the Mac seams; exit 0 (all passed) or 1.
    //   --render-shots <dir>                 draws every built screen to PNGs headless (made-up data, exits by itself).
    //   --launch-plan <version> [--root dir] prepares a launch without starting the game (LaunchPlan).
    //       [--start-game]                   CI only: then starts it until Minecraft's own code logs, and stops it.
    //   --play-plan <version> [--root dir]   presses the Home Play button headless, stops before the game starts (PlayPlan).
    //   --list-versions                      the versions the picker offers, one per line.
    //   --update-proof <loopback feed> --root dir --expect <outcome>   CI: the self-update against a local fake release (UpdateProof).
    // Anything else opens the launcher window (--signin-probe <dir>, CI only, opens just the sign-in card's window).
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--self-test"))
            return SelfTest.RunAsync(network: args.Contains("--network")).GetAwaiter().GetResult();
        if (args.Contains("--list-versions"))
            return LaunchPlan.ListVersions();
        if (ValueOf(args, "--launch-plan") is { } version)
            return LaunchPlan.RunAsync(version, ValueOf(args, "--root"), args.Contains("--start-game")).GetAwaiter().GetResult();
        if (ValueOf(args, "--play-plan") is { } playVersion)
            return PlayPlan.Run(playVersion, ValueOf(args, "--root"));
        if (ValueOf(args, "--update-proof") is { } feedUrl)
            return UpdateProof.Run(feedUrl, ValueOf(args, "--root"), ValueOf(args, "--expect"));

        if (args.Contains("--render-shots"))
        {
            if (ValueOf(args, "--render-shots") is not { } dir)
            {
                Console.Error.WriteLine("Usage: OriginLauncher --render-shots <dir>");
                return 2;
            }
            return RenderShots.Run(dir);
        }

        // CI only: a small window with the real sign-in card, no account, no launcher (SignInProbe.cs).
        if (args.Contains("--signin-probe"))
        {
            if (ValueOf(args, "--signin-probe") is not { } probeDir)
            {
                Console.Error.WriteLine("Usage: OriginLauncher --signin-probe <dir>");
                return 2;
            }
            SignInProbe.Dir = probeDir;
            return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args, Avalonia.Controls.ShutdownMode.OnExplicitShutdown);
        }

        return StartApp(args);
    }

    private static FileStream? _instanceLock;

    // The real launcher window, with the startup steps the Windows (App.xaml.cs) and Linux (DesktopEntry.RunWindow)
    // launchers take first: one launcher at a time, a leftover dev-only test switch cleared, and crash reporting.
    private static int StartApp(string[] args)
    {
        OriginPaths.EnsureScaffold();
        // One launcher at a time (the Windows launcher's single-instance mutex): an exclusive lock on a file in the data
        // folder, released by the OS when this process ends (a crash included). Finder already brings an open app to the
        // front; this covers a second start from the terminal or an update's relaunch racing the old process.
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

        // A release build never keeps the dev-only offline test switch on (DeveloperAccess).
        if (DeveloperAccess.NeedsReset(OriginLauncher.App.Core.Updates.UpdateService.IsDevBuild, SettingsStore.Load()))
            SettingsStore.Update(DeveloperAccess.ClearOfflineTest);

        // Crashes in the launcher itself: a UI-thread exception is recoverable (reported, the launcher keeps running);
        // an unhandled one on any other thread is fatal (reported, then the runtime ends the app). Unobserved task faults
        // are swallowed, as on Windows and Linux.
        Avalonia.Threading.Dispatcher.UIThread.UnhandledException += (_, e) =>
        {
            MacCrashReporter.Report(e.Exception, fatal: false);
            e.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception ex) MacCrashReporter.Report(ex, fatal: true);
        };
        TaskScheduler.UnobservedTaskException += (_, e) => e.SetObserved();

        return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    // The real app. Also what the Avalonia previewer calls.
    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .LogToTrace();

    private static string? ValueOf(string[] args, string flag)
    {
        var i = Array.IndexOf(args, flag);
        return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
    }
}
