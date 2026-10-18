using System.IO;

namespace OriginLauncher.App.Core.Profiles;

/// <summary>
/// The per-account folder a Fabric game is pointed at (fabric.modsFolder) when
/// it must load something other than the version's shared mods folder:
/// "mods-origin-only" (Origin's own jars) or "mods-profile" (Origin's jars plus
/// one profile's mods). Rebuilt each launch from a name -> source map.
/// Pure file work on the paths it is given, so it is unit tested on Linux.
/// </summary>
public static class LaunchModFolder
{
    /// <summary>The launch folder name for a profile launch, inside the account's game folder.</summary>
    public const string ProfileFolderName = "mods-profile";

    /// <summary>The launch folder name for an Origin-only launch.</summary>
    public const string OriginOnlyFolderName = "mods-origin-only";

    /// <summary>
    /// The jars a profile launch loads, as file name -> full path: Origin's own
    /// enabled jars from the shared folder (<paramref name="isOriginJar"/>
    /// decides which), then the profile's enabled jars. The player's loose jars
    /// in the shared folder are NOT in it — that is the point of a profile. A
    /// profile jar that looks like one of Origin's own (a second Fabric API
    /// would stop Fabric booting) is left out.
    /// </summary>
    public static Dictionary<string, string> LaunchSet(
        IEnumerable<string> sharedFiles, IEnumerable<string> profileFiles, Func<string, bool> isOriginJar)
    {
        var set = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var path in sharedFiles)
        {
            var name = Path.GetFileName(path);
            if (IsEnabledJar(name) && isOriginJar(name)) set[name] = path;
        }
        foreach (var path in profileFiles)
        {
            var name = Path.GetFileName(path);
            if (IsEnabledJar(name) && !isOriginJar(name) && !set.ContainsKey(name)) set[name] = path;
        }
        return set;
    }

    private static bool IsEnabledJar(string fileName) =>
        fileName.EndsWith(".jar", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Makes <paramref name="folder"/> hold exactly <paramref name="desired"/>.
    /// A diff, not delete-and-recreate: another game of this account's folder
    /// may still hold some of these jars open (Windows file locks), so an
    /// unchanged set costs no file operations, a stale extra that is locked is
    /// left for the next launch, and a changed jar is copied beside and renamed
    /// in — never rewritten in place.
    /// </summary>
    public static void Sync(string folder, IReadOnlyDictionary<string, string> desired)
    {
        Directory.CreateDirectory(folder);

        foreach (var existing in Directory.EnumerateFiles(folder))
        {
            if (!desired.ContainsKey(Path.GetFileName(existing)))
            {
                try { File.Delete(existing); } catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* locked by a running game */ }
            }
        }

        foreach (var (name, source) in desired)
        {
            var dest = Path.Combine(folder, name);
            try
            {
                if (SameFile(source, dest)) continue;
                var temp = dest + ".download"; // never a loadable name
                File.Copy(source, temp, overwrite: true);
                File.SetLastWriteTimeUtc(temp, File.GetLastWriteTimeUtc(source));
                File.Move(temp, dest, overwrite: true);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Locked by a still-running game but present: the copy there is
                // loadable, and launching with it beats failing the launch.
                if (!File.Exists(dest)) throw;
            }
        }
    }

    // Same length and last-write time: the copy is current (Sync stamps each
    // copy with its source's time).
    private static bool SameFile(string source, string copy)
    {
        if (!File.Exists(copy)) return false;
        var a = new FileInfo(source);
        var b = new FileInfo(copy);
        return a.Length == b.Length && a.LastWriteTimeUtc == b.LastWriteTimeUtc;
    }
}
