using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Loaders;

// OptiFine for the legacy versions — the shader + renderer layer of the
// legacy stack (the Iris+Sodium of the pre-Fabric era; there is no
// alternative on 1.8.9/1.12.2, which is exactly why these versions run Forge).
//
// OptiFine has no license to redistribute, so it is NOT bundled with the
// launcher: it's downloaded at install time, official site first, mirrors
// after. Every source is verified against a pinned SHA-1 before the jar is
// accepted, so a mirror can never hand us a tampered file. Downloads land in
// a launcher-wide cache and installs are a local copy — one download per
// machine, ever.
public static class OptiFineInstaller
{
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var client = new HttpClient();
        // optifine.net serves 503 to clients with no UA.
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) OriginLauncher");
        return client;
    }

    private sealed record OptiFineBuild(string FileName, string Sha1, string[] MirrorUrls);

    private static readonly IReadOnlyDictionary<string, OptiFineBuild> Builds =
        new Dictionary<string, OptiFineBuild>
        {
            // HD_U_M5 — the final 1.8.9 build.
            ["1.8.9"] = new(
                "OptiFine_1.8.9_HD_U_M5.jar",
                "d362d58a28f5373b141b9e426e8e160638bfafcd",
                new[]
                {
                    "https://bmclapi2.bangbang93.com/optifine/1.8.9/HD_U/M5",
                }),
            // HD_U_G5 — the final 1.12.2 build (tested Forge #2847 line).
            ["1.12.2"] = new(
                "OptiFine_1.12.2_HD_U_G5.jar",
                "ca3aea3a09ce215906c346fe190907fe0347b0c4",
                new[]
                {
                    "https://bmclapi2.bangbang93.com/optifine/1.12.2/HD_U/G5",
                    "https://archive.org/download/opti-fine-1.12.2-hd-u-g-5/OptiFine_1.12.2_HD_U_G5.jar",
                    "https://archive.org/download/opti-fine-1.12.2-hd-u-g-5_202510/OptiFine_1.12.2_HD_U_G5.jar",
                }),
        };

    private static string CacheDir => Path.Combine(OriginPaths.Root, "optifine-cache");

    public static bool Supports(string version) => Builds.ContainsKey(version);

    // Installs OptiFine into the instance mods folder as "optifine.jar" (the
    // name ModManager already treats as launcher-managed). Failure is
    // NON-FATAL by contract: the game must still boot without shaders, so the
    // caller decides what to do with a false return (log + carry on).
    public static async Task<bool> InstallAsync(
        string version, string modsFolder, IProgress<string>? progress = null, CancellationToken ct = default)
    {
        if (!Builds.TryGetValue(version, out var build)) return false;

        var destPath = Path.Combine(modsFolder, "optifine.jar");
        // A player's deliberate ".disabled" is always respected. An ENABLED
        // optifine.jar is launcher-managed though, and old-era instances
        // (pre-Fabric-cleanup launchers shipped OptiFine once before) carry
        // stale builds — heal to the pinned build by hash, exactly like the
        // Fabric path heals managed families to catalog pins.
        if (File.Exists(destPath + ".disabled")) return true;
        if (File.Exists(destPath) && Sha1Matches(destPath, build.Sha1)) return true;

        var cached = Path.Combine(CacheDir, build.FileName);
        if (!File.Exists(cached) || !Sha1Matches(cached, build.Sha1))
        {
            var fetched = await DownloadVerifiedAsync(build, progress, ct);
            if (!fetched) return false;
        }

        // Copied beside, then swapped in with one rename: the mods folder is
        // shared by every account (SharedMods), and another account's running
        // game may hold the old optifine.jar open. If it does, the swap fails
        // and this launch uses the copy already there; the next one heals it.
        Directory.CreateDirectory(modsFolder);
        var temp = destPath + ".download";
        try
        {
            File.Copy(cached, temp, overwrite: true);
            File.Move(temp, destPath, overwrite: true);
        }
        catch (Exception ex) when ((ex is IOException or UnauthorizedAccessException) && File.Exists(destPath))
        {
            try { File.Delete(temp); } catch { /* our own half-copy; best-effort */ }
        }
        return true;
    }

    private static async Task<bool> DownloadVerifiedAsync(
        OptiFineBuild build, IProgress<string>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(CacheDir);
        var cached = Path.Combine(CacheDir, build.FileName);
        progress?.Report("Downloading OptiFine...");

        // Official site first (their download page flow), mirrors after. All
        // sources funnel through the same SHA-1 gate.
        var officialUrl = await TryResolveOfficialUrlAsync(build.FileName, ct);
        var sources = officialUrl != null
            ? new List<string> { officialUrl }
            : new List<string>();
        sources.AddRange(build.MirrorUrls);

        foreach (var url in sources)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                var tempPath = cached + ".download";
                using (var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct))
                {
                    if (!response.IsSuccessStatusCode) continue;
                    await using var fileStream = File.Create(tempPath);
                    await response.Content.CopyToAsync(fileStream, ct);
                }
                if (!Sha1Matches(tempPath, build.Sha1))
                {
                    try { File.Delete(tempPath); } catch { }
                    continue;
                }
                File.Move(tempPath, cached, overwrite: true);
                return true;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch
            {
                // Source down/blocked — try the next one.
            }
        }
        return false;
    }

    // optifine.net's download flow: adloadx?f=<file> serves a page whose
    // download link is downloadx?f=<file>&x=<one-time token>. Scrape it; any
    // failure (site down, layout change) just falls through to the mirrors.
    private static async Task<string?> TryResolveOfficialUrlAsync(string fileName, CancellationToken ct)
    {
        try
        {
            var page = await Http.GetStringAsync($"https://optifine.net/adloadx?f={fileName}", ct);
            var match = Regex.Match(page, @"downloadx\?f=[^'""&]+&x=[0-9a-fA-F]+");
            return match.Success ? "https://optifine.net/" + match.Value.Replace("&amp;", "&") : null;
        }
        catch
        {
            return null;
        }
    }

    private static bool Sha1Matches(string filePath, string expectedSha1)
    {
        try
        {
            using var sha1 = SHA1.Create();
            using var stream = File.OpenRead(filePath);
            var hash = Convert.ToHexString(sha1.ComputeHash(stream));
            return hash.Equals(expectedSha1, StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }
}
