using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// The memory side of the crash window, pure so a test holds the whole chain HomePage runs:
/// the launch's heap -> what the crash check is told (<see cref="ForLaunch"/>) -> the "Raise memory" fix it offers
/// (CrashRules + MemoryBump) -> the settings that fix writes (<see cref="ApplyRaise"/>).
/// Three bugs lived here: the check was told the stored RamMb (on Auto only the slider's suggestion: a 32 GB PC stores
/// 16 GB while 1.8.9 really gets 4 GB), the fix wrote only RamMb, which Auto launches ignore, and then the fix wrote
/// Manual, which moved EVERY version (1.8.9 too) to the raised number and turned Auto off (bug run 6, KC-10). The raise
/// is now stored for the crashed version only (RamPolicy.RaiseFor), which HeapPlan.For applies to that version alone.
/// </summary>
public static class CrashMemory
{
    /// <summary>What the crash check judges for one launch: the heap it really used.</summary>
    public readonly record struct Facts(int UsedMb);

    /// <summary>From the launch's own heap plan (HeapPlan.For with the version), never the stored slider value.</summary>
    public static Facts ForLaunch(HeapPlan used) => new(used.MaxMb);

    /// <summary>The crash window's "Raise memory to X": exactly X, for the version that crashed and no other.</summary>
    public static void ApplyRaise(LauncherSettings settings, string version, int ramMb) =>
        RamPolicy.RaiseFor(settings, version, ramMb);
}
