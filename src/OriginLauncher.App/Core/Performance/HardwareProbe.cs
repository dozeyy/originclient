using System.IO;
using Microsoft.Win32;

namespace OriginLauncher.App.Core.Performance;

// Reads this PC's CPU/RAM/GPU for the "Recommended for this PC" preset. The GPU comes from the
// display-adapter class in the registry (a few key reads, ~ms, no WMI/System.Management cost):
// DriverDesc is the name, HardwareInformation.qwMemorySize the dedicated VRAM as a 64-bit value
// (the older 32-bit MemorySize caps at 4 GB). Call from a background thread. Never throws;
// cached for the run (hardware doesn't change while the launcher is open).
public static class HardwareProbe
{
    private const string DisplayClass = @"SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11cd-bfc1-08002be10318}";
    private static readonly Lazy<HardwareProfile> Cached = new(Read, LazyThreadSafetyMode.ExecutionAndPublication);

    public static HardwareProfile Current => Cached.Value;

    public static PerformanceTier Recommended => TierPicker.Pick(Current);

    private static HardwareProfile Read()
    {
        string? bestName = null;
        long? bestVram = null;
        bool? bestIntegrated = null;
        try
        {
            using var root = Registry.LocalMachine.OpenSubKey(DisplayClass);
            if (root != null)
            {
                foreach (var sub in root.GetSubKeyNames())
                {
                    using var adapter = root.OpenSubKey(sub);
                    var name = adapter?.GetValue("DriverDesc") as string;
                    if (string.IsNullOrWhiteSpace(name)) continue;
                    var integrated = TierPicker.LooksIntegrated(name);
                    long? vram = adapter!.GetValue("HardwareInformation.qwMemorySize") switch
                    {
                        long l => l / (1024 * 1024),
                        byte[] b when b.Length >= 8 => BitConverter.ToInt64(b, 0) / (1024 * 1024),
                        _ => null,
                    };
                    // The GPU the game will actually use is the discrete one (GpuPreference points it there).
                    var better = bestName == null
                        || (bestIntegrated == true && integrated == false)
                        || (bestIntegrated == integrated && (vram ?? 0) > (bestVram ?? 0));
                    if (!better) continue;
                    bestName = name;
                    bestVram = vram;
                    bestIntegrated = integrated;
                }
            }
        }
        catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException or IOException)
        {
            // an unreadable registry just means "GPU unknown" -> never High
        }

        return new HardwareProfile(Environment.ProcessorCount, SystemInfo.GetTotalPhysicalMemoryMb(), bestName, bestVram, bestIntegrated);
    }

    /// <summary>One plain line for the Settings card, e.g. "Intel UHD Graphics, 8 GB RAM, 4 threads".</summary>
    public static string Describe(HardwareProfile hw)
    {
        var gpu = string.IsNullOrWhiteSpace(hw.GpuName) ? "unknown graphics" : hw.GpuName.Trim();
        var ramGb = Math.Max(1, (int)Math.Round(hw.TotalRamMb / 1024d));
        return $"{gpu}, {ramGb} GB RAM, {hw.LogicalCores} threads";
    }
}
