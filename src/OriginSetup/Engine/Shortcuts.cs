using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;

namespace OriginSetup.Engine;

// Start-menu and desktop shortcuts, via the Windows Shell's own ShellLink COM
// object (what every installer uses under the hood — no WScript dependency).
public static class Shortcuts
{
    public const string Name = "Origin Launcher.lnk";

    public static string StartMenuPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), Name);

    public static string DesktopPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Name);

    /// <summary>The Inno Setup installer's Start-menu group (with its own uninstall link) — removed on takeover.</summary>
    public static string LegacyStartMenuGroup =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Origin Launcher");

    /// <summary>
    /// Whether Setup puts the desktop shortcut down: always on a fresh install, and on an update or repair only when
    /// the player still has it (refreshed in place). A player who deleted the icon used to get it back on every Setup
    /// run (bug run 6, KD-8); their removal is the choice Setup remembers.
    /// </summary>
    public static bool WantsDesktop(bool updating, bool desktopShortcutExists) => !updating || desktopShortcutExists;

    public static void Create(string shortcutPath, string targetExe, string description, string arguments = "")
    {
        Directory.CreateDirectory(Path.GetDirectoryName(shortcutPath)!);
        var link = (IShellLinkW)new ShellLink();
        try
        {
            link.SetPath(targetExe);
            if (arguments.Length > 0) link.SetArguments(arguments);
            link.SetWorkingDirectory(Path.GetDirectoryName(targetExe)!);
            link.SetDescription(description);
            link.SetIconLocation(targetExe, 0);
            ((IPersistFile)link).Save(shortcutPath, true);
        }
        finally
        {
            Marshal.FinalReleaseComObject(link);
        }
    }

    public static void Delete(string shortcutPath)
    {
        try { if (File.Exists(shortcutPath)) File.Delete(shortcutPath); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    public static void DeleteLegacyGroup()
    {
        try { if (Directory.Exists(LegacyStartMenuGroup)) Directory.Delete(LegacyStartMenuGroup, recursive: true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    private class ShellLink { }

    // Method order is the COM vtable order — do not reorder.
    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
    private interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszFile, int cchMaxPath, IntPtr pfd, int fFlags);
        void GetIDList(out IntPtr ppidl);
        void SetIDList(IntPtr pidl);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszName, int cchMaxName);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string pszName);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszDir, int cchMaxPath);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string pszDir);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszArgs, int cchMaxPath);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string pszArgs);
        void GetHotkey(out short pwHotkey);
        void SetHotkey(short wHotkey);
        void GetShowCmd(out int piShowCmd);
        void SetShowCmd(int iShowCmd);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder pszIconPath, int cchIconPath, out int piIcon);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string pszIconPath, int iIcon);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string pszPathRel, int dwReserved);
        void Resolve(IntPtr hwnd, int fFlags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string pszFile);
    }
}
