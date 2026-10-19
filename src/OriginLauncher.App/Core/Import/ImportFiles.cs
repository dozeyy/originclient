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

    /// <summary>True when the file exists and can be opened for reading right now (a quick probe; nothing is read).</summary>
    public static bool CanOpen(string path)
    {
        try
        {
            using var _ = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return false;
        }
    }

    /// <summary>The volume is full (ERROR_DISK_FULL / ERROR_HANDLE_DISK_FULL): always a destination failure.</summary>
    public static bool IsDiskFull(Exception ex) =>
        ex is IOException && (ex.HResult & 0xFFFF) is 0x70 or 0x27;
}

/// <summary>
/// A problem with the SOURCE (part of a folder couldn't be read, it is a link): the import leaves that item out
/// instead of failing. Thrown by <see cref="ImportTransaction.CopyTree"/>; anything else that goes wrong while
/// writing is a destination problem.
/// </summary>
public sealed class SourceUnreadableException : IOException
{
    public SourceUnreadableException(string message) : base(message) { }
}
