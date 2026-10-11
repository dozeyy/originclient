using OriginLauncher.App.Core.Promos;
using Xunit;

namespace OriginLauncher.Tests;

public class PromoFeedLoaderTests
{
    private static readonly Uri Live = new("https://live.example/launcher/promos.json");
    private static readonly Uri Repo = new("https://raw.example/website/launcher/promos.json");

    private static string Feed(string title) =>
        $$"""{ "schema": 1, "promos": [ { "id": "a", "title": "{{title}}" } ] }""";

    private static string CachePath() =>
        Path.Combine(Directory.CreateTempSubdirectory("promos").FullName, "cache", "promos.json");

    // A fake network: each URL answers from the map; anything else is "offline".
    private static HttpTextGet Net(Dictionary<Uri, HttpTextResult> answers, List<Uri>? log = null) =>
        (url, _, _) =>
        {
            log?.Add(url);
            return Task.FromResult(answers.TryGetValue(url, out var r) ? r : new HttpTextResult(0, null));
        };

    [Fact]
    public void ProductionSourcesAreTheLiveSiteThenRawGitHub()
    {
        Assert.Equal(new[] { PromoFeedLoader.LiveUrl, PromoFeedLoader.RepoUrl }, PromoFeedLoader.Sources);
        Assert.Equal("https://originclient.org/launcher/promos.json", PromoFeedLoader.LiveUrl.AbsoluteUri);
        Assert.Equal("https://raw.githubusercontent.com/dozeyy/originclient/main/website/launcher/promos.json",
            PromoFeedLoader.RepoUrl.AbsoluteUri);
    }

    [Fact]
    public async Task LiveSiteWinsAndIsCached()
    {
        var cache = CachePath();
        var log = new List<Uri>();
        var loader = new PromoFeedLoader(Net(new()
        {
            [Live] = new(200, Feed("live")),
            [Repo] = new(200, Feed("repo")),
        }, log), cache, new[] { Live, Repo });

        var result = await loader.ResolveAsync(CancellationToken.None);

        Assert.Equal(PromoFeedOrigin.Remote, result.Origin);
        Assert.Equal("live", result.Feed.Promos[0].Title);
        Assert.Equal(new[] { Live }, log); // the fallback isn't even asked
        Assert.Equal(Feed("live"), File.ReadAllText(cache));
    }

    [Fact]
    public async Task FallsBackToRawGitHubWhenTheSiteIsDownOrServesGarbage()
    {
        foreach (var liveAnswer in new HttpTextResult[] { new(0, null), new(404, null), new(200, "<html>oops</html>") })
        {
            var loader = new PromoFeedLoader(Net(new()
            {
                [Live] = liveAnswer,
                [Repo] = new(200, Feed("repo")),
            }), CachePath(), new[] { Live, Repo });

            var result = await loader.ResolveAsync(CancellationToken.None);
            Assert.Equal(PromoFeedOrigin.Remote, result.Origin);
            Assert.Equal("repo", result.Feed.Promos[0].Title);
        }
    }

    [Fact]
    public async Task OfflineUsesTheLastGoodCacheThenTheBuiltIn()
    {
        var cache = CachePath();
        var online = new PromoFeedLoader(Net(new() { [Live] = new(200, Feed("cached")) }), cache, new[] { Live, Repo });
        await online.ResolveAsync(CancellationToken.None);

        var offline = new PromoFeedLoader(Net(new()), cache, new[] { Live, Repo });
        Assert.Null(await offline.FetchRemoteAsync(CancellationToken.None));
        var fromCache = await offline.ResolveAsync(CancellationToken.None);
        Assert.Equal(PromoFeedOrigin.Cache, fromCache.Origin);
        Assert.Equal("cached", fromCache.Feed.Promos[0].Title);

        File.WriteAllText(cache, "{ torn");
        var fromBuiltIn = await offline.ResolveAsync(CancellationToken.None);
        Assert.Equal(PromoFeedOrigin.BuiltIn, fromBuiltIn.Origin);

        var noCache = new PromoFeedLoader(Net(new()), CachePath(), new[] { Live, Repo });
        Assert.Equal(PromoFeedOrigin.BuiltIn, noCache.LoadLocal().Origin);
    }

    [Fact]
    public async Task ABadRemoteNeverOverwritesTheCache()
    {
        var cache = CachePath();
        await new PromoFeedLoader(Net(new() { [Live] = new(200, Feed("good")) }), cache, new[] { Live })
            .ResolveAsync(CancellationToken.None);

        await new PromoFeedLoader(Net(new() { [Live] = new(200, "{ \"schema\": 99 }") }), cache, new[] { Live })
            .ResolveAsync(CancellationToken.None);

        Assert.Equal(Feed("good"), File.ReadAllText(cache));
    }
}
