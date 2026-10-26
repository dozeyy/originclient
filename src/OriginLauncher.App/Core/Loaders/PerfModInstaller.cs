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

            // Never promote a download that doesn't hash to the pinned file.
            if (!mod.Matches(tempPath))
            {
                try { File.Delete(tempPath); } catch { /* best effort */ }
                throw new InvalidDataException($"{mod.FileName} did not match its pinned SHA-512; not installed.");
            }
            // No overwrite: a jar that appeared under this name meanwhile is the player's and stays.
            File.Move(tempPath, destPath, overwrite: false);
            Mods.OriginInstallRecord.Add(destPath, mod.Sha512);
        }
    }
}
