namespace OriginLauncher.Mac;

internal static class Program
{
    // No UI yet. None of the switches opens a window:
    //   --self-test [--network]              checks the Mac seams; exit 0 (all passed) or 1.
    //   --launch-plan <version> [--root dir] prepares a launch without starting the game (LaunchPlan).
    //   --list-versions                      the versions the picker offers, one per line.
    // Anything else prints the usage and exits 2.
    private static async Task<int> Main(string[] args)
    {
        if (args.Contains("--self-test"))
            return await SelfTest.RunAsync(network: args.Contains("--network")).ConfigureAwait(false);
        if (args.Contains("--list-versions"))
            return LaunchPlan.ListVersions();
        if (ValueOf(args, "--launch-plan") is { } version)
            return await LaunchPlan.RunAsync(version, ValueOf(args, "--root")).ConfigureAwait(false);

        Console.Error.WriteLine("Origin Launcher for macOS: the window is not built yet.");
        Console.Error.WriteLine("Usage: OriginLauncher --self-test [--network]");
        Console.Error.WriteLine("       OriginLauncher --launch-plan <version> [--root <scratch dir>]");
        Console.Error.WriteLine("       OriginLauncher --list-versions");
        return 2;
    }

    private static string? ValueOf(string[] args, string flag)
    {
        var i = Array.IndexOf(args, flag);
        return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
    }
}
