namespace OriginLauncher.App.Core;

// Mac: nothing to do. Apple Silicon has one GPU, and on dual-GPU Intel Macs macOS itself
// switches to the discrete GPU when a game opens an OpenGL context that asks for it (Minecraft's
// LWJGL does); there is no per-app preference store like Windows' UserGpuPreferences.
public static class GpuPreference
{
    public static void PreferHighPerformanceGpu(string executablePath)
    {
    }
}
