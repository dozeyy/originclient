namespace OriginLauncher.App.Core;

// Linux: nothing to do here. There is no per-app GPU preference store like Windows'
// UserGpuPreferences: a hybrid laptop runs a game on the GPU that drives the display unless that
// one process is started with an offload variable (DRI_PRIME=1, or __NV_PRIME_RENDER_OFFLOAD=1 for
// NVIDIA's driver), which belongs to the launch itself, not to a setting written once.
// HardwareProbe.Linux.cs reports that display GPU for the same reason.
public static class GpuPreference
{
    public static void PreferHighPerformanceGpu(string executablePath)
    {
    }
}
