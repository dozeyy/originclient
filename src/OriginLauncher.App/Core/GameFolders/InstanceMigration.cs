using System.IO;
using System.Text.Json;

namespace OriginLauncher.App.Core.GameFolders;

// One-time move from the old layout (ONE game folder per version, everything at
// the top of instances/<version>/) to the per-account layout (InstanceLayout).
// The old folder becomes the game folder of the account that was selected when
// the launcher first ran this code — that choice is written to a marker file
// BEFORE anything moves, so every later pass (after a crash, a locked file, a
// launcher restart) finishes the job for the SAME account. The old mods/ is the
// exception: it stays where it is and simply becomes the version's shared mods
// folder (InstanceLayout.ModsFolder; SharedMods merges the accounts' into it).
//
// Safety rules:
//  * user data is only ever renamed, never deleted (the one delete is of a
//    folder this code just emptied by moving its contents);
//  * each step is one same-volume rename, so a crash leaves every entry either
//    at its old place or its new one — the next pass simply carries on;
//  * idempotent: once nothing but shared files is left at the top, a pass does
//    nothing;
//  * a version whose old-layout game is still running (it holds
//    logs/latest.log open) is left for a later pass rather than split in half.
public static class InstanceMigration
{
    /// <summary>The top-level entries of a version folder that belong in the account's game folder.</summary>
    public static IReadOnlyList<string> EntriesToMove(IEnumerable<string> topLevelNames) =>
        topLevelNames
            .Where(name => !InstanceLayout.IsSharedEntry(name))
            .OrderBy(name => name, StringComparer.OrdinalIgnoreCase)
            .ToList();

    /// <summary>True when the version folder still holds old-layout game data.</summary>
    public static bool NeedsMigration(string versionRoot)
    {
        try
        {
            return Directory.Exists(versionRoot) && EntriesToMove(TopLevelNames(versionRoot)).Count > 0;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    /// <summary>
    /// Moves every old-layout entry of <paramref name="versionRoot"/> into
    /// accounts/<paramref name="accountKey"/>/. Returns true when nothing is
    /// left to move. Never throws.
    /// </summary>
    public static bool MigrateVersion(string versionRoot, string accountKey, Action<string>? log = null)
    {
        log ??= _ => { };
        try
        {
            if (!Directory.Exists(versionRoot)) return true;
            var toMove = EntriesToMove(TopLevelNames(versionRoot));
            if (toMove.Count == 0) return true;

            if (IsOldLayoutGameRunning(versionRoot))
            {
                log($"{Path.GetFileName(versionRoot)}: its game is still running from the old folder - moving it later");
                return false;
            }

            var target = Path.Combine(versionRoot, InstanceLayout.AccountsFolder, accountKey);
            Directory.CreateDirectory(target);
            var complete = true;
            foreach (var name in toMove)
                complete &= MoveEntry(Path.Combine(versionRoot, name), Path.Combine(target, name), log);
            log($"{Path.GetFileName(versionRoot)}: moved into accounts/{accountKey}" + (complete ? "" : " (some files were busy - retried next start)"));
            return complete;
        }
        catch (Exception ex)
        {
            log($"{Path.GetFileName(versionRoot)}: migration paused: {ex.GetType().Name}: {ex.Message}");
            return false;
        }
    }

    // One entry: a plain rename when the destination is free; folders that
    // already exist on both sides (the account played this version before the
    // last pass could finish) are merged entry by entry; a file that exists on
    // both sides keeps the account's current copy and parks the old one next to
    // it as "<name>.before-accounts" — nothing is overwritten.
    private static bool MoveEntry(string source, string destination, Action<string> log)
    {
        try
        {
            var sourceIsDirectory = Directory.Exists(source);
            if (!sourceIsDirectory && !File.Exists(source)) return true; // already gone

            if (!Directory.Exists(destination) && !File.Exists(destination))
            {
                if (sourceIsDirectory) Directory.Move(source, destination);
                else File.Move(source, destination);
                return true;
            }

            if (sourceIsDirectory && Directory.Exists(destination))
            {
                var complete = true;
                foreach (var child in Directory.EnumerateFileSystemEntries(source).ToList())
                    complete &= MoveEntry(child, Path.Combine(destination, Path.GetFileName(child)), log);
                if (complete && !Directory.EnumerateFileSystemEntries(source).Any())
                    Directory.Delete(source); // emptied by the moves above
                return complete;
            }

            var aside = FreeName(destination + ".before-accounts");
            if (sourceIsDirectory) Directory.Move(source, aside);
            else File.Move(source, aside);
            log($"kept both copies of {Path.GetFileName(destination)} (the older one is {Path.GetFileName(aside)})");
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            log($"could not move {source} yet: {ex.Message}");
            return false;
        }
    }

    private static string FreeName(string path)
    {
        var candidate = path;
        for (var n = 2; Directory.Exists(candidate) || File.Exists(candidate); n++)
            candidate = $"{path}-{n}";
        return candidate;
    }

    private static IEnumerable<string> TopLevelNames(string versionRoot) =>
        Directory.EnumerateFileSystemEntries(versionRoot).Select(Path.GetFileName).OfType<string>().ToList();

    /// <summary>
    /// A running Minecraft keeps logs/latest.log open; asking for it with no
    /// sharing fails while it does. Same probe Settings Sync uses.
    /// </summary>
    public static bool IsGameRunningIn(string gameDir)
    {
        var latestLog = Path.Combine(gameDir, "logs", "latest.log");
        if (!File.Exists(latestLog)) return false;
        try
        {
            using var probe = new FileStream(latestLog, FileMode.Open, FileAccess.Read, FileShare.None);
            return false;
        }
        catch (IOException)
        {
            return true;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static bool IsOldLayoutGameRunning(string versionRoot) => IsGameRunningIn(versionRoot);

    // ---- the marker: which account inherited the old folders ------------------

    private sealed class Marker
    {
        public string? Account { get; set; }
    }

    /// <summary>The account key that inherits the old folders, or null when none was chosen yet.</summary>
    public static string? ReadMarker(string markerPath)
    {
        try
        {
            if (!File.Exists(markerPath)) return null;
            var key = JsonSerializer.Deserialize<Marker>(File.ReadAllText(markerPath))?.Account;
            return key != null && InstanceLayout.IsAccountKey(key) ? key : null;
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>
    /// The first-run move of every version's old single game folder into the inheriting account's. The inheriting
    /// account is the marker's, else <paramref name="candidateKey"/> (recorded in the marker before anything moves).
    /// Returns that account and whether every version finished, or (null, false) while no account is known yet.
    /// Throws when the marker can't be written; nothing has moved then, and the next call starts over.
    /// </summary>
    public static (string? Key, bool Complete) MigrateAll(string markerPath, IEnumerable<string> versionRoots,
        string? candidateKey, Action<string>? log = null)
    {
        var key = ReadMarker(markerPath);
        if (key == null)
        {
            if (candidateKey == null) return (null, false);
            key = candidateKey;
            Directory.CreateDirectory(Path.GetDirectoryName(markerPath)!);
            WriteMarker(markerPath, key);
            log?.Invoke($"old game folders go to account {key}");
        }

        var complete = true;
        foreach (var root in versionRoots)
            complete &= MigrateVersion(root, key, log);
        return (key, complete);
    }

    /// <summary>Records the inheriting account. Atomic (temp file + rename).</summary>
    /// <remarks>Runs on the UI thread (App startup, HomePage.RefreshAccountState), so a busy marker file fails at
    /// once rather than freezing the window while it waits; the migration then pauses and resumes on a later call.</remarks>
    public static void WriteMarker(string markerPath, string accountKey) =>
        Sync.AtomicFile.WriteAllText(markerPath, JsonSerializer.Serialize(new Marker { Account = accountKey }), waitOutReaders: false);
}
