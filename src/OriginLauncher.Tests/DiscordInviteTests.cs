using OriginLauncher.App.Core.Promos;
using Xunit;

namespace OriginLauncher.Tests;

public class DiscordInviteTests
{
    private const string SampleInvite = """
        {
          "type": 0, "code": "origin",
          "guild": { "id": "1234567890123456789", "name": "Origin Client", "icon": "a1b2c3d4e5f6", "features": [] },
          "approximate_member_count": 15234,
          "approximate_presence_count": 1873
        }
        """;

    [Theory]
    [InlineData("abc123", "abc123")]
    [InlineData("  abc123  ", "abc123")]
    [InlineData("origin-client", "origin-client")]
    [InlineData("discord.gg/abc123", "abc123")]
    [InlineData("https://discord.gg/abc123", "abc123")]
    [InlineData("https://discord.gg/abc123/", "abc123")]
    [InlineData("https://www.discord.com/invite/abc123?event=1", "abc123")]
    [InlineData("http://discordapp.com/invite/abc123", "abc123")]
    [InlineData("REPLACE_ME", null)]
    [InlineData("", null)]
    [InlineData(null, null)]
    [InlineData("a", null)]
    [InlineData("abc/../def", null)]
    [InlineData("https://evil.example/abc123", null)]
    public void NormalizesInviteCodes(string? raw, string? expected) =>
        Assert.Equal(expected, DiscordInvite.NormalizeCode(raw));

    [Fact]
    public void ParsesServerInfoFromThePublicInvite()
    {
        var info = DiscordInvite.ParseInvite(SampleInvite)!;

        Assert.Equal("Origin Client", info.Name);
        Assert.Equal("https://cdn.discordapp.com/icons/1234567890123456789/a1b2c3d4e5f6.png?size=128",
            info.IconUrl!.AbsoluteUri);
        Assert.Equal(1873, info.Online);
        Assert.Equal(15234, info.Members);
        Assert.Equal("1,873 online · 15.2K members", DiscordInvite.DescribeCounts(info));
    }

    [Fact]
    public void ToleratesMissingIconAndCounts()
    {
        var info = DiscordInvite.ParseInvite("""{ "guild": { "id": "123456", "name": "Origin", "icon": null } }""")!;
        Assert.Null(info.IconUrl);
        Assert.Null(info.Online);
        Assert.Equal("Join the community", DiscordInvite.DescribeCounts(info));
    }

    [Theory]
    [InlineData("""{ "code": 10006, "message": "Unknown Invite" }""")]
    [InlineData("""{ "guild": { "id": "1" } }""")]
    [InlineData("garbage")]
    public void NonServerInvitesParseToNull(string json) => Assert.Null(DiscordInvite.ParseInvite(json));

    [Fact]
    public void IconHashIsNeverTrustedIntoTheUrl()
    {
        var info = DiscordInvite.ParseInvite("""{ "guild": { "id": "123456", "name": "x", "icon": "../../evil" } }""")!;
        Assert.Null(info.IconUrl);
    }

    [Theory]
    [InlineData(0, "0")]
    [InlineData(987, "987")]
    [InlineData(9_999, "9,999")]
    [InlineData(12_345, "12.3K")]
    [InlineData(999_999, "999.9K")]
    [InlineData(1_250_000, "1.2M")]
    public void FormatsCountsCompactly(int value, string expected) =>
        Assert.Equal(expected, DiscordInvite.FormatCount(value));

    [Fact]
    public async Task LookupMapsStatusesToCardBehaviour()
    {
        Uri? asked = null;
        HttpTextGet ok = (url, _, _) => { asked = url; return Task.FromResult(new HttpTextResult(200, SampleInvite)); };
        var found = await DiscordInvite.LookupAsync(ok, "abc123", CancellationToken.None);
        Assert.Equal(DiscordLookupStatus.Ok, found.Status);
        Assert.Equal("https://discord.com/api/v10/invites/abc123?with_counts=true", asked!.AbsoluteUri);

        HttpTextGet notFound = (_, _, _) => Task.FromResult(new HttpTextResult(404, null));
        Assert.Equal(DiscordLookupStatus.InvalidInvite,
            (await DiscordInvite.LookupAsync(notFound, "abc123", CancellationToken.None)).Status);

        foreach (var status in new[] { 0, 429, 500, 503 })
        {
            HttpTextGet down = (_, _, _) => Task.FromResult(new HttpTextResult(status, null));
            Assert.Equal(DiscordLookupStatus.Unavailable,
                (await DiscordInvite.LookupAsync(down, "abc123", CancellationToken.None)).Status);
        }
    }

    [Fact]
    public void JoinUrlIsTheDiscordGgLink() =>
        Assert.Equal("https://discord.gg/abc123", DiscordInvite.JoinUrl("abc123").AbsoluteUri);
}
