using System.IO;
using OriginLauncher.App.Core.Import.GameFiles;

namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// The machine folders the import looks in, as plain strings (a test passes temp folders). On a Mac,
/// AppData and LocalAppData are both ~/Library/Application Support (where every Mac launcher keeps its
/// data) and Mac is true: a few launchers name their folder differently there (see ImportRoots).
/// </summary>
public sealed record MachineFolders(string AppData, string LocalAppData, string UserProfile, string Documents, bool Mac = false)
{
    public static MachineFolders Current() => OperatingSystem.IsMacOS()
        ? ForMac(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile))
        : new(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments));

    /// <summary>
    /// A Mac home folder's layout, built from HOME itself (never .NET's ApplicationData, which is
    /// ~/.config on macOS). Paths are case-sensitive on a case-sensitive volume: the names below are
    /// the exact ones each launcher creates.
    /// </summary>
    public static MachineFolders ForMac(string home)
    {
        var support = Path.Combine(home, "Library", "Application Support");
        return new MachineFolders(support, support, home, Path.Combine(home, "Documents"), Mac: true);
    }
}

// Where each launcher keeps its data on a normal Windows PC or Mac. Pure: it only asks "does this
// folder exist" (a function the caller can replace), so the roots can be unit tested and the engine
// itself never learns a special-folder path. A launcher that is not installed gets null. Portable or
// moved installs are not guessed at: the player uses "Browse for a folder".
//
// The Mac locations and where each comes from (checked 2026-11-02):
//   Official   ~/Library/Application Support/minecraft (no dot)  https://minecraft.wiki/w/.minecraft (location table)
//   Prism      ~/Library/Application Support/PrismLauncher       https://prismlauncher.org/wiki/getting-started/data-location
//   Modrinth   ~/Library/Application Support/ModrinthApp         https://support.modrinth.com/en/articles/8797641
//              (com.modrinth.theseus before 0.8.0, same article)
//   CurseForge ~/Documents/curseforge/minecraft (+ /Instances)   https://support.curseforge.com/support/solutions/articles/9000218572
//              (the app's standard folder on a Mac); ~/curseforge/minecraft is also read
//   Lunar      ~/.lunarclient                                    https://support.lunarclient.com/support/solutions/articles/60000752051
//   Feather    ~/Library/Application Support/.feather            no Feather doc names it: the Windows folder is
//              %APPDATA%\.feather, i.e. Electron's appData + ".feather", and Electron's appData on macOS is
//              ~/Library/Application Support (https://www.electronjs.org/docs/latest/api/app#appgetpathname)
//   Badlion    ~/Library/Application Support/Badlion Client      https://formulae.brew.sh/cask/badlion-client (zap list)
// No Microsoft Store edition on a Mac: the official launcher's Java runtimes live in minecraft/runtime,
// which GameFileSources already reads as the legacy runtime folder.
public static class ImportRoots
{
    // The Microsoft Store edition of the official launcher keeps its Java runtimes here.
    private const string StorePackage = "Microsoft.4297127D64EC6_8wekyb3d8bbwe";

    public static SourceRoots Sources(MachineFolders f, Func<string, bool>? exists = null)
    {
        exists ??= Directory.Exists;
        return new SourceRoots
        {
            OfficialMinecraftDir = IfExists(OfficialDir(f), exists),
            PrismDataDir = IfExists(Path.Combine(f.AppData, "PrismLauncher"), exists),
            ModrinthDir = ModrinthRoot(f, exists),
            CurseForgeDir = CurseForgeMinecraft(f, exists) is { } cf ? Path.Combine(cf, "Instances") : null,
            LunarDir = IfExists(Path.Combine(f.UserProfile, ".lunarclient"), exists),
            FeatherDir = IfExists(Path.Combine(f.AppData, ".feather"), exists),
            BadlionDir = IfExists(Path.Combine(f.AppData, "Badlion Client"), exists),
        };
    }

    public static GameFileRoots GameFiles(MachineFolders f, string instancesRoot, Func<string, bool>? exists = null)
    {
        exists ??= Directory.Exists;
        var modrinth = ModrinthRoot(f, exists);
        var runtimes = new List<string>();
        if (!f.Mac)
        {
            var store = Path.Combine(f.LocalAppData, "Packages", StorePackage, "LocalCache", "Local", "runtime");
            if (exists(store)) runtimes.Add(store);
        }
        return new GameFileRoots
        {
            InstancesRoot = instancesRoot,
            OfficialMinecraftDir = IfExists(OfficialDir(f), exists),
            OfficialRuntimeRoots = runtimes,
            PrismDataDir = IfExists(Path.Combine(f.AppData, "PrismLauncher"), exists),
            ModrinthMetaDir = modrinth == null ? null : IfExists(Path.Combine(modrinth, "meta"), exists),
            CurseForgeRoot = CurseForgeMinecraft(f, exists),
        };
    }

    // %APPDATA%\.minecraft on Windows; plain "minecraft" (no dot) in Application Support on a Mac.
    private static string OfficialDir(MachineFolders f) => Path.Combine(f.AppData, f.Mac ? "minecraft" : ".minecraft");

    // ModrinthApp, or the older com.modrinth.theseus beside it (both OSes).
    private static string? ModrinthRoot(MachineFolders f, Func<string, bool> exists) =>
        IfExists(Path.Combine(f.AppData, "ModrinthApp"), exists) ?? IfExists(Path.Combine(f.AppData, "com.modrinth.theseus"), exists);

    // Windows: %USERPROFILE%\curseforge\minecraft first, then Documents. Mac: Documents is the app's default, so first.
    private static string? CurseForgeMinecraft(MachineFolders f, Func<string, bool> exists)
    {
        var home = Path.Combine(f.UserProfile, "curseforge", "minecraft");
        var documents = Path.Combine(f.Documents, "curseforge", "minecraft");
        return f.Mac
            ? IfExists(documents, exists) ?? IfExists(home, exists)
            : IfExists(home, exists) ?? IfExists(documents, exists);
    }

    private static string? IfExists(string path, Func<string, bool> exists) => exists(path) ? path : null;
}
