using System.IO;
using System.Text;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import;

// The only code that writes to the destination during an import. Everything it
// does is reversible, because the journal names it BEFORE it happens:
//  * a new file or folder is "created" (undo deletes it, and any folder the
//    import had to make for it);
//  * a file that was already there is MOVED to backup/ first and journaled as
//    "replaced" (undo moves it back) — nothing is ever overwritten in place;
//  * big trees (worlds, folder packs) are copied to staging/ and only then
//    renamed into place, so a cancel or crash never leaves half a world;
//  * the launcher-settings change is journaled, then applied by the glue.
// Cancel, failure or a crash -> Rollback (or ImportRecovery next launch) undoes
// the journal in reverse. The source is never written.
public sealed class ImportTransaction : IDisposable
{
    private readonly string _importsRoot;
    private readonly string _logRoot;
    private readonly Func<DateTime> _clock;
    private readonly CredentialGuard _guard;
    private StreamWriter? _log;
    private int _backupCounter;
    private StreamWriter? _entries;      // the journal's append-only entries log (ImportJournalStore)
    private FileStream? _entriesStream;
    private int _unsynced;

    // The entries log is forced to the disk every this many entries (and at once for a settings change); each entry
    // is handed to the OS before its op runs either way, which is what a crash of this process needs.
    private const int SyncEvery = 64;

    /// <summary>Every byte this transaction wrote to its journal (snapshots + appended entries): a test seam for the linear-cost rule.</summary>
    public long JournalBytesWritten { get; private set; }

    public ImportJournal Journal { get; }
    public string RunDir { get; }
    public string BackupDir { get; }
    public string StagingDir { get; }

    /// <param name="logRoot">Paths in import.log are shown relative to this (the Origin data folder).</param>
    /// <param name="guard">Asked again by <see cref="CopyFile"/> before it copies a source file (defence in depth: the engine has already filtered).</param>
    public ImportTransaction(string importsRoot, string logRoot, string importId, Func<DateTime> clock,
        string? sourceName, string? targetVersion, CredentialGuard? guard = null, string? targetGameDir = null)
    {
        _guard = guard ?? CredentialGuard.Default;
        _importsRoot = importsRoot;
        _logRoot = logRoot;
        _clock = clock;
        RunDir = ImportLayout.RunDir(importsRoot, importId);
        BackupDir = ImportLayout.Backup(importsRoot, importId);
        StagingDir = ImportLayout.Staging(importsRoot, importId);
        Journal = new ImportJournal
        {
            ImportId = importId, SourceName = sourceName, TargetVersion = targetVersion, TargetGameDir = targetGameDir,
            State = ImportState.Planned, StartedUtc = clock(),
        };
    }

    /// <summary>Picks up an import that already ran (to undo a committed one): the same journal, the same log.</summary>
    public ImportTransaction(string importsRoot, string logRoot, ImportJournal existing, Func<DateTime> clock)
    {
        _guard = CredentialGuard.Default;
        _importsRoot = importsRoot;
        _logRoot = logRoot;
        _clock = clock;
        RunDir = ImportLayout.RunDir(importsRoot, existing.ImportId);
        BackupDir = ImportLayout.Backup(importsRoot, existing.ImportId);
        StagingDir = ImportLayout.Staging(importsRoot, existing.ImportId);
        Journal = existing;
        OpenLog();
    }

    /// <summary>Creates the run folder and journals "Applying". From here on a crash is recoverable.</summary>
    public void Begin()
    {
        Directory.CreateDirectory(RunDir);
        OpenLog();
        Journal.State = ImportState.Applying;
        Snapshot();
        Log("import started");
    }

    private void OpenLog()
    {
        Directory.CreateDirectory(RunDir);
        _log = new StreamWriter(new FileStream(ImportLayout.Log(_importsRoot, Journal.ImportId),
            FileMode.Append, FileAccess.Write, FileShare.Read), new UTF8Encoding(false)) { AutoFlush = true };
    }

    // A state change: the whole journal as one snapshot, and a fresh (empty) entries log after it. O(entries), so only
    // at begin / commit / rollback — never per op.
    private void Snapshot()
    {
        CloseEntries();
        var old = Journal.EntriesLog;
        Journal.EntriesLog = $"entries-{Guid.NewGuid():N}.log";
        JournalBytesWritten += ImportJournalStore.Save(Journal, ImportLayout.Journal(_importsRoot, Journal.ImportId));
        if (old != null && Path.GetFileName(old) == old)
        {
            try { File.Delete(Path.Combine(RunDir, old)); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* not named by the snapshot any more: never read */ }
        }
    }

    // Write-ahead, linear: the entries are appended (one line each) and handed to the OS before the caller changes
    // anything. inMemory = false for a record that only the log needs (a "forget").
    private void Append(IReadOnlyList<JournalEntry> entries, bool sync = false, bool inMemory = true)
    {
        if (entries.Count == 0) return;
        if (Journal.EntriesLog == null) Snapshot();
        if (inMemory) Journal.Entries.AddRange(entries);
        if (_entries == null)
        {
            Directory.CreateDirectory(RunDir);
            _entriesStream = new FileStream(Path.Combine(RunDir, Journal.EntriesLog!), FileMode.Append, FileAccess.Write, FileShare.Read);
            _entries = new StreamWriter(_entriesStream, new UTF8Encoding(false));
        }
        foreach (var entry in entries)
        {
            var line = ImportJournalStore.Line(entry);
            _entries.Write(line);
            _entries.Write('\n');
            JournalBytesWritten += Encoding.UTF8.GetByteCount(line) + 1;
        }
        _entries.Flush();
        _unsynced += entries.Count;
        if (sync || _unsynced >= SyncEvery)
        {
            _entriesStream!.Flush(flushToDisk: true);
            _unsynced = 0;
        }
    }

    private void Append(JournalEntry entry, bool sync = false) => Append(new[] { entry }, sync);

    private void CloseEntries()
    {
        try
        {
            _entries?.Flush();
            _entriesStream?.Flush(flushToDisk: true);
            _entries?.Dispose();
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException) { /* the next snapshot holds every entry anyway */ }
        _entries = null;
        _entriesStream = null;
        _unsynced = 0;
    }

    /// <summary>One line in import.log: an action and a path relative to the data folder. Never contents, never a token.</summary>
    public void Log(string action)
    {
        try { _log?.WriteLine($"{_clock():yyyy-MM-dd HH:mm:ss} {action}"); }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException) { /* a log that can't be written never stops an import or its undo */ }
    }

    private string Rel(string path) => Path.GetRelativePath(_logRoot, path).Replace('\\', '/');

    // ---- operations ----

    /// <summary>Copies one file. A different file already at <paramref name="dest"/> is backed up and replaced.</summary>
    public void CopyFile(string src, string dest, bool touchNow)
    {
        // Never copy a credential-looking file, whoever asks (the engine filters first; this is the backstop).
        if (_guard.IsDenied(Path.GetFileName(src))) throw new IOException($"{Path.GetFileName(src)} is left alone for safety");
        var temp = NewTemp(Path.GetFileName(dest));
        CopySource(src, temp);
        Place(temp, dest, touchNow, Verb: "copied");
    }

    /// <summary>
    /// Copies a SOURCE file to a fresh file. A failure to open or read the source (gone, locked by another program,
    /// a byte-range lock, no access) is a <see cref="SourceUnreadableException"/>: that item is left out. A failure to
    /// write is the destination's and stays a plain IOException (the run rolls back). The source's time is kept, as
    /// File.Copy kept it.
    /// </summary>
    internal static void CopySource(string src, string dest)
    {
        FileStream input;
        try
        {
            input = new FileStream(src, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 81920);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            throw new SourceUnreadableException($"{Path.GetFileName(src)} couldn't be read ({ImportErrors.Plain(ex)})");
        }
        using (input)
        using (var output = new FileStream(dest, FileMode.Create, FileAccess.Write, FileShare.None, 81920))
        {
            var buffer = new byte[81920];
            while (true)
            {
                int n;
                try { n = input.Read(buffer, 0, buffer.Length); }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    throw new SourceUnreadableException($"{Path.GetFileName(src)} couldn't be read ({ImportErrors.Plain(ex)})");
                }
                if (n == 0) break;
                output.Write(buffer, 0, n);
            }
        }
        try { File.SetLastWriteTimeUtc(dest, File.GetLastWriteTimeUtc(src)); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* the time is a courtesy */ }
    }

    /// <summary>Writes a file the importer composed itself, with the same replace-after-backup rule.</summary>
    public void WriteBytes(string dest, byte[] bytes, bool touchNow)
    {
        var temp = NewTemp(Path.GetFileName(dest));
        Directory.CreateDirectory(StagingDir);
        File.WriteAllBytes(temp, bytes);
        Place(temp, dest, touchNow, Verb: "wrote");
    }

    /// <summary>
    /// Changes a file by editing a COPY of it: <paramref name="edit"/> receives a
    /// temp path that already holds the file's current content (or does not
    /// exist yet when the file is new) and changes it. If the result differs, it
    /// is placed over the original after a backup. Returns false when nothing
    /// changed (or the edit produced nothing), true when the file was written.
    /// </summary>
    public bool Rewrite(string dest, Action<string> edit, bool touchNow)
    {
        Directory.CreateDirectory(StagingDir);
        var temp = NewTemp(Path.GetFileName(dest));
        var existed = File.Exists(dest);
        if (existed) File.Copy(dest, temp, overwrite: true);
        edit(temp);
        if (!File.Exists(temp)) return false;
        if (existed && FileCompare.Same(temp, dest)) { File.Delete(temp); return false; }
        Place(temp, dest, touchNow, Verb: existed ? "merged into" : "created");
        return true;
    }

    /// <summary>
    /// Copies a folder tree to staging/, then renames it to <paramref name="destDir"/>
    /// (which must not exist: callers pick a free name). Files the guard denies
    /// are left out. Cancellation is honoured between files and leaves nothing behind.
    /// </summary>
    public void CopyTree(SourceFs fs, string srcDir, string destDir, CancellationToken ct)
    {
        if (Directory.Exists(destDir) || File.Exists(destDir))
            throw new IOException($"{Path.GetFileName(destDir)} already exists");
        // The walk follows the folder it starts at, so that folder (and every segment above it inside the
        // instance) must not be a link: a linked world or pack could lead anywhere.
        if (!fs.IsLinkFree(srcDir))
            throw new SourceUnreadableException($"{Path.GetFileName(srcDir)} is a link, so it wasn't copied");
        var stage = Path.Combine(StagingDir, "tree-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(stage);
        var count = 0;
        var unreadableBefore = fs.UnreadableHits;
        foreach (var file in fs.EnumerateFiles(srcDir, recursive: true))
        {
            ct.ThrowIfCancellationRequested();
            if (!fs.AllowWalked(file)) continue; // the walk already refused links below srcDir (checked above)
            var target = Path.Combine(stage, Path.GetRelativePath(srcDir, file));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            CopySource(file, target); // a file in the world that can't be read: the world is left out, never half-copied
            count++;
        }
        ct.ThrowIfCancellationRequested();
        // A part of the tree that couldn't be listed would make this a half copy (a world missing regions):
        // refuse, so the import rolls back instead of presenting a damaged copy as done.
        if (fs.UnreadableHits > unreadableBefore)
            throw new SourceUnreadableException($"Part of {Path.GetFileName(srcDir)} couldn't be read, so none of it was copied");
        Prepare(destDir, isDirectory: true);
        MoveDirectory(stage, destDir);
        Log($"copied folder {Rel(destDir)} ({count} files)");
    }

    /// <summary>Journals the launcher-settings change (before the glue applies it).</summary>
    public void RecordSettingsDelta(SettingsDelta delta)
    {
        Append(new JournalEntry { Kind = JournalEntryKinds.SettingsDelta, Delta = delta }, sync: true);
        Log("launcher settings: profile + selection updated");
    }

    // ---- Engine B additions (game files, caches): more ways to place things, all journaled first ----

    /// <summary>A fresh path inside staging/ for a plug-in to build a copy in (deleted with the run on commit or rollback).</summary>
    public string NewStagingPath(string leaf) => NewTemp(leaf);

    /// <summary>Places a finished temp file at <paramref name="dest"/>; a file already there is backed up first.</summary>
    public void PlaceFile(string temp, string dest, bool touchNow = false) => Place(temp, dest, touchNow, Verb: "placed");

    /// <summary>Renames a finished staged folder to <paramref name="destDir"/>, which must not exist.</summary>
    public void PlaceDirectory(string stagedDir, string destDir)
    {
        if (Directory.Exists(destDir) || File.Exists(destDir))
            throw new IOException($"{Path.GetFileName(destDir)} already exists");
        Prepare(destDir, isDirectory: true);
        MoveDirectory(stagedDir, destDir);
        Log($"placed folder {Rel(destDir)}");
    }

    /// <summary>Moves a file or a whole folder into backup/ (journaled as replaced, so undo puts it back). Missing = nothing to do.</summary>
    public void RemoveWithBackup(string path)
    {
        var isDirectory = Directory.Exists(path);
        if (!isDirectory && !File.Exists(path)) return;
        var backup = Path.Combine(BackupDir, $"{++_backupCounter:D4}-{Path.GetFileName(path)}");
        Append(new JournalEntry { Kind = JournalEntryKinds.Replaced, Path = path, Backup = backup, IsDirectory = isDirectory });
        Directory.CreateDirectory(BackupDir);
        if (isDirectory) Directory.Move(path, backup); else File.Move(path, backup);
        Log($"backed up {Rel(path)}");
    }

    /// <summary>
    /// Journals many NEW files in one write (thousands of game files would otherwise rewrite the journal once
    /// each). Files that already exist are skipped — they are never journaled, so undo can never delete them.
    /// The caller then creates each file; one that can't be created is withdrawn with <see cref="ForgetNewFile"/>.
    /// </summary>
    public void JournalNewFiles(IReadOnlyList<string> dests)
    {
        var dirs = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var files = new List<string>();
        foreach (var dest in dests)
        {
            if (File.Exists(dest) || Directory.Exists(dest)) continue;
            var chain = new List<string>();
            for (var d = Path.GetDirectoryName(dest); !string.IsNullOrEmpty(d) && !Directory.Exists(d); d = Path.GetDirectoryName(d))
                if (!seen.Contains(d)) chain.Add(d); else break;
            chain.Reverse();
            foreach (var dir in chain) { seen.Add(dir); dirs.Add(dir); }
            files.Add(dest);
        }
        var entries = new List<JournalEntry>(dirs.Count + files.Count);
        foreach (var dir in dirs)
            entries.Add(new JournalEntry { Kind = JournalEntryKinds.Created, Path = dir, IsDirectory = true, GameFile = true });
        foreach (var file in files)
            entries.Add(new JournalEntry { Kind = JournalEntryKinds.Created, Path = file, GameFile = true });
        Append(entries);
    }

    /// <summary>Withdraws a file journaled by <see cref="JournalNewFiles"/> that was not (or must not be) created.</summary>
    public void ForgetNewFile(string dest)
    {
        var index = Journal.Entries.FindLastIndex(e => e.Kind == JournalEntryKinds.Created && !e.IsDirectory && e.Path == dest);
        if (index < 0) return;
        Journal.Entries.RemoveAt(index);
        Append(new[] { new JournalEntry { Kind = JournalEntryKinds.Forget, Path = dest } }, inMemory: false);
    }

    // ---- finishing ----

    public void Commit()
    {
        Journal.State = ImportState.Committed;
        Journal.FinishedUtc = _clock();
        Snapshot();
        Log("import committed");
        DeleteStaging();
        Dispose();
        ImportRetention.PruneBackups(_importsRoot);
    }

    /// <summary>Undoes everything journaled, newest first. Returns the things it could not put back (empty = clean).</summary>
    /// <param name="keepGameFiles">True for the undo of a FINISHED import: reused game files stay (see <see cref="JournalEntry.GameFile"/>).</param>
    public List<string> Rollback(IImportSettings? settings, bool keepGameFiles = false)
    {
        // Nothing here may throw: a full disk or a locked log must not turn "put everything back" into a crash
        // that leaves the journal saying Applying. (If the journal itself can't be saved, it stays Applying and
        // ImportRecovery finishes the job at the next start — undoing twice is harmless.)
        if (keepGameFiles)
        {
            // The undo of a FINISHED import: say so in the journal BEFORE anything moves, and mark it Applying, so a
            // crash mid-undo (or a journal that can't be saved at the end) is finished by ImportRecovery with the
            // same rule — the reused game files stay. Without the flag recovery would delete them.
            Journal.UndoOfCommitted = true;
            Journal.State = ImportState.Applying;
            try { Snapshot(); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* the undo still runs; the end state is saved below if it can be */ }
        }
        var problems = ImportUndo.Run(Journal, settings, Log, _logRoot, keepGameFiles);
        Journal.State = ImportState.RolledBack;
        Journal.FinishedUtc = _clock();
        Journal.RollbackProblems = problems;
        try
        {
            Snapshot();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Journal.State = ImportState.Applying;
            problems.Add("The undo couldn't be written to the import's journal, so it will be checked again the next time Origin starts.");
        }
        Log("import rolled back");
        DeleteStaging();
        Dispose();
        return problems;
    }

    public void Dispose()
    {
        CloseEntries();
        try { _log?.Dispose(); }
        catch (IOException) { /* the log is a courtesy */ }
        _log = null;
    }

    // ---- internals ----

    private string NewTemp(string leaf)
    {
        Directory.CreateDirectory(StagingDir);
        return Path.Combine(StagingDir, "tmp-" + Guid.NewGuid().ToString("N") + "-" + leaf);
    }

    // Journal first, then change: moves an existing file to backup/, makes any missing parent folders.
    private void Prepare(string dest, bool isDirectory)
    {
        var missing = new List<string>();
        for (var d = Path.GetDirectoryName(dest); !string.IsNullOrEmpty(d) && !Directory.Exists(d); d = Path.GetDirectoryName(d))
            missing.Add(d);
        missing.Reverse();
        var entries = new List<JournalEntry>();
        foreach (var dir in missing)
            entries.Add(new JournalEntry { Kind = JournalEntryKinds.Created, Path = dir, IsDirectory = true });

        string? backup = null;
        if (Directory.Exists(dest)) throw new IOException($"A folder is in the way of {Path.GetFileName(dest)}");
        if (File.Exists(dest))
        {
            if (isDirectory) throw new IOException($"A file is in the way of {Path.GetFileName(dest)}");
            backup = Path.Combine(BackupDir, $"{++_backupCounter:D4}-{Path.GetFileName(dest)}");
            entries.Add(new JournalEntry { Kind = JournalEntryKinds.Replaced, Path = dest, Backup = backup });
        }
        else
        {
            entries.Add(new JournalEntry { Kind = JournalEntryKinds.Created, Path = dest, IsDirectory = isDirectory, Recursive = isDirectory });
        }
        Append(entries);

        var parent = Path.GetDirectoryName(dest);
        if (!string.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);
        if (backup != null)
        {
            Directory.CreateDirectory(BackupDir);
            File.Move(dest, backup);
            Log($"backed up {Rel(dest)}");
        }
    }

    private void Place(string temp, string dest, bool touchNow, string Verb)
    {
        Prepare(dest, isDirectory: false);
        File.Move(temp, dest);
        if (touchNow) File.SetLastWriteTimeUtc(dest, _clock());
        Log($"{Verb} {Rel(dest)}");
    }

    // A rename on one volume; across volumes (staging on another drive) copy then delete.
    private static void MoveDirectory(string from, string to)
    {
        try
        {
            Directory.Move(from, to);
        }
        catch (IOException) when (!Directory.Exists(to))
        {
            CopyDirectory(from, to);
            Directory.Delete(from, recursive: true);
        }
    }

    private static void CopyDirectory(string from, string to)
    {
        Directory.CreateDirectory(to);
        foreach (var file in Directory.EnumerateFiles(from))
            File.Copy(file, Path.Combine(to, Path.GetFileName(file)), overwrite: true);
        foreach (var dir in Directory.EnumerateDirectories(from))
            CopyDirectory(dir, Path.Combine(to, Path.GetFileName(dir)));
    }

    private void DeleteStaging()
    {
        try { if (Directory.Exists(StagingDir)) Directory.Delete(StagingDir, recursive: true); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* swept with the run folder */ }
    }
}

/// <summary>Byte comparison of files and folder trees (same size first, then the bytes).</summary>
public static class FileCompare
{
    /// <param name="source">When given, <paramref name="a"/> is a SOURCE file: it is opened through the guard (denied = not the same) so the hook sees it.</param>
    /// <param name="walked"><paramref name="a"/> came from a walk of <paramref name="source"/> (its links are already refused): only the guard is asked.</param>
    public static bool Same(string a, string b, SourceFs? source = null, bool walked = false)
    {
        if (source != null && !(walked ? source.AllowWalked(a) : source.Allow(a))) return false;
        var fa = new FileInfo(a);
        var fb = new FileInfo(b);
        if (!fa.Exists || !fb.Exists || fa.Length != fb.Length) return false;
        try
        {
            using var sa = new FileStream(a, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var sb = new FileStream(b, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            var bufferA = new byte[81920];
            var bufferB = new byte[81920];
            while (true)
            {
                var read = ReadFull(sa, bufferA);
                if (read != ReadFull(sb, bufferB)) return false;
                if (read == 0) return true;
                if (!bufferA.AsSpan(0, read).SequenceEqual(bufferB.AsSpan(0, read))) return false;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static int ReadFull(Stream stream, byte[] buffer)
    {
        var total = 0;
        while (total < buffer.Length)
        {
            var n = stream.Read(buffer, total, buffer.Length - total);
            if (n <= 0) break;
            total += n;
        }
        return total;
    }

    /// <summary>Two folders hold the same files with the same bytes. Files the guard denies are ignored on the source side only.</summary>
    public static bool SameTree(string sourceDir, string destDir, SourceFs fs)
    {
        if (!Directory.Exists(sourceDir) || !Directory.Exists(destDir)) return false;
        var src = fs.EnumerateFiles(sourceDir, recursive: true)
            .Select(f => Path.GetRelativePath(sourceDir, f)).ToHashSet(StringComparer.Ordinal);
        var dst = new SourceFs(fs.Guard).EnumerateFiles(destDir, recursive: true) // the destination: same walking rules, not the source's record
            .Select(f => Path.GetRelativePath(destDir, f)).ToHashSet(StringComparer.Ordinal);
        if (!src.SetEquals(dst)) return false;
        foreach (var rel in src)
            if (!Same(Path.Combine(sourceDir, rel), Path.Combine(destDir, rel), fs, walked: true)) return false;
        return true;
    }
}

// Reverses a journal. Used by a failed/cancelled apply, by "undo this import",
// and by recovery after a crash. Best effort per entry: one stubborn file never
// stops the rest from being restored; whatever could not be is reported.
public static class ImportUndo
{
    /// <param name="keepGameFiles">
    /// True for the undo of a FINISHED import: reused game files are left in place. They are Mojang-verified files
    /// Origin would download anyway, they keep their source's timestamps (so a later change can't be seen), and a
    /// launch may already be using them. A failed, cancelled or crashed apply removes them like everything else.
    /// </param>
    public static List<string> Run(ImportJournal journal, IImportSettings? settings, Action<string> log, string logRoot,
        bool keepGameFiles = false)
    {
        var problems = new List<string>();
        string Rel(string p) => Path.GetRelativePath(logRoot, p).Replace('\\', '/');

        for (var i = journal.Entries.Count - 1; i >= 0; i--)
        {
            var entry = journal.Entries[i];
            if (keepGameFiles && entry.GameFile) continue;
            try
            {
                switch (entry.Kind)
                {
                    case JournalEntryKinds.SettingsDelta:
                        // Revert is safe when the delta was never applied (it was journaled first).
                        if (entry.Delta != null) settings?.Revert(entry.Delta);
                        log("rollback: launcher settings restored");
                        break;

                    case JournalEntryKinds.Created when entry.Path != null:
                        if (entry.IsDirectory)
                        {
                            // A folder the import filled goes with its contents; one it only made to hold
                            // something goes only if nothing else has moved in (e.g. imports/ beside instances/).
                            if (Directory.Exists(entry.Path)
                                && (entry.Recursive || !Directory.EnumerateFileSystemEntries(entry.Path).Any()))
                                Directory.Delete(entry.Path, recursive: entry.Recursive);
                        }
                        else if (File.Exists(entry.Path))
                        {
                            File.Delete(entry.Path);
                        }
                        log($"rollback: removed {Rel(entry.Path)}");
                        break;

                    case JournalEntryKinds.Replaced when entry.Path != null && entry.Backup != null && entry.IsDirectory:
                        // A whole folder that was moved aside (a cache replaced by an import).
                        if (Directory.Exists(entry.Backup))
                        {
                            if (Directory.Exists(entry.Path)) Directory.Delete(entry.Path, recursive: true);
                            var dirParent = Path.GetDirectoryName(entry.Path);
                            if (!string.IsNullOrEmpty(dirParent)) Directory.CreateDirectory(dirParent);
                            Directory.Move(entry.Backup, entry.Path);
                            log($"rollback: restored {Rel(entry.Path)}");
                        }
                        else if (!Directory.Exists(entry.Path))
                        {
                            problems.Add($"Couldn't restore {Path.GetFileName(entry.Path)}: its backup is gone.");
                        }
                        break;

                    case JournalEntryKinds.Replaced when entry.Path != null && entry.Backup != null:
                        if (File.Exists(entry.Backup))
                        {
                            if (File.Exists(entry.Path)) File.Delete(entry.Path);
                            var parent = Path.GetDirectoryName(entry.Path);
                            if (!string.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);
                            File.Move(entry.Backup, entry.Path);
                            log($"rollback: restored {Rel(entry.Path)}");
                        }
                        else if (!File.Exists(entry.Path))
                        {
                            problems.Add($"Couldn't restore {Path.GetFileName(entry.Path)}: its backup is gone.");
                        }
                        // else: the backup move never happened (a crash first), the original is still in place
                        break;
                }
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                // One stubborn entry — or a settings revert that throws — never stops the rest being put back.
                problems.Add($"Couldn't undo {Path.GetFileName(entry.Path ?? entry.Kind)}: {ImportErrors.Plain(ex)}.");
            }
        }
        return problems;
    }

    /// <summary>
    /// Why a FINISHED import can't be undone any more, or null when it can. Two reasons, both about never
    /// destroying anything: its backups were cleared (only the newest few imports keep theirs), or the player
    /// has used what it created since (a world played in, a pack edited, settings changed): undoing would
    /// delete or overwrite their later work. Only the "undo" of a committed import asks this; rolling back a
    /// failed, cancelled or crashed apply (nobody has used those files yet) never does.
    /// </summary>
    public static string? CannotUndoReason(ImportJournal journal)
    {
        foreach (var entry in journal.Entries)
            if (entry.Kind == JournalEntryKinds.Replaced && entry.Backup != null && !File.Exists(entry.Backup) && !Directory.Exists(entry.Backup))
                return "This import is too old to undo: Origin no longer has the saved copies of the files it replaced.";

        var changed = LaterChanges(journal);
        if (changed.Count == 0) return null;
        var shown = string.Join(", ", changed.Take(3).Select(n => $"\"{n}\"")) + (changed.Count > 3 ? $" and {changed.Count - 3} more" : "");
        return $"{shown} changed after the import, so undoing it would lose your changes. Nothing was touched.";
    }

    // Things the import created or replaced whose files have been written to since it finished.
    private static List<string> LaterChanges(ImportJournal journal)
    {
        var changed = new List<string>();
        if (journal.FinishedUtc is not { } finished) return changed;
        var limit = finished.AddSeconds(2); // the clock the files were stamped with is the same one: this is slack, not a window
        foreach (var entry in journal.Entries)
        {
            // Reused game files stay after the undo of a finished import (keepGameFiles), so a later change to one
            // (a launch rewriting an asset index) is nothing the undo would lose: never a reason to refuse.
            if (entry.Path == null || entry.GameFile) continue;
            try
            {
                var newer = entry.Kind switch
                {
                    JournalEntryKinds.Created when entry.IsDirectory && entry.Recursive && Directory.Exists(entry.Path) =>
                        Directory.EnumerateFiles(entry.Path, "*", SearchOption.AllDirectories).Any(f => File.GetLastWriteTimeUtc(f) > limit),
                    JournalEntryKinds.Created or JournalEntryKinds.Replaced when !entry.IsDirectory && File.Exists(entry.Path) =>
                        File.GetLastWriteTimeUtc(entry.Path) > limit,
                    _ => false,
                };
                if (newer) changed.Add(Path.GetFileName(entry.Path));
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* unreadable: not provably changed */ }
        }
        return changed.Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    }
}
