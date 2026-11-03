// This assembly only ever runs on Linux, so the Unix-only file-mode APIs the Linux seams use are fine.
[assembly: System.Runtime.Versioning.SupportedOSPlatform("linux")]

namespace OriginLauncher.Linux;

internal static class Program
{
    // `--self-test [--network] [--require-keyring]` checks the Linux seams and exits 0 (all passed) or 1
    // without opening a window; anything else is the launcher UI (src/OriginLauncher.Desktop).
    private static async Task<int> Main(string[] args)
    {
        if (args.Contains("--self-test"))
            return await SelfTest.RunAsync(network: args.Contains("--network"), requireKeyring: args.Contains("--require-keyring"))
                .ConfigureAwait(false);

        // The launcher window, or `--render-shots <dir>` (headless PNGs of every screen). Runs before any await,
        // so the UI owns the process's main thread.
        return OriginLauncher.Desktop.Shell.DesktopEntry.Run(args);
    }
}
