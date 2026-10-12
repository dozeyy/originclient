namespace OriginLauncher.App.Core.Social;

public enum SocialStatus
{
    /// <summary>Not synced yet for this account.</summary>
    Starting,
    /// <summary>No Microsoft account selected (offline test mode included).</summary>
    NotSignedIn,
    /// <summary>Microsoft / Mojang / Origin refused the sign-in.</summary>
    SignInFailed,
    /// <summary>The service can't be reached (offline, not deployed, down). Retrying.</summary>
    Unreachable,
    Online,
}

/// <summary>
/// Everything the Friends drawer shows, as one immutable value. The service
/// swaps whole snapshots, so the UI thread can read one without locks.
/// Conversations are keyed by the other player's uuid; party chat is separate.
/// </summary>
public sealed record SocialSnapshot
{
    /// <summary>The conversation key the party chat pane uses (never a uuid).</summary>
    public const string PartyKey = "party";

    public SocialStatus Status { get; init; } = SocialStatus.Starting;
    public string? StatusMessage { get; init; }

    /// <summary>At least one sync succeeded for this account (lists are real, maybe stale).</summary>
    public bool HasData { get; init; }

    public SocialMe? Me { get; init; }
    public IReadOnlyList<FriendInfo> Friends { get; init; } = [];
    public IReadOnlyList<FriendRequest> Incoming { get; init; } = [];
    public IReadOnlyList<FriendRequest> Outgoing { get; init; } = [];
    public IReadOnlyList<BlockedPlayer> Blocked { get; init; } = [];

    /// <summary>Sent back as friendsEtag: while it matches, the service leaves the lists out.</summary>
    public string? FriendsEtag { get; init; }
    public PartyInfo? Party { get; init; }
    public IReadOnlyList<PartyInvite> PartyInvites { get; init; } = [];
    public WarpRequest? Warp { get; init; }
    public IReadOnlyDictionary<string, IReadOnlyList<ChatMessage>> Conversations { get; init; } =
        new Dictionary<string, IReadOnlyList<ChatMessage>>();
    public IReadOnlyList<PartyChatMessage> PartyChat { get; init; } = [];
    public IReadOnlyDictionary<string, int> Unread { get; init; } = new Dictionary<string, int>();
    public int UnreadParty { get; init; }

    public bool IsPartyLeader => Party != null && Me != null && Party.Leader == Me.Uuid;

    /// <summary>What lights the dot on the Friends button.</summary>
    public int AttentionCount => Incoming.Count + PartyInvites.Count + Unread.Values.Sum() + UnreadParty;

    public FriendInfo? Friend(string uuid) => Friends.FirstOrDefault(f => f.Uuid == uuid);

    public IReadOnlyList<ChatMessage> ConversationWith(string uuid) =>
        Conversations.TryGetValue(uuid, out var lines) ? lines : [];

    /// <summary>A player's name from anything we know about them.</summary>
    public string NameOf(string uuid) =>
        Friend(uuid)?.Name
        ?? Party?.Members.FirstOrDefault(m => m.Uuid == uuid)?.Name
        ?? Incoming.Concat(Outgoing).FirstOrDefault(r => r.Uuid == uuid)?.Name
        ?? (Me?.Uuid == uuid ? Me.Name : null)
        ?? "Someone";
}

/// <summary>A merged sync: the new snapshot, toasts to show, and whether anything live arrived.</summary>
public sealed record SocialMergeOutcome(SocialSnapshot Snapshot, IReadOnlyList<SocialToast> Toasts, bool LiveEvent);

/// <summary>
/// Folds one /v1/sync answer into the running snapshot. Messages arrive as
/// "newer than the cursor", so they're appended (deduplicated by id, oldest
/// first, last 200 per conversation kept); everything else is replaced.
///
/// The first sync after sign-in brings up to 30 days of history: none of it
/// is toasted or counted unread (the service has no read markers), only
/// what arrives after it. A party warp is the exception — it's only ever two
/// minutes old, so it is shown even on the first sync.
/// </summary>
public static class SocialMerge
{
    public const int MaxLinesPerConversation = 200;

    public static SocialMergeOutcome Apply(
        SocialSnapshot before, SyncResult sync, bool firstSync, string? openConversation, bool drawerOpen)
    {
        var me = sync.Me?.Uuid ?? before.Me?.Uuid ?? "";
        var toasts = new List<SocialToast>();
        var live = false;

        // Direct messages.
        var conversations = before.Conversations.ToDictionary(kv => kv.Key, kv => kv.Value.ToList());
        var unread = new Dictionary<string, int>(before.Unread);
        var known = new HashSet<string>(before.Conversations.Values.SelectMany(c => c).Select(m => m.Id));
        foreach (var message in sync.Messages.OrderBy(m => m.At))
        {
            if (!known.Add(message.Id)) continue;
            var key = message.From == me ? message.To : message.From;
            if (key.Length == 0) continue;
            if (!conversations.TryGetValue(key, out var lines)) conversations[key] = lines = new List<ChatMessage>();
            lines.Add(message);

            if (firstSync || message.From == me) continue;
            live = true;
            var reading = drawerOpen && openConversation == key;
            if (reading) continue;
            unread[key] = unread.GetValueOrDefault(key) + 1;
            toasts.Add(new SocialToast(SocialToastKind.Message, NameIn(sync, before, key), message.Text, Uuid: key));
        }

        // Party chat: reset when the party changes or ends.
        var sameParty = sync.Party != null && before.Party?.Id == sync.Party.Id;
        var partyChat = sameParty ? before.PartyChat.ToList() : new List<PartyChatMessage>();
        var unreadParty = sameParty ? before.UnreadParty : 0;
        if (sync.Party != null)
        {
            var knownParty = new HashSet<string>(partyChat.Select(m => m.Id));
            foreach (var message in sync.PartyMessages.OrderBy(m => m.At))
            {
                if (!knownParty.Add(message.Id)) continue;
                partyChat.Add(message);
                if (firstSync || message.From == me) continue;
                live = true;
                if (!(drawerOpen && openConversation == SocialSnapshot.PartyKey)) unreadParty++;
            }
        }

        if (!firstSync)
        {
            foreach (var request in (sync.Incoming ?? before.Incoming).Where(r => before.Incoming.All(b => b.Uuid != r.Uuid)))
            {
                live = true;
                toasts.Add(new SocialToast(SocialToastKind.FriendRequest, "Friend request",
                    $"{request.Name} wants to be friends", Uuid: request.Uuid));
            }
            foreach (var invite in sync.PartyInvites.Where(i => before.PartyInvites.All(b => b.PartyId != i.PartyId)))
            {
                live = true;
                toasts.Add(new SocialToast(SocialToastKind.PartyInvite, "Party invite",
                    $"{invite.Name} invited you to their party", Uuid: invite.From, PartyId: invite.PartyId));
            }
        }

        if (sync.Warp is { } warp && warp.From != me && (before.Warp == null || before.Warp.At != warp.At || before.Warp.From != warp.From))
        {
            live = true;
            toasts.Add(new SocialToast(SocialToastKind.Warp, "Party warp",
                $"Join {warp.Name ?? NameIn(sync, before, warp.From)} on {SocialText.DisplayServer(warp.Server)}",
                Uuid: warp.From, Server: warp.Server, Version: warp.Version));
        }

        var snapshot = before with
        {
            Me = sync.Me ?? before.Me,
            // Omitted lists (friendsEtag matched) keep what we had.
            Friends = sync.Friends == null
                ? before.Friends
                : sync.Friends.OrderByDescending(f => f.Online).ThenBy(f => f.Name, StringComparer.OrdinalIgnoreCase).ToList(),
            Incoming = sync.Incoming ?? before.Incoming,
            Outgoing = sync.Outgoing ?? before.Outgoing,
            Blocked = sync.Blocked ?? before.Blocked,
            FriendsEtag = sync.FriendsEtag ?? before.FriendsEtag,
            Party = sync.Party,
            PartyInvites = sync.PartyInvites,
            Warp = sync.Warp,
            Conversations = conversations.ToDictionary(
                kv => kv.Key,
                kv => (IReadOnlyList<ChatMessage>)kv.Value.OrderBy(m => m.At).TakeLast(MaxLinesPerConversation).ToList()),
            PartyChat = partyChat.OrderBy(m => m.At).TakeLast(MaxLinesPerConversation).ToList(),
            Unread = unread,
            UnreadParty = unreadParty,
        };
        return new SocialMergeOutcome(snapshot, toasts, live);
    }

    /// <summary>Opening a conversation (or the party chat) reads it.</summary>
    public static SocialSnapshot MarkRead(SocialSnapshot snapshot, string? key)
    {
        if (key == null) return snapshot;
        if (key == SocialSnapshot.PartyKey)
            return snapshot.UnreadParty == 0 ? snapshot : snapshot with { UnreadParty = 0 };
        if (!snapshot.Unread.ContainsKey(key)) return snapshot;
        var unread = new Dictionary<string, int>(snapshot.Unread);
        unread.Remove(key);
        return snapshot with { Unread = unread };
    }

    private static string NameIn(SyncResult sync, SocialSnapshot before, string uuid) =>
        sync.Friends?.FirstOrDefault(f => f.Uuid == uuid)?.Name
        ?? sync.Party?.Members.FirstOrDefault(m => m.Uuid == uuid)?.Name
        ?? before.NameOf(uuid);
}
