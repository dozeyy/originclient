using System.Diagnostics;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using OriginSetup.Engine;
using OriginSetup.UI;

namespace OriginSetup;

public partial class App : Application
{
    private string? _deleteSelfOnExit;
    private string? _removeDirIfEmptyOnExit;
    private SingleInstance? _instance;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        WatchForCrashes();

        // Follow Windows' "show animations" preference, exactly like the launcher.
        if (!SystemParameters.ClientAreaAnimation)
        {
            var none = new Duration(TimeSpan.Zero);
            foreach (var key in new[] { "Motion.Base", "Motion.Press", "Motion.Release", "Motion.Scene", "Motion.Exit" })
                Resources[key] = none;
        }

        var args = SetupArgs.Parse(e.Args);
        var self = Assembly.GetEntryAssembly()!.Location;

        if (args.Uninstall)
            StartUninstall(args, self);
        else
            StartInstall(args, self);
    }

    // One Setup or uninstall at a time (Engine/SingleInstance). A window run hands over to the one already open at
    // once; a silent run waits its turn for a while. False = this copy must exit.
    private bool ClaimSingleInstance(bool silent)
    {
        _instance = SingleInstance.TryClaim(silent ? TimeSpan.FromMinutes(2) : TimeSpan.Zero);
        if (_instance == null)
        {
            SetupLog.Write("another Origin Setup is already running: handed over to it");
            return false;
        }
        _instance.OnSecondCopy(() => Dispatcher.BeginInvoke(new Action(() =>
        {
            var w = MainWindow;
            if (w == null || !w.IsVisible) return;
            if (w.WindowState == WindowState.Minimized) w.WindowState = WindowState.Normal;
            w.Activate();
        })));
        return true;
    }

    private void StartInstall(SetupArgs args, string self)
    {
        SetupLog.Start($"Origin Setup {string.Join(" ", Environment.GetCommandLineArgs().Skip(1))}");

        if (args.RenderTour != null)
        {
            RenderTour.Run(args.RenderTour, self);
            Shutdown(0);
            return;
        }
        if (!ClaimSingleInstance(args.Silent))
        {
            Shutdown(args.Silent ? 1 : 0);
            return;
        }
        if (args.Silent)
        {
            Shutdown(RunSilentInstall(self, args));
            return;
        }

        var window = new SetupWindow(self, args.Source);
        window.Closed += (_, _) => Shutdown();
        window.Show();
    }

    private static int RunSilentInstall(string self, SetupArgs args)
    {
        try
        {
            var existing = Registration.FindExisting();
            var options = new InstallOptions
            {
                Directory = args.Dir != null ? InstallLocation.Resolve(args.Dir) : existing?.Directory ?? InstallLocation.Default,
                StartWithWindows = args.StartWithWindows,
                SourceZip = args.Source,
            };
            LauncherProcess.Close(TimeSpan.FromSeconds(5));
            // Off the UI thread: the download is async underneath.
            Task.Run(() => Installer.Run(self, options, null, _ => { }, CancellationToken.None)).GetAwaiter().GetResult();
            return 0;
        }
        catch (Exception ex)
        {
            SetupLog.Write("silent install failed: " + ex);
            return 1;
        }
    }

    private void StartUninstall(SetupArgs args, string self)
    {
        // The uninstaller lives inside the folder it deletes, so it first
        // copies itself to %TEMP% and hands over to that copy.
        if (!args.FromTemp)
        {
            SetupLog.Start("Origin uninstall (handing over to a temporary copy)");
            var installDir = (args.Dir ?? Path.GetDirectoryName(self)!).TrimEnd('\\');
            var temp = Path.Combine(Path.GetTempPath(), $"OriginUninstall-{Guid.NewGuid():N}.exe");
            try
            {
                File.Copy(self, temp);
                var forward = $"--uninstall --from-temp --dir \"{installDir}\""
                              + (args.Silent ? " --silent" : "") + (args.RemoveData ? " --remove-data" : "");
                Process.Start(new ProcessStartInfo(temp, forward) { UseShellExecute = false });
                Shutdown(0);
                return;
            }
            catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException
                                       || ex is System.ComponentModel.Win32Exception || ex is NotSupportedException)
            {
                // A full Temp folder, or antivirus refusing a program started from Temp: before 2026-11-12 this was a
                // crash dialog and nothing was removed. Uninstall from here instead; this exe (and the folder, once
                // empty) go a moment after it exits.
                SetupLog.Write($"couldn't hand over to a temporary copy ({ex.GetType().Name}: {ex.Message}); uninstalling in place");
                try { if (File.Exists(temp)) File.Delete(temp); } catch (Exception cleanup) when (cleanup is IOException || cleanup is UnauthorizedAccessException) { }
                RunUninstall(installDir, args, self, inPlace: true);
                return;
            }
        }

        SetupLog.Write($"Origin uninstall from temp copy, dir={args.Dir}");
        RunUninstall(args.Dir ?? Registration.FindExisting()?.Directory ?? InstallLocation.Default, args, self, inPlace: false);
    }

    /// <param name="inPlace">Running as the installed uninstall.exe itself (the temp hand-off failed): it removes
    /// itself and its folder after exit, and only when the uninstall really ran ("Keep Origin" keeps the uninstaller).</param>
    private void RunUninstall(string dir, SetupArgs args, string self, bool inPlace)
    {
        if (!inPlace) _deleteSelfOnExit = self; // the temp copy always goes, even when it just hands over below
        void AfterRun()
        {
            if (!inPlace) return;
            _deleteSelfOnExit = self;
            _removeDirIfEmptyOnExit = dir;
        }
        if (!ClaimSingleInstance(args.Silent))
        {
            Shutdown(args.Silent ? 1 : 0);
            return;
        }

        if (args.Silent)
        {
            bool ok;
            try { ok = Uninstaller.Run(dir, args.RemoveData, _ => { }, runningExe: inPlace ? self : null).Ok; }
            catch (Exception ex) { SetupLog.Write("silent uninstall failed: " + ex); ok = false; }
            AfterRun();
            Shutdown(ok ? 0 : 1);
            return;
        }

        var window = new UninstallWindow(dir) { RunningExe = inPlace ? self : null };
        window.Closed += (_, _) =>
        {
            if (window.Ran) AfterRun();
            Shutdown();
        };
        window.Show();
    }

    // Last-resort net: an error nothing else caught used to end in .NET's own crash dialog (a wall of text, the
    // worst first impression). It is logged; while files are being moved the window keeps going (closing would
    // kill the move halfway), otherwise the player gets one plain message and Setup closes.
    private void WatchForCrashes()
    {
        DispatcherUnhandledException += (_, e) =>
        {
            SetupLog.Write("unexpected error: " + e.Exception);
            e.Handled = true;
            if (MainWindow is SetupWindow { Busy: true } || MainWindow is UninstallWindow { Busy: true }) return;
            const string text = "Origin Setup ran into a problem it didn't expect and has to close. Run it again. If it keeps "
                                + "happening, send us the file OriginSetup.log from your Temp folder.";
            try
            {
                if (MainWindow is { IsVisible: true } owner)
                    MessageBox.Show(owner, text, "Origin Setup", MessageBoxButton.OK, MessageBoxImage.Warning);
                else
                    MessageBox.Show(text, "Origin Setup", MessageBoxButton.OK, MessageBoxImage.Warning);
            }
            catch (InvalidOperationException) { } // no message loop left to show it on
            Shutdown(1);
        };
        AppDomain.CurrentDomain.UnhandledException += (_, e) => SetupLog.Write("unexpected error (fatal): " + e.ExceptionObject);
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            SetupLog.Write("unobserved task error: " + e.Exception);
            e.SetObserved();
        };
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _instance?.Dispose();
        // The temporary uninstaller copy removes itself once this process is gone (and an in-place uninstall its
        // folder too, if nothing else is left in it: rmdir without /s never deletes a folder that has files).
        if (_deleteSelfOnExit != null)
        {
            try
            {
                var command = $"/c ping 127.0.0.1 -n 3 > nul & del /f /q \"{_deleteSelfOnExit}\"";
                if (_removeDirIfEmptyOnExit != null) command += $" & rmdir \"{_removeDirIfEmptyOnExit}\"";
                Process.Start(new ProcessStartInfo(FinishLaunch.CmdExe, command)
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                    WindowStyle = ProcessWindowStyle.Hidden,
                });
            }
            catch (System.ComponentModel.Win32Exception) { }
        }
        base.OnExit(e);
    }
}
