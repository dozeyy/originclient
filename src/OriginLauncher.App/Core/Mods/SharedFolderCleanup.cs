using System.IO;

namespace OriginLauncher.App.Core.Mods;

/// <summary>
/// The launch-time cleanups of a version's shared mods folder, which holds Origin's jars AND the player's own. They act
/// ONLY on jars Origin itself put there, and how they act depends on how sure that is (<see cref="ModManager.OwnerOf"/>):
///   * <see cref="JarOwner.OriginInstalled"/> (this folder's install record lists the bytes) -> deleted;
///   * <see cref="JarOwner.OriginByPin"/> (the bytes match a pin, current or past, or were adopted from an older
///     launcher) -> renamed aside to ".jar.disabled", never deleted: a player's own download of a version Origin once
///     pinned has exactly those bytes, so a pin match alone is never proof enough to destroy a file;
///   * <see cref="JarOwner.Player"/> (anything else, and every jar that cannot be read right now) -> never touched.
/// Ownership is never decided by a file name or a mod id (Will, 2026-10-25: "a player's own mods win": his own Sodium
/// in instances/1.21.1/mods was deleted by name on every launch).
/// </summary>
public static class SharedFolderCleanup
{
    /// <summary>
    /// 1.21.1-style builds that carry the perf stack inside originclient.jar: Origin's old standalone Sodium / Iris /
    /// Lithium / ... copies left by a pre-bundle install are removed (deleted or set aside, see the class comment).
    /// Returns the names removed.
    /// </summary>
    public static IReadOnlyList<string> PurgeStandalonePerf(string modsFolder, Func<string, JarOwner> ownerOf)
    {
        var removed = new List<string>();
        if (!Directory.Exists(modsFolder)) return removed;
        foreach (var file in EnabledJars(modsFolder))
        {
            var name = Path.GetFileName(file);
            if (name.StartsWith("originclient", StringComparison.OrdinalIgnoreCase)) continue;
            if (!ModManager.IsBundledPerfJar(name)) continue;
            if (Remove(file, ownerOf(file))) removed.Add(name);
        }
        return removed;
    }

    /// <summary>
    /// Exactly one of ORIGIN's own jars per managed family (a catalog bump leaves the old pin behind): the pinned name
    /// is kept, else the highest version, else the newest file; the rest are removed (deleted or set aside). The
    /// player's copies of the same mod are not part of the comparison at all. When a removal fails (a running game
    /// holds the jar), the copy in use stays and the others go. Returns the names removed.
    /// </summary>
    public static IReadOnlyList<string> DedupeOriginFamilies(
        string modsFolder, IReadOnlySet<string> pinnedJarNames, Func<string, JarOwner> ownerOf)
    {
        var removed = new List<string>();
        if (!Directory.Exists(modsFolder)) return removed;
        var owners = new Dictionary<string, JarOwner>(StringComparer.OrdinalIgnoreCase);
        var families = EnabledJars(modsFolder)
            .Where(f => ModManager.IsManaged(Path.GetFileName(f)))
            .Where(f => (owners[f] = ownerOf(f)) != JarOwner.Player)
            .GroupBy(f => ModManager.ModFamilyKey(Path.GetFileName(f)), StringComparer.OrdinalIgnoreCase)
            .Where(g => g.Count() > 1);
        foreach (var family in families)
        {
            var ordered = family
                .OrderByDescending(f => pinnedJarNames.Contains(Path.GetFileName(f)))
                .ThenByDescending(f => ModManager.TryParseVersion(Path.GetFileName(f)) ?? new Version(0, 0))
                .ThenByDescending(f => File.GetLastWriteTimeUtc(f))
                .ToList();
            var kept = ordered[0];
            foreach (var other in ordered.Skip(1))
            {
                if (Remove(other, owners[other])) { removed.Add(Path.GetFileName(other)); continue; }
                // Couldn't remove it (in use): keep the one in use; drop the one that was going to be kept instead.
                if (kept != null && Remove(kept, owners[kept])) removed.Add(Path.GetFileName(kept));
                kept = null;
            }
        }
        return removed;
    }

    // Deletes Origin's recorded jar, sets a pin-only one aside; the player's is never touched. False when nothing happened.
    private static bool Remove(string path, JarOwner owner)
    {
        try
        {
            switch (owner)
            {
                case JarOwner.OriginInstalled:
                    File.Delete(path);
                    return true;
                case JarOwner.OriginByPin:
                    var sha = OriginInstallRecord.Sha512Of(path);
                    var aside = FreeAsideName(path);
                    File.Move(path, aside, overwrite: false);
                    if (sha != null) OriginInstallRecord.NoteAside(aside, sha);
                    return true;
                default:
                    return false;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    // "x.jar" -> "x.jar.disabled", or "x (2).jar.disabled", ... when that name is taken (never overwrites a file).
    private static string FreeAsideName(string jarPath)
    {
        var folder = Path.GetDirectoryName(jarPath)!;
        var stem = Path.GetFileNameWithoutExtension(jarPath);
        var candidate = jarPath + ".disabled";
        for (var n = 2; File.Exists(candidate); n++)
            candidate = Path.Combine(folder, $"{stem} ({n}).jar.disabled");
        return candidate;
    }

    // Enabled jars only, matched by name (a Win32 "*.jar" glob also matches longer extensions).
    private static IEnumerable<string> EnabledJars(string folder) =>
        Directory.EnumerateFiles(folder).Where(f => f.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)).ToList();
}
