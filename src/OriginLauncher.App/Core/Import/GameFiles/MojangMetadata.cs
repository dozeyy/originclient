using System.IO;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Import.GameFiles;

public enum RequiredKind { ClientJar, Library, Native, AssetIndex, Asset, Runtime }

/// <summary>
/// One file a version needs, from Mojang's own metadata. <see cref="RelPath"/> is where it lives in
/// an Origin version root (instances/&lt;version&gt;/): versions/&lt;v&gt;/&lt;v&gt;.jar, libraries/&lt;path&gt;,
/// assets/indexes|objects/..., runtime/windows-x64/&lt;component&gt;/&lt;path&gt;.
/// </summary>
public sealed record RequiredFile(
    RequiredKind Kind, string RelPath, string Sha1, long Size,
    string? Id = null, string? Component = null, string? SubPath = null);

/// <summary>The metadata-derived list of what a version needs, or why it could not be built.</summary>
public sealed class RequiredSet
{
    public List<RequiredFile> Files { get; } = new();

    /// <summary>Set when the version's metadata could not be fetched/verified: nothing at all is reused.</summary>
    public bool Unavailable { get; set; }

    /// <summary>The Java runtime's manifest was not available: only the Java part is skipped.</summary>
    public bool JavaSkipped { get; set; }
    public string? RuntimeComponent { get; set; }
}

// Builds the exact list of files a Minecraft version needs from Mojang's metadata (D25):
//   version_manifest_v2 -> the version json (checked against the manifest's SHA-1)
//     -> client jar, the Windows libraries (rules applied) and natives, the asset index
//        (checked against the version json's SHA-1) and every object in it
//     -> the Java component the version names -> java-runtime/all.json -> that component's
//        manifest (checked against all.json's SHA-1) -> every file in it.
// Fabric loader libraries and profile jsons are NOT in Mojang's metadata (no hash to verify
// them by), so they are never in this list and never copied.
public sealed class MojangMetadata
{
    private const string RuntimePlatform = "windows-x64";
    private const string DefaultComponent = "jre-legacy"; // versions older than 1.17 name no Java component

    private readonly MetadataClient _client;

    // Metadata is data from outside and its values become file names: a hash is exactly 40 lower-case hex
    // digits, a runtime component a plain lower-case word, and a relative path never climbs ('..'), names a
    // drive (':') or starts at a root. Anything else is dropped (never guessed at).
    private static readonly Regex Sha1Pattern = new("^[0-9a-f]{40}$", RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex ComponentPattern = new("^[a-z0-9-]+$", RegexOptions.CultureInvariant | RegexOptions.Compiled);

    internal static bool IsSha1(string? value) => value != null && Sha1Pattern.IsMatch(value);

    internal static bool IsSafeRelative(string? path) =>
        !string.IsNullOrEmpty(path) && !path.Contains("..", StringComparison.Ordinal) && !path.Contains(':')
        && path[0] != '/' && path[0] != '\\';

    public MojangMetadata(MetadataClient client) => _client = client;

    public async Task<RequiredSet> GetRequiredSetAsync(string mcVersion, CancellationToken ct)
    {
        var set = new RequiredSet();
        try
        {
            if (!await AddVersionFilesAsync(set, mcVersion, ct).ConfigureAwait(false))
            {
                set.Files.Clear();
                set.Unavailable = true;
            }
        }
        catch (Exception ex) when (ex is JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        {
            // metadata in a shape this code doesn't know: treat as unavailable, never guess
            set.Files.Clear();
            set.Unavailable = true;
        }
        return set;
    }

    private async Task<bool> AddVersionFilesAsync(RequiredSet set, string mcVersion, CancellationToken ct)
    {
        var manifestBytes = await _client.GetAsync(MetadataClient.VersionManifestUrl, null, ct).ConfigureAwait(false);
        if (manifestBytes == null) return false;
        string? versionUrl = null, versionSha1 = null;
        using (var manifest = JsonDocument.Parse(manifestBytes))
        {
            foreach (var entry in manifest.RootElement.GetProperty("versions").EnumerateArray())
            {
                if (Str(entry, "id") != mcVersion) continue;
                versionUrl = Str(entry, "url");
                versionSha1 = Str(entry, "sha1");
                break;
            }
        }
        if (versionUrl == null || !IsSha1(versionSha1)) return false;

        var versionBytes = await _client.GetAsync(versionUrl, versionSha1, ct).ConfigureAwait(false);
        if (versionBytes == null) return false;
        using var version = JsonDocument.Parse(versionBytes);
        var root = version.RootElement;

        if (root.TryGetProperty("downloads", out var downloads) && downloads.TryGetProperty("client", out var client)
            && IsSha1(Str(client, "sha1")))
            set.Files.Add(new RequiredFile(RequiredKind.ClientJar, $"versions/{mcVersion}/{mcVersion}.jar",
                Str(client, "sha1")!, Long(client, "size"), mcVersion));

        if (root.TryGetProperty("libraries", out var libraries))
            foreach (var library in libraries.EnumerateArray()) AddLibrary(set, library);

        if (!root.TryGetProperty("assetIndex", out var assetIndex)) return false;
        var indexId = Str(assetIndex, "id");
        var indexSha1 = Str(assetIndex, "sha1");
        if (!IsSafeRelative(indexId) || indexId!.Contains('/') || indexId.Contains('\\') || !IsSha1(indexSha1)) return false;
        set.Files.Add(new RequiredFile(RequiredKind.AssetIndex, $"assets/indexes/{indexId}.json", indexSha1!, Long(assetIndex, "size"), indexId));
        var indexBytes = await _client.GetAsync(Str(assetIndex, "url") ?? "", indexSha1, ct).ConfigureAwait(false);
        if (indexBytes == null) return false;
        AddAssets(set, indexBytes);

        var component = DefaultComponent;
        if (root.TryGetProperty("javaVersion", out var java) && Str(java, "component") is { Length: > 0 } named) component = named;
        await AddRuntimeAsync(set, component, ct).ConfigureAwait(false);
        return true;
    }

    // Windows libraries only: rules decide (default: allowed; the last rule that applies wins), x86/arm64-only entries don't apply to x64.
    private static void AddLibrary(RequiredSet set, JsonElement library)
    {
        if (!Allowed(library)) return;
        if (!library.TryGetProperty("downloads", out var downloads)) return;
        if (downloads.TryGetProperty("artifact", out var artifact) && Str(artifact, "path") is { } path && Str(artifact, "sha1") is { } sha1
            && IsSafeRelative(path) && IsSha1(sha1))
            Add(set, new RequiredFile(RequiredKind.Library, "libraries/" + path, sha1, Long(artifact, "size"), path));

        // Older versions keep natives as classifiers: natives.windows names the key ("natives-windows", "natives-windows-${arch}").
        if (library.TryGetProperty("natives", out var natives) && Str(natives, "windows") is { } key
            && downloads.TryGetProperty("classifiers", out var classifiers)
            && classifiers.TryGetProperty(key.Replace("${arch}", "64"), out var native)
            && Str(native, "path") is { } nativePath && Str(native, "sha1") is { } nativeSha1
            && IsSafeRelative(nativePath) && IsSha1(nativeSha1))
            Add(set, new RequiredFile(RequiredKind.Native, "libraries/" + nativePath, nativeSha1, Long(native, "size"), nativePath));
    }

    private static void Add(RequiredSet set, RequiredFile file)
    {
        if (!set.Files.Any(f => f.RelPath == file.RelPath)) set.Files.Add(file);
    }

    private static bool Allowed(JsonElement library)
    {
        if (!library.TryGetProperty("rules", out var rules) || rules.ValueKind != JsonValueKind.Array) return true;
        var allowed = false;
        foreach (var rule in rules.EnumerateArray())
        {
            var applies = true;
            if (rule.TryGetProperty("os", out var os))
            {
                var name = Str(os, "name");
                // Mojang only names x86 / arm64 for the non-default builds; this launcher runs x64.
                applies = (name == null || name == "windows") && !os.TryGetProperty("arch", out _);
            }
            if (applies) allowed = Str(rule, "action") == "allow";
        }
        return allowed;
    }

    private static void AddAssets(RequiredSet set, byte[] indexBytes)
    {
        using var index = JsonDocument.Parse(indexBytes);
        if (!index.RootElement.TryGetProperty("objects", out var objects)) return;
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var asset in objects.EnumerateObject())
        {
            var hash = Str(asset.Value, "hash");
            if (!IsSha1(hash) || !seen.Add(hash!)) continue;
            set.Files.Add(new RequiredFile(RequiredKind.Asset, $"assets/objects/{hash![..2]}/{hash}", hash, Long(asset.Value, "size"), hash));
        }
    }

    private async Task AddRuntimeAsync(RequiredSet set, string component, CancellationToken ct)
    {
        set.RuntimeComponent = component;
        if (!ComponentPattern.IsMatch(component)) { set.JavaSkipped = true; return; } // it names a folder: plain words only
        var listBytes = await _client.GetAsync(MetadataClient.JavaRuntimeListUrl, null, ct).ConfigureAwait(false);
        string? url = null, sha1 = null;
        if (listBytes != null)
        {
            try
            {
                using var list = JsonDocument.Parse(listBytes);
                if (list.RootElement.TryGetProperty(RuntimePlatform, out var platform)
                    && platform.TryGetProperty(component, out var entries)
                    && entries.ValueKind == JsonValueKind.Array && entries.GetArrayLength() > 0
                    && entries[0].TryGetProperty("manifest", out var manifest))
                {
                    url = Str(manifest, "url");
                    sha1 = Str(manifest, "sha1");
                }
            }
            catch (JsonException) { /* handled as "no manifest" below */ }
        }
        var manifestBytes = url == null ? null : await _client.GetAsync(url, sha1, ct).ConfigureAwait(false);
        if (manifestBytes == null)
        {
            set.JavaSkipped = true; // Java will download on first Play
            return;
        }

        using var doc = JsonDocument.Parse(manifestBytes);
        if (!doc.RootElement.TryGetProperty("files", out var files)) { set.JavaSkipped = true; return; }
        foreach (var file in files.EnumerateObject())
        {
            if (Str(file.Value, "type") != "file") continue; // directories appear with their files; links can't be verified
            if (!file.Value.TryGetProperty("downloads", out var d) || !d.TryGetProperty("raw", out var raw)) continue;
            if (Str(raw, "sha1") is not { } fileSha1 || !IsSha1(fileSha1)) continue;
            if (!IsSafeRelative(file.Name) || Path.IsPathRooted(file.Name)) continue; // a path that climbs out is never ours to write
            set.Files.Add(new RequiredFile(RequiredKind.Runtime, $"runtime/{RuntimePlatform}/{component}/{file.Name}",
                fileSha1, Long(raw, "size"), null, component, file.Name));
        }
    }

    private static string? Str(JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    private static long Long(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var n) ? n : 0;
}
