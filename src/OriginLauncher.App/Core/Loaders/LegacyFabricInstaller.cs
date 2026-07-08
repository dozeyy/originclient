using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;
using CmlLib.Core;

namespace OriginLauncher.App.Core.Loaders;

// Fabric for Minecraft versions older than 1.14, where official Fabric does
// not exist: Legacy Fabric (legacyfabric.net) is the community port of the
// same loader + mixin system, covering 1.3.2–1.13.2 — of which Origin
// supports exactly 1.8.9 and 1.12.2 (the only legacy versions the picker
// offers; see VersionManager.PinnedVersions). Its meta server is a drop-in
// mirror of Fabric's own v2 API, so installing is protocol-level and needs
// no loader-specific CmlLib support:
//
//   GET /v2/versions/loader/{game}                    -> available loaders
//   GET /v2/versions/loader/{game}/{loader}/profile/json
//       -> a standard launcher version manifest (inheritsFrom + libraries
//          whose `url` entries point at maven.legacyfabric.net), which we
//          write into the instance's versions/ folder; CmlLib then installs
//          and launches it like any other local version, exactly as it does
//          for the modern-Fabric profiles its own FabricInstaller writes.
//
// Player-visible, this is just "Fabric" — the launcher owning the loader
// choice is the whole product model (see src/OriginClient.Mod/VERSIONS.md).
public static class LegacyFabricInstaller
{
    private const string MetaBase = "https://meta.legacyfabric.net/v2";

    // Written into the instance root after a successful install so relaunches
    // resolve the profile id without a network round-trip (and keep working
    // offline), mirroring the skip-if-present pattern used by the mod
    // installers. Deleting it just forces a clean re-resolve.
    private const string MarkerFileName = "legacyfabric.json";

    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var http = new HttpClient();
        http.DefaultRequestHeaders.UserAgent.ParseAdd("OriginLauncher/1.0 (+will@willhenry.me)");
        return http;
    }

    // True for release versions below 1.14: the range official Fabric
    // doesn't reach and Legacy Fabric does. In practice only 1.8.9 and
    // 1.12.2 ever get here (the picker's supported set), so a simple
    // minor-version check is enough. Unparseable strings (snapshots etc. —
    // the picker never shows them) are conservatively not-legacy.
    public static bool Supports(string mcVersion)
    {
        var parts = mcVersion.Split('.');
        if (parts.Length < 2 || parts[0] != "1")
            return false;
        return int.TryParse(parts[1], out var minor) && minor <= 13;
    }

    /// <summary>
    /// Ensures the Legacy Fabric loader profile for <paramref name="mcVersion"/>
    /// is present in the instance and returns its version id for CmlLib to
    /// install/launch. Throws on failure — unlike the optional mod installs,
    /// a loader the player asked for either installs or the launch must fail
    /// visibly, matching the modern FabricInstaller path's behavior.
    /// </summary>
    public static async Task<string> InstallAsync(
        string mcVersion, MinecraftPath path, IProgress<string>? progress = null, CancellationToken ct = default)
    {
        var markerPath = Path.Combine(path.BasePath, MarkerFileName);

        // Already provisioned for this instance? (Instances are per-version,
        // so the marker can't describe a different Minecraft version.)
        if (File.Exists(markerPath))
        {
            var marker = JsonSerializer.Deserialize<Marker>(await File.ReadAllTextAsync(markerPath, ct));
            if (marker?.Id != null && File.Exists(VersionJsonPath(path, marker.Id)))
                return marker.Id;
        }

        progress?.Report("Installing Fabric loader...");

        // Newest-first list, same convention as Fabric's own meta; prefer the
        // first stable entry, falling back to the first entry at all.
        var loaders = await Http.GetFromJsonAsync<List<LoaderEntry>>(
            $"{MetaBase}/versions/loader/{Uri.EscapeDataString(mcVersion)}", ct);
        var loaderVersion = (loaders?.FirstOrDefault(l => l.Loader?.Stable == true) ?? loaders?.FirstOrDefault())
            ?.Loader?.Version;
        if (string.IsNullOrEmpty(loaderVersion))
            throw new InvalidOperationException(
                $"Legacy Fabric has no loader builds for Minecraft {mcVersion} (meta.legacyfabric.net).");

        var profileJson = await Http.GetStringAsync(
            $"{MetaBase}/versions/loader/{Uri.EscapeDataString(mcVersion)}/{Uri.EscapeDataString(loaderVersion)}/profile/json", ct);

        using var doc = JsonDocument.Parse(profileJson);
        var id = doc.RootElement.GetProperty("id").GetString();
        if (string.IsNullOrEmpty(id))
            throw new InvalidOperationException("Legacy Fabric profile json has no version id.");

        var jsonPath = VersionJsonPath(path, id);
        Directory.CreateDirectory(Path.GetDirectoryName(jsonPath)!);
        var tempPath = jsonPath + ".download";
        await File.WriteAllTextAsync(tempPath, profileJson, ct);
        File.Move(tempPath, jsonPath, overwrite: true);

        await File.WriteAllTextAsync(markerPath,
            JsonSerializer.Serialize(new Marker(id, loaderVersion)), ct);
        return id;
    }

    private static string VersionJsonPath(MinecraftPath path, string id) =>
        Path.Combine(path.BasePath, "versions", id, id + ".json");

    private sealed record Marker(
        [property: JsonPropertyName("id")] string? Id,
        [property: JsonPropertyName("loader")] string? Loader);

    private sealed record LoaderEntry(
        [property: JsonPropertyName("loader")] LoaderInfo? Loader);

    private sealed record LoaderInfo(
        [property: JsonPropertyName("version")] string? Version,
        [property: JsonPropertyName("stable")] bool Stable);
}
