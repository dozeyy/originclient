using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// The heap one launch actually gets. AUTO (the default) sizes it per version and PC
/// (RamAdvice.AutoMb: legacy 4 GB / 6 GB for heavy packs, modern up to 8 GB). MANUAL is the
/// player's number, used as-is on every version; for a legacy version a very large one only
/// earns a heads-up. Pure: callers supply the facts (PackProbe for the pack signals).
/// </summary>
public sealed record HeapPlan(int MaxMb, int StartMb, bool Auto, string? Warning)
{
    /// <summary>A player-chosen legacy heap above this only gets a heads-up, never a change.</summary>
    public const int LegacyWarnAboveMb = 6144;

    // Heavy-instance thresholds (legacy versions only).
    public const int HeavyModCount = 25;
    public const long HeavyResourcePackBytes = 256L * 1024 * 1024;

    public static HeapPlan For(LauncherSettings settings, int totalPhysicalMb, bool legacy, bool heavyPack) =>
        For(settings.RamMb, RamPolicy.IsManual(settings), totalPhysicalMb, legacy, heavyPack);

    public static HeapPlan For(int manualMb, bool manual, int totalPhysicalMb, bool legacy, bool heavyPack)
    {
        // Start == max on every version: committing the heap up front avoids growth stalls,
        // and without AlwaysPreTouch it costs next to nothing.
        if (manual)
        {
            var warn = legacy && manualMb > LegacyWarnAboveMb
                ? $"{manualMb / 1024d:0.#} GB is a lot for this version - 4 GB is plenty for most setups and runs smoother."
                : null;
            return new HeapPlan(manualMb, manualMb, false, warn);
        }

        var auto = RamAdvice.AutoMb(totalPhysicalMb, legacy, heavyPack);
        return new HeapPlan(auto, auto, true, null);
    }

    /// <summary>True when the instance's packs call for the larger legacy cap.</summary>
    public static bool IsHeavy(int modJarCount, int shaderPackCount, long resourcePackBytes) =>
        modJarCount >= HeavyModCount || shaderPackCount > 0 || resourcePackBytes >= HeavyResourcePackBytes;
}
