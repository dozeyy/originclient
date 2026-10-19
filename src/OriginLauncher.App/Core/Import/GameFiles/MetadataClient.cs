using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Import.GameFiles;

/// <summary>A fetched metadata document and the address that actually answered.</summary>
public sealed record MetadataResponse(byte[] Body, string FinalUrl);

/// <summary>
/// The one network call game-file reuse makes: GET a small document. Injectable so tests use a recording fake.
/// An implementation must:
///  * never follow a redirect (a 3xx answer is "unavailable": a redirect could lead to a host nobody allowed);
///  * stream the body and stop reading as soon as it passes <c>maxBytes</c> (and refuse an announced
///    Content-Length over it), so a hostile server can't make the import buffer an unbounded body;
///  * report the address that actually answered as <see cref="MetadataResponse.FinalUrl"/>
///    (MetadataClient re-checks it against the allowlist);
///  * return null for anything that failed, never throw for a network error (cancellation may throw).
/// </summary>
public interface IMetadataHttp
{
    /// <summary>The body of <paramref name="url"/> and its final address, or null when it can't be fetched. Never reads more than <paramref name="maxBytes"/>.</summary>
    Task<MetadataResponse?> GetAsync(string url, long maxBytes, CancellationToken ct);
}

// D27: the import only ever fetches SMALL metadata documents — Mojang's version list, one version's
// json, its asset index, the Java runtime list and one component's file manifest — and never a
// game file. This class is the choke point that guarantees it: only Mojang's two metadata hosts over
// https, every body under 1 MB, and (where the parent document gives one) the body's SHA-1 must
// match, so a tampered or truncated document is rejected rather than trusted (the two root documents,
// the version list and the Java runtime list, have no parent to name a SHA-1: they rest on https to an
// allowed host, checked again on the address that answered). Anything else is
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
        MetadataResponse? response;
        try
        {
            response = await _http.GetAsync(url, MaxBytes, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception)
        {
            return null; // unreachable, a TLS error, a timeout: all mean "not available"
        }
        // Whatever answered must itself be an allowed address (the transport must not follow redirects; this
        // is checked here too, so the guarantee doesn't rest on the transport alone).
        if (response == null || !IsAllowed(response.FinalUrl)) return null;
        var body = response.Body;
        // Backstop: the transport stops reading at the cap; a body at or over it is never trusted.
        if (body == null || body.Length >= MaxBytes) return null;
        if (expectedSha1 != null && !Sha1Hex(body).Equals(expectedSha1, StringComparison.OrdinalIgnoreCase)) return null;
        return body;
    }

    public static string Sha1Hex(byte[] data) => Convert.ToHexString(SHA1.HashData(data)).ToLowerInvariant();
}
