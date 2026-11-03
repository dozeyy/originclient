using System.Text.Json;

namespace OriginLauncher.App.Core.Auth;

/// <summary>What Microsoft hands back when a code sign-in starts. <see cref="DeviceCode"/> is a secret (it is what
/// the launcher polls with); <see cref="UserCode"/> is what the player types on Microsoft's page. Neither is ever
/// logged, written to disk or put in an exception.</summary>
public sealed record DeviceCodeStart(string DeviceCode, string UserCode, string VerificationUri, int IntervalSeconds, int ExpiresInSeconds)
{
    // A record prints every member by default; this one must never print either code, even by accident in a log line.
    public override string ToString() =>
        $"DeviceCodeStart {{ VerificationUri = {VerificationUri}, IntervalSeconds = {IntervalSeconds}, ExpiresInSeconds = {ExpiresInSeconds} }}";
}

/// <summary>One poll's answer, from the token endpoint's status and its "error" field.</summary>
public enum DeviceCodePoll { Done, Pending, SlowDown, Expired, Declined, Failed }

/// <summary>
/// The pure half of "Sign in with a code instead" (OAuth device authorization on Microsoft's live.com endpoints, the
/// same legacy directory the test client id lives in): parse the start answer and read each poll. The HTTP half is
/// MicrosoftAuthenticator.DeviceCode.cs. Kept apart so the tests cover every answer without a network.
/// </summary>
public static class DeviceCodeFlow
{
    public const string ConnectEndpoint = "https://login.live.com/oauth20_connect.srf";
    public const string GrantType = "urn:ietf:params:oauth:grant-type:device_code";

    // Microsoft's own defaults when an answer leaves them out (RFC 8628 3.2: interval 5 s).
    public const int DefaultIntervalSeconds = 5;
    public const int DefaultExpiresSeconds = 900;
    public const int SlowDownStepSeconds = 5;

    /// <summary>The start answer, or null when it is missing a field the player needs (no code to show, nowhere to
    /// enter it, nothing to poll with).</summary>
    public static DeviceCodeStart? ParseStart(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return null;
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            var device = Str(root, "device_code");
            var user = Str(root, "user_code");
            var uri = Str(root, "verification_uri");
            if (string.IsNullOrWhiteSpace(device) || string.IsNullOrWhiteSpace(user) || !IsHttps(uri)) return null;
            var interval = Int(root, "interval");
            var expires = Int(root, "expires_in");
            return new DeviceCodeStart(device!, user!.Trim(), uri!,
                interval is > 0 and <= 60 ? interval.Value : DefaultIntervalSeconds,
                expires is > 0 ? expires.Value : DefaultExpiresSeconds);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>Reads one poll: a success status is Done; otherwise the "error" field decides. An unknown error ends
    /// the sign-in (never polls forever).</summary>
    public static DeviceCodePoll Classify(bool success, string? errorBody)
    {
        if (success) return DeviceCodePoll.Done;
        return ErrorOf(errorBody) switch
        {
            "authorization_pending" => DeviceCodePoll.Pending,
            "slow_down" => DeviceCodePoll.SlowDown,
            "expired_token" or "code_expired" => DeviceCodePoll.Expired,
            "authorization_declined" or "access_denied" => DeviceCodePoll.Declined,
            _ => DeviceCodePoll.Failed,
        };
    }

    /// <summary>The wait before the next poll: slow_down adds 5 s for the rest of the sign-in (RFC 8628 3.5).</summary>
    public static int NextInterval(int current, DeviceCodePoll poll) =>
        poll == DeviceCodePoll.SlowDown ? current + SlowDownStepSeconds : current;

    /// <summary>The OAuth "error" field of an error body, or null.</summary>
    public static string? ErrorOf(string? body)
    {
        if (string.IsNullOrWhiteSpace(body)) return null;
        try
        {
            using var doc = JsonDocument.Parse(body);
            return doc.RootElement.ValueKind == JsonValueKind.Object ? Str(doc.RootElement, "error") : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static bool IsHttps(string? uri) =>
        Uri.TryCreate(uri, UriKind.Absolute, out var u) && u.Scheme == Uri.UriSchemeHttps;

    private static string? Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static int? Int(JsonElement e, string name)
    {
        if (!e.TryGetProperty(name, out var v)) return null;
        if (v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var n)) return n;
        if (v.ValueKind == JsonValueKind.String && int.TryParse(v.GetString(), out n)) return n;
        return null;
    }
}

/// <summary>What the sign-in page's final redirect means for THIS request. Twin of the Windows panel's inline rules
/// (MicrosoftSignInPanel.SignInViaPrismTestAsync), as pure code the Mac panel and the tests share.</summary>
public enum RedirectOutcome { NotOurs, Ignore, Code, Error }

public static class SignInRedirect
{
    /// <summary>
    /// login.live.com echoes state on every redirect, success or error (probed 2026-10-13):
    ///  - not the redirect address at all (compared by how it STARTS: the sign-in page's own address carries the
    ///    redirect as its redirect_uri parameter): NotOurs, let it load;
    ///  - state present but different: a stale or forged redirect, Ignore (it can neither finish nor abort);
    ///  - a code without our state: Error (CSRF guard);
    ///  - an error with no state: Ignore (can't be ours);
    ///  - our state: the Code, or the Error that ends this sign-in.
    /// </summary>
    public static RedirectOutcome Classify(string uri, string redirectUri, string expectedState, out string code, out string error)
    {
        code = "";
        error = "";
        if (!uri.StartsWith(redirectUri, StringComparison.Ordinal)) return RedirectOutcome.NotOurs;
        var query = ParseQuery(Uri.TryCreate(uri, UriKind.Absolute, out var u) ? u.Query : "");
        var hasState = query.TryGetValue("state", out var state);
        if (hasState && state != expectedState) return RedirectOutcome.Ignore;
        var hasCode = query.TryGetValue("code", out var c);
        if (!hasState)
        {
            if (!hasCode) return RedirectOutcome.Ignore;
            error = "authorization code without state";
            return RedirectOutcome.Error;
        }
        if (hasCode && !string.IsNullOrEmpty(c))
        {
            code = c;
            return RedirectOutcome.Code;
        }
        error = query.TryGetValue("error", out var err) ? err : "unknown error";
        return RedirectOutcome.Error;
    }

    public static Dictionary<string, string> ParseQuery(string query)
    {
        var result = new Dictionary<string, string>();
        foreach (var pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var idx = pair.IndexOf('=');
            if (idx < 0) continue;
            result[Uri.UnescapeDataString(pair[..idx])] = Uri.UnescapeDataString(pair[(idx + 1)..]);
        }
        return result;
    }
}
