using System.IO;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// The system pieces the Linux launcher uses when they are there and works around when they are not (LINUX-14). The
/// AppImage and tar.gz bundle none of them and the .deb/.rpm only recommend them, so a player can lack any of them
/// without knowing. Each missing one costs something specific; the self-test lists them and Settings shows one line
/// with the install command, so a silent downgrade becomes one visible note. xrandr is also a hard stop for 1.8.9
/// (PlatformLaunch.Linux refuses Play without it); OpenSSL is handled by LinuxOpenSsl, never listed here.
/// Pure apart from the two probes passed in, so tests feed their own.
/// </summary>
public static class LinuxOptionalPieces
{
    /// <summary>One piece: how to look for it, what the player loses without it, and its package per distro family.</summary>
    public sealed record Piece(string Name, string? Program, string? Library, string Without, LinuxPackages.Piece Package);

    public static readonly IReadOnlyList<Piece> All =
    [
        new("secret-tool", "secret-tool", null, "accounts are sealed with a key file instead of your desktop keyring",
            new("secret-tool", "libsecret-tools", "libsecret", "libsecret", "libsecret-tools")),
        new("xdg-open", "xdg-open", null, "links and folders may not open",
            new("xdg-open", "xdg-utils", "xdg-utils", "xdg-utils", "xdg-utils")),
        new("WebKitGTK", null, "libwebkit2gtk-4.1.so.0", "Microsoft sign-in happens in your browser instead of inside Origin",
            new("WebKitGTK", "libwebkit2gtk-4.1-0", "webkit2gtk4.1", "webkit2gtk-4.1", "libwebkit2gtk-4_1-0")),
        new("xrandr", "xrandr", null, "Minecraft 1.8.9 can't start",
            LinuxPackages.Xrandr),
        new("libXxf86vm", null, "libXxf86vm.so.1", "older Minecraft versions can fail to open their window",
            new("libXxf86vm", "libxxf86vm1", "libXxf86vm", "libxxf86vm", "libXxf86vm1")),
    ];

    // Where distros put shared libraries (multiarch Debian/Ubuntu, lib64 Fedora/openSUSE, plain lib Arch).
    private static readonly string[] LibraryDirs =
    [
        "/usr/lib/x86_64-linux-gnu", "/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/lib/aarch64-linux-gnu",
        "/usr/lib64", "/lib64", "/usr/lib", "/lib",
    ];

    /// <summary>The pieces this computer lacks (file checks only: nothing is loaded or run). Empty off Linux.</summary>
    public static IReadOnlyList<Piece> MissingHere() =>
        OperatingSystem.IsLinux()
            ? Missing(p => LinuxPackages.OnPath(p), lib => LibraryDirs.Any(d => File.Exists(Path.Combine(d, lib))))
            : [];

    public static IReadOnlyList<Piece> Missing(Func<string, bool> programExists, Func<string, bool> libraryExists) =>
        All.Where(p => p.Program != null ? !programExists(p.Program) : !libraryExists(p.Library!)).ToList();

    /// <summary>
    /// The one Settings line for what is missing, or null when nothing is: what each costs, then one install command
    /// for all of them in this distro's words.
    /// </summary>
    public static string? Note(IReadOnlyList<Piece> missing, LinuxPackages.Family family)
    {
        if (missing.Count == 0) return null;
        var costs = string.Join("; ", missing.Select(p => $"without {p.Name}, {p.Without}"));
        var command = family switch
        {
            LinuxPackages.Family.Debian => "sudo apt install " + string.Join(' ', missing.Select(p => p.Package.Apt)),
            LinuxPackages.Family.Fedora => "sudo dnf install " + string.Join(' ', missing.Select(p => p.Package.Dnf)),
            LinuxPackages.Family.Arch => "sudo pacman -S " + string.Join(' ', missing.Select(p => p.Package.Pacman)),
            LinuxPackages.Family.Suse => "sudo zypper install " + string.Join(' ', missing.Select(p => p.Package.Zypper)),
            _ => string.Join(", ", missing.Select(p => LinuxPackages.InstallCommand(p.Package, family))),
        };
        return $"Missing on this computer: {costs}. To add {(missing.Count == 1 ? "it" : "them")}: {command}";
    }
}
