using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.App.Core.Net;

/// <summary>
/// The one retry policy for a file download: Mojang's game files (RetryingGameInstaller) and Modrinth's jars
/// (Fabric API, the perf stack, Simple Voice Chat) all get the same <see cref="Attempts"/> tries with a short capped
/// back-off. A Wi-Fi blip or a stalled CDN edge is worth another try on a fresh connection; a full disk, a denied
/// folder or a server's final answer (404, 403) fails the same way again, so it is never retried.
/// </summary>
public static class DownloadRetry
{
    public const int Attempts = 3;

    /// <summary>1 s, then 2 s: long enough for a blip to pass, short enough that the counter never looks stuck.</summary>
    public static TimeSpan Backoff(int attempt) => TimeSpan.FromSeconds(Math.Min(attempt, 2));

    // 408 / 429 are the server asking to come back. 304 is a CDN glitch: nothing here sends a conditional request
    // (no If-None-Match / If-Modified-Since), so "Not Modified" is an edge answering the wrong question, and a fresh
    // request clears it (Mac CI 2026-11-02, osx-x64 1.21.1).
    public static bool IsTransient(Exception e) => e switch
    {
        HttpRequestException { StatusCode: { } status } => (int)status >= 500 || (int)status is 408 or 429 or 304,
        TimeoutException or HttpRequestException or TaskCanceledException => true,
        UnauthorizedAccessException => false,
        // A full disk fails the same way again on every OS (Win32 0x27/0x70, or ENOSPC/EDQUOT as a raw errno on Mac
        // and Linux): PlayFailure owns that rule, so a full disk is never retried as "network trouble".
        IOException io => !PlayFailure.IsDiskFull(io),
        _ => false,
    };

    /// <summary>
    /// Runs <paramref name="attempt"/> until it succeeds, up to <see cref="Attempts"/> times while its failure is
    /// transient. The last failure (or the first one that isn't transient) is rethrown as it was; the caller's own
    /// cancel is never retried. The attempt must clean up its own half-written file.
    /// </summary>
    public static async Task<T> RunAsync<T>(Func<Task<T>> attempt, CancellationToken ct, Func<int, TimeSpan>? backoff = null)
    {
        for (var n = 1; ; n++)
        {
            try
            {
                return await attempt().ConfigureAwait(false);
            }
            catch (Exception e) when (!ct.IsCancellationRequested && n < Attempts && IsTransient(e))
            {
                // retried below, after the back-off
            }
            await Task.Delay((backoff ?? Backoff)(n), ct).ConfigureAwait(false);
        }
    }

    /// <inheritdoc cref="RunAsync{T}"/>
    public static Task RunAsync(Func<Task> attempt, CancellationToken ct, Func<int, TimeSpan>? backoff = null) =>
        RunAsync(async () => { await attempt().ConfigureAwait(false); return true; }, ct, backoff);
}
