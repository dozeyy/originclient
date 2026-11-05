using System.Globalization;
using System.IO;

namespace OriginLauncher.App.Core.Versions;

/// <summary>Not enough room for a download: which drive, how much it needs and how much is free.</summary>
public sealed record DiskShortage(string Drive, long NeededBytes, long FreeBytes)
{
    /// <summary>"Origin needs about 1.5 GB free on C: (0.4 GB free)." The same words before and after a download.</summary>
    public string Sentence =>
        $"Origin needs about {DiskSpace.Gb(NeededBytes)} free on {Drive} ({DiskSpace.Gb(FreeBytes)} free).";
}

/// <summary>Play stopped for lack of disk space, before or during a download. PlayFailure turns it into its line.</summary>
public sealed class NotEnoughDiskSpaceException(DiskShortage shortage, Exception? inner = null)
    : Exception(shortage.Sentence, inner)
{
    public DiskShortage Shortage { get; } = shortage;
}

/// <summary>
/// The free-space check in front of a version's first download (bug run 5, JL-11). A first Play used to download for
/// minutes and fail half way with "Your disk is full. Free up some space." without saying how much or where; now a
/// version that is not installed yet is refused up front when its drive has less than
/// <see cref="FirstInstallBytes"/> free, and a full disk mid-download names the same drive and numbers.
/// Never blocks Play on a measuring problem: an unreadable drive counts as having room.
/// </summary>
public static class DiskSpace
{
    /// <summary>
    /// What one version's first install can take: the client jar, libraries and natives (~100-200 MB), the asset
    /// objects shared by versions (~400-700 MB on a first version), Mojang's Java runtime for it (~150-250 MB) and
    /// Origin's mods; rounded up so a near-full disk is caught before, not during, the download.
    /// </summary>
    public const long FirstInstallBytes = 1536L * 1024 * 1024;

    /// <summary>Null when <paramref name="freeBytes"/> covers <paramref name="neededBytes"/>.</summary>
    public static DiskShortage? Shortage(string drive, long freeBytes, long neededBytes) =>
        freeBytes >= neededBytes ? null : new DiskShortage(drive, neededBytes, Math.Max(0, freeBytes));

    /// <summary>Throws <see cref="NotEnoughDiskSpaceException"/> when the drive holding <paramref name="path"/> has
    /// less than <see cref="FirstInstallBytes"/> free. Call before anything is downloaded.</summary>
    public static void EnsureRoomForInstall(string path)
    {
        if (Measure(path) is { } m && Shortage(m.Drive, m.FreeBytes, FirstInstallBytes) is { } shortage)
            throw new NotEnoughDiskSpaceException(shortage);
    }

    /// <summary>A full disk hit mid-download, with the same drive and numbers as the up-front check (the drive is
    /// measured again now; "needed" stays the first-install estimate).</summary>
    public static NotEnoughDiskSpaceException DiskFull(string path, Exception cause) =>
        new(Measure(path) is { } m
            ? new DiskShortage(m.Drive, FirstInstallBytes, m.FreeBytes)
            : new DiskShortage(OperatingSystem.IsWindows() ? "your game drive" : "this disk", FirstInstallBytes, 0), cause);

    /// <summary>The drive (mount) holding <paramref name="path"/> and its free bytes; null when it can't be read.</summary>
    public static (string Drive, long FreeBytes)? Measure(string path)
    {
        try
        {
            var full = Path.GetFullPath(path);
            var comparison = OperatingSystem.IsLinux() ? StringComparison.Ordinal : StringComparison.OrdinalIgnoreCase;
            // The longest mount point that holds the path (Linux and macOS list many; Windows one per letter).
            DriveInfo? best = null;
            foreach (var d in DriveInfo.GetDrives())
            {
                var root = d.RootDirectory.FullName;
                if (!full.StartsWith(root, comparison)) continue;
                if (best == null || root.Length > best.RootDirectory.FullName.Length) best = d;
            }
            if (best is not { IsReady: true }) return null;
            return (DriveLabel(best.RootDirectory.FullName, OperatingSystem.IsWindows()), best.AvailableFreeSpace);
        }
        catch (Exception)
        {
            return null; // never refuse Play because a drive couldn't be measured
        }
    }

    /// <summary>"C:" on Windows; "your disk" for the system volume on macOS/Linux, else the mount point ("/home").</summary>
    public static string DriveLabel(string root, bool windows)
    {
        if (windows) return root.TrimEnd('\\', '/');
        var trimmed = root.Length > 1 ? root.TrimEnd('/') : root;
        return trimmed is "/" or "/System/Volumes/Data" ? "your disk" : trimmed;
    }

    /// <summary>"1.5 GB", "0.4 GB": one decimal, always a dot (the launcher's words are English).</summary>
    public static string Gb(long bytes) =>
        (Math.Max(0, bytes) / (1024.0 * 1024 * 1024)).ToString("0.0", CultureInfo.InvariantCulture) + " GB";
}
