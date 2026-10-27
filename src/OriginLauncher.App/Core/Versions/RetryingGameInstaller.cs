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
            catch (Exception e) when (!ct.IsCancellationRequested)
            {
                // The half-written file is dropped on every failure (the last one too), so a retry or the next
                // launch starts clean instead of finding a truncated file.
                TryDelete(file.Path);
                if (attempt < Attempts && IsTransient(e)) { /* retry below */ }
                // TPL Dataflow (CmlLib's pipeline) silently DROPS a message whose delegate throws an
                // OperationCanceledException: a timed-out or stalled file (both are TaskCanceledException) would
                // just be missing while the install reports success. As a TimeoutException it fails the pass, and
                // the player gets "A download took too long" with Retry (caught by DownloadStallTests).
                else if (e is OperationCanceledException)
                    throw new TimeoutException($"Downloading {file.Name} timed out after {attempt} tries.", e);
                else throw;
            }
            await Task.Delay(_backoff(attempt), ct).ConfigureAwait(false);
        }
    }

    // Network trouble is worth another try; a full disk, a denied folder or a refused file (404, 403: the server's
    // final answer) is not — it fails the same way again. 408 / 429 are the server asking to come back.
    private static bool IsTransient(Exception e) => e switch
    {
        HttpRequestException { StatusCode: { } status } => (int)status >= 500 || (int)status is 408 or 429,
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
