using System.IO;
using System.Net.Http;
using CmlLib.Core;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.Core.Loaders;

// Installs legacy Forge (1.8.9 / 1.12.2) the deterministic way: pre-1.13
// Forge is nothing but a version JSON (bundled with the launcher, extracted
// once from the official installer) + the universal jar placed at its library
// path. No installer execution, no processors — CmlLib resolves the rest of
// the JSON (parent version, libraries, natives, Java 8 runtime via the
// vanilla jre-legacy declaration) exactly like any custom version.
public static class LegacyForgeInstaller
{
    private static readonly HttpClient Http = new();

    private sealed record ForgeBuild(
        string VersionId,          // versions/<id>/<id>.json — what CmlLib launches
        string JsonAsset,          // bundled JSON filename under Bundled/Forge
        string UniversalUrl,       // official maven, verified reachable
        string UniversalLibPath);  // where the JSON's forge library entry expects the jar

    private static readonly IReadOnlyDictionary<string, ForgeBuild> Builds =
        new Dictionary<string, ForgeBuild>
        {
            ["1.8.9"] = new(
                "1.8.9-forge1.8.9-11.15.1.2318-1.8.9",
                "forge-1.8.9.json",
                "https://maven.minecraftforge.net/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar",
                Path.Combine("net", "minecraftforge", "forge", "1.8.9-11.15.1.2318-1.8.9", "forge-1.8.9-11.15.1.2318-1.8.9.jar")),
            // Runtime pin is 2860 (the final 1.12.2 build, network-security
            // fix); the mod module compiles against 2847 — same 14.23.5 API.
            ["1.12.2"] = new(
                "1.12.2-forge-14.23.5.2860",
                "forge-1.12.2.json",
                "https://maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-universal.jar",
                Path.Combine("net", "minecraftforge", "forge", "1.12.2-14.23.5.2860", "forge-1.12.2-14.23.5.2860.jar")),
        };

    public static bool IsLegacy(string version) => Builds.ContainsKey(version);

    // Where a legacy version keeps its Forge profile and universal jar, so InstalledVersions can tell
    // "installed" from "half there" without running the installer. Null for a Fabric version. Read-only.
    public static LegacyLoaderLayout? LayoutFor(string version) =>
        Builds.TryGetValue(version, out var build) ? new LegacyLoaderLayout(build.VersionId, build.UniversalLibPath) : null;

    // Returns the version id to launch (the analogue of FabricInstaller.Install's
    // return). Idempotent: existing JSON/jar are left alone, so repeat launches
    // cost nothing.
    public static async Task<string> InstallAsync(
        string version, MinecraftPath path, IProgress<string>? progress = null, CancellationToken ct = default)
    {
        var build = Builds[version];

        var versionDir = Path.Combine(path.Versions, build.VersionId);
        var jsonPath = Path.Combine(versionDir, build.VersionId + ".json");
        if (!File.Exists(jsonPath))
        {
            progress?.Report("Installing Forge loader...");
            Directory.CreateDirectory(versionDir);
            var bundled = Path.Combine(AppContext.BaseDirectory, "Bundled", "Forge", build.JsonAsset);
            File.Copy(bundled, jsonPath, overwrite: true);
        }

        // The JSON's own forge library entry has no downloadable URL (the
        // plain-classifier jar was never published — installers extracted it).
        // Place the universal jar at that library path ourselves; CmlLib then
        // sees the file exists and moves on.
        var libPath = Path.Combine(path.Library, build.UniversalLibPath);
        if (!File.Exists(libPath))
        {
            progress?.Report("Downloading Forge...");
            Directory.CreateDirectory(Path.GetDirectoryName(libPath)!);
            using var response = await Http.GetAsync(build.UniversalUrl, HttpCompletionOption.ResponseHeadersRead, ct);
            response.EnsureSuccessStatusCode();
            var tempPath = libPath + ".download";
            await using (var fileStream = File.Create(tempPath))
                await response.Content.CopyToAsync(fileStream, ct);
            File.Move(tempPath, libPath, overwrite: true);
        }

        return build.VersionId;
    }
}
