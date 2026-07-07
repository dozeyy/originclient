using System.IO;
using System.Net.Http;

namespace OriginLauncher.App.Core.Loaders;

// Downloads the catalog-pinned perf-mod jars for a version's profile straight
// from Modrinth's CDN into an instance's mods folder. Skips files that are
// already there — no-op on repeat launches, same idea as CmlLib.Core's own
// "no-op if already installed" version install.
public static class PerfModInstaller
{
    private static readonly HttpClient Http = new();

    public static async Task InstallAsync(
        VersionPerfProfile profile, string modsFolder, IProgress<string>? progress = null, CancellationToken ct = default)
    {
        Directory.CreateDirectory(modsFolder);

        foreach (var mod in profile.Mods())
        {
            var destPath = Path.Combine(modsFolder, mod.FileName);
            if (File.Exists(destPath)) continue;

            progress?.Report($"Downloading {mod.FileName}...");
            using var response = await Http.GetAsync(mod.Url, HttpCompletionOption.ResponseHeadersRead, ct);
            response.EnsureSuccessStatusCode();

            var tempPath = destPath + ".download";
            await using (var fileStream = File.Create(tempPath))
                await response.Content.CopyToAsync(fileStream, ct);
            File.Move(tempPath, destPath, overwrite: true);
        }
    }
}
