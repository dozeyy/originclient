using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace OriginLauncher.App.Core.Loaders;

// OptiFine has no official download API or CDN — optifine.net gates the jar
// behind an ad page by design, and its license forbids redistributing the
// jar ourselves. BMCLAPI (bmclapi2.bangbang93.com) is the community mirror
// real open-source launchers (HMCL, PCL2, and others) use to solve exactly
// this problem: it mirrors OptiFine's version list and jars directly, so a
// player can get one-click install without visiting the ad-gated page.
public static class OptiFineCatalog
{
    private const string VersionListUrl = "https://bmclapi2.bangbang93.com/optifine/versionList";
    private static readonly HttpClient Http = new();

    private static IReadOnlyList<OptiFineEntry>? _cachedList;

    public static async Task<OptiFineEntry?> TryFindFor(string mcVersion, CancellationToken ct = default)
    {
        _cachedList ??= await FetchListAsync(ct);
        // BMCLAPI returns entries newest-patch-first per Minecraft version.
        return _cachedList.FirstOrDefault(e => e.McVersion == mcVersion);
    }

    public static async Task DownloadAsync(OptiFineEntry entry, string destPath, CancellationToken ct = default)
    {
        var url = $"https://bmclapi2.bangbang93.com/optifine/{entry.McVersion}/{entry.Type}/{entry.Patch}";
        using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();

        Directory.CreateDirectory(Path.GetDirectoryName(destPath)!);
        var tempPath = destPath + ".download";
        await using (var fileStream = File.Create(tempPath))
            await response.Content.CopyToAsync(fileStream, ct);
        File.Move(tempPath, destPath, overwrite: true);
    }

    private static async Task<IReadOnlyList<OptiFineEntry>> FetchListAsync(CancellationToken ct)
    {
        using var response = await Http.GetAsync(VersionListUrl, ct);
        response.EnsureSuccessStatusCode();
        var json = await response.Content.ReadAsStreamAsync(ct);
        var entries = await JsonSerializer.DeserializeAsync<List<OptiFineEntry>>(json, cancellationToken: ct);
        return entries ?? new List<OptiFineEntry>();
    }
}

public sealed class OptiFineEntry
{
    [JsonPropertyName("mcversion")]
    public string McVersion { get; set; } = "";

    [JsonPropertyName("patch")]
    public string Patch { get; set; } = "";

    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("filename")]
    public string FileName { get; set; } = "";
}
