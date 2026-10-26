using System.IO;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Mods;

/// <summary>
/// Which jars in a mods folder Origin itself put there, by SHA-512: one line "&lt;sha512&gt; &lt;file name&gt;" per jar
/// an installer wrote, in <see cref="FileName"/> inside that folder (beside the jars, like the 1.8.9 mirror's
/// ".origin-mirror"; Fabric and Forge only load ".jar" files, so it is never mistaken for a mod).
///
/// Why it exists (Will, 2026-10-25: "a player's own mods win"): the shared folder holds Origin's jars AND the
/// player's, and the two can carry the very same mod id (Will's own Sodium beside Origin's). Ownership by file name
/// or by mod id deleted the player's copy on every launch. With this record (plus ManagedPins' pins, current and
/// past) a jar is Origin's only when its BYTES are a file Origin installed, so the launcher can clean up its own
/// leftovers and never touch the player's.
///
/// The record only grows (a few lines per version); a line whose jar is gone is harmless. Unreadable record = empty
/// record: the jar then counts as the player's, which can only ever leave a file in place, never delete one.
/// </summary>
public static class OriginInstallRecord
{
    /// <summary>The record's file name inside a mods folder.</summary>
    public const string FileName = ".origin-installed";

    private static readonly object Gate = new();

    /// <summary>
    /// Notes that Origin just installed <paramref name="jarPath"/> (its SHA-512, hex). Best-effort: a record that
    /// can't be written only means the jar counts as the player's (kept, never deleted) unless a pin knows it.
    /// </summary>
    public static void Add(string jarPath, string? sha512Hex)
    {
        var folder = Path.GetDirectoryName(Path.GetFullPath(jarPath));
        if (folder == null || string.IsNullOrEmpty(sha512Hex)) return;
        var line = sha512Hex.ToLowerInvariant() + " " + Path.GetFileName(jarPath);
        lock (Gate)
        {
            try
            {
                var record = Path.Combine(folder, FileName);
                if (File.Exists(record) && File.ReadLines(record).Any(l => l.Equals(line, StringComparison.OrdinalIgnoreCase)))
                    return;
                File.AppendAllLines(record, new[] { line });
                ModManager.ClearOwnershipCache(); // an answer remembered before this line may now be wrong
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* best-effort, see above */ }
        }
    }

    /// <summary>Hashes <paramref name="jarPath"/> and records it (for installers that did not hash while downloading).</summary>
    public static void AddFile(string jarPath)
    {
        try
        {
            using var stream = new FileStream(jarPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            Add(jarPath, Convert.ToHexString(SHA512.HashData(stream)));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* best-effort */ }
    }

    /// <summary>True when the record in <paramref name="folder"/> lists this SHA-512 (any file name: a rename keeps it Origin's).</summary>
    public static bool Contains(string folder, string sha512Hex)
    {
        try
        {
            var record = Path.Combine(folder, FileName);
            if (!File.Exists(record)) return false;
            foreach (var line in File.ReadLines(record))
            {
                var space = line.IndexOf(' ');
                var sha = space < 0 ? line : line[..space];
                if (sha.Equals(sha512Hex, StringComparison.OrdinalIgnoreCase)) return true;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        return false;
    }
}
