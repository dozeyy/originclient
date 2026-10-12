using System.Net.Http;

namespace OriginLauncher.App.Core.Social;

/// <summary>
/// The Home pill's "1,284 playing Origin": GET /v1/stats/online (no auth),
/// on start and every 5 minutes. Null = hide the pill (offline, not deployed,
/// nonsense answer) — it never blocks or errors.
/// </summary>
public static class PlayersOnline
{
    public static readonly TimeSpan Refresh = TimeSpan.FromMinutes(5);

    public static async Task<int?> FetchAsync(SocialHttpSend send, Uri baseUrl, CancellationToken ct)
    {
        try
        {
            var response = await send(HttpMethod.Get, SocialEndpoints.For(baseUrl, "/v1/stats/online"), null, null, ct)
                .ConfigureAwait(false);
            return response.Status == 200 ? SocialJson.ParseOnline(response.Body) : null;
        }
        catch (Exception) when (!ct.IsCancellationRequested)
        {
            return null;
        }
    }
}
