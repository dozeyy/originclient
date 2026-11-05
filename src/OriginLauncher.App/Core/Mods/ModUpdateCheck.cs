using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Net;

namespace OriginLauncher.App.Core.Mods;

/// <summary>
/// The Mods page's update check, shared by the Windows, Mac and Linux pages: which of the player's own enabled jars in
/// a mods folder (the version's shared one, or a profile's) Modrinth has a newer release of (Core/Compat/
/// ModrinthUpdates). Runs in the background after the page shows, never before it (opening a screen stays instant),
/// and at most once per folder per 6 hours unless the jars change (a check that got no answer is asked again after 2 minutes); the answer is remembered per folder for the
/// launcher run. 1.8.9 (Forge) is not asked about. UI-thread callers only await; the hashing and the network run on
/// the pool.
/// </summary>
public static class ModUpdateCheck
{
    private static readonly HttpClient Http = DownloadHttp.Create("OriginLauncher/1.0 (+will@willhenry.me)");
    private static readonly object Gate = new();

    // Failed = the check got no answer (offline, Modrinth down): Offers are the last real ones, asked again soon.
    private sealed record Entry(string Signature, DateTime CheckedUtc, IReadOnlyDictionary<string, ModUpdateOffer> Offers,
        bool Failed = false);

    private static readonly Dictionary<string, Entry> ByFolder = new(StringComparer.OrdinalIgnoreCase);
    private static readonly HashSet<string> Running = new(StringComparer.OrdinalIgnoreCase);
    // Updates found to break the next Play (RefusalFor), by folder|file|new version: no Update button for them again
    // this launcher run (pressing it would only download and refuse the same file once more).
    private static readonly HashSet<string> Refused = new(StringComparer.OrdinalIgnoreCase);
    // A jar's SHA-512 by (path, size, write time): a re-check of an unchanged folder hashes nothing.
    private static readonly Dictionary<string, (long Length, DateTime Written, string Sha512)> Hashes = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>The update for <paramref name="fileName"/> in the folder of this version/profile, if one is known.</summary>
    public static ModUpdateOffer? OfferFor(string version, string? profileId, string fileName)
    {
        var folder = ModManager.ModsFolder(version, profileId);
        lock (Gate)
            return ByFolder.TryGetValue(folder, out var entry) && entry.Offers.TryGetValue(fileName, out var offer)
                   && !Refused.Contains(RefusedKey(folder, offer)) ? offer : null;
    }

    private static string RefusedKey(string folder, ModUpdateOffer offer) => $"{folder}|{offer.FileName}|{offer.NewVersion}";

    /// <summary>
    /// After the download, before the Replace: the sentence that keeps the old copy when the new one would stop the
    /// next Play (it needs a newer Fabric API / Loader or a library than this folder has; ModImporter.UpdateProblems),
    /// and that offer disappears for this launcher run. Null when the update can go in. A bug in the check never
    /// blocks an update. Reads jars: call it off the UI thread.
    /// </summary>
    public static string? RefusalFor(string version, string? profileId, ModEntry mod, ModUpdateOffer offer, string downloadedJar)
    {
        IReadOnlyList<CompatFinding> problems;
        try
        {
            var context = ModManager.ImportContextFor(version, profileId);
            // The Fabric Loader this version's instance really has (its install stamp), so a "fabricloader >=X" the
            // update declares is judged against it; unknown (no valid stamp: the next Play installs the newest
            // loader) meets any range.
            if (InstalledLoader(version) is { } loader)
                context = context with { Platform = context.Platform with { LoaderVersion = loader } };
            problems = ModImporter.UpdateProblems(downloadedJar, mod.FileName, context);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            System.Diagnostics.Trace.WriteLine($"[ModUpdateCheck] dependency check of {offer.NewFileName}: {ex.Message}");
            return null;
        }
        if (problems.Count == 0) return null;
        lock (Gate) Refused.Add(RefusedKey(ModManager.ModsFolder(version, profileId), offer));
        return RefusedLine(mod.DisplayName, offer.NewVersion, version, problems);
    }

    private static string? InstalledLoader(string version)
    {
        var decision = Versions.InstallVerification.Evaluate(OriginPaths.VersionRoot(version), version);
        if (decision.FullVerify || Versions.FabricLoaderFloor.ForcesFullVerify(decision.CachedVersionName)) return null;
        return GameFolders.SharedMods.LoaderVersionOf(decision.CachedVersionName)?.ToString();
    }

    /// <summary>"Sodium Extra 0.6.1 wasn't updated: it needs fabric-api >=0.100.0, and Origin has 0.92.2 for 1.21.1.
    /// Your current copy is kept."</summary>
    public static string RefusedLine(string modName, string newVersion, string mcVersion, IReadOnlyList<CompatFinding> problems)
    {
        static string Why(CompatFinding f, string mc) => f.Kind switch
        {
            FindingKind.Breaks => $"it doesn't work with {f.TargetId}{(f.Found is { } found ? " " + DependencyRules.Shown(found) : "")}",
            FindingKind.WrongVersion when f.Found is { } found =>
                $"it needs {f.TargetId}{(f.Range is { } r ? " " + r : "")}, and {(f.TargetIsOrigin ? "Origin has" : "this folder has")} {DependencyRules.Shown(found)} for {mc}",
            _ => $"it needs {f.TargetId}{(f.Range is { } r2 ? " " + r2 : "")}, which isn't installed for {mc}",
        };
        var reasons = problems.Take(2).Select(f => Why(f, mcVersion)).ToList();
        var more = problems.Count > 2 ? $" (and {problems.Count - 2} more)" : "";
        return $"{modName} {newVersion} wasn't updated: {string.Join("; ", reasons)}{more}. Your current copy is kept.";
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
                if (before != null && before.Signature == signature
                    && !ModrinthUpdates.Stale(before.CheckedUtc, before.Failed, DateTime.UtcNow)) return false;

                var byFile = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (var jar in jars)
                    if (HashOf(Path.Combine(folder, jar)) is { } hash) byFile[jar] = hash;
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(45));
                var found = byFile.Count == 0
                    ? new Dictionary<string, ModUpdateOffer>(StringComparer.OrdinalIgnoreCase)
                    : await ModrinthUpdates.FindAsync(Http, version, byFile, cts.Token).ConfigureAwait(false);
                // No answer: keep the Update buttons already known and ask again in a couple of minutes (NET-5).
                var offers = found ?? ModrinthUpdates.KeptAfterFailure(before?.Offers, jars);
                lock (Gate) ByFolder[folder] = new Entry(signature, DateTime.UtcNow, offers, Failed: found == null);
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

    /// <summary>
    /// Downloads and checks an update (see ModrinthUpdates.DownloadAsync), then checks that it would still load here
    /// (RefusalFor): one that needs a newer Fabric API / Loader than this version has is discarded and its sentence
    /// comes back as the error, so the old jar stays. The page then runs its Replace with the path.
    /// </summary>
    public static Task<(string? Path, string? Error)> DownloadAsync(string version, string? profileId, ModEntry mod, ModUpdateOffer offer)
    {
        var installed = Path.Combine(ModManager.ModsFolder(version, profileId), offer.FileName);
        return Task.Run(async () =>
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(5));
            try
            {
                var (path, error) = await ModrinthUpdates.DownloadAsync(Http, offer, installed, OriginPaths.ModUpdateDownloads, cts.Token)
                    .ConfigureAwait(false);
                if (path != null && RefusalFor(version, profileId, mod, offer, path) is { } refusal)
                {
                    ModrinthUpdates.Discard(path);
                    return ((string?)null, (string?)refusal);
                }
                return (path, error);
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
    /// <remarks>The import's own notes (what the new copy still lacks, "Shaders are off for this profile...") come
    /// after it: the line used to replace them, so the one warning that mattered was never seen (N92-2).</remarks>
    public static string? UpdatedLine(ReplaceOffer offer, ImportReport report)
    {
        if (!offer.IsUpdate || report is not { Ok: true, Added: > 0 }) return null;
        var line = $"Updated {offer.ModName} to {offer.NewVersion}. The old file is kept, turned off.";
        if (report.Notes.Count == 0) return line;
        var more = report.Notes.Count > 2 ? $" (and {report.Notes.Count - 2} more)" : "";
        return $"{line} {string.Join(" ", report.Notes.Take(2))}{more}";
    }

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
