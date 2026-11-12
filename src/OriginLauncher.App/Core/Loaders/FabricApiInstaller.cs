using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Net;
using System.Net.Http.Json;
using System.Text.Json;
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
// 2026-10-25 (Phase 5.2a): every live Fabric version now has an exact pin with
// Modrinth's SHA-512 in ManagedPins.Data.cs, used first. The live query stays
// only as the fallback for a version with no pin, and it too installs a file
// only when it hashes to the SHA-512 Modrinth published (ModrinthFileGuard).
public static class FabricApiInstaller
{
    private const string ProjectSlug = "fabric-api";
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        // Stall-guarded (Core/Net/DownloadHttp): a download that goes quiet fails instead of freezing the install.
        var http = DownloadHttp.Create();
        // Modrinth's API docs ask every client to send a descriptive
        // User-Agent — untagged traffic is more likely to get rate-limited.
        http.DefaultRequestHeaders.UserAgent.ParseAdd("OriginLauncher/1.0 (+will@willhenry.me)");
        return http;
    }

    public static Task InstallAsync(
        string mcVersion, string modsFolder, IProgress<string>? progress = null, CancellationToken ct = default) =>
        InstallProjectAsync(Http, ProjectSlug, "fabric-api-", "[\"fabric\"]",
            "Installing Origin's mods...", ManagedPins.FabricApi(mcVersion), mcVersion, modsFolder, progress, ct);

    /// <summary>
    /// A working Fabric API is in the folder: an enabled jar named fabric-api-*, Origin's or the player's own (Modrinth's
    /// file name; a player who renamed theirs is the rare case this misses, and Play then says so with Retry). Every Origin
    /// jar declares "fabric-api" as a hard dependency, so without one Fabric Loader refuses to start the game.
    /// </summary>
    public static bool Present(string modsFolder)
    {
        try
        {
            return Directory.Exists(modsFolder) && Directory.EnumerateFiles(modsFolder).Any(f =>
            {
                var name = Path.GetFileName(f);
                return name.StartsWith("fabric-api-", StringComparison.OrdinalIgnoreCase)
                    && name.EndsWith(Mods.ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase);
            });
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return true; // can't tell: let the game decide rather than refuse a launch that may well work
        }
    }

    /// <summary>
    /// The shared Modrinth install path (Fabric API, Simple Voice Chat): skip when the family is present in either
    /// state, else the exact pin when there is one, else the newest release with a published SHA-512. Never throws
    /// for a network, hash or disk problem (the instance is left without the jar); a cancellation is rethrown.
    /// </summary>
    internal static async Task InstallProjectAsync(
        HttpClient http, string slug, string presentPrefix, string? loadersFilter, string progressText, PerfMod? pin,
        string mcVersion, string modsFolder, IProgress<string>? progress, CancellationToken ct)
    {
        Directory.CreateDirectory(modsFolder);

        // Skip-if-present, same idea as PerfModInstaller: no repeat network
        // call on every subsequent launch of an already-provisioned instance.
        // Enumerate all files and match the project's filename prefix in either
        // state (".jar", or a ".jar.disabled" Voice Chat the player turned off) —
        // never a Win32 glob, and never re-downloading a copy the player turned off.
        // Only ORIGIN's copy counts (ModManager.IsOriginsCopy: by its bytes, a pin or this folder's install record):
        // a player's own Fabric API of that name is theirs, and Origin's mods (and every profile or Origin-only
        // launch, which loads Origin's jars only) still need Origin's beside it.
        // Origin's own pinned Fabric API turned off (an old Mods page offered a switch on it): back on by a rename.
        // HasOriginsCopy counts a turned-off copy only for Voice Chat (the player may switch that one off).
        if (pin != null) Mods.ModManager.TryRestoreOriginsJar(modsFolder, pin.FileName, pin.Matches);
        // A pin moves (or an install from before pins kept the live query's "newest" jar): a built-in family
        // (Fabric API) is skipped only when the exact pinned file is there, so the pin reaches existing installs;
        // VersionManager names the pin to DedupeOriginFamilies, which then retires Origin's older copy. A family the
        // player may switch off (Voice Chat) is never deduplicated, so ANY Origin copy keeps it as it is (two would
        // load the same mod id twice, and a new download would turn a switched-off one back on).
        if (pin != null && File.Exists(Path.Combine(modsFolder, pin.FileName)))
            return;
        var movesToPin = pin != null && Mods.ModManager.IsManaged(pin.FileName);
        if (!movesToPin && Mods.ModManager.HasOriginsCopy(modsFolder, presentPrefix))
            return;

        progress?.Report(progressText);

        if (pin != null)
        {
            await ModrinthFileGuard.TryDownloadAsync(http, pin.Url, pin.FileName, pin.Sha512, modsFolder, ct);
            return;
        }

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
            versions = await http.GetFromJsonAsync<List<ModrinthVersion>>(url, ct);
        }
        catch (Exception ex) when (ex is HttpRequestException or JsonException
                                   || (ex is OperationCanceledException && !ct.IsCancellationRequested))
        {
            return; // offline, Modrinth unreachable or slow, or an unreadable reply — leave the instance without it rather than fail the whole install
        }

        // Release builds only (Will, 2026-10-25): Modrinth also lists Fabric API alphas and
        // betas for the same Minecraft version, and the newest-by-date one is often not stable.
        var latest = ModrinthReleasePicker.PickNewestRelease(versions, v => v.VersionType, v => v.DatePublished);
        var file = latest?.Files.FirstOrDefault(f => f.Primary) ?? latest?.Files.FirstOrDefault();
        if (file == null)
            return; // no Fabric API release published for this exact MC version

        // Fail soft (ModrinthFileGuard): a URL off cdn.modrinth.com, an unsafe file name, a missing or
        // wrong SHA-512 or a failed download leaves the instance without Fabric API instead of throwing,
        // and never leaves a ".download" file behind. Same policy as the self-update zip.
        await ModrinthFileGuard.TryDownloadAsync(
            http, file.Url, file.Filename, ModrinthFileGuard.ParseSha512(file.Hashes), modsFolder, ct);
    }

    private sealed record ModrinthVersion(
        [property: JsonPropertyName("date_published")] DateTimeOffset DatePublished,
        [property: JsonPropertyName("version_type")] string? VersionType,
        [property: JsonPropertyName("files")] List<ModrinthFile> Files);

    private sealed record ModrinthFile(
        [property: JsonPropertyName("url")] string Url,
        [property: JsonPropertyName("filename")] string Filename,
        [property: JsonPropertyName("primary")] bool Primary,
        [property: JsonPropertyName("hashes")] Dictionary<string, string>? Hashes = null);
}

/// <summary>
/// A mod the game can't start without (Fabric API, which every Origin jar depends on) couldn't be downloaded, so Play
/// stops here with Retry instead of starting a game that Fabric Loader would refuse at once. Message is the plain
/// cause sentence the launch screen shows (PlayFailure). It names the host (a Modrinth outage is not Mojang's) but never
/// the mod: "Fabric API" would tell the player which loader the version runs on (mandate 1). ModName is for the log.
/// </summary>
public sealed class RequiredModMissingException : Exception
{
    public string ModName { get; }

    public RequiredModMissingException(string modName)
        : base("Couldn't download a mod Origin needs to start (from Modrinth). Check your internet connection.")
    {
        ModName = modName;
    }
}
