using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// The memory side of the crash window, pure so a test holds the whole chain HomePage runs:
/// the launch's heap -> what the crash check is told (<see cref="ForLaunch"/>) -> the "Raise memory" fix it offers
/// (CrashRules + MemoryBump) -> the settings that fix writes (<see cref="ApplyRaise"/>).
/// Two bugs lived here: the check was told the stored RamMb (on Auto only the slider's suggestion: a 32 GB PC stores
/// 16 GB while 1.8.9 really gets 4 GB), and the fix wrote only RamMb, which Auto launches ignore.
/// </summary>
public static class CrashMemory
{
    /// <summary>What the crash check judges for one launch: the heap it really used, and the least a raise may offer.</summary>
    public readonly record struct Facts(int UsedMb, int RaiseFloorMb);

    /// <summary>
    /// From the launch's own heap plan. The raise is applied as Manual, which every version then uses as-is, so on
    /// Auto it may never come out below what the newest versions already get from Auto (the most any version gets):
    /// a 1.8.9 crash on 4 GB must not quietly drop 1.21.1 from 8 GB to 6 GB. On Manual every version already runs the
    /// same number, so any raise is a raise for all of them.
    /// </summary>
    public static Facts ForLaunch(HeapPlan used, int totalPhysicalMb) =>
        new(used.MaxMb, used.Auto ? RamAdvice.AutoMb(totalPhysicalMb, legacy: false, heavyPack: false) : 0);

    /// <summary>The crash window's "Raise memory to X": exactly X, as Manual (like the Settings slider).</summary>
    public static void ApplyRaise(LauncherSettings settings, int ramMb) => RamPolicy.SetManual(settings, ramMb);
}
