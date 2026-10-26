using System.Collections.Concurrent;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using OriginLauncher.App.Core.Loaders;

namespace OriginLauncher.App.Core.Components;

/// <summary>
/// Origin's component store (OriginPaths.ComponentStore): every alternate component file at
/// &lt;root&gt;/&lt;family&gt;/&lt;first 12 hex of its SHA-512&gt;/&lt;file name&gt;.
///   * Write-once: a file only ever lands here through ModrinthFileGuard (https on cdn.modrinth.com, safe name,
///     SHA-512 checked while it downloads); a file that is there and matches is never written again.
///   * A file that no longer matches its pin (damaged disk, a hand edit) is deleted and fetched again.
///   * Games never load from here: the launch folder gets a copy (LaunchModFolder.Sync), so nothing here is ever
///     held open by a running game.
/// The SHA-512 of a file is checked once per launcher run per (size, time); after that a launch costs a stat.
/// </summary>
public sealed class ComponentStore
{
    private readonly string _root;
    private readonly HttpClient _http;

    // One writer at a time: two accounts pressing Play together must not share a ".download" temp file.
    private static readonly SemaphoreSlim WriteGate = new(1, 1);
    private static readonly ConcurrentDictionary<string, (long Length, long Ticks)> Verified = new(StringComparer.OrdinalIgnoreCase);

    public ComponentStore(string root, HttpClient http)
    {
        _root = Path.GetFullPath(root);
        _http = http;
    }

    /// <summary>Where <paramref name="pin"/> lives in the store; null when the family, name or hash is not well formed.</summary>
    public string? PathFor(string family, PerfMod pin)
    {
        if (!ComponentPinSet.IsFamily(family) || !ModrinthFileGuard.IsSafeJarName(pin.FileName)) return null;
        if (pin.Sha512 is not { Length: 128 } hash || !hash.All(Uri.IsHexDigit)) return null;
        return Path.Combine(_root, family, hash[..12].ToLowerInvariant(), pin.FileName);
    }

    /// <summary>True when the file is in the store (a stat; the hash is checked by <see cref="EnsureAsync"/>).</summary>
    public bool IsMaterialised(string family, PerfMod pin) => PathFor(family, pin) is { } path && File.Exists(path);

    /// <summary>
    /// The store path of <paramref name="pin"/>, fetching it when it is missing or damaged. Null when it cannot be had
    /// (offline, refused by the guard, wrong bytes): the caller launches without it.
    /// </summary>
    public async Task<string?> EnsureAsync(string family, PerfMod pin, CancellationToken ct)
    {
        if (PathFor(family, pin) is not { } path) return null;
        if (IsIntact(path, pin)) return path;

        await WriteGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (IsIntact(path, pin)) return path; // another launch fetched it while this one waited
            var folder = Path.GetDirectoryName(path)!;
            try
            {
                if (File.Exists(path)) File.Delete(path); // damaged: the only time a store file is ever replaced
                Directory.CreateDirectory(folder);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                return null;
            }
            var result = await ModrinthFileGuard.TryDownloadAsync(_http, pin.Url, pin.FileName, pin.Sha512, folder, ct)
                .ConfigureAwait(false);
            return result == ModrinthDownloadResult.Installed && IsIntact(path, pin) ? path : null;
        }
        finally
        {
            WriteGate.Release();
        }
    }

    /// <summary>Forgets which files were checked (tests that damage a file inside one clock tick).</summary>
    public static void ClearVerified() => Verified.Clear();

    /// <summary>Tests only (the source is linked into the test project): treat the file as checked, as a fetch does.</summary>
    internal static void MarkVerifiedForTests(string path)
    {
        var file = new FileInfo(path);
        Verified[file.FullName] = (file.Length, file.LastWriteTimeUtc.Ticks);
    }

    private static bool IsIntact(string path, PerfMod pin)
    {
        try
        {
            var file = new FileInfo(path);
            if (!file.Exists) return false;
            var stamp = (file.Length, file.LastWriteTimeUtc.Ticks);
            if (Verified.TryGetValue(path, out var seen) && seen == stamp) return true;
            if (!pin.Matches(path)) return false;
            Verified[path] = stamp;
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }
}
