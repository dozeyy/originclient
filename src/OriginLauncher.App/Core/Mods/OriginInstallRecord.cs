using System.IO;
using System.Security.Cryptography;
using OriginLauncher.App.Core.Compat;

namespace OriginLauncher.App.Core.Mods;

/// <summary>
/// Which jars in a mods folder Origin itself put there, by SHA-512, in <see cref="FileName"/> inside that folder
/// (beside the jars, like the 1.8.9 mirror's ".origin-mirror"; Fabric and Forge only load ".jar" files, so it is never
/// mistaken for a mod). One line per jar:
///   "&lt;sha512&gt; &lt;file name&gt;"          an Origin installer wrote this file here (Origin's: its cleanups may delete it);
///   "adopted &lt;sha512&gt; &lt;file name&gt;"  already here when this record was started, in a family older launchers
///                                    installed without a pin (Fabric API, Simple Voice Chat): Origin's for loading,
///                                    but only ever set aside (renamed to .disabled), never deleted;
///   "aside &lt;sha512&gt; &lt;file name&gt;"    Origin set this file aside as a leftover of its own (not proof of anything);
///   "player &lt;sha512&gt; &lt;file name&gt;"   the player turned a set-aside file back on: it is theirs from now on.
///   "# ..."                          a comment (the header that marks the record as started).
///
/// Why it exists (Will, 2026-10-25: "a player's own mods win"): the shared folder holds Origin's jars AND the
/// player's, and the two can carry the very same mod id (Will's own Sodium beside Origin's). Ownership by file name
/// or by mod id deleted the player's copy on every launch. With this record (plus ManagedPins' pins, current and
/// past) a jar is Origin's only when its BYTES are a file Origin installed.
///
/// The record only grows (a few lines per version); a line whose jar is gone is harmless. Unreadable record = empty
/// record: the jar then counts as the player's, which can only ever leave a file in place, never delete one.
/// </summary>
public static class OriginInstallRecord
{
    /// <summary>The record's file name inside a mods folder.</summary>
    public const string FileName = ".origin-installed";

    private const string Header = "# Origin's own jars in this folder (one per line, by SHA-512). Written by the launcher.";

    /// <summary>What the record says about one SHA-512 (the strongest line wins: player, then installed, then adopted).</summary>
    public enum Entry { None, Adopted, Installed, Player }

    private static readonly object Gate = new();

    /// <summary>
    /// Notes that Origin just installed <paramref name="jarPath"/> (its SHA-512, hex). Best-effort: a record that
    /// can't be written only means the jar counts as the player's (kept, never deleted) unless a pin knows it.
    /// </summary>
    public static void Add(string jarPath, string? sha512Hex) => Append(jarPath, sha512Hex, null);

    /// <summary>Hashes <paramref name="jarPath"/> and records it (for installers that did not hash while downloading).</summary>
    public static void AddFile(string jarPath)
    {
        if (Sha512Of(jarPath) is { } sha) Add(jarPath, sha);
    }

    /// <summary>Notes that Origin renamed one of its own leftovers aside to <paramref name="asidePath"/>.</summary>
    public static void NoteAside(string asidePath, string sha512Hex) => Append(asidePath, sha512Hex, "aside");

    /// <summary>
    /// The player turned <paramref name="jarPath"/> back on. When Origin had set exactly these bytes aside, the file
    /// is the player's from now on (Origin never sets it aside again). Anything else: nothing to note.
    /// </summary>
    public static void NoteTurnedOn(string jarPath)
    {
        var folder = Path.GetDirectoryName(Path.GetFullPath(jarPath));
        if (folder == null || Sha512Of(jarPath) is not { } sha) return;
        if (Lines(folder).Any(l => l.Kind == "aside" && l.Sha.Equals(sha, StringComparison.OrdinalIgnoreCase)))
            Append(jarPath, sha, "player");
    }

    /// <summary>
    /// Once per folder, the first time a launcher with this record sees it: Fabric API and Simple Voice Chat jars
    /// already here are Origin's (launchers up to v1.0.65 installed them without a pin, so no pin knows their bytes,
    /// and the old launcher treated every jar of those names as its own). They are recorded as "adopted": Origin's
    /// for loading (a profile or Origin-only launch still has its Fabric API), never deleted, only set aside. Every
    /// other family's old Origin copies are known by ManagedPins' history, so nothing else is adopted (a player's own
    /// Sodium is never taken for Origin's). Returns the names adopted. Best-effort, never throws.
    /// </summary>
    public static IReadOnlyList<string> AdoptLegacy(string modsFolder)
    {
        var adopted = new List<string>();
        lock (Gate)
        {
            try
            {
                var record = Path.Combine(modsFolder, FileName);
                if (!Directory.Exists(modsFolder) || File.Exists(record)) return adopted;
                var lines = new List<string> { Header };
                foreach (var path in Directory.EnumerateFiles(modsFolder))
                {
                    var name = Path.GetFileName(path);
                    if (!IsLegacyUnpinnedFamily(name)) continue;
                    var info = ModFolderScanner.ReadInfo(path, readNested: false);
                    if (info is not { ReadFailed: false, Id: { } id } || !LegacyIds.Contains(id)) continue;
                    if (Sha512Of(path) is not { } sha) continue;
                    lines.Add("adopted " + sha.ToLowerInvariant() + " " + name);
                    adopted.Add(name);
                }
                File.WriteAllLines(record, lines);
                ModManager.ClearOwnershipCache();
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* best-effort, see above */ }
        }
        return adopted;
    }

    private static readonly HashSet<string> LegacyIds = new(StringComparer.Ordinal) { "fabric-api", "fabric", "voicechat" };

    private static bool IsLegacyUnpinnedFamily(string fileName)
    {
        var n = fileName.ToLowerInvariant();
        var jar = n.EndsWith(".jar") || n.EndsWith(".jar.disabled");
        return jar && (n.StartsWith("fabric-api-") || n.StartsWith("voicechat-"));
    }

    /// <summary>What the record in <paramref name="folder"/> says about this SHA-512 (any file name: a rename keeps it).</summary>
    public static Entry Lookup(string folder, string sha512Hex)
    {
        var best = Entry.None;
        foreach (var line in Lines(folder))
        {
            if (!line.Sha.Equals(sha512Hex, StringComparison.OrdinalIgnoreCase)) continue;
            var entry = line.Kind switch { null => Entry.Installed, "adopted" => Entry.Adopted, "player" => Entry.Player, _ => Entry.None };
            if (entry > best) best = entry;
        }
        return best;
    }

    /// <summary>True when Origin installed or adopted these bytes in <paramref name="folder"/> (and the player has not claimed them).</summary>
    public static bool Contains(string folder, string sha512Hex) => Lookup(folder, sha512Hex) is Entry.Installed or Entry.Adopted;

    private static IEnumerable<(string? Kind, string Sha)> Lines(string folder)
    {
        List<string> raw;
        try
        {
            var record = Path.Combine(folder, FileName);
            if (!File.Exists(record)) yield break;
            raw = File.ReadAllLines(record).ToList();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { yield break; }

        foreach (var line in raw)
        {
            if (line.Length == 0 || line[0] == '#') continue;
            var parts = line.Split(' ', 3);
            if (parts[0].Length == 128) yield return (null, parts[0]);
            else if (parts.Length >= 2 && parts[1].Length == 128) yield return (parts[0].ToLowerInvariant(), parts[1]);
        }
    }

    private static void Append(string jarPath, string? sha512Hex, string? kind)
    {
        var folder = Path.GetDirectoryName(Path.GetFullPath(jarPath));
        if (folder == null || string.IsNullOrEmpty(sha512Hex)) return;
        var line = (kind == null ? "" : kind + " ") + sha512Hex.ToLowerInvariant() + " " + Path.GetFileName(jarPath);
        // Whoever writes first starts the record, so the old unpinned jars are adopted before any line, whatever the
        // order of the installers (a jar adopted and then installed reads as installed: the stronger line wins).
        AdoptLegacy(folder);
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

    internal static string? Sha512Of(string path)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return Convert.ToHexString(SHA512.HashData(stream));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return null; }
    }
}
