using System.Net.Http;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace OriginSetup.Engine;

/// <summary>The launcher build Setup will install: where to download it and what to expect.</summary>
public sealed class LauncherRelease
{
    public LauncherRelease(string version, string url, long? size = null, string? sha256 = null)
    {
        Version = version;
        Url = url;
        Size = size;
        Sha256 = sha256;
    }

    /// <summary>"1.0.40" — from the release tag.</summary>
    public string Version { get; }
    public string Url { get; }
    /// <summary>Zip size in bytes, when the feed said (the API does, the fallback doesn't).</summary>
    public long? Size { get; }
    /// <summary>Lower-case hex SHA-256 of the zip, when the feed said.</summary>
    public string? Sha256 { get; }
}

// Finds the newest released launcher — the same OriginLauncher-win-x64.zip
// asset every installed launcher self-updates from (the launcher's
// Core/Updates/UpdateService.cs reads the same feed; the names below must
// match it, and tools/release/check_consistency.py fails CI if they drift).
//
// Two independent routes, so a GitHub API hiccup never blocks an install:
//  1. The releases API (unauthenticated; 60 calls/hour per IP) — gives the
//     version, the asset URL, its size and its SHA-256.
//  2. github.com/<repo>/releases/latest, which redirects to the newest
//     release's page (no API, no rate limit). The tag in that final URL gives
//     the version, and the asset's URL is built from it — pinned to that
//     version, so a release published mid-download can't mix two builds.
public static class ReleaseFeed
{
    public const string Owner = "dozeyy";
    public const string Repo = "originclient";
    public const string TagPrefix = "launcher-v";
    public const string AssetName = "OriginLauncher-win-x64.zip";

    public static string ApiUrl => $"https://api.github.com/repos/{Owner}/{Repo}/releases/latest";
    public static string LatestPageUrl => $"https://github.com/{Owner}/{Repo}/releases/latest";

    public static string AssetUrl(string tag) =>
        $"https://github.com/{Owner}/{Repo}/releases/download/{tag}/{AssetName}";

    /// <summary>
    /// The newest release. Throws <see cref="DownloadException"/> (worded for
    /// the player) when neither route answers — almost always no internet.
    /// </summary>
    public static async Task<LauncherRelease> FindLatestAsync(HttpClient http, CancellationToken ct)
    {
        Exception? apiProblem;
        try
        {
            using var response = await http.GetAsync(ApiUrl, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                throw new HttpRequestException($"release API answered HTTP {(int)response.StatusCode}");
            var json = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
            var release = ParseApi(json);
            if (release != null)
            {
                SetupLog.Write($"release feed (API): {release.Version}, {release.Size?.ToString() ?? "?"} bytes, sha256 {(release.Sha256 != null ? "known" : "unknown")}");
                return release;
            }
            apiProblem = new InvalidDataException("release API: no launcher release with " + AssetName);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && IsNetworkOrData(ex))
        {
            apiProblem = ex;
        }
        SetupLog.Write("release feed: API unavailable (" + apiProblem.Message + "), trying the releases page");

        try
        {
            using var response = await http.GetAsync(LatestPageUrl, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            var final = response.RequestMessage?.RequestUri;
            var tag = response.IsSuccessStatusCode ? TagFromReleasePage(final) : null;
            if (tag != null)
            {
                var release = new LauncherRelease(tag.Substring(TagPrefix.Length), AssetUrl(tag));
                SetupLog.Write($"release feed (page): {release.Version}");
                return release;
            }
            SetupLog.Write($"release feed: page answered HTTP {(int)response.StatusCode} at {final}");
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && IsNetworkOrData(ex))
        {
            SetupLog.Write("release feed: page unavailable: " + ex.Message);
            throw new DownloadException(DownloadException.CantConnect, ex);
        }
        ct.ThrowIfCancellationRequested();
        throw new DownloadException(DownloadException.ServerProblem, apiProblem);
    }

    /// <summary>The release in a releases-API answer, or null when it isn't a launcher release with the zip.</summary>
    public static LauncherRelease? ParseApi(byte[] json)
    {
        ApiRelease? r;
        try
        {
            using var ms = new MemoryStream(json);
            r = new DataContractJsonSerializer(typeof(ApiRelease)).ReadObject(ms) as ApiRelease;
        }
        catch (SerializationException) { return null; }

        var version = VersionFromTag(r?.TagName);
        var asset = r?.Assets?.FirstOrDefault(a => string.Equals(a.Name, AssetName, StringComparison.OrdinalIgnoreCase));
        if (version == null || asset?.Url == null || !asset.Url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            return null;

        string? sha = null;
        const string prefix = "sha256:";
        if (asset.Digest != null && asset.Digest.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
            && asset.Digest.Length == prefix.Length + 64)
            sha = asset.Digest.Substring(prefix.Length).ToLowerInvariant();
        return new LauncherRelease(version, asset.Url, asset.Size > 0 ? asset.Size : (long?)null, sha);
    }

    /// <summary>"launcher-v1.0.40" from ".../releases/tag/launcher-v1.0.40", else null.</summary>
    public static string? TagFromReleasePage(Uri? finalUrl)
    {
        if (finalUrl == null) return null;
        var path = finalUrl.AbsolutePath.TrimEnd('/');
        const string marker = "/releases/tag/";
        int at = path.LastIndexOf(marker, StringComparison.Ordinal);
        if (at < 0) return null;
        var tag = Uri.UnescapeDataString(path.Substring(at + marker.Length));
        return VersionFromTag(tag) != null ? tag : null;
    }

    private static string? VersionFromTag(string? tag)
    {
        if (tag == null || !tag.StartsWith(TagPrefix, StringComparison.OrdinalIgnoreCase)) return null;
        var v = tag.Substring(TagPrefix.Length);
        return Version.TryParse(v, out _) ? v : null;
    }

    internal static bool IsNetworkOrData(Exception ex) =>
        ex is HttpRequestException || ex is IOException || ex is System.Net.WebException
        || ex is InvalidDataException || ex is TaskCanceledException // HttpClient.Timeout surfaces as this
        || ex is ObjectDisposedException;

    [DataContract]
    private sealed class ApiRelease
    {
        [DataMember(Name = "tag_name")] public string? TagName { get; set; }
        [DataMember(Name = "assets")] public List<ApiAsset>? Assets { get; set; }
    }

    [DataContract]
    private sealed class ApiAsset
    {
        [DataMember(Name = "name")] public string? Name { get; set; }
        [DataMember(Name = "browser_download_url")] public string? Url { get; set; }
        [DataMember(Name = "size")] public long Size { get; set; }
        [DataMember(Name = "digest")] public string? Digest { get; set; }
    }
}
