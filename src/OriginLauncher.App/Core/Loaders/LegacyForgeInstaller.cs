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

    private static IReadOnlyDictionary<string, LegacyForgeBuilds.ForgeBuild> Builds => LegacyForgeBuilds.Builds;

    public static bool IsLegacy(string version) => LegacyForgeBuilds.IsLegacy(version);

    public static LegacyLoaderLayout? LayoutFor(string version) => LegacyForgeBuilds.LayoutFor(version);

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
