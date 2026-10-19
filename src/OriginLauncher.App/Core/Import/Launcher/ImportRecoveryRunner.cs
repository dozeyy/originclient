using System.Diagnostics;

namespace OriginLauncher.App.Core.Import.Launcher;

// The pure shell around the startup recovery (ImportStartup supplies the real folders and log): "busy" for
// ImportRunState from the moment it is called until it ends, on the thread pool, never throwing, and the
// rule that every import entry waits for it before scanning or planning.
public static class ImportRecoveryRunner
{
    /// <summary>
    /// Marks an import "running" right now (on the calling thread, so Play can never be enabled in between),
    /// then runs <paramref name="recover"/> on the thread pool and releases the mark when it ends, however it ends.
    /// The task completes with how many imports were rolled back and never faults.
    /// </summary>
    public static Task<int> Run(Func<int> recover, Action<string> log)
    {
        var run = ImportRunState.Enter();
        return Task.Run(() =>
        {
            try
            {
                var watch = Stopwatch.StartNew();
                log("import recovery: start");
                var rolledBack = 0;
                try
                {
                    rolledBack = recover();
                }
                catch (Exception ex)
                {
                    // Recovery must never keep the launcher from starting.
                    log("import recovery failed: " + ex.GetType().Name);
                }
                log($"import recovery: done in {watch.ElapsedMilliseconds} ms, rolled back {rolledBack}");
                return rolledBack;
            }
            finally
            {
                run.Dispose();
            }
        });
    }

    /// <summary>
    /// Runs <paramref name="action"/> only after <paramref name="recovery"/> has finished: a scan or plan made
    /// while a rollback is still restoring files would read half-restored folders. A faulted recovery
    /// does not block the import (it logs for itself).
    /// </summary>
    public static async Task<T> AfterRecoveryAsync<T>(Task recovery, Func<Task<T>> action)
    {
        try { await recovery.ConfigureAwait(false); }
        catch (Exception) { /* never blocks an import forever */ }
        return await action().ConfigureAwait(false);
    }
}
