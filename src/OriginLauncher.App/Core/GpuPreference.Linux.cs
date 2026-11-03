using System.Diagnostics;
using System.IO;
using OriginLauncher.App.Core.Performance;

namespace OriginLauncher.App.Core;

// Linux: there is no per-app GPU preference store like Windows' UserGpuPreferences, so
// PreferHighPerformanceGpu has nothing to write. A hybrid laptop (an integrated GPU drives the display,
// a discrete one sits beside it) runs a game on the display GPU unless that one process is started
// with an offload variable, which belongs to the launch itself: ApplyOffload puts it on the game's
// environment, the Linux twin of Windows' "High performance" pick.
//   NVIDIA's own driver loaded (/sys/module/nvidia): the three variables NVIDIA's prime-run sets.
//   Anything else (AMD, Intel Arc, NVIDIA on nouveau): Mesa's DRI_PRIME=1.
// A desktop whose display GPU is already the discrete one, a one-GPU machine, or a player who set an
// offload variable himself (e.g. started Origin with prime-run) is left exactly as it is.
public static class GpuPreference
{
    public static void PreferHighPerformanceGpu(string executablePath)
    {
    }

    /// <summary>Starts the game on the discrete GPU of a hybrid laptop. Never throws: any failure leaves the launch unchanged.</summary>
    public static void ApplyOffload(ProcessStartInfo start)
    {
        try
        {
            if (OffloadVariables.Any(name => !string.IsNullOrEmpty(Environment.GetEnvironmentVariable(name)))) return;
            var cards = HardwareProbe.ReadCards("/sys/class/drm");
            foreach (var (name, value) in OffloadEnvironment(cards, Directory.Exists("/sys/module/nvidia")))
                start.Environment[name] = value;
        }
        catch (Exception)
        {
            // sysfs unreadable or an odd card: the game simply starts on the display GPU, as before.
        }
    }

    // Any of these already set means the player chose the GPU himself.
    private static readonly string[] OffloadVariables = ["DRI_PRIME", "__NV_PRIME_RENDER_OFFLOAD"];

    /// <summary>The variables that move the game to the discrete GPU, or none (pure: sysfs cards in, variables out).</summary>
    internal static IReadOnlyList<(string Name, string Value)> OffloadEnvironment(IReadOnlyList<HardwareProbe.DrmCard> cards, bool nvidiaDriverLoaded)
    {
        // Only a hybrid machine: a display card that is not discrete, and a discrete card that is not the display.
        var display = cards.FirstOrDefault(c => c.BootVga);
        if (display == null || HardwareProbe.IsDiscrete(display)) return [];
        var discrete = cards.FirstOrDefault(c => !c.BootVga && HardwareProbe.IsDiscrete(c));
        if (discrete == null) return [];
        if (discrete.Vendor == HardwareProbe.NvidiaVendor && nvidiaDriverLoaded)
            return [("__NV_PRIME_RENDER_OFFLOAD", "1"), ("__GLX_VENDOR_LIBRARY_NAME", "nvidia"), ("__VK_LAYER_NV_optimus", "NVIDIA_only")];
        return [("DRI_PRIME", "1")];
    }
}
