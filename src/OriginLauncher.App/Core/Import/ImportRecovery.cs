using System.IO;

namespace OriginLauncher.App.Core.Import;

// Crash safety. The launcher calls this once at start-up: any import whose
// journal still says Applying was interrupted (a crash, a power cut, a killed
// process) — it is ROLLED BACK, never resumed, so the player's game folders are
// exactly what they were before the import began. The journal was written
// before every change, so it names everything that may have been touched.
public static class ImportRecovery
{
    /// <summary>
    /// Rolls back every interrupted import under <paramref name="importsRoot"/>.
    /// Logs "import &lt;id&gt;: rolled back interrupted import" for each (to
    /// <paramref name="log"/> and the run's import.log). Returns how many it rolled back.
    /// </summary>
    public static Task<int> RecoverAsync(string importsRoot, string logRoot, IImportSettings? settings,
        Action<string>? log, CancellationToken ct) =>
        Task.Run(() => Recover(importsRoot, logRoot, settings, log, ct), ct);

    public static int Recover(string importsRoot, string logRoot, IImportSettings? settings, Action<string>? log, CancellationToken ct)
    {
        if (!Directory.Exists(importsRoot)) return 0;
        var recovered = 0;
        IEnumerable<string> runs;
        try { runs = Directory.EnumerateDirectories(importsRoot).ToList(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return 0; }

        foreach (var run in runs)
        {
            ct.ThrowIfCancellationRequested();
            var journalPath = Path.Combine(run, ImportLayout.JournalFile);
            if (!File.Exists(journalPath)) continue;
            var journal = ImportJournalStore.Load(journalPath);
            if (journal == null)
            {
                log?.Invoke($"import {Path.GetFileName(run)}: journal unreadable, left as it is");
                continue;
            }
            if (journal.State != ImportState.Applying) continue;

            // An interrupted undo of a FINISHED import keeps its reused game files, like the undo it finishes.
            var problems = ImportUndo.Run(journal, settings, line => AppendLog(run, line), logRoot, keepGameFiles: journal.UndoOfCommitted);
            journal.State = ImportState.RolledBack;
            journal.FinishedUtc = DateTime.UtcNow;
            journal.RollbackProblems = problems;
            // One snapshot with every entry (Load merged the appended log into it); the old log is no longer named.
            var oldLog = journal.EntriesLog;
            journal.EntriesLog = null;
            ImportJournalStore.Save(journal, journalPath);
            if (oldLog != null && Path.GetFileName(oldLog) == oldLog)
            {
                try { File.Delete(Path.Combine(run, oldLog)); }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* never read again */ }
            }
            try
            {
                var staging = Path.Combine(run, ImportLayout.StagingFolder);
                if (Directory.Exists(staging)) Directory.Delete(staging, recursive: true);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* swept later */ }

            var message = $"import {journal.ImportId}: rolled back interrupted import";
            AppendLog(run, message);
            log?.Invoke(message);
            recovered++;
        }
        return recovered;
    }

    private static void AppendLog(string runDir, string line)
    {
        try
        {
            File.AppendAllText(Path.Combine(runDir, ImportLayout.LogFile),
                $"{DateTime.UtcNow:yyyy-MM-dd HH:mm:ss} {line}{System.Environment.NewLine}");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* the log is a courtesy */ }
    }
}

// Keeps the backup/ folders of the newest few COMMITTED imports (so "undo" still
// works for them) and drops older ones; journals, logs and summaries stay.
public static class ImportRetention
{
    public static void PruneBackups(string importsRoot, int keep = ImportLayout.KeepBackupsOf)
    {
        try
        {
            if (!Directory.Exists(importsRoot)) return;
            var committed = new List<(string Dir, DateTime Started)>();
            foreach (var run in Directory.EnumerateDirectories(importsRoot))
            {
                var journal = ImportJournalStore.Load(Path.Combine(run, ImportLayout.JournalFile));
                if (journal?.State == ImportState.Committed) committed.Add((run, journal.StartedUtc));
            }
            foreach (var old in committed.OrderByDescending(c => c.Started).Skip(keep))
            {
                var backup = Path.Combine(old.Dir, ImportLayout.BackupFolder);
                if (Directory.Exists(backup)) Directory.Delete(backup, recursive: true);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // pruning is housekeeping: never fail an import over it
        }
    }
}
