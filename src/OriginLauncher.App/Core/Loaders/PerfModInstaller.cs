using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Mods;

namespace OriginLauncher.App.Core.Loaders;

// Downloads the catalog-pinned perf-mod jars for a version's profile straight
// from Modrinth's CDN into an instance's mods folder. Skips files that are
// already there — no-op on repeat launches, same idea as CmlLib.Core's own
// "no-op if already installed" version install.
public static class PerfModInstaller
{
    private static readonly HttpClient Http = new();

    // The always-on stack (core slots + Extras).
    public static Task InstallAsync(
        VersionPerfProfile profile, string modsFolder, IProgress<string>? progress = null, CancellationToken ct = default)
        => DownloadMissingAsync(profile.Mods(), modsFolder, progress, ct);

    // The opt-in mods (VersionPerfProfile.Optional), each gated by the matching
    // Settings -> Performance flag: C2ME by chunkMt, Starlight/ScalableLux by
    // fastLight. Only the enabled families are downloaded — a disabled family is
    // left untouched here and removed by VersionManager's optional-off sweep.
    public static Task InstallOptionalAsync(
        VersionPerfProfile profile, string modsFolder, bool chunkMt, bool fastLight,
        IProgress<string>? progress = null, CancellationToken ct = default)
    {
        var wanted = new List<PerfMod>();
        foreach (var mod in profile.Optional ?? [])
        {
            if (chunkMt && ModManager.IsC2meJar(mod.FileName)) wanted.Add(mod);
            else if (fastLight && ModManager.IsLightEngineJar(mod.FileName)) wanted.Add(mod);
        }
        return DownloadMissingAsync(wanted, modsFolder, progress, ct);
    }

    private static async Task DownloadMissingAsync(
        IEnumerable<PerfMod> mods, string modsFolder, IProgress<string>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(modsFolder);

        foreach (var mod in mods)
        {
            var destPath = Path.Combine(modsFolder, mod.FileName);
            // Skip if already present in EITHER state — an enabled ".jar" or a
            // ".jar.disabled" the player turned off. Re-downloading over a
            // disabled copy would leave two of the same mod id and refuse to launch.
            if (File.Exists(destPath) || File.Exists(destPath + ".disabled")) continue;

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
