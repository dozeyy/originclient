using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace MacHello;

// The two things Avalonia's NativeWebView doesn't expose, called straight on the WKWebView:
// is the data store really non-persistent, and a picture of the page. WKWebView's own snapshot
// needs no screen-recording permission, which a CI machine may not have granted.
internal static class AppleNative
{
    private const string ObjC = "/usr/lib/libobjc.A.dylib";

    [DllImport(ObjC, EntryPoint = "objc_getClass")] private static extern IntPtr Class(string name);
    [DllImport(ObjC, EntryPoint = "sel_registerName")] private static extern IntPtr Sel(string name);
    [DllImport(ObjC, EntryPoint = "objc_msgSend")] private static extern IntPtr Msg(IntPtr self, IntPtr sel);
    [DllImport(ObjC, EntryPoint = "objc_msgSend")] private static extern byte MsgByte(IntPtr self, IntPtr sel);
    [DllImport(ObjC, EntryPoint = "objc_msgSend")] private static extern IntPtr Msg(IntPtr self, IntPtr sel, IntPtr a);
    [DllImport(ObjC, EntryPoint = "objc_msgSend")] private static extern IntPtr Msg(IntPtr self, IntPtr sel, IntPtr a, IntPtr b);
    [DllImport(ObjC, EntryPoint = "objc_msgSend")] private static extern IntPtr Msg(IntPtr self, IntPtr sel, nuint a, IntPtr b);
    [DllImport(ObjC, EntryPoint = "objc_msgSend")] private static extern byte MsgByte(IntPtr self, IntPtr sel, IntPtr a, byte b);

    public static bool IsDataStorePersistent(IntPtr wkWebView)
    {
        var config = Msg(wkWebView, Sel("configuration"));
        var store = Msg(config, Sel("websiteDataStore"));
        return MsgByte(store, Sel("isPersistent")) != 0;
    }

    private static TaskCompletionSource<bool>? _snapshot;
    private static string _snapshotPath = "";

    public static async Task<bool> SnapshotToPngAsync(IntPtr wkWebView, string path, TimeSpan timeout)
    {
        _snapshot = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        _snapshotPath = path;
        Msg(wkWebView, Sel("takeSnapshotWithConfiguration:completionHandler:"), IntPtr.Zero, GlobalBlock());
        var hit = await Task.WhenAny(_snapshot.Task, Task.Delay(timeout));
        return hit == _snapshot.Task && _snapshot.Task.Result;
    }

    // A hand-built Objective-C global block (clang's block ABI): no captures, so nothing to copy
    // or dispose. Runs on the main thread, on WebKit's callback. Probe-lifetime, never freed.
    private static unsafe IntPtr GlobalBlock()
    {
        var system = NativeLibrary.Load("/usr/lib/libSystem.B.dylib");
        var isa = NativeLibrary.GetExport(system, "_NSConcreteGlobalBlock");

        var descriptor = (ulong*)Marshal.AllocHGlobal(16);
        descriptor[0] = 0;  // reserved
        descriptor[1] = 32; // sizeof(block literal)

        var block = (byte*)Marshal.AllocHGlobal(32);
        *(IntPtr*)block = isa;
        *(int*)(block + 8) = 1 << 28; // BLOCK_IS_GLOBAL
        *(int*)(block + 12) = 0;
        *(IntPtr*)(block + 16) = (IntPtr)(delegate* unmanaged<IntPtr, IntPtr, IntPtr, void>)&OnSnapshot;
        *(ulong**)(block + 24) = descriptor;
        return (IntPtr)block;
    }

    [UnmanagedCallersOnly]
    private static void OnSnapshot(IntPtr block, IntPtr nsImage, IntPtr nsError)
    {
        try
        {
            if (nsImage == IntPtr.Zero)
            {
                ProbeLog.Write("SNAPSHOT no image, error=" + Describe(nsError));
                _snapshot?.TrySetResult(false);
                return;
            }
            var tiff = Msg(nsImage, Sel("TIFFRepresentation"));
            var rep = Msg(Class("NSBitmapImageRep"), Sel("imageRepWithData:"), tiff);
            var noProperties = Msg(Class("NSDictionary"), Sel("dictionary"));
            const nuint NSBitmapImageFileTypePNG = 4;
            var png = Msg(rep, Sel("representationUsingType:properties:"), NSBitmapImageFileTypePNG, noProperties);
            var written = MsgByte(png, Sel("writeToFile:atomically:"), NSString(_snapshotPath), 1) != 0;
            _snapshot?.TrySetResult(written);
        }
        catch (Exception ex)
        {
            // An exception must not unwind through WebKit's native frames.
            ProbeLog.Write("SNAPSHOT exception " + ex.Message);
            _snapshot?.TrySetResult(false);
        }
    }

    private static IntPtr NSString(string value)
    {
        var utf8 = Marshal.StringToCoTaskMemUTF8(value);
        try { return Msg(Class("NSString"), Sel("stringWithUTF8String:"), utf8); }
        finally { Marshal.FreeCoTaskMem(utf8); }
    }

    private static string Describe(IntPtr nsObject)
    {
        if (nsObject == IntPtr.Zero) return "(none)";
        var utf8 = Msg(Msg(nsObject, Sel("description")), Sel("UTF8String"));
        return Marshal.PtrToStringUTF8(utf8) ?? "(none)";
    }
}
