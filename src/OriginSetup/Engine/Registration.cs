using Microsoft.Win32;
using OriginLauncher.App.Core.Install;

namespace OriginSetup.Engine;

// The Add/Remove Programs entry (per user, HKCU). Key name and AppId come
// from the launcher's InstallRegistry, shared with the launcher, which keeps
// DisplayVersion current after self-updates.
public static class Registration
{
    public sealed class ExistingInstall
    {
        public string Directory { get; }
        public string? Version { get; }
        /// <summary>Installed by the old Inno Setup installer (it leaves unins000.exe behind).</summary>
        public bool IsLegacyInno { get; }

        public ExistingInstall(string directory, string? version, bool isLegacyInno)
        {
            Directory = directory;
            Version = version;
            IsLegacyInno = isLegacyInno;
        }
    }

    /// <summary>The current install, from its registry entry, else the default folder; null if none.</summary>
    public static ExistingInstall? FindExisting()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(InstallRegistry.UninstallKeyPath);
            if (key?.GetValue("InstallLocation") is string loc && !string.IsNullOrWhiteSpace(loc))
            {
                var dir = loc.TrimEnd('\\', '/');
                if (InstallLocation.IsOriginInstall(dir))
                    return new ExistingInstall(dir, key.GetValue("DisplayVersion") as string,
                        File.Exists(Path.Combine(dir, "unins000.exe")));
            }
        }
        catch (Exception ex) when (ex is System.Security.SecurityException || ex is UnauthorizedAccessException || ex is IOException) { }

        var fallback = InstallLocation.Default;
        return InstallLocation.IsOriginInstall(fallback)
            ? new ExistingInstall(fallback, null, File.Exists(Path.Combine(fallback, "unins000.exe")))
            : null;
    }

    public static void Write(string installDir, string version)
    {
        // Start clean: an Inno-written key carries Inno-only values that point
        // at its (now deleted) unins000.exe.
        Registry.CurrentUser.DeleteSubKeyTree(InstallRegistry.UninstallKeyPath, throwOnMissingSubKey: false);
        using var key = Registry.CurrentUser.CreateSubKey(InstallRegistry.UninstallKeyPath);
        var exe = Path.Combine(installDir, InstallLocation.LauncherExe);
        var uninstaller = Path.Combine(installDir, InstallLocation.UninstallerExe);
        key.SetValue("DisplayName", "Origin Launcher");
        key.SetValue("DisplayVersion", version);
        key.SetValue("Publisher", "Will Henry");
        key.SetValue("DisplayIcon", exe);
        key.SetValue("InstallLocation", installDir + "\\");
        key.SetValue("UninstallString", $"\"{uninstaller}\" --uninstall");
        key.SetValue("QuietUninstallString", $"\"{uninstaller}\" --uninstall --silent");
        key.SetValue("URLInfoAbout", "https://github.com/dozeyy/originclient");
        key.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
        key.SetValue("EstimatedSize", (int)Math.Min(int.MaxValue, DirectorySizeKb(installDir)), RegistryValueKind.DWord);
        key.SetValue("NoModify", 1, RegistryValueKind.DWord);
        key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
    }

    public static void Remove() =>
        Registry.CurrentUser.DeleteSubKeyTree(InstallRegistry.UninstallKeyPath, throwOnMissingSubKey: false);

    private static long DirectorySizeKb(string dir) =>
        Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories).Sum(f => new FileInfo(f).Length) / 1024;
}
