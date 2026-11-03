using System.Diagnostics;
using System.IO;
using System.Windows;

namespace OriginLauncher.App.Core.Updates;

// Windows: the win-x64 zip, swapped in by a cmd script after this process exits (a running exe
// can't overwrite itself), and WPF's own shutdown.
public static partial class UpdateService
{
    private const string ReleaseAssetName = AssetName;
    private const string ReleaseZipPrefix = "OriginLauncher-win-x64";

    // The changed-files list (tools/release/zip_manifest.py writes it from the final zip; launcher-release.yml publishes
    // it with its .sha256). Only Windows updates file by file: its zip unpacks to plain files the install folder holds
    // one for one. tools/release/check_consistency.py checks the workflow publishes exactly this name.
    private static readonly string? DeltaManifestName = "OriginLauncher-win-x64.files.json";

    private static void StartApplyScript(string stageDir, string updatesRoot)
    {
        var appDir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
        var exePath = Environment.ProcessPath
                      ?? Path.Combine(appDir, "OriginLauncher.App.exe");
        var pid = Environment.ProcessId;

        // robocopy instead of xcopy: copies new + changed files including
        // ones that appeared in this release, and its success exit codes
        // (0-7) don't abort the script. Paths are pre-trimmed of trailing
        // backslashes so the quoted arguments can't swallow a quote.
        var script = Path.Combine(updatesRoot, "apply-update.cmd");
        File.WriteAllText(script, $"""
@echo off
setlocal
:wait
tasklist /FI "PID eq {pid}" 2>nul | find "{pid}" >nul
if not errorlevel 1 (
  timeout /t 1 /nobreak >nul
  goto wait
)
robocopy "{stageDir}" "{appDir}" /E /IS /IT >nul
start "" "{exePath}"
endlocal
""");

        Process.Start(new ProcessStartInfo
        {
            FileName = script,
            UseShellExecute = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            CreateNoWindow = true
        });
    }

    private static void ShutdownApp() => Application.Current.Shutdown();

    // Nothing refuses an update up front on Windows: the swap script copies into the install folder after the exit.
    private static Task<string?> InstallBlockedHereAsync() => Task.FromResult<string?>(null);

    private static void ExtractUpdate(string zipPath, string stageDir) =>
        System.IO.Compression.ZipFile.ExtractToDirectory(zipPath, stageDir, overwriteFiles: true);
}
