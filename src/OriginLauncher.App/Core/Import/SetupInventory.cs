using System.IO;

namespace OriginLauncher.App.Core.Import;

// What the import drawer shows before any plan: which launchers are on this PC and, per setup, how many worlds,
// packs and so on it has ("4 versions, 12 worlds"). Folder listings only: no file is opened, so nothing here can
// read a secret. Runs on the thread pool (the backend calls it there), never on the UI thread.

/// <summary>What one setup holds, by kind. Counts are what is on disk, not what an import would copy.</summary>
public sealed record SetupContents(
    int Mods = 0, int Worlds = 0, int Screenshots = 0, int ResourcePacks = 0, int ShaderPacks = 0,
    bool Servers = false, bool Settings = false)
{
    public static readonly SetupContents Empty = new();

    /// <summary>The count for one category (Servers and Settings count as 1 when present).</summary>
    public int Count(ImportCategories one) => one switch
    {
        ImportCategories.Mods => Mods,
        ImportCategories.Worlds => Worlds,
        ImportCategories.Screenshots => Screenshots,
        ImportCategories.ResourcePacks => ResourcePacks,
        ImportCategories.ShaderPacks => ShaderPacks,
        ImportCategories.Servers => Servers ? 1 : 0,
        ImportCategories.Settings => Settings ? 1 : 0,
        _ => 0,
    };
}

public static class SetupInventory
{
    /// <summary>Counts what <paramref name="setup"/> holds. A folder that can't be listed counts as empty.</summary>
    public static SetupContents Read(DetectedInstance setup) => new(
        Mods: Count(setup.ModsFolder, "*.jar", files: true),
        Worlds: Worlds(setup.SavesFolder),
        Screenshots: Count(setup.ScreenshotsFolder, "*.png", files: true),
        ResourcePacks: Packs(Path.Combine(setup.GameDir, "resourcepacks")),
        ShaderPacks: Packs(setup.ShaderPacksFolder),
        Servers: Exists(Path.Combine(setup.GameDir, "servers.dat")),
        Settings: Exists(Path.Combine(setup.GameDir, "options.txt")));

    /// <summary>The launchers that are installed on this PC (their folder exists), whether or not they hold a setup.</summary>
    public static IReadOnlySet<SourceKind> Installed(SourceRoots roots)
    {
        var found = new HashSet<SourceKind>();
        void Has(SourceKind kind, params string?[] dirs)
        {
            foreach (var dir in dirs)
                if (!string.IsNullOrEmpty(dir) && DirExists(dir)) { found.Add(kind); return; }
        }
        Has(SourceKind.OfficialLauncher, roots.OfficialMinecraftDir);
        Has(SourceKind.Prism, roots.PrismDataDir, roots.MultiMcDir);
        Has(SourceKind.Modrinth, roots.ModrinthDir);
        Has(SourceKind.CurseForge, roots.CurseForgeDir);
        Has(SourceKind.Lunar, roots.LunarDir);
        Has(SourceKind.Feather, roots.FeatherDir);
        Has(SourceKind.Badlion, roots.BadlionDir);
        // LabyMod 4 lives inside .minecraft (PvpClientSource).
        if (roots.OfficialMinecraftDir != null) Has(SourceKind.LabyMod, Path.Combine(roots.OfficialMinecraftDir, "labymod-neo"));
        return found;
    }

    private static int Worlds(string saves)
    {
        try
        {
            return DirExists(saves) ? Directory.EnumerateDirectories(saves).Count(w => File.Exists(Path.Combine(w, "level.dat"))) : 0;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return 0; }
    }

    private static int Packs(string dir)
    {
        try
        {
            if (!DirExists(dir)) return 0;
            return Directory.EnumerateFiles(dir, "*.zip").Count() + Directory.EnumerateDirectories(dir).Count();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return 0; }
    }

    private static int Count(string dir, string pattern, bool files)
    {
        try
        {
            if (!DirExists(dir)) return 0;
            return files ? Directory.EnumerateFiles(dir, pattern).Count() : Directory.EnumerateDirectories(dir, pattern).Count();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return 0; }
    }

    private static bool Exists(string file)
    {
        try { return File.Exists(file); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return false; }
    }

    private static bool DirExists(string dir)
    {
        try { return Directory.Exists(dir); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return false; }
    }
}
