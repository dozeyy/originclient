using Avalonia;
using OriginLauncher.Mac.UI;

namespace OriginLauncher.Mac;

internal static class Program
{
    // `--self-test [--network]` checks the Mac seams and exits 0 (all passed) or 1, without a window.
    // `--render-shots <dir>` draws every built screen to PNGs headless (made-up data, no window, exits by itself).
    // Anything else opens the launcher window.
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--self-test"))
            return SelfTest.RunAsync(network: args.Contains("--network")).GetAwaiter().GetResult();

        var shots = Array.IndexOf(args, "--render-shots");
        if (shots >= 0)
        {
            if (shots + 1 >= args.Length)
            {
                Console.Error.WriteLine("Usage: OriginLauncher --render-shots <dir>");
                return 2;
            }
            return RenderShots.Run(args[shots + 1]);
        }

        return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
    }

    // The real app. Also what the Avalonia previewer calls.
    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .LogToTrace();
}
