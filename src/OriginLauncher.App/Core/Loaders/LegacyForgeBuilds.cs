using System.IO;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.Core.Loaders;

// The pinned legacy Forge builds (1.8.9 live, 1.12.2 pulled) as plain data, apart from the installer so the
// table can be read without CmlLib: LegacyForgeInstaller installs from it, InstalledVersions checks the same
// files against it, and the tests assert the real values.
public static class LegacyForgeBuilds
{
    public sealed record ForgeBuild(
        string VersionId,          // versions/<id>/<id>.json - what CmlLib launches
        string JsonAsset,          // bundled JSON filename under Bundled/Forge
        string UniversalUrl,       // official maven, verified reachable
        string UniversalLibPath);  // where the JSON's forge library entry expects the jar

    public static readonly IReadOnlyDictionary<string, ForgeBuild> Builds =
        new Dictionary<string, ForgeBuild>
        {
            ["1.8.9"] = new(
                "1.8.9-forge1.8.9-11.15.1.2318-1.8.9",
                "forge-1.8.9.json",
                "https://maven.minecraftforge.net/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar",
                Path.Combine("net", "minecraftforge", "forge", "1.8.9-11.15.1.2318-1.8.9", "forge-1.8.9-11.15.1.2318-1.8.9.jar")),
            // Runtime pin is 2860 (the final 1.12.2 build, network-security
            // fix); the mod module compiles against 2847 - same 14.23.5 API.
            ["1.12.2"] = new(
                "1.12.2-forge-14.23.5.2860",
                "forge-1.12.2.json",
                "https://maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-universal.jar",
                Path.Combine("net", "minecraftforge", "forge", "1.12.2-14.23.5.2860", "forge-1.12.2-14.23.5.2860.jar")),
        };

    public static bool IsLegacy(string version) => Builds.ContainsKey(version);

    /// <summary>Where a legacy version keeps its Forge profile and universal jar, or null for a Fabric version. Read-only.</summary>
    public static LegacyLoaderLayout? LayoutFor(string version) =>
        Builds.TryGetValue(version, out var build) ? new LegacyLoaderLayout(build.VersionId, build.UniversalLibPath) : null;
}
