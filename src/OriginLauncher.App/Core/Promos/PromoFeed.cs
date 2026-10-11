using System.Text.Json;

namespace OriginLauncher.App.Core.Promos;

// The home banner's remote feed: website/launcher/promos.json, edited by Will
// and served from the live site so the banner changes without a launcher
// release. Shape (schema 1):
//
//   {
//     "schema": 1,
//     "rotateSeconds": 9,
//     "promos": [
//       { "id": "discord", "kind": "discord" },
//       { "id": "site", "kind": "link", "label": "ORIGIN",
//         "title": "...", "subtitle": "...",
//         "imageUrl": "https://... (optional)", "clickUrl": "https://..." }
//     ],
//     "discord": { "inviteCode": "abc123", "refreshSeconds": 300 },
//     "network": { "enabled": false, "url": "https://originclient.org/ad/banner.html",
//                  "refreshSeconds": 300 }
//   }
//
// "kind": "discord" is a SLOT: its card is filled from Discord's live invite
// API (server name, icon, online/member counts) and silently left out of the
// rotation while the invite code is missing/invalid or Discord is unreachable.
// Unknown kinds and malformed promos are skipped (an older launcher keeps
// working on a newer feed); a different "schema" number is rejected whole, so
// a breaking change falls back to the cached copy or the built-in default.
//
// "network.enabled" swaps the house promos for an ad network's page hosted in
// a WebView2 (see UI/Controls/BannerAdHost.cs). Only https URLs are accepted
// anywhere, and text is length-capped so a typo can't wreck the layout.

public enum PromoKind
{
    Link,
    Discord,
}

public sealed record HousePromo(
    string Id,
    PromoKind Kind,
    string Label,
    string Title,
    string Subtitle,
    Uri? ImageUrl,
    Uri? ClickUrl);

public sealed record DiscordBlock(string? InviteCode, TimeSpan RefreshInterval)
{
    public bool HasInvite => InviteCode != null;
}

public sealed record NetworkBlock(bool Enabled, Uri? Url, TimeSpan RefreshInterval)
{
    /// <summary>On and pointing at a usable https page.</summary>
    public bool IsUsable => Enabled && Url != null;
}

public sealed record PromoFeed(
    TimeSpan RotateInterval,
    IReadOnlyList<HousePromo> Promos,
    DiscordBlock Discord,
    NetworkBlock Network);

public static class PromoFeedParser
{
    public const int SupportedSchema = 1;

    // Bounds that keep a hand-edited feed from hurting the launcher: rotation
    // never strobes, Discord is polled politely, an ad page is reloaded at most
    // every 30 s of on-screen time (the usual ad-network floor).
    public static readonly TimeSpan DefaultRotate = TimeSpan.FromSeconds(9);
    public static readonly TimeSpan MinRotate = TimeSpan.FromSeconds(4);
    public static readonly TimeSpan MaxRotate = TimeSpan.FromSeconds(120);
    public static readonly TimeSpan DefaultDiscordRefresh = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan MinDiscordRefresh = TimeSpan.FromMinutes(1);
    public static readonly TimeSpan MaxDiscordRefresh = TimeSpan.FromHours(1);
    public static readonly TimeSpan DefaultNetworkRefresh = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan MinNetworkRefresh = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan MaxNetworkRefresh = TimeSpan.FromHours(1);

    public const int MaxPromos = 12;
    public const int MaxLabelLength = 18;
    public const int MaxTitleLength = 64;
    public const int MaxSubtitleLength = 96;

    /// <summary>The parsed feed, or null when the text isn't a schema-1 feed at all.</summary>
    public static PromoFeed? TryParse(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return null;
        try
        {
            using var doc = JsonDocument.Parse(json, new JsonDocumentOptions
            {
                AllowTrailingCommas = true,
                CommentHandling = JsonCommentHandling.Skip,
            });
            return Parse(doc.RootElement);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static PromoFeed? Parse(JsonElement root)
    {
        if (root.ValueKind != JsonValueKind.Object) return null;
        if (!root.TryGetProperty("schema", out var schema)
            || schema.ValueKind != JsonValueKind.Number
            || !schema.TryGetInt32(out var schemaNumber)
            || schemaNumber != SupportedSchema)
            return null;

        var rotate = Seconds(root, "rotateSeconds", DefaultRotate, MinRotate, MaxRotate);

        var promos = new List<HousePromo>();
        var seenIds = new HashSet<string>(StringComparer.Ordinal);
        if (root.TryGetProperty("promos", out var list) && list.ValueKind == JsonValueKind.Array)
        {
            var index = 0;
            foreach (var item in list.EnumerateArray())
            {
                if (promos.Count >= MaxPromos) break;
                var promo = ParsePromo(item, index++);
                if (promo != null && seenIds.Add(promo.Id)) promos.Add(promo);
            }
        }

        var discord = new DiscordBlock(null, DefaultDiscordRefresh);
        if (root.TryGetProperty("discord", out var d) && d.ValueKind == JsonValueKind.Object)
        {
            discord = new DiscordBlock(
                DiscordInvite.NormalizeCode(Text(d, "inviteCode", 200)),
                Seconds(d, "refreshSeconds", DefaultDiscordRefresh, MinDiscordRefresh, MaxDiscordRefresh));
        }

        var network = new NetworkBlock(false, null, DefaultNetworkRefresh);
        if (root.TryGetProperty("network", out var n) && n.ValueKind == JsonValueKind.Object)
        {
            network = new NetworkBlock(
                n.TryGetProperty("enabled", out var on) && on.ValueKind == JsonValueKind.True,
                HttpsUrl(n, "url"),
                Seconds(n, "refreshSeconds", DefaultNetworkRefresh, MinNetworkRefresh, MaxNetworkRefresh));
        }

        return new PromoFeed(rotate, promos, discord, network);
    }

    private static HousePromo? ParsePromo(JsonElement item, int index)
    {
        if (item.ValueKind != JsonValueKind.Object) return null;

        var kind = (Text(item, "kind", 32) ?? "link").ToLowerInvariant() switch
        {
            "link" => PromoKind.Link,
            "discord" => PromoKind.Discord,
            _ => (PromoKind?)null,
        };
        if (kind == null) return null;

        var id = Text(item, "id", 64) ?? $"promo-{index}";

        if (kind == PromoKind.Discord)
        {
            // Title/subtitle/click come from the live invite at display time.
            return new HousePromo(id, PromoKind.Discord,
                (Text(item, "label", MaxLabelLength) ?? "DISCORD").ToUpperInvariant(), "", "", null, null);
        }

        var title = Text(item, "title", MaxTitleLength);
        if (title == null) return null;

        return new HousePromo(
            id,
            PromoKind.Link,
            (Text(item, "label", MaxLabelLength) ?? "ORIGIN").ToUpperInvariant(),
            title,
            Text(item, "subtitle", MaxSubtitleLength) ?? "",
            HttpsUrl(item, "imageUrl"),
            HttpsUrl(item, "clickUrl"));
    }

    private static string? Text(JsonElement obj, string name, int maxLength)
    {
        if (!obj.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.String) return null;
        var text = value.GetString()?.Trim();
        if (string.IsNullOrEmpty(text)) return null;
        // Single line only: the banner is one slim row.
        text = text.Replace('\r', ' ').Replace('\n', ' ');
        return text.Length <= maxLength ? text : text[..maxLength].TrimEnd();
    }

    private static TimeSpan Seconds(JsonElement obj, string name, TimeSpan fallback, TimeSpan min, TimeSpan max)
    {
        if (!obj.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.Number
            || !value.TryGetDouble(out var seconds) || double.IsNaN(seconds))
            return fallback;
        return TimeSpan.FromSeconds(Math.Clamp(seconds, min.TotalSeconds, max.TotalSeconds));
    }

    private static Uri? HttpsUrl(JsonElement obj, string name)
    {
        var text = Text(obj, name, 2048);
        return IsHttpsUrl(text, out var uri) ? uri : null;
    }

    public static bool IsHttpsUrl(string? text, out Uri? uri)
    {
        uri = null;
        if (text == null || !Uri.TryCreate(text, UriKind.Absolute, out var parsed)) return false;
        if (parsed.Scheme != Uri.UriSchemeHttps || string.IsNullOrEmpty(parsed.Host)) return false;
        uri = parsed;
        return true;
    }
}
