using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth.Sessions;

namespace OriginLauncher.App.Core.Auth;

/// <summary>
/// accounts.json behind <see cref="MinecraftSessions"/>: each account's
/// Microsoft refresh token + cached Minecraft session, both DPAPI-protected.
/// Its own file (not nested in the broker) so the tests can run it against a
/// temporary accounts.json.
/// </summary>
internal sealed class AccountSessionVault : ISessionVault
{
    public string? RefreshToken(string accountId) =>
        Find(AccountStore.Load(), accountId) is { ProtectedRefreshToken.Length: > 0 } account
            ? AccountStore.TryUnprotectRefreshToken(account.ProtectedRefreshToken)
            : null;

    // Compare-and-swap: only clears the row while it still holds the very
    // refresh token the refused sign-in used. If the account was added again
    // meanwhile (AddSignedIn runs outside the sign-in gate), its fresh tokens
    // stay. Pending rows are never touched (they legitimately change id).
    public void Unlink(string accountId, string usedRefreshToken) =>
        AccountStore.Update(accounts =>
        {
            if (Find(accounts, accountId) is { PendingMinecraftSignIn: false } stored
                && stored.ProtectedRefreshToken.Length > 0
                && AccountStore.TryUnprotectRefreshToken(stored.ProtectedRefreshToken) == usedRefreshToken)
            {
                stored.ProtectedRefreshToken = "";
                stored.ProtectedMinecraftSession = null;
            }
            return accounts;
        });

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
