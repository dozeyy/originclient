using System.Runtime.InteropServices;
using System.Text;

namespace OriginLauncher.App.Core.Desktop;

/// <summary>
/// Brings a running Minecraft's window to the front on Linux: the Play button's job when the selected account's game is
/// already open ("Running"), the twin of the Windows launcher's UI/GameWindow and the Mac's MacGameWindow.
///
/// Minecraft's window is always an X11 window (GLFW and LWJGL 2 use X11, under Wayland through XWayland), so this asks
/// the X server, through libX11 (part of every desktop with X11 or XWayland; no new dependency): the window list
/// (_NET_CLIENT_LIST), each window's process id (_NET_WM_PID) and title, the pick (Core/Desktop/GameWindowPick), then
/// the standard EWMH request _NET_ACTIVE_WINDOW, which also restores a minimised window. A window manager may still
/// decline (focus-stealing prevention marks it "wants attention" instead); a false result (no X display, libX11 missing,
/// no window told apart) lets the caller say where the game is instead of doing nothing.
///
/// Called on the launcher's UI thread, inside the Avalonia X11 app: Avalonia has already called XInitThreads and set a
/// process-wide X error handler that logs instead of exiting, so a window that closes between the list and its property
/// read (BadWindow) can't take the launcher down. It is a handful of round trips on a private connection (milliseconds).
/// </summary>
public static class LinuxGameWindow
{
    /// <summary>True when the game's window was found and asked to come forward. Never throws.</summary>
    public static bool BringToFront(int pid)
    {
        if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("DISPLAY"))) return false;
        nint display = 0;
        try
        {
            display = XOpenDisplay(0);
            if (display == 0) return false;
            var root = XDefaultRootWindow(display);
            var clientList = XInternAtom(display, "_NET_CLIENT_LIST", true);
            var wmPid = XInternAtom(display, "_NET_WM_PID", true);
            var active = XInternAtom(display, "_NET_ACTIVE_WINDOW", false);
            var netName = XInternAtom(display, "_NET_WM_NAME", true);
            var utf8 = XInternAtom(display, "UTF8_STRING", true);
            if (clientList == 0) return false; // not an EWMH window manager: nothing to ask

            var windows = new List<GameWindowPick.Candidate>();
            foreach (var w in ReadLongs(display, root, clientList, XA_WINDOW))
                windows.Add(new GameWindowPick.Candidate(w,
                    wmPid == 0 ? null : ReadLongs(display, w, wmPid, XA_CARDINAL) is [var p, ..] ? (int)p : null,
                    ReadTitle(display, w, netName, utf8)));
            if (GameWindowPick.For(windows, pid) is not { } target) return false;

            var ev = new XClientMessageEvent
            {
                type = ClientMessage, send_event = 1, display = display, window = target, message_type = active, format = 32,
                l0 = 2, // source indication: a pager-like request (the player asked for it), honoured by more WMs than 1
                l1 = 0, // CurrentTime
            };
            XSendEvent(display, root, false, SubstructureRedirectMask | SubstructureNotifyMask, ref ev);
            XMapRaised(display, target);
            XFlush(display);
            return true;
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException or BadImageFormatException)
        {
            return false; // no libX11 (a pure Wayland session without XWayland can't run Minecraft's window either)
        }
        finally
        {
            if (display != 0) XCloseDisplay(display);
        }
    }

    private static List<nint> ReadLongs(nint display, nint window, nint property, nint type)
    {
        var result = new List<nint>();
        if (XGetWindowProperty(display, window, property, 0, 4096, false, type, out _, out var format, out var count, out _, out var data) != 0
            || data == 0) return result;
        try
        {
            // Format-32 properties come back as C longs (8 bytes on every 64-bit Linux Origin ships for).
            if (format == 32)
                for (var i = 0; i < (int)count; i++) result.Add(Marshal.ReadIntPtr(data, i * IntPtr.Size));
        }
        finally { XFree(data); }
        return result;
    }

    private static string? ReadTitle(nint display, nint window, nint netName, nint utf8)
    {
        if (netName != 0 && utf8 != 0
            && XGetWindowProperty(display, window, netName, 0, 256, false, utf8, out _, out var format, out var count, out _, out var data) == 0
            && data != 0)
        {
            try { if (format == 8 && count > 0) return Encoding.UTF8.GetString(ReadBytes(data, (int)count)); }
            finally { XFree(data); }
        }
        // LWJGL 2 sets only the legacy WM_NAME (Latin-1).
        if (XFetchName(display, window, out var name) != 0 && name != 0)
        {
            try { return Marshal.PtrToStringAnsi(name); }
            finally { XFree(name); }
        }
        return null;
    }

    private static byte[] ReadBytes(nint data, int count)
    {
        var bytes = new byte[count];
        Marshal.Copy(data, bytes, 0, count);
        return bytes;
    }

    private const int ClientMessage = 33;
    private const nint XA_CARDINAL = 6, XA_WINDOW = 33;
    private const nint SubstructureNotifyMask = 1 << 19, SubstructureRedirectMask = 1 << 20;

    // XEvent is a 192-byte union; this is its XClientMessageEvent member on LP64 (every field a C long or pointer except
    // the ints, which are padded to 8), with the rest of the union as padding.
    [StructLayout(LayoutKind.Explicit, Size = 192)]
    private struct XClientMessageEvent
    {
        [FieldOffset(0)] public int type;
        [FieldOffset(8)] public nint serial;
        [FieldOffset(16)] public int send_event;
        [FieldOffset(24)] public nint display;
        [FieldOffset(32)] public nint window;
        [FieldOffset(40)] public nint message_type;
        [FieldOffset(48)] public int format;
        [FieldOffset(56)] public nint l0;
        [FieldOffset(64)] public nint l1;
        [FieldOffset(72)] public nint l2;
        [FieldOffset(80)] public nint l3;
        [FieldOffset(88)] public nint l4;
    }

    private const string X11 = "libX11.so.6";

    [DllImport(X11)] private static extern nint XOpenDisplay(nint name);
    [DllImport(X11)] private static extern int XCloseDisplay(nint display);
    [DllImport(X11)] private static extern nint XDefaultRootWindow(nint display);
    [DllImport(X11)] private static extern nint XInternAtom(nint display, string name, bool onlyIfExists);
    [DllImport(X11)] private static extern int XFree(nint data);
    [DllImport(X11)] private static extern int XFlush(nint display);
    [DllImport(X11)] private static extern int XMapRaised(nint display, nint window);
    [DllImport(X11)] private static extern int XFetchName(nint display, nint window, out nint name);
    [DllImport(X11)] private static extern int XSendEvent(nint display, nint window, bool propagate, nint mask, ref XClientMessageEvent ev);

    [DllImport(X11)]
    private static extern int XGetWindowProperty(nint display, nint window, nint property, nint offset, nint length, bool delete,
        nint reqType, out nint actualType, out int actualFormat, out nuint itemCount, out nuint bytesAfter, out nint data);
}
