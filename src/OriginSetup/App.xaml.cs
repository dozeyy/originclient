using System.Diagnostics;
using System.Reflection;
using System.Threading;
using System.Windows;
using OriginSetup.Engine;
using OriginSetup.UI;

namespace OriginSetup;

public partial class App : Application
{
    private string? _deleteSelfOnExit;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

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

    private void StartInstall(SetupArgs args, string self)
    {
        SetupLog.Start($"Origin Setup {string.Join(" ", Environment.GetCommandLineArgs().Skip(1))}");
        Payload? payload = null;
        string? problem = null;
        try
        {
            payload = Payload.TryOpen(self);
            if (payload == null)
                problem = "This setup file doesn't contain Origin. Download OriginLauncher-Setup.exe again from the Origin website.";
        }
        catch (Exception ex) when (ex is IOException || ex is InvalidDataException || ex is UnauthorizedAccessException)
        {
            SetupLog.Write("payload: " + ex);
            problem = "This setup file is damaged or incomplete. Download OriginLauncher-Setup.exe again.";
        }

        if (args.Silent)
        {
            Shutdown(problem == null ? RunSilentInstall(payload!, self, args) : 2);
            return;
        }
        if (args.RenderTour != null && payload != null)
        {
            RenderTour.Run(args.RenderTour, payload, self);
            Shutdown(0);
            return;
        }

        var window = new SetupWindow(payload, self, problem);
        window.Closed += (_, _) => Shutdown();
        window.Show();
    }

    private static int RunSilentInstall(Payload payload, string self, SetupArgs args)
    {
        try
        {
            var existing = Registration.FindExisting();
            var options = new InstallOptions
            {
                Directory = args.Dir != null ? InstallLocation.Resolve(args.Dir) : existing?.Directory ?? InstallLocation.Default,
                StartWithWindows = args.StartWithWindows,
            };
            LauncherProcess.Close(TimeSpan.FromSeconds(5));
            Installer.Run(payload, self, options, (stage, f) => { }, CancellationToken.None);
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
            var installDir = args.Dir ?? Path.GetDirectoryName(self)!;
            var temp = Path.Combine(Path.GetTempPath(), $"OriginUninstall-{Guid.NewGuid():N}.exe");
            File.Copy(self, temp);
            var forward = $"--uninstall --from-temp --dir \"{installDir.TrimEnd('\\')}\""
                          + (args.Silent ? " --silent" : "") + (args.RemoveData ? " --remove-data" : "");
            Process.Start(new ProcessStartInfo(temp, forward) { UseShellExecute = false });
            Shutdown(0);
            return;
        }

        SetupLog.Write($"Origin uninstall from temp copy, dir={args.Dir}");
        _deleteSelfOnExit = self;
        var dir = args.Dir ?? Registration.FindExisting()?.Directory ?? InstallLocation.Default;

        if (args.Silent)
        {
            bool ok;
            try { ok = Uninstaller.Run(dir, args.RemoveData, _ => { }); }
            catch (Exception ex) { SetupLog.Write("silent uninstall failed: " + ex); ok = false; }
            Shutdown(ok ? 0 : 1);
            return;
        }

        var window = new UninstallWindow(dir);
        window.Closed += (_, _) => Shutdown();
        window.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        // The temporary uninstaller copy removes itself once this process is gone.
        if (_deleteSelfOnExit != null)
        {
            try
            {
                Process.Start(new ProcessStartInfo("cmd.exe",
                    $"/c ping 127.0.0.1 -n 3 > nul & del /f /q \"{_deleteSelfOnExit}\"")
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
