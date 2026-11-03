using System.IO;
using System.Text;

namespace OriginLauncher.App.Core.Install;

// Linux counterpart of the per-user registry entries. Same class and method names as the Windows
// file so shared callers (the Settings "start with your computer" toggle) need no OS checks.
//  - "Start with Windows" = an XDG autostart entry, $XDG_CONFIG_HOME/autostart/<id>.desktop
//    (default ~/.config/autostart). GNOME, KDE, XFCE, Cinnamon, MATE and LXQt all start every
//    entry there at login, so writing or deleting the file is the whole switch (no admin, nothing
//    starts right now).
//  - Run from an AppImage, the executable lives in a mount that gets a new random path every run
//    (/tmp/.mount_XXXX), so the entry points at the .AppImage file itself. A .deb/.rpm, or the
//    per-user copy its self-update installed, points at /usr/bin/origin-launcher, which starts the
//    newer of the two. Both rules: LinuxInstall.LoginTarget (Core/Install/LinuxInstall.Linux.cs).
//  - No Add/Remove Programs: the display-version refresh is a no-op.
public static class InstallRegistry
{
    public const string AppId = "{7E9C1A54-2C3B-4E7A-9C1D-0A1B2C3D4E5F}";
    internal const string DesktopId = "org.originclient.launcher";

    private static string? _autostartDir;

    // The self-test points this at a temp folder; the launcher always uses the real one.
    internal static string AutostartDir
    {
        get => _autostartDir ?? Path.Combine(OriginPaths.XdgDir("XDG_CONFIG_HOME", ".config"), "autostart");
        set => _autostartDir = value;
    }

    internal static string DesktopFilePath => Path.Combine(AutostartDir, DesktopId + ".desktop");

    public static bool IsStartWithWindows() => File.Exists(DesktopFilePath);

    /// <summary>Turns "open Origin when you log in" on (for exePath) or off.</summary>
    public static void SetStartWithWindows(bool enabled, string exePath)
    {
        if (!enabled)
        {
            if (File.Exists(DesktopFilePath)) File.Delete(DesktopFilePath);
            return;
        }
        Directory.CreateDirectory(AutostartDir);
        var target = LinuxInstall.LoginTarget(exePath, LinuxInstall.AppImage, OriginPaths.UserAppCopy, File.Exists);
        var entry = $"""
[Desktop Entry]
Type=Application
Name=Origin Launcher
Comment=Opens Origin Launcher when you log in
Exec={ExecQuote(target)}
Terminal=false
X-GNOME-Autostart-enabled=true

""";
        // Written beside and renamed in, so the session never reads half a file.
        var temp = DesktopFilePath + ".tmp";
        File.WriteAllText(temp, entry, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
        File.Move(temp, DesktopFilePath, overwrite: true);
    }

    /// <summary>No Add/Remove Programs on Linux: nothing to keep in step.</summary>
    public static void RefreshDisplayVersion(string version)
    {
    }

    /// <summary>
    /// One Exec= argument per the Desktop Entry spec: always double-quoted (so spaces and reserved
    /// characters are literal); inside the quotes ", `, $ and \ take a backslash, and a literal %
    /// is written %% (field codes). Then the whole value gets the spec's string escaping, which
    /// doubles every backslash once more. A line break can't be in a path Origin runs from.
    /// </summary>
    internal static string ExecQuote(string path)
    {
        var quoted = new StringBuilder("\"");
        foreach (var c in path)
        {
            if (c is '"' or '`' or '$' or '\\') quoted.Append('\\');
            if (c == '%') quoted.Append('%');
            quoted.Append(c);
        }
        quoted.Append('"');
        return quoted.ToString().Replace("\\", "\\\\");
    }
}
