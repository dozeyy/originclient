using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// Download uses static timing knobs, so every class that downloads shares one
// collection (never run in parallel with each other).
[CollectionDefinition("download")]
public class DownloadCollection { }

[Collection("download")]
public class DownloadTests
{
    private const string Url = "https://example.test/OriginLauncher-win-x64.zip";

    public DownloadTests()
    {
        Download.RetryDelay = TimeSpan.Zero;
        Download.StallTimeout = TimeSpan.FromSeconds(30);
    }

    private static byte[] Data(int n = 1_000_000)
    {
        var b = new byte[n];
        new Random(3).NextBytes(b);
        return b;
    }

    private static string Sha(byte[] b) => Convert.ToHexString(SHA256.HashData(b)).ToLowerInvariant();

    private static string Target() => Path.Combine(TestKit.Temp("dl"), "OriginLauncher-win-x64.zip");

    [Fact]
    public async Task DownloadsVerifiesAndReportsProgress()
    {
        var data = Data();
        var server = new FakeServer().File(Url, data);
        var path = Target();
        long lastGot = 0; long? lastTotal = null; int ticks = 0;
        await Download.ToFileAsync(server.Client(), Url, path, data.Length, Sha(data),
            (got, total) => { Assert.True(got >= lastGot); lastGot = got; lastTotal = total; ticks++; }, CancellationToken.None);
        Assert.Equal(data, File.ReadAllBytes(path));
        Assert.Equal(data.Length, lastGot);
        Assert.Equal(data.Length, lastTotal);
        Assert.True(ticks > 10, "progress should tick as bytes arrive");
    }

    [Fact]
    public async Task ADroppedConnectionResumesWhereItStopped()
    {
        var data = Data();
        var server = new FakeServer().File(Url, data, dropAfter: 400_000, drops: 1);
        var path = Target();
        await Download.ToFileAsync(server.Client(), Url, path, null, Sha(data), null, CancellationToken.None);
        Assert.Equal(data, File.ReadAllBytes(path));
        Assert.Equal(2, server.Requests.Count);
        Assert.Equal(400_000, server.Requests[1].Headers.Range!.Ranges.Single().From);
    }

    [Fact]
    public async Task AServerThatWontResumeRestartsCleanly()
    {
        var data = Data();
        var server = new FakeServer().File(Url, data, dropAfter: 300_000, drops: 1, honourRange: false);
        var path = Target();
        await Download.ToFileAsync(server.Client(), Url, path, data.Length, Sha(data), null, CancellationToken.None);
        Assert.Equal(data, File.ReadAllBytes(path));
    }

    [Fact]
    public async Task AConnectionThatKeepsDroppingGivesUpWithAFriendlyMessage()
    {
        var data = Data();
        var server = new FakeServer().File(Url, data, dropAfter: 200_000);
        var path = Target();
        var ex = await Assert.ThrowsAsync<DownloadException>(() =>
            Download.ToFileAsync(server.Client(), Url, path, null, null, null, CancellationToken.None));
        Assert.Equal(DownloadException.Dropped, ex.Message);
        Assert.Equal(Download.MaxAttempts, server.Requests.Count);
        Assert.False(File.Exists(path), "a failed download leaves no file");
    }

    [Fact]
    public async Task AStalledConnectionIsRetried()
    {
        Download.StallTimeout = TimeSpan.FromMilliseconds(300);
        var data = Data();
        var server = new FakeServer().File(Url, data, stallAt: 500_000);
        var path = Target();
        await Download.ToFileAsync(server.Client(), Url, path, data.Length, Sha(data), null, CancellationToken.None);
        Assert.Equal(data, File.ReadAllBytes(path));
        Assert.Equal(2, server.Requests.Count);
    }

    [Fact]
    public async Task NoInternetSaysSo()
    {
        var path = Target();
        var ex = await Assert.ThrowsAsync<DownloadException>(() =>
            Download.ToFileAsync(new FakeServer().Client(), Url, path, null, null, null, CancellationToken.None));
        Assert.Equal(DownloadException.CantConnect, ex.Message);
        Assert.False(File.Exists(path));
    }

    [Fact]
    public async Task MissingFileIsAServerProblemAndNotRetried()
    {
        var server = new FakeServer().Status(Url, HttpStatusCode.NotFound);
        var ex = await Assert.ThrowsAsync<DownloadException>(() =>
            Download.ToFileAsync(server.Client(), Url, Target(), null, null, null, CancellationToken.None));
        Assert.Equal(DownloadException.ServerProblem, ex.Message);
        Assert.Single(server.Requests);
    }

    [Fact]
    public async Task ServerErrorsAreRetriedThenReported()
    {
        var server = new FakeServer().Status(Url, HttpStatusCode.ServiceUnavailable);
        var ex = await Assert.ThrowsAsync<DownloadException>(() =>
            Download.ToFileAsync(server.Client(), Url, Target(), null, null, null, CancellationToken.None));
        Assert.Equal(DownloadException.ServerProblem, ex.Message);
        Assert.Equal(Download.MaxAttempts, server.Requests.Count);
    }

    [Fact]
    public async Task WrongHashIsDamaged()
    {
        var data = Data();
        var server = new FakeServer().File(Url, data);
        var path = Target();
        var ex = await Assert.ThrowsAsync<DownloadException>(() =>
            Download.ToFileAsync(server.Client(), Url, path, null, new string('0', 64), null, CancellationToken.None));
        Assert.Equal(DownloadException.Damaged, ex.Message);
        Assert.False(File.Exists(path));
    }

    [Fact]
    public async Task SizeDisagreeingWithTheFeedIsDamaged()
    {
        var data = Data();
        var server = new FakeServer().File(Url, data);
        var ex = await Assert.ThrowsAsync<DownloadException>(() =>
            Download.ToFileAsync(server.Client(), Url, Target(), data.Length + 1, null, null, CancellationToken.None));
        Assert.Equal(DownloadException.Damaged, ex.Message);
    }

    [Fact]
    public async Task CancellingStopsAndCleansUp()
    {
        var data = Data(4_000_000);
        var server = new FakeServer().File(Url, data);
        var path = Target();
        using var cts = new CancellationTokenSource();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            Download.ToFileAsync(server.Client(), Url, path, null, null, (got, _) => { if (got > 100_000) cts.Cancel(); }, cts.Token));
        Assert.False(File.Exists(path));
    }
}
