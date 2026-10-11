namespace OriginLauncher.App.Core;

// How much memory Minecraft should get. ONE rule for both the launcher
// (default + Settings slider range) and the installer's "Memory for
// Minecraft" slider — src/OriginSetup links this file, so it must also
// compile on .NET Framework 4.7.2 (no Math.Clamp, no newer APIs).
public static class RamAdvice
{
    public const int StepMb = 256;
    public const int SliderMinimumMb = 1024;
    private const int FloorMb = 2048;
    private const int OsHeadroomMb = 2048;

    /// <summary>Most Minecraft may take: total RAM minus 2 GB for Windows, on the 256 MB grid.</summary>
    public static int MaximumMb(int totalPhysicalMb) =>
        Math.Max(FloorMb, (totalPhysicalMb - OsHeadroomMb) / StepMb * StepMb);

    /// <summary>
    /// The default: half of the PC's RAM in whole gigabytes (a "16 GB" PC — which
    /// Windows reports as ~15.8 GB — gets 8 GB, not 7.75), at least 2 GB, within
    /// the maximum.
    /// </summary>
    public static int RecommendedMb(int totalPhysicalMb)
    {
        int totalGb = (int)Math.Round(totalPhysicalMb / 1024d);
        return Math.Min(MaximumMb(totalPhysicalMb), Math.Max(FloorMb, totalGb / 2 * 1024));
    }
}
