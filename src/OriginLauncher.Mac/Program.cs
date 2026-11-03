using Avalonia;
using OriginLauncher.Mac.UI;

namespace OriginLauncher.Mac;

internal static class Program
{
    // None of these switches opens a window:
    //   --self-test [--network]              checks the Mac seams; exit 0 (all passed) or 1.
    //   --render-shots <dir>                 draws every built screen to PNGs headless (made-up data, exits by itself).
    //   --launch-plan <version> [--root dir] prepares a launch without starting the game (LaunchPlan).
    //   --play-plan <version> [--root dir]   presses the Home Play button headless, stops before the game starts (PlayPlan).
    //   --list-versions                      the versions the picker offers, one per line.
    // Anything else opens the launcher window.
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--self-test"))
            return SelfTest.RunAsync(network: args.Contains("--network")).GetAwaiter().GetResult();
        if (args.Contains("--list-versions"))
            return LaunchPlan.ListVersions();
        if (ValueOf(args, "--launch-plan") is { } version)
            return LaunchPlan.RunAsync(version, ValueOf(args, "--root")).GetAwaiter().GetResult();
        if (ValueOf(args, "--play-plan") is { } playVersion)
            return PlayPlan.Run(playVersion, ValueOf(args, "--root"));

        if (args.Contains("--render-shots"))
        {
            if (ValueOf(args, "--render-shots") is not { } dir)
            {
                Console.Error.WriteLine("Usage: OriginLauncher --render-shots <dir>");
                return 2;
            }
            return RenderShots.Run(dir);
        }

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
