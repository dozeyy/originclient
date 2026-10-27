using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Loaders;

namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// Fix Compatibility's source for a missing dependency: a Modrinth release of that mod for this Minecraft version on
/// Fabric, with a published SHA-512. What leaves the PC is ONLY the missing mod's id (Will's rule, 2026-10-25: mod ids
/// and hashes only): one GET per id, https://api.modrinth.com/v2/project/&lt;id&gt;/version, no query, no body, no
/// file names, no other mods, no account. When that id has a usable release, one more GET of
/// https://api.modrinth.com/v2/project/&lt;id&gt; (same rule: the id and nothing else) reads the project's title, so the
/// prompt can say "Cloth Config" instead of "cloth-config". Every filter (loader, Minecraft version, release, version range) runs here.
/// The answers are fetched once, before planning (<see cref="ResolveAsync"/>), so <see cref="Find"/> stays pure.
/// A Modrinth project whose slug is the id but which is another mod is caught when its file arrives: the executor
/// reads the jar and refuses one whose own id is not the one needed.
/// </summary>
public sealed class ModrinthDependencyCatalog : IDependencyCatalog
{
    public const string ApiBase = "https://api.modrinth.com/v2/project/";

    /// <summary>At most this many ids are asked about per fix (a set missing more is not one a click can mend).</summary>
    public const int MaxIds = 8;

    // Fabric's own rule for a mod id (fabric.mod.json "id"); anything else is never sent anywhere.
    private static readonly Regex ModId = new("^[a-z][a-z0-9_-]{1,63}$", RegexOptions.CultureInvariant);

    // Provided by the game or the loader, never a download.
    private static readonly HashSet<string> NotMods = new(StringComparer.Ordinal)
    {
        "minecraft", "java", "fabricloader", "fabric", "forge", "quilt_loader",
    };

    internal sealed record Candidate(string Version, string Url, string FileName, string Sha512, IReadOnlyList<string> GameVersions);

    private readonly Dictionary<string, List<Candidate>> _byId = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> _titles = new(StringComparer.Ordinal);

    /// <summary>True when <paramref name="id"/> may be asked about at all.</summary>
    public static bool IsAskable(string? id) => id != null && ModId.IsMatch(id) && !NotMods.Contains(id);

    /// <summary>The ids a report is missing that Modrinth could supply (the first <see cref="MaxIds"/>, in order).</summary>
    /// <param name="answered">A finding another source already answers (Origin's own pins): its id is not asked about.</param>
    public static IReadOnlyList<string> MissingIds(IEnumerable<CompatReport> reports, Func<CompatFinding, bool>? answered = null) =>
        reports.SelectMany(r => r.Findings)
            .Where(f => f.Kind == FindingKind.MissingDependency && f.Severity == Severity.Blocking && !f.TargetIsOrigin
                        && IsAskable(f.TargetId) && answered?.Invoke(f) != true)
            .Select(f => f.TargetId!)
            .Distinct(StringComparer.Ordinal)
            .Take(MaxIds)
            .ToList();

    /// <summary>
    /// Asks Modrinth about each id (GET, the id in the path and nothing else). A failed or odd answer means no offer
    /// for that id; it never throws except for the caller's cancel.
    /// </summary>
    public static async Task<ModrinthDependencyCatalog> ResolveAsync(
        HttpClient http, IEnumerable<string> ids, string mcVersion, CancellationToken ct)
    {
        var catalog = new ModrinthDependencyCatalog();
        foreach (var id in ids.Where(IsAskable).Distinct(StringComparer.Ordinal).Take(MaxIds))
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, ApiBase + id + "/version");
                using var response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false);
                if (!response.IsSuccessStatusCode) continue;
                var json = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                var candidates = Parse(json, mcVersion);
                catalog._byId[id] = candidates;
                if (candidates.Count == 0) continue;
                // The project's title, for the prompt only (the id stays what is checked and installed).
                using var titleRequest = new HttpRequestMessage(HttpMethod.Get, ApiBase + id);
                using var titleResponse = await http.SendAsync(titleRequest, HttpCompletionOption.ResponseContentRead, ct).ConfigureAwait(false);
                if (!titleResponse.IsSuccessStatusCode) continue;
                if (ParseTitle(await titleResponse.Content.ReadAsStringAsync(ct).ConfigureAwait(false)) is { } title)
                    catalog._titles[id] = title;
            }
            catch (Exception ex) when (ex is HttpRequestException or JsonException or IOException
                                           || (ex is OperationCanceledException && !ct.IsCancellationRequested))
            {
                // offline, timed out, or an answer this can't read: nothing to offer for that id
            }
        }
        return catalog;
    }

    /// <summary>The releases in a Modrinth version list that would load here, newest first (Modrinth's order).</summary>
    internal static List<Candidate> Parse(string json, string mcVersion)
    {
        var list = new List<Candidate>();
        using var doc = JsonDocument.Parse(json);
        if (doc.RootElement.ValueKind != JsonValueKind.Array) return list;
        foreach (var v in doc.RootElement.EnumerateArray())
        {
            if (v.ValueKind != JsonValueKind.Object) continue;
            if (Str(v, "version_type") != "release") continue;
            if (!Strings(v, "loaders").Contains("fabric")) continue;
            var games = Strings(v, "game_versions");
            if (!games.Contains(mcVersion)) continue;
            if (Str(v, "version_number") is not { Length: > 0 and <= 64 } number) continue;
            if (!v.TryGetProperty("files", out var files) || files.ValueKind != JsonValueKind.Array) continue;
            var file = files.EnumerateArray().FirstOrDefault(f => f.ValueKind == JsonValueKind.Object
                && f.TryGetProperty("primary", out var p) && p.ValueKind == JsonValueKind.True);
            if (file.ValueKind != JsonValueKind.Object) file = files.EnumerateArray().FirstOrDefault();
            if (file.ValueKind != JsonValueKind.Object) continue;
            var url = Str(file, "url");
            var name = Str(file, "filename");
            string? sha512 = null;
            if (file.TryGetProperty("hashes", out var hashes) && hashes.ValueKind == JsonValueKind.Object)
            {
                var map = new Dictionary<string, string>(StringComparer.Ordinal);
                foreach (var h in hashes.EnumerateObject())
                    if (h.Value.ValueKind == JsonValueKind.String) map[h.Name] = h.Value.GetString()!;
                sha512 = ModrinthFileGuard.ParseSha512(map);
            }
            if (ModrinthFileGuard.Check(url, name, sha512) != ModrinthDownloadResult.Installed) continue;
            list.Add(new Candidate(number, url!, name!, sha512!, games));
        }
        return list;
    }

    /// <summary>A project's title from Modrinth's project answer: plain text up to 48 characters, else null.</summary>
    internal static string? ParseTitle(string json)
    {
        using var doc = JsonDocument.Parse(json);
        if (doc.RootElement.ValueKind != JsonValueKind.Object) return null;
        var title = Str(doc.RootElement, "title")?.Trim();
        return title is { Length: > 0 and <= 48 } && !title.Any(char.IsControl) ? title : null;
    }

    public DependencyOffer? Find(string id, VersionPredicate range, CompatPlatform platform)
    {
        if (platform.Loader != LoaderFamily.Fabric || !_byId.TryGetValue(id, out var candidates)) return null;
        foreach (var c in candidates)
        {
            if (!c.GameVersions.Contains(platform.McVersion)) continue;
            if (!range.MatchesAnything && !range.IsSatisfiedBy(c.Version)) continue;
            // What the file will say about itself, as far as Modrinth tells: the id and version. Its own needs are not
            // known until it is here; the check at the next Play reads the real file.
            var info = new ModInfo
            {
                FileName = c.FileName, Loader = ModLoader.Fabric, Id = id, Version = c.Version,
                Name = _titles.TryGetValue(id, out var title) ? title : id,
            };
            return new DependencyOffer(id, c.Version, c.FileName, info, c.Url, Sha512: c.Sha512);
        }
        return null;
    }

    private static string? Str(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static List<string> Strings(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Array
            ? v.EnumerateArray().Where(e => e.ValueKind == JsonValueKind.String).Select(e => e.GetString()!).ToList()
            : new List<string>();
}

/// <summary>
/// Brings a Modrinth offer to the executor's ".part" path: downloaded by <see cref="ModrinthFileGuard"/> (https on
/// cdn.modrinth.com only, safe name, SHA-512 checked while it streams) into a private folder beside it, then moved.
/// Runs on the fix's background thread, so waiting on the download here blocks nothing the player sees.
/// </summary>
public sealed class ModrinthDependencyFetcher : IDependencyFetcher
{
    private readonly HttpClient _http;
    private readonly CancellationToken _ct;

    public ModrinthDependencyFetcher(HttpClient http, CancellationToken ct)
    {
        _http = http;
        _ct = ct;
    }

    public bool Fetch(DependencyOffer offer, string destinationPath, out string? error)
    {
        error = null;
        if (offer.Sha512 == null)
        {
            error = "Modrinth gave no checksum for that file, so it was not downloaded.";
            return false;
        }
        var folder = Path.Combine(Path.GetDirectoryName(destinationPath)!, ".fetch-" + Guid.NewGuid().ToString("N")[..8]);
        try
        {
            Directory.CreateDirectory(folder);
            var result = ModrinthFileGuard.TryDownloadAsync(_http, offer.Source, offer.FileName, offer.Sha512, folder, _ct)
                .GetAwaiter().GetResult();
            if (result != ModrinthDownloadResult.Installed)
            {
                error = result == ModrinthDownloadResult.HashMismatch
                    ? "The download didn't match Modrinth's checksum, so it was not added."
                    : "Couldn't download that mod from Modrinth.";
                return false;
            }
            File.Move(Path.Combine(folder, offer.FileName), destinationPath);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            error = "Couldn't download that mod - " + ex.Message;
            return false;
        }
        finally
        {
            try { if (Directory.Exists(folder)) Directory.Delete(folder, recursive: true); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* inside the staging copy: removed with it */ }
        }
    }
}
