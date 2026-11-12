namespace OriginSetup.Engine;

// Where Origin may be installed — pure rules, unit-tested.
//
// Two hard constraints shape these rules:
//  1. The launcher updates ITSELF in place without admin rights
//     (UpdateService swaps its own exe), so the folder must be writable by the
//     player: never Program Files or Windows.
//  2. Origin always gets a folder of its own — picking "Documents" installs to
//     "Documents\OriginLauncher", never into Documents itself — and never shares
//     one: a folder that already holds someone else's files, and Origin's data
//     folder (worlds, accounts, settings), are refused outright. Uninstall removes
//     only what InstallManifest proves is Origin's (UninstallPlan).
public static class InstallLocation
{
    public const string FolderName = "OriginLauncher";
    public const string LauncherExe = "OriginLauncher.App.exe";
    public const string UninstallerExe = "uninstall.exe";
    private const string WriteProbe = ".origin-write-test";

    /// <summary>%LocalAppData%\Programs\OriginLauncher — the same default the Inno installer used.</summary>
    public static string Default =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", FolderName);

    /// <summary>
    /// The folder Origin will install into when the player picks <paramref name="picked"/>:
    /// the pick itself if it is not there yet, empty, or an existing Origin install,
    /// otherwise a new OriginLauncher folder inside it. A folder's NAME proves nothing
    /// ("D:\Origin" may be EA's old Origin client), so a busy folder always gets the subfolder.
    /// </summary>
    public static string Resolve(string picked)
    {
        var full = Path.GetFullPath(picked.Trim());
        if (IsDriveRoot(full))
            return Path.Combine(Path.GetPathRoot(full)!, FolderName);
        full = Trim(full);
        if (!Directory.Exists(full) || IsOriginInstall(full) || IsEmptyForOrigin(full))
            return full;
        return Path.Combine(full, FolderName);
    }

    /// <summary>
    /// Origin's launcher exe, or the manifest Setup writes, is in <paramref name="dir"/>. Never
    /// uninstall.exe alone: that is NSIS's default uninstaller name, so countless programs have one.
    /// </summary>
    public static bool IsOriginInstall(string dir) =>
        File.Exists(Path.Combine(dir, LauncherExe)) || File.Exists(Path.Combine(dir, InstallManifest.FileName));

    /// <summary>
    /// Nothing in <paramref name="dir"/> but what a cancelled Setup can leave behind (its
    /// staging folder, the write probe). A folder that can't be read counts as not empty.
    /// </summary>
    public static bool IsEmptyForOrigin(string dir)
    {
        try
        {
            return Directory.EnumerateFileSystemEntries(dir).Select(e => Path.GetFileName(e))
                .All(n => string.Equals(n, Installer.StagingFolder, StringComparison.OrdinalIgnoreCase)
                          || string.Equals(n, WriteProbe, StringComparison.OrdinalIgnoreCase));
        }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException) { return false; }
    }

    /// <summary>True when <paramref name="a"/> and <paramref name="b"/> are the same folder or one is inside the other.</summary>
    public static bool Overlaps(string a, string b)
    {
        var x = Trim(Path.GetFullPath(a)) + Path.DirectorySeparatorChar;
        var y = Trim(Path.GetFullPath(b)) + Path.DirectorySeparatorChar;
        return x.StartsWith(y, StringComparison.OrdinalIgnoreCase) || y.StartsWith(x, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Why <paramref name="dir"/> can't hold Origin, in words for the player,
    /// or null when it is fine. Writability is <see cref="ProbeWritable"/>'s job.
    /// </summary>
    /// <param name="dataRoot">Origin's data folder; null = the real one (<see cref="Handoff.DataRoot"/>). Tests pass their own.</param>
    public static string? Problem(string dir, IEnumerable<string> protectedRoots, string? dataRoot = null)
    {
        if (string.IsNullOrWhiteSpace(dir)) return "Choose a folder.";
        if (!Path.IsPathRooted(dir) || dir.StartsWith(@"\\", StringComparison.Ordinal))
            return "Choose a folder on this PC (not a network location).";
        if (dir.IndexOfAny(Path.GetInvalidPathChars()) >= 0) return "That folder name has characters Windows doesn't allow.";
        if (IsDriveRoot(dir)) return "Choose a folder, not a whole drive.";
        if (dir.Length > 180) return "That folder path is too long - choose a shorter one.";
        string full;
        try { full = Trim(Path.GetFullPath(dir)); }
        catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException || ex is PathTooLongException)
        {
            return "That folder name isn't valid.";
        }
        var withSep = full + Path.DirectorySeparatorChar;
        foreach (var root in protectedRoots.Where(r => !string.IsNullOrEmpty(r)))
        {
            if (withSep.StartsWith(Trim(Path.GetFullPath(root)) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                return "Origin can't go in Program Files or Windows - it updates itself, which needs a folder you own.";
        }
        // The data folder holds worlds and accounts: an app inside it (or around it) would put
        // them in the uninstaller's way.
        if (Overlaps(full, dataRoot ?? Handoff.DataRoot))
            return "That's where Origin keeps your worlds and settings. Choose another folder for the app.";
        // Someone else's folder: Origin would mix its files into theirs.
        if (Directory.Exists(full) && !IsOriginInstall(full) && !IsEmptyForOrigin(full))
            return "That folder already has other files in it. Choose an empty folder or a new one.";
        return null;
    }

    /// <summary>"Not enough space" in plain words, or null. Unknown drives pass (the install itself will tell).</summary>
    public static string? SpaceProblem(string dir, long bytesNeeded)
    {
        try
        {
            var root = Path.GetPathRoot(Path.GetFullPath(dir));
            if (string.IsNullOrEmpty(root)) return null;
            var drive = new DriveInfo(root);
            if (!drive.IsReady) return "That drive isn't available.";
            // Room for the unpack plus the files it replaces on an update.
            long needed = bytesNeeded + bytesNeeded / 10;
            if (drive.AvailableFreeSpace >= needed) return null;
            return $"Not enough free space on {root.TrimEnd('\\')} - Origin needs {needed / 1048576} MB, " +
                   $"{drive.AvailableFreeSpace / 1048576} MB is free.";
        }
        catch (Exception ex) when (ex is ArgumentException || ex is IOException || ex is UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>Program Files (both), Windows: folders Origin may never install under.</summary>
    public static IEnumerable<string> ProtectedRoots() => new[]
    {
        Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
        Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
        Environment.GetFolderPath(Environment.SpecialFolder.Windows),
    };

    /// <summary>
    /// Checks the folder can really be written (permissions, read-only media)
    /// by creating it and a scratch file, then undoes anything it created.
    /// Returns a player-facing problem, or null.
    /// </summary>
    public static string? ProbeWritable(string dir)
    {
        var created = new List<string>();
        try
        {
            for (var d = dir; d != null && !Directory.Exists(d); d = Path.GetDirectoryName(d))
                created.Add(d);
            Directory.CreateDirectory(dir);
            var probe = Path.Combine(dir, WriteProbe);
            File.WriteAllText(probe, "ok");
            File.Delete(probe);
            return null;
        }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException || ex is NotSupportedException)
        {
            return "Origin can't write to that folder. Choose one you have access to.";
        }
        finally
        {
            foreach (var d in created) // deepest first
            {
                try { if (Directory.Exists(d) && !Directory.EnumerateFileSystemEntries(d).Any()) Directory.Delete(d); }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
    }

    /// <summary>
    /// A drive root or a well-known personal/system folder (profile, Desktop, Documents,
    /// Downloads, Pictures, Music, Videos, OneDrive, AppData, Programs, Program Files, Windows): never Origin's own, whatever it holds.
    /// </summary>
    public static bool IsWellKnownFolder(string dir)
    {
        if (IsDriveRoot(dir)) return true;
        var full = Trim(Path.GetFullPath(dir));
        var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var known = new[]
            {
                Environment.SpecialFolder.UserProfile, Environment.SpecialFolder.Desktop, Environment.SpecialFolder.DesktopDirectory,
                Environment.SpecialFolder.MyDocuments, Environment.SpecialFolder.LocalApplicationData,
                Environment.SpecialFolder.ApplicationData, Environment.SpecialFolder.ProgramFiles,
                Environment.SpecialFolder.ProgramFilesX86, Environment.SpecialFolder.Windows,
                Environment.SpecialFolder.MyPictures, Environment.SpecialFolder.MyMusic, Environment.SpecialFolder.MyVideos,
                Environment.SpecialFolder.System, Environment.SpecialFolder.CommonApplicationData,
            }.Select(Environment.GetFolderPath)
            .Concat(new[]
            {
                string.IsNullOrEmpty(profile) ? "" : Path.Combine(profile, "Downloads"),
                string.IsNullOrEmpty(local) ? "" : Path.Combine(local, "Programs"),
                Environment.GetEnvironmentVariable("OneDrive") ?? "",
                string.IsNullOrEmpty(profile) ? "" : Path.GetDirectoryName(Trim(profile)) ?? "", // C:\Users
            });
        return known.Where(p => !string.IsNullOrEmpty(p))
            .Any(p => string.Equals(Trim(p), full, StringComparison.OrdinalIgnoreCase));
    }

    private static string Trim(string path) => path.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

    private static bool IsDriveRoot(string dir)
    {
        var root = Path.GetPathRoot(dir);
        return !string.IsNullOrEmpty(root)
               && string.Equals(Trim(root!), Trim(dir), StringComparison.OrdinalIgnoreCase);
    }
}
