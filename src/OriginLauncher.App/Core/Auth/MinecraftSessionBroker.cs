using CmlLib.Core.Auth;
using OriginLauncher.App.Core.Accounts;

namespace OriginLauncher.App.Core.Auth;

/// <summary>
/// The one place a stored account becomes a live Minecraft session (Play and
/// Origin social both come through here). Silent sign-ins are serialised, so
/// two callers can never race the same rotating Microsoft refresh token, and
/// the rotated token is persisted (DPAPI) the moment it arrives.
///
/// The resulting session — including its Minecraft access token — is kept in
/// memory only, never written anywhere; it dies with the process.
/// </summary>
public static class MinecraftSessionBroker
{
    private static readonly SemaphoreSlim Gate = new(1, 1);
    private static (string AccountId, MSession Session, DateTimeOffset At)? _cached;

    /// <summary>A fresh sign-in, every time (Play). Null = the stored token can't be read: re-add the account.</summary>
    public static async Task<MSession?> RefreshAsync(string accountId, bool markUsed, CancellationToken ct = default)
    {
        await Gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            return await RefreshCoreAsync(accountId, markUsed, ct).ConfigureAwait(false);
        }
        finally
        {
            Gate.Release();
        }
    }

    /// <summary>
    /// The cached session when it's younger than <paramref name="maxAge"/>
    /// (Minecraft tokens last 24 h), else a fresh sign-in. Doesn't touch
    /// "last used" — only playing does.
    /// </summary>
    public static async Task<MSession?> GetAsync(string accountId, TimeSpan maxAge, CancellationToken ct = default)
    {
        await Gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (_cached is { } c && c.AccountId == accountId && DateTimeOffset.UtcNow - c.At < maxAge)
                return c.Session;
            return await RefreshCoreAsync(accountId, markUsed: false, ct).ConfigureAwait(false);
        }
        finally
        {
            Gate.Release();
        }
    }

    private static async Task<MSession?> RefreshCoreAsync(string accountId, bool markUsed, CancellationToken ct)
    {
        // Re-read the account: a sign-in elsewhere may have rotated its token
        // since the caller's copy was loaded.
        var account = AccountStore.Load().FirstOrDefault(a => a.Id == accountId);
        var refreshToken = account == null ? null : AccountStore.TryUnprotectRefreshToken(account.ProtectedRefreshToken);
        if (refreshToken == null) return null;

        var result = await new MicrosoftAuthenticator().SignInSilentlyAsync(refreshToken, ct).ConfigureAwait(false);

        // Microsoft rotates refresh tokens on use — persist the new one at once.
        AccountStore.Update(accounts =>
        {
            var stored = accounts.FirstOrDefault(a => a.Id == accountId);
            if (stored != null)
            {
                stored.ProtectedRefreshToken = AccountStore.ProtectRefreshToken(result.MsaRefreshToken);
                if (markUsed) stored.LastUsedUtc = DateTimeOffset.UtcNow;
            }
            return accounts;
        });

        _cached = (accountId, result.Session, DateTimeOffset.UtcNow);
        return result.Session;
    }
}
