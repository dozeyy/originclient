using System.Diagnostics;
using System.Runtime.InteropServices;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// Brings a running Minecraft to the front: the Play button's job while the selected account's game is open ("Running"),
/// and once when the game's window takes over from the launch screen (Mac twin of the WPF UI/GameWindow.cs). macOS has no
/// window handle to raise; the game's app is activated by its process id through AppKit's NSRunningApplication. Never
/// throws: a game that went away, or an AppKit that is not there (the headless shots), just stays where it is.
/// </summary>
internal static class MacGameWindow
{
    private const string ObjC = "/usr/lib/libobjc.A.dylib";
    // NSApplicationActivateAllWindows | NSApplicationActivateIgnoringOtherApps: the game's windows, now.
    private const ulong ActivateAllWindows = 1, ActivateIgnoringOtherApps = 2;

    [DllImport(ObjC, EntryPoint = "objc_getClass")]
    private static extern IntPtr GetClass(string name);

    [DllImport(ObjC, EntryPoint = "sel_registerName")]
    private static extern IntPtr Selector(string name);

    [DllImport(ObjC, EntryPoint = "objc_msgSend")]
    private static extern IntPtr SendPid(IntPtr receiver, IntPtr selector, int pid);

    [DllImport(ObjC, EntryPoint = "objc_msgSend")]
    [return: MarshalAs(UnmanagedType.I1)]
    private static extern bool SendOptions(IntPtr receiver, IntPtr selector, ulong options);

    /// <summary>True when the game's app was asked to come forward.</summary>
    public static bool BringToFront(Process process)
    {
        if (!OperatingSystem.IsMacOS()) return false;
        try
        {
            if (process.HasExited) return false;
            var cls = GetClass("NSRunningApplication");
            if (cls == IntPtr.Zero) return false;
            var app = SendPid(cls, Selector("runningApplicationWithProcessIdentifier:"), process.Id);
            return app != IntPtr.Zero
                && SendOptions(app, Selector("activateWithOptions:"), ActivateAllWindows | ActivateIgnoringOtherApps);
        }
        catch
        {
            return false;
        }
    }
}
