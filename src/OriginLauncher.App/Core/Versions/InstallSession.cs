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
    /// <paramref name="dropVerifyStamp"/> runs when an install opens: a version that is missing or part
    /// installed must be verified in full this time, never fast-pathed on an old stamp.
    /// <paramref name="ensureRoom"/> runs first when a NEW install opens (not installed at all), before the journal or
    /// any download: it throws to refuse the install (the free-space check, DiskSpace.EnsureRoomForInstall), leaving
    /// nothing half done. A Partial install skips it: what is left may be a few MB, and asking for the whole 1.5 GB
    /// would refuse a finish that fits; a disk that really fills up still says the drive and numbers (VersionManager).
    /// </summary>
    public static async Task<T> RunAsync<T>(
        InstalledVersions installed, InstallJournal journal, string version,
        IProgress<string>? progress, Func<Task<T>> provision, Action<string>? dropVerifyStamp = null,
        Action? ensureRoom = null)
    {
        var state = installed.Check(version).State; // includes an open journal: Partial
        var opened = state is InstallState.NotInstalled or InstallState.Partial;
        if (opened)
        {
            if (state == InstallState.NotInstalled) ensureRoom?.Invoke();
            // Said first so the launch screen names the work before the first download starts. The version is
            // already on screen (eyebrow and numeral), so the line is only the verb.
            progress?.Report(state == InstallState.NotInstalled
                ? "Installing"
                : "Finishing");
            journal.Open(version);
            dropVerifyStamp?.Invoke(version);
        }

        var result = await provision().ConfigureAwait(false);
        if (opened) journal.Close(version);
        return result;
    }
}
