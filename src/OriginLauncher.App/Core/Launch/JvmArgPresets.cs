using System.Linq;
namespace OriginLauncher.App.Core.Launch;

public static class JvmArgPresets
{
    // Aikar's flags — the widely-published, community-standard G1GC tuning
    // set used across Minecraft server/client launchers. Real, documented
    // flags (not invented). Applied to every launch: since the old
    // Graphics/Performance mode toggle was removed there is exactly one
    // JVM preset, and this tuned set is the right baseline for a game.
    public static readonly IReadOnlyList<string> AikarsFlags =
    [
        "-XX:+UseG1GC",
        "-XX:+ParallelRefProcEnabled",
        "-XX:MaxGCPauseMillis=200",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+DisableExplicitGC",
        // NOTE: Aikar's published set includes -XX:+AlwaysPreTouch. It is
        // deliberately omitted here. Pre-touch faults in the ENTIRE committed
        // heap before main() runs, and because Origin now sets -Xms == -Xmx
        // (see LaunchProfileBuilder) that is the full slider value — several
        // hundred ms of blocking page-faulting on an 8-16 GB heap, paid on
        // every launch, right where the player is waiting. Its benefit is
        // smoothing later allocation jitter, which matters for a long-running
        // server and very little for a desktop client.
        "-XX:G1NewSizePercent=30",
        "-XX:G1MaxNewSizePercent=40",
        "-XX:G1HeapRegionSize=8M",
        "-XX:G1ReservePercent=20",
        "-XX:G1HeapWastePercent=5",
        "-XX:G1MixedGCCountTarget=4",
        "-XX:InitiatingHeapOccupancyPercent=15",
        "-XX:G1MixedGCLiveThresholdPercent=90",
        "-XX:G1RSetUpdatingPauseTimePercent=5",
        "-XX:SurvivorRatio=32",
        "-XX:+PerfDisableSharedMem",
        "-XX:MaxTenuringThreshold=1"
    ];

    // The same set re-sized for a legacy (1.8.9-era) heap of up to LegacySmallHeapMaxMb.
    // Aikar's values assume a big heap: 30%/40% young generation and 8 MB regions give a
    // 3.6 GB+ young gen at 12 GB (the 2026-10-20 1.8.9 log). Here the young generation is
    // 25-35% (about 1-1.4 GB at 4 GB) and regions are 4 MB, so a young collection stays short.
    // Every other flag is Aikar's, unchanged. Modern versions keep AikarsFlags.
    public const int LegacySmallHeapMaxMb = 6144;
    public static readonly IReadOnlyList<string> LegacySmallHeapFlags =
        AikarsFlags.Select(f => f switch
        {
            "-XX:G1NewSizePercent=30" => "-XX:G1NewSizePercent=25",
            "-XX:G1MaxNewSizePercent=40" => "-XX:G1MaxNewSizePercent=35",
            "-XX:G1HeapRegionSize=8M" => "-XX:G1HeapRegionSize=4M",
            _ => f,
        }).ToList();

    /// <summary>The G1 set for this launch: the small-heap one for a legacy version on a small heap, else Aikar's.</summary>
    public static IReadOnlyList<string> FlagsFor(bool legacy, int maxHeapMb) =>
        legacy && maxHeapMb <= LegacySmallHeapMaxMb ? LegacySmallHeapFlags : AikarsFlags;

    // Per-version boot speed-ups, measured one at a time with the boot-time
    // workflow (.github/workflows/boot-time.yml) and checked in dev shots before
    // they were listed here. Only versions where that was done get them.
    private static readonly IReadOnlyDictionary<string, IReadOnlyList<string>> BootArgs =
        new Dictionary<string, IReadOnlyList<string>>
        {
            // ModernFix (bundled in the 1.21.1 jar) bakes block/item models the
            // first time they are used instead of every model under the loading
            // screen: the startup reload went 4.1 s -> 2.3 s, time to an
            // interactive title -11.8% and -10.3% (CI runs 39270959643 and
            // 39284372584, 5 boots each, 2026-10-19). ModernFix's
            // own documented switch, read as a JVM property before its config file.
            // The 1.21.1 module's build.gradle runs dev builds with the same flag.
            ["1.21.1"] = ["-Dmodernfix.config.mixin.perf.dynamic_resources=true"],
        };

    /// <summary>The boot flags measured for this version (none for the rest).</summary>
    public static IReadOnlyList<string> BootArgsFor(string? version) =>
        version != null && BootArgs.TryGetValue(version, out var args) ? args : [];
}
