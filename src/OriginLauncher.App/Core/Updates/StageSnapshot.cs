using System.IO;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Updates;

// A background download stages the update long before the player clicks Update (minutes or hours). Right after it
// staged (the files came from a SHA-256-verified zip, or were each checked against the delta's manifest), every file's
// size and SHA-256 is recorded here; the click hashes the stage again and swaps only when nothing changed, so an
// antivirus that removed a file, a half-cleaned folder or anything else that touched the stage never reaches the
// install folder. A mismatch makes the click download the update itself, exactly as before.
// Symlinks (a Mac .app's framework links) are skipped both times: their targets are files inside the stage, which are
// hashed anyway, and following them would hash the same bytes twice or leave the stage.
public static class StageSnapshot
{
    public sealed record Entry(string Path, long Size, string Sha256);

    private static readonly EnumerationOptions Walk = new()
    {
        RecurseSubdirectories = true,
        AttributesToSkip = FileAttributes.ReparsePoint,
        IgnoreInaccessible = false
    };

    /// <summary>Every regular file under <paramref name="stageDir"/> with its size and SHA-256 (paths relative, '/').</summary>
    public static IReadOnlyList<Entry> Take(string stageDir, CancellationToken ct = default)
    {
        var entries = new List<Entry>();
        foreach (var path in Directory.EnumerateFiles(stageDir, "*", Walk))
        {
            ct.ThrowIfCancellationRequested();
            entries.Add(new Entry(Relative(stageDir, path), new FileInfo(path).Length, Hash(path)));
        }
        if (entries.Count == 0) throw new IOException("the update's stage is empty");
        return entries;
    }

    /// <summary>
    /// True when <paramref name="stageDir"/> holds exactly the recorded files, each with its size and SHA-256. Never
    /// throws: a missing folder or an unreadable file is simply a mismatch.
    /// </summary>
    public static bool Matches(string stageDir, IReadOnlyList<Entry> snapshot)
    {
        try
        {
            if (!Directory.Exists(stageDir)) return false;
            var found = Directory.EnumerateFiles(stageDir, "*", Walk).Count();
            if (found != snapshot.Count) return false;
            foreach (var entry in snapshot)
            {
                var path = Path.Combine(stageDir, entry.Path.Replace('/', Path.DirectorySeparatorChar));
                var info = new FileInfo(path);
                if (!info.Exists || info.Length != entry.Size) return false;
                if (!string.Equals(Hash(path), entry.Sha256, StringComparison.Ordinal)) return false;
            }
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static string Relative(string root, string path) =>
        Path.GetRelativePath(root, path).Replace(Path.DirectorySeparatorChar, '/');

    private static string Hash(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16);
        return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
    }
}
