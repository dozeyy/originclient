using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Net;

namespace OriginLauncher.App.Core.Loaders;

// Downloads the catalog-pinned perf-mod jars for a version's profile straight
// from Modrinth's CDN into an instance's mods folder. Skips files that are
// already there — no-op on repeat launches, same idea as CmlLib.Core's own
// "no-op if already installed" version install.
//
// Fail-hard only for a first install: a family with no Origin copy at all
// (no Sodium, no Iris) can't give the promised game. A pin BUMP, though, finds
// Origin's previous jar of that family still in the folder (SharedFolderCleanup
// retires it only after the new one is in), so an offline PC, a Modrinth 5xx/429
// or a hash mismatch keeps that one and retries next launch, instead of making a
// version that played yesterday unplayable after a launcher update (mandate 4).
public static class PerfModInstaller
{
    // Stall-guarded (Core/Net/DownloadHttp): a download that goes quiet fails instead of freezing the install.
    private static readonly HttpClient Http = DownloadHttp.Create();

    public static Task InstallAsync(
        VersionPerfProfile profile, string modsFolder, IProgress<string>? progress = null, CancellationToken ct = default) =>
        InstallAsync(Http, profile, modsFolder, progress, ct);

    internal static async Task InstallAsync(
        HttpClient http, VersionPerfProfile profile, string modsFolder, IProgress<string>? progress, CancellationToken ct)
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

            progress?.Report("Downloading Origin's mods...");
            try
            {
                // The same tries as Mojang's game files (DownloadRetry): one stalled Sodium/Iris download on a shaky
                // connection no longer fails the whole Play. A hash mismatch is not retried (InvalidDataException).
                await DownloadRetry.RunAsync(() => DownloadAsync(http, mod, destPath, ct), ct);
            }
            catch (Exception ex) when (ex is HttpRequestException or IOException or InvalidDataException
                                       || (ex is OperationCanceledException && !ct.IsCancellationRequested))
            {
                if (!HasOriginsCopyOfFamily(modsFolder, mod.FileName)) throw;
                progress?.Report("Couldn't update one of Origin's mods right now - playing with the one you have");
            }
        }
    }

    private static async Task DownloadAsync(HttpClient http, PerfMod mod, string destPath, CancellationToken ct)
    {
        using var response = await http.GetAsync(mod.Url, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();

        var tempPath = destPath + ".download";
        try
        {
            await using (var fileStream = File.Create(tempPath))
                await response.Content.CopyToAsync(fileStream, ct);

            // Never promote a download that doesn't hash to the pinned file.
            if (!mod.Matches(tempPath))
                throw new InvalidDataException($"{mod.FileName} did not match its pinned SHA-512; not installed.");
            // No overwrite: a jar that appeared under this name meanwhile is the player's and stays.
            File.Move(tempPath, destPath, overwrite: false);
            Mods.OriginInstallRecord.Add(destPath, mod.Sha512);
        }
        finally
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* best effort: the next try overwrites it */ }
        }
    }

    /// <summary>Origin's own working jar of <paramref name="fileName"/>'s family is in the folder (by its bytes, never its name).</summary>
    internal static bool HasOriginsCopyOfFamily(string modsFolder, string fileName)
    {
        var family = Mods.ModManager.ModFamilyKey(fileName);
        try
        {
            // Enabled jars only (a turned-off copy doesn't load); matched in code, never a Win32 glob.
            return Directory.EnumerateFiles(modsFolder).Any(path =>
                path.EndsWith(Mods.ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase)
                && string.Equals(Mods.ModManager.ModFamilyKey(Path.GetFileName(path)), family, StringComparison.OrdinalIgnoreCase)
                && Mods.ModManager.IsManagedFile(path));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }
}
