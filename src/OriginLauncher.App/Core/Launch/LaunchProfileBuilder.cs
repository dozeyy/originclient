using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Launch;

public static class LaunchProfileBuilder
{
    public static MLaunchOption Build(LauncherSettings settings, MSession session)
    {
        return new MLaunchOption
        {
            Session = session,
            MaximumRamMb = settings.RamMb,
            MinimumRamMb = Math.Min(1024, settings.RamMb),
            ScreenWidth = settings.ResolutionWidth,
            ScreenHeight = settings.ResolutionHeight,
            ExtraJvmArguments = JvmArgPresets.For(settings.PerformanceMode)
                .Select(flag => new MArgument(flag))
                .ToList()
        };
    }
}
