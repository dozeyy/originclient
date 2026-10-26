using System.IO;

namespace OriginLauncher.App.Core.Profiles;

/// <summary>
/// What profiles do on disk, given the folders (OriginPaths owns where they
/// are). Nothing here rewrites a jar in place: files are copied beside and
/// renamed in, or deleted whole, so a game that has a copy open is never hurt.
/// </summary>
public static class ProfileFolders
{
    public const string ParkedSuffix = ".deleting";
    private const string TempSuffix = ".download";

    /// <summary>
    /// Copies every file under <paramref name="source"/> into
    /// <paramref name="destination"/> (made if needed) — a duplicated profile's
    /// mods. A missing source is an empty profile: nothing to copy. Returns
    /// null on success, otherwise a sentence for the player.
    /// </summary>
    public static string? CopyMods(string source, string destination)
    {
        try
        {
            Directory.CreateDirectory(destination);
            if (!Directory.Exists(source)) return null;
            CopyFolder(source, destination);
            return null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return "Couldn't copy the mods: " + ex.Message;
        }
    }

    private static void CopyFolder(string source, string destination)
    {
        Directory.CreateDirectory(destination);
        foreach (var file in Directory.EnumerateFiles(source))
        {
            var name = Path.GetFileName(file);
            if (name.EndsWith(TempSuffix, StringComparison.OrdinalIgnoreCase)) continue; // an unfinished download
            var target = Path.Combine(destination, name);
            var temp = target + TempSuffix;
            File.Copy(file, temp, overwrite: true);
            File.SetLastWriteTimeUtc(temp, File.GetLastWriteTimeUtc(file));
            File.Move(temp, target, overwrite: true);
        }
        foreach (var folder in Directory.EnumerateDirectories(source))
            CopyFolder(folder, Path.Combine(destination, Path.GetFileName(folder)));
    }

    /// <summary>
    /// Removes a profile's whole folder (<paramref name="profileDir"/> =
    /// instances/&lt;version&gt;/profiles/&lt;id&gt;). It is first RENAMED aside —
    /// one atomic step that fails cleanly when something still holds a file in
    /// it, leaving the profile exactly as it was — and only then deleted. If
    /// that last delete is refused, the parked folder is swept on a later visit
    /// (<see cref="SweepParked"/>). Returns null on success, else a sentence.
    /// </summary>
    public static string? Delete(string profileDir)
    {
        if (!Directory.Exists(profileDir)) return null;
        var parked = FreeName(profileDir.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + ParkedSuffix);
        try
        {
            Directory.Move(profileDir, parked);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return "Couldn't remove the profile's mods - close Minecraft and try again.";
        }
        try { Directory.Delete(parked, recursive: true); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* swept next time */ }
        return null;
    }

    /// <summary>Deletes folders a past <see cref="Delete"/> parked but could not finish removing. Best effort.</summary>
    public static void SweepParked(string profilesDir)
    {
        try
        {
            if (!Directory.Exists(profilesDir)) return;
            foreach (var parked in Directory.EnumerateDirectories(profilesDir)
                         .Where(d => Path.GetFileName(d).Contains(ParkedSuffix, StringComparison.Ordinal)))
            {
                try { Directory.Delete(parked, recursive: true); }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* still busy */ }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Unreadable folder: nothing to sweep.
        }
    }

    /// <summary>How many mods (on or off) a profile's mods folder holds.</summary>
    public static int CountMods(string modsDir)
    {
        try
        {
            if (!Directory.Exists(modsDir)) return 0;
            return Directory.EnumerateFiles(modsDir).Count(f =>
            {
                var name = Path.GetFileName(f);
                return name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)
                       || name.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase);
            });
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return 0;
        }
    }

    private static string FreeName(string path)
    {
        var candidate = path;
        for (var n = 2; Directory.Exists(candidate) || File.Exists(candidate); n++)
            candidate = $"{path}-{n}";
        return candidate;
    }
}
