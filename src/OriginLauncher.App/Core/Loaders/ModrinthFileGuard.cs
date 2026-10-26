using System.IO;
using System.Net.Http;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Loaders;

// The integrity gate for a jar the launcher downloads from Modrinth's live API on its own (Fabric API),
// as opposed to the hand-pinned catalog entries that carry their SHA-512 in PerformanceModCatalog.Data.cs.
// Same policy as the self-update zip (Core/Updates/UpdateVerifier): a download is installed only when it
// is the file Modrinth published, otherwise it is REFUSED, the partial file is deleted, and nothing is
// thrown (a launch is never blocked by a refused optional dependency; it just starts without it).
//
// Refused: a URL that is not https on cdn.modrinth.com, a filename that is not a plain "*.jar" (a path,
// "..", a drive letter or separators would let a response write outside the mods folder), a version that
// publishes no SHA-512, or bytes that do not hash to the published SHA-512. Modrinth publishes SHA-1 and
// SHA-512 (no SHA-256) in files[].hashes.
// Pure on purpose (HttpClient + files, no WPF, no OriginPaths) so every refusal is unit-tested against a stub handler.
public enum ModrinthDownloadResult
{
    /// <summary>Downloaded, hash matched, moved into the mods folder.</summary>
    Installed,
    /// <summary>The URL is not https on cdn.modrinth.com: nothing was requested.</summary>
    RefusedUrl,
    /// <summary>The filename is empty, a path, or not a ".jar": nothing was requested.</summary>
    RefusedFilename,
    /// <summary>Modrinth published no usable SHA-512 for the file: nothing was requested.</summary>
    RefusedNoHash,
    /// <summary>The bytes do not match the published SHA-512: the temp file was deleted.</summary>
    HashMismatch,
    /// <summary>The download failed (HTTP error, network, disk): the temp file was deleted.</summary>
    Failed
}

public static class ModrinthFileGuard
{
    public const string CdnHost = "cdn.modrinth.com";

    /// <summary>True for https://cdn.modrinth.com/... on the default port, with no credentials in the URL.</summary>
    public static bool IsTrustedUrl(string? url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var uri)
        && uri.Scheme == Uri.UriSchemeHttps
        && string.Equals(uri.Host, CdnHost, StringComparison.OrdinalIgnoreCase)
        && uri.IsDefaultPort
        && string.IsNullOrEmpty(uri.UserInfo);

    /// <summary>
    /// A plain file name ending in ".jar": no directory part, no ".." and no separators or drive colon on ANY
    /// platform (checked by character, because Path.GetFileName is platform-dependent), no invalid characters.
    /// </summary>
    public static bool IsSafeJarName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name) || name != name.Trim()) return false;
        if (name.Length > 200 || name == "." || name.Contains("..")) return false;
        if (name.IndexOfAny(['/', '\\', ':']) >= 0) return false;
        if (name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return false;
        return Path.GetFileName(name) == name
            && name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)
            && name.Length > ".jar".Length;
    }

    /// <summary>The lower-case SHA-512 from Modrinth's "hashes" object, or null when absent or not 128 hex digits.</summary>
    public static string? ParseSha512(IReadOnlyDictionary<string, string>? hashes)
    {
        if (hashes == null || !hashes.TryGetValue("sha512", out var h) || h is not { Length: 128 }) return null;
        foreach (var c in h)
            if (!Uri.IsHexDigit(c)) return null;
        return h.ToLowerInvariant();
    }

    /// <summary>The validation half, with no I/O: Installed means "safe to request".</summary>
    public static ModrinthDownloadResult Check(string? url, string? fileName, string? sha512) =>
        !IsTrustedUrl(url) ? ModrinthDownloadResult.RefusedUrl
        : !IsSafeJarName(fileName) ? ModrinthDownloadResult.RefusedFilename
        : sha512 == null ? ModrinthDownloadResult.RefusedNoHash
        : ModrinthDownloadResult.Installed;

    /// <summary>
    /// Validates, downloads to "&lt;name&gt;.download" while hashing, and moves the file into
    /// <paramref name="modsFolder"/> only when the SHA-512 matches. Every failure deletes the temp file and
    /// returns a result; only a cancellation (the player started another launch) is rethrown, after cleanup.
    /// </summary>
    public static async Task<ModrinthDownloadResult> TryDownloadAsync(
        HttpClient http, string? url, string? fileName, string? sha512, string modsFolder, CancellationToken ct)
    {
        var check = Check(url, fileName, sha512);
        if (check != ModrinthDownloadResult.Installed) return check;

        var destPath = Path.Combine(modsFolder, fileName!);
        // Belt and braces after IsSafeJarName: whatever the name was, it must resolve to a direct child of the mods folder.
        var folder = Path.GetFullPath(modsFolder);
        var parent = Path.GetDirectoryName(Path.GetFullPath(destPath));
        if (!string.Equals(parent?.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
                folder.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar), StringComparison.OrdinalIgnoreCase))
            return ModrinthDownloadResult.RefusedFilename;

        var tempPath = destPath + ".download";
        try
        {
            using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();

            string actual;
            await using (var source = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
            await using (var file = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16, useAsync: true))
            {
                using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA512);
                var buffer = new byte[1 << 16];
                int read;
                while ((read = await source.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
                {
                    await file.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                    hash.AppendData(buffer, 0, read);
                }
                actual = Convert.ToHexString(hash.GetHashAndReset());
            }

            if (!string.Equals(actual, sha512, StringComparison.OrdinalIgnoreCase))
            {
                TryDelete(tempPath);
                return ModrinthDownloadResult.HashMismatch;
            }
            // No overwrite: a jar already under this name is the player's (callers skip a present family) and stays.
            File.Move(tempPath, destPath, overwrite: false);
            Mods.OriginInstallRecord.Add(destPath, actual);
            return ModrinthDownloadResult.Installed;
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            TryDelete(tempPath);
            throw;
        }
        catch (Exception)
        {
            // HttpRequestException, IOException, UnauthorizedAccessException, an HttpClient timeout: leave the instance without it.
            TryDelete(tempPath);
            return ModrinthDownloadResult.Failed;
        }
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}
