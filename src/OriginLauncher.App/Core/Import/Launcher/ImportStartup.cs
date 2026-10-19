using System.Diagnostics;

namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// Crash safety at launcher start: an import whose journal still says "applying" was interrupted (a crash, a
/// power cut) and is rolled back, never resumed. It runs entirely on the thread pool, after the single-instance
/// check and EnsureScaffold, and starts no UI work: when nothing is interrupted it is one Directory.Exists. The
/// log line pair (start, done in N ms) is how "no startup change" is checked.
/// </summary>
public static class ImportStartup
{
    /// <summary>Completes with how many interrupted imports were rolled back (0 when none, or when recovery itself failed).</summary>
    public static Task<int> Recovery { get; private set; } = Task.FromResult(0);

    public static void StartRecovery()
    {
        Recovery = Task.Run(() =>
        {
            var watch = Stopwatch.StartNew();
            ImportLog.Write("import recovery: start");
            var rolledBack = 0;
            try
            {
                rolledBack = ImportRecovery.Recover(OriginPaths.Imports, OriginPaths.Root, new LauncherImportSettings(),
                    ImportLog.Write, CancellationToken.None);
            }
            catch (Exception ex)
            {
                // Recovery must never keep the launcher from starting.
                ImportLog.Write("import recovery failed: " + ex.GetType().Name);
            }
            ImportLog.Write($"import recovery: done in {watch.ElapsedMilliseconds} ms, rolled back {rolledBack}");
            return rolledBack;
        });
    }
}
