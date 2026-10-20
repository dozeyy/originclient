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

    // Auto sizing per version family (see AutoMb). 1.8.9-era Minecraft needs ~2-4 GB and, on
    // Java 8's G1, a 12 GB heap only made a 3.6 GB+ young generation (Will's 2026-10-20 log).
    // Modern versions keep the suggestion but stop at 8 GB: more buys nothing for the game.
    public const int LegacyAutoMb = 4096;
    public const int LegacyHeavyAutoMb = 6144;
    public const int ModernAutoMb = 8192;

    /// <summary>
    /// What Auto gives this launch: the PC's suggestion (half its RAM, Windows keeps the rest),
    /// capped per version family - legacy 4 GB (6 GB for a pack-heavy instance), modern 8 GB.
    /// </summary>
    public static int AutoMb(int totalPhysicalMb, bool legacy, bool heavyPack)
    {
        var cap = legacy ? (heavyPack ? LegacyHeavyAutoMb : LegacyAutoMb) : ModernAutoMb;
        return Math.Min(RecommendedMb(totalPhysicalMb), cap);
    }

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
