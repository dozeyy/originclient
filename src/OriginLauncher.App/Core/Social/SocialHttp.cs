using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;

namespace OriginLauncher.App.Core.Social;

/// <summary>One HTTP exchange. Status 0 = no answer at all (offline, DNS, timeout).</summary>
public readonly record struct SocialHttpResponse(int Status, string? Body);

/// <summary>The network seam every social class takes, so tests never touch a network.</summary>
public delegate Task<SocialHttpResponse> SocialHttpSend(
    HttpMethod method, Uri url, string? jsonBody, string? bearerToken, CancellationToken ct);

/// <summary>
/// The production <see cref="SocialHttpSend"/>. Never throws for network
/// trouble (status 0 instead), every request has a short timeout, bodies are
/// size-capped, no cookies. Only the caller's own cancellation is rethrown.
/// </summary>
public static class SocialHttp
{
    private const int MaxBodyChars = 1024 * 1024;
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(15);
    private static readonly HttpClient Client = CreateClient();

    private static HttpClient CreateClient()
    {
        var handler = new SocketsHttpHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            UseCookies = false,
            PooledConnectionLifetime = TimeSpan.FromMinutes(10),
        };
        var client = new HttpClient(handler) { Timeout = System.Threading.Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("OriginLauncher/1.0");
        return client;
    }

    public static async Task<SocialHttpResponse> SendAsync(
        HttpMethod method, Uri url, string? jsonBody, string? bearerToken, CancellationToken ct)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(Timeout);
        try
        {
            using var request = new HttpRequestMessage(method, url);
            if (jsonBody != null)
                request.Content = new StringContent(jsonBody, Encoding.UTF8, "application/json");
            if (bearerToken != null)
                request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearerToken);

            using var response = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cts.Token)
                .ConfigureAwait(false);
            string? body = null;
            if (response.Content.Headers.ContentLength is not > MaxBodyChars)
            {
                body = await response.Content.ReadAsStringAsync(cts.Token).ConfigureAwait(false);
                if (body.Length > MaxBodyChars) body = null;
            }
            return new SocialHttpResponse((int)response.StatusCode, body);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException or IOException)
        {
            return new SocialHttpResponse(0, null);
        }
    }
}
