namespace OriginLauncher.App.Core.Performance;

// Reads this computer's CPU/RAM/GPU for the "Recommended for this PC" preset. The GPU read is the
// per-OS part (one file per OS, picked by each csproj): HardwareProbe.Windows.cs reads the
// display-adapter registry class, HardwareProbe.Mac.cs asks sysctl. Call from a background thread.
// Never throws; cached for the run (hardware doesn't change while the launcher is open).
public static partial class HardwareProbe
{
    private static readonly Lazy<HardwareProfile> Cached = new(Read, LazyThreadSafetyMode.ExecutionAndPublication);

    public static HardwareProfile Current => Cached.Value;

    public static PerformanceTier Recommended => TierPicker.Pick(Current);

    private static HardwareProfile Read()
    {
        var (name, vram, integrated) = ReadGpu();
        return new HardwareProfile(Environment.ProcessorCount, SystemInfo.GetTotalPhysicalMemoryMb(), name, vram, integrated);
    }

    /// <summary>One plain line for the Settings card, e.g. "Intel UHD Graphics, 8 GB RAM, 4 threads".</summary>
    public static string Describe(HardwareProfile hw)
    {
        var gpu = string.IsNullOrWhiteSpace(hw.GpuName) ? "unknown graphics" : hw.GpuName.Trim();
        var ramGb = Math.Max(1, (int)Math.Round(hw.TotalRamMb / 1024d));
        return $"{gpu}, {ramGb} GB RAM, {hw.LogicalCores} threads";
    }
}
