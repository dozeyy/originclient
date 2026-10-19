using System.IO;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Updates;

// The integrity check for the self-update zip. Pure (streams + files, no WPF, no network, no
// OriginPaths) so the tests exercise the real copy-and-verify path against temp files.
//
// Policy (docs/RELEASING.md -> Self-update integrity):
//  - A published SHA-256 that the download does not match, or a download cut short of its
//    Content-Length, is REFUSED: the file is deleted and nothing is extracted or applied.
//  - No published SHA-256 at all is ACCEPTED (OkUnverified): releases cut before the pipeline
//    published one must still update, and "no hash" is indistinguishable from "an old release".
//    A hash that is advertised but cannot be fetched or parsed is NOT "no hash": the caller
//    treats that as a failure and retries later (UpdateService).
public enum UpdateVerdict
{
    /// <summary>Hash published and matched.</summary>
    Ok,
    /// <summary>No hash published: accepted, see the policy above.</summary>
    OkUnverified,
    /// <summary>Hash published and the downloaded bytes do not match it.</summary>
    Mismatch,
    /// <summary>The stream ended before the announced Content-Length.</summary>
    Truncated
}

/// <summary>
/// The download failed its integrity check (mismatch, short file, or a published hash we cannot read). It never
/// blocks play: UpdateGate ungates the update and retries quietly later.
/// </summary>
public sealed class UpdateVerificationException(string message) : IOException(message);

public static class UpdateVerifier
{
    /// <summary>
    /// GitHub's asset "digest" field ("sha256:" + 64 hex). Anything else (another algorithm, empty, missing,
    /// a changed format) is "no hash", never a mismatch, so a change on GitHub's side cannot brick updates.
    /// </summary>
    public static string? ParseDigest(string? digest)
    {
        const string prefix = "sha256:";
        if (digest == null || digest.Length != prefix.Length + 64
            || !digest.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
            || !IsHex(digest.AsSpan(prefix.Length))) return null;
        return digest[prefix.Length..].ToLowerInvariant();
    }

    /// <summary>
    /// Reads the published sidecar: a bare 64-digit hex string or sha256sum format ("hex  filename" or
    /// "hex *filename"). Returns lower-case hex, or null when nothing valid is there. With several lines, only the line naming <paramref name="assetName"/> counts.
    /// </summary>
    public static string? ParseSha256(string? text, string assetName)
    {
        if (string.IsNullOrWhiteSpace(text) || text.Length > 4096) return null;
        var lines = text.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        foreach (var raw in lines)
        {
            var line = raw;
            if (line.Length < 64 || !IsHex(line.AsSpan(0, 64))) continue;
            var name = line[64..].Trim().TrimStart('*').Trim();
            if (name.Length == 0 ? lines.Length == 1 : string.Equals(name, assetName, StringComparison.OrdinalIgnoreCase))
                return line[..64].ToLowerInvariant();
        }
        return null;
    }

    private static bool IsHex(ReadOnlySpan<char> s)
    {
        foreach (var c in s)
            if (!Uri.IsHexDigit(c)) return false;
        return true;
    }

    /// <summary>
    /// Copies <paramref name="source"/> to <paramref name="destPath"/>, hashing as it goes (no second
    /// pass over ~126 MB). On Mismatch / Truncated the file is deleted, so a bad download can never be
    /// picked up later. A failure mid-copy (connection reset, stall cancel, disk full) also deletes the
    /// partial file and rethrows.
    /// </summary>
    public static async Task<UpdateVerdict> CopyAndVerifyAsync(
        Stream source, string destPath, long? expectedLength, string? expectedSha256,
        Action<long>? onBytes, CancellationToken ct)
    {
        long received = 0;
        string actual;
        try
        {
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using (var file = new FileStream(destPath, FileMode.Create, FileAccess.Write,
                             FileShare.None, bufferSize: 1 << 16, useAsync: true))
            {
                var buffer = new byte[1 << 16];
                int read;
                while ((read = await source.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
                {
                    await file.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                    hash.AppendData(buffer, 0, read);
                    received += read;
                    onBytes?.Invoke(received);
                }
            }
            actual = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
        }
        catch
        {
            TryDelete(destPath);
            throw;
        }

        UpdateVerdict verdict;
        if (expectedLength is > 0 && received != expectedLength.Value)
            verdict = UpdateVerdict.Truncated;
        else if (expectedSha256 == null)
            verdict = UpdateVerdict.OkUnverified;
        else if (string.Equals(actual, expectedSha256, StringComparison.OrdinalIgnoreCase))
            verdict = UpdateVerdict.Ok;
        else
            verdict = UpdateVerdict.Mismatch;

        if (verdict is UpdateVerdict.Mismatch or UpdateVerdict.Truncated) TryDelete(destPath);
        return verdict;
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}
