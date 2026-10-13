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
    // TEMPORARY — single switch for the whole test setup. Flip to false (and
    // revert ClientId below) once Origin's own Azure app registration is
    // confirmed working. Every place that needs to behave differently while
    // testing (silent refresh included — that's what was still broken)
    // checks this same flag, so nothing can drift out of sync.
    public const bool IsTestMode = true;

    // TEMPORARY — testing only. Swapped to a public client ID (registered in
    // Microsoft's legacy Live Connect system) to isolate whether the auth
    // chain itself works, independent of whether Origin's own Azure app
    // registration (de37d9e5-82d5-43a7-8f66-ebac788e8ba5) has finished
    // propagating. Revert to Origin's own ID before shipping.
    private const string ClientId = "00000000402b5328";

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

    // Full interactive sign-in: requires the user to log in through whatever
    // surface presentAuthorizationUrl renders (an embedded WebView2 page inside
    // the launcher, not an external browser popup — the caller decides how the
    // URL gets shown; this class only needs it navigated to somehow so the
    // loopback listener below sees the redirect).
    public async Task<AuthResult> SignInAsync(Action<string> presentAuthorizationUrl, CancellationToken ct = default)
    {
        var port = GetFreeLoopbackPort();
        var redirectUri = $"http://localhost:{port}/";

        var codeVerifier = Pkce.GenerateCodeVerifier();
        var codeChallenge = Pkce.GenerateCodeChallenge(codeVerifier);
        var state = Guid.NewGuid().ToString("N");

        var code = await GetAuthorizationCodeAsync(redirectUri, port, codeChallenge, state, presentAuthorizationUrl, ct);
        var (msaToken, refreshToken) = await ExchangeCodeForTokenAsync(code, redirectUri, codeVerifier, ct);

        return await CompleteInteractiveAsync(msaToken, refreshToken, ct);
    }

    // After the Microsoft window: the Microsoft part is done and its refresh
    // token is valuable. If only the Minecraft step is rate-limited (429), hand
    // the refresh token back anyway (Session = null, Deferred = the 429) so the
    // account can be kept and its Minecraft step finished later — the player
    // must not have to redo the Microsoft window because Mojang said "slow down".
    private static async Task<AuthResult> CompleteInteractiveAsync(string msaToken, string refreshToken, CancellationToken ct)
    {
        try
        {
            var (session, expiresAt) = await CompleteMinecraftAuthAsync(msaToken, ct);
            return new AuthResult { Session = session, MsaRefreshToken = refreshToken, ExpiresAt = expiresAt };
        }
        catch (MicrosoftAuthException ex) when (ex.RateLimited)
        {
            return new AuthResult { Session = null, MsaRefreshToken = refreshToken, Deferred = ex };
        }
    }

    // TEMPORARY — testing only, paired with the ClientId swap above. This is
    // this client ID's actual registered redirect — Microsoft's own generic
    // native-client landing page (login.live.com, not a third party's own
    // domain), which is why this ID is usable this way at all. Since our
    // sign-in runs inside our own embedded WebView2 rather than the system
    // browser, the caller just watches WebView2's NavigationStarting for
    // this URI and cancels it before it actually loads that blank page,
    // then hands the extracted code to CompleteTestSignInAsync below.
    // See MicrosoftSignInPanel.
    public const string TestRedirectUri = "https://login.live.com/oauth20_desktop.srf";

    // TEMPORARY — testing only. This client ID is registered in Microsoft's
    // legacy Live Connect identity system, not modern Azure AD v2 — the v2
    // "/consumers/oauth2/v2.0/token" endpoint returned AADSTS700016 ("app not
    // found in directory") because it genuinely isn't there. These legacy
    // endpoints are where it actually lives.
    private const string TestAuthorizeEndpoint = "https://login.live.com/oauth20_authorize.srf";
    private const string TestTokenEndpoint = "https://login.live.com/oauth20_token.srf";

    // Returns the state too: the caller must reject any redirect whose state
    // doesn't match (MicrosoftSignInPanel), like the loopback flow does.
    // No prompt=select_account here: on legacy login.live.com it is
    // undocumented, and an anonymous probe got the same login page for a bogus
    // prompt value, so "accepted" proves nothing. Not needed either: the
    // panel's InPrivate profile means there is never a session to reuse.
    public (string AuthUrl, string CodeVerifier, string State) BuildTestAuthorizationRequest()
    {
        var codeVerifier = Pkce.GenerateCodeVerifier();
        var codeChallenge = Pkce.GenerateCodeChallenge(codeVerifier);
        var state = Guid.NewGuid().ToString("N");

        var authUrl =
            $"{TestAuthorizeEndpoint}" +
            $"?client_id={Uri.EscapeDataString(ClientId)}" +
            $"&response_type=code" +
            $"&redirect_uri={Uri.EscapeDataString(TestRedirectUri)}" +
            $"&response_mode=query" +
            $"&scope={Uri.EscapeDataString("XboxLive.signin offline_access")}" +
            $"&code_challenge={codeChallenge}" +
            $"&code_challenge_method=S256" +
            $"&state={state}";

        return (authUrl, codeVerifier, state);
    }

    public async Task<AuthResult> CompleteTestSignInAsync(string code, string codeVerifier, CancellationToken ct = default)
    {
        var (msaToken, refreshToken) = await ExchangeCodeForTokenAsync(code, TestRedirectUri, codeVerifier, ct, TestTokenEndpoint);
        return await CompleteInteractiveAsync(msaToken, refreshToken, ct);
    }

    // Silent re-authentication using a previously stored MSA refresh token —
    // no browser, no user interaction. Only MinecraftSessionBroker calls this,
    // and only when there is no cached Minecraft session left to reuse
    // (Mojang rate-limits login_with_xbox). Always returns a Session; a 429
    // throws (MicrosoftAuthException.RateLimited).
    public async Task<AuthResult> SignInSilentlyAsync(string refreshToken, CancellationToken ct = default)
    {
        var (msaToken, newRefreshToken) = await RefreshMsaTokenAsync(refreshToken, ct);
        var (session, expiresAt) = await CompleteMinecraftAuthAsync(msaToken, ct);
        return new AuthResult { Session = session, MsaRefreshToken = newRefreshToken, ExpiresAt = expiresAt };
    }

    /// <summary>
    /// Does Mojang still accept this cached Minecraft token? One profile call
    /// (not login_with_xbox, so it doesn't spend the sign-in rate limit).
    /// Never throws except on the caller's own cancel: anything but a clear
    /// 200/401 is Unknown.
    /// </summary>
    public static async Task<Sessions.TokenCheck> CheckMinecraftTokenAsync(string minecraftAccessToken, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, McProfileEndpoint);
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", minecraftAccessToken);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(8));
            using var response = await Http.SendAsync(request, timeout.Token);
            return response.StatusCode switch
            {
                HttpStatusCode.OK => Sessions.TokenCheck.Valid,
                HttpStatusCode.Unauthorized => Sessions.TokenCheck.Rejected,
                _ => Sessions.TokenCheck.Unknown,
            };
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return Sessions.TokenCheck.Unknown;
        }
    }

    private static async Task<(MSession Session, DateTimeOffset ExpiresAt)> CompleteMinecraftAuthAsync(string msaAccessToken, CancellationToken ct)
    {
        var xbl = await AuthenticateWithXboxLiveAsync(msaAccessToken, ct);
        var xsts = await AuthenticateWithXstsAsync(xbl.Token, ct);
        var (mcToken, expiresAt) = await AuthenticateWithMinecraftAsync(xsts.UserHash, xsts.Token, ct);
        var profile = await GetMinecraftProfileAsync(mcToken, ct);

        var session = new MSession
        {
            Username = profile.Name,
            UUID = profile.Id,
            AccessToken = mcToken,
            UserType = "msa",
            Xuid = xsts.Xuid
        };
        return (session, expiresAt);
    }

    // --- Step 1 + 2: open the browser, listen on the loopback redirect ---

    private static async Task<string> GetAuthorizationCodeAsync(
        string redirectUri, int port, string codeChallenge, string state,
        Action<string> presentAuthorizationUrl, CancellationToken ct)
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
            // Adding an account must never silently reuse a session Microsoft
            // already has (that is how the second account couldn't be added).
            // Documented for this v2 endpoint; the InPrivate profile in
            // MicrosoftSignInPanel is the main guard.
            $"&prompt=select_account" +
            $"&state={state}";

        using var listener = new HttpListener();
        listener.Prefixes.Add($"http://localhost:{port}/");
        listener.Start();

        presentAuthorizationUrl(authUrl);

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
            throw new MicrosoftAuthException("login", "Microsoft sign-in didn't finish. Please try again.",
                $"no authorization code ({error ?? "unknown error"})");

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

    // Every HTTP failure goes through here: 429 becomes the friendly
    // rate-limited error (with the server's Retry-After), a 5xx says
    // Microsoft is down (never "re-add your account"), and anything else
    // becomes `playerMessage` — the raw status/body only ever goes in Detail
    // (the error log), never on screen.
    private static MicrosoftAuthException Failure(string stage, HttpResponseMessage response, string body, string playerMessage)
    {
        var detail = DescribeFailure(response, body);
        if (response.StatusCode == HttpStatusCode.TooManyRequests)
            return MicrosoftAuthException.Limited(stage, RetryAfterOf(response), detail);
        if ((int)response.StatusCode >= 500)
            return new MicrosoftAuthException(stage + "_unavailable",
                "Microsoft's sign-in service isn't answering right now. Please try again in a few minutes.", detail);
        return new MicrosoftAuthException(stage, playerMessage, detail);
    }

    private static TimeSpan? RetryAfterOf(HttpResponseMessage response)
    {
        var header = response.Headers.RetryAfter;
        if (header?.Delta is { } delta) return delta;
        if (header?.Date is { } date)
        {
            var wait = date - DateTimeOffset.UtcNow;
            return wait > TimeSpan.Zero ? wait : TimeSpan.Zero;
        }
        return null;
    }

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
        string code, string redirectUri, string codeVerifier, CancellationToken ct, string tokenEndpoint = TokenEndpoint)
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

        using var response = await Http.PostAsync(tokenEndpoint, new FormUrlEncodedContent(form), ct);
        var json = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw Failure("token_exchange", response, json, "Microsoft sign-in didn't finish. Please try again.");

        var token = JsonSerializer.Deserialize<MsaTokenResponse>(json)
            ?? throw new MicrosoftAuthException("token_exchange", "Microsoft sign-in didn't finish. Please try again.", "empty token response");
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

        // TEMPORARY — IsTestMode routes this to the legacy endpoint too; the
        // test client ID only exists in Microsoft's legacy Live Connect
        // directory, so silent refresh against the modern endpoint fails
        // with the same AADSTS700016 ("app not found") the initial sign-in
        // did before this switch was added.
        var endpoint = IsTestMode ? TestTokenEndpoint : TokenEndpoint;
        using var response = await Http.PostAsync(endpoint, new FormUrlEncodedContent(form), ct);
        var json = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw Failure("token_refresh", response, json, "Your Microsoft sign-in expired. Remove and re-add this account in the account switcher.");

        var token = JsonSerializer.Deserialize<MsaTokenResponse>(json)
            ?? throw new MicrosoftAuthException("token_refresh", "Your Microsoft sign-in expired. Remove and re-add this account in the account switcher.", "empty refresh response");
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
            throw Failure("xbox_live", response, json, "Couldn't sign in to Xbox Live. Please try again in a moment.");

        var xbl = JsonSerializer.Deserialize<XboxLiveTokenResponse>(json)
            ?? throw new MicrosoftAuthException("xbox_live", "Couldn't sign in to Xbox Live. Please try again in a moment.", "empty response");
        var uhs = xbl.DisplayClaims.Xui.FirstOrDefault()?.Uhs
            ?? throw new MicrosoftAuthException("xbox_live", "Couldn't sign in to Xbox Live. Please try again in a moment.", "missing user hash");
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
                null => "Xbox Live refused this sign-in. Please try again in a moment.",
                _ => $"Xbox Live refused this sign-in (code {xErr.XErr})."
            };
            throw new MicrosoftAuthException("xsts", message, DescribeFailure(response, json));
        }
        if (!response.IsSuccessStatusCode)
            throw Failure("xsts", response, json, "Xbox Live refused this sign-in. Please try again in a moment.");

        var xsts = JsonSerializer.Deserialize<XboxLiveTokenResponse>(json)
            ?? throw new MicrosoftAuthException("xsts", "Xbox Live refused this sign-in. Please try again in a moment.", "empty response");
        var claims = xsts.DisplayClaims.Xui.FirstOrDefault()
            ?? throw new MicrosoftAuthException("xsts", "Xbox Live refused this sign-in. Please try again in a moment.", "missing user claims");
        return (xsts.Token, claims.Uhs, claims.Xid ?? string.Empty);
    }

    // --- Step 5: Minecraft access token ---
    // The call Mojang rate-limits (429): reached only when no cached session
    // is left to reuse (MinecraftSessionBroker).

    // When login_with_xbox doesn't say (expires_in missing), Mojang's documented 24 h.
    private const int DefaultMinecraftTokenSeconds = 86400;

    private static async Task<(string Token, DateTimeOffset ExpiresAt)> AuthenticateWithMinecraftAsync(
        string userHash, string xstsToken, CancellationToken ct)
    {
        var payload = new { identityToken = $"XBL3.0 x={userHash};{xstsToken}" };

        using var response = await Http.PostAsJsonAsync(McLoginEndpoint, payload, RequestJsonOptions, ct);
        var json = await response.Content.ReadAsStringAsync(ct);
        if (!response.IsSuccessStatusCode)
            throw Failure("minecraft_login", response, json, "Couldn't sign in to Minecraft. Please try again in a moment.");

        var mc = JsonSerializer.Deserialize<MinecraftAuthResponse>(json)
            ?? throw new MicrosoftAuthException("minecraft_login", "Couldn't sign in to Minecraft. Please try again in a moment.", "empty response");
        var seconds = mc.ExpiresIn > 0 ? mc.ExpiresIn : DefaultMinecraftTokenSeconds;
        return (mc.AccessToken, DateTimeOffset.UtcNow.AddSeconds(seconds));
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
            throw Failure("profile", response, json, "Couldn't load your Minecraft profile. Please try again in a moment.");

        return JsonSerializer.Deserialize<MinecraftProfileResponse>(json)
            ?? throw new MicrosoftAuthException("profile", "Couldn't load your Minecraft profile. Please try again in a moment.", "empty response");
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

        // Seconds until the Minecraft token stops working (normally 86400).
        [JsonPropertyName("expires_in")]
        public long ExpiresIn { get; set; }
    }

    private sealed class MinecraftProfileResponse
    {
        [JsonPropertyName("id")]
        public string Id { get; set; } = "";

        [JsonPropertyName("name")]
        public string Name { get; set; } = "";
    }
}
