using System.IO;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.GameFolders;

/// <summary>What the merge does with one entry of an account's old mods folder.</summary>
public enum ModMergeStep
{
    Move,          // the shared folder has nothing by that name: move it in
    MergeFolders,  // a folder on both sides: merge their contents entry by entry
    DropDuplicate, // the shared folder has a byte-identical copy: the account's copy is redundant
    KeepShared,    // different file, shared copy is newer (or same age): park the account's copy
    TakeAccounts,  // different file, account's copy is newer: park the shared copy, move the account's in
}

/// <summary>The facts the merge rule needs about one side of a name clash.</summary>
public readonly record struct ModMergeEntry(bool IsFolder, DateTime LastWriteUtc);

// One mods folder per Minecraft version, shared by every account (Will,
// 2026-10-12: "mods can be shared"). Worlds, settings, servers and configs stay
// in each account's own game folder (InstanceLayout); only mods/ moves up to
// instances/<version>/mods/.
//
// HOW the game finds it: Fabric Loader's own "fabric.modsFolder" system
// property (VersionManager adds it to every Fabric launch). Chosen over an NTFS
// junction from each account folder because it needs no filesystem tricks
// (no reparse points, nothing for antivirus or a non-NTFS drive to refuse),
// it is the mechanism the launcher already used for every "Origin-only" launch,
// and it is checked, not assumed: the property exists from Fabric Loader 0.15.0
// on (SystemProperties.MODS_FOLDER; absent in 0.14.x), and the launcher always
// installs the NEWEST loader Fabric's meta lists for the version (CmlLib
// FabricInstaller.Install -> GetFirstLoader), so every live version gets 0.16+.
// LoaderReadsModsFolderProperty guards a cached older install anyway.
//
// SAFE WITH SEVERAL GAMES OPEN: a running game holds its mod jars open. Every
// write to the shared folder is therefore one of: adding a new name, deleting,
// or an atomic rename/replace — never rewriting a jar in place. On Windows a
// jar a game holds open either refuses the delete/rename (the operation fails
// and is retried later) or lets it go while the running game keeps reading its
// open copy; either way that game is unaffected. VersionManager follows the
// same rule for Origin's own jars.
//
// MIGRATION (MergeVersion): every account's old mods/ for the version is merged
// into the shared folder — a union, byte-identical files deduplicated, and on a
// same-name clash the newer file keeps the name while the other is parked next
// to it as "<name>.before-shared" (Fabric and the Mods page ignore that name).
// Nothing the player added is ever deleted: the only deletes are of a file whose
// exact bytes are already in the shared folder, and of folders this code just
// emptied. Each step is one same-volume rename, so a crash leaves every entry at
// its old place or its new one and the next pass carries on (idempotent). An
// account whose game is still running is skipped until it closes. The merge
// runs at every launch of the version too, so a jar something drops into an
// account's mods/ (Mod Menu's own drag-and-drop writes there) joins the shared
// folder on the next launch.
public static class SharedMods
{
    public const string ParkSuffix = ".before-shared";
    public const string DisabledSuffix = ".jar.disabled";

    /// <summary>Fabric Loader's system property for the folder it loads mods from.</summary>
    public const string ModsFolderProperty = "fabric.modsFolder";

    /// <summary>The first Fabric Loader with <see cref="ModsFolderProperty"/> (checked against the loader's tags: 0.14.24 lacks it, 0.15.0 has it).</summary>
    public static readonly Version FirstLoaderWithModsFolder = new(0, 15, 0);

    /// <summary>The JVM argument that points Fabric at a mods folder.</summary>
    public static string JvmArgument(string modsDir) => $"-D{ModsFolderProperty}={modsDir}";

    private static readonly Regex LoaderName = new(@"^fabric-loader-(\d+(?:\.\d+)+)", RegexOptions.IgnoreCase);

    /// <summary>The Fabric Loader version in a profile name like "fabric-loader-0.16.14-1.21.1", or null.</summary>
    public static Version? LoaderVersionOf(string? versionName)
    {
        if (versionName == null) return null;
        var match = LoaderName.Match(versionName);
        return match.Success && Version.TryParse(match.Groups[1].Value, out var version) ? version : null;
    }

    /// <summary>True when that installed loader honours <see cref="ModsFolderProperty"/>. Unknown = false (reinstall).</summary>
    public static bool LoaderReadsModsFolderProperty(string? versionName) =>
        LoaderVersionOf(versionName) is { } version && version >= FirstLoaderWithModsFolder;

    // ------------------------------------------------------------------ rules

    /// <summary>
    /// The merge rule for one entry of an account's mods folder.
    /// <paramref name="shared"/> is what the shared folder has under the same
    /// name (null = nothing); <paramref name="sameBytes"/> is only asked for two files.
    /// </summary>
    public static ModMergeStep Decide(ModMergeEntry account, ModMergeEntry? shared, Func<bool> sameBytes)
    {
        if (shared is not { } other) return ModMergeStep.Move;
        if (account.IsFolder && other.IsFolder) return ModMergeStep.MergeFolders;
        // A file against a folder can't be compared: the shared one stays.
        if (account.IsFolder != other.IsFolder) return ModMergeStep.KeepShared;
        if (sameBytes()) return ModMergeStep.DropDuplicate;
        return account.LastWriteUtc > other.LastWriteUtc ? ModMergeStep.TakeAccounts : ModMergeStep.KeepShared;
    }

    /// <summary>
    /// For a "foo.jar.disabled" whose enabled twin "foo.jar" is also in the
    /// folder (one account had the mod on, another off): the enabled one wins —
    /// someone was playing with it — and the disabled copy has to go, or the
    /// Mods page would list the mod twice. Returns the disabled names to resolve.
    /// </summary>
    public static IReadOnlyList<string> DisabledTwins(IEnumerable<string> fileNames)
    {
        var names = fileNames.ToList();
        var present = new HashSet<string>(names, StringComparer.OrdinalIgnoreCase);
        return names
            .Where(n => n.EndsWith(DisabledSuffix, StringComparison.OrdinalIgnoreCase)
                        && present.Contains(n[..^".disabled".Length]))
            .OrderBy(n => n, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    // -------------------------------------------------------------- migration

    /// <summary>True when some account folder of the version still has its own mods folder.</summary>
    public static bool NeedsMerge(string versionRoot) => AccountModsFolders(versionRoot).Count > 0;

    /// <summary>
    /// Merges every account's mods/ under <paramref name="versionRoot"/> into
    /// the version's shared mods folder. Returns true when nothing is left to
    /// merge. Never throws.
    /// </summary>
    /// <param name="isGameRunning">Probe for an account game folder whose game is open (default: its logs/latest.log is held).</param>
    public static bool MergeVersion(string versionRoot, Func<string, bool>? isGameRunning = null, Action<string>? log = null)
    {
        log ??= _ => { };
        isGameRunning ??= InstanceMigration.IsGameRunningIn;
        var version = Path.GetFileName(versionRoot);
        var shared = Path.Combine(versionRoot, InstanceLayout.ModsFolder);
        var complete = true;
        try
        {
            foreach (var (key, mods) in AccountModsFolders(versionRoot))
            {
                if (isGameRunning(Path.GetDirectoryName(mods)!))
                {
                    log($"{version}: {key}'s game is open — its mods join the shared folder after it closes");
                    complete = false;
                    continue;
                }
                Directory.CreateDirectory(shared);
                var done = MergeFolder(mods, shared, log);
                if (done && !Directory.EnumerateFileSystemEntries(mods).Any())
                    Directory.Delete(mods); // emptied by the moves above
                log($"{version}: {key}'s mods merged into the shared folder" + (done ? "" : " (some files were busy — retried next time)"));
                complete &= done;
            }
            complete &= ResolveDisabledTwins(shared, log);
        }
        catch (Exception ex)
        {
            log($"{version}: mods merge paused: {ex.GetType().Name}: {ex.Message}");
            return false;
        }
        return complete;
    }

    /// <summary>Every account game folder's own mods folder that exists, as (account key, path), sorted by key.</summary>
    public static IReadOnlyList<(string Key, string Dir)> AccountModsFolders(string versionRoot)
    {
        try
        {
            var accounts = Path.Combine(versionRoot, InstanceLayout.AccountsFolder);
            if (!Directory.Exists(accounts)) return Array.Empty<(string, string)>();
            return Directory.EnumerateDirectories(accounts)
                .Select(dir => (Key: Path.GetFileName(dir), Dir: Path.Combine(dir, InstanceLayout.ModsFolder)))
                .Where(e => InstanceLayout.IsAccountKey(e.Key) && Directory.Exists(e.Dir))
                .OrderBy(e => e.Key, StringComparer.Ordinal)
                .ToList();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return Array.Empty<(string, string)>();
        }
    }

    // Every entry of source into destination by Decide's rule. True when all
    // of them were handled (a busy file leaves the rest for the next pass).
    private static bool MergeFolder(string source, string destination, Action<string> log)
    {
        var complete = true;
        foreach (var entry in Directory.EnumerateFileSystemEntries(source).OrderBy(p => p, StringComparer.Ordinal).ToList())
            complete &= MergeEntry(entry, Path.Combine(destination, Path.GetFileName(entry)), log);
        return complete;
    }

    private static bool MergeEntry(string source, string destination, Action<string> log)
    {
        try
        {
            var sourceIsFolder = Directory.Exists(source);
            if (!sourceIsFolder && !File.Exists(source)) return true; // already gone

            var step = Decide(Facts(source, sourceIsFolder), FactsOrNull(destination), () => SameBytes(source, destination));
            switch (step)
            {
                case ModMergeStep.Move:
                    Move(source, destination, sourceIsFolder);
                    return true;

                case ModMergeStep.MergeFolders:
                    var complete = MergeFolder(source, destination, log);
                    if (complete && !Directory.EnumerateFileSystemEntries(source).Any())
                        Directory.Delete(source); // emptied by the moves above
                    return complete;

                case ModMergeStep.DropDuplicate:
                    File.Delete(source); // the shared folder has these exact bytes
                    return true;

                case ModMergeStep.TakeAccounts:
                {
                    // Two renames. A crash between them leaves the name free and
                    // the account's file still in place: the next pass moves it.
                    var aside = FreeName(destination + ParkSuffix);
                    Move(destination, aside, isFolder: false);
                    Move(source, destination, isFolder: false);
                    log($"kept the newer {Path.GetFileName(destination)} (the older one is {Path.GetFileName(aside)})");
                    return true;
                }

                default: // KeepShared
                {
                    var aside = FreeName(destination + ParkSuffix);
                    Move(source, aside, sourceIsFolder);
                    log($"kept the newer {Path.GetFileName(destination)} (the older one is {Path.GetFileName(aside)})");
                    return true;
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            log($"could not merge {source} yet: {ex.Message}");
            return false;
        }
    }

    // The disabled half of an enabled/disabled pair: dropped when it is the
    // same jar, otherwise parked like any other clash.
    private static bool ResolveDisabledTwins(string sharedMods, Action<string> log)
    {
        if (!Directory.Exists(sharedMods)) return true;
        var complete = true;
        var names = Directory.EnumerateFiles(sharedMods).Select(Path.GetFileName).OfType<string>();
        foreach (var disabled in DisabledTwins(names))
        {
            var disabledPath = Path.Combine(sharedMods, disabled);
            var enabledPath = Path.Combine(sharedMods, disabled[..^".disabled".Length]);
            try
            {
                if (SameBytes(disabledPath, enabledPath))
                {
                    File.Delete(disabledPath);
                }
                else
                {
                    var aside = FreeName(disabledPath + ParkSuffix);
                    File.Move(disabledPath, aside);
                    log($"{Path.GetFileName(enabledPath)} is on for some account and off for another: kept it on (the off copy is {Path.GetFileName(aside)})");
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                log($"could not settle {disabled} yet: {ex.Message}");
                complete = false;
            }
        }
        return complete;
    }

    private static ModMergeEntry Facts(string path, bool isFolder) =>
        new(isFolder, isFolder ? Directory.GetLastWriteTimeUtc(path) : File.GetLastWriteTimeUtc(path));

    private static ModMergeEntry? FactsOrNull(string path)
    {
        if (Directory.Exists(path)) return Facts(path, isFolder: true);
        if (File.Exists(path)) return Facts(path, isFolder: false);
        return null;
    }

    private static void Move(string source, string destination, bool isFolder)
    {
        if (isFolder) Directory.Move(source, destination);
        else File.Move(source, destination);
    }

    private static string FreeName(string path)
    {
        var candidate = path;
        for (var n = 2; Directory.Exists(candidate) || File.Exists(candidate); n++)
            candidate = $"{path}-{n}";
        return candidate;
    }

    /// <summary>True when both files hold exactly the same bytes. Reads with sharing so a running game never blocks it.</summary>
    public static bool SameBytes(string a, string b)
    {
        try
        {
            if (!File.Exists(a) || !File.Exists(b)) return false;
            if (new FileInfo(a).Length != new FileInfo(b).Length) return false;
            const FileShare share = FileShare.ReadWrite | FileShare.Delete;
            using var left = new FileStream(a, FileMode.Open, FileAccess.Read, share, 1 << 16);
            using var right = new FileStream(b, FileMode.Open, FileAccess.Read, share, 1 << 16);
            var bufferA = new byte[1 << 16];
            var bufferB = new byte[1 << 16];
            while (true)
            {
                var read = left.ReadAtLeast(bufferA, bufferA.Length, throwOnEndOfStream: false);
                var readB = right.ReadAtLeast(bufferB, bufferB.Length, throwOnEndOfStream: false);
                if (read != readB) return false;
                if (read == 0) return true;
                if (!bufferA.AsSpan(0, read).SequenceEqual(bufferB.AsSpan(0, read))) return false;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false; // unreadable: treat as different, which only ever parks — never deletes
        }
    }
}
