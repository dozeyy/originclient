// This assembly only ever runs on Linux, so the Unix-only file-mode APIs the Linux seams use are fine.
[assembly: System.Runtime.Versioning.SupportedOSPlatform("linux")]

namespace OriginLauncher.Linux;

internal static class Program
{
    // No UI yet. `--self-test [--network] [--require-keyring]` checks the Linux seams and exits 0 (all
    // passed) or 1; anything else prints the usage and exits 2. It never opens a window.
    private static async Task<int> Main(string[] args)
    {
        if (args.Contains("--self-test"))
            return await SelfTest.RunAsync(network: args.Contains("--network"), requireKeyring: args.Contains("--require-keyring"))
                .ConfigureAwait(false);

        Console.Error.WriteLine("Origin Launcher for Linux: the window is not built yet.");
        Console.Error.WriteLine("Usage: OriginLauncher --self-test [--network] [--require-keyring]");
        return 2;
    }
}
