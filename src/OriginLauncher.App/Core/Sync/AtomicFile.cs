using System.IO;

namespace OriginLauncher.App.Core.Sync;

// Every Settings Sync write goes through here: write a sibling temp file, then
// rename it over the target. A same-directory rename is atomic on Windows, so a
// crash or power cut leaves either the old file or the new one — never a
// half-written options.txt that would reset the player's settings.
public static class AtomicFile
{
    public static void WriteAllBytes(string path, byte[] bytes)
    {
        var directory = Path.GetDirectoryName(path)!;
        Directory.CreateDirectory(directory);
        var temporaryPath = Path.Combine(directory, $".{Path.GetFileName(path)}.{Guid.NewGuid():N}.originsync.tmp");
        try
        {
            File.WriteAllBytes(temporaryPath, bytes);
            ReplaceWith(temporaryPath, path);
        }
        finally
        {
            if (File.Exists(temporaryPath))
                File.Delete(temporaryPath);
        }
    }

    // Windows refuses to rename over a file while ANY handle is open on it ("Access to the path is denied", whatever
    // the reader's sharing flags), and readers are everywhere: another import pruning backups reads every journal,
    // Minecraft reads options.txt, antivirus and the search indexer open new files. Those reads take milliseconds,
    // so the rename waits them out (~1.5 s in all) instead of failing the write; a real permission problem still
    // throws once the waits run out. Proven: two imports committing at once rolled one back 7 runs in 10
    // (ImportStressEdgeTests.ReadingAJournalNeverBlocksItsNextAtomicWrite).
    private static readonly int[] RenameWaitsMs = { 2, 5, 10, 20, 40, 80, 150, 250, 400, 500 };

    private static void ReplaceWith(string temporaryPath, string path)
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                File.Move(temporaryPath, path, overwrite: true);
                return;
            }
            catch (Exception ex) when (ex is UnauthorizedAccessException or IOException && attempt < RenameWaitsMs.Length
                                       && File.Exists(temporaryPath))
            {
                Thread.Sleep(RenameWaitsMs[attempt]);
            }
        }
    }

    public static void WriteAllText(string path, string text) =>
        // UTF-8 without a BOM: that is what Minecraft, Gson and Java's
        // Properties write, and a BOM would become part of the first key.
        WriteAllBytes(path, new System.Text.UTF8Encoding(false).GetBytes(text));

    /// <summary>The file's last-write time as Unix milliseconds (UTC).</summary>
    public static long LastWriteMs(string path) =>
        new DateTimeOffset(File.GetLastWriteTimeUtc(path)).ToUnixTimeMilliseconds();
}
