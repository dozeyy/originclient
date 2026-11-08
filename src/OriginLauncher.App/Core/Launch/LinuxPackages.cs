using System.IO;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// The one install command a Linux player needs for a missing system piece, in their own distro's words, from
/// /etc/os-release (ID and ID_LIKE). Used where Origin refuses to go on without the piece instead of letting it fail
/// later and vaguely: 1.8.9's xrandr (PlatformLaunch.Linux EnsureCanRun) and OpenSSL (LinuxOpenSsl). Pure, so every
/// test run checks the words; an unknown distro gets every family's command, never none.
/// </summary>
public static class LinuxPackages
{
    public enum Family { Unknown, Debian, Fedora, Arch, Suse }

    /// <summary>A missing piece and its package name per family.</summary>
    public sealed record Piece(string Name, string Apt, string Dnf, string Pacman, string Zypper);

    /// <summary>1.8.9's LWJGL 2 runs the `xrandr` program to list screen modes; without it the game dies before its window.</summary>
    public static readonly Piece Xrandr = new("xrandr", "x11-xserver-utils", "xrandr", "xorg-xrandr", "xrandr");

    /// <summary>OpenSSL 3 (libssl.so.3), which .NET opens for every HTTPS call. Fedora 45's openssl-libs is OpenSSL 4.</summary>
    public static readonly Piece OpenSsl3 = new("OpenSSL 3", "libssl3", "openssl3-libs", "openssl", "libopenssl3");

    /// <summary>The distro family from the text of /etc/os-release (ID first, then ID_LIKE); Unknown when neither says.</summary>
    public static Family FamilyOf(string? osRelease)
    {
        if (string.IsNullOrEmpty(osRelease)) return Family.Unknown;
        string Field(string key) =>
            osRelease.Split('\n').Select(l => l.Trim()).FirstOrDefault(l => l.StartsWith(key + "=", StringComparison.Ordinal))
                ?[(key.Length + 1)..].Trim('"', '\'').ToLowerInvariant() ?? "";
        foreach (var id in (Field("ID") + " " + Field("ID_LIKE")).Split(' ', StringSplitOptions.RemoveEmptyEntries))
        {
            switch (id)
            {
                case "debian" or "ubuntu" or "linuxmint" or "pop" or "elementary" or "zorin" or "kali" or "raspbian":
                    return Family.Debian;
                case "fedora" or "rhel" or "centos" or "rocky" or "almalinux" or "nobara" or "ultramarine":
                    return Family.Fedora;
                case "arch" or "manjaro" or "endeavouros" or "cachyos" or "garuda" or "steamos":
                    return Family.Arch;
                case "suse" or "opensuse" or "opensuse-tumbleweed" or "opensuse-leap" or "sles":
                    return Family.Suse;
            }
        }
        return Family.Unknown;
    }

    /// <summary>"sudo apt install x11-xserver-utils" for a Debian-family distro; every family's command when unknown.</summary>
    public static string InstallCommand(Piece piece, Family family) => family switch
    {
        Family.Debian => $"sudo apt install {piece.Apt}",
        Family.Fedora => $"sudo dnf install {piece.Dnf}",
        Family.Arch => $"sudo pacman -S {piece.Pacman}",
        Family.Suse => $"sudo zypper install {piece.Zypper}",
        _ => $"sudo apt install {piece.Apt} (Ubuntu, Debian, Mint), sudo dnf install {piece.Dnf} (Fedora), "
             + $"sudo pacman -S {piece.Pacman} (Arch), or sudo zypper install {piece.Zypper} (openSUSE)",
    };

    /// <summary>This computer's family (reads /etc/os-release once; Unknown off Linux or when it can't be read).</summary>
    public static Family ThisComputer => _thisComputer ??= ReadFamily();

    private static Family? _thisComputer;

    private static Family ReadFamily()
    {
        if (!OperatingSystem.IsLinux()) return Family.Unknown;
        foreach (var path in new[] { "/etc/os-release", "/usr/lib/os-release" })
        {
            try
            {
                if (File.Exists(path)) return FamilyOf(File.ReadAllText(path));
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        }
        return Family.Unknown;
    }

    /// <summary>True when an executable named <paramref name="program"/> is on PATH (what a Java child's exec would find).</summary>
    public static bool OnPath(string program, string? path = null)
    {
        foreach (var dir in (path ?? Environment.GetEnvironmentVariable("PATH") ?? "").Split(':', StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var candidate = Path.Combine(dir, program);
                if (!File.Exists(candidate)) continue;
                if (OperatingSystem.IsWindows()) return true;
                const UnixFileMode anyExecute = UnixFileMode.UserExecute | UnixFileMode.GroupExecute | UnixFileMode.OtherExecute;
                if ((File.GetUnixFileMode(candidate) & anyExecute) != 0) return true;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException) { }
        }
        return false;
    }
}

/// <summary>
/// A version can't start on this Linux PC until the player installs one system piece (1.8.9 without `xrandr`). Thrown
/// by PlatformLaunch.EnsureCanRun before anything downloads; PlayFailure shows the title and the one install command,
/// and Play works as soon as it is installed. Never a crash the player has to decode afterwards.
/// </summary>
public sealed class SystemPieceMissingException : Exception
{
    public SystemPieceMissingException(string version, LinuxPackages.Piece piece, string installCommand)
        : base($"Minecraft {version} needs {piece.Name}, which this computer doesn't have. Install it with: {installCommand}")
    {
        Version = version;
        Piece = piece;
        InstallCommand = installCommand;
    }

    public string Version { get; }
    public LinuxPackages.Piece Piece { get; }
    public string InstallCommand { get; }
}
