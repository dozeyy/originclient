using System.Net.Http;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;

namespace OriginLauncher.App.Core.Social.Launcher;

/// <summary>
/// The launcher's <see cref="IdentitySource"/>: the selected Microsoft
/// account's Minecraft session via <see cref="MinecraftSessionBroker"/>. No
/// account (offline test mode included) = no social; the drawer explains.
/// The broker reuses the account's saved session (shared with Play), and a
/// forced refresh (Mojang refused the token) signs in again at most once per
/// account per 30 minutes. A 429 from Microsoft = Unreachable with the
/// friendly line, so Friends waits on its normal backoff.
/// </summary>
public static class LauncherIdentity
{
    public static async Task<IdentityResult> GetAsync(bool forceRefresh, CancellationToken ct)
    {
        var account = AccountStore.GetSelected(AccountStore.Load());
        if (account == null)
            return IdentityResult.NoAccount("Sign in with Microsoft to use friends.");

        try
        {
            var session = forceRefresh
                ? await MinecraftSessionBroker.RenewAsync(account.Id, ct).ConfigureAwait(false)
                : await MinecraftSessionBroker.GetAsync(account.Id, ct).ConfigureAwait(false);
            if (session?.AccessToken is not { Length: > 0 } token || session.UUID is not { Length: > 0 } uuid)
                return IdentityResult.SignInFailed("Your sign-in expired. Remove and re-add your account to use friends.");
            return IdentityResult.Ok(new MinecraftIdentity(session.Username ?? account.Gamertag, uuid, token));
        }
        catch (MicrosoftAuthException ex) when (ex.RateLimited)
        {
            return IdentityResult.Unreachable(MicrosoftAuthException.RateLimitedMessage);
        }
        catch (MicrosoftAuthException)
        {
            return IdentityResult.SignInFailed("Couldn't sign in to friends right now. Origin will try again soon.");
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            return IdentityResult.Unreachable("Can't reach Microsoft right now.");
        }
        catch (HttpRequestException)
        {
            return IdentityResult.Unreachable("Can't reach Microsoft right now.");
        }
        catch (System.Text.Json.JsonException)
        {
            return IdentityResult.SignInFailed("Couldn't sign in to friends right now. Origin will try again soon.");
        }
    }
}
