using System.Net.Http;
using OriginLauncher.App.Core.Auth;

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
/// Origin social's sign-in and authorised calls, exactly per docs/SOCIAL.md
/// "Identity v2": with the player's own Minecraft token, fetch the Mojang
/// player certificate (cached until Mojang says refresh) and the Mojang-signed
/// profile, sign `origin-auth|uuid|launcher|ms` with the certificate's private
/// key, and trade that proof at /v1/auth for an Origin token. The service
/// checks Mojang's signatures offline — it never calls Mojang (Mojang refuses
/// Cloudflare Workers). The Minecraft token only ever goes to Mojang; the
/// private key and the Origin token stay in this object, memory only.
/// A 401 on any call re-runs /v1/auth once, then retries the call.
/// </summary>
public sealed class SocialClient
{
    public const string ClientName = "launcher";

    public const string OnlinePlayNotAllowed =
        "Your Microsoft account doesn't allow online play, so friends can't be used.";
    public const string GenericSignInFailure = "Couldn't sign in to friends right now.";

    private sealed record AuthSession(string Token, string Uuid, string Name);

    /// <summary>The cached Mojang certificate and whose it is.</summary>
    private sealed record CachedCertificate(string Uuid, PlayerCertificate Certificate);

    private readonly Uri _baseUrl;
    private readonly SocialHttpSend _send;
    private readonly IdentitySource _identity;
    private readonly ServerClock _serverClock;
    private readonly SemaphoreSlim _authGate = new(1, 1);
    private AuthSession? _session;
    private CachedCertificate? _certificate;

    // Which account the sign-in belongs to: Forget (account switched) bumps it. A sign-in started before the switch
    // read the OLD account's identity and must not store its token after it (it once did: Friends then showed, and
    // sent everything as, the previous account until a restart). Every store of _session/_certificate checks the
    // generation the sign-in started on, under _stateLock so a Forget can't land between the check and the store.
    private readonly object _stateLock = new();
    private int _generation;

    public SocialClient(Uri baseUrl, SocialHttpSend send, IdentitySource identity, Func<DateTimeOffset>? clock = null)
    {
        _baseUrl = baseUrl;
        _send = send;
        _identity = identity;
        _serverClock = new ServerClock(clock);
    }

    /// <summary>
    /// The real time as Origin's service and Mojang report it. `clock` (the PC
    /// clock) is never used directly for anything a server checks, so a wrong
    /// PC date or time zone can't break sign-in.
    /// </summary>
    public ServerClock Clock => _serverClock;

    /// <summary>The signed-in player's uuid (normalised), or null before sign-in.</summary>
    public string? MyUuid => Volatile.Read(ref _session)?.Uuid;

    /// <summary>Drop the Origin token and the certificate (account switched): the next call signs in again.</summary>
    public void Forget()
    {
        lock (_stateLock)
        {
            _generation++;
            Volatile.Write(ref _session, null);
            Volatile.Write(ref _certificate, null);
        }
    }

    /// <summary>Stores <paramref name="store"/> only while no Forget happened since <paramref name="generation"/>.</summary>
    private bool StoreIfCurrent(int generation, Action store)
    {
        lock (_stateLock)
        {
            if (_generation != generation) return false;
            store();
            return true;
        }
    }

    public async Task<SocialAuthOutcome> EnsureAuthAsync(CancellationToken ct)
    {
        if (Volatile.Read(ref _session) != null) return SocialAuthOutcome.Success;
        await _authGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            // Another caller may have signed in while this one waited. A sign-in overtaken by an account switch
            // stored nothing (null): start over as the account selected now (a couple of times, then quietly retry
            // on the next tick rather than spin while the player clicks through accounts).
            for (var attempt = 0; attempt < 3; attempt++)
            {
                if (Volatile.Read(ref _session) != null) return SocialAuthOutcome.Success;
                int generation;
                lock (_stateLock) generation = _generation;
                if (await AuthenticateAsync(generation, ct).ConfigureAwait(false) is { } outcome) return outcome;
            }
            return new SocialAuthOutcome(SocialAuthState.Unreachable, null);
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
            _serverClock.Observe(response.ServerDate);
            if (response.Status != 401 || attempt > 0)
                return new SocialCallResult(response.Status, response.Body);

            // The Origin token expired or was revoked: forget exactly this one
            // (a concurrent caller may already hold a fresh one) and retry.
            Interlocked.CompareExchange(ref _session, null, session);
        }
    }

    /// <summary>Signs in as the identity source's account; null when Forget ran meanwhile (nothing was stored).</summary>
    private async Task<SocialAuthOutcome?> AuthenticateAsync(int generation, CancellationToken ct)
    {
        var id = await _identity(false, ct).ConfigureAwait(false);
        for (var attempt = 0; ; attempt++)
        {
            if (id.State != IdentityState.Ok || id.Identity is not { } me)
                return Map(id);
            var uuid = Ids.Norm(me.Uuid);

            // 1. The Mojang certificate (the key pair this sign-in is signed with).
            var certificate = CachedFor(uuid);
            if (certificate == null)
            {
                var issued = await _send(HttpMethod.Post, SocialEndpoints.PlayerCertificates, null, me.AccessToken, ct)
                    .ConfigureAwait(false);
                // Mojang's answer (never cached) says what time it really is: the proof signed below uses it.
                _serverClock.Observe(issued.ServerDate);
                if (IsUnreachable(issued.Status))
                    return new SocialAuthOutcome(SocialAuthState.Unreachable, null);
                if (issued.Status == 401)
                {
                    // The cached Minecraft token expired: get a fresh one from
                    // Microsoft once, then give up until the backoff.
                    if (attempt > 0)
                        return new SocialAuthOutcome(SocialAuthState.SignInFailed,
                            SignInAgain.FriendsLine);
                    id = await _identity(true, ct).ConfigureAwait(false);
                    continue;
                }
                if (issued.Status != 200)
                    return new SocialAuthOutcome(SocialAuthState.SignInFailed,
                        MojangProof.SaysMultiplayerDisabled(issued.Body) ? OnlinePlayNotAllowed : GenericSignInFailure);
                certificate = MojangProof.ParseCertificate(issued.Body);
                if (certificate == null)
                    return new SocialAuthOutcome(SocialAuthState.SignInFailed, GenericSignInFailure);
                var issuedCertificate = new CachedCertificate(uuid, certificate);
                if (!StoreIfCurrent(generation, () => Volatile.Write(ref _certificate, issuedCertificate))) return null;
            }

            // 2. The Mojang-signed profile (proves the name; no token needed).
            var profileAnswer = await _send(HttpMethod.Get, SocialEndpoints.SignedProfile(uuid), null, null, ct)
                .ConfigureAwait(false);
            if (IsUnreachable(profileAnswer.Status))
                return new SocialAuthOutcome(SocialAuthState.Unreachable, null);
            if (profileAnswer.Status != 200 || MojangProof.ParseProfile(profileAnswer.Body) is not { } profile)
                return new SocialAuthOutcome(SocialAuthState.SignInFailed, GenericSignInFailure);

            // 3. Sign the proof and trade it for an Origin token. The name sent
            // is Mojang's own spelling from the signed profile — the service
            // compares it exactly. `at` is the REAL time (ServerClock), never the
            // PC clock: the service refuses an `at` more than 5 minutes off its own.
            var name = string.IsNullOrWhiteSpace(profile.Name) ? me.Name : profile.Name;
            SocialHttpResponse auth = default;
            for (var clockTry = 0; clockTry < 2; clockTry++)
            {
                var offsetBefore = _serverClock.OffsetMs;
                var at = _serverClock.NowMs;
                var sig = MojangProof.Sign(certificate, MojangProof.SignedString(uuid, ClientName, at));
                auth = await _send(HttpMethod.Post, SocialEndpoints.For(_baseUrl, "/v1/auth"),
                    SocialJson.AuthBody(ClientName, uuid, name, at, sig, certificate, profile.Textures), null, ct)
                    .ConfigureAwait(false);
                _serverClock.Observe(auth.ServerDate);
                // A refusal whose answer corrected our idea of the time was the
                // service's "clock looks wrong": sign once more with the corrected
                // time (a new signature, inside the service's window - the
                // anti-replay check is untouched).
                if (auth.Status != 401 || _serverClock.OffsetMs == offsetBefore) break;
            }
            if (auth.Status == 200 && SocialJson.ParseAuth(auth.Body) is { } granted)
            {
                var session = new AuthSession(granted.Token, granted.Uuid, granted.Name);
                return StoreIfCurrent(generation, () => Volatile.Write(ref _session, session))
                    ? SocialAuthOutcome.Success
                    : null;
            }
            var result = new SocialCallResult(auth.Status, auth.Body);
            // 503 with a sentence = the service is up but not ready (Mojang's
            // keys missing): show it, keep retrying on the backoff.
            if (result.Unreachable)
                return new SocialAuthOutcome(SocialAuthState.Unreachable, SocialJson.ErrorSentence(auth.Body));
            // 401 = a check failed (e.g. "Your Minecraft sign-in ran out"):
            // don't offer the same certificate again; the next try (after the
            // backoff) fetches a fresh one. 400 = "update Origin". Both
            // sentences are player-facing and shown as is.
            if (auth.Status == 401) StoreIfCurrent(generation, () => Volatile.Write(ref _certificate, null));
            return new SocialAuthOutcome(SocialAuthState.SignInFailed,
                SocialJson.ErrorSentence(auth.Body) ?? "Origin couldn't confirm your Minecraft account.");
        }
    }

    /// <summary>
    /// The cached certificate when it belongs to this player and Mojang hasn't
    /// asked for a refresh (Mojang's times, so compared with the real time).
    /// </summary>
    private PlayerCertificate? CachedFor(string uuid) =>
        Volatile.Read(ref _certificate) is { } cached && cached.Uuid == uuid
            && cached.Certificate.FreshAt(_serverClock.NowMs)
            ? cached.Certificate
            : null;

    /// <summary>No answer, a server fault or a rate limit — try again later, not the player's problem.</summary>
    private static bool IsUnreachable(int status) => status == 0 || status >= 500 || status == 429;

    private static SocialAuthOutcome Map(IdentityResult id) => id.State switch
    {
        IdentityState.NoAccount => new SocialAuthOutcome(SocialAuthState.NoAccount, id.Message),
        IdentityState.Unreachable => new SocialAuthOutcome(SocialAuthState.Unreachable, id.Message),
        _ => new SocialAuthOutcome(SocialAuthState.SignInFailed, id.Message ?? GenericSignInFailure),
    };
}
