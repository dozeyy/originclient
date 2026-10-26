using System.IO;
using System.Runtime.InteropServices;

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
    /// The launch folder name for a shared-folder launch where the player's own copy of a mod Origin ships must load
    /// INSTEAD of Origin's (PlayerOverrides withheld one of Origin's jars, set one of the player's aside, or swapped in
    /// a vetted Sodium/Iris pair): every jar of the shared folder but those. The shared folder itself is not touched,
    /// so another account's running game is never disturbed. Without such a decision the game loads the shared folder
    /// directly, as always.
    /// </summary>
    public const string SharedFolderName = "mods-shared";

    /// <summary>
    /// The jars a profile launch loads, as file name -> full path: Origin's own
    /// enabled jars from the shared folder (<paramref name="isOriginJar"/>
    /// decides which), then the profile's enabled jars. The player's loose jars
    /// in the shared folder are NOT in it — that is the point of a profile.
    /// <paramref name="confirmOrigin"/> (a full path; ModManager.IsOriginsCopy) confirms a name that looks like
    /// Origin's by the file's hash or metadata: a player's jar that is only NAMED like one of Origin's stays theirs.
    /// Every profile jar is the player's. Where the profile and Origin both have a mod, <paramref name="skip"/>
    /// (PlayerOverridePlan.Skips: Origin's copy withheld, or the player's set aside) says which copy stays out; a
    /// profile jar with exactly the name of one of Origin's loaded jars gives way to Origin's.
    /// </summary>
    public static Dictionary<string, string> LaunchSet(
        IEnumerable<string> sharedFiles, IEnumerable<string> profileFiles, Func<string, bool> isOriginJar,
        Func<string, bool>? confirmOrigin = null, Func<string, bool>? skip = null)
    {
        bool IsOrigin(string path, string name) => isOriginJar(name) && (confirmOrigin?.Invoke(path) ?? true);
        bool Skipped(string path) => skip?.Invoke(path) ?? false;

        var set = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var path in sharedFiles)
        {
            var name = Path.GetFileName(path);
            if (IsEnabledJar(name) && IsOrigin(path, name) && !Skipped(path)) set[name] = path;
        }
        foreach (var path in profileFiles)
        {
            var name = Path.GetFileName(path);
            if (IsEnabledJar(name) && !Skipped(path) && !set.ContainsKey(name)) set[name] = path;
        }
        return set;
    }

    private static bool IsEnabledJar(string fileName) =>
        fileName.EndsWith(".jar", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// A swap's effect on a launch set: Origin's jars it replaces leave (only Origin's, from the shared folder, and
    /// only when <paramref name="isOriginsCopy"/> confirms them: a player's jar named like Sodium stays), and the
    /// swap's files join unless <paramref name="skip"/> keeps them out.
    /// </summary>
    public static void ApplySwap(Dictionary<string, string> desired, Compat.LaunchSwap swap, IEnumerable<string> sharedFiles,
        Func<string, bool> isOriginsCopy, Func<string, bool> skip)
    {
        var fromShared = new HashSet<string>(sharedFiles, StringComparer.OrdinalIgnoreCase);
        foreach (var name in desired.Where(d => swap.Replaces(d.Key) && fromShared.Contains(d.Value) && isOriginsCopy(d.Value))
                     .Select(d => d.Key).ToList())
            desired.Remove(name);
        foreach (var file in swap.Files)
            if (!skip(file.Path)) desired[Compat.LaunchSwap.NameOf(file)] = file.Path;
    }

    /// <summary>
    /// The folder a shared-folder launch loads. The shared folder itself, unless the player's copies change what loads
    /// (<paramref name="plan"/> withholds one of Origin's jars or sets one of theirs aside, or a vetted pair is swapped
    /// in): then <c>mods-shared</c> (or <c>mods-shared-&lt;configId&gt;</c>) inside <paramref name="gameDir"/>, holding
    /// every jar of the shared folder but those, as hard links (no copy: instant, no extra disk; safe because no jar
    /// in a mods folder is ever rewritten in place, only added, deleted or renamed). Other mods-shared* folders of this
    /// account are removed (best-effort). Fail-soft: anything that goes wrong building the folder (disk full, access)
    /// is logged and the game loads the shared folder as it is, as before this existed. Never throws an IO error.
    /// </summary>
    public static string PrepareSharedLaunch(string gameDir, string sharedFolder, Compat.PlayerOverridePlan plan,
        Compat.LaunchSwap? swap, string? configId, Func<string, bool> isOriginsCopy, Action<string>? log = null)
    {
        var needed = swap != null || plan.WithheldPaths.Count > 0 || plan.SetAsidePaths.Count > 0;
        var name = !needed ? null : swap != null && configId != null ? SharedFolderName + "-" + configId : SharedFolderName;
        RemoveStaleSharedFolders(gameDir, name);
        if (name == null) return sharedFolder;
        try
        {
            var sharedFiles = Directory.EnumerateFiles(sharedFolder).ToList();
            // Every jar of the shared folder, the player's included, except the ones the plan keeps out.
            var desired = LaunchSet(sharedFiles, Enumerable.Empty<string>(), static _ => true, null, plan.Skips);
            if (swap != null) ApplySwap(desired, swap, sharedFiles, isOriginsCopy, plan.Skips);
            var folder = Path.Combine(gameDir, name);
            var sharedFull = Path.GetFullPath(sharedFolder);
            Sync(folder, desired, source => string.Equals(Path.GetDirectoryName(Path.GetFullPath(source)), sharedFull,
                StringComparison.OrdinalIgnoreCase));
            return folder;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            log?.Invoke($"Couldn't prepare {name} ({ex.Message}); loading the shared mods folder as it is.");
            return sharedFolder;
        }
    }

    // mods-shared and mods-shared-<id> folders this launch does not use: their links/copies are only ever for a launch.
    private static void RemoveStaleSharedFolders(string gameDir, string? keep)
    {
        try
        {
            if (!Directory.Exists(gameDir)) return;
            foreach (var dir in Directory.EnumerateDirectories(gameDir, SharedFolderName + "*"))
            {
                var dirName = Path.GetFileName(dir);
                if (!(dirName.Equals(SharedFolderName, StringComparison.OrdinalIgnoreCase)
                      || dirName.StartsWith(SharedFolderName + "-", StringComparison.OrdinalIgnoreCase))) continue;
                if (keep != null && dirName.Equals(keep, StringComparison.OrdinalIgnoreCase)) continue;
                try { Directory.Delete(dir, recursive: true); }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* a running game holds it: next launch */ }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* best-effort */ }
    }

    /// <summary>
    /// Makes <paramref name="folder"/> hold exactly <paramref name="desired"/>.
    /// A diff, not delete-and-recreate: another game of this account's folder
    /// may still hold some of these jars open (Windows file locks), so an
    /// unchanged set costs no file operations, a stale extra that is locked is
    /// left for the next launch, and a changed jar is copied (or, for a source
    /// <paramref name="linkable"/> allows, hard-linked) beside and renamed in —
    /// never rewritten in place. A hard link that can't be made (another volume,
    /// not Windows) falls back to a copy.
    /// </summary>
    public static void Sync(string folder, IReadOnlyDictionary<string, string> desired, Func<string, bool>? linkable = null)
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
                if (File.Exists(temp)) File.Delete(temp);
                if (!(linkable?.Invoke(source) == true && TryHardLink(temp, source)))
                {
                    File.Copy(source, temp, overwrite: true);
                    File.SetLastWriteTimeUtc(temp, File.GetLastWriteTimeUtc(source)); // a link already shares it
                }
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

    // A second name for the same file (NTFS hard link, same volume). False when it can't be made: copy instead.
    private static bool TryHardLink(string link, string existing) =>
        OperatingSystem.IsWindows() && CreateHardLinkW(link, existing, IntPtr.Zero);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateHardLinkW(string lpFileName, string lpExistingFileName, IntPtr lpSecurityAttributes);

    // Same length and last-write time: the copy is current (Sync stamps each
    // copy with its source's time; a hard link is the source).
    private static bool SameFile(string source, string copy)
    {
        if (!File.Exists(copy)) return false;
        var a = new FileInfo(source);
        var b = new FileInfo(copy);
        return a.Length == b.Length && a.LastWriteTimeUtc == b.LastWriteTimeUtc;
    }
}
