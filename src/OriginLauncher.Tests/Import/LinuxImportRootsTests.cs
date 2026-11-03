using OriginLauncher.App.Core.Import.Launcher;
using Xunit;

namespace OriginLauncher.Tests.Import;

/// <summary>
/// Where the import looks on Linux (ImportRoots, MachineFolders.ForLinux): ~/.minecraft, Prism and
/// Modrinth under XDG data, their Flatpak copies, Feather under XDG config, no Store runtimes. Pure
/// (folder existence is a set), so it runs in every suite; linux-launcher.yml runs it in Linux mode
/// (-p:OriginPlatform=Linux), where it also proves the real MachineFolders.Current() is the Linux one.
/// </summary>
public sealed class LinuxImportRootsTests
{
    private const string Home = "/home/p";
    private static readonly string Data = Path.Combine(Home, ".local", "share");
    private static readonly string Config = Path.Combine(Home, ".config");

    [Fact]
    public void Linux_folders_default_to_xdg()
    {
        var f = MachineFolders.ForLinux(Home);
        Assert.True(f.Linux);
        Assert.False(f.Mac);
        Assert.Equal(Config, f.AppData);
        Assert.Equal(Data, f.LocalAppData);
        Assert.Equal(Home, f.UserProfile);
        Assert.Equal(Path.Combine(Home, "Documents"), f.Documents);
    }

    [Fact]
    public void Linux_folders_follow_a_custom_xdg_home()
    {
        var f = MachineFolders.ForLinux(Home, xdgData: "/data/p", xdgConfig: "/cfg/p");
        var present = new HashSet<string>(StringComparer.Ordinal)
        {
            Path.Combine("/data/p", "PrismLauncher"),
            Path.Combine("/data/p", "ModrinthApp"),
            Path.Combine("/cfg/p", ".feather"),
        };
        var roots = ImportRoots.Sources(f, present.Contains);
        Assert.Equal(Path.Combine("/data/p", "PrismLauncher"), roots.PrismDataDir);
        Assert.Equal(Path.Combine("/data/p", "ModrinthApp"), roots.ModrinthDir);
        Assert.Equal(Path.Combine("/cfg/p", ".feather"), roots.FeatherDir);
    }

    [Fact]
    public void Every_launcher_has_its_linux_folder()
    {
        var f = MachineFolders.ForLinux(Home);
        var present = new HashSet<string>(StringComparer.Ordinal)
        {
            Path.Combine(Home, ".minecraft"),
            Path.Combine(Data, "PrismLauncher"),
            Path.Combine(Data, "ModrinthApp"),
            Path.Combine(Data, "ModrinthApp", "meta"),
            Path.Combine(Home, "curseforge", "minecraft"),
            Path.Combine(Home, ".lunarclient"),
            Path.Combine(Config, ".feather"),
        };
        var roots = ImportRoots.Sources(f, present.Contains);
        Assert.Equal(Path.Combine(Home, ".minecraft"), roots.OfficialMinecraftDir);
        Assert.Equal(Path.Combine(Data, "PrismLauncher"), roots.PrismDataDir);
        Assert.Equal(Path.Combine(Data, "ModrinthApp"), roots.ModrinthDir);
        Assert.Equal(Path.Combine(Home, "curseforge", "minecraft", "Instances"), roots.CurseForgeDir);
        Assert.Equal(Path.Combine(Home, ".lunarclient"), roots.LunarDir);
        Assert.Equal(Path.Combine(Config, ".feather"), roots.FeatherDir);

        var files = ImportRoots.GameFiles(f, "inst", present.Contains);
        // The official launcher's Java runtimes are ~/.minecraft/runtime: GameFileSources reads them
        // from this folder as the legacy runtime folder, so finding .minecraft finds the runtimes.
        Assert.Equal(Path.Combine(Home, ".minecraft"), files.OfficialMinecraftDir);
        Assert.Equal(Path.Combine(Data, "PrismLauncher"), files.PrismDataDir);
        Assert.Equal(Path.Combine(Data, "ModrinthApp", "meta"), files.ModrinthMetaDir);
        Assert.Equal(Path.Combine(Home, "curseforge", "minecraft"), files.CurseForgeRoot);
        Assert.Empty(files.OfficialRuntimeRoots);
    }

    [Fact]
    public void Flatpak_installs_are_found_when_there_is_no_native_one()
    {
        var f = MachineFolders.ForLinux(Home);
        var app = Path.Combine(Home, ".var", "app");
        var present = new HashSet<string>(StringComparer.Ordinal)
        {
            Path.Combine(app, "com.mojang.Minecraft", ".minecraft"),
            Path.Combine(app, "org.prismlauncher.PrismLauncher", "data", "PrismLauncher"),
            Path.Combine(app, "com.modrinth.ModrinthApp", "data", "ModrinthApp"),
        };
        var roots = ImportRoots.Sources(f, present.Contains);
        Assert.Equal(Path.Combine(app, "com.mojang.Minecraft", ".minecraft"), roots.OfficialMinecraftDir);
        Assert.Equal(Path.Combine(app, "org.prismlauncher.PrismLauncher", "data", "PrismLauncher"), roots.PrismDataDir);
        Assert.Equal(Path.Combine(app, "com.modrinth.ModrinthApp", "data", "ModrinthApp"), roots.ModrinthDir);
    }

    [Fact]
    public void The_native_install_wins_over_the_flatpak()
    {
        var f = MachineFolders.ForLinux(Home);
        var app = Path.Combine(Home, ".var", "app");
        var present = new HashSet<string>(StringComparer.Ordinal)
        {
            Path.Combine(Home, ".minecraft"),
            Path.Combine(app, "com.mojang.Minecraft", ".minecraft"),
            Path.Combine(Data, "PrismLauncher"),
            Path.Combine(app, "org.prismlauncher.PrismLauncher", "data", "PrismLauncher"),
            Path.Combine(Data, "com.modrinth.theseus"),
            Path.Combine(app, "com.modrinth.ModrinthApp", "data", "ModrinthApp"),
        };
        var roots = ImportRoots.Sources(f, present.Contains);
        Assert.Equal(Path.Combine(Home, ".minecraft"), roots.OfficialMinecraftDir);
        Assert.Equal(Path.Combine(Data, "PrismLauncher"), roots.PrismDataDir);
        Assert.Equal(Path.Combine(Data, "com.modrinth.theseus"), roots.ModrinthDir);
    }

    [Fact]
    public void Linux_never_reads_the_windows_layout()
    {
        // What the old code looked for on Linux (it treated Linux as Windows, AppData = ~/.config).
        var f = MachineFolders.ForLinux(Home);
        var windowsLayout = new HashSet<string>(StringComparer.Ordinal)
        {
            Path.Combine(Config, ".minecraft"),
            Path.Combine(Config, "PrismLauncher"),
            Path.Combine(Config, "ModrinthApp"),
            Path.Combine(Data, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime"),
        };
        var roots = ImportRoots.Sources(f, windowsLayout.Contains);
        Assert.Null(roots.OfficialMinecraftDir);
        Assert.Null(roots.PrismDataDir);
        Assert.Null(roots.ModrinthDir);
        Assert.Empty(ImportRoots.GameFiles(f, "inst", windowsLayout.Contains).OfficialRuntimeRoots);
    }

#if ORIGIN_LINUX
    [Fact]
    public void This_linux_machine_reads_its_own_home()
    {
        var current = MachineFolders.Current();
        Assert.True(current.Linux);
        Assert.Equal(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), current.UserProfile);
        Assert.Equal(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), current.LocalAppData);
    }
#endif
}
