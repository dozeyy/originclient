using System.Runtime.InteropServices;
using OriginLauncher.App.Core.Promos;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// Win32 half of the banner's visibility gate: is the banner's on-screen
/// rectangle hidden by OTHER windows? Ported from Modrinth's app
/// (ads_occlusion_windows.rs): walk top-level windows from the top of the
/// z-order down to our own window — only those can be in front of us — and
/// measure how much of the banner they cover. Differences: the covered area
/// is an exact union (<see cref="OcclusionMath"/>), click-through overlay
/// windows (GPU/recording/Discord HUDs that span the whole screen but show
/// nothing) are ignored, and a banner on no monitor at all counts as hidden.
/// One call is a few dozen cheap user32 queries; PromoBanner polls it every
/// 1.5 s and only while the launcher is on Home and not minimized.
/// </summary>
internal static class WindowOcclusion
{
    /// <summary>True when the banner should count as not visible.</summary>
    public static bool IsHidden(IntPtr ownWindow, ScreenRect banner)
    {
        if (banner.IsEmpty || ownWindow == IntPtr.Zero) return true;

        var bannerRect = new RECT { Left = banner.Left, Top = banner.Top, Right = banner.Right, Bottom = banner.Bottom };
        if (MonitorFromRect(ref bannerRect, MONITOR_DEFAULTTONULL) == IntPtr.Zero) return true;

        var ownRoot = GetAncestor(ownWindow, GA_ROOT);
        var occluders = new List<ScreenRect>();

        for (var hwnd = GetTopWindow(IntPtr.Zero); hwnd != IntPtr.Zero; hwnd = GetWindow(hwnd, GW_HWNDNEXT))
        {
            if (hwnd == ownRoot) break; // everything further down is behind us

            // Unlike Modrinth (whose ad is a separate top-level window), our
            // own other windows — the crash report, say — DO count: they're
            // real dialogs sitting over the banner. The ad WebView itself is a
            // child window, so it never shows up in this top-level walk.
            if (!IsWindowVisible(hwnd) || IsIconic(hwnd) || IsCloaked(hwnd)) continue;
            if (IsSeeThrough(hwnd)) continue;
            if (!TryGetBounds(hwnd, out var bounds)) continue;

            var hit = bounds.Intersect(banner);
            if (!hit.IsEmpty) occluders.Add(hit);
        }

        return OcclusionMath.IsHidden(banner, occluders);
    }

    // Click-through windows are overlays by construction (they can't be
    // interacted with), and a fully transparent layered window draws nothing.
    private static bool IsSeeThrough(IntPtr hwnd)
    {
        // GetWindowLongPtrW only exists in 64-bit user32.
        var exStyle = IntPtr.Size == 8
            ? (long)GetWindowLongPtr(hwnd, GWL_EXSTYLE)
            : GetWindowLong32(hwnd, GWL_EXSTYLE);
        if ((exStyle & WS_EX_TRANSPARENT) != 0) return true;
        if ((exStyle & WS_EX_LAYERED) != 0
            && GetLayeredWindowAttributes(hwnd, out _, out var alpha, out var flags)
            && (flags & LWA_ALPHA) != 0 && alpha == 0)
            return true;
        return false;
    }

    // Windows on another virtual desktop, suspended UWP frames, etc. report
    // visible but are cloaked by DWM — they aren't on screen.
    private static bool IsCloaked(IntPtr hwnd) =>
        DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, out int cloaked, sizeof(int)) == 0 && cloaked != 0;

    // DWM's extended frame bounds exclude the invisible resize borders that
    // GetWindowRect includes (~7 px each side on Windows 10/11).
    private static bool TryGetBounds(IntPtr hwnd, out ScreenRect bounds)
    {
        if (DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, out RECT r, Marshal.SizeOf<RECT>()) != 0
            && !GetWindowRect(hwnd, out r))
        {
            bounds = default;
            return false;
        }
        bounds = new ScreenRect(r.Left, r.Top, r.Right, r.Bottom);
        return !bounds.IsEmpty;
    }

    private const uint GW_HWNDNEXT = 2;
    private const uint GA_ROOT = 2;
    private const int GWL_EXSTYLE = -20;
    private const long WS_EX_TRANSPARENT = 0x00000020;
    private const long WS_EX_LAYERED = 0x00080000;
    private const uint LWA_ALPHA = 0x2;
    private const uint MONITOR_DEFAULTTONULL = 0;
    private const int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    private const int DWMWA_CLOAKED = 14;

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left, Top, Right, Bottom;
    }

    [DllImport("user32.dll")]
    private static extern IntPtr GetTopWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern IntPtr GetWindow(IntPtr hWnd, uint uCmd);

    [DllImport("user32.dll")]
    private static extern IntPtr GetAncestor(IntPtr hwnd, uint gaFlags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsIconic(IntPtr hWnd);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern IntPtr GetWindowLongPtr(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
    private static extern int GetWindowLong32(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetLayeredWindowAttributes(IntPtr hwnd, out uint crKey, out byte bAlpha, out uint dwFlags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromRect(ref RECT lprc, uint dwFlags);

    [DllImport("dwmapi.dll")]
    private static extern int DwmGetWindowAttribute(IntPtr hwnd, int dwAttribute, out int pvAttribute, int cbAttribute);

    [DllImport("dwmapi.dll")]
    private static extern int DwmGetWindowAttribute(IntPtr hwnd, int dwAttribute, out RECT pvAttribute, int cbAttribute);
}
