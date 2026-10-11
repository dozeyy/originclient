using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace OriginSetup.Engine;

/// <summary>
/// Getting the launcher off the internet failed. The message is worded for the
/// player; every case is worth a retry (the UI offers "Try again").
/// </summary>
public sealed class DownloadException : Exception
{
    public const string CantConnect =
        "Setup couldn't reach Origin's download server. Check that you're connected to the internet, then try again.";
    public const string Dropped =
        "The download stopped partway — your internet connection may have dropped. Check it, then try again.";
    public const string ServerProblem =
        "Origin's download server isn't answering properly right now. Try again in a few minutes.";
    public const string Damaged =
        "The download arrived damaged. Try again — Setup will download Origin fresh.";

    public DownloadException(string message, Exception? inner = null) : base(message, inner) { }
}

// Downloads one file over HTTPS with progress, verification and resume.
//
//  - A dropped connection resumes where it stopped (HTTP Range), up to
//    MaxAttempts tries in all; a server that won't resume restarts cleanly.
//  - A connection that goes silent for StallTimeout counts as dropped (body
//    reads have no timeout of their own on .NET Framework).
//  - The finished file must match the size the server announced, the size
//    and SHA-256 the release feed published (when known); anything else is
//    "damaged" and the file is deleted.
// .NET Framework 4.7.2 on older Windows 10 may not offer TLS 1.2 by default;
// GitHub requires it, so CreateClient turns it on explicitly.
public static class Download
{
    public const int MaxAttempts = 4;
    public static TimeSpan StallTimeout { get; set; } = TimeSpan.FromSeconds(30);
    /// <summary>Pause before resuming; tests set it to zero.</summary>
    public static TimeSpan RetryDelay { get; set; } = TimeSpan.FromSeconds(2);

    private static HttpClient? _shared;

    /// <summary>The one client the setup app uses (HttpClient is meant to be reused).</summary>
    public static HttpClient Shared => _shared ??= CreateClient();

    public static HttpClient CreateClient(HttpMessageHandler? handler = null)
    {
#if NETFRAMEWORK
        ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
#endif
        var http = handler != null ? new HttpClient(handler) : new HttpClient();
        // Covers connecting and the response headers; the body is guarded by StallTimeout.
        http.Timeout = TimeSpan.FromSeconds(30);
        // GitHub rejects requests without a User-Agent.
        http.DefaultRequestHeaders.UserAgent.ParseAdd("OriginSetup/1.0 (+https://github.com/dozeyy/originclient)");
        return http;
    }

    /// <summary>
    /// Downloads <paramref name="url"/> to <paramref name="path"/> (replaced).
    /// <paramref name="onBytes"/> gets (received, total) — total null when the
    /// server didn't say. Throws <see cref="DownloadException"/> or
    /// <see cref="OperationCanceledException"/>; a failed download leaves no file.
    /// </summary>
    public static async Task ToFileAsync(HttpClient http, string url, string path, long? expectedSize, string? expectedSha256,
                                         Action<long, long?>? onBytes, CancellationToken ct)
    {
        long received = 0;
        long? total = expectedSize;
        bool gotAnyResponse = false;
        // Opened outside the try: a folder Setup can't write to is a disk
        // problem (the caller words it), not a download problem.
        var file = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16);
        try
        {
            using (file)
            {
                for (int attempt = 1; ; attempt++)
                {
                    try
                    {
                        file.SetLength(received); // drop any half-written tail of a failed attempt
                        file.Position = received;
                        using var request = new HttpRequestMessage(HttpMethod.Get, url);
                        if (received > 0) request.Headers.Range = new RangeHeaderValue(received, null);
                        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
                        gotAnyResponse = true;

                        if (received > 0 && response.StatusCode != HttpStatusCode.PartialContent)
                        {
                            // The server sent the whole file again (or refused the range): start over.
                            SetupLog.Write($"download: server did not resume (HTTP {(int)response.StatusCode}), restarting");
                            received = 0;
                            file.SetLength(0);
                            file.Position = 0;
                            if (response.StatusCode != HttpStatusCode.OK) throw new RetryableException($"HTTP {(int)response.StatusCode}", fromServer: true);
                        }
                        else if (!response.IsSuccessStatusCode)
                        {
                            int code = (int)response.StatusCode;
                            if (code >= 500 || code == 429) throw new RetryableException($"HTTP {code}", fromServer: true);
                            SetupLog.Write($"download: HTTP {code} for {url}");
                            throw new DownloadException(DownloadException.ServerProblem);
                        }

                        long? announced = response.StatusCode == HttpStatusCode.PartialContent
                            ? response.Content.Headers.ContentRange?.Length
                            : response.Content.Headers.ContentLength;
                        if (announced != null)
                        {
                            if (total != null && announced != total)
                            {
                                SetupLog.Write($"download: server announced {announced} bytes, feed said {total}");
                                throw new DownloadException(DownloadException.Damaged);
                            }
                            total = announced;
                        }
                        onBytes?.Invoke(received, total);

                        await CopyBodyAsync(response, file, n =>
                        {
                            received += n;
                            onBytes?.Invoke(received, total);
                        }, ct).ConfigureAwait(false);
                        if (total != null && received < total)
                            throw new RetryableException($"connection closed at {received} of {total} bytes");
                        break;
                    }
                    catch (Exception ex) when (!ct.IsCancellationRequested && attempt < MaxAttempts
                                               && (ex is RetryableException || ReleaseFeed.IsNetworkOrData(ex)))
                    {
                        SetupLog.Write($"download: attempt {attempt} failed at {received} bytes ({ex.Message}); retrying");
                        await Task.Delay(RetryDelay, ct).ConfigureAwait(false);
                    }
                }
            }

            Verify(path, received, total, expectedSha256);
            SetupLog.Write($"download: {received} bytes, verified");
        }
        catch (Exception ex)
        {
            TryDelete(path);
            if (ct.IsCancellationRequested) throw new OperationCanceledException(ct);
            if (ex is DownloadException) throw;
            if (ex is RetryableException || ReleaseFeed.IsNetworkOrData(ex))
            {
                SetupLog.Write("download failed: " + ex);
                throw new DownloadException(
                    !gotAnyResponse ? DownloadException.CantConnect
                    : ex is RetryableException { FromServer: true } ? DownloadException.ServerProblem
                    : DownloadException.Dropped, ex);
            }
            throw;
        }
    }

    // Streams the body into the file. A read that stalls is ended by disposing
    // the response (the only reliable way to unblock it on .NET Framework).
    private static async Task CopyBodyAsync(HttpResponseMessage response, Stream file, Action<int> onRead, CancellationToken ct)
    {
        using var watchdog = CancellationTokenSource.CreateLinkedTokenSource(ct);
        using var stream = await response.Content.ReadAsStreamAsync().ConfigureAwait(false);
        using (watchdog.Token.Register(() => { try { response.Dispose(); stream.Dispose(); } catch (Exception) { } }))
        {
            var buffer = new byte[1 << 16];
            while (true)
            {
                watchdog.CancelAfter(StallTimeout);
                int n;
                try
                {
                    n = await stream.ReadAsync(buffer, 0, buffer.Length, watchdog.Token).ConfigureAwait(false);
                }
                catch (Exception) when (watchdog.IsCancellationRequested && !ct.IsCancellationRequested)
                {
                    throw new RetryableException($"no data for {StallTimeout.TotalSeconds:0.#}s");
                }
                if (n == 0)
                {
                    // A stalled read can also end as "0 bytes" once the response is disposed.
                    if (watchdog.IsCancellationRequested && !ct.IsCancellationRequested)
                        throw new RetryableException($"no data for {StallTimeout.TotalSeconds:0.#}s");
                    return;
                }
                try { await file.WriteAsync(buffer, 0, n, ct).ConfigureAwait(false); }
                catch (IOException ex) when (!ct.IsCancellationRequested)
                {
                    // Not the network's fault, so never retried or worded as a connection problem.
                    throw new SetupException("Setup couldn't save the download — the drive may be full. Free up some space, then try again.", ex);
                }
                onRead(n);
            }
        }
    }

    private static void Verify(string path, long received, long? total, string? expectedSha256)
    {
        if (total != null && received != total)
        {
            SetupLog.Write($"download: got {received} of {total} bytes");
            throw new DownloadException(received < total ? DownloadException.Dropped : DownloadException.Damaged);
        }
        if (received == 0) throw new DownloadException(DownloadException.Damaged);
        if (expectedSha256 == null) return;

        string actual;
        using (var sha = SHA256.Create())
        using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16))
            actual = string.Concat(sha.ComputeHash(fs).Select(b => b.ToString("x2")));
        if (!string.Equals(actual, expectedSha256, StringComparison.OrdinalIgnoreCase))
        {
            SetupLog.Write($"download: SHA-256 {actual}, expected {expectedSha256}");
            throw new DownloadException(DownloadException.Damaged);
        }
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    /// <summary>A failure worth another attempt (an HTTP error, a stall, an early end) — never escapes this class.</summary>
    private sealed class RetryableException : Exception
    {
        public RetryableException(string message, bool fromServer = false) : base(message) => FromServer = fromServer;

        /// <summary>The server answered with an error (vs. the connection failing).</summary>
        public bool FromServer { get; }
    }
}
