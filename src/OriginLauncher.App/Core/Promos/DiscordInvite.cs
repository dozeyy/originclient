using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Promos;

/// <summary>Live facts about the Origin Discord server, from its public invite.</summary>
public sealed record DiscordServerInfo(string Name, Uri? IconUrl, int? Online, int? Members);

public enum DiscordLookupStatus
{
    /// <summary>Server info fetched.</summary>
    Ok,
    /// <summary>Discord says the invite doesn't exist / expired (404) or isn't a server invite — hide the card until the feed changes the code.</summary>
    InvalidInvite,
    /// <summary>Offline, rate-limited, Discord down: keep whatever was shown, retry next interval.</summary>
    Unavailable,
}

public sealed record DiscordLookup(DiscordLookupStatus Status, DiscordServerInfo? Info);

/// <summary>
/// Discord's public, unauthenticated invite endpoint:
/// GET https://discord.com/api/v10/invites/{code}?with_counts=true
/// → guild.name, guild.icon (→ cdn.discordapp.com/icons/{guild_id}/{icon}.png),
/// approximate_presence_count, approximate_member_count.
/// </summary>
public static class DiscordInvite
{
    public static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(6);

    // Invite and vanity codes are letters, digits and dashes. Anything else —
    // including the feed's "REPLACE_ME" placeholder (underscore) — is treated
    // as "no invite yet": the card is hidden and Discord is never called.
    private static readonly Regex CodePattern = new("^[A-Za-z0-9-]{2,32}$", RegexOptions.CultureInvariant);
    private static readonly Regex SnowflakePattern = new("^[0-9]{5,25}$", RegexOptions.CultureInvariant);
    private static readonly Regex IconHashPattern = new("^[A-Za-z0-9_]{1,64}$", RegexOptions.CultureInvariant);

    /// <summary>
    /// The bare invite code, or null when empty/invalid. Also accepts a pasted
    /// invite link (discord.gg/abc, https://discord.com/invite/abc) so Will can
    /// drop in whatever Discord gave him.
    /// </summary>
    public static string? NormalizeCode(string? raw)
    {
        var text = raw?.Trim();
        if (string.IsNullOrEmpty(text)) return null;

        foreach (var prefix in new[] { "https://", "http://" })
            if (text.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) text = text[prefix.Length..];
        if (text.StartsWith("www.", StringComparison.OrdinalIgnoreCase)) text = text[4..];
        foreach (var host in new[] { "discord.gg/", "discord.com/invite/", "discordapp.com/invite/" })
            if (text.StartsWith(host, StringComparison.OrdinalIgnoreCase)) { text = text[host.Length..]; break; }
        text = text.Split('?', '#')[0].TrimEnd('/');

        return CodePattern.IsMatch(text) ? text : null;
    }

    public static Uri ApiUrl(string code) =>
        new($"https://discord.com/api/v10/invites/{Uri.EscapeDataString(code)}?with_counts=true");

    public static Uri JoinUrl(string code) => new($"https://discord.gg/{Uri.EscapeDataString(code)}");

    /// <summary>Server info from the invite JSON, or null when it isn't a server invite.</summary>
    public static DiscordServerInfo? ParseInvite(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return null;
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object
                || !root.TryGetProperty("guild", out var guild) || guild.ValueKind != JsonValueKind.Object)
                return null;

            var name = guild.TryGetProperty("name", out var n) && n.ValueKind == JsonValueKind.String
                ? n.GetString()?.Trim() : null;
            if (string.IsNullOrEmpty(name)) return null;
            if (name.Length > PromoFeedParser.MaxTitleLength) name = name[..PromoFeedParser.MaxTitleLength];

            var id = guild.TryGetProperty("id", out var i) && i.ValueKind == JsonValueKind.String ? i.GetString() : null;
            var icon = guild.TryGetProperty("icon", out var ic) && ic.ValueKind == JsonValueKind.String ? ic.GetString() : null;
            Uri? iconUrl = null;
            if (id != null && icon != null && SnowflakePattern.IsMatch(id) && IconHashPattern.IsMatch(icon))
                iconUrl = new Uri($"https://cdn.discordapp.com/icons/{id}/{icon}.png?size=128");

            return new DiscordServerInfo(name, iconUrl,
                Count(root, "approximate_presence_count"),
                Count(root, "approximate_member_count"));
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static async Task<DiscordLookup> LookupAsync(HttpTextGet get, string code, CancellationToken ct)
    {
        var result = await get(ApiUrl(code), RequestTimeout, ct).ConfigureAwait(false);
        if (result.StatusCode == 404) return new DiscordLookup(DiscordLookupStatus.InvalidInvite, null);
        if (!result.IsSuccess) return new DiscordLookup(DiscordLookupStatus.Unavailable, null);

        var info = ParseInvite(result.Body);
        return info == null
            ? new DiscordLookup(DiscordLookupStatus.InvalidInvite, null)
            : new DiscordLookup(DiscordLookupStatus.Ok, info);
    }

    /// <summary>"1,234 online · 5,678 members" (either half dropped when Discord omits it).</summary>
    public static string DescribeCounts(DiscordServerInfo info)
    {
        var parts = new List<string>(2);
        if (info.Online is int online) parts.Add($"{FormatCount(online)} online");
        if (info.Members is int members) parts.Add($"{FormatCount(members)} members");
        return parts.Count == 0 ? "Join the community" : string.Join(" - ", parts);
    }

    /// <summary>Compact counts: 987 → "987", 12,345 → "12.3K", 1,200,000 → "1.2M".</summary>
    public static string FormatCount(int value)
    {
        var inv = CultureInfo.InvariantCulture;
        if (value < 10_000) return value.ToString("N0", inv);
        // Floor to one decimal so 999,999 reads "999.9K", never "1000K".
        if (value < 1_000_000) return (Math.Floor(value / 100d) / 10).ToString("0.#", inv) + "K";
        return (Math.Floor(value / 100_000d) / 10).ToString("0.#", inv) + "M";
    }

    private static int? Count(JsonElement root, string name) =>
        root.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number
        && v.TryGetInt32(out var count) && count >= 0
            ? count
            : null;
}
