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
}
