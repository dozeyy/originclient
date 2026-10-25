using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;

namespace OriginSetup.UI;

// Two small window helpers shared by the installer and the uninstaller.
public static class WindowFrame
{
    private const int DwmWindowCornerPreference = 33; // DWMWA_WINDOW_CORNER_PREFERENCE
    private const int DwmRound = 2;                    // DWMWCP_ROUND (8 px)

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    /// <summary>
    /// Spec 14: Windows 11 rounded corners on the chromeless window, asked of DWM (a chromeless WPF window is
    /// square otherwise). Windows 10 has no such attribute: the call fails quietly and the window stays square.
    /// </summary>
    public static void RoundCorners(Window window)
    {
        window.SourceInitialized += (_, _) =>
        {
            try
            {
                var hwnd = new WindowInteropHelper(window).Handle;
                int round = DwmRound;
                DwmSetWindowAttribute(hwnd, DwmWindowCornerPreference, ref round, sizeof(int));
            }
            catch (Exception ex) when (ex is DllNotFoundException || ex is EntryPointNotFoundException) { }
        };
    }

    /// <summary>
    /// Shows <paramref name="path"/> in <paramref name="block"/> shortened in the MIDDLE when it doesn't fit,
    /// keeping the drive and the last folders ("C:\Users\Will\App...\OriginLauncher"): the end of a path is the
    /// part a player recognises. The full path is the tooltip. Call again when the width changes.
    /// </summary>
    public static void FitPath(TextBlock block, string path)
    {
        block.ToolTip = path;
        double room = block.ActualWidth;
        if (room <= 0 || Measure(block, path) <= room)
        {
            block.Text = path;
            return;
        }
        // Keep one third of the characters from the front and two thirds from the back, as many as fit.
        for (int kept = path.Length - 1; kept > 6; kept--)
        {
            int head = kept / 3;
            var candidate = path.Substring(0, head) + "..." + path.Substring(path.Length - (kept - head));
            if (Measure(block, candidate) <= room)
            {
                block.Text = candidate;
                return;
            }
        }
        block.Text = path;
    }

    private static double Measure(TextBlock block, string text)
    {
        var typeface = new Typeface(block.FontFamily, block.FontStyle, block.FontWeight, block.FontStretch);
        var formatted = new FormattedText(text, System.Globalization.CultureInfo.CurrentUICulture,
            FlowDirection.LeftToRight, typeface, block.FontSize, Brushes.Black,
            VisualTreeHelper.GetDpi(block).PixelsPerDip);
        return formatted.WidthIncludingTrailingWhitespace;
    }
}
