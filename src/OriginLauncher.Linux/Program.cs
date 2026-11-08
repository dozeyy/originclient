// This assembly only ever runs on Linux, so the Unix-only file-mode APIs the Linux seams use are fine.
[assembly: System.Runtime.Versioning.SupportedOSPlatform("linux")]

namespace OriginLauncher.Linux;

internal static class Program
{
    // `--self-test [--network] [--require-keyring]` checks the Linux seams and exits 0 (all passed) or 1
    // without opening a window. Hidden, CI only: `--boot-test <version> [--root DIR] [--log FILE]
    // [--timeout SECONDS]` installs and starts that Minecraft version through Core and exits 0 once
    // Origin's title screen is up (BootTest.cs). Anything else is the launcher UI
    // (src/OriginLauncher.Desktop), including `--render-shots <dir>`.
    private static async Task<int> Main(string[] args)
    {
        // Before anything can go online: .NET aborts the whole process at its first HTTPS call when it finds no OpenSSL
        // it accepts (Fedora 45 ships only OpenSSL 4). The AppImage and tar.gz bring their own (LinuxOpenSsl); with none
        // at all, say what to install instead of vanishing.
        if (!OriginLauncher.App.Core.Net.LinuxOpenSsl.Prepare())
        {
            TellPlayer(OriginLauncher.App.Core.Net.LinuxOpenSsl.MissingMessage);
            return 1;
        }

        if (args.Contains("--self-test"))
            return await SelfTest.RunAsync(network: args.Contains("--network"), requireKeyring: args.Contains("--require-keyring"))
                .ConfigureAwait(false);

        if (Value(args, "--boot-test") is { } version)
        {
            var seconds = int.TryParse(Value(args, "--timeout"), out var s) && s > 0 ? s : 900;
            return BootTest.Run(version, Value(args, "--root"), Value(args, "--log"), TimeSpan.FromSeconds(seconds));
        }

        // The launcher window, or `--render-shots <dir>` (headless PNGs of every screen). Runs before any await,
        // so the UI owns the process's main thread.
        return OriginLauncher.Desktop.Shell.DesktopEntry.Run(args);
    }

    // A message for a launcher that can't open its window: the terminal, then the desktop's own dialog tool (best
    // effort: zenity on GNOME-style desktops, kdialog on KDE, else a notification). Nothing here needs OpenSSL.
    private static void TellPlayer(string message)
    {
        Console.Error.WriteLine(message);
        foreach (var (tool, toolArgs) in new (string, string[])[]
                 {
                     ("zenity", ["--error", "--title=Origin", "--text=" + message]),
                     ("kdialog", ["--title", "Origin", "--error", message]),
                     ("notify-send", ["Origin", message]),
                 })
        {
            if (!OriginLauncher.App.Core.Launch.LinuxPackages.OnPath(tool)) continue;
            try
            {
                var info = new System.Diagnostics.ProcessStartInfo(tool) { UseShellExecute = false };
                foreach (var a in toolArgs) info.ArgumentList.Add(a);
                using var p = System.Diagnostics.Process.Start(info);
                p?.WaitForExit(120_000);
                return;
            }
            catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException) { }
        }
    }

    // The argument after `flag`, or null when the flag is absent or last.
    private static string? Value(string[] args, string flag)
    {
        var i = Array.IndexOf(args, flag);
        return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
    }
}
