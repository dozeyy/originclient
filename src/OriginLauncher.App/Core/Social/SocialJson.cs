using System.IO;
using System.Globalization;
using System.Text;
using System.Text.Json;

namespace OriginLauncher.App.Core.Social;

/// <summary>
/// Request bodies and tolerant response parsing for the social service.
/// Parsing never throws: a malformed answer reads as null (the caller treats
/// it like an unreachable service) and a missing field reads as empty — ids
/// and times are accepted as JSON strings or numbers alike.
/// </summary>
public static class SocialJson
{
    // ---- request bodies ----------------------------------------------------

    /// <summary>Any small flat body: strings, bools, numbers, null.</summary>
    public static string Body(params (string Key, object? Value)[] fields)
    {
        var map = new Dictionary<string, object?>();
        foreach (var (key, value) in fields) map[key] = value;
        return JsonSerializer.Serialize(map);
    }

    /// <summary>
    /// The Identity v2 /v1/auth body (docs/SOCIAL.md): the signed proof, the
    /// certificate's public half with Mojang's signature on it, and the signed
    /// textures. Never the private key, never a Minecraft token.
    /// </summary>
    public static string AuthBody(string client, string uuid, string name, long atMs, string sig,
        PlayerCertificate certificate, SignedTextures textures)
    {
        using var buffer = new MemoryStream();
        using (var w = new Utf8JsonWriter(buffer))
        {
            w.WriteStartObject();
            w.WriteString("client", client);
            w.WriteString("uuid", Ids.Norm(uuid));
            w.WriteString("name", name);
            w.WriteNumber("at", atMs);
            w.WriteString("sig", sig);
            w.WriteString("publicKey", Convert.ToBase64String(certificate.PublicKeySpki));
            w.WriteNumber("keyExpiresAt", certificate.ExpiresAtMs);
            w.WriteString("keySignature", certificate.PublicKeySignatureV2);
            w.WriteStartObject("textures");
            w.WriteString("value", textures.Value);
            w.WriteString("signature", textures.Signature);
            w.WriteEndObject();
            w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    /// <summary>
    /// The launcher's /v1/sync body: presence "launcher" / "menu" with the
    /// selected version, never a partyState (that is the game's). The cursor is
    /// echoed back exactly as the service sent it (number or string).
    /// </summary>
    public static string SyncBody(string? cursorJson, string? version, string? friendsEtag = null)
    {
        using var buffer = new MemoryStream();
        using (var w = new Utf8JsonWriter(buffer))
        {
            w.WriteStartObject();
            w.WritePropertyName("since");
            if (IsJsonValue(cursorJson)) w.WriteRawValue(cursorJson!);
            else w.WriteNumberValue(0);
            w.WriteStartObject("presence");
            w.WriteString("client", "launcher");
            w.WriteString("activity", "menu");
            w.WriteNull("server");
            if (string.IsNullOrWhiteSpace(version)) w.WriteNull("version");
            else w.WriteString("version", version);
            w.WriteEndObject();
            w.WriteNull("partyState");
            if (!string.IsNullOrEmpty(friendsEtag)) w.WriteString("friendsEtag", friendsEtag);
            w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    private static bool IsJsonValue(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return false;
        try
        {
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.ValueKind is JsonValueKind.Number or JsonValueKind.String;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    // ---- responses -----------------------------------------------------------

    /// <summary>The player-facing sentence of an error answer ({ error: "..." }), if any.</summary>
    public static string? ErrorSentence(string? body)
    {
        var root = Parse(body);
        if (root is not { } r) return null;
        var text = Str(r, "error");
        return string.IsNullOrWhiteSpace(text) ? null : text.Trim();
    }

    /// <summary>/v1/auth 200 → (token, uuid, name); null when any part is missing.</summary>
    public static (string Token, string Uuid, string Name)? ParseAuth(string? body)
    {
        if (Parse(body) is not { } r) return null;
        var token = Str(r, "token");
        var uuid = Str(r, "uuid");
        if (string.IsNullOrWhiteSpace(token) || string.IsNullOrWhiteSpace(uuid)) return null;
        return (token, Ids.Norm(uuid), Str(r, "name") ?? "");
    }

    /// <summary>/v1/stats/online → the count; null for anything that isn't a sane number.</summary>
    public static int? ParseOnline(string? body)
    {
        if (Parse(body) is not { } r || !r.TryGetProperty("online", out var v)) return null;
        long n;
        if (v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var whole)) n = whole;
        else if (v.ValueKind == JsonValueKind.Number) n = (long)v.GetDouble();
        else if (v.ValueKind == JsonValueKind.String && long.TryParse(v.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var s)) n = s;
        else return null;
        return n is >= 0 and <= int.MaxValue ? (int)n : null;
    }

    public static SyncResult? ParseSync(string? body)
    {
        if (Parse(body) is not { } r) return null;

        SocialMe? me = null;
        if (Obj(r, "me") is { } m && Ids.Norm(Str(m, "uuid")) is { Length: > 0 } meId)
            me = new SocialMe(meId, Str(m, "name") ?? "", Bool(m, "appearOffline"));

        var friends = !Has(r, "friends") ? null : Arr(r, "friends")
            .Select(f => new FriendInfo(
                Ids.Norm(Str(f, "uuid")), Str(f, "name") ?? "?", Bool(f, "online"),
                Str(f, "client"), Str(f, "activity"), Str(f, "server"), Str(f, "version"), Long(f, "lastSeen")))
            .Where(f => f.Uuid.Length > 0)
            .ToList();

        List<FriendRequest>? Requests(JsonElement? parent, string name) =>
            parent is { } p
                ? Arr(p, name).Select(q => new FriendRequest(Ids.Norm(Str(q, "uuid")), Str(q, "name") ?? "?", Long(q, "at")))
                    .Where(q => q.Uuid.Length > 0).ToList()
                : null;
        var requests = Obj(r, "requests");
        var blocked = !Has(r, "blocked") ? null : Arr(r, "blocked")
            .Select(b => new BlockedPlayer(Ids.Norm(Str(b, "uuid")), Str(b, "name") ?? "?"))
            .Where(b => b.Uuid.Length > 0)
            .ToList();

        var messages = Arr(r, "messages")
            .Select(x => new ChatMessage(Str(x, "id") ?? "", Ids.Norm(Str(x, "from")), Ids.Norm(Str(x, "to")),
                Str(x, "text") ?? "", Long(x, "at")))
            .Where(x => x.Id.Length > 0)
            .ToList();

        PartyInfo? party = null;
        if (Obj(r, "party") is { } p && Str(p, "id") is { Length: > 0 } partyId)
        {
            var members = Arr(p, "members")
                .Select(x => new PartyMember(Ids.Norm(Str(x, "uuid")), Str(x, "name") ?? "?", Bool(x, "online")))
                .Where(x => x.Uuid.Length > 0)
                .ToList();
            party = new PartyInfo(partyId, Ids.Norm(Str(p, "leader")), members);
        }

        var partyMessages = Arr(r, "partyMessages")
            .Select(x => new PartyChatMessage(Str(x, "id") ?? "", Ids.Norm(Str(x, "from")), Str(x, "name") ?? "?",
                Str(x, "text") ?? "", Long(x, "at")))
            .Where(x => x.Id.Length > 0)
            .ToList();

        var invites = Arr(r, "partyInvites")
            .Select(x => new PartyInvite(Str(x, "partyId") ?? "", Ids.Norm(Str(x, "from")), Str(x, "name") ?? "?", Long(x, "at")))
            .Where(x => x.PartyId.Length > 0)
            .ToList();

        WarpRequest? warp = null;
        if (Obj(r, "warp") is { } w && Str(w, "server") is { Length: > 0 } warpServer)
            warp = new WarpRequest(warpServer, Str(w, "version"), Long(w, "at"), Ids.Norm(Str(w, "from")), Str(w, "name"));

        string? cursor = r.TryGetProperty("cursor", out var c) && c.ValueKind is JsonValueKind.Number or JsonValueKind.String
            ? c.GetRawText()
            : null;

        return new SyncResult(cursor, me, friends, Requests(requests, "incoming"), Requests(requests, "outgoing"),
            messages, party, partyMessages, invites, warp, blocked, Str(r, "friendsEtag"));
    }

    // ---- tolerant readers ------------------------------------------------------

    private static JsonElement? Parse(string? body)
    {
        if (string.IsNullOrWhiteSpace(body)) return null;
        try
        {
            using var doc = JsonDocument.Parse(body);
            return doc.RootElement.ValueKind == JsonValueKind.Object ? doc.RootElement.Clone() : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>Present and not null — an omitted list means "unchanged" (friendsEtag matched).</summary>
    private static bool Has(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && v.ValueKind != JsonValueKind.Null;

    private static JsonElement? Obj(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Object ? v : null;

    private static IEnumerable<JsonElement> Arr(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Array
            ? v.EnumerateArray().Where(e => e.ValueKind == JsonValueKind.Object).ToList()
            : [];

    private static string? Str(JsonElement o, string name)
    {
        if (!o.TryGetProperty(name, out var v)) return null;
        return v.ValueKind switch
        {
            JsonValueKind.String => v.GetString(),
            JsonValueKind.Number => v.GetRawText(),
            _ => null,
        };
    }

    private static long Long(JsonElement o, string name)
    {
        if (!o.TryGetProperty(name, out var v)) return 0;
        if (v.ValueKind == JsonValueKind.Number)
            return v.TryGetInt64(out var n) ? n : (long)v.GetDouble();
        if (v.ValueKind == JsonValueKind.String
            && long.TryParse(v.GetString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var s))
            return s;
        return 0;
    }

    private static bool Bool(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && (v.ValueKind == JsonValueKind.True
            || (v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var n) && n != 0));
}
