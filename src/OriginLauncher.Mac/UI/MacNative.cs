using System.Runtime.InteropServices;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// The two AppKit calls the launcher's UI needs, straight through the Objective-C runtime (no binding library): the
/// Edit menu's actions sent down the responder chain, and the system's "Reduce motion" setting. Off a Mac (the Linux
/// headless CI runs of this build) both answer "nothing done" instead of touching AppKit.
/// </summary>
internal static class MacNative
{
    private const string ObjC = "/usr/lib/libobjc.A.dylib";

    [DllImport(ObjC)] private static extern IntPtr objc_getClass(string name);
    [DllImport(ObjC)] private static extern IntPtr sel_registerName(string name);
    [DllImport(ObjC, EntryPoint = "objc_msgSend")] private static extern IntPtr Send(IntPtr receiver, IntPtr selector);

    [DllImport(ObjC, EntryPoint = "objc_msgSend")]
    [return: MarshalAs(UnmanagedType.I1)]
    private static extern bool SendBool(IntPtr receiver, IntPtr selector);

    [DllImport(ObjC, EntryPoint = "objc_msgSend")]
    [return: MarshalAs(UnmanagedType.I1)]
    private static extern bool SendAction(IntPtr receiver, IntPtr selector, IntPtr action, IntPtr to, IntPtr from);

    /// <summary>
    /// [NSApp sendAction:@selector(<paramref name="selector"/>) to:nil from:nil]: the action goes to the key window's
    /// first responder and up its chain, exactly what an Edit menu item does in any Mac app. Microsoft's sign-in page
    /// (a WKWebView inside Avalonia's NativeControlHost) only pastes, copies or selects through these actions: Avalonia
    /// hands it no Cmd key combinations (Avalonia issue 19000), so without this Cmd+V did nothing in the page. True when
    /// a responder took the action. Main thread only (the Avalonia UI thread on a Mac).
    /// </summary>
    internal static bool SendEditAction(string selector)
    {
        if (!OperatingSystem.IsMacOS()) return false;
        try
        {
            var app = Send(objc_getClass("NSApplication"), sel_registerName("sharedApplication"));
            if (app == IntPtr.Zero) return false;
            return SendAction(app, sel_registerName("sendAction:to:from:"), sel_registerName(selector), IntPtr.Zero, IntPtr.Zero);
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException)
        {
            return false;
        }
    }

    /// <summary>
    /// System Settings > Accessibility > Display > Reduce motion ([[NSWorkspace sharedWorkspace]
    /// accessibilityDisplayShouldReduceMotion]): the Mac twin of Windows' "Show animations in Windows"
    /// (SystemParameters.ClientAreaAnimation), which the Windows launcher follows the same way. False when unknown.
    /// </summary>
    internal static bool ReduceMotion()
    {
        if (!OperatingSystem.IsMacOS()) return false;
        try
        {
            var workspace = Send(objc_getClass("NSWorkspace"), sel_registerName("sharedWorkspace"));
            return workspace != IntPtr.Zero && SendBool(workspace, sel_registerName("accessibilityDisplayShouldReduceMotion"));
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException)
        {
            return false;
        }
    }
}
