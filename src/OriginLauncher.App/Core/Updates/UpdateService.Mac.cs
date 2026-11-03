using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core.Updates;

// Mac: the release asset for this build's architecture (an Apple Silicon build updates to the next
// Apple Silicon build), a zip holding one .app bundle. A running bundle can't be replaced file by
// file without breaking its code signature halfway, so a small sh script waits for this process to
// exit, moves the old bundle aside, moves the new one into the same place (renames on one volume),
// puts the old one back if that fails, and reopens the app. No release publishes these assets yet:
// until one does, CheckAsync finds no asset and nothing is offered.
public static partial class UpdateService
{
    private static readonly string ReleaseZipPrefix =
        RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "OriginLauncher-mac-arm64" : "OriginLauncher-mac-x64";

    internal static readonly string ReleaseAssetName = ReleaseZipPrefix + ".zip";

    /// <summary>How the app quits for the swap. The UI start replaces this with its own lifetime's shutdown.</summary>
    public static Action Shutdown { get; set; } = () => Environment.Exit(0);

    private static void ShutdownApp() => Shutdown();

    // Paths travel as script arguments ("$2", "$3"), never pasted into the script text, so no path
    // can change what the script does.
    internal const string ApplyScript = """
#!/bin/sh
# Origin Launcher self-update (Mac): wait for the launcher to quit, swap the app bundle, reopen it.
pid="$1"; stage="$2"; app="$3"
while kill -0 "$pid" 2>/dev/null; do sleep 1; done
new=$(find "$stage" -maxdepth 1 -type d -name '*.app' | head -n 1)
[ -n "$new" ] || exit 1
old="$app.previous"
rm -rf "$old"
mv "$app" "$old" || exit 1
if mv "$new" "$app"; then rm -rf "$old"; else mv "$old" "$app"; fi
open "$app"

""";

    private static void StartApplyScript(string stageDir, string updatesRoot)
    {
        var bundle = CurrentBundle() ?? throw new InvalidOperationException("Not running from an app bundle; nothing to update in place.");
        var script = Path.Combine(updatesRoot, "apply-update.sh");
        File.WriteAllText(script, ApplyScript);
        var psi = new ProcessStartInfo("/bin/sh") { UseShellExecute = false };
        psi.ArgumentList.Add(script);
        psi.ArgumentList.Add(Environment.ProcessId.ToString());
        psi.ArgumentList.Add(stageDir);
        psi.ArgumentList.Add(bundle);
        Process.Start(psi);
    }

    // X.app when this process runs from X.app/Contents/MacOS, else null.
    private static string? CurrentBundle()
    {
        var macOs = new DirectoryInfo(AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar));
        var app = macOs.Parent?.Parent;
        return macOs.Name == "MacOS" && macOs.Parent?.Name == "Contents"
               && app != null && app.Name.EndsWith(".app", StringComparison.OrdinalIgnoreCase) ? app.FullName : null;
    }
}
