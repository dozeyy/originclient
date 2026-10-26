namespace OriginSetup.Engine;

// Where Origin may be installed — pure rules, unit-tested.
//
// Two hard constraints shape these rules:
//  1. The launcher updates ITSELF in place without admin rights
//     (UpdateService swaps its own exe), so the folder must be writable by the
//     player: never Program Files or Windows.
//  2. Uninstall deletes the whole install folder. So Origin always gets a
//     folder of its own — picking "Documents" installs to
//     "Documents\OriginLauncher", never into Documents itself.
public static class InstallLocation
{
    public const string FolderName = "OriginLauncher";
    public const string LauncherExe = "OriginLauncher.App.exe";
    public const string UninstallerExe = "uninstall.exe";

    /// <summary>%LocalAppData%\Programs\OriginLauncher — the same default the Inno installer used.</summary>
    public static string Default =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", FolderName);

    /// <summary>
    /// The folder Origin will install into when the player picks <paramref name="picked"/>:
    /// the pick itself if it is already Origin's own folder (named like one, empty,
    /// not there yet, or an existing Origin install), otherwise a new
    /// OriginLauncher folder inside it.
    /// </summary>
    public static string Resolve(string picked)
    {
        var full = Path.GetFullPath(picked.Trim());
        if (IsDriveRoot(full))
            return Path.Combine(Path.GetPathRoot(full)!, FolderName);
        full = Trim(full);
        var name = Path.GetFileName(full);
        bool ownName = string.Equals(name, FolderName, StringComparison.OrdinalIgnoreCase)
                       || string.Equals(name, "Origin Launcher", StringComparison.OrdinalIgnoreCase)
                       || string.Equals(name, "Origin", StringComparison.OrdinalIgnoreCase);
        if (ownName || !Directory.Exists(full) || IsOriginInstall(full) || !Directory.EnumerateFileSystemEntries(full).Any())
            return full;
        return Path.Combine(full, FolderName);
    }

    public static bool IsOriginInstall(string dir) =>
        File.Exists(Path.Combine(dir, LauncherExe)) || File.Exists(Path.Combine(dir, UninstallerExe));

    /// <summary>
    /// Why <paramref name="dir"/> can't hold Origin, in words for the player,
    /// or null when it is fine. Shape rules only — see <see cref="ProbeWritable"/>.
    /// </summary>
    public static string? Problem(string dir, IEnumerable<string> protectedRoots)
    {
        if (string.IsNullOrWhiteSpace(dir)) return "Choose a folder.";
        if (!Path.IsPathRooted(dir) || dir.StartsWith(@"\\", StringComparison.Ordinal))
            return "Choose a folder on this PC (not a network location).";
        if (dir.IndexOfAny(Path.GetInvalidPathChars()) >= 0) return "That folder name has characters Windows doesn't allow.";
        if (IsDriveRoot(dir)) return "Choose a folder, not a whole drive.";
        if (dir.Length > 180) return "That folder path is too long - choose a shorter one.";
        string withSep;
        try { withSep = Trim(Path.GetFullPath(dir)) + Path.DirectorySeparatorChar; }
        catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException || ex is PathTooLongException)
        {
            return "That folder name isn't valid.";
        }
        foreach (var root in protectedRoots.Where(r => !string.IsNullOrEmpty(r)))
        {
            if (withSep.StartsWith(Trim(Path.GetFullPath(root)) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                return "Origin can't go in Program Files or Windows - it updates itself, which needs a folder you own.";
        }
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
            var probe = Path.Combine(dir, ".origin-write-test");
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
    /// True when it is safe to delete <paramref name="dir"/> recursively on
    /// uninstall: it holds Origin's files and is not a drive root or a
    /// well-known personal/system folder.
    /// </summary>
    public static bool SafeToDelete(string dir)
    {
        if (!Directory.Exists(dir) || !IsOriginInstall(dir) || IsDriveRoot(dir)) return false;
        var full = Trim(Path.GetFullPath(dir));
        var never = new[]
        {
            Environment.SpecialFolder.UserProfile, Environment.SpecialFolder.Desktop, Environment.SpecialFolder.DesktopDirectory,
            Environment.SpecialFolder.MyDocuments, Environment.SpecialFolder.LocalApplicationData,
            Environment.SpecialFolder.ApplicationData, Environment.SpecialFolder.ProgramFiles,
            Environment.SpecialFolder.ProgramFilesX86, Environment.SpecialFolder.Windows,
        }.Select(Environment.GetFolderPath).Where(p => !string.IsNullOrEmpty(p)).Select(Trim);
        var downloads = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        return !never.Concat(new[] { downloads, Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs") })
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
