using System.Diagnostics;

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
}
