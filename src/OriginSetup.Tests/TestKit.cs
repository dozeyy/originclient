using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Xunit;

namespace OriginSetup.Tests;

// Everything the download-path tests share: the repo root, a sample launcher
// tree and its zip, and FakeServer — an in-memory HTTP server (an
// HttpMessageHandler), so no test ever touches the network.
internal static class TestKit
{
    public static string Repo { get; } = FindRepo();

    private static string FindRepo()
    {
        var dir = AppContext.BaseDirectory;
        while (dir != null && !File.Exists(Path.Combine(dir, "OriginLauncher.sln")))
            dir = Path.GetDirectoryName(dir);
        return dir ?? throw new InvalidOperationException("repo root not found");
    }

    public static string Temp(string prefix) => Directory.CreateTempSubdirectory(prefix).FullName;

    /// <summary>A small stand-in for the launcher's publish folder.</summary>
    public static string MakeLauncherTree()
    {
        var src = Temp("src");
        var rnd = new Random(11);
        void Put(string rel, int size)
        {
            var p = Path.Combine(src, rel.Replace('/', Path.DirectorySeparatorChar));
            Directory.CreateDirectory(Path.GetDirectoryName(p)!);
            var b = new byte[size];
            rnd.NextBytes(b);
            for (int i = 0; i < size; i += 3) b[i] = (byte)(i % 7); // part-compressible
            File.WriteAllBytes(p, b);
        }
        Put("OriginLauncher.App.exe", 900_000);
        Put("Bundled/OriginClient/originclient-1.21.1.jar", 300_000);
        Put("Bundled/OriginClient/originclient-1.20.jar", 1);
        Put("ThirdParty/Monocraft-LICENSE.txt", 0); // empty file
        return src;
    }

    /// <summary>Zips a tree like CI does. backslashes = Windows PowerShell's Compress-Archive entry names.</summary>
    public static string Zip(string tree, bool backslashes = false)
    {
        var zip = Path.Combine(Temp("zip"), "OriginLauncher-win-x64.zip");
        using (var archive = ZipFile.Open(zip, ZipArchiveMode.Create))
        {
            foreach (var file in Directory.GetFiles(tree, "*", SearchOption.AllDirectories).OrderBy(f => f, StringComparer.Ordinal))
            {
                var rel = Path.GetRelativePath(tree, file).Replace(Path.DirectorySeparatorChar, '/');
                archive.CreateEntryFromFile(file, backslashes ? rel.Replace('/', '\\') : rel);
            }
        }
        return zip;
    }

    public static void AssertSameTree(string expected, string actual)
    {
        static List<string> Files(string root) => Directory.GetFiles(root, "*", SearchOption.AllDirectories)
            .Select(p => Path.GetRelativePath(root, p)).OrderBy(p => p, StringComparer.Ordinal).ToList();
        var a = Files(expected);
        Assert.Equal(a, Files(actual));
        foreach (var rel in a)
            Assert.True(File.ReadAllBytes(Path.Combine(expected, rel)).AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(actual, rel))), rel);
    }
}

/// <summary>
/// In-memory HTTP server. Routes map a URL to a responder; files are served
/// with real Range support, and each can be told to drop the connection
/// after some bytes (once, or every time) or to stall.
/// </summary>
internal sealed class FakeServer : HttpMessageHandler
{
    private readonly Dictionary<string, Func<HttpRequestMessage, HttpResponseMessage>> _routes = new();
    public List<HttpRequestMessage> Requests { get; } = new();

    public HttpClient Client() => OriginSetup.Engine.Download.CreateClient(this);

    public FakeServer Route(string url, Func<HttpRequestMessage, HttpResponseMessage> respond)
    {
        _routes[url] = respond;
        return this;
    }

    public FakeServer Json(string url, string json) => Route(url, req => new HttpResponseMessage(HttpStatusCode.OK)
    {
        Content = new StringContent(json),
        RequestMessage = req,
    });

    public FakeServer Status(string url, HttpStatusCode code) => Route(url, req => new HttpResponseMessage(code) { RequestMessage = req });

    /// <summary>Answers <paramref name="url"/> as if it redirected to <paramref name="finalUrl"/>.</summary>
    public FakeServer Redirect(string url, string finalUrl) => Route(url, req => new HttpResponseMessage(HttpStatusCode.OK)
    {
        Content = new StringContent("<html></html>"),
        RequestMessage = new HttpRequestMessage(HttpMethod.Get, finalUrl),
    });

    /// <param name="dropAfter">Cut each response after this many bytes of the file (absolute offset).</param>
    /// <param name="drops">How many responses get cut; later ones are whole.</param>
    /// <param name="honourRange">False = a server that always sends the whole file.</param>
    /// <param name="stallAt">Stop sending (but keep the connection open) at this offset, once.</param>
    public FakeServer File(string url, byte[] data, long? dropAfter = null, int drops = int.MaxValue,
                           bool honourRange = true, long? stallAt = null, bool sendLength = true)
    {
        int dropped = 0, stalled = 0;
        return Route(url, req =>
        {
            long from = 0;
            var range = req.Headers.Range?.Ranges.FirstOrDefault();
            bool partial = honourRange && range?.From is long f && f > 0;
            if (partial) from = range!.From!.Value;
            long? cut = null;
            if (dropAfter is long d && dropped < drops) { dropped++; cut = d; }
            long? stall = null;
            if (stallAt is long s && stalled == 0) { stalled++; stall = s; }

            var body = new ServeStream(data, from, cut, stall);
            var content = new StreamContent(body);
            if (sendLength) content.Headers.ContentLength = data.Length - from;
            var response = new HttpResponseMessage(partial ? HttpStatusCode.PartialContent : HttpStatusCode.OK)
            {
                Content = content,
                RequestMessage = req,
            };
            if (partial) content.Headers.ContentRange = new System.Net.Http.Headers.ContentRangeHeaderValue(from, data.Length - 1, data.Length);
            return response;
        });
    }

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        lock (Requests) Requests.Add(request);
        ct.ThrowIfCancellationRequested();
        var url = request.RequestUri!.ToString();
        if (_routes.TryGetValue(url, out var respond)) return Task.FromResult(respond(request));
        throw new HttpRequestException("No such host is known. (" + request.RequestUri!.Host + ")");
    }

    // Serves data[from..], failing like a dropped connection at `cut`, or
    // going silent at `stall` (until the reader gives up).
    private sealed class ServeStream : Stream
    {
        private readonly byte[] _data;
        private long _pos;
        private readonly long? _cut, _stall;

        public ServeStream(byte[] data, long from, long? cut, long? stall)
        {
            _data = data;
            _pos = from;
            _cut = cut;
            _stall = stall;
        }

        public override int Read(byte[] buffer, int offset, int count) =>
            ReadAsync(buffer, offset, count, CancellationToken.None).GetAwaiter().GetResult();

        public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct)
        {
            if (_stall is long s && _pos >= s) await Task.Delay(Timeout.Infinite, ct);
            if (_cut is long c && _pos >= c) throw new IOException("The connection was reset.");
            long limit = _data.Length;
            if (_cut is long c2 && c2 > _pos) limit = Math.Min(limit, c2);
            if (_stall is long s2 && s2 > _pos) limit = Math.Min(limit, s2);
            int n = (int)Math.Min(Math.Min(count, 8192), limit - _pos);
            if (n <= 0) return 0;
            Array.Copy(_data, _pos, buffer, offset, n);
            _pos += n;
            return n;
        }

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => _pos; set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
