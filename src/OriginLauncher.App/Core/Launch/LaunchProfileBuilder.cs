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
            // -Xms == -Xmx, which is what the G1 tuning set in JvmArgPresets is
            // designed for. This used to be capped at 1 GB, so the heap started
            // tiny and had to grow in steps all the way up to the slider value
            // during boot and world load — and with
            // -XX:InitiatingHeapOccupancyPercent=15 measured against that small
            // committed heap, G1 kicked off concurrent marking almost
            // immediately. Committing the final size up front removes both the
            // growth stalls and the spurious early GC cycles from exactly the
            // stretch the player is waiting through.
            MinimumRamMb = settings.RamMb,
            ScreenWidth = settings.ResolutionWidth,
            ScreenHeight = settings.ResolutionHeight,
            ExtraJvmArguments = JvmArgPresets.AikarsFlags
                .Select(flag => new MArgument(flag))
                .ToList()
        };
    }
}
