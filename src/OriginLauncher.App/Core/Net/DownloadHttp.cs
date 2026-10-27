using System.IO;
using System.Net.Http;

namespace OriginLauncher.App.Core.Net;

/// <summary>
/// The HttpClient every install download goes through (game files, Forge, OptiFine, perf mods, Fabric API, Java).
///
/// WHY: a plain HttpClient has no limit on a response BODY that stops arriving. HttpClient.Timeout (100 s) only
/// covers the wait for headers when a download streams (ResponseHeadersRead), and CmlLib's file download reads the
/// body with no cancellation token at all (its "ReadTimeout = 10000" is skipped because the response stream's
/// CanTimeout is false). So one connection that went quiet mid-file (a dropped Wi-Fi packet, a CDN edge that hangs)
/// waited forever: the install counter froze one file short ("967 files, the last one won't go") and only
/// restarting the launcher got past it (Will, 2026-10-27; reproduced in DownloadStallTests).
///
/// Here every response body is read through <see cref="StallGuardStream"/>: no bytes for <see cref="StallTimeout"/>
/// = the read fails with a <see cref="DownloadStalledException"/>, and the stuck connection is aborted. Headers that
/// never come fail the same way after <see cref="HeaderTimeout"/>. The caller then retries, tries its next mirror, or
/// shows the Play failure with a Retry — never a frozen counter.
/// </summary>
public static class DownloadHttp
{
    /// <summary>No bytes for this long on a body that is still open = a stalled download.</summary>
    public static readonly TimeSpan StallTimeout = TimeSpan.FromSeconds(20);

    /// <summary>No response headers for this long = a dead host (mirror chains move on instead of waiting 100 s).</summary>
    public static readonly TimeSpan HeaderTimeout = TimeSpan.FromSeconds(30);

    /// <summary>A new client for downloads. One per installer, kept for the process (it pools connections).</summary>
    public static HttpClient Create(string? userAgent = null) =>
        Create(new SocketsHttpHandler
        {
            // A host that never answers the TCP handshake fails fast instead of riding the OS default (~21 s per address).
            ConnectTimeout = TimeSpan.FromSeconds(15),
            // CDN edges move; a pooled connection is replaced now and then so a stale one is not reused forever.
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            // MaxConnectionsPerServer stays unlimited (the default): the header deadline would otherwise also count
            // a request's wait for a free connection, and CmlLib runs max(24, cores) downloads at once.
        }, userAgent, StallTimeout, HeaderTimeout);

    /// <summary>Test seam: the same guard over any handler, with short timeouts.</summary>
    public static HttpClient Create(HttpMessageHandler inner, string? userAgent, TimeSpan stall, TimeSpan headers)
    {
        var client = new HttpClient(new StallGuardHandler(stall, headers) { InnerHandler = inner });
        if (userAgent != null) client.DefaultRequestHeaders.UserAgent.ParseAdd(userAgent);
        return client;
    }
}

/// <summary>
/// A download that stalled (no data, or no headers, within the guard's window). Deliberately the exact shape of
/// HttpClient's own timeout — a <see cref="TaskCanceledException"/> whose inner exception is a
/// <see cref="TimeoutException"/> — so every fail-soft filter that already treated "HttpClient timed out" as a
/// network failure (Temurin falls back to jre-legacy, Fabric API / voice chat skip the mod, the Fabric loader uses
/// the installed one, MojangMetaCache serves its disk copy, OptiFine tries its next mirror) handles a stall the same
/// way, and none mistakes it for the player's cancel (those filters all check the caller's token). PlayFailure reads
/// it as "A download took too long".
/// </summary>
public sealed class DownloadStalledException : TaskCanceledException
{
    public DownloadStalledException(string message, Exception? cause)
        : base(message, new TimeoutException(message, cause)) { }
}

/// <summary>Puts a header deadline on every request and a stall guard on every response body.</summary>
public sealed class StallGuardHandler : DelegatingHandler
{
    private readonly TimeSpan _stall;
    private readonly TimeSpan _headers;

    public StallGuardHandler(TimeSpan stall, TimeSpan headers)
    {
        _stall = stall;
        _headers = headers;
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(_headers);
        HttpResponseMessage response;
        try
        {
            response = await base.SendAsync(request, deadline.Token).ConfigureAwait(false);
        }
        catch (Exception e) when (deadline.IsCancellationRequested && !ct.IsCancellationRequested)
        {
            throw new DownloadStalledException(
                $"{request.RequestUri?.Host} sent no response for {_headers.TotalSeconds:0} s.", e);
        }
        if (response.Content != null)
            response.Content = new StallGuardContent(response.Content, _stall, request.RequestUri?.Host);
        return response;
    }
}

/// <summary>The original content, read through a <see cref="StallGuardStream"/>; headers carried over as they were.</summary>
internal sealed class StallGuardContent : HttpContent
{
    private readonly HttpContent _inner;
    private readonly TimeSpan _stall;
    private readonly string? _host;

    public StallGuardContent(HttpContent inner, TimeSpan stall, string? host)
    {
        _inner = inner;
        _stall = stall;
        _host = host;
        foreach (var header in inner.Headers)
            Headers.TryAddWithoutValidation(header.Key, header.Value);
    }

    protected override async Task<Stream> CreateContentReadStreamAsync(CancellationToken ct) =>
        new StallGuardStream(await _inner.ReadAsStreamAsync(ct).ConfigureAwait(false), _stall, _host);

    protected override Task<Stream> CreateContentReadStreamAsync() => CreateContentReadStreamAsync(CancellationToken.None);

    protected override Stream CreateContentReadStream(CancellationToken ct) =>
        CreateContentReadStreamAsync(ct).GetAwaiter().GetResult();

    protected override Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context) =>
        SerializeToStreamAsync(stream, context, CancellationToken.None);

    protected override async Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context, CancellationToken ct)
    {
        await using var source = await CreateContentReadStreamAsync(ct).ConfigureAwait(false);
        await source.CopyToAsync(stream, ct).ConfigureAwait(false);
    }

    // A known length already came over with the headers above. Reading Headers.ContentLength here would recurse
    // (its getter calls this when the header is absent, e.g. a chunked body), so: unknown.
    protected override bool TryComputeLength(out long length)
    {
        length = 0;
        return false;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) _inner.Dispose();
        base.Dispose(disposing);
    }
}

/// <summary>
/// A read-only body stream whose every read gets its own deadline: no bytes within the stall window throws
/// <see cref="DownloadStalledException"/> (and cancelling the inner read aborts the stuck connection). Every read overload
/// funnels into the cancellable Memory overload, because callers (CmlLib) use the array overload with no token,
/// whose Stream default would block a thread pool thread on a synchronous Read with no way out.
/// </summary>
public sealed class StallGuardStream : Stream
{
    private readonly Stream _inner;
    private readonly TimeSpan _stall;
    private readonly string? _host;

    public StallGuardStream(Stream inner, TimeSpan stall, string? host = null)
    {
        _inner = inner;
        _stall = stall;
        _host = host;
    }

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
        deadline.CancelAfter(_stall);
        try
        {
            return await _inner.ReadAsync(buffer, deadline.Token).ConfigureAwait(false);
        }
        catch (Exception e) when (deadline.IsCancellationRequested && !ct.IsCancellationRequested)
        {
            throw new DownloadStalledException(
                $"Download from {_host ?? "the server"} stalled: no data for {_stall.TotalSeconds:0} s.", e);
        }
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
        ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();

    public override int Read(byte[] buffer, int offset, int count) =>
        ReadAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();

    public override int Read(Span<byte> buffer)
    {
        var rent = new byte[buffer.Length];
        var n = Read(rent, 0, rent.Length);
        rent.AsSpan(0, n).CopyTo(buffer);
        return n;
    }

    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => _inner.Length;
    public override long Position { get => _inner.Position; set => throw new NotSupportedException(); }
    public override void Flush() { }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing) _inner.Dispose();
        base.Dispose(disposing);
    }

    public override async ValueTask DisposeAsync()
    {
        await _inner.DisposeAsync().ConfigureAwait(false);
        await base.DisposeAsync().ConfigureAwait(false);
    }
}
