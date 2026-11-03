using System.IO;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Updates;

// The one thing the update gate must remember across a restart: "this launcher quit to swap in version X".
// The swap runs in a script after the launcher has quit (UpdateService.<os>.cs), so when it fails there (disk
// full during the copy, a sticky-bit folder, robocopy refused) nothing in the process that wanted the update is
// left to count the failure. The script starts the old launcher again, whose fresh gate would offer X, gate Play,
// download, quit and fail again, at every start, for good (mandate 4: never broken). So the marker is written
// before the quit, and the next start reads it:
//  - still older than X: the swap failed. The update is released at once with the usual quiet back-off
//    (UpdateGate.SwapFailed), so Play works. The marker stays until a start runs X or newer, so every later start
//    also opens unblocked, and the quiet path retries X with back-off instead of in front of the player.
//  - X or newer: the swap worked (or a newer build came another way); the marker is removed.
// Pure (the marker path and versions are parameters) so the tests can drive it.
public static class SwapAttempt
{
    public enum Outcome
    {
        /// <summary>No swap was attempted (no marker, or an unreadable one, which is removed).</summary>
        None,
        /// <summary>This process runs the version the swap installed, or a newer one; the marker is removed.</summary>
        Applied,
        /// <summary>This process is still older than the version the swap was meant to install.</summary>
        Failed
    }

    /// <summary>Written right before the launcher quits for the swap. Throws if it can't be written.</summary>
    public static void Record(string markerPath, Version target) =>
        AtomicFile.WriteAllText(markerPath, UpdateGate.Normalize(target).ToString());

    /// <summary>
    /// What the last swap did, judged from the running version. <paramref name="attempted"/> is the version
    /// the marker names (null for <see cref="Outcome.None"/>). Never throws.
    /// </summary>
    public static Outcome Check(string markerPath, Version current, out Version? attempted)
    {
        attempted = null;
        string text;
        try
        {
            if (!File.Exists(markerPath)) return Outcome.None;
            text = File.ReadAllText(markerPath);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return Outcome.None;
        }
        if (!Version.TryParse(text.Trim(), out var target))
        {
            Clear(markerPath);
            return Outcome.None;
        }
        if (UpdateGate.Normalize(current) >= UpdateGate.Normalize(target))
        {
            Clear(markerPath);
            return Outcome.Applied;
        }
        attempted = target;
        return Outcome.Failed;
    }

    /// <summary>Removes the marker. Never throws.</summary>
    public static void Clear(string markerPath)
    {
        try { File.Delete(markerPath); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
    }
}
