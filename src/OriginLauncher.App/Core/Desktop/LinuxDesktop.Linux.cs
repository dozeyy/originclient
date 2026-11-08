using System.Diagnostics;

namespace OriginLauncher.App.Core.Desktop;

/// <summary>
/// What the launcher asks of the Linux desktop around its window: the "animations off" preference (ReduceMotion)
/// and a plain message box for the few failures that happen before
/// the launcher's own window exists. Each call is best effort and never throws.
/// </summary>
public static class LinuxDesktop
{
    /// <summary>True when the desktop asks for no animations (GNOME-family gsettings, or KDE's instant animation speed).
    /// One short gsettings run (1 s limit) before the window is built; a missing gsettings or kdeglobals reads as "on".</summary>
    public static bool ReducedMotion()
    {
        bool? gnome = null, kde = null;
        try { gnome = ReduceMotion.FromGnome(Run("gsettings", ["get", "org.gnome.desktop.interface", "enable-animations"], TimeSpan.FromSeconds(1))); }
        catch (Exception) { /* not a GNOME-family desktop */ }
        try
        {
            var config = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME") is { Length: > 0 } x
                ? x : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config");
            var file = Path.Combine(config, "kdeglobals");
            if (File.Exists(file)) kde = ReduceMotion.FromKdeGlobals(File.ReadAllText(file));
        }
        catch (Exception) { /* unreadable: animations stay on */ }
        return ReduceMotion.Combine(gnome, kde);
    }

    /// <summary>
    /// A plain error box for a failure before (or instead of) the launcher's window: zenity (GNOME and most GTK
    /// desktops), else kdialog (KDE), else a desktop notification, else only stderr. Waits until the box is closed.
    /// </summary>
    public static void ShowError(string title, string text)
    {
        Console.Error.WriteLine(title + ": " + text);
        var wait = TimeSpan.FromMinutes(10);
        if (TryRun("zenity", ["--error", "--title", title, "--text", text, "--no-markup", "--width", "420"], wait)) return;
        if (TryRun("kdialog", ["--title", title, "--error", text], wait)) return;
        TryRun("notify-send", ["--urgency=critical", "--app-name=Origin Launcher", title, text], TimeSpan.FromSeconds(5));
    }

    private static bool TryRun(string file, string[] args, TimeSpan limit)
    {
        try
        {
            Run(file, args, limit);
            return true;
        }
        catch (Exception)
        {
            return false; // not installed, or no display for it either
        }
    }

    // Runs a program by name (PATH) with its arguments as a list (never a shell line), returns its stdout. Throws when it
    // can't start or exits non-zero; kills it at the limit.
    private static string Run(string file, string[] args, TimeSpan limit)
    {
        var info = new ProcessStartInfo(file)
        {
            RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true,
        };
        foreach (var a in args) info.ArgumentList.Add(a);
        using var p = Process.Start(info) ?? throw new InvalidOperationException(file + " did not start");
        var output = p.StandardOutput.ReadToEndAsync();
        _ = p.StandardError.ReadToEndAsync();
        if (!p.WaitForExit(limit))
        {
            try { p.Kill(entireProcessTree: true); } catch (Exception) { }
            throw new TimeoutException(file + " took too long");
        }
        if (p.ExitCode != 0) throw new InvalidOperationException(file + " exited " + p.ExitCode);
        return output.Result;
    }
}
