using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Import.GameFiles;

namespace OriginLauncher.App.Core.Import.Launcher;

// The real IMetadataHttp: GET one small Mojang metadata document.
//  * ONE dedicated HttpClient for the whole process (never one per call). It is its own client, not
//    VersionManager's shared one, because that one follows redirects and this one must not: a redirect
//    could carry the request to a host the engine's allowlist (MetadataClient.IsAllowed) never approved.
//    A 3xx answer is simply "unavailable".
//  * The body is streamed and reading stops the moment it passes maxBytes (a hostile or broken server
//    cannot make the import buffer an unbounded body); an announced Content-Length over the cap is
//    refused before any byte is read.
//  * 15 s per document; any failure is "unavailable" (null), never an exception into the plan.
public sealed class ImportMetadataHttp : IMetadataHttp
{
    private static readonly HttpClient Client = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        ConnectTimeout = TimeSpan.FromSeconds(10),
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
    })
    {
        Timeout = TimeSpan.FromSeconds(30),
    };

    private static readonly TimeSpan PerDocument = TimeSpan.FromSeconds(15);

    public async Task<byte[]?> GetAsync(string url, long maxBytes, CancellationToken ct)
    {
        if (!MetadataClient.IsAllowed(url)) return null;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(PerDocument);
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            using var response = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) return null; // includes 3xx: redirects are not followed
            // Belt and braces: whatever answered must still be an allowed address.
            if (response.RequestMessage?.RequestUri is not { } final || !MetadataClient.IsAllowed(final.AbsoluteUri)) return null;
            if (response.Content.Headers.ContentLength is { } announced && announced > maxBytes) return null;

            await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token).ConfigureAwait(false);
            using var body = new MemoryStream();
            var buffer = new byte[16 * 1024];
            while (true)
            {
                var read = await stream.ReadAsync(buffer, timeout.Token).ConfigureAwait(false);
                if (read == 0) break;
                if (body.Length + read > maxBytes) return null; // over the cap: stop reading, nothing is trusted
                body.Write(buffer, 0, read);
            }
            return body.ToArray();
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException or IOException or InvalidOperationException)
        {
            return null; // unreachable, timed out, TLS error: "not available"
        }
    }
}
