using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json.Serialization;

namespace OriginLauncher.App.Core.Loaders;

// Fabric API is a hard runtime dependency for the Fabric ecosystem — Origin
// Client's own fabric.mod.json declares "fabric-api": "*", and so does
// virtually every third-party Fabric mod a player might drop in. Unlike
// PerformanceModCatalog (a small, deliberately hand-pinned list — exact
// build pairing matters there, see its own comments), Fabric API needs to
// "just work" for whatever exact MC version is being installed, across
// every Fabric version Origin supports. So this queries Modrinth's version
// API live and grabs the newest compatible build instead of hand-maintaining
// a table across every Fabric-supported MC version back to 1.14 — the same
// "ask the real source" approach FabricInstaller already uses for the
// loader itself.
public static class FabricApiInstaller
{
    private const string ProjectSlug = "fabric-api";
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var http = new HttpClient();
        // Modrinth's API docs ask every client to send a descriptive
        // User-Agent — untagged traffic is more likely to get rate-limited.
        http.DefaultRequestHeaders.UserAgent.ParseAdd("OriginLauncher/1.0 (+will@willhenry.me)");
        return http;
    }

    public static Task InstallAsync(
        string mcVersion, string modsFolder, IProgress<string>? progress = null, CancellationToken ct = default) =>
        InstallProjectAsync(ProjectSlug, "fabric-api-", "[\"fabric\"]",
            "Installing Fabric API...", mcVersion, modsFolder, progress, ct);

    private static async Task InstallProjectAsync(
        string slug, string presentPrefix, string? loadersFilter, string progressText,
        string mcVersion, string modsFolder, IProgress<string>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(modsFolder);

        // Skip-if-present, same idea as PerfModInstaller: no repeat network
        // call on every subsequent launch of an already-provisioned instance.
        // Enumerate all files and match the project's filename prefix in either
        // state (".jar" or a player-disabled ".jar.disabled") — never a Win32
        // glob, and never re-downloading a copy the player deliberately turned off.
        if (Directory.EnumerateFiles(modsFolder).Any(f =>
        {
            var n = Path.GetFileName(f);
            return n.StartsWith(presentPrefix, StringComparison.OrdinalIgnoreCase)
                && (n.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)
                    || n.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase));
        }))
            return;

        progress?.Report(progressText);

        // Modrinth's API rejects these query params with a 400 unless the
        // brackets/quotes are percent-encoded — confirmed against the real
        // API, a literal `?loaders=["fabric"]` is not accepted as-is.
        var gameVersions = Uri.EscapeDataString($"[\"{mcVersion}\"]");
        var url = $"https://api.modrinth.com/v2/project/{slug}/version" +
                  $"?game_versions={gameVersions}";
        if (loadersFilter != null)
            url += $"&loaders={Uri.EscapeDataString(loadersFilter)}";

        List<ModrinthVersion>? versions;
        try
        {
            versions = await Http.GetFromJsonAsync<List<ModrinthVersion>>(url, ct);
        }
        catch (HttpRequestException)
        {
            return; // offline or Modrinth unreachable — leave the instance without it rather than fail the whole install
        }

        // Release builds only (Will, 2026-10-25): Modrinth also lists Fabric API alphas and
        // betas for the same Minecraft version, and the newest-by-date one is often not stable.
        var latest = ModrinthReleasePicker.PickNewestRelease(versions, v => v.VersionType, v => v.DatePublished);
        var file = latest?.Files.FirstOrDefault(f => f.Primary) ?? latest?.Files.FirstOrDefault();
        if (file == null)
            return; // no Fabric API release published for this exact MC version

        var destPath = Path.Combine(modsFolder, file.Filename);
        using var response = await Http.GetAsync(file.Url, HttpCompletionOption.ResponseHeadersRead, ct);
        response.EnsureSuccessStatusCode();

        var tempPath = destPath + ".download";
        await using (var fileStream = File.Create(tempPath))
            await response.Content.CopyToAsync(fileStream, ct);
        File.Move(tempPath, destPath, overwrite: true);
    }

    private sealed record ModrinthVersion(
        [property: JsonPropertyName("date_published")] DateTimeOffset DatePublished,
        [property: JsonPropertyName("version_type")] string? VersionType,
        [property: JsonPropertyName("files")] List<ModrinthFile> Files);

    private sealed record ModrinthFile(
        [property: JsonPropertyName("url")] string Url,
        [property: JsonPropertyName("filename")] string Filename,
        [property: JsonPropertyName("primary")] bool Primary);
}
