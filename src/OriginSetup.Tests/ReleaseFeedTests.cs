using System;
using System.Net;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

[Collection("download")]
public class ReleaseFeedTests
{
    private const string Sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // Trimmed from a real releases/latest answer (nested objects, other assets, notes).
    private static string ApiJson(string tag = "launcher-v1.0.40", string? digest = "sha256:" + Sha, string asset = "OriginLauncher-win-x64.zip") => $$"""
        {
          "url": "https://api.github.com/repos/dozeyy/originclient/releases/1",
          "tag_name": "{{tag}}",
          "name": "Origin Launcher 1.0.40",
          "draft": false,
          "author": { "login": "dozeyy", "id": 1 },
          "body": "## What's new\n- \"quoted\" things, braces { } and [ ]",
          "assets": [
            { "name": "OriginLauncher-Setup.exe", "size": 900000, "digest": null,
              "browser_download_url": "https://github.com/dozeyy/originclient/releases/download/{{tag}}/OriginLauncher-Setup.exe",
              "uploader": { "login": "github-actions[bot]" } },
            { "name": "{{asset}}", "size": 132000000, "digest": {{(digest == null ? "null" : "\"" + digest + "\"")}},
              "content_type": "application/zip",
              "browser_download_url": "https://github.com/dozeyy/originclient/releases/download/{{tag}}/{{asset}}" }
          ]
        }
        """;

    [Fact]
    public void ApiAnswerGivesVersionUrlSizeAndHash()
    {
        var r = ReleaseFeed.ParseApi(Encoding.UTF8.GetBytes(ApiJson()))!;
        Assert.Equal("1.0.40", r.Version);
        Assert.Equal("https://github.com/dozeyy/originclient/releases/download/launcher-v1.0.40/OriginLauncher-win-x64.zip", r.Url);
        Assert.Equal(132000000, r.Size);
        Assert.Equal(Sha, r.Sha256);
    }

    [Fact]
    public void OlderReleasesWithoutADigestStillWork()
    {
        var r = ReleaseFeed.ParseApi(Encoding.UTF8.GetBytes(ApiJson(digest: null)))!;
        Assert.Null(r.Sha256);
        Assert.Equal("1.0.40", r.Version);
    }

    [Theory]
    [InlineData("v1.0.40", "OriginLauncher-win-x64.zip")]         // not a launcher tag
    [InlineData("launcher-vNEXT", "OriginLauncher-win-x64.zip")]  // not a version
    [InlineData("launcher-v1.0.40", "Something-else.zip")]        // no launcher zip
    public void NonLauncherReleasesAreIgnored(string tag, string asset) =>
        Assert.Null(ReleaseFeed.ParseApi(Encoding.UTF8.GetBytes(ApiJson(tag, asset: asset))));

    [Fact]
    public void GarbageIsIgnored()
    {
        Assert.Null(ReleaseFeed.ParseApi(Encoding.UTF8.GetBytes("<html>rate limited</html>")));
        Assert.Null(ReleaseFeed.ParseApi(Encoding.UTF8.GetBytes("{\"message\":\"API rate limit exceeded\"}")));
    }

    [Fact]
    public void ReleasePageRedirectNamesTheTag()
    {
        Assert.Equal("launcher-v1.0.40", ReleaseFeed.TagFromReleasePage(new Uri("https://github.com/dozeyy/originclient/releases/tag/launcher-v1.0.40")));
        Assert.Null(ReleaseFeed.TagFromReleasePage(new Uri("https://github.com/dozeyy/originclient/releases")));
        Assert.Null(ReleaseFeed.TagFromReleasePage(new Uri("https://github.com/dozeyy/originclient/releases/tag/v2")));
    }

    [Fact]
    public async Task UsesTheApiWhenItAnswers()
    {
        var server = new FakeServer().Json(ReleaseFeed.ApiUrl, ApiJson());
        var r = await ReleaseFeed.FindLatestAsync(server.Client(), CancellationToken.None);
        Assert.Equal("1.0.40", r.Version);
        Assert.Equal(Sha, r.Sha256);
    }

    [Fact]
    public async Task RateLimitedApiFallsBackToTheReleasesPage()
    {
        var server = new FakeServer()
            .Status(ReleaseFeed.ApiUrl, HttpStatusCode.Forbidden)
            .Redirect(ReleaseFeed.LatestPageUrl, "https://github.com/dozeyy/originclient/releases/tag/launcher-v1.0.41");
        var r = await ReleaseFeed.FindLatestAsync(server.Client(), CancellationToken.None);
        Assert.Equal("1.0.41", r.Version);
        // Pinned to that release, so a newer one published mid-download can't mix in.
        Assert.Equal(ReleaseFeed.AssetUrl("launcher-v1.0.41"), r.Url);
        Assert.Null(r.Size);
    }

    [Fact]
    public async Task NoInternetIsWordedForThePlayer()
    {
        var server = new FakeServer(); // every host unknown
        var ex = await Assert.ThrowsAsync<DownloadException>(() => ReleaseFeed.FindLatestAsync(server.Client(), CancellationToken.None));
        Assert.Equal(DownloadException.CantConnect, ex.Message);
    }

    [Fact]
    public async Task NoLauncherReleaseAnywhereIsAServerProblem()
    {
        var server = new FakeServer()
            .Json(ReleaseFeed.ApiUrl, "{}")
            .Redirect(ReleaseFeed.LatestPageUrl, "https://github.com/dozeyy/originclient/releases");
        var ex = await Assert.ThrowsAsync<DownloadException>(() => ReleaseFeed.FindLatestAsync(server.Client(), CancellationToken.None));
        Assert.Equal(DownloadException.ServerProblem, ex.Message);
    }
}
