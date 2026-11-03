using System.Net.Http;
using System.Text.Json;

namespace OriginLauncher.App.Core.Auth;

// "Sign in with a code instead": the player opens Microsoft's page on any device, types a short code, and the
// launcher polls until Microsoft says yes. Same client id, scope and token endpoint as the embedded sign-in, then the
// same Xbox / XSTS / Minecraft chain (CompleteInteractiveAsync), so an account added either way is the same AuthResult
// and is stored the same way (MinecraftSessionBroker.AddSignedIn). The device code, the tokens and every success body
// stay in memory: nothing here logs, writes or throws them (an exception's Detail only ever carries an ERROR body).
public sealed partial class MicrosoftAuthenticator
{
    private const string DeviceCodeFailed = "Couldn't start sign-in with a code. Please try again.";

    /// <summary>Asks Microsoft for a code to show the player.</summary>
    public async Task<DeviceCodeStart> StartDeviceCodeAsync(CancellationToken ct = default)
    {
        var form = new Dictionary<string, string>
        {
            ["client_id"] = ClientId,
            ["scope"] = "XboxLive.signin offline_access",
            ["response_type"] = "device_code",
        };
        using var response = await Http.PostAsync(DeviceCodeFlow.ConnectEndpoint, new FormUrlEncodedContent(form), ct);
        var json = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw Failure("device_code", response, json, DeviceCodeFailed);
        return DeviceCodeFlow.ParseStart(json)
               ?? throw new MicrosoftAuthException("device_code", DeviceCodeFailed, "device code answer was missing a field");
    }

    /// <summary>Polls until the player finishes on Microsoft's page (or the code expires, or they decline), then runs
    /// the Minecraft half. Cancel <paramref name="ct"/> to stop waiting.</summary>
    public async Task<AuthResult> CompleteDeviceCodeSignInAsync(DeviceCodeStart start, CancellationToken ct = default)
    {
        var interval = start.IntervalSeconds;
        var deadline = DateTime.UtcNow.AddSeconds(start.ExpiresInSeconds);
        var misses = 0; // network blips in a row (DeviceCodeFlow.MaxTransientMisses)
        while (true)
        {
            await Task.Delay(TimeSpan.FromSeconds(interval), ct);
            if (DateTime.UtcNow >= deadline) throw Expired();

            var form = new Dictionary<string, string>
            {
                ["client_id"] = ClientId,
                ["grant_type"] = DeviceCodeFlow.GrantType,
                ["device_code"] = start.DeviceCode,
            };
            HttpResponseMessage? response = null;
            string json;
            try
            {
                response = await Http.PostAsync(TestTokenEndpoint, new FormUrlEncodedContent(form), ct);
                json = await response.Content.ReadAsStringAsync(ct);
            }
            // No answer (dropped connection, DNS) or HttpClient's own timeout (a TaskCanceledException that is not
            // the player's cancel): one blip, waited out like "pending" until too many come in a row. ct and the
            // deadline still end the wait at once. Nothing of the request or the exception is logged.
            catch (Exception ex) when (ex is HttpRequestException || (ex is TaskCanceledException && !ct.IsCancellationRequested))
            {
                response?.Dispose();
                if (DeviceCodeFlow.KeepWaitingAfterMiss(++misses)) continue;
                throw new MicrosoftAuthException("device_code_poll",
                    "Couldn't reach Microsoft. Check your internet connection and try again.", "no answer to repeated polls");
            }
            using var reply = response;
            if (DeviceCodeFlow.IsTransientStatus((int)reply.StatusCode))
            {
                // A 5xx (often an HTML page): Microsoft's hiccup, not its answer. The body is never kept or logged.
                if (DeviceCodeFlow.KeepWaitingAfterMiss(++misses)) continue;
                throw new MicrosoftAuthException("device_code_poll", "Microsoft sign-in didn't finish. Please try again.",
                    $"HTTP {(int)reply.StatusCode} on repeated polls");
            }
            misses = 0;
            var poll = DeviceCodeFlow.Classify(reply.IsSuccessStatusCode, json);
            switch (poll)
            {
                case DeviceCodePoll.Done:
                    MsaTokenResponse? token;
                    try { token = JsonSerializer.Deserialize<MsaTokenResponse>(json); }
                    catch (JsonException) { token = null; }
                    if (token == null || string.IsNullOrEmpty(token.AccessToken))
                        throw new MicrosoftAuthException("device_code", "Microsoft sign-in didn't finish. Please try again.", "empty token response");
                    return await CompleteInteractiveAsync(token.AccessToken, token.RefreshToken, ct);
                case DeviceCodePoll.Pending:
                case DeviceCodePoll.SlowDown:
                    interval = DeviceCodeFlow.NextInterval(interval, poll);
                    continue;
                case DeviceCodePoll.Expired:
                    throw Expired();
                case DeviceCodePoll.Declined:
                    throw new MicrosoftAuthException("device_code_declined",
                        "Sign-in was cancelled on Microsoft's page. Ask for a new code to try again.", "authorization_declined");
                default:
                    throw Failure("device_code_poll", reply, json, "Microsoft sign-in didn't finish. Please try again.");
            }
        }
    }

    private static MicrosoftAuthException Expired() =>
        new("device_code_expired", "This code expired. Ask for a new one to try again.", "expired_token");
}
