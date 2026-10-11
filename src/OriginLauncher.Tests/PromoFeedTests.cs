using OriginLauncher.App.Core.Promos;
using Xunit;

namespace OriginLauncher.Tests;

public class PromoFeedTests
{
    private static string Repo { get; } = FindRepo();

    private static string FindRepo()
    {
        var dir = AppContext.BaseDirectory;
        while (dir != null && !File.Exists(Path.Combine(dir, "OriginLauncher.sln")))
            dir = Path.GetDirectoryName(dir);
        return dir ?? throw new InvalidOperationException("repo root not found");
    }

    [Fact]
    public void LiveFeedFileParses()
    {
        // The file Will edits — a typo here would silently drop every launcher
        // to its cached/built-in copy, so CI catches it instead.
        var json = File.ReadAllText(Path.Combine(Repo, "website", "launcher", "promos.json"));
        var feed = PromoFeedParser.TryParse(json);

        Assert.NotNull(feed);
        Assert.NotEmpty(feed!.Promos);
        Assert.NotNull(feed.Network.Url);
        Assert.Equal("https", feed.Network.Url!.Scheme);
    }

    [Fact]
    public void BuiltInFeedParsesAndNeverEnablesTheAdNetwork()
    {
        var builtIn = PromoFeedLoader.BuiltIn();
        Assert.Equal(PromoFeedOrigin.BuiltIn, builtIn.Origin);
        Assert.NotEmpty(builtIn.Feed.Promos);
        Assert.False(builtIn.Feed.Network.Enabled);
    }

    [Fact]
    public void BuiltInMirrorsTheLiveFeedsPromos()
    {
        var live = PromoFeedParser.TryParse(File.ReadAllText(Path.Combine(Repo, "website", "launcher", "promos.json")))!;
        var builtIn = PromoFeedLoader.BuiltIn().Feed;
        Assert.Equal(live.Promos.Select(p => p.Id), builtIn.Promos.Select(p => p.Id));
    }

    [Theory]
    [InlineData("")]
    [InlineData("not json")]
    [InlineData("[]")]
    [InlineData("{}")]
    [InlineData("{\"schema\": 2, \"promos\": []}")]
    [InlineData("{\"schema\": \"1\", \"promos\": []}")]
    public void RejectsWhatIsNotASchema1Feed(string json) => Assert.Null(PromoFeedParser.TryParse(json));

    [Fact]
    public void SkipsMalformedAndUnknownPromosButKeepsTheRest()
    {
        var feed = PromoFeedParser.TryParse("""
            { "schema": 1, "promos": [
                { "id": "a", "kind": "link", "title": "A", "clickUrl": "https://example.com/a" },
                { "id": "b", "kind": "video", "title": "B" },
                { "id": "c", "kind": "link" },
                "nonsense",
                { "id": "a", "kind": "link", "title": "duplicate id" },
                { "kind": "link", "title": "No id" }
            ] }
            """)!;

        Assert.Equal(new[] { "a", "promo-5" }, feed.Promos.Select(p => p.Id));
        Assert.Equal("A", feed.Promos[0].Title);
    }

    [Fact]
    public void OnlyHttpsUrlsSurvive()
    {
        var feed = PromoFeedParser.TryParse("""
            { "schema": 1, "promos": [
                { "id": "a", "title": "A", "clickUrl": "http://example.com", "imageUrl": "file:///C:/x.png" },
                { "id": "b", "title": "B", "clickUrl": "https://example.com/b", "imageUrl": "https://example.com/b.png" }
            ],
              "network": { "enabled": true, "url": "javascript:alert(1)" } }
            """)!;

        Assert.Null(feed.Promos[0].ClickUrl);
        Assert.Null(feed.Promos[0].ImageUrl);
        Assert.Equal("https://example.com/b", feed.Promos[1].ClickUrl!.AbsoluteUri);
        Assert.True(feed.Network.Enabled);
        Assert.False(feed.Network.IsUsable);
    }

    [Fact]
    public void ClampsTimingsAndTextAndDefaultsMissingBlocks()
    {
        var longTitle = new string('x', 500);
        var feed = PromoFeedParser.TryParse($$"""
            { "schema": 1, "rotateSeconds": 0.5,
              "promos": [ { "id": "a", "label": "new", "title": "{{longTitle}}", "subtitle": "line one\nline two" } ],
              "discord": { "inviteCode": "abc", "refreshSeconds": 1 },
              "network": { "enabled": true, "url": "https://ads.example.com/b.html", "refreshSeconds": 5 } }
            """)!;

        Assert.Equal(PromoFeedParser.MinRotate, feed.RotateInterval);
        Assert.Equal(PromoFeedParser.MaxTitleLength, feed.Promos[0].Title.Length);
        Assert.Equal("NEW", feed.Promos[0].Label);
        Assert.Equal("line one line two", feed.Promos[0].Subtitle);
        Assert.Equal(PromoFeedParser.MinDiscordRefresh, feed.Discord.RefreshInterval);
        Assert.Equal(PromoFeedParser.MinNetworkRefresh, feed.Network.RefreshInterval);
        Assert.True(feed.Network.IsUsable);

        var bare = PromoFeedParser.TryParse("{ \"schema\": 1 }")!;
        Assert.Empty(bare.Promos);
        Assert.False(bare.Discord.HasInvite);
        Assert.False(bare.Network.Enabled);
        Assert.Equal(PromoFeedParser.DefaultRotate, bare.RotateInterval);
    }

    [Fact]
    public void PlaceholderInviteMeansNoDiscordCard()
    {
        var feed = PromoFeedParser.TryParse("""
            { "schema": 1, "promos": [ { "id": "discord", "kind": "discord" }, { "id": "x", "title": "X" } ],
              "discord": { "inviteCode": "REPLACE_ME" } }
            """)!;

        Assert.False(feed.Discord.HasInvite);
        // Even if Discord info were somehow present, no invite → no card.
        Assert.Equal(new[] { "x" }, PromoRotation.Playable(feed, discordReady: true).Select(p => p.Id));
    }
}
