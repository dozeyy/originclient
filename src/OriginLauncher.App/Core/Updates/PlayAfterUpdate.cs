using System.IO;
using System.Text.Json;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Updates;

/// <summary>
/// A Play that was refused for a required update and continues by itself once the updated launcher opens (bug run 5,
/// JL-5). Before: Play, "Update required", Update, the launcher restarts on Home, press Play again. Now the prompt's
/// "Update and play" leaves this note right before the launcher quits for the swap, and the next start launches the
/// same account and version (and server, for a Join) without a second click.
/// </summary>
/// <param name="AccountKey">The account folder key (InstanceLayout.KeyFor), the same one the refused launch used.</param>
/// <param name="Server">A friend's server the refused launch was joining; null for a plain Play.</param>
public sealed record PlayAfterUpdate(string AccountKey, string Version, string? Server = null);

/// <summary>
/// The note on disk (OriginPaths.PlayAfterUpdateMarker). Written only when the restart really goes ahead (next to
/// SwapAttempt.Record), read and removed by the next start. Pure: paths, versions and clocks are parameters.
/// </summary>
public static class PlayAfterUpdateMarker
{
    /// <summary>A note older than this is a restart the player has long forgotten about: never start a game from it.</summary>
    public static readonly TimeSpan MaxAge = TimeSpan.FromMinutes(15);

    private sealed record Note(string AccountKey, string Version, string? Server, string UpdateVersion, DateTime WrittenUtc);

    /// <summary>Right before the quit for the swap to <paramref name="updateVersion"/>. Throws if it can't be written.</summary>
    public static void Record(string path, PlayAfterUpdate play, Version updateVersion, DateTime nowUtc) =>
        AtomicFile.WriteAllText(path, JsonSerializer.Serialize(
            new Note(play.AccountKey, play.Version, play.Server, UpdateGate.Normalize(updateVersion).ToString(), nowUtc)));

    /// <summary>
    /// The Play to continue on this start, or null. Always removes the note, so it is acted on at most once. Null when
    /// there is none, it is unreadable, older than <see cref="MaxAge"/> (or from the future), or this launcher is still
    /// older than the update it was written for (the swap failed: the player is back where they were, Play as usual).
    /// Never throws.
    /// </summary>
    public static PlayAfterUpdate? Take(string path, Version running, DateTime nowUtc)
    {
        Note? note;
        try
        {
            if (!File.Exists(path)) return null;
            note = JsonSerializer.Deserialize<Note>(File.ReadAllText(path));
        }
        catch (Exception)
        {
            note = null;
        }
        Clear(path);
        if (note is null || string.IsNullOrWhiteSpace(note.AccountKey) || string.IsNullOrWhiteSpace(note.Version))
            return null;
        var age = nowUtc - note.WrittenUtc;
        if (age > MaxAge || age < -TimeSpan.FromMinutes(1)) return null;
        if (!Version.TryParse(note.UpdateVersion, out var target)
            || UpdateGate.Normalize(running) < UpdateGate.Normalize(target)) return null;
        return new PlayAfterUpdate(note.AccountKey, note.Version, note.Server);
    }

    public static void Clear(string path)
    {
        try { File.Delete(path); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
    }
}
