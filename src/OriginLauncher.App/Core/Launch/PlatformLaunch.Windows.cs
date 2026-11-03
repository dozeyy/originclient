using CmlLib.Core;
using CmlLib.Core.ProcessBuilder;

namespace OriginLauncher.App.Core.Launch;

// Windows: CmlLib's defaults (LauncherOSRule.Current, Mojang's JSON arguments) are the launch, unchanged.
public static partial class PlatformLaunch
{
    public static partial void EnsureCanRun(string version) { }

    // The words the crash report uses for this computer (CrashRules): the system, the machine, where drivers come from.
    public static string SystemName => "Windows";
    public static string MachineName => "PC";
    public static string DriverUpdateAdvice => "Update your graphics driver (NVIDIA, AMD or Intel, or Windows Update)";

    public static partial void Configure(MinecraftLauncher launcher, string version) { }

    public static partial void AdjustOption(string version, MLaunchOption option) { }
}
