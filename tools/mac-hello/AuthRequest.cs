using System.Security.Cryptography;
using System.Text;

namespace MacHello;

// Copy of MicrosoftAuthenticator.BuildTestAuthorizationRequest and Pkce (src/OriginLauncher.App/Core/Auth),
// and of the redirect rules in MicrosoftSignInPanel.OnNavigationStarting. A copy, not a link: the
// launcher project is WPF + net8 and this probe must not touch it. Same client, same redirect.
internal static class AuthRequest
{
    public const string ClientId = "00000000402b5328";
    public const string RedirectUri = "https://login.live.com/oauth20_desktop.srf";
    private const string AuthorizeEndpoint = "https://login.live.com/oauth20_authorize.srf";

    public static (string Url, string State) Build(string scope = "XboxLive.signin offline_access")
    {
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        var state = Guid.NewGuid().ToString("N");
        var url =
            $"{AuthorizeEndpoint}" +
            $"?client_id={Uri.EscapeDataString(ClientId)}" +
            $"&response_type=code" +
            $"&redirect_uri={Uri.EscapeDataString(RedirectUri)}" +
            $"&response_mode=query" +
            $"&scope={Uri.EscapeDataString(scope)}" +
            $"&code_challenge={challenge}" +
            $"&code_challenge_method=S256" +
            $"&state={state}";
        return (url, state);
    }

    // Same decisions as the Windows panel, in the same order.
    public static string Classify(string redirect, string expectedState)
    {
        var query = ParseQuery(new Uri(redirect).Query);
        var hasState = query.TryGetValue("state", out var returned);
        if (hasState && returned != expectedState) return "ignored-wrong-state";
        var hasCode = query.ContainsKey("code");
        if (!hasState) return hasCode ? "refused-code-without-state" : "ignored-error-without-state";
        if (hasCode) return "code";
        return "error=" + (query.TryGetValue("error", out var error) ? error : "unknown");
    }

    private static Dictionary<string, string> ParseQuery(string query)
    {
        var result = new Dictionary<string, string>();
        foreach (var pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = pair.IndexOf('=');
            if (eq > 0) result[pair[..eq]] = Uri.UnescapeDataString(pair[(eq + 1)..]);
        }
        return result;
    }

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
