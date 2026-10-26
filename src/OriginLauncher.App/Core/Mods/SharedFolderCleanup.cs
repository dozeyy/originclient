using System.IO;

namespace OriginLauncher.App.Core.Mods;

/// <summary>
/// The two cleanups a Fabric launch runs on a version's SHARED mods folder before the game starts. Both delete only
/// jars that are certainly Origin's (<paramref name="isOriginsOwn"/>: ModManager.IsOriginsOwnJar, by the file's bytes),
/// never by name or mod id: a player's own Sodium, Iris, Mod Menu or Fabric API in the shared folder is theirs and
/// stays (Will, 2026-10-25: a player's own mods win; PlayerOverrides then decides which copy loads, without
/// touching either file). The folder is shared by every account, so a jar is only ever added, deleted or
/// rename-swapped, never rewritten in place.
/// </summary>
public static class SharedFolderCleanup
{
    /// <summary>
    /// For a build that carries the performance stack inside originclient.jar (1.21.1): deletes Origin's own
    /// STANDALONE copies a pre-bundle install left behind (two Origin copies of one mod id). Returns what went.
    /// </summary>
    public static List<string> PurgeStandalonePerf(string modsFolder, Func<string, bool> isOriginsOwn)
    {
        var gone = new List<string>();
        foreach (var file in EnabledJars(modsFolder))
        {
            var name = Path.GetFileName(file);
            if (name.Equals("originclient.jar", StringComparison.OrdinalIgnoreCase)) continue;
            if (!ModManager.IsBundledPerfJar(name) || !isOriginsOwn(file)) continue;
            if (TryDelete(file)) gone.Add(name);
        }
        return gone;
    }

    /// <summary>
    /// Exactly ONE of Origin's own jars per managed family (a catalog bump leaves the old pin behind; Fabric would
    /// load the higher of the two). Which of Origin's copies survives:
    ///   1. the catalog PIN for this version (<paramref name="pinnedJarNames"/>), when the family has one -- pins are
    ///      deliberate era pairings (Iris 1.7.x rides Sodium 0.5.x), so a higher leftover is the wrong one to keep;
    ///   2. otherwise the highest filename version, then the most recently landed.
    /// A copy that would not go is held open by another account's game of this version: then the one in use stays and
    /// the rest go, and the next launch after that game closes finishes the swap. The player's jars never enter a
    /// family here, so they are never compared, kept or dropped. Returns what went.
    /// </summary>
    public static List<string> DedupeOriginFamilies(
        string modsFolder, IReadOnlySet<string> pinnedJarNames, Func<string, bool> isOriginsOwn)
    {
        var gone = new List<string>();
        foreach (var family in EnabledJars(modsFolder)
                     .Where(f => ModManager.IsManaged(Path.GetFileName(f)) && isOriginsOwn(f))
                     .GroupBy(f => ModManager.ModFamilyKey(Path.GetFileName(f)), StringComparer.OrdinalIgnoreCase))
        {
            var keep = family.FirstOrDefault(f => pinnedJarNames.Contains(Path.GetFileName(f)))
                ?? family
                    .OrderByDescending(f => ModManager.TryParseVersion(Path.GetFileName(f)) ?? new Version(0, 0))
                    .ThenByDescending(Landed)
                    .First();
            foreach (var stale in family.Where(f => !ReferenceEquals(f, keep)))
                if (TryDelete(stale)) gone.Add(Path.GetFileName(stale));

            var left = family.Where(File.Exists).ToList();
            if (left.Count > 1)
            {
                var inUse = left.First(f => !ReferenceEquals(f, keep));
                foreach (var other in left.Where(f => !ReferenceEquals(f, inUse)))
                    if (TryDelete(other)) gone.Add(Path.GetFileName(other));
            }
        }
        return gone;
    }

    private static IEnumerable<string> EnabledJars(string modsFolder)
    {
        if (!Directory.Exists(modsFolder)) return Array.Empty<string>();
        return Directory.EnumerateFiles(modsFolder)
            .Where(f => Path.GetFileName(f).EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase))
            .ToList();
    }

    private static DateTime Landed(string path)
    {
        var created = File.GetCreationTimeUtc(path);
        var written = File.GetLastWriteTimeUtc(path);
        return created > written ? created : written;
    }

    private static bool TryDelete(string path)
    {
        try
        {
            File.Delete(path);
            return !File.Exists(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false; // locked by a running game, or already gone
        }
    }
}
