namespace OriginLauncher.App.Core.Auth.Sessions;

/// <summary>
/// Decides when Origin actually signs in to Minecraft. Mojang rate-limits
/// login_with_xbox (HTTP 429), so a session is reused until shortly before
/// its real expiry instead of signing in on every Play / account switch /
/// Friends retry (the v1.0.49 "add account fails with 429" bug).
///
/// Rules:
/// - One cached session PER ACCOUNT (memory + the vault, so restarts reuse it),
///   reused while more than <see cref="ReuseMargin"/> of it is left.
/// - Signing in happens only when there is no usable session, or Mojang
///   refused the cached token (Play checks it; Friends sees its own 401).
/// - One sign-in at a time overall (never race a rotating refresh token), and
///   one in flight per account: concurrent callers await the same one.
/// - A 429 anywhere in the chain blocks that account's sign-ins for the
///   server's Retry-After (clamped), else <see cref="DefaultBackoff"/>; while
///   blocked, callers get the friendly rate-limited error straight away.
/// - Friends' forced renewals: at most one per account per
///   <see cref="RenewInterval"/>.
/// </summary>
public sealed class MinecraftSessions
{
    /// <summary>Reuse a session while more than this is left of it (Mojang's tokens last 24 h).</summary>
    public static readonly TimeSpan ReuseMargin = TimeSpan.FromHours(1);

    /// <summary>Back-off after a 429 that didn't say how long to wait.</summary>
    public static readonly TimeSpan DefaultBackoff = TimeSpan.FromMinutes(10);

    /// <summary>Retry-After is honoured, but never shorter than this nor longer than <see cref="MaxBackoff"/>.</summary>
    public static readonly TimeSpan MinBackoff = TimeSpan.FromMinutes(1);
    public static readonly TimeSpan MaxBackoff = TimeSpan.FromHours(1);

    /// <summary>Friends may force a fresh sign-in at most this often per account.</summary>
    public static readonly TimeSpan RenewInterval = TimeSpan.FromMinutes(30);

    /// <summary>Id of an account added while Microsoft was rate-limiting, until its real UUID is known.</summary>
    public const string PendingIdPrefix = "pending-";

    private readonly ISessionVault _vault;
    private readonly ISessionSignIn _signIn;
    private readonly Func<DateTimeOffset> _clock;

    private readonly object _lock = new();
    private readonly SemaphoreSlim _signInGate = new(1, 1);
    private readonly Dictionary<string, MinecraftSession> _cache = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Task<MinecraftSession?>> _inFlight = new(StringComparer.Ordinal);
    private readonly Dictionary<string, DateTimeOffset> _blockedUntil = new(StringComparer.Ordinal);
    private readonly Dictionary<string, DateTimeOffset> _lastSignIn = new(StringComparer.Ordinal);

    public MinecraftSessions(ISessionVault vault, ISessionSignIn signIn, Func<DateTimeOffset>? clock = null)
    {
        _vault = vault;
        _signIn = signIn;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
    }

    /// <summary>
    /// A usable session (cached, or a fresh sign-in when there is none).
    /// Doesn't touch "last used". Null = the account's Microsoft sign-in
    /// can't be read: re-add the account. Throws MicrosoftAuthException.
    /// </summary>
    public async Task<MinecraftSession?> GetAsync(string accountId, CancellationToken ct = default) =>
        Usable(accountId) ?? await SignInAsync(accountId, ct).ConfigureAwait(false);

    /// <summary>
    /// Play: the cached session after one cheap check that Mojang still
    /// accepts it (a refused token = sign in again; no answer = use it
    /// anyway), else a fresh sign-in. Marks the account used.
    /// </summary>
    public async Task<MinecraftSession?> ForPlayAsync(string accountId, CancellationToken ct = default)
    {
        var session = Usable(accountId);
        if (session != null)
        {
            TokenCheck check;
            try
            {
                check = await _signIn.CheckAsync(session.AccessToken, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception)
            {
                check = TokenCheck.Unknown; // the check is a courtesy; never block Play on it
            }
            if (check == TokenCheck.Rejected)
            {
                Drop(accountId, session);
                session = null;
            }
        }

        session ??= await SignInAsync(accountId, ct).ConfigureAwait(false);
        if (session != null) _vault.MarkUsed(session.Uuid);
        return session;
    }

    /// <summary>
    /// Friends: Mojang refused the token this caller holds. Signs in again —
    /// unless this account signed in less than <see cref="RenewInterval"/>
    /// ago, in which case the token is fresh and the refusal isn't about it,
    /// so the current session is returned unchanged.
    /// </summary>
    public async Task<MinecraftSession?> RenewAsync(string accountId, CancellationToken ct = default)
    {
        var current = Usable(accountId);
        lock (_lock)
        {
            if (current != null
                && _lastSignIn.TryGetValue(accountId, out var at)
                && _clock() - at < RenewInterval)
                return current;
        }
        if (current != null) Drop(accountId, current);
        return await SignInAsync(accountId, ct).ConfigureAwait(false);
    }

    /// <summary>
    /// A sign-in that happened outside the broker (adding an account): keep
    /// its session so the first Play doesn't sign in again. The caller has
    /// already stored it with the account.
    /// </summary>
    public void Adopt(MinecraftSession session)
    {
        lock (_lock)
        {
            _cache[session.Uuid] = session;
            _lastSignIn[session.Uuid] = _clock();
            _blockedUntil.Remove(session.Uuid);
        }
    }

    /// <summary>Microsoft answered 429 for this account: hold its sign-ins off.</summary>
    public void NoteRateLimited(string accountId, TimeSpan? retryAfter)
    {
        var wait = BackoffFor(retryAfter);
        lock (_lock)
        {
            var until = _clock() + wait;
            if (!_blockedUntil.TryGetValue(accountId, out var existing) || existing < until)
                _blockedUntil[accountId] = until;
        }
    }

    /// <summary>When this account may sign in again, or null when it isn't held off.</summary>
    public DateTimeOffset? BlockedUntil(string accountId)
    {
        lock (_lock)
            return _blockedUntil.TryGetValue(accountId, out var until) && _clock() < until ? until : null;
    }

    /// <summary>Retry-After when the server sent one (clamped to sane bounds), else the default.</summary>
    public static TimeSpan BackoffFor(TimeSpan? retryAfter)
    {
        if (retryAfter is not { } asked) return DefaultBackoff;
        if (asked < MinBackoff) return MinBackoff;
        return asked > MaxBackoff ? MaxBackoff : asked;
    }

    /// <summary>The account's session when it has more than ReuseMargin left; loads the vault's copy on first use.</summary>
    private MinecraftSession? Usable(string accountId)
    {
        MinecraftSession? session;
        lock (_lock) _cache.TryGetValue(accountId, out session);
        if (session == null)
        {
            session = _vault.SavedSession(accountId);
            if (session == null || session.Uuid != accountId) return null;
            lock (_lock) _cache.TryAdd(accountId, session);
        }
        return session.ExpiresAt - _clock() > ReuseMargin ? session : null;
    }

    /// <summary>Forget a session Mojang refused — but not a newer one another caller just got.</summary>
    private void Drop(string accountId, MinecraftSession refused)
    {
        lock (_lock)
        {
            if (_cache.TryGetValue(accountId, out var cached) && !ReferenceEquals(cached, refused)
                && cached.AccessToken != refused.AccessToken)
                return;
            _cache.Remove(accountId);
        }
        _vault.Forget(accountId);
    }

    private async Task<MinecraftSession?> SignInAsync(string accountId, CancellationToken ct)
    {
        Task<MinecraftSession?> task;
        lock (_lock)
        {
            if (_blockedUntil.TryGetValue(accountId, out var until) && _clock() < until)
                throw MicrosoftAuthException.Limited("backoff", until - _clock());
            if (!_inFlight.TryGetValue(accountId, out task!))
            {
                // Its own task, not tied to any one caller's token: a sign-in
                // abandoned half-way would lose the refresh token Microsoft
                // already rotated. Each caller only stops WAITING when cancelled.
                task = Task.Run(() => RunSignInAsync(accountId));
                _inFlight[accountId] = task;
            }
        }
        return await task.WaitAsync(ct).ConfigureAwait(false);
    }

    private async Task<MinecraftSession?> RunSignInAsync(string accountId)
    {
        await _signInGate.WaitAsync().ConfigureAwait(false);
        try
        {
            // A sign-in that finished while this one queued already did the job.
            if (Usable(accountId) is { } fresh) return fresh;

            // Read inside the gate: the sign-in before this one may have
            // rotated the token (or turned a pending account into a real one).
            var refreshToken = _vault.RefreshToken(accountId);
            if (refreshToken == null) return null;
            var startedAt = _clock();

            SilentSignIn result;
            try
            {
                result = await _signIn.SignInAsync(refreshToken, CancellationToken.None).ConfigureAwait(false);
            }
            catch (MicrosoftAuthException ex) when (ex.RateLimited)
            {
                NoteRateLimited(accountId, ex.RetryAfter);
                throw;
            }

            // A real account's refresh token must sign in as THAT account. If
            // it comes back as another profile, the row's tokens are crossed
            // (an older launcher could write one account's rotated token into
            // another): save none of it, so the crossing can't spread or launch
            // the wrong player, and unlink the row's Microsoft sign-in so the
            // next Play / Friends call fails at once instead of spending
            // another login_with_xbox (rate-limited). Adding the account again
            // replaces this row by its real UUID.
            // Pending ids are decided by their prefix, never by re-reading the
            // file (a read error there must not make a pending row look real).
            if (result.Session.Uuid != accountId && !accountId.StartsWith(PendingIdPrefix, StringComparison.Ordinal))
            {
                lock (_lock)
                {
                    // Keep a session adopted after this sign-in started (the
                    // account was just added again); drop anything older.
                    if (!_lastSignIn.TryGetValue(accountId, out var at) || at <= startedAt)
                        _cache.Remove(accountId);
                }
                var detail = "silent sign-in returned a different profile than the stored account";
                try
                {
                    _vault.Unlink(accountId, refreshToken);
                }
                catch (Exception ex)
                {
                    // Still the mismatch the player must act on; the failed
                    // unlink only means the next call checks again.
                    detail += $"; unlinking the row failed: {ex.GetType().Name}: {ex.Message}";
                }
                throw new MicrosoftAuthException(MicrosoftAuthException.AccountMismatchStage,
                    "This account's saved sign-in belongs to a different Minecraft account. Add the account again to fix it.",
                    detail);
            }

            _vault.Save(accountId, result);
            var session = result.Session;
            lock (_lock)
            {
                if (session.Uuid != accountId) _cache.Remove(accountId); // a pending account resolved
                _cache[session.Uuid] = session;
                _lastSignIn[session.Uuid] = _clock();
                _blockedUntil.Remove(accountId);
                _blockedUntil.Remove(session.Uuid);
            }
            return session;
        }
        finally
        {
            _signInGate.Release();
            lock (_lock) _inFlight.Remove(accountId);
        }
    }
}
