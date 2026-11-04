using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using OriginLauncher.App.UI;

namespace OriginLauncher.App;

// Where the window opens (UI/WindowFit has the rule and its tests). Runs once, when the window's handle exists and
// before it is first shown: the work area of the monitor the window is on (the same one CenterScreen would use),
// in DIPs at that monitor's scale, and the designed size shrunk to it and centred. Manual placement from then on,
// so WPF's CenterScreen can't put back the unshrunk box.
//
// Maximising (there is no maximise button: Aero snap to the top edge, Win+Up) answers WM_GETMINMAXINFO with the
// work area of the monitor the window is on (UI/WindowFit.Maximised), so the chromeless window stops at the taskbar
// instead of covering it and overhanging every edge by the resize border. With an auto-hide taskbar a sliver stays free
// on its edge, so the taskbar still slides in over a maximised launcher.
public partial class MainWindow
{
    private const int WM_GETMINMAXINFO = 0x0024;

    private void HookMaximisedBounds()
    {
        try { HwndSource.FromHwnd(new WindowInteropHelper(this).Handle)?.AddHook(PlacementWndProc); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"[MainWindow] Maximise hook skipped: {ex.Message}"); }
    }

    // Only fills in the maximised box and never marks the message handled, so WPF's own handler still applies
    // MinWidth/MinHeight to the track sizes.
    private IntPtr PlacementWndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg != WM_GETMINMAXINFO || lParam == IntPtr.Zero) return IntPtr.Zero;
        try
        {
            var monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
            if (monitor == IntPtr.Zero || !GetMonitorInfo(monitor, ref info)) return IntPtr.Zero;
            var monitorBox = ToBox(info.rcMonitor);
            var workBox = ToBox(info.rcWork);
            var autoHide = workBox == monitorBox ? AutoHideTaskbarEdge(info.rcMonitor) : WindowFit.Edge.None;
            if (WindowFit.Maximised(monitorBox, workBox, autoHide) is not { } max) return IntPtr.Zero;

            var mmi = Marshal.PtrToStructure<MINMAXINFO>(lParam);
            mmi.ptMaxPosition = new POINT { X = (int)max.Left, Y = (int)max.Top };
            mmi.ptMaxSize = new POINT { X = (int)max.Width, Y = (int)max.Height };
            Marshal.StructureToPtr(mmi, lParam, fDeleteOld: false);
        }
        catch { /* window messages must never throw; Windows' default maximised box stands */ }
        return IntPtr.Zero;
    }

    private static WindowFit.Box ToBox(MONITOR_RECT r) => new(r.Left, r.Top, r.Right - r.Left, r.Bottom - r.Top);

    // The edge of an auto-hide taskbar on this monitor, or None: the taskbar's auto-hide state first (ABM_GETSTATE),
    // then which edge of THIS monitor holds one (ABM_GETAUTOHIDEBAREX, Windows 8+; a secondary monitor's taskbar too).
    // Only asked when the work area is the whole monitor, which is the only case an auto-hide taskbar matters.
    private static WindowFit.Edge AutoHideTaskbarEdge(MONITOR_RECT monitor)
    {
        var state = new APPBARDATA { cbSize = (uint)Marshal.SizeOf<APPBARDATA>() };
        if (((uint)SHAppBarMessage(ABM_GETSTATE, ref state) & ABS_AUTOHIDE) == 0) return WindowFit.Edge.None;
        foreach (var edge in new[] { WindowFit.Edge.Bottom, WindowFit.Edge.Left, WindowFit.Edge.Top, WindowFit.Edge.Right })
        {
            var query = new APPBARDATA { cbSize = (uint)Marshal.SizeOf<APPBARDATA>(), uEdge = (uint)edge, rc = monitor };
            if (SHAppBarMessage(ABM_GETAUTOHIDEBAREX, ref query) != UIntPtr.Zero) return edge;
        }
        return WindowFit.Edge.None;
    }

    private const uint ABM_GETSTATE = 0x4;
    private const uint ABM_GETAUTOHIDEBAREX = 0xB;
    private const uint ABS_AUTOHIDE = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    private struct APPBARDATA
    {
        public uint cbSize;
        public IntPtr hWnd;
        public uint uCallbackMessage;
        public uint uEdge;
        public MONITOR_RECT rc;
        public IntPtr lParam;
    }

    [DllImport("shell32.dll")]
    private static extern UIntPtr SHAppBarMessage(uint dwMessage, ref APPBARDATA pData);

    private void FitToWorkArea()
    {
        // CI screen renders size the window themselves (UI/RenderShots.cs).
        if (App.IsRenderMode) return;
        try
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            var source = HwndSource.FromHwnd(hwnd);
            if (hwnd == IntPtr.Zero || source?.CompositionTarget == null) return;

            var monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
            if (monitor == IntPtr.Zero || !GetMonitorInfo(monitor, ref info)) return;

            var toDip = source.CompositionTarget.TransformFromDevice;
            var topLeft = toDip.Transform(new Point(info.rcWork.Left, info.rcWork.Top));
            var bottomRight = toDip.Transform(new Point(info.rcWork.Right, info.rcWork.Bottom));
            var work = new WindowFit.Box(topLeft.X, topLeft.Y, bottomRight.X - topLeft.X, bottomRight.Y - topLeft.Y);

            if (WindowFit.Centre(work, Width, Height, MinWidth, MinHeight) is not { } fit) return;
            WindowStartupLocation = WindowStartupLocation.Manual;
            MinWidth = fit.MinWidth;
            MinHeight = fit.MinHeight;
            Width = fit.Window.Width;
            Height = fit.Window.Height;
            Left = fit.Window.Left;
            Top = fit.Window.Top;
        }
        catch (Exception ex)
        {
            // Placement is cosmetic: on any failure WPF's own CenterScreen stands.
            System.Diagnostics.Debug.WriteLine($"[MainWindow] Work-area fit skipped: {ex.Message}");
        }
    }

    private const uint MONITOR_DEFAULTTONEAREST = 2;

    [StructLayout(LayoutKind.Sequential)]
    private struct MONITOR_RECT
    {
        public int Left, Top, Right, Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MONITORINFO
    {
        public int cbSize;
        public MONITOR_RECT rcMonitor;
        public MONITOR_RECT rcWork;
        public uint dwFlags;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X, Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MINMAXINFO
    {
        public POINT ptReserved;
        public POINT ptMaxSize;
        public POINT ptMaxPosition;
        public POINT ptMinTrackSize;
        public POINT ptMaxTrackSize;
    }

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint dwFlags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);
}
