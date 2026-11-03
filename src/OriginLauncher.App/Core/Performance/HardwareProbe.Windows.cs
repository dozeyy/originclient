using System.IO;
using Microsoft.Win32;

namespace OriginLauncher.App.Core.Performance;

// Windows: the GPU comes from the display-adapter class in the registry (a few key reads, ~ms, no
// WMI/System.Management cost): DriverDesc is the name, HardwareInformation.qwMemorySize the
// dedicated VRAM as a 64-bit value (the older 32-bit MemorySize caps at 4 GB).
public static partial class HardwareProbe
{
    private const string DisplayClass = @"SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11cd-bfc1-08002be10318}";

    private static (string? Name, long? VramMb, bool? Integrated) ReadGpu()
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

        return (bestName, bestVram, bestIntegrated);
    }
}
