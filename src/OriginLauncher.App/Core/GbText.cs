using System.Globalization;

namespace OriginLauncher.App.Core;

/// <summary>
/// Memory sizes in the launcher's words: "4.5 GB", always with a dot, on every Windows/Mac/Linux language. The
/// launcher's text is English, so a German or French PC must not mix "4,5 GB" (the RAM slider, the crash window, the
/// heap warning) with the "1.5 GB" of the disk-space line (DiskSpace.Gb) (LOC-6).
/// </summary>
public static class GbText
{
    /// <summary><paramref name="mb"/> megabytes as gigabytes, e.g. FromMb(4608) = "4.5 GB"; <paramref name="format"/> sets the decimals.</summary>
    public static string FromMb(double mb, string format = "0.#") =>
        (mb / 1024d).ToString(format, CultureInfo.InvariantCulture) + " GB";
}
