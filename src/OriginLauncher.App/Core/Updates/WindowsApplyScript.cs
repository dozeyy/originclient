using System.Diagnostics;
using System.IO;

namespace OriginLauncher.App.Core.Updates;

/// <summary>
/// The Windows self-update swap: a cmd script that waits for the launcher to quit, copies the staged release over the
/// install folder and starts the new exe (a running exe can't overwrite itself). Pure (string + ProcessStartInfo) so
/// its text is unit-tested on every OS.
///
/// No path is ever written into the script (SEC-1): cmd.exe reads a .cmd file in the console's OEM code page (437, 850,
/// 866, 936...), so a UTF-8 path with "ü", "И" or "李" in the user folder pointed robocopy and start at folders that
/// don't exist, and a "%" in a path was expanded as a variable. The script is plain ASCII and reads the paths from
/// environment variables: they reach cmd as UTF-16, and a variable's value is never expanded a second time, so any
/// letter or "%" stays exactly as it is. The script is started by its bare name from its own folder, so no path is on
/// the command line either.
/// </summary>
public static class WindowsApplyScript
{
    public const string FileName = "apply-update.cmd";

    public const string PidVariable = "ORIGIN_UPDATE_PID";
    public const string StageVariable = "ORIGIN_UPDATE_STAGE";
    public const string AppVariable = "ORIGIN_UPDATE_APP";
    public const string ExeVariable = "ORIGIN_UPDATE_EXE";

    // robocopy instead of xcopy: copies new + changed files including ones that appeared in this release, and its
    // success exit codes (0-7) don't abort the script. ping, not timeout: timeout refuses to run without an
    // interactive console. The paths are only used outside the if block, so a ")" in one can't end the block.
    public const string Text =
        "@echo off\r\n" +
        "setlocal\r\n" +
        ":wait\r\n" +
        "tasklist /FI \"PID eq %" + PidVariable + "%\" 2>nul | find \"%" + PidVariable + "%\" >nul\r\n" +
        "if not errorlevel 1 (\r\n" +
        "  ping -n 2 127.0.0.1 >nul\r\n" +
        "  goto wait\r\n" +
        ")\r\n" +
        "robocopy \"%" + StageVariable + "%\" \"%" + AppVariable + "%\" /E /IS /IT >nul\r\n" +
        "start \"\" \"%" + ExeVariable + "%\"\r\n" +
        "endlocal\r\n";

    /// <summary>
    /// Writes the script into <paramref name="updatesRoot"/> (ASCII only) and returns how to start it: cmd.exe on the
    /// script's bare name, run from that folder, with the four values in its environment and no window.
    /// </summary>
    public static ProcessStartInfo Prepare(string updatesRoot, int pid, string stageDir, string appDir, string exePath)
    {
        File.WriteAllText(Path.Combine(updatesRoot, FileName), Text, System.Text.Encoding.ASCII);
        return StartInfo(updatesRoot, pid, stageDir, appDir, exePath);
    }

    public static ProcessStartInfo StartInfo(string updatesRoot, int pid, string stageDir, string appDir, string exePath)
    {
        var cmd = Environment.GetEnvironmentVariable("ComSpec");
        var psi = new ProcessStartInfo
        {
            FileName = string.IsNullOrEmpty(cmd) ? Path.Combine(Environment.SystemDirectory, "cmd.exe") : cmd,
            // /d: no AutoRun commands from the registry. The bare name resolves in the working directory.
            Arguments = "/d /c " + FileName,
            WorkingDirectory = updatesRoot,
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        // Trailing separators trimmed: robocopy would read `\"` at the end of a quoted folder as an escaped quote.
        psi.Environment[PidVariable] = pid.ToString(System.Globalization.CultureInfo.InvariantCulture);
        psi.Environment[StageVariable] = stageDir.TrimEnd('\\', '/');
        psi.Environment[AppVariable] = appDir.TrimEnd('\\', '/');
        psi.Environment[ExeVariable] = exePath;
        return psi;
    }
}
