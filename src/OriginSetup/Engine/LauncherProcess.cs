using System.Diagnostics;
using System.Threading;

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

    // The launcher's self-update copies into the install folder from a script after the launcher has quit; while it
    // runs it holds this file open for writing (launcher: Core/Updates/WindowsApplyScript.LockFileName; the name is
    // checked by tools/release/check_consistency.py). Installing meanwhile would have the script overwrite Setup's
    // newer files with its older stage a moment later, so a running swap counts as "Origin is open".
    public const string SwapLockFileName = "apply-update.lock";

    private static string SwapLockPath => Path.Combine(Handoff.DataRoot, "updates", SwapLockFileName);

    public static bool IsRunning()
    {
        if (SwapRunning(SwapLockPath)) return true;
        var running = OursRunning();
        foreach (var p in running) p.Dispose();
        return running.Count > 0;
    }

    /// <summary>
    /// True while someone holds <paramref name="lockPath"/> open for writing (the swap script). Read-only open that
    /// refuses writers: never writes, never throws. A leftover file nobody holds reads as false.
    /// </summary>
    public static bool SwapRunning(string lockPath)
    {
        try
        {
            using (new FileStream(lockPath, FileMode.Open, FileAccess.Read, FileShare.Read)) return false;
        }
        catch (FileNotFoundException) { return false; }
        catch (DirectoryNotFoundException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
        catch (IOException) { return true; }
    }

    /// <summary>
    /// Lets a running self-update swap finish (bounded: the script gives up on its own within ~2 minutes), then asks
    /// every launcher window in this session to close, waits, then ends any that remain.
    /// </summary>
    public static void Close(TimeSpan grace)
    {
        var swapDeadline = DateTime.UtcNow.AddMinutes(2);
        if (SwapRunning(SwapLockPath))
        {
            while (SwapRunning(SwapLockPath) && DateTime.UtcNow < swapDeadline) Thread.Sleep(250);
            Thread.Sleep(1500); // the script starts the launcher right after the copy: let it appear so it is closed too
        }
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
