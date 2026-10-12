using System.Net.Http;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Social;

/// <summary>The signed-in player as the launcher knows them. Lives in memory only.</summary>
public sealed record MinecraftIdentity(string Name, string Uuid, string AccessToken)
{
    // Never let the access token reach a log or a debugger tooltip by accident.
    public override string ToString() => $"MinecraftIdentity {{ Name = {Name} }}";
}

public enum IdentityState { Ok, NoAccount, SignInFailed, Unreachable }

public sealed record IdentityResult(IdentityState State, MinecraftIdentity? Identity, string? Message)
{
    public static IdentityResult Ok(MinecraftIdentity identity) => new(IdentityState.Ok, identity, null);
    public static IdentityResult NoAccount(string message) => new(IdentityState.NoAccount, null, message);
    public static IdentityResult SignInFailed(string message) => new(IdentityState.SignInFailed, null, message);
    public static IdentityResult Unreachable(string message) => new(IdentityState.Unreachable, null, message);
}

/// <summary>
/// Hands out the selected account's Minecraft identity. forceRefresh = the
/// cached Minecraft token was refused, get a new one from Microsoft.
/// </summary>
public delegate Task<IdentityResult> IdentitySource(bool forceRefresh, CancellationToken ct);

public enum SocialAuthState { Ok, NoAccount, SignInFailed, Unreachable }

public sealed record SocialAuthOutcome(SocialAuthState State, string? Message)
{
    public static SocialAuthOutcome Success { get; } = new(SocialAuthState.Ok, null);
}

/// <summary>One social call's answer.</summary>
public readonly record struct SocialCallResult(int Status, string? Body)
{
    public bool Ok => Status is >= 200 and < 300;

    /// <summary>No answer, a server fault, rate limit, or a 404 with no sentence (service not deployed yet).</summary>
    public bool Unreachable => Status == 0 || Status >= 500 || Status == 429
        || (Status == 404 && SocialJson.ErrorSentence(Body) == null);

    /// <summary>The service's own sentence when it sent one, else a plain fallback.</summary>
    public string ErrorText => SocialJson.ErrorSentence(Body) ?? Status switch
    {
        0 or 404 or >= 500 => "Can't reach Origin right now. Try again in a moment.",
        401 => "Couldn't confirm your account. Try again in a moment.",
        429 => "Slow down a little, then try again.",
        _ => "Something went wrong. Try again in a moment.",
    };
}

/// <summary>
/// Origin social's sign-in and authorised calls, exactly per docs/SOCIAL.md:
/// pick a random serverId, tell Mojang "I'm joining serverId" with the
/// player's own Minecraft token, then trade the serverId for an Origin token
/// at /v1/auth (client "launcher"). No Microsoft or Mojang token ever goes to
/// Origin. The Origin token stays in this object — memory only, never disk.
/// A 401 on any call re-runs /v1/auth once, then retries the call.
/// </summary>
public sealed class SocialClient
{
    public const string ClientName = "launcher";

    private sealed record AuthSession(string Token, string Uuid, string Name);

    private readonly Uri _baseUrl;
    private readonly SocialHttpSend _send;
    private readonly IdentitySource _identity;
    private readonly Func<string> _newServerId;
    private readonly SemaphoreSlim _authGate = new(1, 1);
    private AuthSession? _session;

    public SocialClient(Uri baseUrl, SocialHttpSend send, IdentitySource identity, Func<string>? newServerId = null)
    {
        _baseUrl = baseUrl;
        _send = send;
        _identity = identity;
        _newServerId = newServerId ?? NewServerId;
    }

    /// <summary>The signed-in player's uuid (normalised), or null before sign-in.</summary>
    public string? MyUuid => Volatile.Read(ref _session)?.Uuid;

    /// <summary>Drop the Origin token (account switched): the next call signs in again.</summary>
    public void Forget() => Volatile.Write(ref _session, null);

    /// <summary>32 random hex characters — a serverId Mojang has never seen.</summary>
    public static string NewServerId() => Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();

    public async Task<SocialAuthOutcome> EnsureAuthAsync(CancellationToken ct)
    {
        if (Volatile.Read(ref _session) != null) return SocialAuthOutcome.Success;
        await _authGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            // Another caller may have signed in while this one waited.
            if (Volatile.Read(ref _session) != null) return SocialAuthOutcome.Success;
            return await AuthenticateAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            _authGate.Release();
        }
    }

    /// <summary>POST an authorised call; signs in first if needed, re-signs once on 401.</summary>
    public async Task<SocialCallResult> PostAsync(string path, string jsonBody, CancellationToken ct)
    {
        for (var attempt = 0; ; attempt++)
        {
            var auth = await EnsureAuthAsync(ct).ConfigureAwait(false);
            var session = Volatile.Read(ref _session);
            if (auth.State != SocialAuthState.Ok || session == null)
                return new SocialCallResult(auth.State == SocialAuthState.Unreachable ? 0 : 401, null);

            var response = await _send(HttpMethod.Post, SocialEndpoints.For(_baseUrl, path), jsonBody, session.Token, ct)
                .ConfigureAwait(false);
            if (response.Status != 401 || attempt > 0)
                return new SocialCallResult(response.Status, response.Body);

            // The Origin token expired or was revoked: forget exactly this one
            // (a concurrent caller may already hold a fresh one) and retry.
            Interlocked.CompareExchange(ref _session, null, session);
        }
    }

    private async Task<SocialAuthOutcome> AuthenticateAsync(CancellationToken ct)
    {
        var id = await _identity(false, ct).ConfigureAwait(false);
        for (var attempt = 0; ; attempt++)
        {
            if (id.State != IdentityState.Ok || id.Identity is not { } me)
                return Map(id);

            var serverId = _newServerId();
            var join = await _send(HttpMethod.Post, SocialEndpoints.MojangJoin,
                SocialJson.MojangJoinBody(me.AccessToken, me.Uuid, serverId), null, ct).ConfigureAwait(false);

            if (join.Status is 200 or 204)
            {
                var auth = await _send(HttpMethod.Post, SocialEndpoints.For(_baseUrl, "/v1/auth"),
                    SocialJson.AuthBody(me.Name, serverId, ClientName), null, ct).ConfigureAwait(false);
                if (auth.Status == 200 && SocialJson.ParseAuth(auth.Body) is { } granted)
                {
                    Volatile.Write(ref _session, new AuthSession(granted.Token, granted.Uuid, granted.Name));
                    return SocialAuthOutcome.Success;
                }
                var result = new SocialCallResult(auth.Status, auth.Body);
                return result.Unreachable
                    ? new SocialAuthOutcome(SocialAuthState.Unreachable, null)
                    : new SocialAuthOutcome(SocialAuthState.SignInFailed,
                        SocialJson.ErrorSentence(auth.Body) ?? "Origin couldn't confirm your Minecraft account.");
            }

            if (join.Status == 0 || join.Status >= 500 || join.Status == 429)
                return new SocialAuthOutcome(SocialAuthState.Unreachable, null);

            // 401/403: Mojang refused this Minecraft token (expired) — get a
            // fresh one from Microsoft once, then give up until the backoff.
            if (attempt > 0)
                return new SocialAuthOutcome(SocialAuthState.SignInFailed,
                    "Couldn't sign in to friends. Try removing and re-adding your account.");
            id = await _identity(true, ct).ConfigureAwait(false);
        }
    }

    private static SocialAuthOutcome Map(IdentityResult id) => id.State switch
    {
        IdentityState.NoAccount => new SocialAuthOutcome(SocialAuthState.NoAccount, id.Message),
        IdentityState.Unreachable => new SocialAuthOutcome(SocialAuthState.Unreachable, id.Message),
        _ => new SocialAuthOutcome(SocialAuthState.SignInFailed, id.Message ?? "Couldn't sign in to friends right now."),
    };
}
