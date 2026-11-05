using System.IO;

namespace OriginLauncher.App.Core.GameFolders;

// A brand-new account game folder (an account's first launch of a version) is
// seeded with another account's SETTINGS for that version — keybinds, video
// settings, the server list, mod configs (Origin's originclient*.json, Sodium,
// Iris, ...) — so it doesn't start bare. Its resource and shader packs are
// copied too (resourcepacks/, shaderpacks/ with Iris's per-pack .txt options):
// the seeded options.txt and iris.properties name those packs, and without the
// files Minecraft drops them from the list and Iris switches shaders off, so a
// second account would start with every pack gone. They are COPIES — each
// account keeps its own folder (DECISIONS 2026-10-12), and deleting a pack on
// one account never touches another. Worlds, screenshots and logs are NOT
// copied: those stay with the account that has them. Mods are never copied
// either — there is nothing to copy: every account of a version loads the same
// shared instances/<version>/mods/ (SharedMods).
//
// Crash-safe: the copy is built in a temporary sibling folder and renamed into
// place in one step, so a game folder is either fully seeded or absent. The copy
// can be cancelled between files (the launch's Cancel), and only that last step
// takes the caller's lock (commitLock): a folder that appeared meanwhile wins.
public static class AccountSeed
{
    // Top-level settings files. options.txt + servers.dat are vanilla; the
    // optionsof/optionsshaders pair is OptiFine's (legacy versions).
    private static readonly HashSet<string> TopLevelFiles = new(StringComparer.OrdinalIgnoreCase)
    {
        "options.txt", "servers.dat", "optionsof.txt", "optionsshaders.txt",
    };

    // Pack folders copied whole (zips, unpacked folder packs, Iris's <pack>.txt options).
    private static readonly string[] PackFolders = { "resourcepacks", "shaderpacks" };

    private const string SeedingPrefix = ".seeding-";

    // Temp folders a Seed in this process is filling right now: CleanAbandonedSeeds leaves them alone (a cancelled
    // launch's copy may still be finishing its current file while the next launch of that account starts).
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, byte> InProgress =
        new(StringComparer.OrdinalIgnoreCase);

    private static readonly object NoCallerLock = new();

    /// <summary>Packs bigger than this in total are not copied (minutes of copying on a first Play); the account starts without them.</summary>
    public const long MaxPackBytes = 4L << 30;

    /// <summary>Free space kept on the disk beyond the packs themselves; with less, the packs are not copied.</summary>
    public const long FreeSpaceMargin = 500L << 20;

    /// <summary>The launch screen's line while packs copy.</summary>
    public const string CopyingPacksStage = "Copying your packs";

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
    /// Every file under <paramref name="sourceGameDir"/>'s resourcepacks/ and shaderpacks/, as
    /// '/'-separated relative paths, sorted. Half-written ".tmp" siblings are skipped.
    /// </summary>
    public static IReadOnlyList<string> PackFilesToSeed(string sourceGameDir)
    {
        var files = new List<string>();
        foreach (var folder in PackFolders)
        {
            try
            {
                var dir = Path.Combine(sourceGameDir, folder);
                if (!Directory.Exists(dir)) continue;
                foreach (var file in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories))
                {
                    if (file.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase)) continue;
                    files.Add(Path.GetRelativePath(sourceGameDir, file).Replace('\\', '/'));
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // an unreadable pack folder just seeds fewer packs
            }
        }
        files.Sort(StringComparer.Ordinal);
        return files;
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
    /// <paramref name="sourceGameDir"/>'s settings and packs (or empty when there is no
    /// source). Does nothing when the folder already exists (or appears while the
    /// copy runs). Returns true when it created the folder. Never throws, except
    /// OperationCanceledException when <paramref name="cancel"/> fires (the half-made
    /// copy is deleted and no folder is made).
    /// </summary>
    /// <param name="stage">Told <see cref="CopyingPacksStage"/> before packs copy (the launch screen's line).</param>
    /// <param name="notice">Told one plain sentence when the packs were left out (too big, or not enough free space).</param>
    /// <param name="maxPackBytes">Tests only: the size limit.</param>
    /// <param name="freeBytes">Tests only: the free space on the disk holding a folder (null = unknown).</param>
    /// <param name="cancel">Checked between files.</param>
    /// <param name="commitLock">Held only for the final exists-check and rename (AccountFolders' folder lock).</param>
    public static bool Seed(string gameDir, string? sourceGameDir, Action<string>? log = null,
        Action<string>? stage = null, Action<string>? notice = null,
        long maxPackBytes = MaxPackBytes, Func<string, long?>? freeBytes = null,
        CancellationToken cancel = default, object? commitLock = null)
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
                return Commit(() => Directory.CreateDirectory(gameDir));

            var seeding = Path.Combine(parent, $"{SeedingPrefix}{key}-{Guid.NewGuid():N}");
            temp = seeding;
            InProgress[seeding] = 0;
            Directory.CreateDirectory(seeding);
            var copied = 0;
            foreach (var relative in FilesToSeed(sourceGameDir))
            {
                cancel.ThrowIfCancellationRequested();
                if (CopyShared(Path.Combine(sourceGameDir, relative), Path.Combine(temp, relative)))
                    copied++;
            }
            // Packs are copied into the same temp folder, so the folder still appears in one step.
            // Runs on the launch worker (PrepareGameDir is called off the UI thread on every launcher).
            var packs = 0;
            var packFiles = PackFilesToSeed(sourceGameDir);
            var packBytes = packFiles.Sum(relative => SizeOf(Path.Combine(sourceGameDir, relative)));
            var free = (freeBytes ?? FreeBytes)(temp);
            if (packFiles.Count == 0)
            {
                // nothing to copy
            }
            else if (packBytes > maxPackBytes)
            {
                // Minutes of copying on a first Play is worse than starting without them.
                notice?.Invoke("Your resource and shader packs weren't copied to this account (more than 4 GB).");
                log($"new game folder {key}: packs not copied ({packBytes / (1 << 20)} MB is over the limit)");
            }
            else if (free is long space && space < packBytes + FreeSpaceMargin)
            {
                notice?.Invoke("Your resource and shader packs weren't copied to this account (not enough free disk space).");
                log($"new game folder {key}: packs not copied ({packBytes / (1 << 20)} MB, {space / (1 << 20)} MB free)");
            }
            else
            {
                stage?.Invoke(CopyingPacksStage);
                foreach (var relative in packFiles)
                {
                    cancel.ThrowIfCancellationRequested();
                    if (CopyShared(Path.Combine(sourceGameDir, relative), Path.Combine(temp, relative)))
                        packs++;
                }
            }
            cancel.ThrowIfCancellationRequested();
            if (!Commit(() => Directory.Move(seeding, gameDir)))
            {
                log($"new game folder {key}: made meanwhile (another launch or the migration); this copy is dropped");
                return false;
            }
            temp = null;
            log($"new game folder {key}: copied {copied} settings file(s) and {packs} pack file(s) from {Path.GetFileName(sourceGameDir)}");
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            log($"seeding {key} failed: {ex.Message}");
            // Fall back to an empty folder: an unseeded launch beats a failed one.
            try
            {
                Commit(() => Directory.CreateDirectory(gameDir));
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
            if (temp != null)
            {
                TryDeleteTree(temp);
                InProgress.TryRemove(temp, out _);
            }
        }

        // The one step under the caller's lock: a folder that appeared while the copy ran wins.
        bool Commit(Action make)
        {
            lock (commitLock ?? NoCallerLock)
            {
                if (Directory.Exists(gameDir)) return false;
                make();
                return true;
            }
        }
    }

    // Read with sharing so a game running on the source account (which may
    // hold its options open) never blocks the copy. A copy that fails part way
    // (disk full, the source vanished) is deleted: a cut-off pack that
    // options.txt or iris.properties names is worse than no pack at all.
    private static bool CopyShared(string source, string destination)
    {
        var created = false;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            using (var input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            using (var output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write))
            {
                created = true; // CreateNew: the file is ours, so it may be deleted on failure
                input.CopyTo(output);
            }
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            if (created)
            {
                try { File.Delete(destination); }
                catch (Exception inner) when (inner is IOException or UnauthorizedAccessException) { /* in the temp copy: dropped with it on a crash */ }
            }
            return false;
        }
    }

    private static long SizeOf(string path)
    {
        try { return new FileInfo(path).Length; }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return 0; }
    }

    // Free space on the disk holding the folder; null when it can't be read (the copy then just tries).
    // Windows wants the drive root; elsewhere .NET asks the file system of the given path itself (statvfs).
    private static long? FreeBytes(string folder)
    {
        try
        {
            var full = Path.GetFullPath(folder);
            var drive = new DriveInfo(OperatingSystem.IsWindows() ? Path.GetPathRoot(full)! : full);
            return drive.AvailableFreeSpace;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return null;
        }
    }

    // A crash mid-seed leaves ".seeding-<key>-<guid>": a copy, safe to drop.
    private static void CleanAbandonedSeeds(string accountsDir, string key)
    {
        try
        {
            foreach (var dir in Directory.EnumerateDirectories(accountsDir, SeedingPrefix + key + "-*"))
                if (!InProgress.ContainsKey(dir)) TryDeleteTree(dir);
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
