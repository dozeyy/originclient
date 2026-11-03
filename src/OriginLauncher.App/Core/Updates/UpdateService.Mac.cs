using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core.Updates;

// Mac: the release asset for this build's architecture (an Apple Silicon build updates to the next
// Apple Silicon build), a zip holding one .app bundle; names in MacReleaseAssets. A running bundle
// can't be replaced file by file without breaking its code signature halfway, so a small sh script
// waits for this process to exit and swaps the whole bundle. launcher-release.yml's Mac jobs add
// these zips to a release after the Windows files; a release without this arch's zip (older ones,
// or one whose Mac upload failed) has no asset for CheckAsync, so nothing is offered or gated.
//
// Never broken (mandate 4): updates are mandatory, so a launcher that quits for an update and
// doesn't come back would block Play for good, and anything that fails after this process exits is
// invisible to UpdateGate. So everything that can be checked is checked BEFORE quitting
// (EnsureCanReplace; a throw there is counted by Gate.DownloadFailed, which ungates Play on the
// second failure), and the script reopens the app on every way out, swapped or not. A swap that
// failed anyway leaves a marker, so the next attempt at that version refuses before quitting too.
public static partial class UpdateService
{
    private static readonly string ReleaseZipPrefix = MacReleaseAssets.ZipPrefixFor(RuntimeInformation.ProcessArchitecture);

    internal static readonly string ReleaseAssetName = ReleaseZipPrefix + ".zip";

    // No changed-files updates on the Mac: the zip holds a signed .app whose symlinks and exec bits only ditto restores,
    // and the swap replaces the whole bundle. It still gets the background download (UpdateService.cs).
    private static readonly string? DeltaManifestName = null;

    /// <summary>How the app quits for the swap. The UI start replaces this with its own lifetime's shutdown.</summary>
    public static Action Shutdown { get; set; } = () => Environment.Exit(0);

    private static void ShutdownApp() => Shutdown();

    // ditto, not ZipFile: a signed .app carries symlinks (framework Versions/Current) and exec bits,
    // and .NET's zip reader writes a symlink as a plain file holding its target, which breaks the
    // signature. ditto is what made the zip (ditto -c -k --keepParent) and restores it exactly.
    internal static void ExtractUpdate(string zipPath, string stageDir)
    {
        var psi = new ProcessStartInfo("/usr/bin/ditto") { UseShellExecute = false, RedirectStandardError = true };
        psi.ArgumentList.Add("-x");
        psi.ArgumentList.Add("-k");
        psi.ArgumentList.Add(zipPath);
        psi.ArgumentList.Add(stageDir);
        using var p = Process.Start(psi) ?? throw new IOException("Could not start ditto to unpack the update.");
        var error = p.StandardError.ReadToEnd();
        p.WaitForExit();
        if (p.ExitCode != 0) throw new IOException($"Unpacking the update failed (ditto exit {p.ExitCode}): {error.Trim()}");
    }

    // Paths travel as script arguments ("$2".."$4"), never pasted into the script text, so no path
    // can change what the script does.
    internal const string ApplyScript = """
#!/bin/sh
# Origin Launcher self-update (Mac): wait for the launcher to quit, swap the app bundle, reopen it.
pid="$1"; stage="$2"; app="$3"; failed="$4"
old="$app.previous"; incoming="$app.incoming"
# Every way out reopens the launcher: the new one after a swap, the old one when anything failed.
trap 'open "$app"' EXIT
fail() { rm -rf "$incoming"; : > "$failed"; exit 1; }
while kill -0 "$pid" 2>/dev/null; do sleep 1; done
new=$(find "$stage" -maxdepth 1 -type d -name '*.app' | head -n 1)
[ -n "$new" ] || fail
rm -rf "$old" "$incoming"
# A leftover that couldn't be deleted would make the mv below nest the bundle inside it (an app that won't open):
# stop here instead, and the trap reopens the old app untouched.
[ ! -e "$incoming" ] && [ ! -e "$old" ] || fail
# Next to the app first: across volumes mv is a copy, and a half-done copy must never sit in the
# app's place. The two renames after it stay on one volume.
mv "$new" "$incoming" || fail
mv "$app" "$old" || fail
if mv "$incoming" "$app"; then rm -rf "$old"; rm -f "$failed"; exit 0; fi
rm -rf "$app"
mv "$old" "$app"
fail

""";

    private static void StartApplyScript(string stageDir, string updatesRoot)
    {
        var bundle = CurrentBundle() ?? throw new InvalidOperationException("Not running from an app bundle; nothing to update in place.");
        EnsureCanReplace(bundle);
        var failedMarker = FailedMarker(stageDir, updatesRoot);
        if (File.Exists(failedMarker))
            throw new IOException("Installing this update failed last time; not quitting to try the same swap again.");

        var script = Path.Combine(updatesRoot, "apply-update.sh");
        File.WriteAllText(script, ApplyScript);
        var psi = new ProcessStartInfo("/bin/sh") { UseShellExecute = false };
        psi.ArgumentList.Add(script);
        psi.ArgumentList.Add(Environment.ProcessId.ToString());
        psi.ArgumentList.Add(stageDir);
        psi.ArgumentList.Add(bundle);
        psi.ArgumentList.Add(failedMarker);
        Process.Start(psi);
    }

    // CheckAsync asks this before it offers an update: the reason this copy can't swap itself where it sits (the same
    // EnsureCanReplace the swap runs before quitting), or null. A translocated or unwritable app is told to move to
    // Applications instead of being offered something no click could install. One tiny probe file, created and gone, on
    // the pool (a sleeping or network disk must never stall the UI thread). A copy that isn't a bundle or is translocated
    // stays that way for the whole run, so that answer is kept and the folder is never probed again.
    private static string? _blockedForTheRun;

    private static Task<string?> InstallBlockedHereAsync()
    {
        if (_blockedForTheRun != null) return Task.FromResult<string?>(_blockedForTheRun);
        if (CurrentBundle() is not { } bundle)
            return Task.FromResult<string?>(_blockedForTheRun = "This copy of Origin Launcher isn't an app bundle, so it can't update itself. Install it from originclient.org.");
        if (IsTranslocated(bundle))
            return Task.FromResult<string?>(_blockedForTheRun = TranslocatedMessage);
        return Task.Run(() =>
        {
            try
            {
                EnsureCanReplace(bundle);
                return (string?)null;
            }
            catch (IOException e)
            {
                return e.Message;
            }
        });
    }

    private const string TranslocatedMessage =
        "Origin Launcher is running from a temporary copy macOS made. Move it to Applications, open it from there, and the update installs.";

    private static bool IsTranslocated(string bundle) => bundle.Contains("/AppTranslocation/", StringComparison.Ordinal);

    // One marker per version (the stage folder is named after it), so the next release tries afresh.
    internal static string FailedMarker(string stageDir, string updatesRoot) =>
        Path.Combine(updatesRoot, "apply-failed-" + Path.GetFileName(stageDir.TrimEnd('/')));

    /// <summary>
    /// Throws (IOException) when the swap could not work, so it fails while the launcher is still
    /// open: a translocated app (macOS runs a quarantined app opened from Downloads or a disk image
    /// from a random read-only copy, and moving that copy does nothing useful), or a folder this user
    /// can't write to (a read-only disk image, another user's /Applications).
    /// </summary>
    internal static void EnsureCanReplace(string bundle)
    {
        if (IsTranslocated(bundle))
            throw new IOException(TranslocatedMessage);
        var parent = Path.GetDirectoryName(bundle.TrimEnd('/'))
                     ?? throw new IOException("The app has no folder to update in.");
        var probe = Path.Combine(parent, ".origin-update-probe-" + Guid.NewGuid().ToString("N"));
        try
        {
            using (File.Create(probe, 1, FileOptions.DeleteOnClose)) { }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // /Applications itself, on a Mac user without admin rights: the fix is the Applications folder in their home.
            throw new IOException(parent.TrimEnd('/') == "/Applications"
                ? "Origin Launcher can't update itself in /Applications because this Mac user can't change that folder. Move it to the Applications folder in your home folder and open it from there."
                : $"Origin Launcher can't update itself in {parent} (that folder can't be written to). Move it to Applications and open it from there.", e);
        }
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
