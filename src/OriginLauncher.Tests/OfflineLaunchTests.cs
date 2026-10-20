using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using OriginLauncher.App.Core.Versions;
using Xunit;

namespace OriginLauncher.Tests;

// Playing with no internet: MojangMetaCache keeps a disk copy of Java's metadata
// that only ever holds the real file, and OfflineLaunch tells "no connection"
// apart from a server that answered, so Home can say "you're offline".
public class OfflineLaunchTests
{
    private const string AllJson = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    private const string GoodJson = "{\"windows-x64\":{}}";
    private const string BlockPage = "<html><body>Blocked by your web shield</body></html>";

    private readonly string _dir = Directory.CreateTempSubdirectory("mojang-meta").FullName;

    // Answers every request with the given body/status, or throws when body is null.
    private sealed class FakeNetwork : HttpMessageHandler
    {
        public string? Body;
        public HttpStatusCode Status = HttpStatusCode.OK;
        public int Calls;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Calls++;
            if (Body == null) throw new HttpRequestException("No connection could be made (127.0.0.1:9)", new SocketException(10061));
            return Task.FromResult(new HttpResponseMessage(Status) { Content = new StringContent(Body) });
        }
    }

    private async Task<string> Get(FakeNetwork net, string url, bool diskFirst = false)
    {
        using var client = new HttpClient(new MojangMetaCache(_dir, net, diskFirst));
        return await client.GetStringAsync(url);
    }

    private string SavedFile(string url) =>
        Path.Combine(_dir, Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(new Uri(url).AbsoluteUri))).ToLowerInvariant() + ".json");

    [Fact]
    public async Task AJsonAnswerIsSavedAndServedWhenTheNetworkIsGone()
    {
        var net = new FakeNetwork { Body = GoodJson };
        Assert.Equal(GoodJson, await Get(net, AllJson));
        net.Body = null;
        Assert.Equal(GoodJson, await Get(net, AllJson));
    }

    [Fact]
    public async Task AnHtmlBlockPageWithStatus200IsNeverSavedOverTheGoodCopy()
    {
        var net = new FakeNetwork { Body = GoodJson };
        await Get(net, AllJson);

        net.Body = BlockPage;
        // The good copy answers instead of the block page, and stays on disk.
        Assert.Equal(GoodJson, await Get(net, AllJson));
        Assert.Equal(GoodJson, File.ReadAllText(SavedFile(AllJson)));

        net.Body = null;
        Assert.Equal(GoodJson, await Get(net, AllJson, diskFirst: true));
    }

    [Fact]
    public async Task AnHtmlBlockPageWithNoSavedCopyIsNotSaved()
    {
        var net = new FakeNetwork { Body = BlockPage };
        Assert.Equal(BlockPage, await Get(net, AllJson));
        Assert.False(File.Exists(SavedFile(AllJson)));
    }

    [Fact]
    public async Task ACorruptCopyOnDiskFallsBackToTheNetworkOnTheFastPath()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(SavedFile(AllJson), BlockPage);
        var net = new FakeNetwork { Body = GoodJson };

        Assert.Equal(GoodJson, await Get(net, AllJson, diskFirst: true));
        Assert.Equal(1, net.Calls);
        Assert.Equal(GoodJson, File.ReadAllText(SavedFile(AllJson)));
    }

    [Fact]
    public async Task AValidCopyOnDiskAnswersTheFastPathWithoutTheNetwork()
    {
        var net = new FakeNetwork { Body = GoodJson };
        await Get(net, AllJson);
        await Get(net, AllJson, diskFirst: true);
        Assert.Equal(1, net.Calls);
    }

    [Fact]
    public void AContentAddressedFileMustMatchItsSha1()
    {
        var body = Encoding.UTF8.GetBytes(GoodJson);
        var sha = Convert.ToHexString(SHA1.HashData(body)).ToLowerInvariant();
        Assert.True(MojangMetaCache.IsValid(new Uri($"https://piston-meta.mojang.com/v1/packages/{sha}/manifest.json"), body));
        Assert.False(MojangMetaCache.IsValid(new Uri($"https://piston-meta.mojang.com/v1/packages/{new string('0', 40)}/manifest.json"), body));
        Assert.False(MojangMetaCache.IsValid(new Uri(AllJson), Array.Empty<byte>()));
    }

    [Fact]
    public void NoConnectionIsOfflineWhereverItIsWrapped()
    {
        var refused = new HttpRequestException("refused", new SocketException(10061));
        Assert.True(OfflineLaunch.IsNoConnection(refused, default));
        Assert.True(OfflineLaunch.IsNoConnection(new InvalidOperationException("download failed", refused), default));
        Assert.True(OfflineLaunch.IsNoConnection(new AggregateException(new IOException("x"), refused), default));
        Assert.True(OfflineLaunch.IsNoConnection(new TaskCanceledException("timeout", new TimeoutException()), default));
    }

    [Fact]
    public void AServerThatAnsweredOrThePlayersCancelIsNotOffline()
    {
        Assert.False(OfflineLaunch.IsNoConnection(new HttpRequestException("404", null, HttpStatusCode.NotFound), default));
        Assert.False(OfflineLaunch.IsNoConnection(new InvalidOperationException("bad json"), default));
        Assert.False(OfflineLaunch.IsNoConnection(new TaskCanceledException("cancelled"), default));
        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        Assert.False(OfflineLaunch.IsNoConnection(new HttpRequestException("refused", new SocketException(10061)), cancelled.Token));
    }

    [Fact]
    public void TheOfflineMessageNamesTheVersionInPlainWords()
    {
        var e = new OfflineInstallException("1.21.1", new HttpRequestException("refused"));
        Assert.StartsWith("You're offline and Minecraft 1.21.1 isn't fully downloaded yet.", e.Message);
        Assert.IsType<HttpRequestException>(e.InnerException);
    }
}
