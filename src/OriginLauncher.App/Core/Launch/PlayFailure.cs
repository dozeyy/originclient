using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// What the launch screen says when Play could not finish getting a version ready (Phase 3, U3.3): a
/// short title, one plain sentence of cause, and what to do. Always ends with "Retry" being safe, because
/// nothing was deleted and a half-done install never counts as installed (InstallJournal). Raw exception
/// text never reaches the player; the full error goes to the launcher log.
/// </summary>
public sealed record PlayFailure(string Title, string Message)
{
    // Win32 ERROR_HANDLE_DISK_FULL / ERROR_DISK_FULL, as the low 16 bits of an IOException HResult.
    private const int HandleDiskFull = 39;
    private const int DiskFull = 112;

    /// <summary>The nudge every message ends on.</summary>
    public const string RetryLine = "Your files are safe. Retry picks up where it stopped.";

    public static PlayFailure From(Exception error, string version)
    {
        var title = $"Couldn't get Minecraft {version} ready";
        return new PlayFailure(title, Cause(error, version) + " " + RetryLine);
    }

    private static string Cause(Exception error, string version)
    {
        if (error is OfflineInstallException)
            return "Can't reach Minecraft's download servers. Check your internet connection.";
        for (var e = error; e != null; e = e.InnerException)
        {
            switch (e)
            {
                case IOException io when (io.HResult & 0xFFFF) is HandleDiskFull or DiskFull:
                    return "Your disk is full. Free up some space.";
                case UnauthorizedAccessException:
                    return "Origin can't write to its game folder. Close anything using it.";
                case HttpRequestException { StatusCode: not null } http:
                    return $"The download server refused a file ({(int)http.StatusCode}).";
                case HttpRequestException:
                    return "Can't reach Minecraft's download servers. Check your internet connection.";
                case TimeoutException:
                case TaskCanceledException:
                    return "A download took too long.";
            }
        }
        return "Something went wrong while downloading the game.";
    }
}
