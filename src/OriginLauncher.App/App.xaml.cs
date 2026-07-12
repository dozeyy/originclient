using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.UI.Windows;

namespace OriginLauncher.App;

public partial class App : Application
{
    // Held for the process lifetime; never disposed explicitly — the OS releases
    // it at exit. Single-instance guard: two launchers racing the same
    // settings.json (last-write-wins) and double-provisioning the same instance
    // folder is a corruption class, not a feature.
    private static System.Threading.Mutex? _singleInstanceMutex;

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    protected override void OnStartup(StartupEventArgs e)
    {
        _singleInstanceMutex = new System.Threading.Mutex(
            initiallyOwned: true, @"Local\OriginLauncher.SingleInstance", out var isFirst);
        if (!isFirst)
        {
            // Another launcher is already running — front it and bow out.
            FocusExistingInstance();
            Shutdown();
            return;
        }

        base.OnStartup(e);
        OriginPaths.EnsureScaffold();

        // A single stray exception used to take the whole launcher down with
        // nothing to show for it. Route every unhandled failure through the
        // crash reporter (saved log + copy-paste dialog) instead.
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;
        System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (_, args) => args.SetObserved();
    }

    // Best-effort: bring the already-running launcher's window forward so the
    // second click on the shortcut still "does something" visible. SW_RESTORE
    // (9) also un-minimizes it.
    private static void FocusExistingInstance()
    {
        try
        {
            var me = System.Diagnostics.Process.GetCurrentProcess();
            foreach (var other in System.Diagnostics.Process.GetProcessesByName(me.ProcessName))
            {
                if (other.Id != me.Id && other.MainWindowHandle != IntPtr.Zero)
                {
                    ShowWindow(other.MainWindowHandle, 9);
                    SetForegroundWindow(other.MainWindowHandle);
                    break;
                }
            }
        }
        catch
        {
            // Focusing is a courtesy — never block shutdown of the duplicate.
        }
    }

    // UI-thread exceptions are usually survivable (a bad event handler, a render
    // hiccup) — show the report, then keep the launcher alive.
    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        CrashReporter.Report(e.Exception, fatal: false);
        e.Handled = true;
    }

    // Background-thread exceptions can't be swallowed — the runtime is tearing
    // the process down. Show the report before it goes.
    private void OnDomainUnhandledException(object sender, UnhandledExceptionEventArgs e)
    {
        if (e.ExceptionObject is Exception ex)
            CrashReporter.Report(ex, fatal: true);
    }
}
