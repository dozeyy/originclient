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

        // And every current pin's URL (tools/release/pin_history.py --check proves the same for every URL ever pinned).
        var urls = new HashSet<string>((typeof(ManagedPins).GetField("EverPinnedUrls",
            System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static)!.GetValue(null) as string[])!,
            StringComparer.Ordinal);
        var currentUrls = PerformanceModCatalog.AllPins().Select(p => p.Url)
            .Concat(ManagedPins.FabricApiVersions.Select(v => ManagedPins.FabricApi(v)!.Url))
            .Concat(ManagedPins.VoiceChatVersions.Select(v => ManagedPins.VoiceChat(v)!.Url))
            .Concat(ManagedPins.AlternateRows.Select(r => r.Value.Url))
            .Where(u => u.StartsWith("https://cdn.modrinth.com/", StringComparison.Ordinal));
        var missingUrls = currentUrls.Where(u => !urls.Contains(u)).ToList();
        Assert.True(missingUrls.Count == 0, "Run tools/release/pin_history.py: " + string.Join(", ", missingUrls));
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

    // ---- the crash window's "turn this mod off" decides by the file, like the Mods page (bug run 3: LC-5) ----

    [Fact]
    public void Crash_repair_turns_off_a_players_own_sodium_and_voice_chat_but_never_origins_fabric_api()
    {
        var players = Jar("crash", "sodium-fabric-0.6.9+mc1.21.11.jar", Kit.FabricJar("sodium", "0.6.9+mc1.21.11"));
        var voice = Jar("crash", "voicechat-fabric-1.21.1-2.6.0.jar", Kit.FabricJar("voicechat", "1.21.1-2.6.0"));
        var api = Jar("crash", "fabric-api-0.90.0+1.21.1.jar", Kit.FabricJar("fabric-api", "0.90.0+1.21.1"));
        OriginInstallRecord.AddFile(voice);
        OriginInstallRecord.AddFile(api);
        var folder = Path.GetDirectoryName(players)!;

        Assert.True(OriginLauncher.App.Core.Launch.CrashAnalyzer.IsExternalJar(players));
        Assert.True(OriginLauncher.App.Core.Launch.CrashAnalyzer.IsExternalJar(voice));
        Assert.False(OriginLauncher.App.Core.Launch.CrashAnalyzer.IsExternalJar(api));

        Assert.Equal(CrashRepairOutcome.Disabled, CrashRepair.DisableBlamed(folder, Path.GetFileName(players), ModManager.IsManagedFile).Outcome);
        Assert.Equal(CrashRepairOutcome.Disabled, CrashRepair.DisableBlamed(folder, Path.GetFileName(voice), ModManager.IsManagedFile).Outcome);
        Assert.Equal(CrashRepairOutcome.Protected, CrashRepair.DisableBlamed(folder, Path.GetFileName(api), ModManager.IsManagedFile).Outcome);
        Assert.True(File.Exists(api));
    }

    // ---- pin bumps reach existing installs, and never break them (bug run 3: LC-2, LC-6) ----

    private const string Cdn = "https://cdn.modrinth.com/data/test/versions/x/";
    private static readonly string BodySha512 = Convert.ToHexString(SHA512.HashData(Body));

    private sealed class Counting(Func<HttpResponseMessage> reply) : HttpMessageHandler
    {
        public int Requests;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Interlocked.Increment(ref Requests);
            return Task.FromResult(reply());
        }
    }

    private static VersionPerfProfile SodiumOnly(PerfMod sodium) => new("1.21.11", default, sodium, null, null, null, null);

    private static PerfMod NewSodiumPin(string sha512) =>
        new("0.6.13", Cdn + "sodium-fabric-0.6.13+mc1.21.11.jar", "sodium-fabric-0.6.13+mc1.21.11.jar", sha512);

    [Theory]
    [InlineData(HttpStatusCode.ServiceUnavailable)]
    [InlineData(HttpStatusCode.TooManyRequests)]
    [InlineData(HttpStatusCode.OK)] // answers, but with bytes that don't hash to the pin
    public async Task A_perf_pin_bump_that_cant_download_keeps_origins_previous_jar(HttpStatusCode answer)
    {
        var old = Jar("mods", "sodium-fabric-0.6.9+mc1.21.11.jar", Kit.FabricJar("sodium", "0.6.9+mc1.21.11"));
        OriginInstallRecord.AddFile(old);
        var mods = Path.GetDirectoryName(old)!;

        await PerfModInstaller.InstallAsync(Serves(answer), SodiumOnly(NewSodiumPin(new string('a', 128))), mods, null, default);

        Assert.True(File.Exists(old));
        Assert.Equal(new[] { Path.GetFileName(old) }, Directory.EnumerateFiles(mods)
            .Select(f => Path.GetFileName(f)).Where(n => n.EndsWith(".jar")).ToArray());
        Assert.Empty(Directory.EnumerateFiles(mods, "*.download"));
    }

    [Fact]
    public async Task A_perf_jar_with_no_origin_copy_still_fails_the_install()
    {
        // First install, or only the player's own Sodium there (theirs doesn't stand in for Origin's).
        var players = Jar("mods", "sodium-fabric-0.6.9+mc1.21.11.jar", Kit.FabricJar("sodium", "0.6.9+mc1.21.11"));
        await Assert.ThrowsAsync<HttpRequestException>(() => PerfModInstaller.InstallAsync(
            Serves(HttpStatusCode.ServiceUnavailable), SodiumOnly(NewSodiumPin(BodySha512)),
            Path.GetDirectoryName(players)!, null, default));
    }

    [Fact]
    public async Task A_perf_pin_bump_installs_when_modrinth_answers()
    {
        var old = Jar("mods", "sodium-fabric-0.6.9+mc1.21.11.jar", Kit.FabricJar("sodium", "0.6.9+mc1.21.11"));
        OriginInstallRecord.AddFile(old);
        var mods = Path.GetDirectoryName(old)!;
        await PerfModInstaller.InstallAsync(Serves(), SodiumOnly(NewSodiumPin(BodySha512)), mods, null, default);
        Assert.Equal(Body, File.ReadAllBytes(Path.Combine(mods, "sodium-fabric-0.6.13+mc1.21.11.jar")));
    }

    [Fact]
    public async Task A_fabric_api_pin_reaches_an_install_that_has_an_older_origin_copy()
    {
        var old = Jar("mods", "fabric-api-0.90.0+1.21.1.jar", Kit.FabricJar("fabric-api", "0.90.0+1.21.1"));
        OriginInstallRecord.AddFile(old);
        var mods = Path.GetDirectoryName(old)!;
        var pin = new PerfMod("0.116.0+1.21.1", Cdn + "fabric-api-0.116.0+1.21.1.jar", "fabric-api-0.116.0+1.21.1.jar", BodySha512);

        await FabricApiInstaller.InstallProjectAsync(Serves(), "fabric-api", "fabric-api-", "[\"fabric\"]",
            "Installing Fabric API...", pin, "1.21.1", mods, null, default);
        Assert.True(File.Exists(Path.Combine(mods, pin.FileName)));

        // VersionManager names the pin to the dedupe, which retires Origin's older copy (not the pin).
        SharedFolderCleanup.DedupeOriginFamilies(mods, new HashSet<string> { pin.FileName }, ModManager.OwnerOf);
        Assert.False(File.Exists(old));
        Assert.True(File.Exists(Path.Combine(mods, pin.FileName)));

        // Next launch: the exact pin is there, so nothing is fetched.
        var handler = new Counting(() => throw new InvalidOperationException("no request expected"));
        await FabricApiInstaller.InstallProjectAsync(new HttpClient(handler), "fabric-api", "fabric-api-", "[\"fabric\"]",
            "Installing Fabric API...", pin, "1.21.1", mods, null, default);
        Assert.Equal(0, handler.Requests);
    }

    [Fact]
    public async Task Voice_chat_keeps_any_origin_copy_so_a_switched_off_one_stays_off()
    {
        var off = Jar("mods", "voicechat-fabric-1.21.1-2.6.0.jar.disabled", Kit.FabricJar("voicechat", "1.21.1-2.6.0"));
        OriginInstallRecord.AddFile(off);
        var mods = Path.GetDirectoryName(off)!;
        var pin = new PerfMod("1.21.1-2.6.22", Cdn + "voicechat-fabric-1.21.1-2.6.22.jar", "voicechat-fabric-1.21.1-2.6.22.jar", BodySha512);
        var handler = new Counting(() => throw new InvalidOperationException("no request expected"));

        await FabricApiInstaller.InstallProjectAsync(new HttpClient(handler), "simple-voice-chat", "voicechat-", "[\"fabric\"]",
            "Installing Simple Voice Chat...", pin, "1.21.1", mods, null, default);

        Assert.Equal(0, handler.Requests);
        Assert.False(File.Exists(Path.Combine(mods, pin.FileName)));
    }
}
