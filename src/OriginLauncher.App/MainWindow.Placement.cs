using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using OriginLauncher.App.UI;

namespace OriginLauncher.App;

// Where the window opens (UI/WindowFit has the rule and its tests). Runs once, when the window's handle exists and
// before it is first shown: the work area of the monitor the window is on (the same one CenterScreen would use),
// in DIPs at that monitor's scale, and the designed size shrunk to it and centred. Manual placement from then on,
// so WPF's CenterScreen can't put back the unshrunk box.
public partial class MainWindow
{
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

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint dwFlags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);
}
