using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Tests.Import;
using Xunit;

namespace OriginLauncher.Tests;

// Phase 5.2a: every jar Origin installs on its own carries a pin (Fabric API, Simple Voice Chat, the legacy 1.8.9
// Forge files), a download that differs is refused, and "is this jar Origin's?" is decided by the file (hash, then
// its own mod id) before its name. Temp folders and stub handlers only; never the network.
public sealed class ManagedPinsTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-pins-" + Guid.NewGuid().ToString("N"));

    public ManagedPinsTests()
    {
        Directory.CreateDirectory(_root);
        ModManager.ClearOwnershipCache();
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    private static bool IsHex(string? s, int length) => s is { } h && h.Length == length && h.All(Uri.IsHexDigit);

    // Every live Fabric version (all but 1.8.9 Forge) gets Fabric API from a pin, never the live query.
    public static IEnumerable<object[]> FabricVersions() =>
        VersionCatalog.SupportedVersions.Where(v => v != "1.8.9").Select(v => new object[] { v });

    [Theory]
    [MemberData(nameof(FabricVersions))]
    public void Every_live_fabric_version_has_a_pinned_fabric_api(string version)
    {
        var pin = ManagedPins.FabricApi(version);
        Assert.NotNull(pin);
        Assert.True(ModrinthFileGuard.IsTrustedUrl(pin!.Url), pin.Url);
        Assert.True(ModrinthFileGuard.IsSafeJarName(pin.FileName), pin.FileName);
        Assert.StartsWith("fabric-api-", pin.FileName);
        Assert.True(IsHex(pin.Sha512, 128));
        Assert.Equal(ModrinthDownloadResult.Installed, ModrinthFileGuard.Check(pin.Url, pin.FileName, pin.Sha512));
    }

    [Fact]
    public void Voice_chat_pins_are_well_formed_and_only_missing_where_no_build_exists()
    {
        foreach (var version in ManagedPins.VoiceChatVersions)
        {
            var pin = ManagedPins.VoiceChat(version)!;
            Assert.True(ModrinthFileGuard.IsTrustedUrl(pin.Url), pin.Url);
            Assert.True(ModrinthFileGuard.IsSafeJarName(pin.FileName), pin.FileName);
            Assert.StartsWith("voicechat-", pin.FileName); // the name the installer's skip-if-present matches
            Assert.True(IsHex(pin.Sha512, 128));
        }
        // Simple Voice Chat publishes no Fabric build for these (2026-10-25); they run without it.
        var none = FabricVersions().Select(r => (string)r[0]).Where(v => ManagedPins.VoiceChat(v) == null).OrderBy(v => v).ToArray();
        Assert.Equal(new[] { "1.21.2", "1.21.6", "26.3" }, none);
    }

    [Fact]
    public void Legacy_189_files_are_pinned_by_the_sha256_ci_measured()
    {
        Assert.Equal("c7a9602cb95109c6cd371d805b7bf35cc79488a16bc5b5d37f266a29093a38c4", LegacyStackInstaller.Sha256For("TexFix-1.8.9-4.0.jar"));
        Assert.Equal("d87561a739436eacb540f61277524fcc2992a40ceb6cbcbb7a8235abdb25ed5a", LegacyStackInstaller.Sha256For("BetterFps-1.2.0.jar"));
        Assert.Equal("596512ad5f12f95d8a3170321543d4455d23b8fe649c68580c5f828fe74f6668", LegacyForgeBuilds.Builds["1.8.9"].UniversalSha256);
        Assert.True(ManagedPins.IsKnownSha256("C7A9602CB95109C6CD371D805B7BF35CC79488A16BC5B5D37F266A29093A38C4"));
    }

    [Fact]
    public void Known_hash_set_covers_catalog_fabric_api_voice_chat_and_alternates()
    {
        Assert.True(ManagedPins.IsKnownSha512(PerformanceModCatalog.TryGet("1.21.11")!.Sodium!.Sha512));
        Assert.True(ManagedPins.IsKnownSha512(ManagedPins.FabricApi("1.21.1")!.Sha512!.ToUpperInvariant()));
        Assert.True(ManagedPins.IsKnownSha512(ManagedPins.VoiceChat("1.20.1")!.Sha512));
        Assert.True(ManagedPins.IsKnownSha512(ManagedPins.Alternate("1.21.1", "iris", "0.6.5+1.8.1")!.Sha512));
        Assert.False(ManagedPins.IsKnownSha512(new string('a', 128)));
        Assert.False(ManagedPins.IsKnownSha512(null));
    }

    // ---- PinnedDownload (the legacy SHA-256 path) ----

    private sealed class Stub(Func<HttpResponseMessage> reply) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) =>
            Task.FromResult(reply());
    }

    private static readonly byte[] Body = [0x50, 0x4B, 0x03, 0x04, 9, 8, 7, 6];
    private static readonly string BodySha256 = Convert.ToHexString(SHA256.HashData(Body));

    private HttpClient Serves(HttpStatusCode code = HttpStatusCode.OK) =>
        new(new Stub(() => new HttpResponseMessage(code) { Content = new ByteArrayContent(Body) }));

    [Fact]
    public async Task Pinned_download_installs_only_the_pinned_bytes()
    {
        var dest = Path.Combine(_root, "TexFix-1.8.9-4.0.jar");
        Assert.Equal(PinnedDownloadResult.Installed,
            await PinnedDownload.TryDownloadAsync(Serves(), "https://edge.forgecdn.net/x.jar", dest, BodySha256.ToLowerInvariant(), default));
        Assert.Equal(Body, File.ReadAllBytes(dest));
        Assert.False(File.Exists(dest + ".download"));
    }

    [Fact]
    public async Task Pinned_download_refuses_a_mismatch_and_leaves_nothing()
    {
        var dest = Path.Combine(_root, "BetterFps-1.2.0.jar");
        Assert.Equal(PinnedDownloadResult.HashMismatch,
            await PinnedDownload.TryDownloadAsync(Serves(), "https://edge.forgecdn.net/x.jar", dest, new string('0', 64), default));
        Assert.Empty(Directory.EnumerateFiles(_root));
    }

    [Fact]
    public async Task Pinned_download_failure_leaves_nothing()
    {
        var dest = Path.Combine(_root, "BetterFps-1.2.0.jar");
        Assert.Equal(PinnedDownloadResult.Failed,
            await PinnedDownload.TryDownloadAsync(Serves(HttpStatusCode.InternalServerError), "https://edge.forgecdn.net/x.jar", dest, BodySha256, default));
        Assert.Empty(Directory.EnumerateFiles(_root));
    }

    [Fact]
    public void Matches_checks_sha256_and_passes_unpinned()
    {
        var file = Path.Combine(_root, "a.jar");
        File.WriteAllBytes(file, Body);
        Assert.True(PinnedDownload.Matches(file, BodySha256));
        Assert.False(PinnedDownload.Matches(file, new string('1', 64)));
        Assert.True(PinnedDownload.Matches(file, null));
    }

    // ---- "is this jar Origin's?" ----

    private string Jar(string folder, string fileName, byte[] bytes)
    {
        var dir = Path.Combine(_root, folder);
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, fileName);
        File.WriteAllBytes(path, bytes);
        return path;
    }

    [Fact]
    public void A_jar_named_like_origins_but_carrying_another_mod_id_is_the_players()
    {
        var addon = Jar("shared", "modmenu-badges-lib-2.0.jar", Kit.FabricJar("modmenu-badges-lib", "2.0.0"));
        Assert.True(ModManager.IsManaged(Path.GetFileName(addon)));   // the old name-only answer
        Assert.False(ModManager.IsManagedFile(addon));                 // the file says otherwise
        Assert.False(ModManager.IsOriginsCopy(addon, allowHash: false));
    }

    [Fact]
    public void The_players_own_copy_of_a_mod_origin_ships_is_theirs_even_with_the_same_id()
    {
        // Will's case: his own Sodium (or a hand-updated Fabric API) in the shared folder carries exactly the id of
        // Origin's copy. Same id is not Origin's: only a pinned or recorded file is (2026-10-25, player's mods win).
        var sodium = Jar("shared", "sodium-fabric-0.6.9+mc1.21.1.jar", Kit.FabricJar("sodium", "0.6.9+mc1.21.1"));
        var api = Jar("shared", "fabric-api-0.90.0+1.21.1.jar", Kit.FabricJar("fabric-api", "0.90.0+1.21.1"));
        Assert.False(ModManager.IsManagedFile(sodium));
        Assert.False(ModManager.IsOriginsOwnJar(sodium));
        Assert.False(ModManager.IsManagedFile(api));
        var svc = Jar("shared3", "voicechat-fabric-1.21.1-2.6.22.jar", Kit.FabricJar("voicechat", "1.21.1-2.6.22"));
        Assert.False(ModManager.IsOriginProvisionedFile(svc));
    }

    [Fact]
    public void A_jar_an_origin_installer_recorded_is_origins_and_stays_origins_after_a_rename()
    {
        var api = Jar("shared", "fabric-api-0.90.0+1.21.1.jar", Kit.FabricJar("fabric-api", "0.90.0+1.21.1"));
        OriginInstallRecord.AddFile(api);
        Assert.True(ModManager.IsManagedFile(api));
        Assert.True(ModManager.IsOriginsOwnJar(api));
        var svc = Jar("shared", "voicechat-fabric-1.21.1-2.6.22.jar", Kit.FabricJar("voicechat", "1.21.1-2.6.22"));
        OriginInstallRecord.AddFile(svc);
        Assert.True(ModManager.IsOriginProvisionedFile(svc));
        Assert.False(ModManager.IsManagedFile(svc)); // voice chat stays a toggleable row
        // The record is per folder: the same bytes in another folder are not "installed by Origin" there.
        var elsewhere = Jar("other", "fabric-api-0.90.0+1.21.1.jar", Kit.FabricJar("fabric-api", "0.90.0+1.21.1"));
        Assert.False(ModManager.IsOriginsOwnJar(elsewhere));
    }

    [Fact]
    public void Every_current_pin_is_in_the_append_only_history()
    {
        // A pin that leaves the catalog must still mark the file Origin installed (an old leftover is Origin's to
        // clean up, not the player's). So a new pin goes into ManagedPins.History.cs the day it is added.
        var history = typeof(ManagedPins).GetField("EverPinnedSha512",
            System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)!.GetValue(null) as string[];
        var set = new HashSet<string>(history!, StringComparer.OrdinalIgnoreCase);
        var current = PerformanceModCatalog.AllPins().Select(p => p.Sha512)
            .Concat(ManagedPins.FabricApiVersions.Select(v => ManagedPins.FabricApi(v)!.Sha512))
            .Concat(ManagedPins.VoiceChatVersions.Select(v => ManagedPins.VoiceChat(v)!.Sha512))
            .Concat(ManagedPins.AlternateRows.Select(r => r.Value.Sha512))
            .Where(s => !string.IsNullOrEmpty(s));
        var missing = current.Where(s => !set.Contains(s!)).ToList();
        Assert.True(missing.Count == 0, "Add to ManagedPins.History.cs: " + string.Join(", ", missing));
    }

    [Fact]
    public void An_unreadable_jar_falls_back_to_its_name_and_is_never_certainly_origins()
    {
        var locked = Jar("shared", "sodium-fabric-0.6.13+mc1.21.1.jar", Kit.FabricJar("sodium", "0.6.13+mc1.21.1"));
        using (new FileStream(locked, FileMode.Open, FileAccess.Read, FileShare.None))
        {
            Assert.True(ModManager.IsManagedFile(locked));     // shown as Origin's by its name for the moment
            Assert.False(ModManager.IsOriginsOwnJar(locked));  // but nothing that deletes ever acts on "maybe"
        }
        // Readable again: the bytes decide (not pinned, not recorded: the player's), and nothing stale was remembered.
        Assert.False(ModManager.IsManagedFile(locked));
    }

    [Fact]
    public void Names_outside_origins_shapes_are_never_opened()
    {
        var mine = Jar("shared", "mymod-1.0.jar", [9, 9, 9]);
        Assert.False(ModManager.IsManagedFile(mine));
        Assert.True(ModManager.IsOriginsCopy(Path.Combine(_root, "missing", "fabric-api-1.jar"))); // no file: name decides
    }

    [Fact]
    public void Profile_launch_set_keeps_a_lookalike_and_drops_a_fake_shared_origin_jar()
    {
        var api = Jar("shared", "fabric-api-0.116.17+1.21.1.jar", Kit.FabricJar("fabric-api", "0.116.17+1.21.1"));
        var fake = Jar("shared", "krypton-fnp-0.2.jar", Kit.FabricJar("krypton-fnp", "0.2"));
        var lookalike = Jar("profile", "modmenu-badges-lib-2.0.jar", Kit.FabricJar("modmenu-badges-lib", "2.0.0"));
        var dupe = Jar("profile", "fabric-api-0.100.0+1.21.1.jar", Kit.FabricJar("fabric-api", "0.100.0+1.21.1"));
        OriginInstallRecord.AddFile(api); // Origin's own Fabric API, as FabricApiInstaller leaves it

        // The player's older Fabric API gives way to Origin's (PlayerOverrides: only a newer one wins).
        var plan = LaunchPreflight.OverridesFor("1.21.1", Path.GetDirectoryName(api)!, Path.GetDirectoryName(lookalike)!,
            ModManager.IsOriginProvisioned, ModManager.IsOriginsCopy);
        var set = LaunchModFolder.LaunchSet(new[] { api, fake }, new[] { lookalike, dupe },
            ModManager.IsOriginProvisioned, ModManager.IsOriginsCopy, plan.Skips);

        Assert.Equal(new[] { Path.GetFileName(api), Path.GetFileName(lookalike) }.OrderBy(n => n),
            set.Keys.OrderBy(n => n));
    }
}
