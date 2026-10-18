using System.Text.Json;
using OriginLauncher.App.Core.Social;

namespace OriginLauncher.App.Core.Promos;

/// <summary>
/// "Is this account an Origin+ member" for ONE job: the Origin+ perk "no ads in
/// the launcher" (the home banner never shows the ad network to a member;
/// promos and tips are unaffected). Asks the cosmetics service's public
/// player endpoint — GET {api}/v1/players/&lt;uuid-no-dashes&gt; answers
/// { ..., "plus": true|false } — the same Worker the mod and Friends use
/// (<see cref="SocialEndpoints.BaseUrl"/>). Nothing secret is sent: the uuid is
/// public and the request carries no token (<see cref="PromoHttp"/>).
///
/// Fail-soft by design: anything but a clear "plus": true — no account, a
/// pending / offline test account (no real uuid), offline, timeout, bad JSON —
/// is "not a member", i.e. exactly how the banner behaved before this perk.
/// Never throws for network trouble; safe to call off the UI thread.
///
/// Cached per account: a real answer for <see cref="FreshFor"/> (just under the
/// banner's 30-minute feed tick, so every tick re-checks), a failed lookup only
/// for <see cref="FailureRetry"/> so a blip doesn't hide a member's perk for
/// half an hour.
/// </summary>
public sealed class OriginPlusMembership
{
    public static readonly TimeSpan FreshFor = TimeSpan.FromMinutes(29);
    public static readonly TimeSpan FailureRetry = TimeSpan.FromMinutes(2);
    public static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(5);

    private readonly HttpTextGet _get;
    private readonly Uri _baseUrl;
    private readonly Func<DateTime> _utcNow;
    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _cache = new();

    private readonly record struct Entry(bool Member, bool LookupOk, DateTime At);

    public OriginPlusMembership(HttpTextGet get, Uri? baseUrl = null, Func<DateTime>? utcNow = null)
    {
        _get = get;
        _baseUrl = baseUrl ?? SocialEndpoints.BaseUrl;
        _utcNow = utcNow ?? (() => DateTime.UtcNow);
    }

    /// <summary>
    /// The account's Minecraft uuid as the service spells it (32 lowercase hex,
    /// no dashes), or null when it isn't one (null/empty, "pending-...").
    /// </summary>
    public static string? NormalizeUuid(string? accountId)
    {
        var id = Ids.Norm(accountId);
        if (id.Length != 32) return null;
        foreach (var c in id)
            if (!Uri.IsHexDigit(c)) return null;
        return id;
    }

    /// <summary>The player endpoint for a normalized uuid.</summary>
    public Uri PlayerUrl(string normalizedUuid) => SocialEndpoints.For(_baseUrl, "/v1/players/" + normalizedUuid);

    /// <summary>
    /// The "plus" field of the service's answer: true / false, or null when the
    /// body isn't a JSON object with a boolean "plus" (treated as a failed lookup).
    /// </summary>
    public static bool? ParsePlus(string? body)
    {
        if (string.IsNullOrWhiteSpace(body)) return null;
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind != JsonValueKind.Object
                || !doc.RootElement.TryGetProperty("plus", out var plus))
                return null;
            return plus.ValueKind switch
            {
                JsonValueKind.True => true,
                JsonValueKind.False => false,
                _ => null,
            };
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>
    /// The answer without asking the network: true when it is already known
    /// (an account with no real uuid is known-not-a-member; a cached entry that
    /// is still fresh). False means "a lookup is needed".
    /// </summary>
    public bool TryGetCached(string? accountId, out bool member)
    {
        var uuid = NormalizeUuid(accountId);
        if (uuid == null)
        {
            member = false;
            return true;
        }

        lock (_gate)
        {
            if (_cache.TryGetValue(uuid, out var entry)
                && _utcNow() - entry.At < (entry.LookupOk ? FreshFor : FailureRetry))
            {
                member = entry.Member;
                return true;
            }
        }
        member = false;
        return false;
    }

    /// <summary>Cached answer, else one lookup. True only for a clear "plus": true.</summary>
    public async Task<bool> IsMemberAsync(string? accountId, CancellationToken ct)
    {
        if (TryGetCached(accountId, out var cached)) return cached;
        var uuid = NormalizeUuid(accountId)!; // TryGetCached answers every non-uuid itself

        var member = false;
        var ok = false;
        try
        {
            var response = await _get(PlayerUrl(uuid), RequestTimeout, ct).ConfigureAwait(false);
            if (response.IsSuccess && ParsePlus(response.Body) is bool plus)
            {
                member = plus;
                ok = true;
            }
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // A fetch seam that threw is the same as no answer: not a member.
        }

        lock (_gate)
            _cache[uuid] = new Entry(member, ok, _utcNow());
        return member;
    }
}
