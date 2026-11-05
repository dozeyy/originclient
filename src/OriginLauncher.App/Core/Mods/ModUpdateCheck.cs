using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Net;

namespace OriginLauncher.App.Core.Mods;

/// <summary>
/// The Mods page's update check, shared by the Windows, Mac and Linux pages: which of the player's own enabled jars in
/// a mods folder (the version's shared one, or a profile's) Modrinth has a newer release of (Core/Compat/
/// ModrinthUpdates). Runs in the background after the page shows, never before it (opening a screen stays instant),
/// and at most once per folder per 6 hours unless the jars change; the answer is remembered per folder for the
/// launcher run. 1.8.9 (Forge) is not asked about. UI-thread callers only await; the hashing and the network run on
/// the pool.
/// </summary>
public static class ModUpdateCheck
{
    private static readonly TimeSpan Fresh = TimeSpan.FromHours(6);
    private static readonly HttpClient Http = DownloadHttp.Create("OriginLauncher/1.0 (+will@willhenry.me)");
    private static readonly object Gate = new();

    private sealed record Entry(string Signature, DateTime CheckedUtc, IReadOnlyDictionary<string, ModUpdateOffer> Offers);

    private static readonly Dictionary<string, Entry> ByFolder = new(StringComparer.OrdinalIgnoreCase);
    private static readonly HashSet<string> Running = new(StringComparer.OrdinalIgnoreCase);
    // A jar's SHA-512 by (path, size, write time): a re-check of an unchanged folder hashes nothing.
    private static readonly Dictionary<string, (long Length, DateTime Written, string Sha512)> Hashes = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>The update for <paramref name="fileName"/> in the folder of this version/profile, if one is known.</summary>
    public static ModUpdateOffer? OfferFor(string version, string? profileId, string fileName)
    {
        var folder = ModManager.ModsFolder(version, profileId);
        lock (Gate)
            return ByFolder.TryGetValue(folder, out var entry) && entry.Offers.TryGetValue(fileName, out var offer) ? offer : null;
    }

    /// <summary>
    /// Checks the folder if its answer is missing, older than 6 hours, or its jars changed. True when the offers
    /// changed (the page rebuilds its rows). Never throws.
    /// </summary>
    public static async Task<bool> RefreshAsync(string version, string? profileId, IReadOnlyList<string> enabledPlayerJars)
    {
        if (LaunchPreflight.PlatformFor(version).IsLegacyForge) return false;
        var folder = ModManager.ModsFolder(version, profileId);
        lock (Gate)
        {
            if (!Running.Add(folder)) return false; // one check per folder at a time
        }
        try
        {
            return await Task.Run(async () =>
            {
                var jars = enabledPlayerJars.Where(n => n.EndsWith(".jar", StringComparison.OrdinalIgnoreCase))
                    .OrderBy(n => n, StringComparer.OrdinalIgnoreCase).ToList();
                var signature = Signature(folder, jars);
                Entry? before;
                lock (Gate) ByFolder.TryGetValue(folder, out before);
                if (before != null && before.Signature == signature && DateTime.UtcNow - before.CheckedUtc < Fresh) return false;

                var byFile = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var jar in jars)
                    if (HashOf(Path.Combine(folder, jar)) is { } hash) byFile[jar] = hash;
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(45));
                var offers = byFile.Count == 0
                    ? new Dictionary<string, ModUpdateOffer>(StringComparer.OrdinalIgnoreCase)
                    : await ModrinthUpdates.FindAsync(Http, version, byFile, cts.Token).ConfigureAwait(false);
                lock (Gate) ByFolder[folder] = new Entry(signature, DateTime.UtcNow, offers);
                return !SameOffers(before?.Offers, offers);
            }).ConfigureAwait(true);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            // The check is a convenience: a bug here is no Update button, never a broken Mods page.
            System.Diagnostics.Trace.WriteLine($"[ModUpdateCheck] {folder}: {ex.Message}");
            return false;
        }
        finally
        {
            lock (Gate) Running.Remove(folder);
        }
    }

    /// <summary>Downloads and checks an update (see ModrinthUpdates.DownloadAsync). The page then runs its Replace with the path.</summary>
    public static Task<(string? Path, string? Error)> DownloadAsync(string version, string? profileId, ModUpdateOffer offer)
    {
        var installed = Path.Combine(ModManager.ModsFolder(version, profileId), offer.FileName);
        return Task.Run(async () =>
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(5));
            try
            {
                return await ModrinthUpdates.DownloadAsync(Http, offer, installed, OriginPaths.ModUpdateDownloads, cts.Token)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return ((string?)null, (string?)"The download took too long, so nothing was changed. Try again.");
            }
        });
    }

    /// <summary>The row's second line when an update is known: "sodium-0.6.0.jar - 0.6.13 available".</summary>
    public static string Caption(string fileName, ModUpdateOffer offer) => $"{fileName} - {offer.NewVersion} available";

    /// <summary>The Replace an Update button runs once its download is checked (the old jar is kept, turned off).</summary>
    public static ReplaceOffer ReplaceFor(ModEntry mod, ModUpdateOffer offer, string downloadedJar) =>
        new(downloadedJar, offer.NewFileName, mod.DisplayName, offer.NewVersion, mod.FileName, null) { IsUpdate = true };

    /// <summary>The status line after an update's Replace went in; null for any other import (its own message stands).</summary>
    public static string? UpdatedLine(ReplaceOffer offer, ImportReport report) =>
        offer.IsUpdate && report is { Ok: true, Added: > 0 }
            ? $"Updated {offer.ModName} to {offer.NewVersion}. The old file is kept, turned off."
            : null;

    private static string Signature(string folder, IReadOnlyList<string> jars)
    {
        var parts = new List<string>(jars.Count);
        foreach (var jar in jars)
        {
            try
            {
                var info = new FileInfo(Path.Combine(folder, jar));
                parts.Add(info.Exists ? $"{jar}:{info.Length}:{info.LastWriteTimeUtc.Ticks}" : jar);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                parts.Add(jar);
            }
        }
        return string.Join("|", parts);
    }

    private static string? HashOf(string path)
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists) return null;
            lock (Gate)
                if (Hashes.TryGetValue(path, out var known) && known.Length == info.Length && known.Written == info.LastWriteTimeUtc)
                    return known.Sha512;
            var hash = ModrinthUpdates.Sha512Of(path);
            if (hash != null) lock (Gate) Hashes[path] = (info.Length, info.LastWriteTimeUtc, hash);
            return hash;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static bool SameOffers(IReadOnlyDictionary<string, ModUpdateOffer>? a, IReadOnlyDictionary<string, ModUpdateOffer> b) =>
        (a?.Count ?? 0) == b.Count && (a == null || b.All(kv => a.TryGetValue(kv.Key, out var o) && o == kv.Value));
}
