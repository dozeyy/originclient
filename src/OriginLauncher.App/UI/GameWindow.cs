using System.Diagnostics;
using System.Runtime.InteropServices;

namespace OriginLauncher.App.UI;

/// <summary>
/// Brings a running Minecraft's window to the front — the Play button's job
/// when the selected account's game is already open ("Running"). Restores it
/// first when it is minimised. The launcher is the foreground app at the moment
/// of the click, so Windows lets it hand the focus over.
/// </summary>
public static class GameWindow
{
    private const int SwRestore = 9;

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    private static extern bool IsIconic(IntPtr hWnd);

    /// <summary>True when the game's window was found and asked to come forward. Never throws.</summary>
    public static bool BringToFront(Process process)
    {
        try
        {
            process.Refresh();
            if (process.HasExited) return false;
            var handle = process.MainWindowHandle;
            if (handle == IntPtr.Zero) return false;
            if (IsIconic(handle)) ShowWindow(handle, SwRestore);
            return SetForegroundWindow(handle);
        }
        catch
        {
            // the process went away between checks — nothing to show
            return false;
        }
    }
}
