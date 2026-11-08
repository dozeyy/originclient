namespace OriginLauncher.App.Core.Desktop;

/// <summary>Whether a Linux session can show the launcher's window (pure, so OriginLauncher.Tests covers it). The
/// launcher is an X11 app on purpose: see DesktopEntry (Avalonia 12.1's Wayland backend has no in-launcher sign-in page).</summary>
public static class WindowSession
{
    /// <summary>
    /// Why the launcher's window can't open in this session, in the player's words, or null when it can. The launcher is
    /// an X11 app (on Wayland it runs through XWayland, which every mainstream Wayland desktop starts by itself), so a
    /// session with no X display at all - Wayland without XWayland, or no desktop - would otherwise just never open.
    /// </summary>
    public static string? WindowBlocker(string? display, string? waylandDisplay)
    {
        if (!string.IsNullOrEmpty(display)) return null;
        if (!string.IsNullOrEmpty(waylandDisplay))
            return "Origin Launcher needs XWayland to open its window (Minecraft needs it too). Install it - for example "
                   + "\"sudo apt install xwayland\", \"sudo dnf install xorg-x11-server-Xwayland\" or "
                   + "\"sudo pacman -S xorg-xwayland\" - then log out and back in.";
        return "Origin Launcher needs a desktop session to open its window. Start it from your desktop's app menu.";
    }
}
