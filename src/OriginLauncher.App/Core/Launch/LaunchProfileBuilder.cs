using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Launch;

public static class LaunchProfileBuilder
{
    public static MLaunchOption Build(LauncherSettings settings, MSession session, string? version = null)
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
            ExtraJvmArguments = BuildJvmArguments(settings, version)
                .Select(flag => new MArgument(flag))
                .ToList()
        };
    }

    /// <summary>
    /// Origin's tuned preset plus whatever the player set for this version under
    /// Settings -> JVM arguments. Custom args go LAST on purpose: the JVM takes
    /// the last occurrence of a repeated flag, so overriding one preset value
    /// costs nothing else in the set. "Replace Origin's tuned defaults" drops the
    /// preset entirely for that version instead.
    /// </summary>
    public static IReadOnlyList<string> BuildJvmArguments(LauncherSettings settings, string? version)
    {
        var custom = settings.JvmArgsFor(version);
        var flags = new List<string>();

        if (custom is not { ReplacePresets: true })
            flags.AddRange(JvmArgPresets.AikarsFlags);

        if (custom != null)
            flags.AddRange(JvmArgLine.Split(custom.Arguments));

        return flags;
    }
}
