using CmlLib.Core.Auth;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth.Sessions;

namespace OriginLauncher.App.Core.Auth;

/// <summary>
/// The one place a stored account becomes a live Minecraft session (Play,
/// Origin social and the account switcher all come through here). The rules —
/// reuse each account's session until shortly before it expires, one sign-in
/// at a time, back off after a 429 — live in <see cref="MinecraftSessions"/>
/// (pure, tested); this class wires them to accounts.json, DPAPI and the real
/// Microsoft chain.
///
/// Mojang rate-limits login_with_xbox, so a typical day signs each account in
/// at most once: the session (access token, uuid, name, expiry) is saved with
/// the account, DPAPI-protected like its refresh token, and reused across
/// Play presses, account switches, Friends and launcher restarts.
/// </summary>
public static class MinecraftSessionBroker
{
    private static readonly MinecraftSessions Sessions = new(new AccountSessionVault(), new LiveSignIn());

    /// <summary>
    /// Play: the account's cached session (after a quick check that Mojang
    /// still accepts it), else a fresh sign-in. Bumps "last used".
    /// Null = the stored Microsoft sign-in can't be read: re-add the account.
    /// Throws MicrosoftAuthException (RateLimited = Microsoft said "slow down").
    /// </summary>
    public static async Task<MSession?> ForPlayAsync(string accountId, CancellationToken ct = default) =>
        ToMSession(await Sessions.ForPlayAsync(accountId, ct).ConfigureAwait(false));

    /// <summary>Friends: the cached session, else a sign-in. Doesn't touch "last used".</summary>
    public static async Task<MSession?> GetAsync(string accountId, CancellationToken ct = default) =>
        ToMSession(await Sessions.GetAsync(accountId, ct).ConfigureAwait(false));

    /// <summary>
    /// Friends: Mojang refused the token. A fresh sign-in, but at most once per
    /// account per 30 minutes (MinecraftSessions.RenewInterval).
    /// </summary>
    public static async Task<MSession?> RenewAsync(string accountId, CancellationToken ct = default) =>
        ToMSession(await Sessions.RenewAsync(accountId, ct).ConfigureAwait(false));

    /// <summary>
    /// Store a freshly added account (the Microsoft window just finished).
    /// With a session: saved with it and selected, so the first Play reuses it.
    /// Without one (the Minecraft step hit a 429): kept as a pending account
    /// holding its Microsoft refresh token, so the player never has to redo
    /// the Microsoft window; its Minecraft step finishes later
    /// (<see cref="FinishPendingAsync"/>). Returns the stored account's id.
    /// </summary>
    public static string AddSignedIn(AuthResult result)
    {
        var protectedRefresh = AccountStore.ProtectRefreshToken(result.MsaRefreshToken);
        if (FromMSession(result.Session, result.ExpiresAt) is { } session)
        {
            var stored = new StoredAccount
            {
                Id = session.Uuid,
                Gamertag = session.Name,
                LastUsedUtc = DateTimeOffset.UtcNow,
                ProtectedRefreshToken = protectedRefresh,
                ProtectedMinecraftSession = AccountStore.ProtectSession(session),
            };
            AccountStore.Update(accounts => AccountStore.Upsert(accounts, stored));
            Sessions.Adopt(session);
            return stored.Id;
        }

        var pending = new StoredAccount
        {
            Id = "pending-" + Guid.NewGuid().ToString("N"),
            Gamertag = "New account",
            LastUsedUtc = DateTimeOffset.UtcNow,
            ProtectedRefreshToken = protectedRefresh,
            PendingMinecraftSignIn = true,
        };
        // Not Upsert: a pending account is never selected (AccountStore.GetSelected).
        AccountStore.Update(accounts =>
        {
            accounts.Add(pending);
            return accounts;
        });
        Sessions.NoteRateLimited(pending.Id, result.Deferred?.RetryAfter);
        return pending.Id;
    }

    /// <summary>
    /// Finish a pending account's Minecraft step. On success it becomes a real
    /// account (its id turns into the Minecraft UUID — returned in the
    /// session) and is selected. Throws MicrosoftAuthException; a 429 (or
    /// still waiting one out) leaves it pending.
    /// </summary>
    public static async Task<MSession?> FinishPendingAsync(string pendingId, CancellationToken ct = default)
    {
        var session = await Sessions.GetAsync(pendingId, ct).ConfigureAwait(false);
        if (session == null) return null;
        AccountStore.Update(accounts =>
        {
            AccountStore.SetSelected(accounts, session.Uuid);
            return accounts;
        });
        return ToMSession(session);
    }

    /// <summary>Drop an account that can never finish (its Microsoft sign-in is gone).</summary>
    public static void RemovePending(string pendingId) =>
        AccountStore.Update(accounts =>
        {
            accounts.RemoveAll(a => a.Id == pendingId && a.PendingMinecraftSignIn);
            return accounts;
        });

    private static MSession? ToMSession(MinecraftSession? session) => session == null
        ? null
        : new MSession
        {
            Username = session.Name,
            UUID = session.Uuid,
            AccessToken = session.AccessToken,
            UserType = "msa",
            Xuid = session.Xuid,
        };

    private static MinecraftSession? FromMSession(MSession? session, DateTimeOffset expiresAt) =>
        session is { AccessToken.Length: > 0, UUID.Length: > 0 }
            ? new MinecraftSession(session.Username ?? "", session.UUID, session.AccessToken, session.Xuid ?? "",
                expiresAt == default ? DateTimeOffset.UtcNow.AddHours(24) : expiresAt)
            : null;

    /// <summary>The real network side: Microsoft's silent chain + Mojang's profile check.</summary>
    private sealed class LiveSignIn : ISessionSignIn
    {
        public async Task<SilentSignIn> SignInAsync(string refreshToken, CancellationToken ct)
        {
            var result = await new MicrosoftAuthenticator().SignInSilentlyAsync(refreshToken, ct).ConfigureAwait(false);
            var session = FromMSession(result.Session, result.ExpiresAt)
                ?? throw new MicrosoftAuthException("profile",
                    "Couldn't load your Minecraft profile. Please try again in a moment.", "silent sign-in returned no session");
            return new SilentSignIn(session, result.MsaRefreshToken);
        }

        public Task<TokenCheck> CheckAsync(string accessToken, CancellationToken ct) =>
            MicrosoftAuthenticator.CheckMinecraftTokenAsync(accessToken, ct);
    }

    /// <summary>accounts.json: refresh token + cached session, both DPAPI-protected.</summary>
    private sealed class AccountSessionVault : ISessionVault
    {
        public string? RefreshToken(string accountId) =>
            Find(AccountStore.Load(), accountId) is { } account
                ? AccountStore.TryUnprotectRefreshToken(account.ProtectedRefreshToken)
                : null;

        public MinecraftSession? SavedSession(string accountId) =>
            Find(AccountStore.Load(), accountId) is { PendingMinecraftSignIn: false } account
                ? AccountStore.TryUnprotectSession(account.ProtectedMinecraftSession)
                : null;

        public void Save(string accountId, SilentSignIn signIn)
        {
            var session = signIn.Session;
            var protectedRefresh = AccountStore.ProtectRefreshToken(signIn.RefreshToken);
            var protectedSession = AccountStore.ProtectSession(session);
            AccountStore.Update(accounts =>
            {
                var stored = Find(accounts, accountId);
                if (stored == null) return accounts;
                if (stored.PendingMinecraftSignIn)
                {
                    // The pending account becomes the real one — or, when this
                    // Minecraft account was already in the list, refreshes it.
                    var existing = accounts.FirstOrDefault(a => a.Id == session.Uuid && !a.PendingMinecraftSignIn);
                    if (existing != null)
                    {
                        accounts.Remove(stored);
                        stored = existing;
                    }
                    else
                    {
                        stored.Id = session.Uuid;
                        stored.PendingMinecraftSignIn = false;
                    }
                }
                if (!string.IsNullOrEmpty(session.Name)) stored.Gamertag = session.Name;
                stored.ProtectedRefreshToken = protectedRefresh;
                stored.ProtectedMinecraftSession = protectedSession;
                return accounts;
            });
        }

        public void Forget(string accountId) =>
            AccountStore.Update(accounts =>
            {
                if (Find(accounts, accountId) is { } stored) stored.ProtectedMinecraftSession = null;
                return accounts;
            });

        public void MarkUsed(string accountId) =>
            AccountStore.Update(accounts =>
            {
                if (Find(accounts, accountId) is { } stored) stored.LastUsedUtc = DateTimeOffset.UtcNow;
                return accounts;
            });

        private static StoredAccount? Find(List<StoredAccount> accounts, string accountId) =>
            accounts.FirstOrDefault(a => a.Id == accountId);
    }
}
