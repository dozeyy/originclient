using System.Diagnostics;

namespace OriginSetup.Engine;

// The launcher holds its own exe open while running, so (re)installing or
// uninstalling needs it closed. Minecraft itself is a separate java process
// and is never touched.
public static class LauncherProcess
{
    private const string ProcessName = "OriginLauncher.App";

    public static bool IsRunning()
    {
        var running = Process.GetProcessesByName(ProcessName);
        foreach (var p in running) p.Dispose();
        return running.Length > 0;
    }

    /// <summary>Asks every launcher window to close, waits, then ends any that remain.</summary>
    public static void Close(TimeSpan grace)
    {
        foreach (var p in Process.GetProcessesByName(ProcessName))
        {
            using (p)
            {
                try
                {
                    if (p.MainWindowHandle != IntPtr.Zero) p.CloseMainWindow();
                    if (!p.WaitForExit((int)grace.TotalMilliseconds))
                    {
                        p.Kill();
                        p.WaitForExit(5000);
                    }
                }
                catch (InvalidOperationException) { } // already gone
                catch (System.ComponentModel.Win32Exception) { } // not ours to touch
            }
        }
    }
}
