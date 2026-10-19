namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// Crash safety at launcher start: an import whose journal still says "applying" was interrupted (a crash, a
/// power cut) and is rolled back, never resumed. It runs entirely on the thread pool, after the single-instance
/// check and EnsureScaffold, and starts no UI work: when nothing is interrupted it is one Directory.Exists. The
/// log line pair (start, done in N ms) is how "no startup change" is checked.
///
/// While it runs it is "an import writing" (<see cref="ImportRunState"/>, via <see cref="ImportRecoveryRunner"/>):
/// Play stays disabled until it ends. Every import entry waits for <see cref="Recovery"/> before it scans or
/// plans: the real backend awaits it first thing, so the Profiles button, the first-run card and --import are
/// all covered without each caller remembering to.
/// </summary>
public static class ImportStartup
{
    /// <summary>Completes with how many interrupted imports were rolled back (0 when none, or when recovery itself failed). Never faults.</summary>
    public static Task<int> Recovery { get; private set; } = Task.FromResult(0);

    public static void StartRecovery() =>
        Recovery = ImportRecoveryRunner.Run(
            () => ImportRecovery.Recover(OriginPaths.Imports, OriginPaths.Root, new LauncherImportSettings(),
                ImportLog.Write, CancellationToken.None),
            ImportLog.Write);
}
