using Microsoft.Win32;

namespace OriginLauncher.App.Core.Install;

// The per-user registry entries an Origin install owns. Shared by the
// installer (src/OriginSetup links this file — keep it .NET Framework 4.7.2
// compatible) and the launcher, so both always agree on names:
//  - the Add/Remove Programs entry. Its key name is the one the old Inno Setup
//    installer used ("{AppId}_is1"), so installing with the new setup takes
//    over an existing Inno install's entry instead of leaving a duplicate.
//  - the "Start with Windows" Run value (installer option + Settings toggle).
// Everything is under HKEY_CURRENT_USER: Origin installs per user, no admin.
public static class InstallRegistry
{
    public const string AppId = "{7E9C1A54-2C3B-4E7A-9C1D-0A1B2C3D4E5F}";
    public const string UninstallKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\" + AppId + "_is1";
    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValueName = "Origin Launcher";

    public static bool IsStartWithWindows()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
        return key?.GetValue(RunValueName) is string;
    }

    /// <summary>Turns "open Origin when Windows starts" on (for exePath) or off.</summary>
    public static void SetStartWithWindows(bool enabled, string exePath)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath);
        if (enabled)
            key.SetValue(RunValueName, "\"" + exePath + "\"");
        else if (key.GetValue(RunValueName) != null)
            key.DeleteValue(RunValueName, throwOnMissingValue: false);
    }

    /// <summary>
    /// Keeps Add/Remove Programs showing the running version: the launcher
    /// self-updates from a zip, which never touches the entry the installer
    /// wrote. No-op when Origin was not installed by a setup.exe.
    /// </summary>
    public static void RefreshDisplayVersion(string version)
    {
        using var key = Registry.CurrentUser.OpenSubKey(UninstallKeyPath, writable: true);
        if (key != null && !Equals(key.GetValue("DisplayVersion"), version))
            key.SetValue("DisplayVersion", version);
    }
}
