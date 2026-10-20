using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Mods.Icons;

/// <summary>
/// Finds the real icon for a mod, off the UI thread, in this order:
///  1. the jar's own icon (JarIconReader: offline, instant);
///  2. Modrinth by file hash: SHA-1 -> /v2/version_file/{sha1} -> project_id -> /v2/project/{id} -> icon_url
///     (only the file's hash is sent; the image is fetched from cdn.modrinth.com only, capped at 512 KB);
///  3. for Origin's own built-in mods, the Modrinth project by slug.
/// Everything lands in <see cref="ModIconCache"/> keyed by SHA-1 (or slug), negative answers included.
/// A network failure returns null and stores nothing, so offline is silent and retried next time.
/// CurseForge is not used: its API needs a key. Returns raw image bytes; the page decodes them.
/// </summary>
public static class ModIconSource
{
    public const long MaxDownloadBytes = 512 * 1024;
    public const string CdnHost = "cdn.modrinth.com";
    private const string Api = "https://api.modrinth.com/v2/";

    private static readonly Regex ProjectIdText = new("^[A-Za-z0-9]{4,16}$", RegexOptions.CultureInvariant);

    private static readonly HttpClient Http = CreateClient();
    private static readonly SemaphoreSlim NetworkGate = new(3);
    private static ModIconCache? _cache;

    // Render mode (UI/RenderShots.cs): icons for the made-up rows, so shots never touch the network.
    public static IReadOnlyDictionary<string, byte[]>? DemoIcons { get; set; }

    public static ModIconCache Cache => _cache ??= new ModIconCache(OriginPaths.ModIconCache, TimeSpan.FromDays(7));

    private static HttpClient CreateClient()
    {
        var client = new HttpClient(new SocketsHttpHandler
        {
            AllowAutoRedirect = false, // an icon must come from the allow-listed host, not wherever a redirect points
            ConnectTimeout = TimeSpan.FromSeconds(8),
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        })
        {
            Timeout = TimeSpan.FromSeconds(12),
        };
        // Modrinth asks every client for a descriptive User-Agent.
        client.DefaultRequestHeaders.UserAgent.ParseAdd("OriginLauncher/1.0 (+will@willhenry.me)");
        return client;
    }

    // ---- pure helpers (unit-tested, no network) ------------------------------

    /// <summary>True only for an https URL on cdn.modrinth.com (the one host icons may come from).</summary>
    public static bool IsAllowedIconUrl(string? url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var u)
        && u.Scheme == Uri.UriSchemeHttps
        && string.Equals(u.Host, CdnHost, StringComparison.OrdinalIgnoreCase)
        && u.IsDefaultPort
        && string.IsNullOrEmpty(u.UserInfo);

    /// <summary>"project_id" from a version_file answer, only when it is a plain id.</summary>
    public static string? ParseProjectId(string json) => ReadString(json, "project_id") is { } id && ProjectIdText.IsMatch(id) ? id : null;

    /// <summary>"icon_url" from a project answer, only when it passes the allow-list.</summary>
    public static string? ParseIconUrl(string json) => ReadString(json, "icon_url") is { } u && IsAllowedIconUrl(u) ? u : null;

    private static string? ReadString(string json, string name)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.ValueKind == JsonValueKind.Object
                && doc.RootElement.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
                ? v.GetString() : null;
        }
        catch (JsonException) { return null; }
    }

    /// <summary>The Modrinth slug for one of Origin's built-in mods by jar name, or null.</summary>
    public static string? BuiltInSlug(string fileName)
    {
        var n = fileName.ToLowerInvariant();
        if (n.StartsWith("sodium")) return "sodium";
        if (n.StartsWith("iris")) return "iris";
        if (n.StartsWith("lithium")) return "lithium";
        if (n.StartsWith("modmenu")) return "modmenu";
        if (n.StartsWith("jei") || n.StartsWith("jei-")) return "jei";
        if (n.StartsWith("fabric-api")) return "fabric-api";
        return null; // Origin Client keeps its own mark
    }

    // ---- resolving ------------------------------------------------------------

    /// <summary>
    /// The icon bytes for the jar at <paramref name="jarPath"/>, or null (no icon, or offline).
    /// <paramref name="slug"/> is only for built-ins whose hash is not on Modrinth.
    /// </summary>
    public static async Task<byte[]?> ResolveAsync(string jarPath, string? slug, CancellationToken ct = default)
    {
        try
        {
            var sha1 = await Task.Run(() => Sha1Of(jarPath), ct).ConfigureAwait(false);
            var key = ModIconCache.KeyForSha1(sha1);
            if (key == null) return null;

            var state = Cache.Lookup(key);
            if (state == IconLookup.Hit) return Cache.ReadHit(key);
            if (state == IconLookup.Miss) return null;

            var inJar = JarIconReader.ReadIcon(jarPath);
            if (inJar != null)
            {
                Cache.StoreHit(key, inJar);
                return inJar;
            }

            var (bytes, definitive) = await FromModrinthHashAsync(sha1!, ct).ConfigureAwait(false);
            if (bytes == null && definitive && slug != null)
                (bytes, definitive) = await FromSlugAsync(slug, ct).ConfigureAwait(false);

            if (bytes != null) Cache.StoreHit(key, bytes);
            else if (definitive) Cache.StoreMiss(key);
            return bytes;
        }
        catch
        {
            return null; // never an error into the page
        }
    }

    /// <summary>The icon found for this jar would not decode: remember "no icon" instead of retrying it forever.</summary>
    public static void MarkUndecodable(string jarPath)
    {
        var key = ModIconCache.KeyForSha1(Sha1Of(jarPath));
        if (key != null) Cache.StoreMiss(key);
    }

    private static string? Sha1Of(string path)
    {
        try
        {
            using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return Convert.ToHexString(SHA1.HashData(fs)).ToLowerInvariant();
        }
        catch { return null; }
    }

    // (bytes, definitive): definitive = Modrinth answered "no icon / unknown file", so cache that;
    // false = a network or server failure, so cache nothing.
    private static async Task<(byte[]?, bool)> FromModrinthHashAsync(string sha1, CancellationToken ct)
    {
        var (status, body) = await GetTextAsync($"{Api}version_file/{sha1}?algorithm=sha1", ct).ConfigureAwait(false);
        if (status == HttpStatusCode.NotFound) return (null, true);
        if (body == null) return (null, false);
        var projectId = ParseProjectId(body);
        if (projectId == null) return (null, true);
        return await FromProjectAsync(projectId, ct).ConfigureAwait(false);
    }

    private static async Task<(byte[]?, bool)> FromSlugAsync(string slug, CancellationToken ct)
    {
        var key = ModIconCache.KeyForSlug(slug);
        if (key == null) return (null, true);
        var state = Cache.Lookup(key);
        if (state == IconLookup.Hit) return (Cache.ReadHit(key), true);
        if (state == IconLookup.Miss) return (null, true);

        var (bytes, definitive) = await FromProjectAsync(slug.ToLowerInvariant(), ct).ConfigureAwait(false);
        if (bytes != null) Cache.StoreHit(key, bytes);
        else if (definitive) Cache.StoreMiss(key);
        return (bytes, definitive);
    }

    private static async Task<(byte[]?, bool)> FromProjectAsync(string idOrSlug, CancellationToken ct)
    {
        var (status, body) = await GetTextAsync($"{Api}project/{idOrSlug}", ct).ConfigureAwait(false);
        if (status == HttpStatusCode.NotFound) return (null, true);
        if (body == null) return (null, false);
        var url = ParseIconUrl(body);
        if (url == null) return (null, true);
        var image = await GetCappedAsync(url, ct).ConfigureAwait(false);
        return image == null ? (null, false) : (image, true);
    }

    private static async Task<(HttpStatusCode?, string?)> GetTextAsync(string url, CancellationToken ct)
    {
        await NetworkGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) return (response.StatusCode, null);
            var bytes = await ReadCappedAsync(response, 256 * 1024, ct).ConfigureAwait(false);
            return (response.StatusCode, bytes == null ? null : System.Text.Encoding.UTF8.GetString(bytes));
        }
        catch { return (null, null); }
        finally { NetworkGate.Release(); }
    }

    private static async Task<byte[]?> GetCappedAsync(string url, CancellationToken ct)
    {
        if (!IsAllowedIconUrl(url)) return null;
        await NetworkGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            return response.IsSuccessStatusCode ? await ReadCappedAsync(response, MaxDownloadBytes, ct).ConfigureAwait(false) : null;
        }
        catch { return null; }
        finally { NetworkGate.Release(); }
    }

    // Reads the body but stops (null) the moment it passes the cap: a hostile server cannot make us buffer more.
    private static async Task<byte[]?> ReadCappedAsync(HttpResponseMessage response, long max, CancellationToken ct)
    {
        if (response.Content.Headers.ContentLength is long len && len > max) return null;
        await using var s = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var ms = new MemoryStream();
        var buffer = new byte[16 * 1024];
        int n;
        while ((n = await s.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
        {
            if (ms.Length + n > max) return null;
            ms.Write(buffer, 0, n);
        }
        return ms.ToArray();
    }
}
