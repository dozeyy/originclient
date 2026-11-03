using CmlLib.Core;
using CmlLib.Core.ProcessBuilder;

namespace OriginLauncher.App.Core.Launch;

// Windows: CmlLib's defaults (LauncherOSRule.Current, Mojang's JSON arguments) are the launch, unchanged.
public static partial class PlatformLaunch
{
    public static partial void EnsureCanRun(string version) { }

    public static partial void Configure(MinecraftLauncher launcher, string version) { }

    public static partial void AdjustOption(string version, MLaunchOption option) { }
}
