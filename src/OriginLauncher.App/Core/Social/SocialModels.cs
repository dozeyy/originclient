namespace OriginLauncher.App.Core.Social;

// The /v1/sync answer (docs/SOCIAL.md), as the launcher uses it. Every uuid is
// normalised to lowercase without dashes (Ids.Norm) so comparisons never miss.
// Times ("at", "lastSeen") are Unix milliseconds, 0 when the service sent none.

public sealed record SocialMe(string Uuid, string Name, bool AppearOffline);

public sealed record FriendInfo(
    string Uuid, string Name, bool Online,
    string? Client, string? Activity, string? Server, string? Version, long LastSeen)
{
    /// <summary>On a server in the game — the only state "Join" works from.</summary>
    public bool IsOnServer => Online && Activity == "server" && !string.IsNullOrWhiteSpace(Server);
}

public sealed record FriendRequest(string Uuid, string Name, long At);

public sealed record ChatMessage(string Id, string From, string To, string Text, long At);

public sealed record PartyMember(string Uuid, string Name, bool Online);

public sealed record PartyInfo(string Id, string Leader, IReadOnlyList<PartyMember> Members);

public sealed record PartyChatMessage(string Id, string From, string Name, string Text, long At);

public sealed record PartyInvite(string PartyId, string From, string Name, long At);

public sealed record WarpRequest(string Server, string? Version, long At, string From, string? Name = null);

public sealed record BlockedPlayer(string Uuid, string Name);

/// <summary>
/// One /v1/sync answer. Friends, requests and blocked are null when the
/// service OMITTED them because our friendsEtag still matched — keep the last
/// lists then. Everything else is always present (empty when missing).
/// </summary>
public sealed record SyncResult(
    string? CursorJson,
    SocialMe? Me,
    IReadOnlyList<FriendInfo>? Friends,
    IReadOnlyList<FriendRequest>? Incoming,
    IReadOnlyList<FriendRequest>? Outgoing,
    IReadOnlyList<ChatMessage> Messages,
    PartyInfo? Party,
    IReadOnlyList<PartyChatMessage> PartyMessages,
    IReadOnlyList<PartyInvite> PartyInvites,
    WarpRequest? Warp,
    IReadOnlyList<BlockedPlayer>? Blocked = null,
    string? FriendsEtag = null);

public static class Ids
{
    /// <summary>"0A1B-..." -> "0a1b...": the one uuid spelling every comparison uses.</summary>
    public static string Norm(string? uuid) =>
        (uuid ?? "").Replace("-", "").Trim().ToLowerInvariant();
}

/// <summary>A toast to show: a new DM, request, invite or warp — or a plain notice (e.g. why Join can't go ahead).</summary>
public enum SocialToastKind { Message, FriendRequest, PartyInvite, Warp, Notice }

public sealed record SocialToast(
    SocialToastKind Kind, string Title, string Body,
    string? Uuid = null, string? PartyId = null, string? Server = null, string? Version = null);

/// <summary>What a button press came back with: ok, or the sentence to show.</summary>
public sealed record SocialActionResult(bool Ok, string? Error)
{
    public static SocialActionResult Success { get; } = new(true, null);
    public static SocialActionResult Fail(string error) => new(false, error);
}
