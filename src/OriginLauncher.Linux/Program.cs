// This assembly only ever runs on Linux, so the Unix-only file-mode APIs the Linux seams use are fine.
[assembly: System.Runtime.Versioning.SupportedOSPlatform("linux")]

namespace OriginLauncher.Linux;

internal static class Program
{
    // No UI yet. `--self-test [--network] [--require-keyring]` checks the Linux seams and exits 0 (all
    // passed) or 1. Hidden, CI only: `--boot-test <version> [--root DIR] [--log FILE] [--timeout SECONDS]`
    // installs and starts that Minecraft version through Core and exits 0 once Origin's title screen is up
    // (BootTest.cs). Anything else prints the usage and exits 2. Neither flag opens a launcher window.
    private static async Task<int> Main(string[] args)
    {
        if (args.Contains("--self-test"))
            return await SelfTest.RunAsync(network: args.Contains("--network"), requireKeyring: args.Contains("--require-keyring"))
                .ConfigureAwait(false);

        if (Value(args, "--boot-test") is { } version)
        {
            var seconds = int.TryParse(Value(args, "--timeout"), out var s) && s > 0 ? s : 900;
            return BootTest.Run(version, Value(args, "--root"), Value(args, "--log"), TimeSpan.FromSeconds(seconds));
        }

        Console.Error.WriteLine("Origin Launcher for Linux: the window is not built yet.");
        Console.Error.WriteLine("Usage: OriginLauncher --self-test [--network] [--require-keyring]");
        return 2;
    }

    // The argument after `flag`, or null when the flag is absent or last.
    private static string? Value(string[] args, string flag)
    {
        var i = Array.IndexOf(args, flag);
        return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
    }
}
