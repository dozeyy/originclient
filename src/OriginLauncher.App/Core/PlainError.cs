using System.ComponentModel;
using System.IO;
using System.Net.Http;

namespace OriginLauncher.App.Core;

/// <summary>
/// Turns a file or network exception into a few plain words for the player ("a file is in use by another
/// program"), so no screen ever shows .NET's own text ("The process cannot access the file 'C:\Users\...'
/// because it is being used by another process") with its full path and Windows user name. The raw
/// exception still goes to the log at the call site. Shared by every launcher (Core is linked on Mac and
/// Linux), so the words name no Windows-only tool.
/// </summary>
public static class PlainError
{
    // Win32 error codes, as the low 16 bits of an IOException HResult (0x8007xxxx).
    private const int SharingViolation = 32;
    private const int LockViolation = 33;
    private const int HandleDiskFull = 39;
    private const int DiskFull = 112;

    /// <summary>"{what}: {reason}." - for example "Couldn't copy the mods: a file is in use by another program."</summary>
    public static string Sentence(string what, Exception error) => $"{what}: {Reason(error)}.";

    /// <summary>The cause as a lower-case clause with no full stop, from the most telling exception in the chain.</summary>
    public static string Reason(Exception error)
    {
        for (var e = error; e != null; e = e.InnerException)
        {
            switch (e)
            {
                case IOException io when IsDiskFull(io):
                    return "the disk is full - free up some space and try again";
                case IOException io when (io.HResult & 0xFFFF) is SharingViolation or LockViolation
                                         && (io.HResult >>> 16) == 0x8007:
                    return "a file is in use by another program - close Minecraft and try again";
                case UnauthorizedAccessException:
                    return "access to a file was blocked - your antivirus may be holding it, or it is read-only";
                case FileNotFoundException:
                case DirectoryNotFoundException:
                    return "a file it needed is missing - it may have been moved or deleted";
                case PathTooLongException:
                    return "a folder name is too long - move it somewhere shorter and try again";
                case InvalidDataException:
                    return "the file is damaged or isn't a Minecraft mod";
                case HttpRequestException:
                    return "Origin couldn't connect - check your internet connection and try again";
                case TimeoutException:
                case TaskCanceledException:
                    return "the connection was too slow - try again in a moment";
                case Win32Exception:
                    return "a program it needed couldn't start - your antivirus may be blocking it";
            }
        }
        return "something unexpected went wrong - try again";
    }

    private static bool IsDiskFull(IOException io) =>
        ((io.HResult >>> 16) == 0x8007 && (io.HResult & 0xFFFF) is HandleDiskFull or DiskFull)
        || (!OperatingSystem.IsWindows() && io.HResult is 28 or 69); // ENOSPC / EDQUOT off Windows (see PlayFailure)
}
