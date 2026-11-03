using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Net;

namespace OriginLauncher.App.Core.Loaders;

// Downloads the catalog-pinned perf-mod jars for a version's profile straight
// from Modrinth's CDN into an instance's mods folder. Skips files that are
// already there — no-op on repeat launches, same idea as CmlLib.Core's own
// "no-op if already installed" version install.
public static class PerfModInstaller
{
    // Stall-guarded (Core/Net/DownloadHttp): a download that goes quiet fails instead of freezing the install.
    private static readonly HttpClient Http = DownloadHttp.Create();

    public static async Task InstallAsync(
        VersionPerfProfile profile, string modsFolder, IProgress<string>? progress = null, CancellationToken ct = default)
    {
        Directory.CreateDirectory(modsFolder);

        foreach (var mod in profile.Mods())
        {
            var destPath = Path.Combine(modsFolder, mod.FileName);
            // Already on: nothing to do. Origin's OWN copy turned off (the Mods page used to offer a switch on it
            // before it knew the jar was Origin's, and then it stayed off for good): turned back on by a rename.
            // Any other ".jar.disabled" of this name is the player's and is left alone, not downloaded over.
            if (File.Exists(destPath)
                || Mods.ModManager.TryRestoreOriginsJar(modsFolder, mod.FileName, mod.Matches)
                || File.Exists(destPath + ".disabled")) continue;

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
