using OriginLauncher.App.Core.GameFolders;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// The oldest Fabric Loader a Fabric instance may keep using. Below it the launcher installs a fresh loader (CmlLib's
/// FabricInstaller takes the first loader Fabric's meta lists: the newest, 0.20.1 on 2026-10-25) instead of reusing the
/// cached one.
///
/// Why 0.20.1 and not 0.15 (the modsFolder floor): the jars Origin ships declare newer floors. JEI 11.68 (1.19.2) and
/// the newer JEIs need fabricloader &gt;=0.19.4, Origin's own jars up to &gt;=0.19.5, and every live module builds and
/// boots in CI on 0.20.1 (gradle.properties loader_version). A player whose instance cached an older loader (0.16 or
/// 0.19.3 from an earlier install) would otherwise get Fabric's "Incompatible mods found" on the next launch.
/// One floor for every Fabric version: Fabric Loader is not tied to a Minecraft version, and 0.20.1's classes are
/// Java 8 bytecode, so it runs on 1.16.5 too. Raise it together with the modules' loader_version.
/// </summary>
public static class FabricLoaderFloor
{
    public static readonly Version Minimum = new(0, 20, 1);

    /// <summary>True when the profile name ("fabric-loader-0.20.1-1.21.1") names a loader at or above the floor.</summary>
    public static bool IsMet(string? versionName) =>
        SharedMods.LoaderVersionOf(versionName) is { } version && version >= Minimum;

    /// <summary>
    /// True when a fast-path stamp's cached loader is below the floor, so the launch must take the full path: the
    /// loader is reinstalled, and only a full verify re-stamps the instance with the new loader's name (otherwise every
    /// later launch would see the old name again and reinstall the loader each time). No cached name = already full.
    /// </summary>
    public static bool ForcesFullVerify(string? cachedVersionName) =>
        cachedVersionName != null && !IsMet(cachedVersionName);
}
