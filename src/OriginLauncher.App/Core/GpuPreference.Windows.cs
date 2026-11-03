using Microsoft.Win32;

namespace OriginLauncher.App.Core;

public static class GpuPreference
{
    private const string RegistryPath = @"Software\Microsoft\DirectX\UserGpuPreferences";

    // Same mechanism Windows Settings' own per-app "Graphics preference" UI
    // writes to — hints hybrid-GPU laptops to run the given executable on the
    // discrete/high-performance GPU instead of the integrated one.
    public static void PreferHighPerformanceGpu(string executablePath)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RegistryPath);
        key.SetValue(executablePath, "GpuPreference=2;", RegistryValueKind.String);
    }
}
