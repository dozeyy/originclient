using System.IO;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// After the crash window's "Try once without my mods" starts the game, one of the player's own mods caused the crash.
/// This names the ones to check first, so the next Play doesn't just crash the same way again: the mods the crash
/// evidence named (even below the confidence a fix button needs), then the ones the player added most recently (a
/// crash usually follows adding a mod). Three at most. A guided halving search was considered and left out: splitting
/// mods in half breaks their dependencies (a library in the other half is a new, different crash), so its answers
/// would mislead more often than this list does.
/// </summary>
public static class ModSuspects
{
    public const int Max = 3;

    /// <summary>
    /// The player's jars to check first (file names, at most <see cref="Max"/>) and how many of their jars the crashed
    /// launch loaded. <paramref name="arrivedUtc"/> gives when a jar arrived in its folder (newest first).
    /// </summary>
    public static (IReadOnlyList<string> Jars, int Added) Pick(CrashVerdict verdict, IReadOnlyList<CrashModEntry> mods,
        Func<string, DateTime> arrivedUtc)
    {
        var cmp = StringComparer.OrdinalIgnoreCase;
        var added = mods.Where(m => m.IsExternal && m.JarFileName != null)
            .Select(m => m.JarFileName!).Distinct(cmp).ToList();
        var picked = new List<string>();
        foreach (var culprit in new[] { verdict.Finding }.Concat(verdict.Others).SelectMany(f => f.Culprits))
        {
            if (picked.Count >= Max) break;
            if (culprit is { IsExternal: true, JarFileName: { } jar } && added.Contains(jar, cmp) && !picked.Contains(jar, cmp))
                picked.Add(jar);
        }
        foreach (var jar in added.OrderByDescending(arrivedUtc).ThenBy(j => j, cmp))
        {
            if (picked.Count >= Max) break;
            if (!picked.Contains(jar, cmp)) picked.Add(jar);
        }
        return (picked, added.Count);
    }

    /// <summary>
    /// The Home line once the Origin-only try is running. It replaces the usual "Running" line for that one launch.
    /// </summary>
    public static string BootedLine(IReadOnlyList<string> jars, int added)
    {
        const string lead = "Running without your mods - ";
        if (jars.Count == 0)
            return lead + "one of them caused the crash. Turn them off one at a time on the Mods tab.";
        var names = jars.Select(Display).ToList();
        if (added == 1 && names.Count == 1)
            return lead + $"{names[0]} caused the crash. Turn it off on the Mods tab.";
        var list = names.Count == 1 ? names[0] : string.Join(", ", names.Take(names.Count - 1)) + " or " + names[^1];
        return names.Count >= added
            ? lead + $"one of them caused the crash: {list}. Turn them off one at a time on the Mods tab."
            : lead + $"one of them caused the crash. Try turning off {list} first, on the Mods tab.";
    }

    /// <summary>
    /// The line for this crash, read off the UI thread (it stats the launch's jars). Never throws: anything unreadable
    /// just names fewer mods.
    /// </summary>
    public static string LineFor(string gameDir, CrashVerdict verdict, IReadOnlyList<CrashModEntry> mods)
    {
        var folders = CrashAnalyzer.ModsFolders(gameDir);
        var (jars, added) = Pick(verdict, mods, jar => Arrived(folders, jar));
        return BootedLine(jars, added);
    }

    // When the jar arrived: a copy keeps the source's write time but gets a new creation time (and the reverse for a
    // jar edited in place), so the later of the two. Missing or unreadable: as old as possible (never "newest").
    private static DateTime Arrived(IReadOnlyList<string> folders, string jar)
    {
        foreach (var folder in folders)
        {
            try
            {
                var path = Path.Combine(folder, jar);
                if (!File.Exists(path)) continue;
                var created = File.GetCreationTimeUtc(path);
                var written = File.GetLastWriteTimeUtc(path);
                return created > written ? created : written;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
            {
            }
        }
        return DateTime.MinValue;
    }

    private static string Display(string jar)
    {
        var name = jar.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) ? jar[..^4] : jar;
        return name.Length <= 28 ? name : name[..25] + "...";
    }
}
