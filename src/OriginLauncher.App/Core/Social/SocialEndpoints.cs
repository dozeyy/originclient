namespace OriginLauncher.App.Core.Social;

/// <summary>
/// Where Origin social lives (docs/SOCIAL.md). The same Worker as the
/// cosmetics store; the game resolves the same base URL. A developer can point
/// the launcher at a local `wrangler dev` with ORIGIN_COSMETICS_API.
/// </summary>
public static class SocialEndpoints
{
    public const string DefaultBaseUrl = "https://origin-cosmetics.will-f11.workers.dev";

    public static Uri BaseUrl { get; } = Resolve(Environment.GetEnvironmentVariable("ORIGIN_COSMETICS_API"));

    /// <summary>Mojang's "I'm joining this server" call — the identity proof (no token reaches Origin).</summary>
    public static readonly Uri MojangJoin = new("https://sessionserver.mojang.com/session/minecraft/join");

    public static Uri Resolve(string? overrideUrl)
    {
        var trimmed = overrideUrl?.Trim().TrimEnd('/');
        return Uri.TryCreate(trimmed, UriKind.Absolute, out var url) && (url.Scheme == Uri.UriSchemeHttps || url.Scheme == Uri.UriSchemeHttp)
            ? url
            : new Uri(DefaultBaseUrl);
    }

    /// <summary>"/v1/sync" against a base URL, whether or not the base ends in a slash.</summary>
    public static Uri For(Uri baseUrl, string path) => new(baseUrl.ToString().TrimEnd('/') + path);
}
