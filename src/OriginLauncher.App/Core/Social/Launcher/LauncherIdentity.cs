using System.Net.Http;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;

namespace OriginLauncher.App.Core.Social.Launcher;

/// <summary>
/// The launcher's <see cref="IdentitySource"/>: the selected Microsoft
/// account's Minecraft session via <see cref="MinecraftSessionBroker"/>. No
/// account (offline test mode included) = no social; the drawer explains.
/// </summary>
public static class LauncherIdentity
{
    private static readonly TimeSpan MaxTokenAge = TimeSpan.FromHours(12);

    public static async Task<IdentityResult> GetAsync(bool forceRefresh, CancellationToken ct)
    {
        var account = AccountStore.GetSelected(AccountStore.Load());
        if (account == null)
            return IdentityResult.NoAccount("Sign in with Microsoft to use friends.");

        try
        {
            var session = forceRefresh
                ? await MinecraftSessionBroker.RefreshAsync(account.Id, markUsed: false, ct).ConfigureAwait(false)
                : await MinecraftSessionBroker.GetAsync(account.Id, MaxTokenAge, ct).ConfigureAwait(false);
            if (session?.AccessToken is not { Length: > 0 } token || session.UUID is not { Length: > 0 } uuid)
                return IdentityResult.SignInFailed("Your sign-in expired. Remove and re-add your account to use friends.");
            return IdentityResult.Ok(new MinecraftIdentity(session.Username ?? account.Gamertag, uuid, token));
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
