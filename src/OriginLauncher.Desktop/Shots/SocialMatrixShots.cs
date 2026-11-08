using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Shots;

// The Friends drawer in every state, the Linux twin of the Windows `--social` set (UI/SocialShots.cs) and the Mac's
// (src/OriginLauncher.Mac/UI/Shots/SocialShots.cs), under the SAME file names, so CI's "Windows shots with no Linux
// twin" list can compare them (PAR3-8): `NN-scene-WxH` for every scene at 1180x740, 980x600 and 1600x1000, then the
// scenes with the most icons and Home at 100 / 125 / 150 % display scale (`NN-scene-WxH-PCTpct`, `00-home-...`).
// The numbers are the Windows list's positions, so a scene Linux can't stage yet (row hover/focus, the row menus,
// the scrolled-to-end list, the near-limit chat box, the in-drawer confirm) leaves a gap in the numbering and shows
// in the parity list instead of renumbering the rest. Data is the Windows/Mac builders' own (no network: ShowDemo).
public static class SocialMatrixShots
{
    private static readonly (int Width, int Height)[] Sizes = [(1180, 740), (980, 600), (1600, 1000)];
    private static readonly string[] Scaled = ["will-one-offline", "list-leader", "list-long", "list-add-open", "chat-friend-online", "toasts-drawer-open"];

    private sealed record Scene(string Name, Action<MainWindow>? Setup);

    [ShotSet]
    public static IEnumerable<Shot> All()
    {
        var scenes = Scenes(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        foreach (var (w, h) in Sizes)
            for (var i = 0; i < scenes.Count; i++)
                if (scenes[i].Setup is { } setup)
                    yield return new Shot($"{i + 1:00}-{scenes[i].Name}-{w}x{h}", setup, w, h, Seed: Signed);
        foreach (var percent in new[] { 100, 125, 150 })
            foreach (var (w, h) in Sizes)
            {
                for (var i = 0; i < scenes.Count; i++)
                    if (Scaled.Contains(scenes[i].Name) && scenes[i].Setup is { } setup)
                        yield return new Shot($"{i + 1:00}-{scenes[i].Name}-{w}x{h}-{percent}pct", setup, w, h, Seed: Signed, Scale: percent / 100.0);
                yield return new Shot($"00-home-{w}x{h}-{percent}pct", _ => { }, w, h, Seed: Signed, Scale: percent / 100.0);
            }
    }

    private static void Signed() => Demo.SignIn();

    private static void Show(MainWindow w, SocialSnapshot s)
    {
        SocialHub.Service.ShowDemo(s);
        FriendsDrawer.Wire(w);
    }

    private static FriendsDrawer List(MainWindow w, SocialSnapshot s)
    {
        Show(w, s);
        return FriendsDrawer.OpenOn(w);
    }

    private static void Chat(MainWindow w, SocialSnapshot s, string key)
    {
        Show(w, s);
        FriendsDrawer.OpenOn(w, key);
    }

    private static void Toasts(MainWindow w, IEnumerable<SocialToast> toasts) => SocialToasts.Of(w).ShowAll(toasts);

    // The Windows list, in its order (the numbers are positions in it). Null = not staged on Linux yet.
    private static List<Scene> Scenes(long now) =>
    [
        new("will-one-offline", w => List(w, WillsDrawer(now)).ShowAdd("")),
        new("list-leader", w => List(w, SocialDemo.Online(now))),
        new("list-member", w => List(w, AsMember(now))),
        new("list-no-party", w => List(w, NoParty(now))),
        new("list-empty", w => List(w, Empty())),
        new("list-long", w => List(w, LongList(now))),
        new("list-warp-appear-offline", w => List(w, WithWarp(now))),
        new("list-add-open", w =>
        {
            var d = List(w, SocialDemo.Online(now));
            d.ShowAdd("");
            d.ShowNotice("Friend request sent to dinnerbone.", error: false);
        }),
        new("list-add-error", w =>
        {
            var d = List(w, SocialDemo.Online(now));
            d.ShowAdd("");
            d.ShowNotice("That isn't a Minecraft username.", error: true);
        }),
        new("row-hover", null),
        new("row-focus", null),
        new("list-notice", w => List(w, SocialDemo.Online(now)).ShowNotice("Can't join: Steve is not on a server.", error: true)),
        new("list-long-scrolled-to-end", null),
        new("menu-friend-list", null),
        new("menu-party-member", null),
        new("menu-friend-chat", null),
        new("chat-near-limit", null),
        new("list-stale", w => List(w, SocialDemo.Online(now) with { Status = SocialStatus.Unreachable })),
        new("chat-friend-online", w => Chat(w, SocialDemo.Online(now), SocialDemo.Steve)),
        new("chat-friend-long", w => Chat(w, LongChat(now), SocialDemo.Steve)),
        new("chat-friend-offline-empty", w => Chat(w, SocialDemo.Online(now), SocialDemo.Notch)),
        new("chat-party", w => Chat(w, SocialDemo.Online(now), SocialSnapshot.PartyKey)),
        new("chat-party-alone", w => Chat(w, SocialDemo.Online(now) with
        {
            Party = new PartyInfo("p1", SocialDemo.Me, [new PartyMember(SocialDemo.Me, "OriginPlayer", true)]),
            PartyChat = [],
            UnreadParty = 0,
        }, SocialSnapshot.PartyKey)),
        new("confirm", null),
        new("state-signed-out", w => List(w, new SocialSnapshot
        {
            Status = SocialStatus.NotSignedIn, StatusMessage = "Sign in with Microsoft to use friends.",
        })),
        new("state-connecting", w => List(w, new SocialSnapshot { Status = SocialStatus.Starting })),
        new("state-unreachable", w => List(w, new SocialSnapshot
        {
            Status = SocialStatus.Unreachable,
            StatusMessage = "Origin can't reach its friends service. It keeps trying in the background.",
        })),
        new("state-sign-in-failed", w => List(w, new SocialSnapshot
        {
            Status = SocialStatus.SignInFailed,
            StatusMessage = "Microsoft said this Minecraft account has no profile.",
        })),
        new("toasts", w =>
        {
            Show(w, SocialDemo.Online(now));
            Toasts(w, SocialDemo.Toasts().Concat(
            [
                new SocialToast(SocialToastKind.FriendRequest, "Friend request", "OjoTheCat wants to be friends", Uuid: SocialDemo.Ojo),
                new SocialToast(SocialToastKind.PartyInvite, "Party invite", "Kai_Builds invited you to their party", Uuid: SocialDemo.Kai, PartyId: "p2"),
            ]));
        }),
        new("toasts-drawer-open", w =>
        {
            List(w, SocialDemo.Online(now));
            Toasts(w, SocialDemo.Toasts());
        }),
    ];

    // ---- data (the Windows SocialShots builders, as the Mac copies them) ----

    private static SocialSnapshot Empty() => new()
    {
        Status = SocialStatus.Online,
        HasData = true,
        Me = new SocialMe(SocialDemo.Me, "OriginPlayer", false),
    };

    private static SocialSnapshot LongList(long now)
    {
        string[] names =
        [
            "Steve", "Alex", "Kai_Builds", "rinrin", "Notch", "Dream_SMP_fan_1", "xX_Slayer_Xx", "Technoblade_RIP",
            "ender_pearl", "CreeperAwMan", "redstone_guy", "Herobrine", "jeb_", "Dinnerbone", "Grumm", "MumboJumbo",
            "iskall85", "Xisuma", "Etho", "BdoubleO100", "Grian", "Scar", "Pearl", "Impulse", "Tango", "Zedaph",
            "Keralis", "Cubfan135", "Docm77", "Welsknight",
        ];
        string?[] servers = ["hypixel.net", "play.cubecraft.net", "mc.very-long-server-name-network.example.com", null, "mineplex.com"];
        var friends = new List<FriendInfo>();
        for (var i = 0; i < names.Length; i++)
        {
            var uuid = (0x100 + i).ToString("x32");
            var online = i % 3 != 2;
            var server = servers[i % servers.Length];
            friends.Add(online
                ? new FriendInfo(uuid, names[i], true, i % 4 == 3 ? "launcher" : "game",
                    server != null ? "server" : "singleplayer", server, i % 2 == 0 ? "1.21.1" : "1.20.1", now)
                : new FriendInfo(uuid, names[i], false, null, null, null, null, now - (i + 1) * 3_600_000L));
        }
        return SocialDemo.Online(now) with
        {
            Friends = friends.OrderByDescending(f => f.Online).ThenBy(f => f.Name, StringComparer.OrdinalIgnoreCase).ToList(),
            Incoming =
            [
                new FriendRequest("a1".PadLeft(32, '0'), "OjoTheCat", now),
                new FriendRequest("a2".PadLeft(32, '0'), "A_Very_Long_Name_", now),
            ],
        };
    }

    private static SocialSnapshot WillsDrawer(long now) => new()
    {
        Status = SocialStatus.Online,
        HasData = true,
        Me = new SocialMe(SocialDemo.Me, "OriginPlayer", false),
        Friends = [new FriendInfo("b1".PadLeft(32, '0'), "Yozeyy", false, null, null, null, null, now - 11 * 3_600_000L)],
    };

    private static SocialSnapshot AsMember(long now) => SocialDemo.Online(now) with
    {
        Party = new PartyInfo("p1", SocialDemo.Steve,
        [
            new PartyMember(SocialDemo.Steve, "Steve", true),
            new PartyMember(SocialDemo.Me, "OriginPlayer", true),
            new PartyMember(SocialDemo.Alex, "Alex", true),
            new PartyMember(SocialDemo.Notch, "Notch", false),
        ]),
        PartyInvites = [],
        Incoming = [],
        Outgoing = [],
    };

    private static SocialSnapshot NoParty(long now) => SocialDemo.Online(now) with
    {
        Party = null,
        PartyInvites = [],
        PartyChat = [],
        UnreadParty = 0,
        Incoming = [],
        Outgoing = [],
        Unread = new Dictionary<string, int>(),
    };

    private static SocialSnapshot WithWarp(long now) => SocialDemo.Online(now) with
    {
        Warp = new WarpRequest("hypixel.net", "1.21.1", now, SocialDemo.Steve, "Steve"),
        Me = new SocialMe(SocialDemo.Me, "OriginPlayer", true),
    };

    private static SocialSnapshot LongChat(long now)
    {
        long Ago(int minutes) => now - minutes * 60_000L;
        ChatMessage Dm(string id, string from, string to, string text, int minutesAgo) => new(id, from, to, text, Ago(minutesAgo));
        const string me = SocialDemo.Me, steve = SocialDemo.Steve;
        var lines = new List<ChatMessage>
        {
            Dm("c1", steve, me, "yo, did you see the new update?", 3200),
            Dm("c2", me, steve, "not yet, whats in it", 3190),
            Dm("c3", steve, me, "new shaders and a faster launcher and the new capes are in the store", 3188),
            Dm("c4", steve, me, "also the map thing", 3187),
            Dm("c5", me, steve, "nice", 300),
            Dm("c6", steve, me, "you on tonight?", 42),
            Dm("c7", steve, me, "bed wars? I'm on hypixel already. bring the whole party, we need four for the new map and the queue is empty right now so we should get in fast, then we can try the new mode after", 41),
            Dm("c8", me, steve, "yeah, after dinner", 40),
            Dm("c9", me, steve, "give me twenty minutes", 40),
            Dm("c10", me, steve, "https://example.com/a/really/long/link/with/no/spaces/that/should/still/wrap/inside/the/bubble/without/clipping", 39),
            Dm("c11", steve, me, "ok", 3),
            Dm("c12", steve, me, "hit join on my name in the launcher", 1),
        };
        return SocialDemo.Online(now) with
        {
            Conversations = new Dictionary<string, IReadOnlyList<ChatMessage>> { [steve] = lines },
            Unread = new Dictionary<string, int>(),
            UnreadParty = 0,
        };
    }
}
