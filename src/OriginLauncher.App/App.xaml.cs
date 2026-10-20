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

    /// <summary>`--render-shots` (CI): the real window with made-up data; nothing reads this PC.</summary>
    internal static bool IsRenderMode { get; private set; }

    /// <summary>`--import`: the installer's finish-page checkbox. The launcher starts normally, then opens straight into the import card.</summary>
    internal static bool StartWithImport { get; private set; }

    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    protected override void OnStartup(StartupEventArgs e)
    {
        // `--import-plan <out.json>`: a read-only dry run of the import against this PC (Core/Import/Launcher/
        // ImportPlanReport). No window, no single-instance guard, and NOTHING below this block runs - in
        // particular not EnsureScaffold or the recovery - so no Origin folder is created or written. It writes
        // only the one json file and exits (0 written, 2 refused, 1 failed).
        var dryRun = Array.IndexOf(e.Args, "--import-plan");
        if (dryRun >= 0 && dryRun + 1 < e.Args.Length)
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            var code = Core.Import.Launcher.ImportPlanReport.Run(e.Args[dryRun + 1]);
            Shutdown(code);
            return;
        }

        // `--launch-timing <root> <version> <out.json>` (CI, launch-time.yml): one timed Play
        // press with every launcher file under <root>, no window (Core/Launch/LaunchTimingHarness).
        var timing = Array.IndexOf(e.Args, "--launch-timing");
        if (timing >= 0 && timing + 3 < e.Args.Length)
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            // On a pool thread: the launch path's awaits would otherwise queue back onto this
            // (blocked) UI thread and never run.
            var code = Task.Run(() => Core.Launch.LaunchTimingHarness.Run(e.Args[timing + 1], e.Args[timing + 2], e.Args[timing + 3]))
                .GetAwaiter().GetResult();
            Shutdown(code);
            return;
        }

        // Hover-label timing and placement (UI/ToolTipDefaults.cs): before any window exists.
        UI.ToolTipDefaults.Apply();

        // CI screen render (UI/RenderShots.cs): no single-instance guard, no
        // main window of its own, no motion; pictures, then exit (1 = failed).
        var render = Array.IndexOf(e.Args, "--render-shots");
        if (render >= 0 && render + 1 < e.Args.Length)
        {
            var outDir = e.Args[render + 1];
            IsRenderMode = true;
            var scratch = System.IO.Path.Combine(
                System.IO.Path.GetTempPath(), "origin-render-shots-" + Guid.NewGuid().ToString("N"));
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            // Any failure, on any thread, leaves its stack trace for the CI log.
            AppDomain.CurrentDomain.UnhandledException += (_, args) =>
                WriteRenderError(outDir, args.ExceptionObject?.ToString());
            DispatcherUnhandledException += (_, args) => WriteRenderError(outDir, args.Exception.ToString());
            base.OnStartup(e);
            DisableMotion();
            try
            {
                // First thing that reads a launcher path: all launcher data goes to a
                // throwaway folder, so this run can't touch the player's settings.
                OriginPaths.UseScratchRoot(scratch);
                OriginPaths.EnsureScaffold();
                UI.RenderShots.Run(outDir);
                Shutdown(0);
            }
            catch (Exception ex)
            {
                WriteRenderError(outDir, ex.ToString());
                Shutdown(1);
            }
            finally
            {
                // Best effort: the scratch profile is only ever this run's.
                try { System.IO.Directory.Delete(scratch, recursive: true); } catch { }
            }
            return;
        }

        StartWithImport = Array.IndexOf(e.Args, "--import") >= 0;

		var mutexName = @"Local\OriginLauncher.SingleInstance";
#if DEBUG
		// A locally built launcher must not hand focus to an older installed
		// release. Keep the production single-instance contract unchanged, but
		// isolate developer builds by executable path so the UI being verified is
		// always the binary that was just compiled.
		var executable = Environment.ProcessPath ?? AppContext.BaseDirectory;
		var pathBytes = System.Text.Encoding.UTF8.GetBytes(executable.ToUpperInvariant());
		var pathHash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(pathBytes))[..12];
		mutexName += ".Debug." + pathHash;
#endif
        _singleInstanceMutex = new System.Threading.Mutex(
            initiallyOwned: true, mutexName, out var isFirst);
        if (!isFirst)
        {
            // `--import` must not be lost: hand the request to the running launcher (one fixed word over a
            // per-user pipe, short timeout; Core/Import/Launcher/ImportIpc), then front it and bow out.
            if (StartWithImport) Core.Import.Launcher.ImportIpc.TrySend(ImportPipeName(mutexName));
            FocusExistingInstance();
            Shutdown();
            return;
        }

        base.OnStartup(e);

        // Follow Windows' animation preference everywhere. The same resource
        // keys drive page, panel, hover and loading transitions, so one user
        // setting removes motion without branching every control template.
        if (!SystemParameters.ClientAreaAnimation)
            DisableMotion();

        OriginPaths.EnsureScaffold();
        System.Threading.Tasks.Task.Run(DeleteLegacySignInProfile);
        // An import that was interrupted last time (crash, power cut) is rolled back, on the thread pool;
        // MainWindow tells the player once the first window is up (Core/Import/Launcher/ImportStartup).
        Core.Import.Launcher.ImportStartup.StartRecovery();

        // Per-account game folders (Core/GameFolders): the first run of this
        // code moves each version's old single game folder into the selected
        // account's — before any page reads a game folder. Renames only, so it
        // is quick; never throws; resumes on a later start if a file was busy.
        Core.GameFolders.Launcher.AccountFolders.EnsureMigrated(
            Core.GameFolders.Launcher.AccountFolders.SelectedKey());

        // Choices from the installer's Customize page (memory, default
        // version) land in settings before the first window reads them.
        Core.Install.SetupChoices.ApplyIfPresent();
        // Self-updates swap files from a zip; keep Add/Remove Programs showing
        // the version that is actually running. Best-effort, never blocks.
        if (!Core.Updates.UpdateService.IsDevBuild)
        {
            try { Core.Install.InstallRegistry.RefreshDisplayVersion(Core.Updates.UpdateService.CurrentVersion.ToString(3)); }
            catch (Exception ex) when (ex is UnauthorizedAccessException or System.Security.SecurityException or System.IO.IOException) { }
        }

        // A single stray exception used to take the whole launcher down with
        // nothing to show for it. Route every unhandled failure through the
        // crash reporter (saved log + copy-paste dialog) instead.
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnDomainUnhandledException;
        System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (_, args) => args.SetObserved();

        // Listens for a second launch's `--import` (see above). A pool thread; the window is opened on the UI thread.
        _importIpc = new Core.Import.Launcher.ImportIpcServer(
            ImportPipeName(mutexName), OnImportRequestedByAnotherInstance, Core.Import.Launcher.ImportLog.Write);
        _importIpc.Start();

        new MainWindow().Show();
    }

    private static Core.Import.Launcher.ImportIpcServer? _importIpc;

    // The pipe a second `--import` launch talks to: named from this user's SID and logon session (never the
    // account name, which is guessable and can collide between a local and a domain account).
    private static string ImportPipeName(string mutexName)
    {
        var sid = System.Security.Principal.WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName;
        return Core.Import.Launcher.ImportIpc.PipeName(mutexName, sid, System.Diagnostics.Process.GetCurrentProcess().SessionId);
    }

    private void OnImportRequestedByAnotherInstance() => Dispatcher.BeginInvoke(() =>
    {
        if (Current.MainWindow is OriginLauncher.App.MainWindow { IsLoaded: true } window)
            window.OpenImportFromAnotherInstance();
        else
            StartWithImport = true; // the window is still opening: it opens the import panel itself once it is up
    });

    private static void WriteRenderError(string outDir, string? error)
    {
        try
        {
            System.IO.Directory.CreateDirectory(outDir);
            System.IO.File.AppendAllText(System.IO.Path.Combine(outDir, "render-error.txt"), error + Environment.NewLine);
        }
        catch { /* nothing more to do */ }
    }

    private void DisableMotion()
    {
        var none = new Duration(TimeSpan.Zero);
        Resources["Motion.Base"] = none;
        Resources["Motion.Press"] = none;
        Resources["Motion.Release"] = none;
        Resources["Motion.Scene"] = none;
        Resources["Motion.Exit"] = none;
        Resources["Motion.Intro"] = none;
        Resources["Motion.Settle"] = none;
    }

    // Removes the pre-2026-10-13 sign-in browser profile
    // (OriginPaths.LegacySignInWebViewData): it still held the first account's
    // Microsoft session cookies. Off the UI thread and fail-soft: a locked file
    // only means another try on the next start. The log line names the
    // outcome, never the folder's contents.
    // Also sweeps per-panel sign-in profiles (WV2Profile_signin-<guid>) that a
    // closed panel's Profile.Delete() didn't get to remove; at startup no
    // sign-in browser is running yet, so none of them is in use.
    private static void DeleteLegacySignInProfile()
    {
        var targets = new List<string>();
        if (OriginPaths.LegacySignInWebViewData is { } legacy && System.IO.Directory.Exists(legacy))
            targets.Add(legacy);
        var signInProfiles = System.IO.Path.Combine(OriginPaths.SignInWebViewData, "EBWebView");
        try
        {
            if (System.IO.Directory.Exists(signInProfiles))
                targets.AddRange(System.IO.Directory.GetDirectories(signInProfiles, "WV2Profile_signin-*"));
        }
        catch (Exception)
        {
            // Unreadable folder: nothing to sweep this time.
        }

        foreach (var target in targets)
        {
            string outcome;
            try
            {
                System.IO.Directory.Delete(target, recursive: true);
                outcome = $"removed old sign-in browser profile {System.IO.Path.GetFileName(target)}";
            }
            catch (Exception ex)
            {
                outcome = $"could not remove old sign-in browser profile {System.IO.Path.GetFileName(target)} ({ex.GetType().Name}); will retry next start";
            }
            try
            {
                System.IO.File.AppendAllText(
                    System.IO.Path.Combine(OriginPaths.Logs, "signin-profile.log"),
                    $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {outcome}{Environment.NewLine}");
            }
            catch (Exception)
            {
                // Logging is a courtesy; never let it surface.
            }
        }
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
