using System.IO;

namespace OriginLauncher.App.Core.Import;

// Small file facts every planner needs, in one place so each one handles a vanished file the same way:
// a source can change while it is being planned (a launcher cleans up, the player deletes a pack), and that
// is never a reason for a plan to throw.
public static class ImportFiles
{
    /// <summary>The file's size, or null when it can't be read (gone, no access, a bad path).</summary>
    public static long? SizeOrNull(string path)
    {
        try
        {
            var info = new FileInfo(path);
            return info.Exists ? info.Length : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return null;
        }
    }

    /// <summary>The file's size, or 0 when it can't be read: for totals, where a vanished file adds nothing.</summary>
    public static long SizeOf(string path) => SizeOrNull(path) ?? 0;

    /// <summary>The volume is full (ERROR_DISK_FULL / ERROR_HANDLE_DISK_FULL): always a destination failure.</summary>
    public static bool IsDiskFull(Exception ex) =>
        ex is IOException && (ex.HResult & 0xFFFF) is 0x70 or 0x27;

    // Deletes inside Origin's OWN folders (staging, backups, an undo) that a read-only file can't stop. A read-only
    // file (a backed-up options.txt the player locked) made Directory.Delete throw, which left staging and old
    // backups on disk for good and made an undo report a problem. Never used on a source.

    public static void MakeWritable(string path)
    {
        var attributes = File.GetAttributes(path);
        if (attributes.HasFlag(FileAttributes.ReadOnly)) File.SetAttributes(path, attributes & ~FileAttributes.ReadOnly);
    }

    public static void DeleteFile(string path)
    {
        try { File.Delete(path); }
        catch (UnauthorizedAccessException) when (File.Exists(path) && File.GetAttributes(path).HasFlag(FileAttributes.ReadOnly))
        {
            MakeWritable(path);
            File.Delete(path);
        }
    }

    public static void DeleteTree(string dir)
    {
        if (!Directory.Exists(dir)) return;
        try { Directory.Delete(dir, recursive: true); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Clear read-only on what is left (links are not followed out of the folder), then once more.
            var walk = new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = FileAttributes.ReparsePoint, IgnoreInaccessible = true };
            foreach (var file in Directory.EnumerateFiles(dir, "*", walk)) MakeWritable(file);
            Directory.Delete(dir, recursive: true);
        }
    }
}

/// <summary>
/// A problem with the SOURCE (a file that can't be opened or read, part of a folder that can't be listed, a link):
/// a bulk import leaves that item out instead of failing. Thrown by the transaction's source reads (CopyFile,
/// CopyTree); anything else that goes wrong while writing is a destination problem.
/// </summary>
public sealed class SourceUnreadableException : IOException
{
    public SourceUnreadableException(string message) : base(message) { }
}
