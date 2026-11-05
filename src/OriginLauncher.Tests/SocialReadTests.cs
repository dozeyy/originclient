using System.Collections.Concurrent;
using System.Text.Json;
using OriginLauncher.App.Core.Social;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Read markers, per-conversation history and the store reserve's `slow`
/// (docs/SOCIAL.md "Read markers", "History", "Store reserve").
/// </summary>
public class SocialReadMarkerTests
{
    private const string Me = "me";

    private static SyncResult Sync(
        IReadOnlyList<ChatMessage>? messages = null, IReadOnlyDictionary<string, long>? reads = null, long? readsAt = 1,
        PartyInfo? party = null, IReadOnlyList<PartyChatMessage>? partyMessages = null) =>
        new("1", new SocialMe(Me, "Me", false),
            [new FriendInfo("b", "Steve", true, "game", "menu", null, "1.21.1", 0),
             new FriendInfo("c", "Alex", false, null, null, null, null, 0),
             new FriendInfo("d", "Sam", false, null, null, null, null, 0)],
            [], [], messages ?? [], party, partyMessages ?? [], [], null,
            Reads: reads ?? new Dictionary<string, long>(), ReadsAt: readsAt);

    private static ChatMessage From(string who, long id, string text = "hi") => new(id.ToString(), who, Me, text, id);
    private static ChatMessage Mine(string to, long id) => new(id.ToString(), Me, to, "me", id);

    [Fact]
    public void MessagesThatArrivedWhileClosedAreUnreadWithOneSummaryToast()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(
            messages: [From("b", 1, "old"), Mine("b", 2), From("b", 3, "you there?"), From("b", 4, "hello?")],
            reads: new Dictionary<string, long> { ["b"] = 2 }), firstSync: true, null, false);
        Assert.Equal(2, m.Snapshot.Unread["b"]);
        var toast = Assert.Single(m.Toasts);
        Assert.Equal(SocialToastKind.Message, toast.Kind);
        Assert.Equal("Steve", toast.Title);
        Assert.Equal("2 new messages", toast.Body);
        Assert.Equal("b", toast.Uuid);
        Assert.True(m.LiveEvent);
    }

    [Fact]
    public void ASingleOfflineMessageShowsItsText()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(messages: [From("c", 9, "gg last night")]), true, null, false);
        Assert.Equal(1, m.Snapshot.Unread["c"]);
        Assert.Equal(("Alex", "gg last night"), (m.Toasts.Single().Title, m.Toasts.Single().Body));
    }

    [Fact]
    public void SeveralSendersShareOneToastThatRepliesToTheNewest()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(
            messages: [From("b", 1), From("c", 2), From("c", 3), From("d", 5)]), true, null, false);
        var toast = Assert.Single(m.Toasts);
        Assert.Equal("New messages", toast.Title);
        Assert.Equal("4 new messages from Sam, Alex and 1 more", toast.Body);
        Assert.Equal("d", toast.Uuid);
        Assert.Equal(4, m.Snapshot.AttentionCount);
    }

    [Fact]
    public void FirstSyncHistoryAlreadyReadIsQuiet()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(
            messages: [From("b", 1), From("b", 2)], reads: new Dictionary<string, long> { ["b"] = 2 }), true, null, false);
        Assert.Empty(m.Snapshot.Unread);
        Assert.Empty(m.Toasts);
    }

    [Fact]
    public void AnOlderServiceWithoutMarkersKeepsHistoryQuiet()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(messages: [From("b", 1)], reads: null, readsAt: null), true, null, false);
        Assert.Empty(m.Snapshot.Unread);
        Assert.Empty(m.Toasts);
    }

    [Fact]
    public void TheOpenChatIsNeverUnreadEvenOnTheFirstSync()
    {
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(messages: [From("b", 1)]), true, "b", drawerOpen: true);
        Assert.Empty(m.Snapshot.Unread);
    }

    [Fact]
    public void ReadingInTheGameClearsTheLauncherBadge()
    {
        var start = SocialMerge.Apply(new SocialSnapshot(), Sync(), true, null, false).Snapshot;
        var got = SocialMerge.Apply(start, Sync(messages: [From("b", 5), From("b", 6), From("b", 7)]), false, null, false).Snapshot;
        Assert.Equal(3, got.Unread["b"]);

        // The game read up to 6: one left.
        var partly = SocialMerge.Apply(got, Sync(reads: new Dictionary<string, long> { ["b"] = 6 }), false, null, false).Snapshot;
        Assert.Equal(1, partly.Unread["b"]);
        // Answering in the game reads the lot (the reply arrives here as my own line).
        var answered = SocialMerge.Apply(partly, Sync(messages: [Mine("b", 8)]), false, null, false).Snapshot;
        Assert.Empty(answered.Unread);
        Assert.Equal(8, answered.ReadMarks["b"]);
        // A line the game already read never toasts here.
        var late = SocialMerge.Apply(answered, Sync(messages: [From("b", 9)], reads: new Dictionary<string, long> { ["b"] = 9 }), false, null, false);
        Assert.Empty(late.Toasts);
        Assert.Empty(late.Snapshot.Unread);
    }

    [Fact]
    public void PartyChatFollowsTheSameMarkers()
    {
        var party = new PartyInfo("p1", "b", [new PartyMember(Me, "Me", true), new PartyMember("b", "Steve", true)]);
        PartyChatMessage Line(string from, long id) => new(id.ToString(), from, from == Me ? "Me" : "Steve", "x", id);
        var m = SocialMerge.Apply(new SocialSnapshot(), Sync(party: party,
            partyMessages: [Line("b", 1), Line("b", 2), Line("b", 3)],
            reads: new Dictionary<string, long> { [SocialSnapshot.PartyKey] = 1 }), true, null, false).Snapshot;
        Assert.Equal(2, m.UnreadParty);
        var read = SocialMerge.Apply(m, Sync(party: party, reads: new Dictionary<string, long> { [SocialSnapshot.PartyKey] = 3 }), false, null, false).Snapshot;
        Assert.Equal(0, read.UnreadParty);
    }

    [Fact]
    public void HistoryJoinsTheChatInOrderWithoutDuplicates()
    {
        var s = SocialMerge.Apply(new SocialSnapshot(), Sync(messages: [From("b", 10), From("b", 11)]), false, null, false).Snapshot;
        s = SocialMerge.AddHistory(s, "b", [From("b", 8), From("b", 9), From("b", 10)]);
        Assert.Equal(new[] { "8", "9", "10", "11" }, s.ConversationWith("b").Select(m => m.Id));
        Assert.Equal(11, SocialMerge.NewestId(s, "b"));
        Assert.Equal(0, SocialMerge.NewestId(s, "nobody"));
        Assert.Equal(5, SocialMerge.WithReadMark(SocialMerge.WithReadMark(s, "b", 5), "b", 3).ReadMarks["b"]);
    }
}

public class SocialReadJsonTests
{
    [Fact]
    public void ParsesMarkersCursorAndSlow()
    {
        var r = SocialJson.ParseSync("""
            { "cursor": 9, "reads": { "0A1B-00000000000000000000000000000": 7, "party": "8", "bad": 0 }, "readsAt": 1700000000001, "slow": 3 }
            """)!;
        Assert.Equal(7, r.Reads![Ids.Norm("0A1B-00000000000000000000000000000")]);
        Assert.Equal(8, r.Reads[SocialSnapshot.PartyKey]);
        Assert.False(r.Reads.ContainsKey("bad"));
        Assert.Equal(1700000000001, r.ReadsAt);
        Assert.True(r.KeepsReadMarkers);
        Assert.Equal(3, r.Slow);
    }

    [Fact]
    public void AnOlderServiceHasNoMarkersAndFullSpeed()
    {
        var r = SocialJson.ParseSync("""{ "cursor": 1 }""")!;
        Assert.Null(r.Reads);
        Assert.Null(r.ReadsAt);
        Assert.False(r.KeepsReadMarkers);
        Assert.Equal(1, r.Slow);
        Assert.Equal(8, SocialJson.ParseSync("""{ "slow": 99 }""")!.Slow);
    }

    [Fact]
    public void SyncBodyAsksForMarkers()
    {
        using var doc = JsonDocument.Parse(SocialJson.SyncBody("5", "1.21.1", null, 123));
        Assert.Equal(123, doc.RootElement.GetProperty("readsSince").GetInt64());
        using var none = JsonDocument.Parse(SocialJson.SyncBody("5", "1.21.1"));
        Assert.False(none.RootElement.TryGetProperty("readsSince", out _));
    }

    [Fact]
    public void ParsesHistory()
    {
        var h = SocialJson.ParseHistory("""{ "messages": [ { "id": 3, "from": "B", "to": "me", "text": "old", "at": 1000 } ], "more": true }""",
            ms => ms - 10)!;
        Assert.Equal(("3", "b", "old", 990L), (h[0].Id, h[0].From, h[0].Text, h[0].At));
        Assert.Null(SocialJson.ParseHistory("""{ "error": "Pick a friend." }"""));
    }

    [Fact]
    public void SlowStretchesEveryCadence()
    {
        var now = new DateTimeOffset(2026, 11, 5, 12, 0, 0, TimeSpan.Zero);
        Assert.Equal(TimeSpan.FromSeconds(90), SyncSchedule.NextDelay(new SyncInputs(now, false, false, null, null, 3)));
        Assert.Equal(TimeSpan.FromSeconds(32), SyncSchedule.NextDelay(new SyncInputs(now, true, false, null, null, 8)));
        Assert.Equal(TimeSpan.FromSeconds(30), SyncSchedule.NextDelay(new SyncInputs(now, false, false, null, null, 0)));
    }
}

public class SocialReadServiceTests
{
    [Fact]
    public async Task OpeningAChatTellsTheServiceItWasReadAndFetchesAnEmptyChatsHistory()
    {
        var posts = new ConcurrentQueue<(string Path, string? Body)>();
        var client = new SocialClient(new Uri("https://social.test"),
            (method, url, body, bearer, ct) =>
            {
                if (url.Host == "social.test") posts.Enqueue((url.AbsolutePath, body));
                return FakeMojang.Net(u => u.AbsolutePath switch
                {
                    "/v1/auth" => new SocialHttpResponse(200, """{"token":"t","uuid":"me","name":"Me"}"""),
                    "/v1/sync" => new SocialHttpResponse(200, """
                        {"cursor":5,"readsAt":0,"reads":{},"me":{"uuid":"me","name":"Me"},
                         "friends":[{"uuid":"b","name":"Steve","online":true},{"uuid":"c","name":"Alex","online":false}],
                         "messages":[{"id":4,"from":"b","to":"me","text":"hey","at":1}]}
                        """),
                    "/v1/messages/history" => new SocialHttpResponse(200, """{"messages":[{"id":2,"from":"c","to":"me","text":"old","at":1}],"more":false}"""),
                    _ => new SocialHttpResponse(200, """{"ok":true}"""),
                })(method, url, body, bearer, ct);
            },
            (_, _) => Task.FromResult(IdentityResult.Ok(new MinecraftIdentity("Me", FakeMojang.Uuid, "mc"))));
        using var service = new SocialService(client, () => "1.21.1");
        var online = new TaskCompletionSource<SocialSnapshot>();
        service.Changed += s => { if (s.Status == SocialStatus.Online) online.TrySetResult(s); };
        service.Start();
        var first = await online.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(1, first.Unread["b"]);

        service.SetDrawerOpen(true);
        service.SetOpenConversation("b");
        var history = new TaskCompletionSource<SocialSnapshot>();
        service.Changed += s => { if (s.ConversationWith("c").Count > 0) history.TrySetResult(s); };
        service.SetOpenConversation("c");
        var withHistory = await history.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal("old", withHistory.ConversationWith("c").Single().Text);

        await WaitUntil(() => posts.Any(p => p.Path == "/v1/messages/read"));
        var read = posts.First(p => p.Path == "/v1/messages/read");
        using var doc = JsonDocument.Parse(read.Body!);
        Assert.Equal("b", doc.RootElement.GetProperty("with").GetString());
        Assert.Equal(4, doc.RootElement.GetProperty("upTo").GetInt64());
        Assert.Contains(posts, p => p.Path == "/v1/sync" && p.Body!.Contains("\"readsSince\":0"));
    }

    private static async Task WaitUntil(Func<bool> done)
    {
        for (var i = 0; i < 200 && !done(); i++) await Task.Delay(25);
        Assert.True(done());
    }
}
