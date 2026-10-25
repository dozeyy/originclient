using System.Collections.Concurrent;
using System.IO;

namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// Reads a mods folder into <see cref="ModFile"/>s for the engine. Read-only; it never changes a file. A jar is read
/// at most once until it changes (size or time stamp): the Mods page, the import and the crash path all scan the same
/// folders, and re-opening a few hundred jars is the one slow thing in the whole compatibility check.
/// </summary>
public static class ModFolderScanner
{
    private sealed record Stamp(long Length, long WriteTicks, bool Nested);

    // Bounded: a launcher session touches a few folders, not thousands of distinct jars.
    private const int CacheLimit = 4096;
    private static readonly ConcurrentDictionary<string, (Stamp Stamp, ModInfo Info)> Cache = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Forgets every cached read (tests that measure a cold read; nothing else needs it: a changed file re-reads itself).</summary>
    public static void ClearCache() => Cache.Clear();

    /// <summary>
    /// The *.jar and *.jar.disabled files at the top of <paramref name="folder"/> (never sub-folders), by file name.
    /// A file <paramref name="isOriginProvisioned"/> names is <see cref="ModRole.Managed"/>; the rest get
    /// <paramref name="role"/>. A missing folder is an empty list; a jar that can't be read is still listed
    /// (<see cref="ModInfo.ReadFailed"/>), so it can be reported rather than silently skipped.
    /// </summary>
    public static List<ModFile> Scan(
        string folder, ModRole role, Func<string, bool>? isOriginProvisioned = null, bool readNested = true)
    {
        var result = new List<ModFile>();
        List<string> files;
        try
        {
            if (!Directory.Exists(folder)) return result;
            files = Directory.EnumerateFiles(folder).OrderBy(p => p, StringComparer.OrdinalIgnoreCase).ToList();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return result;
        }

        foreach (var path in files)
        {
            var name = Path.GetFileName(path);
            var disabled = name.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase);
            if (!disabled && !name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) continue;
            var jarName = disabled ? name[..^".disabled".Length] : name;
            var info = ReadInfo(path, readNested) ?? new ModInfo { FileName = name, ReadFailed = true };
            var fileRole = isOriginProvisioned?.Invoke(jarName) == true ? ModRole.Managed : role;
            result.Add(new ModFile(path, name, Enabled: !disabled, fileRole, info));
        }
        return result;
    }

    /// <summary>
    /// What one jar says about itself, or null when it can't be opened right now (in use, no access). A file that
    /// opens but isn't a readable jar returns an info with <see cref="ModInfo.ReadFailed"/>, as the reader does.
    /// </summary>
    public static ModInfo? ReadInfo(string path, bool readNested = true)
    {
        var name = Path.GetFileName(path);
        FileInfo file;
        try { file = new FileInfo(path); if (!file.Exists) return null; }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return null; }

        var stamp = new Stamp(file.Length, file.LastWriteTimeUtc.Ticks, readNested);
        if (Cache.TryGetValue(path, out var cached) && cached.Stamp == stamp) return cached.Info with { FileName = name };

        try
        {
            // Share everything: another account's game may hold the jar open right now.
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            var info = ModMetadataReader.Read(stream, name, readNested);
            if (Cache.Count >= CacheLimit) Cache.Clear();
            Cache[path] = (stamp, info);
            return info;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidDataException)
        {
            return null;
        }
    }
}
