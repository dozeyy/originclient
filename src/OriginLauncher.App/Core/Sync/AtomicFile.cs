using System.IO;

namespace OriginLauncher.App.Core.Sync;

// Every Settings Sync write goes through here: write a sibling temp file, then
// rename it over the target. A same-directory rename is atomic on Windows, so a
// crash or power cut leaves either the old file or the new one — never a
// half-written options.txt that would reset the player's settings.
public static class AtomicFile
{
    /// <param name="waitOutReaders">False on the UI thread: a busy target then fails at once instead of waiting up
    /// to ~1.5 s for a reader to let go (the caller must cope with the throw).</param>
    public static void WriteAllBytes(string path, byte[] bytes, bool waitOutReaders = true)
    {
        var directory = Path.GetDirectoryName(path)!;
        Directory.CreateDirectory(directory);
        var temporaryPath = Path.Combine(directory, $".{Path.GetFileName(path)}.{Guid.NewGuid():N}.originsync.tmp");
        try
        {
            File.WriteAllBytes(temporaryPath, bytes);
            ReplaceWith(temporaryPath, path, waitOutReaders);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    public static void WriteAllText(string path, string text, bool waitOutReaders = true) =>
        // UTF-8 without a BOM: that is what Minecraft, Gson and Java's
        // Properties write, and a BOM would become part of the first key.
        WriteAllBytes(path, new System.Text.UTF8Encoding(false).GetBytes(text), waitOutReaders);

    // Windows refuses to rename over a file while ANY handle is open on it (a sharing violation, or "Access to the
    // path is denied", whatever the reader's sharing flags), and readers are everywhere: another import pruning
    // backups reads every journal, Minecraft reads options.txt, antivirus and the search indexer open new files.
    // Those reads take milliseconds, so the rename waits them out (~1.5 s in all) instead of failing the write.
    // Only those two errors wait: a read-only target, a full disk, a path too long fail at once, since waiting
    // can't change them. Proven: two imports committing at once rolled one back 7 runs in 10
    // (ImportStressEdgeTests.ReadingAJournalNeverBlocksItsNextAtomicWrite).
    private static readonly int[] RenameWaitsMs = { 2, 5, 10, 20, 40, 80, 150, 250, 400, 500 };

    private const int SharingViolation = unchecked((int)0x80070020);
    private const int AccessDenied = unchecked((int)0x80070005);

    private static void ReplaceWith(string temporaryPath, string path, bool waitOutReaders)
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                File.Move(temporaryPath, path, overwrite: true);
                return;
            }
            catch (Exception ex) when (waitOutReaders && attempt < RenameWaitsMs.Length && MayBeAReader(ex, path)
                                       && File.Exists(temporaryPath))
            {
                Thread.Sleep(RenameWaitsMs[attempt]);
            }
        }
    }

    // A reader holding the target open, as far as the error can tell: a sharing violation, or access denied on a
    // target that isn't read-only (a read-only one is the player's choice and stays denied however long we wait).
    private static bool MayBeAReader(Exception ex, string path)
    {
        if (ex.HResult == SharingViolation) return true;
        if (ex is not UnauthorizedAccessException || ex.HResult != AccessDenied) return false;
        try
        {
            return !File.GetAttributes(path).HasFlag(FileAttributes.ReadOnly);
        }
        catch (Exception probe) when (probe is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    /// <summary>The file's last-write time as Unix milliseconds (UTC).</summary>
    public static long LastWriteMs(string path) =>
        new DateTimeOffset(File.GetLastWriteTimeUtc(path)).ToUnixTimeMilliseconds();
}
