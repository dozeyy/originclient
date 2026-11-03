using System.IO;
using OriginLauncher.App.Core.Import.GameFiles;

namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// The machine folders the import looks in, as plain strings (a test passes temp folders). On a Mac,
/// AppData and LocalAppData are both ~/Library/Application Support (where every Mac launcher keeps its
/// data) and Mac is true: a few launchers name their folder differently there (see ImportRoots). On
/// Linux, AppData is XDG_CONFIG_HOME (~/.config), LocalAppData is XDG_DATA_HOME (~/.local/share) and
/// Linux is true: the official launcher lives straight in home and Prism/Modrinth in the data folder.
/// </summary>
public sealed record MachineFolders(string AppData, string LocalAppData, string UserProfile, string Documents, bool Mac = false, bool Linux = false)
{
    public static MachineFolders Current()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (OperatingSystem.IsMacOS()) return ForMac(home);
        // .NET on Linux resolves these two from XDG_DATA_HOME / XDG_CONFIG_HOME (falling back to
        // ~/.local/share and ~/.config when unset or not absolute): the same rule every launcher follows.
        if (OperatingSystem.IsLinux())
            return ForLinux(home,
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData));
        return new(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            home,
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments));
    }

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

    /// <summary>
    /// A Linux home folder's layout: xdgData = XDG_DATA_HOME, xdgConfig = XDG_CONFIG_HOME (empty or
    /// null = the XDG defaults ~/.local/share and ~/.config). Documents is plain ~/Documents
    /// (CurseForge on Linux reads ~/curseforge first anyway). Case-sensitive: ImportRoots uses the
    /// exact names each launcher creates.
    /// </summary>
    public static MachineFolders ForLinux(string home, string? xdgData = null, string? xdgConfig = null) => new(
        AppData: string.IsNullOrEmpty(xdgConfig) ? Path.Combine(home, ".config") : xdgConfig,
        LocalAppData: string.IsNullOrEmpty(xdgData) ? Path.Combine(home, ".local", "share") : xdgData,
        UserProfile: home,
        Documents: Path.Combine(home, "Documents"),
        Linux: true);

    /// <summary>A Flatpak app's own data folder (its XDG_DATA_HOME inside the sandbox): ~/.var/app/&lt;id&gt;/data.</summary>
    internal string FlatpakData(string appId) => Path.Combine(UserProfile, ".var", "app", appId, "data");
}

// Where each launcher keeps its data on a normal Windows PC, Mac or Linux machine. Pure: it only asks
// "does this folder exist" (a function the caller can replace), so the roots can be unit tested and
// the engine itself never learns a special-folder path. A launcher that is not installed gets null.
// Portable or moved installs are not guessed at: the player uses "Browse for a folder".
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
//
// The Linux locations (checked 2026-11-03; XDG = XDG_DATA_HOME, default ~/.local/share):
//   Official   ~/.minecraft                                      https://minecraft.wiki/w/.minecraft (location table)
//              Flathub's com.mojang.Minecraft keeps ~/.var/app/com.mojang.Minecraft/.minecraft (read second).
//              Java runtimes: ~/.minecraft/runtime, read by GameFileSources as the legacy runtime folder.
//   Prism      XDG/PrismLauncher                                 https://prismlauncher.org/wiki/getting-started/data-location
//              Flatpak: ~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher (same page)
//   Modrinth   XDG/ModrinthApp (com.modrinth.theseus before 0.8) https://support.modrinth.com/en/articles/8797641
//              Flatpak: ~/.var/app/com.modrinth.ModrinthApp/data/ModrinthApp (the app's XDG data in the sandbox)
//   CurseForge ~/curseforge/minecraft (the Linux app's default), then ~/Documents/curseforge/minecraft
//   Lunar      ~/.lunarclient (every OS)
//   Feather    XDG_CONFIG_HOME/.feather (Electron's appData on Linux is ~/.config)
//   Badlion    XDG_CONFIG_HOME/Badlion Client (Electron appData; no official Linux build, read if present)
// No Microsoft Store edition on Linux either.
public static class ImportRoots
{
    // The Microsoft Store edition of the official launcher keeps its Java runtimes here.
    private const string StorePackage = "Microsoft.4297127D64EC6_8wekyb3d8bbwe";

    public static SourceRoots Sources(MachineFolders f, Func<string, bool>? exists = null)
    {
        exists ??= Directory.Exists;
        return new SourceRoots
        {
            OfficialMinecraftDir = OfficialRoot(f, exists),
            PrismDataDir = PrismRoot(f, exists),
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
        if (!f.Mac && !f.Linux)
        {
            var store = Path.Combine(f.LocalAppData, "Packages", StorePackage, "LocalCache", "Local", "runtime");
            if (exists(store)) runtimes.Add(store);
        }
        return new GameFileRoots
        {
            InstancesRoot = instancesRoot,
            OfficialMinecraftDir = OfficialRoot(f, exists),
            OfficialRuntimeRoots = runtimes,
            PrismDataDir = PrismRoot(f, exists),
            ModrinthMetaDir = modrinth == null ? null : IfExists(Path.Combine(modrinth, "meta"), exists),
            CurseForgeRoot = CurseForgeMinecraft(f, exists),
        };
    }

    // %APPDATA%\.minecraft on Windows; plain "minecraft" (no dot) in Application Support on a Mac;
    // ~/.minecraft on Linux, else the Flathub launcher's sandboxed copy.
    private static string? OfficialRoot(MachineFolders f, Func<string, bool> exists) => f.Linux
        ? IfExists(Path.Combine(f.UserProfile, ".minecraft"), exists)
            ?? IfExists(Path.Combine(f.UserProfile, ".var", "app", "com.mojang.Minecraft", ".minecraft"), exists)
        : IfExists(Path.Combine(f.AppData, f.Mac ? "minecraft" : ".minecraft"), exists);

    // AppData\PrismLauncher on Windows and a Mac; XDG data on Linux, else its Flatpak.
    private static string? PrismRoot(MachineFolders f, Func<string, bool> exists) => f.Linux
        ? IfExists(Path.Combine(f.LocalAppData, "PrismLauncher"), exists)
            ?? IfExists(Path.Combine(f.FlatpakData("org.prismlauncher.PrismLauncher"), "PrismLauncher"), exists)
        : IfExists(Path.Combine(f.AppData, "PrismLauncher"), exists);

    // ModrinthApp, or the older com.modrinth.theseus beside it (every OS). On Linux both sit in XDG
    // data, and the Flatpak's sandboxed copy is read last.
    private static string? ModrinthRoot(MachineFolders f, Func<string, bool> exists)
    {
        var data = f.Linux ? f.LocalAppData : f.AppData;
        return IfExists(Path.Combine(data, "ModrinthApp"), exists)
            ?? IfExists(Path.Combine(data, "com.modrinth.theseus"), exists)
            ?? (f.Linux ? IfExists(Path.Combine(f.FlatpakData("com.modrinth.ModrinthApp"), "ModrinthApp"), exists) : null);
    }

    // Windows and Linux: ~/curseforge/minecraft first, then Documents. Mac: Documents is the app's default, so first.
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
