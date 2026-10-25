namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// Play on a missing or partly installed version (Phase 3, U3.3): the install runs as part of Play, under
/// <see cref="InstallJournal"/>. The journal opens before anything is downloaded and closes only when
/// <c>provision</c> returned; a failure or a cancel leaves it open, so the version reads Partial (never
/// Installed) and the next Play finishes the job. An already installed version skips all of this and
/// costs one disk check. Pure logic over InstalledVersions + InstallJournal, so it is unit tested without CmlLib.
/// </summary>
public static class InstallSession
{
    /// <summary>
    /// Runs <paramref name="provision"/> (the whole install-and-build step). Blocking disk reads happen
    /// before the first await, so call it from a worker thread. Exceptions pass through untouched.
    /// </summary>
    public static async Task<T> RunAsync<T>(
        InstalledVersions installed, InstallJournal journal, string version,
        IProgress<string>? progress, Func<Task<T>> provision)
    {
        var state = installed.Check(version).State; // includes an open journal: Partial
        var opened = state is InstallState.NotInstalled or InstallState.Partial;
        if (opened)
        {
            // Said first so the launch screen names the work before the first download starts.
            progress?.Report(state == InstallState.NotInstalled
                ? $"Installing Minecraft {version}"
                : $"Finishing Minecraft {version}");
            journal.Open(version);
        }

        var result = await provision().ConfigureAwait(false);
        if (opened) journal.Close(version);
        return result;
    }
}
