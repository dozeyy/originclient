using System.IO;
using OriginLauncher.App.Core.Import.GameFiles;

namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>The Windows folders the import looks in, as plain strings (a test passes temp folders).</summary>
public sealed record MachineFolders(string AppData, string LocalAppData, string UserProfile, string Documents)
{
    public static MachineFolders Current() => new(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments));
}

// Where each launcher keeps its data on a normal Windows PC. Pure: it only asks "does this folder
// exist" (a function the caller can replace), so the roots can be unit tested and the engine itself
// never learns a special-folder path. A launcher that is not installed gets null. Portable or moved
// installs are not guessed at: the player uses "Browse for a folder".
public static class ImportRoots
{
    // The Microsoft Store edition of the official launcher keeps its Java runtimes here.
    private const string StorePackage = "Microsoft.4297127D64EC6_8wekyb3d8bbwe";

    public static SourceRoots Sources(MachineFolders f, Func<string, bool>? exists = null)
    {
        exists ??= Directory.Exists;
        return new SourceRoots
        {
            OfficialMinecraftDir = IfExists(Path.Combine(f.AppData, ".minecraft"), exists),
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
        var store = Path.Combine(f.LocalAppData, "Packages", StorePackage, "LocalCache", "Local", "runtime");
        if (exists(store)) runtimes.Add(store);
        return new GameFileRoots
        {
            InstancesRoot = instancesRoot,
            OfficialMinecraftDir = IfExists(Path.Combine(f.AppData, ".minecraft"), exists),
            OfficialRuntimeRoots = runtimes,
            PrismDataDir = IfExists(Path.Combine(f.AppData, "PrismLauncher"), exists),
            ModrinthMetaDir = modrinth == null ? null : IfExists(Path.Combine(modrinth, "meta"), exists),
            CurseForgeRoot = CurseForgeMinecraft(f, exists),
        };
    }

    // %APPDATA%\ModrinthApp, or the older com.modrinth.theseus beside it.
    private static string? ModrinthRoot(MachineFolders f, Func<string, bool> exists) =>
        IfExists(Path.Combine(f.AppData, "ModrinthApp"), exists) ?? IfExists(Path.Combine(f.AppData, "com.modrinth.theseus"), exists);

    // %USERPROFILE%\curseforge\minecraft, or the one under Documents.
    private static string? CurseForgeMinecraft(MachineFolders f, Func<string, bool> exists) =>
        IfExists(Path.Combine(f.UserProfile, "curseforge", "minecraft"), exists)
        ?? IfExists(Path.Combine(f.Documents, "curseforge", "minecraft"), exists);

    private static string? IfExists(string path, Func<string, bool> exists) => exists(path) ? path : null;
}
