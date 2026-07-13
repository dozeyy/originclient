namespace OriginLauncher.App.Core.Loaders;

// Per-version data (Data dictionary) is generated from a live Modrinth API
// snapshot — see PerformanceModCatalog.Data.cs. This half holds the lookup
// API used by the launch pipeline and the version picker.
public static partial class PerformanceModCatalog
{
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
}
