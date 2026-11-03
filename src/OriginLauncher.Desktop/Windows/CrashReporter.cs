using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Threading;
using OriginLauncher.App.Core;

namespace OriginLauncher.Desktop.Windows;

// Turns an unhandled launcher exception into a saved log file + the CrashWindow (the Windows launcher's CrashReporter),
// so a failure never just vanishes with nothing to go on. Meant to be wired from the app's entry for both UI-thread
// (recoverable) and background-thread (fatal) failures. Never throws.
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
            if (Dispatcher.UIThread.CheckAccess())
            {
                ShowAndWait(report, savedPath, fatal);
            }
            else
            {
                // A dying background thread waits here until the player closes the window.
                using var closed = new ManualResetEventSlim();
                Dispatcher.UIThread.Post(() =>
                {
                    try { Show(report, savedPath, fatal, closed.Set); }
                    catch { closed.Set(); }
                });
                closed.Wait(TimeSpan.FromMinutes(30));
            }
        }
        catch
        {
            // If even the window can't show, the saved log is the fallback.
        }
    }

    // On the UI thread: show the window and run a nested loop until it closes (the failing call waits, as WPF's
    // ShowDialog did).
    private static void ShowAndWait(string report, string? savedPath, bool fatal)
    {
        using var cts = new CancellationTokenSource();
        if (!Show(report, savedPath, fatal, cts.Cancel)) return;
        Dispatcher.UIThread.MainLoop(cts.Token);
    }

    private static bool Show(string report, string? savedPath, bool fatal, Action onClosed)
    {
        // A second failure while the window is up must not stack more windows.
        if (_showing) { onClosed(); return false; }
        _showing = true;
        try
        {
            var window = new CrashWindow(report, savedPath, fatal);
            window.Closed += (_, _) => { _showing = false; onClosed(); };
            var owner = (Avalonia.Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.MainWindow;
            if (owner is { IsVisible: true }) window.ShowDialog(owner);
            else window.Show();
            return true;
        }
        catch
        {
            _showing = false;
            onClosed();
            return false;
        }
    }

    internal static string BuildReport(Exception ex, bool fatal)
    {
        var sb = new StringBuilder();
        var name = Assembly.GetEntryAssembly()?.GetName();
        sb.AppendLine("Origin Launcher - crash report");
        sb.AppendLine($"Version : {name?.Version?.ToString() ?? "unknown"}");
        sb.AppendLine($"OS      : {RuntimeInformation.OSDescription} ({RuntimeInformation.OSArchitecture})");
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
            if (!string.IsNullOrWhiteSpace(e.StackTrace)) sb.AppendLine(e.StackTrace);
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
