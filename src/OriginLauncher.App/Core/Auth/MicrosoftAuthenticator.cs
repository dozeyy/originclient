using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using CmlLib.Core.Auth;

namespace OriginLauncher.App.Core.Auth;

// Full sign-in chain for a custom Minecraft launcher:
//   MSA login (browser + loopback listener, PKCE authorization-code flow)
//     -> Xbox Live user token
//     -> XSTS token (scoped to the Minecraft relying party)
//     -> Minecraft access token
//     -> Minecraft profile (username + UUID)
//
// No external OAuth/HTTP library needed — HttpListener, HttpClient, and
// System.Text.Json are all in the .NET 8 base class library.
public sealed class MicrosoftAuthenticator
{
    // Azure App Registration (public client, personal Microsoft accounts only).
    private const string ClientId = "de37d9e5-82d5-43a7-8f66-ebac788e8ba5";

    // "Personal Microsoft accounts only" apps authenticate against the
    // /consumers authority, not the app's own Directory (tenant) ID — that
    // tenant ID identifies Will's Azure AD directory, which personal MSA
    // accounts don't belong to. Using the tenant ID here would be correct
    // for a work/school-account app, not this one.
    private const string AuthorizeEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    private const string TokenEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    private const string XblAuthEndpoint = "https://user.auth.xboxlive.com/user/authenticate";
    private const string XstsAuthEndpoint = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private const string McLoginEndpoint = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private const string McProfileEndpoint = "https://api.minecraftservices.com/minecraft/profile";

    private static readonly HttpClient Http = new();

    // PostAsJsonAsync's default options silently camelCase every property
    // (confirmed: "Properties" -> "properties", "AuthMethod" -> "authMethod",
    // etc.) — Xbox Live's API requires the exact PascalCase keys from its
    // docs and returns a bodiless 400 if they don't match. This is what was
    // actually causing "Xbox Live authentication failed: HTTP 400 BadRequest
    // (empty response body)".
    private static readonly JsonSerializerOptions RequestJsonOptions = new() { PropertyNamingPolicy = null };

    // Full interactive sign-in: opens the browser, requires the user to log in.
    public async Task<AuthResult> SignInAsync(CancellationToken ct = default)
    {
        var port = GetFreeLoopbackPort();
        var redirectUri = $"http://localhost:{port}/";

        var codeVerifier = Pkce.GenerateCodeVerifier();
        var codeChallenge = Pkce.GenerateCodeChallenge(codeVerifier);
        var state = Guid.NewGuid().ToString("N");

        var code = await GetAuthorizationCodeAsync(redirectUri, port, codeChallenge, state, ct);
        var (msaToken, refreshToken) = await ExchangeCodeForTokenAsync(code, redirectUri, codeVerifier, ct);

        var session = await CompleteMinecraftAuthAsync(msaToken, ct);
        return new AuthResult { Session = session, MsaRefreshToken = refreshToken };
    }

    // Silent re-authentication using a previously stored MSA refresh token —
    // no browser, no user interaction. Used on every launch so a signed-in
    // account doesn't need a fresh browser login each time.
    public async Task<AuthResult> SignInSilentlyAsync(string refreshToken, CancellationToken ct = default)
    {
        var (msaToken, newRefreshToken) = await RefreshMsaTokenAsync(refreshToken, ct);
        var session = await CompleteMinecraftAuthAsync(msaToken, ct);
        return new AuthResult { Session = session, MsaRefreshToken = newRefreshToken };
    }

    private static async Task<MSession> CompleteMinecraftAuthAsync(string msaAccessToken, CancellationToken ct)
    {
        var xbl = await AuthenticateWithXboxLiveAsync(msaAccessToken, ct);
        var xsts = await AuthenticateWithXstsAsync(xbl.Token, ct);
        var mcToken = await AuthenticateWithMinecraftAsync(xsts.UserHash, xsts.Token, ct);
        var profile = await GetMinecraftProfileAsync(mcToken, ct);

        return new MSession
        {
            Username = profile.Name,
            UUID = profile.Id,
            AccessToken = mcToken,
            UserType = "msa",
            Xuid = xsts.Xuid
        };
    }

    // --- Step 1 + 2: open the browser, listen on the loopback redirect ---

    private static async Task<string> GetAuthorizationCodeAsync(
        string redirectUri, int port, string codeChallenge, string state, CancellationToken ct)
    {
        var authUrl =
            $"{AuthorizeEndpoint}" +
            $"?client_id={Uri.EscapeDataString(ClientId)}" +
            $"&response_type=code" +
            $"&redirect_uri={Uri.EscapeDataString(redirectUri)}" +
            $"&response_mode=query" +
            $"&scope={Uri.EscapeDataString("XboxLive.signin offline_access")}" +
            $"&code_challenge={codeChallenge}" +
            $"&code_challenge_method=S256" +
            $"&state={state}";

        using var listener = new HttpListener();
        listener.Prefixes.Add($"http://localhost:{port}/");
        listener.Start();

        Process.Start(new ProcessStartInfo(authUrl) { UseShellExecute = true });

        var context = await listener.GetContextAsync().WaitAsync(TimeSpan.FromMinutes(5), ct);
        var query = context.Request.QueryString;

        string responseHtml;
        string? code = query["code"];
        string? returnedState = query["state"];
        string? error = query["error"];

        if (error != null)
        {
            responseHtml = "<html><body>Sign-in failed. You can close this window.</body></html>";
        }
        else if (returnedState != state)
        {
            error = "state_mismatch";
            responseHtml = "<html><body>Sign-in failed (security check mismatch). You can close this window.</body></html>";
        }
        else
        {
            responseHtml = "<html><body>Signed in — you can close this window and return to Origin Launcher.</body></html>";
        }

        var buffer = Encoding.UTF8.GetBytes(responseHtml);
        context.Response.ContentType = "text/html; charset=utf-8";
        context.Response.ContentLength64 = buffer.Length;
        await context.Response.OutputStream.WriteAsync(buffer, ct);
        context.Response.OutputStream.Close();
        listener.Stop();

        if (error != null || code == null)
            throw new MicrosoftAuthException("login", $"Microsoft sign-in did not return an authorization code ({error ?? "unknown error"}).");

        return code;
    }

    // A failed request with an empty body (common for some Xbox/Azure error
    // responses) previously produced a message like "...failed: " with
    // nothing after the colon — useless for diagnosing anything. Always
    // include the actual status code, and say so explicitly when the body
    // really is empty rather than leaving a dangling colon.
    private static string DescribeFailure(HttpResponseMessage response, string body) =>
        string.IsNullOrWhiteSpace(body)
            ? $"HTTP {(int)response.StatusCode} {response.StatusCode} (empty response body)"
            : $"HTTP {(int)response.StatusCode} {response.StatusCode}: {body}";

    private static int GetFreeLoopbackPort()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    // --- Step 3 (first half): trade the auth code for a Microsoft access token ---

    private static async Task<(string AccessToken, string RefreshToken)> ExchangeCodeForTokenAsync(
        string code, string redirectUri, string codeVerifier, CancellationToken ct)
    {
        var form = new Dictionary<string, string>
        {
            ["client_id"] = ClientId,
            ["grant_type"] = "authorization_code",
            ["code"] = code,
            ["redirect_uri"] = redirectUri,
            ["code_verifier"] = codeVerifier,
            ["scope"] = "XboxLive.signin offline_access"
        };

        using var response = await Http.PostAsync(TokenEndpoint, new FormUrlEncodedContent(form), ct);
        var json = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw new MicrosoftAuthException("token_exchange", $"Microsoft token exchange failed: {DescribeFailure(response, json)}");

        var token = JsonSerializer.Deserialize<MsaTokenResponse>(json)
            ?? throw new MicrosoftAuthException("token_exchange", "Microsoft token exchange returned an empty response.");
        return (token.AccessToken, token.RefreshToken);
    }

    // Same token endpoint, refresh_token grant instead of authorization_code —
    // no redirect_uri/code_verifier needed for this grant type.
    private static async Task<(string AccessToken, string RefreshToken)> RefreshMsaTokenAsync(
        string refreshToken, CancellationToken ct)
    {
        var form = new Dictionary<string, string>
        {
            ["client_id"] = ClientId,
            ["grant_type"] = "refresh_token",
            ["refresh_token"] = refreshToken,
            ["scope"] = "XboxLive.signin offline_access"
        };

        using var response = await Http.PostAsync(TokenEndpoint, new FormUrlEncodedContent(form), ct);
        var json = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw new MicrosoftAuthException("token_refresh", $"Silent sign-in failed, a fresh login is needed: {DescribeFailure(response, json)}");

        var token = JsonSerializer.Deserialize<MsaTokenResponse>(json)
            ?? throw new MicrosoftAuthException("token_refresh", "Microsoft token refresh returned an empty response.");
        // Microsoft rotates refresh tokens on use — always persist the new one.
        return (token.AccessToken, token.RefreshToken);
    }

    // --- Step 3 (second half): Xbox Live user token ---

    private static async Task<(string Token, string UserHash)> AuthenticateWithXboxLiveAsync(string msaAccessToken, CancellationToken ct)
    {
        var payload = new
        {
            Properties = new
            {
                AuthMethod = "RPS",
                SiteName = "user.auth.xboxlive.com",
                RpsTicket = $"d={msaAccessToken}"
            },
            RelyingParty = "http://auth.xboxlive.com",
            TokenType = "JWT"
        };

        using var response = await Http.PostAsJsonAsync(XblAuthEndpoint, payload, RequestJsonOptions, ct);
        var json = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw new MicrosoftAuthException("xbox_live", $"Xbox Live authentication failed: {DescribeFailure(response, json)}");

        var xbl = JsonSerializer.Deserialize<XboxLiveTokenResponse>(json)
            ?? throw new MicrosoftAuthException("xbox_live", "Xbox Live authentication returned an empty response.");
        var uhs = xbl.DisplayClaims.Xui.FirstOrDefault()?.Uhs
            ?? throw new MicrosoftAuthException("xbox_live", "Xbox Live response was missing a user hash.");
        return (xbl.Token, uhs);
    }

    // --- Step 4: XSTS, scoped to the Minecraft relying party ---

    private static async Task<(string Token, string UserHash, string Xuid)> AuthenticateWithXstsAsync(string xblToken, CancellationToken ct)
    {
        var payload = new
        {
            Properties = new
            {
                SandboxId = "RETAIL",
                UserTokens = new[] { xblToken }
            },
            RelyingParty = "rp://api.minecraftservices.com/",
            TokenType = "JWT"
        };

        using var response = await Http.PostAsJsonAsync(XstsAuthEndpoint, payload, RequestJsonOptions, ct);
        var json = await response.Content.ReadAsStringAsync(ct);

        if (response.StatusCode == HttpStatusCode.Unauthorized)
        {
            // XstsErrorResponse deserialization would throw on an empty body
            // (JsonSerializer rejects empty input), so guard it explicitly
            // instead of letting that crash out as an unrelated JsonException.
            var xErr = string.IsNullOrWhiteSpace(json) ? null : JsonSerializer.Deserialize<XstsErrorResponse>(json);
            var message = xErr?.XErr switch
            {
                2148916233 => "This Microsoft account has no Xbox Live profile. Sign in at xbox.com once, then try again.",
                2148916238 => "This account is a child account and needs to be added to a Microsoft Family group first.",
                2148916235 => "Xbox Live is not available in this account's region.",
                null => $"XSTS authentication was rejected: {DescribeFailure(response, json)}",
                _ => $"XSTS authentication was rejected ({xErr.XErr})."
            };
            throw new MicrosoftAuthException("xsts", message);
        }
        if (!response.IsSuccessStatusCode)
            throw new MicrosoftAuthException("xsts", $"XSTS authentication failed: {DescribeFailure(response, json)}");

        var xsts = JsonSerializer.Deserialize<XboxLiveTokenResponse>(json)
            ?? throw new MicrosoftAuthException("xsts", "XSTS authentication returned an empty response.");
        var claims = xsts.DisplayClaims.Xui.FirstOrDefault()
            ?? throw new MicrosoftAuthException("xsts", "XSTS response was missing user claims.");
        return (xsts.Token, claims.Uhs, claims.Xid ?? string.Empty);
    }

    // --- Step 5: Minecraft access token ---

    private static async Task<string> AuthenticateWithMinecraftAsync(string userHash, string xstsToken, CancellationToken ct)
    {
        var payload = new { identityToken = $"XBL3.0 x={userHash};{xstsToken}" };

        using var response = await Http.PostAsJsonAsync(McLoginEndpoint, payload, RequestJsonOptions, ct);
        var json = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw new MicrosoftAuthException("minecraft_login", $"Minecraft authentication failed: {DescribeFailure(response, json)}");

        var mc = JsonSerializer.Deserialize<MinecraftAuthResponse>(json)
            ?? throw new MicrosoftAuthException("minecraft_login", "Minecraft authentication returned an empty response.");
        return mc.AccessToken;
    }

    // --- Step 6: profile (username + UUID) ---

    private static async Task<MinecraftProfileResponse> GetMinecraftProfileAsync(string minecraftAccessToken, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, McProfileEndpoint);
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", minecraftAccessToken);

        using var response = await Http.SendAsync(request, ct);
        var json = await response.Content.ReadAsStringAsync(ct);

        if (response.StatusCode == HttpStatusCode.NotFound)
            throw new MicrosoftAuthException("profile", "This Microsoft account does not own a copy of Minecraft.");
        if (!response.IsSuccessStatusCode)
            throw new MicrosoftAuthException("profile", $"Fetching the Minecraft profile failed: {DescribeFailure(response, json)}");

        return JsonSerializer.Deserialize<MinecraftProfileResponse>(json)
            ?? throw new MicrosoftAuthException("profile", "Minecraft profile response was empty.");
    }

    // --- DTOs for the JSON responses above ---

    private sealed class MsaTokenResponse
    {
        [JsonPropertyName("access_token")]
        public string AccessToken { get; set; } = "";

        [JsonPropertyName("refresh_token")]
        public string RefreshToken { get; set; } = "";
    }

    private sealed class XboxLiveTokenResponse
    {
        [JsonPropertyName("Token")]
        public string Token { get; set; } = "";

        [JsonPropertyName("DisplayClaims")]
        public DisplayClaimsDto DisplayClaims { get; set; } = new();

        public sealed class DisplayClaimsDto
        {
            [JsonPropertyName("xui")]
            public List<XuiClaim> Xui { get; set; } = new();
        }

        public sealed class XuiClaim
        {
            [JsonPropertyName("uhs")]
            public string Uhs { get; set; } = "";

            [JsonPropertyName("xid")]
            public string? Xid { get; set; }
        }
    }

    private sealed class XstsErrorResponse
    {
        [JsonPropertyName("XErr")]
        public long XErr { get; set; }
    }

    private sealed class MinecraftAuthResponse
    {
        [JsonPropertyName("access_token")]
        public string AccessToken { get; set; } = "";
    }

    private sealed class MinecraftProfileResponse
    {
        [JsonPropertyName("id")]
        public string Id { get; set; } = "";

        [JsonPropertyName("name")]
        public string Name { get; set; } = "";
    }
}
