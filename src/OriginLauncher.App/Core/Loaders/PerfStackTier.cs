namespace OriginLauncher.App.Core.Loaders;

public enum PerfStackTier
{
    // No safe automated performance mod exists for this version (pre-1.16.3,
    // where the whole Fabric perf-mod ecosystem doesn't exist yet).
    None,

    // Lithium + FerriteCore + Krypton only. Used whenever Indium isn't
    // available/verified for this version — Sodium is deliberately left out
    // rather than risk breaking other mods' Fabric Rendering API usage.
    Partial,

    // Full stack: Sodium + Indium + Lithium + FerriteCore + Krypton, with
    // Indium's required-Sodium dependency confirmed to target this same
    // Minecraft version.
    Full
}
