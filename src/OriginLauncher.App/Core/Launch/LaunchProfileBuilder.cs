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
            // Always Aikar's flags now that the Graphics/Performance launch-mode
            // toggle is gone — the tuned G1GC set is the right baseline for every
            // launch and there's no player-facing reason to ship the weaker one.
            ExtraJvmArguments = JvmArgPresets.For(PerformanceMode.Performance)
                .Select(flag => new MArgument(flag))
                .ToList()
        };
    }
}
