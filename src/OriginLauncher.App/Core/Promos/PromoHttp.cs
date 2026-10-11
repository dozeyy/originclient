using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;

namespace OriginLauncher.App.Core.Promos;

/// <summary>
/// One GET's outcome. StatusCode 0 means no HTTP answer at all (offline, DNS,
/// timeout, body over the size cap) — callers treat that as "try later".
/// </summary>
public readonly record struct HttpTextResult(int StatusCode, string? Body)
{
    public bool IsSuccess => StatusCode is >= 200 and < 300 && Body != null;
}

/// <summary>The fetch seam every promo class takes, so tests run without a network.</summary>
public delegate Task<HttpTextResult> HttpTextGet(Uri url, TimeSpan timeout, CancellationToken ct);

/// <summary>
/// The home banner's HTTP client: the feed, Discord's public invite API and
/// promo images. Never throws for network trouble, every request has its own
/// short timeout, and bodies are size-capped — the banner is decoration and
/// must never stall or crash the launcher. Sends no account data: no cookies,
/// no auth headers, a generic User-Agent.
/// </summary>
public static class PromoHttp
{
    private const int MaxTextBytes = 256 * 1024;

    private static readonly HttpClient Client = CreateClient();

    private static HttpClient CreateClient()
    {
        var handler = new SocketsHttpHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            UseCookies = false,
            PooledConnectionLifetime = TimeSpan.FromMinutes(10),
        };
        // Per-request timeouts (linked CTS) instead of the client-wide one, so
        // the feed, Discord and images can each pick their own.
        var client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("OriginLauncher/1.0");
        return client;
    }

    /// <summary>The production <see cref="HttpTextGet"/>.</summary>
    public static Task<HttpTextResult> GetTextAsync(Uri url, TimeSpan timeout, CancellationToken ct) =>
        GetTextCoreAsync(url, timeout, ct);

    private static async Task<HttpTextResult> GetTextCoreAsync(Uri url, TimeSpan timeout, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeout);
        try
        {
            using var response = await Client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cts.Token)
                .ConfigureAwait(false);
            var status = (int)response.StatusCode;
            if (!response.IsSuccessStatusCode) return new HttpTextResult(status, null);

            var bytes = await ReadCappedAsync(response.Content, MaxTextBytes, cts.Token).ConfigureAwait(false);
            return bytes == null
                ? new HttpTextResult(0, null)
                : new HttpTextResult(status, Encoding.UTF8.GetString(bytes));
        }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException or IOException)
        {
            return new HttpTextResult(0, null);
        }
    }

    /// <summary>Raw bytes (promo images, server icons); null on any failure or over <paramref name="maxBytes"/>.</summary>
    public static async Task<byte[]?> GetBytesAsync(Uri url, int maxBytes, TimeSpan timeout, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeout);
        try
        {
            using var response = await Client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cts.Token)
                .ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) return null;
            return await ReadCappedAsync(response.Content, maxBytes, cts.Token).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException or IOException)
        {
            return null;
        }
    }

    private static async Task<byte[]?> ReadCappedAsync(HttpContent content, int maxBytes, CancellationToken ct)
    {
        if (content.Headers.ContentLength is long declared && declared > maxBytes) return null;

        await using var stream = await content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var buffer = new MemoryStream();
        var chunk = new byte[16 * 1024];
        int read;
        while ((read = await stream.ReadAsync(chunk, ct).ConfigureAwait(false)) > 0)
        {
            if (buffer.Length + read > maxBytes) return null;
            buffer.Write(chunk, 0, read);
        }
        return buffer.ToArray();
    }
}
