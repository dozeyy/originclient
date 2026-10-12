namespace OriginLauncher.App.Core.Social;

/// <summary>
/// Made-up social data for the CI screen render (`--render-shots`) — the
/// only place the drawer can be seen full without a live service. Never used
/// in a normal launch.
/// </summary>
public static class SocialDemo
{
    public const string Me = "0000000000000000000000000000000a";
    public const string Steve = "0000000000000000000000000000000b";
    public const string Alex = "0000000000000000000000000000000c";
    public const string Notch = "0000000000000000000000000000000d";
    public const string Kai = "0000000000000000000000000000000e";
    public const string Rin = "0000000000000000000000000000000f";
    public const string Ojo = "00000000000000000000000000000010";

    public static SocialSnapshot Online(long nowMs)
    {
        long Ago(int minutes) => nowMs - minutes * 60_000L;
        ChatMessage Dm(string id, string from, string to, string text, int minutesAgo) =>
            new(id, from, to, text, Ago(minutesAgo));

        return new SocialSnapshot
        {
            Status = SocialStatus.Online,
            HasData = true,
            Me = new SocialMe(Me, "OriginPlayer", false),
            Friends =
            [
                new FriendInfo(Steve, "Steve", true, "game", "server", "hypixel.net", "1.21.1", nowMs),
                new FriendInfo(Alex, "Alex", true, "game", "singleplayer", null, "1.20.1", nowMs),
                new FriendInfo(Kai, "Kai_Builds", true, "launcher", "menu", null, "1.21.11", nowMs),
                new FriendInfo(Rin, "rinrin", true, "game", "server", "play.cubecraft.net", "1.8.9", nowMs),
                new FriendInfo(Notch, "Notch", false, null, null, null, null, Ago(180)),
            ],
            Incoming = [new FriendRequest(Ojo, "OjoTheCat", Ago(3))],
            Outgoing = [new FriendRequest("00000000000000000000000000000011", "dinnerbone", Ago(60))],
            Party = new PartyInfo("p1", Me,
            [
                new PartyMember(Me, "OriginPlayer", true),
                new PartyMember(Steve, "Steve", true),
                new PartyMember(Alex, "Alex", true),
            ]),
            PartyInvites = [new PartyInvite("p2", Kai, "Kai_Builds", Ago(1))],
            Conversations = new Dictionary<string, IReadOnlyList<ChatMessage>>
            {
                [Steve] =
                [
                    Dm("m1", Steve, Me, "you on tonight?", 42),
                    Dm("m2", Me, Steve, "yeah, after dinner", 40),
                    Dm("m3", Steve, Me, "bed wars? I'm on hypixel already", 2),
                    Dm("m4", Steve, Me, "hit join on my name in the launcher", 1),
                ],
            },
            PartyChat =
            [
                new PartyChatMessage("pc1", Alex, "Alex", "who's bringing the beds", Ago(6)),
                new PartyChatMessage("pc2", Me, "OriginPlayer", "warping you all in a sec", Ago(5)),
                new PartyChatMessage("pc3", Steve, "Steve", "ready", Ago(4)),
            ],
            Unread = new Dictionary<string, int> { [Steve] = 2 },
            UnreadParty = 1,
        };
    }

    public static IReadOnlyList<SocialToast> Toasts() =>
    [
        new SocialToast(SocialToastKind.Message, "Steve", "hit join on my name in the launcher", Uuid: Steve),
        new SocialToast(SocialToastKind.Warp, "Party warp", "Join Steve on hypixel.net",
            Uuid: Steve, Server: "hypixel.net", Version: "1.21.1"),
    ];
}
