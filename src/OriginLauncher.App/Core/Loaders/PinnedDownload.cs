using System.IO;
using System.Net.Http;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Loaders;

/// <summary>What <see cref="PinnedDownload.TryDownloadAsync"/> did.</summary>
public enum PinnedDownloadResult { Installed, HashMismatch, Failed }

// The legacy Forge files (1.8.9's universal jar and its perf stack) come from Forge's maven and CurseForge's CDN,
// which publish no SHA-512 the way Modrinth does, so they are pinned by the SHA-256 measured on CI. Same shape as
// ModrinthFileGuard: download beside the destination as "<name>.download" while hashing, move in only on a match,
// delete the temp file on every failure, rethrow only a cancellation. Pure (HttpClient + files) for the tests.
public static class PinnedDownload
{
    /// <param name="sha256">Expected SHA-256 (hex). Null = unpinned (only the pulled 1.12.2 stack): no check.</param>
    public static async Task<PinnedDownloadResult> TryDownloadAsync(
        HttpClient http, string url, string destPath, string? sha256, CancellationToken ct)
    {
        var tempPath = destPath + ".download";
        try
        {
            using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();

            string actual;
            await using (var source = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
            await using (var file = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16, useAsync: true))
            {
                using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                var buffer = new byte[1 << 16];
                int read;
                while ((read = await source.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
                {
                    await file.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                    hash.AppendData(buffer, 0, read);
                }
                actual = Convert.ToHexString(hash.GetHashAndReset());
            }

            if (sha256 != null && !string.Equals(actual, sha256, StringComparison.OrdinalIgnoreCase))
            {
                TryDelete(tempPath);
                return PinnedDownloadResult.HashMismatch;
            }
            File.Move(tempPath, destPath, overwrite: true);
            return PinnedDownloadResult.Installed;
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            TryDelete(tempPath);
            throw;
        }
        catch (Exception)
        {
            TryDelete(tempPath);
            return PinnedDownloadResult.Failed;
        }
    }

    /// <summary>True when no hash is pinned or the file's SHA-256 matches it.</summary>
    public static bool Matches(string path, string? sha256)
    {
        if (string.IsNullOrEmpty(sha256)) return true;
        using var stream = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(stream)).Equals(sha256, StringComparison.OrdinalIgnoreCase);
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}
