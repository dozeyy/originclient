using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Social;
using Xunit;

namespace OriginLauncher.Tests;

public class SyncScheduleTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 12, 12, 0, 0, TimeSpan.Zero);

    private static TimeSpan Next(bool drawer = false, bool party = false, TimeSpan? liveAgo = null, TimeSpan? inactiveFor = null) =>
        SyncSchedule.NextDelay(new SyncInputs(Now, drawer, party,
            liveAgo is { } l ? Now - l : null, inactiveFor is { } i ? Now - i : null));

    [Fact]
    public void IdleIsThirtySeconds() => Assert.Equal(TimeSpan.FromSeconds(30), Next());

    [Fact]
    public void DrawerOpenIsFourSecondsAPartyAloneTwenty()
    {
        Assert.Equal(TimeSpan.FromSeconds(4), Next(drawer: true));
        Assert.Equal(TimeSpan.FromSeconds(4), Next(drawer: true, party: true));
        Assert.Equal(TimeSpan.FromSeconds(20), Next(party: true));
        Assert.Equal(TimeSpan.FromSeconds(4), Next(party: true, liveAgo: TimeSpan.FromSeconds(30)));
    }

    [Fact]
    public void ARecentDmKeepsItFastForTwoMinutes()
    {
        Assert.Equal(TimeSpan.FromSeconds(4), Next(liveAgo: TimeSpan.FromSeconds(119)));
        Assert.Equal(TimeSpan.FromSeconds(30), Next(liveAgo: TimeSpan.FromMinutes(2)));
    }

    [Fact]
    public void LongInBackgroundIsOneMinuteEvenInAParty()
    {
        Assert.Equal(TimeSpan.FromSeconds(20), Next(party: true, inactiveFor: TimeSpan.FromMinutes(4)));
        Assert.Equal(TimeSpan.FromSeconds(60), Next(party: true, drawer: true, inactiveFor: TimeSpan.FromMinutes(6)));
    }

    [Theory]
    [InlineData(1, 5)]
    [InlineData(2, 10)]
    [InlineData(3, 20)]
    [InlineData(6, 160)]
    [InlineData(7, 300)]
    [InlineData(500, 300)]
    public void FailuresBackOffToFiveMinutes(int failures, int seconds) =>
        Assert.Equal(TimeSpan.FromSeconds(seconds), SyncSchedule.Backoff(failures));

    [Fact]
    public void SignInFailuresBackOffFromAMinuteToThirty()
    {
        Assert.Equal(TimeSpan.FromMinutes(1), SyncSchedule.AuthBackoff(1));
        Assert.Equal(TimeSpan.FromMinutes(4), SyncSchedule.AuthBackoff(3));
        Assert.Equal(TimeSpan.FromMinutes(30), SyncSchedule.AuthBackoff(40));
    }
}

public class SocialTextTests
{
    private static FriendInfo Friend(bool online, string? client = "game", string? activity = null,
        string? server = null, string? version = "1.21.1") =>
        new("u", "Steve", online, client, activity, server, version, 0);

    [Theory]
    [InlineData(0, "0 playing Origin")]
    [InlineData(1, "1 playing Origin")]
    [InlineData(999, "999 playing Origin")]
    [InlineData(1284, "1,284 playing Origin")]
    [InlineData(1234567, "1,234,567 playing Origin")]
    public void PlayersOnlineUsesThousandsSeparators(int count, string text) =>
        Assert.Equal(text, SocialText.PlayersOnline(count));

    [Fact]
    public void StatusLines()
    {
        Assert.Equal("Playing on hypixel.net · 1.21.1", SocialText.StatusLine(Friend(true, activity: "server", server: "hypixel.net")));
        Assert.Equal("Playing on hypixel.net · 1.21.1", SocialText.StatusLine(Friend(true, activity: "server", server: "hypixel.net:25565")));
        Assert.Equal("Playing on mc.example.org:25570 · 1.21.1", SocialText.StatusLine(Friend(true, activity: "server", server: "mc.example.org:25570")));
        Assert.Equal("Playing singleplayer · 1.20.1", SocialText.StatusLine(Friend(true, activity: "singleplayer", version: "1.20.1")));
        Assert.Equal("In game · 1.21.1", SocialText.StatusLine(Friend(true, activity: "menu")));
        Assert.Equal("In game", SocialText.StatusLine(Friend(true, activity: "menu", version: null)));
        Assert.Equal("In the launcher", SocialText.StatusLine(Friend(true, client: "launcher", activity: "menu")));
        Assert.Equal("Offline", SocialText.StatusLine(Friend(false, activity: "server", server: "hypixel.net")));
    }

    [Theory]
    [InlineData("Steve", true)]
    [InlineData("a_b", true)]
    [InlineData("ab", false)]
    [InlineData("seventeen_chars_x", false)]
    [InlineData("bad name", false)]
    [InlineData("-dash", false)]
    public void Usernames(string name, bool valid) => Assert.Equal(valid, SocialText.IsValidUsername(name));
}

public class JoinArgumentsTests
{
    [Theory]
    [InlineData("1.21.1", "hypixel.net", "--quickPlayMultiplayer hypixel.net")]
    [InlineData("1.21.1", "hypixel.net:25565", "--quickPlayMultiplayer hypixel.net")]
    [InlineData("1.20", "mc.example.org:25570", "--quickPlayMultiplayer mc.example.org:25570")]
    [InlineData("26.2", "10.0.0.5:25566", "--quickPlayMultiplayer 10.0.0.5:25566")]
    [InlineData("1.21.11", "[2001:db8::1]:25566", "--quickPlayMultiplayer [2001:db8::1]:25566")]
    [InlineData("1.19.4", "hypixel.net", "--server hypixel.net")]
    [InlineData("1.19.4", "hypixel.net:25565", "--server hypixel.net")]
    [InlineData("1.16.5", "mc.example.org:25570", "--server mc.example.org --port 25570")]
    [InlineData("1.18.2", "2001:db8::1", "--server 2001:db8::1")]
    [InlineData("1.8.9", "mc.hypixel.net", "--server mc.hypixel.net")]           // Forge 1.8.9: vanilla's own pair
    [InlineData("1.8.9", "pvp.example.net:25599", "--server pvp.example.net --port 25599")]
    public void QuickPlayFrom120ServerPortBefore(string version, string server, string expected) =>
        Assert.Equal(expected, string.Join(" ", JoinArguments.Build(version, server)));

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("-Dexploit=1")]
    [InlineData("host name")]
    [InlineData("host:notaport")]
    [InlineData("host:70000")]
    [InlineData("host:0")]
    [InlineData("evil;rm")]
    [InlineData("[::1")]
    public void RefusesAnythingThatIsNotAnAddress(string server) =>
        Assert.Empty(JoinArguments.Build("1.21.1", server));

    [Fact]
    public void CheckExplainsWhyJoinCantGoAhead()
    {
        static bool Supported(string v) => v is "1.21.1" or "1.20.1";
        Assert.Equal(JoinBlock.None, JoinArguments.Check("1.21.1", "hypixel.net", Supported));
        Assert.Equal(JoinBlock.UnsupportedVersion, JoinArguments.Check("1.12.2", "hypixel.net", Supported));
        Assert.Equal(JoinBlock.UnknownVersion, JoinArguments.Check(null, "hypixel.net", Supported));
        Assert.Equal(JoinBlock.NoServer, JoinArguments.Check("1.21.1", null, Supported));
        Assert.Contains("1.12.2", JoinArguments.Explain(JoinBlock.UnsupportedVersion, "rinrin", "1.12.2"));
        Assert.Contains("rinrin", JoinArguments.Explain(JoinBlock.UnsupportedVersion, "rinrin", "1.12.2"));
    }
}

public class RunningGameTrackerTests
{
    [Theory]
    [InlineData("[12:00:01] [Render thread/INFO]: Connecting to mc.hypixel.net, 25565", "mc.hypixel.net")]
    [InlineData("[12:00:01] [main/INFO]: Connecting to localhost, 25570", "localhost:25570")]
    [InlineData("[12:00:01] [Render thread/INFO]: Starting integrated minecraft server version 1.21.1", null)]
    [InlineData("[12:00:01] [Render thread/INFO]: Connecting to -bad, 25565", null)]
    public void ParsesTheConnectLine(string line, string? server) =>
        Assert.Equal(server, RunningGameTracker.ParseConnect(line));

    [Fact]
    public void TracksTheNewestGameOnly()
    {
        var tracker = new RunningGameTracker();
        var first = tracker.Started("1.20.1");
        tracker.Observe(first, "[x] [Render thread/INFO]: Connecting to old.example.org, 25565");
        var second = tracker.Started("1.21.1");
        tracker.Observe(first, "[x] [Render thread/INFO]: Connecting to stale.example.org, 25565");
        Assert.Equal(new RunningGameTracker.GameState(second, "1.21.1", null), tracker.Current);

        tracker.Observe(second, "[x] [Render thread/INFO]: Connecting to hypixel.net, 25565");
        Assert.Equal("hypixel.net", tracker.Current?.Server);
        tracker.Observe(second, "[x] [Server thread/INFO]: Starting integrated minecraft server version 1.21.1");
        Assert.Null(tracker.Current?.Server);

        tracker.Exited(first);
        Assert.NotNull(tracker.Current);
        tracker.Exited(second);
        Assert.Null(tracker.Current);
    }
}

public class SocialJsonTests
{
    private const string Sync = """
    {
      "cursor": 42,
      "friendsEtag": "abc",
      "me": { "uuid": "0000000000000000000000000000000A", "name": "Me", "appearOffline": false },
      "friends": [
        { "uuid": "b", "name": "Steve", "online": true, "client": "game", "activity": "server", "server": "hypixel.net", "version": "1.21.1", "lastSeen": 1700000000000 },
        { "uuid": "c", "name": "Alex", "online": false, "lastSeen": "1690000000000" }
      ],
      "requests": { "incoming": [ { "uuid": "d", "name": "Ojo", "at": 5 } ], "outgoing": [] },
      "blocked": [ { "uuid": "e", "name": "Griefer" } ],
      "messages": [ { "id": 7, "from": "b", "to": "0000000000000000000000000000000a", "text": "hi", "at": 10 } ],
      "party": { "id": "p1", "leader": "0000000000000000000000000000000a", "members": [ { "uuid": "0000000000000000000000000000000a", "name": "Me", "online": true, "state": null } ] },
      "partyMessages": [ { "id": 8, "from": "b", "name": "Steve", "text": "ready", "at": 11 } ],
      "pings": [],
      "partyInvites": [ { "partyId": "p2", "from": "c", "name": "Alex", "at": 12 } ],
      "warp": { "server": "hypixel.net", "version": "1.21.1", "at": 13, "from": "b", "name": "Steve" },
      "unknownField": { "future": true }
    }
    """;

    [Fact]
    public void ParsesAFullSync()
    {
        var r = SocialJson.ParseSync(Sync)!;
        Assert.Equal("42", r.CursorJson);
        Assert.Equal("abc", r.FriendsEtag);
        Assert.Equal("0000000000000000000000000000000a", r.Me!.Uuid);
        Assert.Equal(2, r.Friends!.Count);
        Assert.True(r.Friends[0].IsOnServer);
        Assert.Equal(1690000000000, r.Friends[1].LastSeen);
        Assert.Single(r.Incoming!);
        Assert.Empty(r.Outgoing!);
        Assert.Equal("Griefer", r.Blocked!.Single().Name);
        Assert.Equal("7", r.Messages.Single().Id);
        Assert.Equal("p1", r.Party!.Id);
        Assert.Equal("8", r.PartyMessages.Single().Id);
        Assert.Equal("p2", r.PartyInvites.Single().PartyId);
        Assert.Equal("Steve", r.Warp!.Name);
    }

    [Fact]
    public void OmittedListsAreNullNotEmpty()
    {
        var r = SocialJson.ParseSync("""{ "cursor": 1, "messages": [] }""")!;
        Assert.Null(r.Friends);
        Assert.Null(r.Incoming);
        Assert.Null(r.Blocked);
        Assert.Null(r.Party);
        Assert.Empty(r.Messages);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("not json")]
    [InlineData("[1,2]")]
    public void GarbageIsNull(string? body) => Assert.Null(SocialJson.ParseSync(body));

    [Fact]
    public void SyncBodyEchoesTheCursorAndSaysLauncher()
    {
        using var doc = JsonDocument.Parse(SocialJson.SyncBody("42", "1.21.1", "abc"));
        var root = doc.RootElement;
        Assert.Equal(42, root.GetProperty("since").GetInt32());
        Assert.Equal("launcher", root.GetProperty("presence").GetProperty("client").GetString());
        Assert.Equal("menu", root.GetProperty("presence").GetProperty("activity").GetString());
        Assert.Equal(JsonValueKind.Null, root.GetProperty("presence").GetProperty("server").ValueKind);
        Assert.Equal("1.21.1", root.GetProperty("presence").GetProperty("version").GetString());
        Assert.Equal(JsonValueKind.Null, root.GetProperty("partyState").ValueKind);
        Assert.Equal("abc", root.GetProperty("friendsEtag").GetString());

        using var first = JsonDocument.Parse(SocialJson.SyncBody(null, null));
        Assert.Equal(0, first.RootElement.GetProperty("since").GetInt32());
        Assert.False(first.RootElement.TryGetProperty("friendsEtag", out _));
        Assert.Equal(0, JsonDocument.Parse(SocialJson.SyncBody("{\"x\":1}", null)).RootElement.GetProperty("since").GetInt32());
    }

    [Theory]
    [InlineData("""{"online": 1284, "at": 1}""", 1284)]
    [InlineData("""{"online": "12"}""", 12)]
    [InlineData("""{"online": -1}""", null)]
    [InlineData("""{"count": 5}""", null)]
    [InlineData("<html>", null)]
    public void ParsesOnlineCount(string body, int? expected) => Assert.Equal(expected, SocialJson.ParseOnline(body));

    [Fact]
    public void ErrorSentenceIsShownAsIs() =>
        Assert.Equal("That player isn't on Origin.", SocialJson.ErrorSentence("""{"error":"That player isn't on Origin."}"""));
}

public class SocialMergeTests
{
    private const string Me = "me";

    private static SyncResult Sync(
        IReadOnlyList<ChatMessage>? messages = null, IReadOnlyList<FriendRequest>? incoming = null,
        IReadOnlyList<PartyInvite>? invites = null, WarpRequest? warp = null, IReadOnlyList<FriendInfo>? friends = null) =>
        new("1", new SocialMe(Me, "Me", false),
            friends ?? [new FriendInfo("b", "Steve", true, "game", "menu", null, "1.21.1", 0)],
            incoming ?? [], [], messages ?? [], null, [], invites ?? [], warp);

    [Fact]
    public void FirstSyncHistoryIsNeitherToastedNorUnread()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(
            messages: [new ChatMessage("1", "b", Me, "old", 1)],
            incoming: [new FriendRequest("d", "Ojo", 1)]), firstSync: true, null, false);
        Assert.Empty(m.Toasts);
        Assert.Empty(m.Snapshot.Unread);
        Assert.Single(m.Snapshot.ConversationWith("b"));
        Assert.False(m.LiveEvent);
    }

    [Fact]
    public void NewDmToastsAndCountsUnreadUnlessThatChatIsOpen()
    {
        var start = SocialMerge.Apply(new SocialSnapshot(), Sync(), true, null, false).Snapshot;
        var dm = Sync(messages: [new ChatMessage("2", "b", Me, "hey", 5)]);

        var closed = SocialMerge.Apply(start, dm, false, null, false);
        Assert.Equal(SocialToastKind.Message, closed.Toasts.Single().Kind);
        Assert.Equal("Steve", closed.Toasts.Single().Title);
        Assert.Equal(1, closed.Snapshot.Unread["b"]);
        Assert.True(closed.LiveEvent);

        var reading = SocialMerge.Apply(start, dm, false, "b", drawerOpen: true);
        Assert.Empty(reading.Toasts);
        Assert.Empty(reading.Snapshot.Unread);
        Assert.True(reading.LiveEvent);

        // The same id again (a repeated page) is not a second message.
        var again = SocialMerge.Apply(closed.Snapshot, dm, false, null, false);
        Assert.Empty(again.Toasts);
        Assert.Single(again.Snapshot.ConversationWith("b"));

        Assert.Empty(SocialMerge.MarkRead(closed.Snapshot, "b").Unread);
    }

    [Fact]
    public void MyOwnMessagesFileUnderTheOtherPlayer()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(messages: [new ChatMessage("3", Me, "b", "yo", 1)]), false, null, false);
        Assert.Single(m.Snapshot.ConversationWith("b"));
        Assert.Empty(m.Toasts);
    }

    [Fact]
    public void RequestsInvitesAndWarpsToastOnce()
    {
        var start = SocialMerge.Apply(new SocialSnapshot(), Sync(), true, null, false).Snapshot;
        var next = Sync(incoming: [new FriendRequest("d", "Ojo", 1)],
            invites: [new PartyInvite("p2", "b", "Steve", 2)],
            warp: new WarpRequest("hypixel.net", "1.21.1", 3, "b", "Steve"));
        var m = SocialMerge.Apply(start, next, false, null, false);
        Assert.Equal(
            [SocialToastKind.FriendRequest, SocialToastKind.PartyInvite, SocialToastKind.Warp],
            m.Toasts.Select(t => t.Kind).ToArray());
        Assert.Equal("1.21.1", m.Toasts.Last().Version);

        Assert.Empty(SocialMerge.Apply(m.Snapshot, next, false, null, false).Toasts);
    }

    [Fact]
    public void MyOwnWarpNeverToasts()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(warp: new WarpRequest("hypixel.net", "1.21.1", 3, Me)), true, null, false);
        Assert.Empty(m.Toasts);
    }

    [Fact]
    public void OmittedFriendsKeepTheLastList()
    {
        var start = SocialMerge.Apply(new SocialSnapshot(), Sync() with { FriendsEtag = "e1" }, true, null, false).Snapshot;
        var unchanged = Sync() with { Friends = null, Incoming = null, Outgoing = null };
        var m = SocialMerge.Apply(start, unchanged, false, null, false).Snapshot;
        Assert.Equal("Steve", m.Friends.Single().Name);
        Assert.Equal("e1", m.FriendsEtag);
    }

    [Fact]
    public void FriendsSortOnlineFirstThenByName()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(friends:
        [
            new FriendInfo("x", "zed", false, null, null, null, null, 0),
            new FriendInfo("y", "Bob", true, "game", "menu", null, null, 0),
            new FriendInfo("z", "alice", true, "launcher", "menu", null, null, 0),
        ]), true, null, false);
        Assert.Equal(["alice", "Bob", "zed"], m.Snapshot.Friends.Select(f => f.Name).ToArray());
    }
}

/// <summary>
/// Stands in for Mojang in the sign-in tests: a real RSA key pair acting as
/// the player certificate, answered in Mojang's own shapes (PEM labelled
/// "RSA ... KEY" around PKCS#8 / SPKI bodies, ISO times with 9 fraction digits).
/// </summary>
internal static class FakeMojang
{
    public static readonly RSA Key = RSA.Create(2048);
    public static readonly byte[] Spki = Key.ExportSubjectPublicKeyInfo();
    public const string Uuid = "069A79F4-44E9-4726-A5BE-FCA90E38AAF5";
    public const string UuidNorm = "069a79f444e94726a5befca90e38aaf5";
    public const string ExpiresAtIso = "2026-10-14T12:00:00.123456789Z";
    public const string RefreshedAfterIso = "2026-10-14T04:00:00.987654Z";
    public static readonly long ExpiresAtMs = new DateTimeOffset(2026, 10, 14, 12, 0, 0, TimeSpan.Zero).ToUnixTimeMilliseconds() + 123;
    public static readonly long RefreshedAfterMs = new DateTimeOffset(2026, 10, 14, 4, 0, 0, TimeSpan.Zero).ToUnixTimeMilliseconds() + 987;
    public const string KeySignature = "bW9qYW5nLXYyLXNpZ25hdHVyZQ==";
    public const string TexturesValue = "eyJwcm9maWxlSWQiOiIwNjlhNzlmNDQ0ZTk0NzI2YTViZWZjYTkwZTM4YWFmNSJ9";
    public const string TexturesSignature = "dGV4dHVyZXMtc2lnbmF0dXJl";

    public static string Pem(string label, byte[] der, string newline = "\n")
    {
        var b64 = Convert.ToBase64String(der);
        var lines = Enumerable.Range(0, (b64.Length + 75) / 76).Select(i => b64.Substring(i * 76, Math.Min(76, b64.Length - i * 76)));
        return $"-----BEGIN {label}-----{newline}{string.Join(newline, lines)}{newline}-----END {label}-----{newline}";
    }

    public static string CertificateJson(byte[]? privateDer = null, byte[]? publicDer = null) => JsonSerializer.Serialize(new
    {
        keyPair = new
        {
            privateKey = Pem("RSA PRIVATE KEY", privateDer ?? Key.ExportPkcs8PrivateKey()),
            publicKey = Pem("RSA PUBLIC KEY", publicDer ?? Spki),
        },
        publicKeySignature = "old-v1",
        publicKeySignatureV2 = KeySignature,
        expiresAt = ExpiresAtIso,
        refreshedAfter = RefreshedAfterIso,
    });

    public static string ProfileJson(string name = "Notch") => JsonSerializer.Serialize(new
    {
        id = UuidNorm,
        name,
        properties = new[] { new { name = "textures", value = TexturesValue, signature = TexturesSignature } },
    });

    /// <summary>A network that answers Mojang like Mojang and everything else with <paramref name="origin"/>.</summary>
    public static SocialHttpSend Net(Func<Uri, SocialHttpResponse> origin) => (_, url, _, _, _) => Task.FromResult(
        url == SocialEndpoints.PlayerCertificates ? new SocialHttpResponse(200, CertificateJson())
        : url.Host == "sessionserver.mojang.com" ? new SocialHttpResponse(200, ProfileJson())
        : origin(url));
}

public class MojangProofTests
{
    [Fact]
    public void TheSignedStringIsExact()
    {
        Assert.Equal("origin-auth|069a79f444e94726a5befca90e38aaf5|launcher|1760270400000",
            MojangProof.SignedString(FakeMojang.Uuid, "launcher", 1760270400000));
    }

    [Fact]
    public void PemBodiesIgnoreTheLabelAndLineBreaks()
    {
        var der = new byte[] { 1, 2, 3, 250, 251, 252 };
        Assert.Equal(der, MojangProof.PemBody(FakeMojang.Pem("RSA PRIVATE KEY", der, "\r\n")));
        Assert.Equal(der, MojangProof.PemBody("  " + Convert.ToBase64String(der) + "\n"));
        Assert.Null(MojangProof.PemBody("-----BEGIN RSA PRIVATE KEY-----\n***\n-----END RSA PRIVATE KEY-----"));
        Assert.Null(MojangProof.PemBody(null));
    }

    [Fact]
    public void ACertificateParsesAndSignsVerifiably()
    {
        var cert = MojangProof.ParseCertificate(FakeMojang.CertificateJson());
        Assert.NotNull(cert);
        Assert.Equal(FakeMojang.Spki, cert!.PublicKeySpki);
        Assert.Equal(FakeMojang.KeySignature, cert.PublicKeySignatureV2);
        Assert.Equal(FakeMojang.ExpiresAtMs, cert.ExpiresAtMs);
        Assert.Equal(FakeMojang.RefreshedAfterMs, cert.RefreshedAfterMs);

        var sig = MojangProof.Sign(cert, "hello");
        Assert.True(FakeMojang.Key.VerifyData(Encoding.UTF8.GetBytes("hello"), Convert.FromBase64String(sig),
            HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
    }

    [Fact]
    public void APkcsOnePrivateKeyStillSignsButThePublicKeyIsNeverReencoded()
    {
        var cert = MojangProof.ParseCertificate(FakeMojang.CertificateJson(privateDer: FakeMojang.Key.ExportRSAPrivateKey()));
        Assert.NotNull(cert);
        var sig = MojangProof.Sign(cert!, "x");
        Assert.True(FakeMojang.Key.VerifyData(Encoding.UTF8.GetBytes("x"), Convert.FromBase64String(sig),
            HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));

        // A PKCS#1 public key isn't what Mojang signed: refuse rather than convert.
        Assert.Null(MojangProof.ParseCertificate(FakeMojang.CertificateJson(publicDer: FakeMojang.Key.ExportRSAPublicKey())));
    }

    [Fact]
    public void ABrokenCertificateIsNull()
    {
        Assert.Null(MojangProof.ParseCertificate(null));
        Assert.Null(MojangProof.ParseCertificate("{}"));
        Assert.Null(MojangProof.ParseCertificate("""{"keyPair":{"privateKey":"-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----","publicKey":"x"},"publicKeySignatureV2":"s","expiresAt":"2026-10-14T12:00:00Z"}"""));
        var noExpiry = FakeMojang.CertificateJson().Replace(FakeMojang.ExpiresAtIso, "soon");
        Assert.Null(MojangProof.ParseCertificate(noExpiry));
    }

    [Fact]
    public void IsoTimesTruncateToMillisecondsLikeJava()
    {
        var baseMs = new DateTimeOffset(2026, 10, 14, 12, 0, 0, TimeSpan.Zero).ToUnixTimeMilliseconds();
        Assert.Equal(baseMs, MojangProof.IsoToEpochMs("2026-10-14T12:00:00Z"));
        Assert.Equal(baseMs + 999, MojangProof.IsoToEpochMs("2026-10-14T12:00:00.999999999Z"));
        Assert.Equal(baseMs + 5, MojangProof.IsoToEpochMs("2026-10-14T12:00:00.0059Z"));
        Assert.Equal(baseMs, MojangProof.IsoToEpochMs("2026-10-14T14:00:00+02:00"));
        Assert.Null(MojangProof.IsoToEpochMs("not a time"));
    }

    [Fact]
    public void TheSignedProfileGivesTexturesAndName()
    {
        var profile = MojangProof.ParseProfile(FakeMojang.ProfileJson());
        Assert.NotNull(profile);
        Assert.Equal("Notch", profile!.Value.Name);
        Assert.Equal(new SignedTextures(FakeMojang.TexturesValue, FakeMojang.TexturesSignature), profile.Value.Textures);
        Assert.Null(MojangProof.ParseProfile("""{"id":"x","name":"n","properties":[{"name":"textures","value":"v"}]}"""));
        Assert.Null(MojangProof.ParseProfile("""{"id":"x","name":"n","properties":[]}"""));
    }

    [Fact]
    public void TheCertificateNeverPrintsItsKey()
    {
        var cert = MojangProof.ParseCertificate(FakeMojang.CertificateJson())!;
        var text = cert.ToString();
        Assert.DoesNotContain(Convert.ToBase64String(FakeMojang.Key.ExportPkcs8PrivateKey())[..40], text);
        Assert.DoesNotContain("Private", text);
    }

    [Fact]
    public void MultiplayerRefusalsAreRecognised()
    {
        Assert.True(MojangProof.SaysMultiplayerDisabled("""{"errorMessage":"Multiplayer is disabled for this account"}"""));
        Assert.True(MojangProof.SaysMultiplayerDisabled("""{"error":"INSUFFICIENT_PRIVILEGES"}"""));
        Assert.False(MojangProof.SaysMultiplayerDisabled("""{"error":"FORBIDDEN"}"""));
        Assert.False(MojangProof.SaysMultiplayerDisabled(null));
    }
}

public class SocialClientTests
{
    private sealed class FakeNet
    {
        public readonly List<(HttpMethod Method, string Url, string? Body, string? Bearer)> Calls = new();
        public Func<string?, SocialHttpResponse> Certificates = _ => new(200, FakeMojang.CertificateJson());
        public Func<SocialHttpResponse> Profile = () => new(200, FakeMojang.ProfileJson());
        public Func<int, SocialHttpResponse> Auth = n => new(200, $$"""{"token":"t{{n}}","uuid":"{{FakeMojang.Uuid}}","name":"Notch","expiresAt":1}""");
        public Func<string?, SocialHttpResponse> Call = _ => new(200, """{"ok":true}""");
        private int _auths;

        public Task<SocialHttpResponse> Send(HttpMethod method, Uri url, string? body, string? bearer, CancellationToken ct)
        {
            Calls.Add((method, url.ToString(), body, bearer));
            if (url == SocialEndpoints.PlayerCertificates) return Task.FromResult(Certificates(bearer));
            if (url.Host == "sessionserver.mojang.com") return Task.FromResult(Profile());
            if (url.AbsolutePath == "/v1/auth") return Task.FromResult(Auth(++_auths));
            return Task.FromResult(Call(bearer));
        }

        public int Count(string what) => Calls.Count(c => c.Url.Contains(what, StringComparison.Ordinal));
    }

    private static readonly Uri Base = new("https://social.test");
    private static readonly DateTimeOffset Now = new(2026, 10, 12, 12, 0, 0, TimeSpan.Zero);

    private static IdentitySource Identity(List<bool>? forced = null) => (force, _) =>
    {
        forced?.Add(force);
        return Task.FromResult(IdentityResult.Ok(new MinecraftIdentity("notch", FakeMojang.Uuid, force ? "fresh-mc" : "cached-mc")));
    };

    private static SocialClient Client(FakeNet net, IdentitySource? identity = null, Func<DateTimeOffset>? clock = null) =>
        new(Base, net.Send, identity ?? Identity(), clock ?? (() => Now));

    [Fact]
    public async Task SignsInWithTheMojangCertificateAndSignedProfile()
    {
        var net = new FakeNet();
        var client = Client(net);
        var result = await client.PostAsync("/v1/sync", "{}", CancellationToken.None);

        Assert.True(result.Ok);
        Assert.Equal(4, net.Calls.Count);

        // 1. certificate: POST, the Minecraft token as Bearer, no body.
        Assert.Equal(HttpMethod.Post, net.Calls[0].Method);
        Assert.Equal("https://api.minecraftservices.com/player/certificates", net.Calls[0].Url);
        Assert.Equal("cached-mc", net.Calls[0].Bearer);
        Assert.Null(net.Calls[0].Body);

        // 2. signed profile: GET, undashed uuid, unsigned=false, no token.
        Assert.Equal(HttpMethod.Get, net.Calls[1].Method);
        Assert.Equal($"https://sessionserver.mojang.com/session/minecraft/profile/{FakeMojang.UuidNorm}?unsigned=false", net.Calls[1].Url);
        Assert.Null(net.Calls[1].Bearer);

        // 3. /v1/auth with the v2 body.
        Assert.Equal("https://social.test/v1/auth", net.Calls[2].Url);
        Assert.Null(net.Calls[2].Bearer);
        var body = net.Calls[2].Body!;
        using var auth = JsonDocument.Parse(body);
        var r = auth.RootElement;
        var at = Now.ToUnixTimeMilliseconds();
        Assert.Equal("launcher", r.GetProperty("client").GetString());
        Assert.Equal(FakeMojang.UuidNorm, r.GetProperty("uuid").GetString());
        Assert.Equal("Notch", r.GetProperty("name").GetString()); // Mojang's spelling, not the account's "notch"
        Assert.Equal(at, r.GetProperty("at").GetInt64());
        Assert.Equal(Convert.ToBase64String(FakeMojang.Spki), r.GetProperty("publicKey").GetString());
        Assert.Equal(FakeMojang.ExpiresAtMs, r.GetProperty("keyExpiresAt").GetInt64());
        Assert.Equal(FakeMojang.KeySignature, r.GetProperty("keySignature").GetString());
        Assert.Equal(FakeMojang.TexturesValue, r.GetProperty("textures").GetProperty("value").GetString());
        Assert.Equal(FakeMojang.TexturesSignature, r.GetProperty("textures").GetProperty("signature").GetString());
        Assert.Equal(["client", "uuid", "name", "at", "sig", "publicKey", "keyExpiresAt", "keySignature", "textures"],
            r.EnumerateObject().Select(p => p.Name).ToArray());

        var signed = $"origin-auth|{FakeMojang.UuidNorm}|launcher|{at}";
        Assert.True(FakeMojang.Key.VerifyData(Encoding.UTF8.GetBytes(signed),
            Convert.FromBase64String(r.GetProperty("sig").GetString()!), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));

        // Neither the Minecraft token nor the private key ever reaches Origin.
        Assert.DoesNotContain("cached-mc", body);
        Assert.DoesNotContain(Convert.ToBase64String(FakeMojang.Key.ExportPkcs8PrivateKey())[..40], body);

        // 4. the call itself, with the Origin token.
        Assert.Equal("t1", net.Calls[3].Bearer);
        Assert.Equal(FakeMojang.UuidNorm, client.MyUuid);
    }

    [Fact]
    public async Task TheCertificateIsCachedUntilMojangSaysRefresh()
    {
        var net = new FakeNet();
        var now = Now;
        var client = Client(net, clock: () => now);
        await client.PostAsync("/v1/sync", "{}", CancellationToken.None);

        // Origin token expires → re-auth reuses the certificate, re-fetches the profile.
        net.Call = bearer => bearer == "t1" ? new(401, null) : new(200, "{}");
        await client.PostAsync("/v1/sync", "{}", CancellationToken.None);
        Assert.Equal(1, net.Count("/player/certificates"));
        Assert.Equal(2, net.Count("sessionserver"));

        // Past refreshedAfter → a new certificate.
        now = DateTimeOffset.FromUnixTimeMilliseconds(FakeMojang.RefreshedAfterMs);
        net.Call = bearer => bearer == "t2" ? new(401, null) : new(200, "{}");
        await client.PostAsync("/v1/sync", "{}", CancellationToken.None);
        Assert.Equal(2, net.Count("/player/certificates"));

        // Account switched → the certificate is dropped with the token.
        client.Forget();
        await client.EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(3, net.Count("/player/certificates"));
    }

    [Fact]
    public async Task ACertificateCloseToExpiryIsNotReused()
    {
        var net = new FakeNet();
        var now = Now;
        net.Certificates = _ => new(200, FakeMojang.CertificateJson().Replace(FakeMojang.RefreshedAfterIso, "2099-01-01T00:00:00Z"));
        var client = Client(net, clock: () => now);
        await client.EnsureAuthAsync(CancellationToken.None);
        client.Forget();
        now = DateTimeOffset.FromUnixTimeMilliseconds(FakeMojang.ExpiresAtMs - 60_000);
        await client.EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(2, net.Count("/player/certificates"));
    }

    [Fact]
    public async Task A401ReauthenticatesOnceThenRetries()
    {
        var net = new FakeNet();
        net.Call = bearer => bearer == "t1" ? new(401, null) : new(200, "{}");
        var client = Client(net);
        var result = await client.PostAsync("/v1/sync", "{}", CancellationToken.None);

        Assert.True(result.Ok);
        Assert.Equal("t2", net.Calls.Last().Bearer);
    }

    [Fact]
    public async Task APersistent401IsNotRetriedForever()
    {
        var net = new FakeNet();
        net.Call = _ => new(401, null);
        var client = Client(net);
        var result = await client.PostAsync("/v1/sync", "{}", CancellationToken.None);
        Assert.Equal(401, result.Status);
        Assert.True(net.Calls.Count <= 8);
    }

    [Fact]
    public async Task ARefusedMinecraftTokenIsRefreshedOnce()
    {
        var net = new FakeNet();
        net.Certificates = bearer => bearer == "fresh-mc" ? new(200, FakeMojang.CertificateJson()) : new(401, null);
        var forced = new List<bool>();
        var client = Client(net, Identity(forced));
        var auth = await client.EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.Ok, auth.State);
        Assert.Equal([false, true], forced.ToArray());
    }

    [Fact]
    public async Task AMinecraftTokenRefusedTwiceGivesUp()
    {
        var net = new FakeNet();
        net.Certificates = _ => new(401, null);
        var forced = new List<bool>();
        var client = Client(net, Identity(forced));
        var auth = await client.EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.SignInFailed, auth.State);
        Assert.Equal("Couldn't sign in to friends. Try removing and re-adding your account.", auth.Message);
        Assert.Equal([false, true], forced.ToArray());
        Assert.Equal(0, net.Count("/v1/auth"));
    }

    [Theory]
    [InlineData("""{"path":"/player/certificates","errorMessage":"Multiplayer is disabled for this account"}""",
        "Your Microsoft account doesn't allow online play, so friends can't be used.")]
    [InlineData("""{"path":"/player/certificates","error":"FORBIDDEN"}""", "Couldn't sign in to friends right now.")]
    [InlineData(null, "Couldn't sign in to friends right now.")]
    public async Task ACertificateRefusalSaysWhy(string? mojangBody, string expected)
    {
        var net = new FakeNet { Certificates = _ => new(403, mojangBody) };
        var forced = new List<bool>();
        var auth = await Client(net, Identity(forced)).EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.SignInFailed, auth.State);
        Assert.Equal(expected, auth.Message);
        Assert.Equal([false], forced.ToArray()); // a 403 isn't an expired token: no refresh
        Assert.Equal(0, net.Count("/v1/auth"));
    }

    [Fact]
    public async Task AnUnreadableCertificateIsAPlainFailure()
    {
        var net = new FakeNet { Certificates = _ => new(200, """{"keyPair":{}}""") };
        var auth = await Client(net).EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.SignInFailed, auth.State);
        Assert.Equal("Couldn't sign in to friends right now.", auth.Message);
    }

    [Theory]
    [InlineData(0, 200)]
    [InlineData(503, 200)]
    [InlineData(429, 200)]
    [InlineData(200, 0)]
    [InlineData(200, 502)]
    public async Task MojangUnreachableIsUnreachable(int certificates, int profile)
    {
        var net = new FakeNet
        {
            Certificates = _ => certificates == 200 ? new(200, FakeMojang.CertificateJson()) : new(certificates, null),
            Profile = () => profile == 200 ? new(200, FakeMojang.ProfileJson()) : new(profile, null),
        };
        var auth = await Client(net).EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.Unreachable, auth.State);
        Assert.Equal(0, net.Count("/v1/auth"));
    }

    [Fact]
    public async Task AProfileWithoutSignedTexturesFails()
    {
        var net = new FakeNet { Profile = () => new(200, """{"id":"x","name":"Notch","properties":[]}""") };
        var auth = await Client(net).EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.SignInFailed, auth.State);
        Assert.Equal("Couldn't sign in to friends right now.", auth.Message);
    }

    [Fact]
    public async Task TheServicesRefusalIsShownAsIsAndDropsTheCertificate()
    {
        var net = new FakeNet { Auth = _ => new(401, """{"error":"Your Minecraft profile couldn't be checked. Try again in a moment."}""") };
        var client = Client(net);
        var auth = await client.EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.SignInFailed, auth.State);
        Assert.Equal("Your Minecraft profile couldn't be checked. Try again in a moment.", auth.Message);

        await client.EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(2, net.Count("/player/certificates"));
    }

    [Fact]
    public async Task AnOutdatedClientIsToldToUpdate()
    {
        var net = new FakeNet { Auth = _ => new(400, """{"error":"Couldn't sign in to Origin. Please update Origin and try again."}""") };
        var auth = await Client(net).EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.SignInFailed, auth.State);
        Assert.Equal("Couldn't sign in to Origin. Please update Origin and try again.", auth.Message);
    }

    [Fact]
    public async Task AServiceNotReadyIsRetriedLaterWithItsSentence()
    {
        var net = new FakeNet { Auth = _ => new(503, """{"error":"Friends are starting up. Try again in a few minutes."}""") };
        var auth = await Client(net).EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.Unreachable, auth.State);
        Assert.Equal("Friends are starting up. Try again in a few minutes.", auth.Message);
    }

    [Fact]
    public async Task EveryAuthIsSignedAfresh()
    {
        var net = new FakeNet();
        var now = Now;
        var client = Client(net, clock: () => now);
        await client.EnsureAuthAsync(CancellationToken.None);
        client.Forget();
        now = now.AddMilliseconds(1);
        await client.EnsureAuthAsync(CancellationToken.None);
        var sigs = net.Calls.Where(c => c.Url.EndsWith("/v1/auth", StringComparison.Ordinal))
            .Select(c => JsonDocument.Parse(c.Body!).RootElement.GetProperty("sig").GetString()).ToList();
        Assert.Equal(2, sigs.Count);
        Assert.NotEqual(sigs[0], sigs[1]);
    }

    [Fact]
    public async Task NoAccountMeansNoNetwork()
    {
        var net = new FakeNet();
        var client = new SocialClient(Base, net.Send,
            (_, _) => Task.FromResult(IdentityResult.NoAccount("Sign in with Microsoft to use friends.")));
        var auth = await client.EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.NoAccount, auth.State);
        Assert.Equal("Sign in with Microsoft to use friends.", auth.Message);
        Assert.Empty(net.Calls);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(404)]
    [InlineData(503)]
    public async Task AnUndeployedOrDownServiceIsUnreachable(int status)
    {
        var net = new FakeNet { Auth = _ => new(status, null) };
        var auth = await Client(net).EnsureAuthAsync(CancellationToken.None);
        Assert.Equal(SocialAuthState.Unreachable, auth.State);
    }

    [Fact]
    public void ErrorTextPrefersTheServiceSentence()
    {
        Assert.Equal("You're already in a party.", new SocialCallResult(400, """{"error":"You're already in a party."}""").ErrorText);
        Assert.Contains("Can't reach Origin", new SocialCallResult(0, null).ErrorText);
        Assert.True(new SocialCallResult(404, null).Unreachable);
        Assert.False(new SocialCallResult(404, """{"error":"That player isn't on Origin."}""").Unreachable);
    }
}

public class SocialServiceTests
{
    [Fact]
    public async Task SignedOutShowsTheFriendlyLineAndNeverPolls()
    {
        var calls = 0;
        var client = new SocialClient(new Uri("https://social.test"),
            (_, _, _, _, _) => { calls++; return Task.FromResult(new SocialHttpResponse(200, "{}")); },
            (_, _) => Task.FromResult(IdentityResult.NoAccount("Sign in with Microsoft to use friends.")));
        using var service = new SocialService(client, () => "1.21.1");
        var changed = new TaskCompletionSource<SocialSnapshot>();
        service.Changed += s => changed.TrySetResult(s);
        service.Start();

        var snapshot = await changed.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(SocialStatus.NotSignedIn, snapshot.Status);
        Assert.Equal("Sign in with Microsoft to use friends.", snapshot.StatusMessage);
        Assert.Equal(0, calls);
    }

    [Fact]
    public async Task ASyncBecomesAnOnlineSnapshot()
    {
        var client = new SocialClient(new Uri("https://social.test"),
            FakeMojang.Net(url => url.AbsolutePath switch
            {
                "/v1/auth" => new SocialHttpResponse(200, """{"token":"t","uuid":"me","name":"Me"}"""),
                "/v1/sync" => new SocialHttpResponse(200, """{"cursor":5,"me":{"uuid":"me","name":"Me"},"friends":[{"uuid":"b","name":"Steve","online":true,"client":"launcher"}]}"""),
                _ => new SocialHttpResponse(204, null),
            }),
            (_, _) => Task.FromResult(IdentityResult.Ok(new MinecraftIdentity("Me", FakeMojang.Uuid, "mc"))));
        using var service = new SocialService(client, () => "1.21.1");
        var online = new TaskCompletionSource<SocialSnapshot>();
        service.Changed += s => { if (s.Status == SocialStatus.Online) online.TrySetResult(s); };
        service.Start();

        var snapshot = await online.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(snapshot.HasData);
        Assert.Equal("In the launcher", SocialText.StatusLine(snapshot.Friends.Single()));
    }
}
