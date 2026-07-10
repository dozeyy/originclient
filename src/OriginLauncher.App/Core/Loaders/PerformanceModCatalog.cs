namespace OriginLauncher.App.Core.Loaders;

// Per-version data (Data dictionary) is generated from a live Modrinth API
// snapshot — see PerformanceModCatalog.Data.cs. This half holds the lookup
// API and the Fabric-vs-Forge recommendation used to drive the loader
// selector and the launch pipeline.
public static partial class PerformanceModCatalog
{
    // Oldest Minecraft version where the Fabric performance-mod ecosystem
    // exists at all (Sodium/Lithium's oldest tagged builds). Below this,
    // there's no Fabric perf stack to offer — Forge (+ optional OptiFine) is
    // the only path.
    public const string FabricFloor = "1.16.3";

    public static VersionPerfProfile? TryGet(string mcVersion) =>
        Data.TryGetValue(mcVersion, out var profile) ? profile : null;

    // True only when this version has the full, shader-capable stack — Sodium
    // (the renderer Iris is built on) AND Iris itself. Used to gate the version
    // picker so Origin never offers a version where shaders/Sodium don't exist
    // yet (e.g. a just-released Minecraft the perf mods haven't caught up to).
    // Self-updating: regenerating PerformanceModCatalog.Data.cs from Modrinth
    // (once Sodium/Iris ship for a new version) makes it appear automatically.
    public static bool HasShaderStack(string mcVersion) =>
        Data.TryGetValue(mcVersion, out var p) && p.Sodium != null && p.Iris != null;

    // Whether Fabric is the recommended loader for this version — i.e. we
    // have at least a partial perf-mod profile for it. Versions inside the
    // Fabric-supporting range but missing from Data (a handful of odd point
    // releases where one mod skipped a build) fall back to Forge too, since
    // "Fabric with zero perf mods" isn't worth it over Forge+OptiFine.
    public static bool RecommendsFabric(string mcVersion) => Data.ContainsKey(mcVersion);
}
