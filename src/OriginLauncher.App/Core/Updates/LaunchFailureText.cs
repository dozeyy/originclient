using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.Core.Updates;

/// <summary>
/// The failure line under Play, update-aware (all three launchers). An out-of-date launcher is almost always failing
/// on a bug that's already fixed, so a failed launch re-checks the release feed and says "update" when there is one.
/// That re-check must never hold the line up (NET-3): a failure that is "no connection" skips it (GitHub is out of
/// reach too), and any other waits at most <see cref="RecheckCap"/>; past that the last known answer is used.
/// Pure (the check and the answer are parameters) so the timing is unit-tested.
/// </summary>
public static class LaunchFailureText
{
    public static readonly TimeSpan RecheckCap = TimeSpan.FromSeconds(5);

    /// <summary>False when the failure itself says the network is down: asking GitHub would only add a wait.</summary>
    public static bool ShouldRecheck(Exception error) =>
        error is not (OfflineInstallException or MicrosoftAuthException { Stage: MicrosoftAuthException.UnreachableStage })
        && !Auth.Sessions.MinecraftSessions.IsUnreachable(error, CancellationToken.None);

    /// <summary>
    /// <paramref name="outdatedLine"/> when the (capped) re-check says this launcher is out of date, else
    /// <paramref name="upToDateMessage"/>. Never throws.
    /// </summary>
    public static async Task<string> ForAsync(Exception error, string upToDateMessage, string outdatedLine,
        Func<Task> recheck, Func<bool> updateRequired, TimeSpan? cap = null)
    {
        if (ShouldRecheck(error))
        {
            try
            {
                await recheck().WaitAsync(cap ?? RecheckCap).ConfigureAwait(false);
            }
            catch (Exception)
            {
                // Timed out or failed: the feed's last answer below decides (UpdateService fails open).
            }
        }
        return updateRequired() ? outdatedLine : upToDateMessage;
    }
}
