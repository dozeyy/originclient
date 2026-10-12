using System.IO;

namespace OriginLauncher.App.Core.GameFolders;

// A brand-new account game folder (an account's first launch of a version) is
// seeded with another account's SETTINGS for that version — keybinds, video
// settings, the server list, mod configs (Origin's originclient*.json, Sodium,
// Iris, ...) — so it doesn't start bare. Worlds, screenshots, logs, resource and
// shader packs and mods are NOT copied: those stay with the account that has
// them (the launcher provisions Origin's own mods into every folder at launch).
//
// Crash-safe: the copy is built in a temporary sibling folder and renamed into
// place in one step, so a game folder is either fully seeded or absent.
public static class AccountSeed
{
    // Top-level settings files. options.txt + servers.dat are vanilla; the
    // optionsof/optionsshaders pair is OptiFine's (legacy versions).
    private static readonly HashSet<string> TopLevelFiles = new(StringComparer.OrdinalIgnoreCase)
    {
        "options.txt", "servers.dat", "optionsof.txt", "optionsshaders.txt",
    };

    private const string SeedingPrefix = ".seeding-";

    /// <summary>True for a path (relative to a game folder, either slash) that is a setting to seed.</summary>
    public static bool IsSettingsFile(string relativePath)
    {
        var path = relativePath.Replace('\\', '/').TrimStart('/');
        if (path.Length == 0) return false;
        var name = path[(path.LastIndexOf('/') + 1)..];
        // Half-written siblings that mods/Settings Sync write atomically.
        if (name.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase)) return false;
        if (!path.Contains('/')) return TopLevelFiles.Contains(path);
        return path.StartsWith("config/", StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Every settings file in <paramref name="sourceGameDir"/>, as '/'-separated relative paths, sorted.</summary>
    public static IReadOnlyList<string> FilesToSeed(string sourceGameDir)
    {
        var files = new List<string>();
        try
        {
            if (!Directory.Exists(sourceGameDir)) return files;
            foreach (var file in Directory.EnumerateFiles(sourceGameDir))
                Add(file);
            var config = Path.Combine(sourceGameDir, "config");
            if (Directory.Exists(config))
                foreach (var file in Directory.EnumerateFiles(config, "*", SearchOption.AllDirectories))
                    Add(file);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // an unreadable source just seeds less
        }
        files.Sort(StringComparer.Ordinal);
        return files;

        void Add(string file)
        {
            var relative = Path.GetRelativePath(sourceGameDir, file).Replace('\\', '/');
            if (IsSettingsFile(relative)) files.Add(relative);
        }
    }

    /// <summary>
    /// The folder to seed from: among the other accounts' game folders for the
    /// same version, the one whose options.txt changed most recently (the
    /// player's latest settings); null when none has one.
    /// </summary>
    public static string? PickSource(IEnumerable<string> candidateGameDirs)
    {
        string? best = null;
        var bestTime = DateTime.MinValue;
        foreach (var dir in candidateGameDirs)
        {
            try
            {
                var options = Path.Combine(dir, "options.txt");
                if (!File.Exists(options)) continue;
                var time = File.GetLastWriteTimeUtc(options);
                if (best == null || time > bestTime)
                {
                    best = dir;
                    bestTime = time;
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // skip an unreadable candidate
            }
        }
        return best;
    }

    /// <summary>
    /// Creates <paramref name="gameDir"/> holding a copy of
    /// <paramref name="sourceGameDir"/>'s settings (or empty when there is no
    /// source). Does nothing when the folder already exists. Returns true when
    /// it created the folder. Never throws.
    /// </summary>
    public static bool Seed(string gameDir, string? sourceGameDir, Action<string>? log = null)
    {
        log ??= _ => { };
        if (Directory.Exists(gameDir)) return false;

        var parent = Path.GetDirectoryName(gameDir)!;
        var key = Path.GetFileName(gameDir);
        string? temp = null;
        try
        {
            Directory.CreateDirectory(parent);
            CleanAbandonedSeeds(parent, key);

            if (sourceGameDir == null || !Directory.Exists(sourceGameDir))
            {
                Directory.CreateDirectory(gameDir);
                return true;
            }

            temp = Path.Combine(parent, $"{SeedingPrefix}{key}-{Guid.NewGuid():N}");
            Directory.CreateDirectory(temp);
            var copied = 0;
            foreach (var relative in FilesToSeed(sourceGameDir))
            {
                if (CopyShared(Path.Combine(sourceGameDir, relative), Path.Combine(temp, relative)))
                    copied++;
            }
            Directory.Move(temp, gameDir);
            temp = null;
            log($"new game folder {key}: copied {copied} settings file(s) from {Path.GetFileName(sourceGameDir)}");
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            log($"seeding {key} failed: {ex.Message}");
            // Fall back to an empty folder: an unseeded launch beats a failed one.
            try
            {
                if (!Directory.Exists(gameDir)) Directory.CreateDirectory(gameDir);
                return true;
            }
            catch (Exception inner) when (inner is IOException or UnauthorizedAccessException)
            {
                return false;
            }
        }
        finally
        {
            // Our own half-made copy only — never the player's files.
            if (temp != null) TryDeleteTree(temp);
        }
    }

    // Read with sharing so a game running on the source account (which may
    // hold its options open) never blocks the copy.
    private static bool CopyShared(string source, string destination)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            using var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write);
            input.CopyTo(output);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    // A crash mid-seed leaves ".seeding-<key>-<guid>": a copy, safe to drop.
    private static void CleanAbandonedSeeds(string accountsDir, string key)
    {
        try
        {
            foreach (var dir in Directory.EnumerateDirectories(accountsDir, SeedingPrefix + key + "-*"))
                TryDeleteTree(dir);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
    }

    private static void TryDeleteTree(string dir)
    {
        try
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
    }
}
