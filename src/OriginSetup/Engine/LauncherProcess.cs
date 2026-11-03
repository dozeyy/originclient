using System.Diagnostics;

namespace OriginSetup.Engine;

// The launcher holds its own exe open while running, so (re)installing or
// uninstalling needs it closed. Minecraft itself is a separate java process
// and is never touched.
//
// Only launchers in THIS Windows session count: the install is per user, and on
// a shared PC with fast user switching another user's launcher runs from that
// user's own folder, can't be closed from here (Access denied), and must not
// block this user's install.
public static class LauncherProcess
{
    private const string ProcessName = "OriginLauncher.App";

    public static bool IsRunning()
    {
        var running = OursRunning();
        foreach (var p in running) p.Dispose();
        return running.Count > 0;
    }

    /// <summary>Asks every launcher window in this session to close, waits, then ends any that remain.</summary>
    public static void Close(TimeSpan grace)
    {
        foreach (var p in OursRunning())
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

    private static List<Process> OursRunning()
    {
        int session;
        using (var self = Process.GetCurrentProcess()) session = self.SessionId;
        var ours = new List<Process>();
        foreach (var p in Process.GetProcessesByName(ProcessName))
        {
            bool mine;
            try { mine = p.SessionId == session; }
            catch (InvalidOperationException) { mine = false; } // exited meanwhile
            if (mine) ours.Add(p);
            else p.Dispose();
        }
        return ours;
    }
}
