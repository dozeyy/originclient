namespace OriginLauncher.App.Core.Performance;

// Mac: sysctl (SystemInfo.Mac.cs), microseconds, no process. On Apple Silicon the GPU is part of
// the chip, so the chip's name (machdep.cpu.brand_string, e.g. "Apple M2 Pro") names it; it shares
// system memory, which is exactly TierPicker's "integrated" case (Balanced only with 8+ cores and
// 16 GB). The x64 build running under Rosetta sees "VirtualApple ..." there, so
// sysctl.proc_translated = 1 is the Apple Silicon tell in that case. An Intel Mac's GPU isn't in
// sysctl (only system_profiler knows, ~1 s), so it stays unknown, which never promises High.
public static partial class HardwareProbe
{
    private static (string? Name, long? VramMb, bool? Integrated) ReadGpu()
    {
        var chip = SystemInfo.SysctlString("machdep.cpu.brand_string");
        if (chip != null && chip.StartsWith("Apple ", StringComparison.Ordinal)) return (chip + " GPU", null, true);
        if (SystemInfo.SysctlInt64("sysctl.proc_translated") == 1) return ("Apple Silicon GPU", null, true);
        return (null, null, null);
    }
}
