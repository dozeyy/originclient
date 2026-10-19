using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Import.GameFiles;

/// <summary>The one network call game-file reuse makes: GET a small document. Injectable so tests use a recording fake.</summary>
public interface IMetadataHttp
{
    /// <summary>The body of <paramref name="url"/>, or null when it can't be fetched. Must not read more than <paramref name="maxBytes"/>.</summary>
    Task<byte[]?> GetAsync(string url, long maxBytes, CancellationToken ct);
}

// D27: the import only ever fetches SMALL metadata documents — Mojang's version list, one version's
// json, its asset index, the Java runtime list and one component's file manifest — and never a
// game file. This class is the choke point that guarantees it: only Mojang's two metadata hosts over
// https, every body under 1 MB, and (where the parent document gives one) the body's SHA-1 must
// match, so a tampered or truncated document is rejected rather than trusted. Anything else is
// "unavailable" and reuse of what depends on it is skipped.
public sealed class MetadataClient
{
    /// <summary>Every document must be smaller than this.</summary>
    public const long MaxBytes = 1024 * 1024;

    public const string VersionManifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    // The list of Java runtimes per platform and component (each entry names its manifest + SHA-1).
    public const string JavaRuntimeListUrl =
        "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private static readonly string[] AllowedHosts = { "launchermeta.mojang.com", "piston-meta.mojang.com" };

    private readonly IMetadataHttp _http;

    public MetadataClient(IMetadataHttp http) => _http = http;

    public static bool IsAllowed(string? url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var uri)
        && uri.Scheme == Uri.UriSchemeHttps
        && uri.IsDefaultPort
        && AllowedHosts.Contains(uri.Host, StringComparer.OrdinalIgnoreCase);

    /// <summary>The document, or null when the address isn't allowed, the fetch failed, it is too big, or its SHA-1 differs from <paramref name="expectedSha1"/>.</summary>
    public async Task<byte[]?> GetAsync(string url, string? expectedSha1, CancellationToken ct)
    {
        if (!IsAllowed(url)) return null;
        byte[]? body;
        try
        {
            body = await _http.GetAsync(url, MaxBytes, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception)
        {
            return null; // unreachable, a TLS error, a timeout: all mean "not available"
        }
        if (body == null || body.Length >= MaxBytes) return null;
        if (expectedSha1 != null && !Sha1Hex(body).Equals(expectedSha1, StringComparison.OrdinalIgnoreCase)) return null;
        return body;
    }

    public static string Sha1Hex(byte[] data) => Convert.ToHexString(SHA1.HashData(data)).ToLowerInvariant();
}
