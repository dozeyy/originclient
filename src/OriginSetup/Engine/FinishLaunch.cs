using System;
using System.Diagnostics;
using System.IO;

// Windows libraries Setup calls (dwmapi, shell32) load from System32 only, never from the folder Setup was
// downloaded to, where a planted DLL of the same name would otherwise be picked up first.
[assembly: System.Runtime.InteropServices.DefaultDllImportSearchPaths(System.Runtime.InteropServices.DllImportSearchPath.System32)]

namespace OriginSetup.Engine;

// How the finish page's "Launch Origin" button starts the launcher. The page has one optional switch,
// "Import from another Minecraft launcher": off unless the player turns it on, and only then does the launcher
// get exactly one argument, "--import" (the launcher then opens its import card; see OriginLauncher.App's
// App.OnStartup). With the switch off the launch is exactly what it always was: no arguments.
// Pure on purpose: SetupWindow only passes the switch's state in, so the rule is unit tested.
public static class FinishLaunch
{
    /// <summary>The one flag the launcher understands for this (App.xaml.cs reads the same literal).</summary>
    public const string ImportArgument = "--import";

    /// <summary>The finish page's import switch starts off; the XAML never checks it.</summary>
    public const bool ImportOnByDefault = false;

    public static string Arguments(bool importChecked) => importChecked ? ImportArgument : "";

    public static ProcessStartInfo StartInfo(string launcherExe, string workingDirectory, bool importChecked) =>
        new ProcessStartInfo(launcherExe, Arguments(importChecked))
        {
            UseShellExecute = true,
            WorkingDirectory = workingDirectory,
        };

    /// <summary>
    /// For a Setup started with "Run as administrator": a plain start would run the launcher (and
    /// Minecraft) as administrator too, so files it makes in the data folder belong to Administrators
    /// and Explorer drag-and-drop into it fails. Instead Explorer opens <paramref name="shortcut"/> (a
    /// shortcut to the launcher carrying the same arguments), and Explorer starts programs as the
    /// signed-in user, not elevated. The shortcut carries the arguments because Explorer passes none.
    /// </summary>
    public static ProcessStartInfo UnelevatedStartInfo(string shortcut) =>
        new ProcessStartInfo(ExplorerExe, "\"" + shortcut + "\"") { UseShellExecute = false };

    // Windows' own programs are always started by full path. A bare "explorer.exe" or "cmd.exe" is looked up in
    // Setup's own folder first, and Setup usually runs from Downloads: a file of that name dropped there by a
    // website would run instead (as administrator, when Setup was started that way).
    public static string ExplorerExe =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "explorer.exe");

    public static string CmdExe => Path.Combine(Environment.SystemDirectory, "cmd.exe");
}
