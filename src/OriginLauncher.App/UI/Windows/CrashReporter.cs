using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows;
using OriginLauncher.App.Core;

namespace OriginLauncher.App.UI.Windows;

// Turns an unhandled exception into a saved log file + the CrashWindow. Wired up
// from App for both UI-thread (recoverable) and background-thread (fatal)
// failures, so a crash never just vanishes to the desktop with nothing to go on.
public static class CrashReporter
{
    private static bool _showing;

    public static void Report(Exception ex, bool fatal)
    {
        string report;
        try { report = BuildReport(ex, fatal); }
        catch { report = ex.ToString(); } // never let the reporter itself throw

        var savedPath = TryWriteLog(report);

        try
        {
            var dispatcher = Application.Current?.Dispatcher;
            if (dispatcher == null) return;
            if (dispatcher.CheckAccess())
                ShowWindow(report, savedPath, fatal);
            else
                dispatcher.Invoke(() => ShowWindow(report, savedPath, fatal));
        }
        catch
        {
            // If we can't even show the window, the saved log is the fallback.
        }
    }

    private static void ShowWindow(string report, string? savedPath, bool fatal)
    {
        // A second failure while the dialog is up must not stack more dialogs.
        if (_showing) return;
        _showing = true;
        try
        {
            var window = new CrashWindow(report, savedPath, fatal);
            if (Application.Current?.MainWindow is { IsLoaded: true } main
                && !ReferenceEquals(main, window) && main.IsVisible)
            {
                window.Owner = main;
            }
            window.ShowDialog();
        }
        finally
        {
            _showing = false;
        }
    }

    private static string BuildReport(Exception ex, bool fatal)
    {
        var sb = new StringBuilder();
        var name = Assembly.GetEntryAssembly()?.GetName();

        sb.AppendLine("Origin Launcher - crash report");
        sb.AppendLine($"Version : {name?.Version?.ToString() ?? "unknown"}");
        sb.AppendLine($"OS      : {Environment.OSVersion} ({RuntimeInformation.OSArchitecture})");
        sb.AppendLine($"Runtime : {RuntimeInformation.FrameworkDescription}");
        sb.AppendLine($"Time    : {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
        sb.AppendLine($"Type    : {(fatal ? "fatal" : "recovered")}");
        sb.AppendLine();
        sb.AppendLine("----- error -----");

        var e = ex;
        var depth = 0;
        while (e != null)
        {
            sb.AppendLine($"{(depth == 0 ? "" : "Caused by: ")}{e.GetType().FullName}: {e.Message}");
            if (!string.IsNullOrWhiteSpace(e.StackTrace))
                sb.AppendLine(e.StackTrace);
            e = e.InnerException;
            depth++;
            if (e != null) sb.AppendLine();
        }

        return sb.ToString().TrimEnd();
    }

    private static string? TryWriteLog(string report)
    {
        try
        {
            OriginPaths.EnsureScaffold();
            var path = Path.Combine(OriginPaths.Logs, $"crash_{DateTime.Now:yyyyMMdd_HHmmss}.log");
            File.WriteAllText(path, report);
            return path;
        }
        catch
        {
            return null;
        }
    }
}
