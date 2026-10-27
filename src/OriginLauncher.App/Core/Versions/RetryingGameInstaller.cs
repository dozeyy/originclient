using System.IO;
using System.Net.Http;
using CmlLib.Core;
using CmlLib.Core.Files;
using CmlLib.Core.Installers;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// CmlLib's parallel game-file installer, where one bad file no longer decides the whole install.
///
/// CmlLib downloads each file exactly once: a stalled or dropped file either hung the pass forever (no stall
/// timeout, see Core/Net/DownloadHttp) or failed the whole launch. With the DownloadHttp client a stall now throws
/// after 20 s, and this retries that ONE file on a fresh connection (a retry usually lands on another CDN edge),
/// up to <see cref="Attempts"/> times with a short capped back-off. Only after that does the file's error reach the
/// Play failure screen, which says what happened and offers Retry (files already on disk are kept and skipped).
/// </summary>
public sealed class RetryingGameInstaller : ParallelGameInstaller
{
    public const int Attempts = 3;

    private readonly Func<int, TimeSpan> _backoff;

    public RetryingGameInstaller(int maxChecker, int maxDownloader, int boundedCapacity, HttpClient http,
        Func<int, TimeSpan>? backoff = null)
        : base(maxChecker, maxDownloader, boundedCapacity, http)
    {
        // 1 s, then 2 s: long enough for a blip to pass, short enough that the counter never looks stuck.
        _backoff = backoff ?? (attempt => TimeSpan.FromSeconds(Math.Min(attempt, 2)));
    }

    protected override async Task Download(GameFile file, IProgress<ByteProgress>? progress, CancellationToken ct)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                await base.Download(file, progress, ct).ConfigureAwait(false);
                return;
            }
            catch (Exception e) when (attempt < Attempts && !ct.IsCancellationRequested && IsTransient(e))
            {
                // The half-written file is dropped so a retry (or the next launch's size check) starts clean.
                TryDelete(file.Path);
                await Task.Delay(_backoff(attempt), ct).ConfigureAwait(false);
            }
        }
    }

    // Network trouble is worth another try; a full disk or a denied folder is not (it fails the same way again).
    private static bool IsTransient(Exception e) => e switch
    {
        TimeoutException or HttpRequestException or TaskCanceledException => true,
        UnauthorizedAccessException => false,
        IOException io => (io.HResult & 0xFFFF) is not (0x27 or 0x70), // ERROR_HANDLE_DISK_FULL, ERROR_DISK_FULL
        _ => false,
    };

    private static void TryDelete(string? path)
    {
        if (string.IsNullOrEmpty(path)) return;
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}
