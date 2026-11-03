namespace OriginLauncher.Mac;

internal static class Program
{
    // No UI yet. `--self-test [--network]` checks the Mac seams and exits 0 (all passed) or 1;
    // anything else prints the usage and exits 2. It never opens a window.
    private static async Task<int> Main(string[] args)
    {
        if (args.Contains("--self-test"))
            return await SelfTest.RunAsync(network: args.Contains("--network")).ConfigureAwait(false);

        Console.Error.WriteLine("Origin Launcher for macOS: the window is not built yet.");
        Console.Error.WriteLine("Usage: OriginLauncher --self-test [--network]");
        return 2;
    }
}
