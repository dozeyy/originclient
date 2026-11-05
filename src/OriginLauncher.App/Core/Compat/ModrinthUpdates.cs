using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Loaders;

namespace OriginLauncher.App.Core.Compat;

/// <summary>A newer Modrinth release of one of the player's own jars, for this Minecraft version on Fabric.</summary>
/// <param name="FileName">The installed jar it updates (as on the Mods page).</param>
/// <param name="NewVersion">The release's version number, for the row ("Update to 1.4.2").</param>
/// <param name="NewFileName">The new jar's file name (checked: a plain *.jar name).</param>
/// <param name="Url">Where it downloads from (checked: https on cdn.modrinth.com).</param>
/// <param name="Sha512">Modrinth's published SHA-512 of that file: the download is kept only when its bytes match.</param>
public sealed record ModUpdateOffer(string FileName, string NewVersion, string NewFileName, string Url, string Sha512);

/// <summary>
/// The Mods page's "Update" button: which of the player's own jars Modrinth has a newer release of. Looked up BY FILE
/// HASH, so nothing but the SHA-512 of each of the player's jars, the Minecraft version and "fabric" leave the PC (Will,
/// 2026-10-25: mod ids and hashes only): two POSTs for the whole folder, no file names, no account.
///   1. https://api.modrinth.com/v2/version_files: which Modrinth release each jar IS (unknown jars drop out here);
///   2. https://api.modrinth.com/v2/version_files/update: the newest release of each one's project for this Minecraft
///      version on Fabric.
/// An offer needs: the same project, a different file, a full release (no beta or alpha), published AFTER the jar the
/// player has, made for this Minecraft version and Fabric, and a download Modrinth publishes a SHA-512 for (DECISIONS
/// 2026-10-27: a Modrinth file is installed only when it matches Modrinth's SHA-512). The download itself is
/// <see cref="ModrinthFileGuard"/>'s (https on cdn.modrinth.com, safe name, hash checked while it streams), and the
/// swap into the mods folder is the Mods page's own Replace (ModImporter: add beside, old jar renamed to .disabled).
/// Parsing and the offer rules are pure and unit tested; only <see cref="FindAsync"/> and <see cref="DownloadAsync"/>
/// touch the network.
/// </summary>
public static class ModrinthUpdates
{
    public const string ApiBase = "https://api.modrinth.com/v2/";

    /// <summary>At most this many jars are asked about per check (a folder with more is asked about the first ones).</summary>
    public const int MaxJars = 300;

    internal sealed record Release(string ProjectId, string Number, string Type, DateTimeOffset Published,
        IReadOnlyList<string> Loaders, IReadOnlyList<string> Games, string? Url, string? FileName, string? Sha512);

    /// <summary>Modrinth's "hash -> version" answer (both endpoints answer in this shape). Unreadable entries drop out.</summary>
    internal static Dictionary<string, Release> ParseByHash(string json)
    {
        var map = new Dictionary<string, Release>(StringComparer.OrdinalIgnoreCase);
        using var doc = JsonDocument.Parse(json);
        if (doc.RootElement.ValueKind != JsonValueKind.Object) return map;
        foreach (var entry in doc.RootElement.EnumerateObject())
        {
            var v = entry.Value;
            if (v.ValueKind != JsonValueKind.Object) continue;
            if (Str(v, "project_id") is not { Length: > 0 } project) continue;
            if (Str(v, "version_number") is not { Length: > 0 and <= 64 } number || number.Any(char.IsControl)) continue;
            if (!DateTimeOffset.TryParse(Str(v, "date_published"), System.Globalization.CultureInfo.InvariantCulture,
                    System.Globalization.DateTimeStyles.AssumeUniversal, out var published)) continue;

            string? url = null, name = null, sha512 = null;
            if (v.TryGetProperty("files", out var files) && files.ValueKind == JsonValueKind.Array)
            {
                var file = files.EnumerateArray().FirstOrDefault(f => f.ValueKind == JsonValueKind.Object
                    && f.TryGetProperty("primary", out var p) && p.ValueKind == JsonValueKind.True);
                if (file.ValueKind != JsonValueKind.Object) file = files.EnumerateArray().FirstOrDefault();
                if (file.ValueKind == JsonValueKind.Object)
                {
                    url = Str(file, "url");
                    name = Str(file, "filename");
                    if (file.TryGetProperty("hashes", out var hashes) && hashes.ValueKind == JsonValueKind.Object)
                    {
                        var h = new Dictionary<string, string>(StringComparer.Ordinal);
                        foreach (var p in hashes.EnumerateObject())
                            if (p.Value.ValueKind == JsonValueKind.String) h[p.Name] = p.Value.GetString()!;
                        sha512 = ModrinthFileGuard.ParseSha512(h);
                    }
                }
            }
            map[entry.Name] = new Release(project, number, Str(v, "version_type") ?? "", published,
                Strings(v, "loaders"), Strings(v, "game_versions"), url, name, sha512);
        }
        return map;
    }

    /// <summary>
    /// The offers, by installed file name: <paramref name="sha512ByFile"/> is each jar's SHA-512 (lower-case hex),
    /// <paramref name="current"/> what each hash is on Modrinth, <paramref name="latest"/> the newest release of its
    /// project here. See the class comment for the rules.
    /// </summary>
    internal static Dictionary<string, ModUpdateOffer> Offers(IReadOnlyDictionary<string, string> sha512ByFile,
        IReadOnlyDictionary<string, Release> current, IReadOnlyDictionary<string, Release> latest, string mcVersion)
    {
        var offers = new Dictionary<string, ModUpdateOffer>(StringComparer.OrdinalIgnoreCase);
        foreach (var (file, hash) in sha512ByFile)
        {
            if (!current.TryGetValue(hash, out var have) || !latest.TryGetValue(hash, out var next)) continue;
            if (next.Sha512 == null || string.Equals(next.Sha512, hash, StringComparison.OrdinalIgnoreCase)) continue;
            if (next.ProjectId != have.ProjectId) continue;
            if (next.Type != "release") continue;
            if (!next.Loaders.Contains("fabric") || !next.Games.Contains(mcVersion)) continue;
            if (next.Published <= have.Published) continue;
            if (ModrinthFileGuard.Check(next.Url, next.FileName, next.Sha512) != ModrinthDownloadResult.Installed) continue;
            offers[file] = new ModUpdateOffer(file, next.Number, next.FileName!, next.Url!, next.Sha512);
        }
        return offers;
    }

    /// <summary>The lower-case SHA-512 of a file, read with sharing (a running game may hold it). Null when unreadable.</summary>
    public static string? Sha512Of(string path)
    {
        try
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return Convert.ToHexString(SHA512.HashData(stream)).ToLowerInvariant();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>A real answer is trusted this long; a failed check is asked again after <see cref="FailedRetry"/>.</summary>
    public static readonly TimeSpan FreshAnswer = TimeSpan.FromHours(6);
    public static readonly TimeSpan FailedRetry = TimeSpan.FromMinutes(2);

    /// <summary>
    /// True when a folder's answer from <paramref name="checkedUtc"/> must be asked again. A failed check (offline,
    /// Modrinth down) is not an answer: it is retried soon instead of hiding the Update buttons for 6 hours (NET-5).
    /// </summary>
    public static bool Stale(DateTime checkedUtc, bool failed, DateTime nowUtc) =>
        nowUtc - checkedUtc >= (failed ? FailedRetry : FreshAnswer);

    /// <summary>
    /// What a folder shows after a check that failed: the last real offers, for the jars that are still there (a
    /// failed re-check never makes known Update buttons disappear).
    /// </summary>
    public static Dictionary<string, ModUpdateOffer> KeptAfterFailure(
        IReadOnlyDictionary<string, ModUpdateOffer>? previous, IEnumerable<string> jars)
    {
        var kept = new Dictionary<string, ModUpdateOffer>(StringComparer.OrdinalIgnoreCase);
        if (previous == null) return kept;
        foreach (var jar in jars)
            if (previous.TryGetValue(jar, out var offer)) kept[jar] = offer;
        return kept;
    }

    /// <summary>
    /// Asks Modrinth (two POSTs, hashes only) which jars have a newer release. Null when there is no answer (offline,
    /// timed out, a refused or unreadable reply): the caller keeps what it knew and asks again soon, because "couldn't
    /// ask" is not "nothing newer". Never throws except for the caller's cancel.
    /// </summary>
    public static async Task<Dictionary<string, ModUpdateOffer>?> FindAsync(HttpClient http, string mcVersion,
        IReadOnlyDictionary<string, string> sha512ByFile, CancellationToken ct)
    {
        var none = new Dictionary<string, ModUpdateOffer>(StringComparer.OrdinalIgnoreCase);
        var hashes = sha512ByFile.Values.Distinct(StringComparer.OrdinalIgnoreCase).Take(MaxJars).ToList();
        if (hashes.Count == 0) return none;
        try
        {
            var current = await PostAsync(http, "version_files", new { hashes, algorithm = "sha512" }, ct).ConfigureAwait(false);
            if (current == null) return null; // refused (5xx, 429, ...): no answer
            if (current.Count == 0) return none; // none of these jars is on Modrinth
            var known = hashes.Where(current.ContainsKey).ToList();
            var latest = await PostAsync(http, "version_files/update", UpdateRequest(known, mcVersion), ct).ConfigureAwait(false);
            return latest == null ? null : Offers(sha512ByFile, current, latest, mcVersion);
        }
        catch (Exception ex) when (ex is HttpRequestException or JsonException or IOException
                                       || (ex is OperationCanceledException && !ct.IsCancellationRequested))
        {
            return null; // offline, timed out, or an answer this can't read: no answer
        }
    }

    /// <summary>
    /// The "newest version of each" question. Modrinth answers with ONE version per hash, the newest of the types
    /// asked for: without <c>version_types</c> a beta published after the last release hid that release, and the
    /// release-only check in <see cref="Offers"/> could then only drop it (no Update although a newer release
    /// existed; bug run 11 PROF-7). Offers keeps its own release check as a guard.
    /// </summary>
    internal static object UpdateRequest(IReadOnlyList<string> hashes, string mcVersion) => new
    {
        hashes,
        algorithm = "sha512",
        loaders = new[] { "fabric" },
        game_versions = new[] { mcVersion },
        version_types = new[] { "release" },
    };

    private static async Task<Dictionary<string, Release>?> PostAsync(HttpClient http, string path, object body, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, ApiBase + path)
        {
            Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json"),
        };
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) return null;
        return ParseByHash(await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false));
    }

    /// <summary>
    /// Downloads the offer into a new private folder under <paramref name="downloadsDir"/> (never the mods folder) and
    /// checks it is the same mod as <paramref name="installedJar"/> (its own id, read from the jar). Returns the new
    /// jar's path, or null and a sentence for the player. The caller swaps it in (the Mods page's Replace) and then
    /// deletes the folder (<see cref="Discard"/>).
    /// </summary>
    public static async Task<(string? Path, string? Error)> DownloadAsync(HttpClient http, ModUpdateOffer offer,
        string installedJar, string downloadsDir, CancellationToken ct)
    {
        string? folder = null;
        try
        {
            CleanStale(downloadsDir);
            folder = Path.Combine(downloadsDir, Guid.NewGuid().ToString("N")[..12]);
            Directory.CreateDirectory(folder);
            var result = await ModrinthFileGuard.TryDownloadAsync(http, offer.Url, offer.NewFileName, offer.Sha512, folder, ct)
                .ConfigureAwait(false);
            if (result != ModrinthDownloadResult.Installed)
            {
                Discard(folder);
                return (null, result == ModrinthDownloadResult.HashMismatch
                    ? "The download didn't match Modrinth's checksum, so nothing was changed."
                    : "Couldn't download the update from Modrinth. Check your internet and try again.");
            }
            var path = Path.Combine(folder, offer.NewFileName);
            var oldId = ModFolderScanner.ReadInfo(installedJar, readNested: false)?.Id;
            var newId = ModFolderScanner.ReadInfo(path, readNested: false)?.Id;
            if (oldId == null || !string.Equals(oldId, newId, StringComparison.Ordinal))
            {
                Discard(folder);
                return (null, "Modrinth's file isn't the same mod, so nothing was changed.");
            }
            return (path, null);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            if (folder != null) Discard(folder);
            return (null, "Couldn't save the update - " + ex.Message);
        }
    }

    /// <summary>Deletes a download folder (or the folder holding a downloaded jar). Best-effort, never throws.</summary>
    public static void Discard(string? pathOrFolder)
    {
        if (pathOrFolder == null) return;
        try
        {
            var folder = Directory.Exists(pathOrFolder) ? pathOrFolder : Path.GetDirectoryName(pathOrFolder);
            if (folder != null && Directory.Exists(folder)) Directory.Delete(folder, recursive: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
    }

    // Folders an earlier run left (the launcher closed between download and swap): older than an hour, never in use.
    private static void CleanStale(string downloadsDir)
    {
        try
        {
            if (!Directory.Exists(downloadsDir)) return;
            foreach (var dir in Directory.EnumerateDirectories(downloadsDir))
                if (Directory.GetCreationTimeUtc(dir) < DateTime.UtcNow.AddHours(-1)) Discard(dir);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
        }
    }

    private static string? Str(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static List<string> Strings(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Array
            ? v.EnumerateArray().Where(e => e.ValueKind == JsonValueKind.String).Select(e => e.GetString()!).ToList()
            : new List<string>();
}
