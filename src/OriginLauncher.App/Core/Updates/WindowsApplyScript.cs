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

    /// <summary>
    /// The script holds this file (in its own folder, the updates folder) open for writing from the moment it starts
    /// until the copy is over. A launcher start (App.OnStartup) or Setup that finds it held knows a swap is copying
    /// into the install folder: the launcher bows out (the script opens the new one when it's done), Setup waits.
    /// Without it, a double-click on "Restarting" started the OLD exe, locked its files, and that start's stale-download
    /// cleanup deleted the stage the copy was still reading from (UPG-1): a half-old, half-new install.
    /// </summary>
    public const string LockFileName = "apply-update.lock";

    // robocopy instead of xcopy: copies new + changed files including ones that appeared in this release, and its
    // success exit codes (0-7) don't abort the script. ping, not timeout: timeout refuses to run without an
    // interactive console. No if-blocks: a path is only ever expanded on a line of its own, so a ")" in one can't end
    // a block. DisableDelayedExpansion: a registry default of DelayedExpansion=1 would strip a "!" from a user folder.
    //
    // Every wait is bounded (UPG-1): robocopy's own defaults are a million retries 30 s apart in a hidden window, so
    // one busy file (antivirus scanning it, a second launcher) meant Origin never came back.
    //  - the old launcher gets ~60 s to quit; if it is still there, nothing is copied over it (it would only half work);
    //  - /R:5 /W:1 retries a busy file for a few seconds, then robocopy moves on and reports failure (exit 8+);
    //  - a failed pass gets one more whole pass after a short pause, which fills in whatever an antivirus scan held;
    //  - the new (or, if nothing could be copied, the old) launcher is always started at the end. A copy that still
    //    failed is caught by the next start (SwapAttempt: Play opens, the update retries quietly and swaps again).
    // The lock (9> on the call) is released before "start", so the launcher it opens doesn't see a swap running.
    public const string Text =
        "@echo off\r\n" +
        "setlocal DisableDelayedExpansion\r\n" +
        "call :swap 9>\"" + LockFileName + "\"\r\n" +
        "start \"\" \"%" + ExeVariable + "%\"\r\n" +
        "endlocal\r\n" +
        "exit /b 0\r\n" +
        ":swap\r\n" +
        "set /a waited=0\r\n" +
        ":wait\r\n" +
        "tasklist /FI \"PID eq %" + PidVariable + "%\" 2>nul | find \"%" + PidVariable + "%\" >nul\r\n" +
        "if errorlevel 1 goto copy\r\n" +
        "set /a waited+=1\r\n" +
        "if %waited% geq " + QuitWaitSeconds + " exit /b 1\r\n" +
        "ping -n 2 127.0.0.1 >nul\r\n" +
        "goto wait\r\n" +
        ":copy\r\n" +
        Robocopy +
        "if not errorlevel 8 exit /b 0\r\n" +
        "ping -n 4 127.0.0.1 >nul\r\n" +
        Robocopy +
        "exit /b 0\r\n";

    /// <summary>How many one-second checks the script makes for the old launcher to quit (text: it goes into the script).</summary>
    public const string QuitWaitSeconds = "60";

    private const string Robocopy =
        "robocopy \"%" + StageVariable + "%\" \"%" + AppVariable + "%\" /E /IS /IT /R:5 /W:1 >nul\r\n";

    /// <summary>
    /// True while a swap script holds <see cref="LockFileName"/> in <paramref name="updatesRoot"/>, i.e. it is
    /// copying the new release into the install folder right now. A read-only open that refuses other writers fails
    /// exactly while someone has the file open for writing; a leftover lock file nobody holds reads as false.
    /// Never throws.
    /// </summary>
    public static bool IsSwapRunning(string updatesRoot)
    {
        try
        {
            var path = Path.Combine(updatesRoot, LockFileName);
            if (!File.Exists(path)) return false; // the usual start: no exception on the cold-start path
            using var _ = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            return false;
        }
        catch (FileNotFoundException) { return false; }
        catch (DirectoryNotFoundException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
        catch (IOException) { return true; } // sharing violation: the script has it open
    }

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
            // /d: no AutoRun commands from the registry. ".\\" because cmd skips the working directory for bare names
            // when NoDefaultCurrentDirectoryInExePath is set (locked-down PCs); the launcher has already quit by then.
            Arguments = "/d /c .\\" + FileName,
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
