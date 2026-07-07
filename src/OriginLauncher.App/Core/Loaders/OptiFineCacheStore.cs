using System.IO;

namespace OriginLauncher.App.Core.Loaders;

// One cached OptiFine.jar per Minecraft version, fetched via OptiFineCatalog
// (BMCLAPI mirror) the first time the player enables the toggle for that
// version. Cached globally rather than per-instance since the same jar
// applies to every Forge instance of that version.
public static class OptiFineCacheStore
{
    private static string DirFor(string mcVersion) => Path.Combine(OriginPaths.OptiFineCache, mcVersion);

    public static string JarPathFor(string mcVersion) => Path.Combine(DirFor(mcVersion), "OptiFine.jar");

    public static bool IsCached(string mcVersion) => File.Exists(JarPathFor(mcVersion));
}
