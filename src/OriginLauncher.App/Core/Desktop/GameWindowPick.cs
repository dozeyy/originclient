namespace OriginLauncher.App.Core.Desktop;

/// <summary>
/// Which top-level window is a running Minecraft's, from the desktop's window list (Linux: X11's _NET_CLIENT_LIST, read
/// by LinuxGameWindow.Linux.cs). Pure, so OriginLauncher.Tests covers it on every OS.
///
/// The process id wins: GLFW (every LWJGL 3 version) sets _NET_WM_PID on its window, so several games at once (one per
/// account) are told apart exactly. LWJGL 2 (1.8.9) does not set it, so when no window carries the pid, the one window
/// whose title starts with "Minecraft" is taken; with two or more such windows nothing is guessed (a wrong account's
/// game must never come forward).
/// </summary>
public static class GameWindowPick
{
    public readonly record struct Candidate(nint Window, int? Pid, string? Title);

    /// <summary>The game's window, or null when none can be told apart.</summary>
    public static nint? For(IReadOnlyList<Candidate> windows, int gamePid)
    {
        foreach (var w in windows)
            if (w.Pid == gamePid) return w.Window;
        nint? only = null;
        foreach (var w in windows)
        {
            if (w.Pid != null || w.Title is not { } title || !title.StartsWith("Minecraft", StringComparison.Ordinal)) continue;
            if (only != null) return null;
            only = w.Window;
        }
        return only;
    }
}
