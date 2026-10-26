using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Tests.Import;
using Xunit;

namespace OriginLauncher.Tests.Components;

// Phase 5.2b: the component store, the pins string, the selector, the settings overlay and the pre-launch check of
// an alternate configuration. Temp folders and stub HTTP only; never the network, never the player's data.
public sealed class ComponentTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-components-" + Guid.NewGuid().ToString("N"));

    public ComponentTests()
    {
        Directory.CreateDirectory(_root);
        ComponentStore.ClearVerified();
        LaunchPreflight.ClearCache();
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    // ---- store ----

    private sealed class Counting(Func<byte[]> body, HttpStatusCode code = HttpStatusCode.OK) : HttpMessageHandler
    {
        public int Calls;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Interlocked.Increment(ref Calls);
            return Task.FromResult(new HttpResponseMessage(code) { Content = new ByteArrayContent(body()) });
        }
    }

    private static readonly byte[] Jar = Kit.FabricJar("sodium", "0.6.5+mc1.21.1", name: "Sodium");
    private static readonly PerfMod Pin = new("mc1.21.1-0.6.5-fabric",
        "https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium-fabric-0.6.5%2Bmc1.21.1.jar",
        "sodium-fabric-0.6.5+mc1.21.1.jar", Convert.ToHexString(SHA512.HashData(Jar)).ToLowerInvariant());

    [Fact]
    public async Task Store_fetches_once_and_never_writes_a_good_file_again()
    {
        var http = new Counting(() => Jar);
        var store = new ComponentStore(Path.Combine(_root, "components"), new HttpClient(http));

        var path = await store.EnsureAsync("sodium", Pin, default);
        Assert.Equal(Path.Combine(_root, "components", "sodium", Pin.Sha512![..12], Pin.FileName), path);
        Assert.Equal(Jar, File.ReadAllBytes(path!));
        var written = File.GetLastWriteTimeUtc(path!);

        ComponentStore.ClearVerified(); // even with the hash re-checked, a matching file is left alone
        Assert.Equal(path, await store.EnsureAsync("sodium", Pin, default));
        Assert.Equal(1, http.Calls);
        Assert.Equal(written, File.GetLastWriteTimeUtc(path!));
        Assert.True(store.IsMaterialised("sodium", Pin));
    }

    [Fact]
    public async Task Store_fetches_a_damaged_file_again()
    {
        var http = new Counting(() => Jar);
        var store = new ComponentStore(Path.Combine(_root, "components"), new HttpClient(http));
        var path = (await store.EnsureAsync("sodium", Pin, default))!;

        File.WriteAllBytes(path, new byte[] { 1, 2, 3 });
        File.SetLastWriteTimeUtc(path, DateTime.UtcNow.AddMinutes(1));
        Assert.Equal(path, await store.EnsureAsync("sodium", Pin, default));
        Assert.Equal(2, http.Calls);
        Assert.Equal(Jar, File.ReadAllBytes(path));
    }

    [Fact]
    public async Task Store_refuses_wrong_bytes_and_bad_pins()
    {
        var store = new ComponentStore(Path.Combine(_root, "components"), new HttpClient(new Counting(() => new byte[] { 9 })));
        Assert.Null(await store.EnsureAsync("sodium", Pin, default));
        Assert.False(store.IsMaterialised("sodium", Pin));
        Assert.Empty(Directory.EnumerateFiles(Path.Combine(_root, "components"), "*", SearchOption.AllDirectories));

        Assert.Null(store.PathFor("../x", Pin));
        Assert.Null(store.PathFor("sodium", Pin with { FileName = "..\\evil.jar" }));
        Assert.Null(store.PathFor("sodium", Pin with { Sha512 = null }));
        Assert.Null(store.PathFor("sodium", Pin with { Sha512 = new string('z', 128) }));
        Assert.Null(await store.EnsureAsync("sodium", Pin with { Url = "http://example.com/x.jar" }, default));
    }

    // ---- pins string ----

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("garbage")]
    [InlineData("=1.0")]
    [InlineData("Sodium-Iris=0.6.5")]          // family must be lower case
    [InlineData("sodium-iris=../../x")]        // pin id with a path in it
    [InlineData("sodium-iris=.hidden")]
    [InlineData("sodium iris=0.6.5")]
    public void Malformed_pins_read_as_none(string? text) => Assert.Empty(ComponentPinSet.Parse(text));

    [Fact]
    public void Pins_round_trip_and_keep_the_first_of_a_family()
    {
        var pins = ComponentPinSet.Parse(" sodium-iris=0.6.5+1.8.1 ; junk ; sodium-iris=0.8.0+1.10.0;x=1");
        Assert.Equal("0.6.5+1.8.1", pins["sodium-iris"]);
        Assert.Equal("1", pins["x"]);
        Assert.Equal("sodium-iris=0.6.5+1.8.1;x=1", ComponentPinSet.Format(pins));
        Assert.Equal("x=1", ComponentPinSet.With("sodium-iris=0.6.5+1.8.1;x=1", "sodium-iris", null));
        Assert.Null(ComponentPinSet.With(null, "sodium-iris", null));
        Assert.Throws<ArgumentException>(() => ComponentPinSet.With(null, "sodium-iris", "a/b"));
    }

    [Theory]
    [InlineData("5")]
    [InlineData("{\"a\":1}")]
    [InlineData("[1,2]")]
    [InlineData("true")]
    [InlineData("null")]
    public void A_bad_pins_value_in_settings_json_never_fails_the_file(string raw)
    {
        // settings.json is one document: SettingsStore resets everything on a JsonException, so this field must not throw.
        var json = "{\"Id\":\"0123456789abcdef0123456789abcdef\",\"Name\":\"PvP\",\"Version\":\"1.21.1\",\"ComponentPins\":" + raw +
                   ",\"Tint\":\"Slate\"}";
        var profile = JsonSerializer.Deserialize<ModProfile>(json)!;
        Assert.Null(profile.ComponentPins);
        Assert.Equal("PvP", profile.Name);
        Assert.Equal("Slate", profile.Tint);

        var back = JsonSerializer.Deserialize<ModProfile>(JsonSerializer.Serialize(new ModProfile { ComponentPins = "sodium-iris=0.6.5+1.8.1" }))!;
        Assert.Equal("sodium-iris=0.6.5+1.8.1", back.ComponentPins);
    }

    // ---- catalog ----

    [Fact]
    public void Only_the_spike_proven_pairs_are_offered()
    {
        foreach (var v in VersionCatalog.SupportedVersions)
        {
            var list = ConfigurationCatalog.For(v);
            Assert.Equal(ConfigurationCatalog.DefaultId, list[0].Id);
            Assert.True(list[0].IsDefault);
            var expected = v switch
            {
                "1.21.1" => new[] { "default", "sodium-iris-0.6.5+1.8.1" },
                "1.21.11" => new[] { "default", "sodium-iris-0.8.4+1.10.5", "sodium-iris-0.8.0+1.10.0" },
                _ => new[] { "default" },
            };
            Assert.Equal(expected, list.Select(c => c.Id));
            Assert.Equal(list.Count > 1, ConfigurationCatalog.HasAlternates(v));
            Assert.True(list.Zip(list.Skip(1)).All(p => p.First.Rank > p.Second.Rank)); // newest first
            foreach (var c in list.Where(c => !c.IsDefault))
            {
                Assert.Equal(new[] { "sodium", "iris" }, c.Files.Select(f => f.Family));
                Assert.All(c.Files, f => Assert.Equal(ModrinthDownloadResult.Installed,
                    ModrinthFileGuard.Check(f.Pin.Url, f.Pin.FileName, f.Pin.Sha512)));
                Assert.True(ComponentPinSet.IsPinId(c.PairId));
            }
        }
        Assert.False(ConfigurationCatalog.HasAlternates("1.8.9"));
        Assert.False(ConfigurationCatalog.HasAlternates(null));
    }

    // ---- selector ----

    private static readonly IReadOnlyList<VettedConfiguration> Vetted = ConfigurationCatalog.For("1.21.11");
    private static readonly IReadOnlyDictionary<string, string> NoPins = new Dictionary<string, string>();

    [Fact]
    public void The_shipped_set_wins_when_it_starts_and_alternates_are_never_checked()
    {
        var asked = new List<string>();
        var r = ConfigurationSelector.Pick(Vetted, NoPins, c => { asked.Add(c.Id); return false; }, _ => false, Array.Empty<string>());
        Assert.Equal(PickOutcome.Picked, r.Outcome);
        Assert.Equal("default", r.Configuration.Id);
        Assert.Equal(new[] { "default" }, asked);
    }

    [Fact]
    public void The_newest_alternate_that_starts_is_picked()
    {
        var r = ConfigurationSelector.Pick(Vetted, NoPins, c => c.Id == "default", _ => false, Array.Empty<string>());
        Assert.Equal("sodium-iris-0.8.4+1.10.5", r.Configuration.Id);
        Assert.Contains(r.Log, l => l.Contains("Picked sodium-iris-0.8.4+1.10.5"));

        r = ConfigurationSelector.Pick(Vetted, NoPins, c => c.Id != "sodium-iris-0.8.0+1.10.0", _ => false, Array.Empty<string>());
        Assert.Equal("sodium-iris-0.8.0+1.10.0", r.Configuration.Id);
    }

    [Fact]
    public void A_tie_prefers_the_configuration_already_in_the_store()
    {
        var tied = new[]
        {
            new VettedConfiguration("sodium-iris-a", "x", 1, "a", Vetted[1].Files, ""),
            new VettedConfiguration("sodium-iris-b", "x", 1, "b", Vetted[2].Files, ""),
        };
        Assert.Equal("sodium-iris-a", ConfigurationSelector.Pick(tied, NoPins, _ => false, _ => false, Array.Empty<string>()).Configuration.Id);
        Assert.Equal("sodium-iris-b", ConfigurationSelector.Pick(tied, NoPins, _ => false, c => c.Id == "sodium-iris-b", Array.Empty<string>()).Configuration.Id);
    }

    [Fact]
    public void None_clean_returns_the_top_candidate_for_the_prompt()
    {
        var r = ConfigurationSelector.Pick(Vetted, NoPins, _ => true, _ => true, Array.Empty<string>());
        Assert.Equal(PickOutcome.NoneClean, r.Outcome);
        Assert.Equal("default", r.Configuration.Id);
    }

    [Fact]
    public void A_pin_narrows_and_an_unvetted_pin_is_ignored()
    {
        var pinned = new Dictionary<string, string> { ["sodium-iris"] = "0.8.0+1.10.0" };
        var asked = new List<string>();
        var r = ConfigurationSelector.Pick(Vetted, pinned, c => { asked.Add(c.Id); return false; }, _ => false, Array.Empty<string>());
        Assert.Equal("sodium-iris-0.8.0+1.10.0", r.Configuration.Id);
        Assert.Equal(new[] { "sodium-iris-0.8.0+1.10.0" }, asked);

        var stray = new Dictionary<string, string> { ["sodium-iris"] = "0.6.5+1.8.1" }; // a 1.21.1 pair on 1.21.11
        r = ConfigurationSelector.Pick(Vetted, stray, _ => false, _ => false, Array.Empty<string>());
        Assert.Equal("default", r.Configuration.Id);
        Assert.Contains(r.Log, l => l.Contains("not vetted"));
    }

    [Fact]
    public void Switching_is_refused_while_a_game_of_the_profile_runs_on_another_configuration()
    {
        var r = ConfigurationSelector.Pick(Vetted, NoPins, c => c.Id == "default", _ => false, new[] { "default" });
        Assert.Equal(PickOutcome.Refused, r.Outcome);
        r = ConfigurationSelector.Pick(Vetted, NoPins, _ => false, _ => false, new[] { "default" });
        Assert.Equal(PickOutcome.Picked, r.Outcome);
    }

    [Fact]
    public void Running_registry_counts_games_and_releases_once()
    {
        var a = RunningConfigurations.Started("p1", "default");
        var b = RunningConfigurations.Started("p1", "default");
        a.Dispose();
        a.Dispose();
        Assert.Equal(new[] { "default" }, RunningConfigurations.For("p1"));
        b.Dispose();
        Assert.Empty(RunningConfigurations.For("p1"));
    }

    // ---- settings overlay ----

    private const string Alt = "sodium-iris-0.6.5+1.8.1";

    [Fact]
    public void Overlay_round_trip_brings_the_players_files_back_exactly()
    {
        var config = Path.Combine(_root, "game", "config");
        var overlays = Path.Combine(_root, "overlays");
        Directory.CreateDirectory(config);
        File.WriteAllText(Path.Combine(config, "sodium-options.json"), "{\"mine\":1}");
        File.WriteAllText(Path.Combine(config, "iris.properties"), "shaderPack=Mine.zip");
        File.WriteAllText(Path.Combine(config, "other.json"), "untouched");

        ConfigOverlay.Switch(config, overlays, "default"); // the usual launch: nothing happens
        Assert.False(Directory.Exists(overlays));

        ConfigOverlay.Switch(config, overlays, Alt);
        Assert.Equal(Alt, ConfigOverlay.Active(overlays));
        Assert.False(File.Exists(Path.Combine(config, "sodium-options.json"))); // the old Sodium starts on its own defaults
        Assert.False(File.Exists(Path.Combine(config, "iris.properties")));
        File.WriteAllText(Path.Combine(config, "sodium-options.json"), "{\"old\":1}"); // the game wrote its own

        ConfigOverlay.Switch(config, overlays, "default"); // after exit
        Assert.Equal("{\"mine\":1}", File.ReadAllText(Path.Combine(config, "sodium-options.json")));
        Assert.Equal("shaderPack=Mine.zip", File.ReadAllText(Path.Combine(config, "iris.properties")));
        Assert.Equal("untouched", File.ReadAllText(Path.Combine(config, "other.json")));
        Assert.Equal("default", ConfigOverlay.Active(overlays));

        ConfigOverlay.Switch(config, overlays, Alt); // its own settings come back next time
        Assert.Equal("{\"old\":1}", File.ReadAllText(Path.Combine(config, "sodium-options.json")));
    }

    [Fact]
    public void Overlay_finishes_a_switch_cut_short()
    {
        var config = Path.Combine(_root, "game", "config");
        var overlays = Path.Combine(_root, "overlays");
        Directory.CreateDirectory(config);
        Directory.CreateDirectory(Path.Combine(overlays, "default"));
        // Crash after the player's files were saved and before the alternate's were loaded.
        File.WriteAllText(Path.Combine(overlays, "default", "sodium-options.json"), "{\"mine\":1}");
        File.WriteAllText(Path.Combine(config, "sodium-options.json"), "{\"mine\":1}");
        File.WriteAllText(Path.Combine(overlays, "active.txt"), "pending:" + Alt);

        ConfigOverlay.Switch(config, overlays, "default");
        Assert.Equal("default", ConfigOverlay.Active(overlays));
        Assert.Equal("{\"mine\":1}", File.ReadAllText(Path.Combine(config, "sodium-options.json")));
        Assert.Throws<ArgumentException>(() => ConfigOverlay.Switch(config, overlays, "..\\x"));
    }

    [Fact]
    public void Active_configuration_registers_and_restores_on_dispose()
    {
        var game = Path.Combine(_root, "game");
        var overlays = Path.Combine(_root, "overlays");
        Directory.CreateDirectory(Path.Combine(game, "config"));
        File.WriteAllText(Path.Combine(game, "config", "sodium-options.json"), "mine");

        var active = ActiveConfiguration.Begin("p2", Alt, game, overlays);
        Assert.Equal(new[] { Alt }, RunningConfigurations.For("p2"));
        Assert.False(File.Exists(Path.Combine(game, "config", "sodium-options.json")));
        active.Dispose();
        active.Dispose();
        Assert.Empty(RunningConfigurations.For("p2"));
        Assert.Equal("mine", File.ReadAllText(Path.Combine(game, "config", "sodium-options.json")));
    }

    // ---- the pre-launch check of an alternate ----

    private (string Shared, string Profile) PinnedOldSodiumProfile()
    {
        var shared = Path.Combine(_root, "shared");
        var profile = Path.Combine(_root, "profile");
        Directory.CreateDirectory(shared);
        Directory.CreateDirectory(profile);
        File.WriteAllBytes(Path.Combine(shared, "originclient-1.21.1.jar"), Kit.OriginClientJar()); // nests Sodium 0.6.13
        File.WriteAllBytes(Path.Combine(shared, "fabric-api-0.116.7+1.21.1.jar"), Kit.FabricJar("fabric-api", "0.116.7+1.21.1"));
        File.WriteAllBytes(Path.Combine(profile, "oldaddon-1.0.jar"), Kit.FabricJar("oldaddon", "1.0.0", name: "Old Addon",
            depends: new Dictionary<string, object> { ["sodium"] = "<0.6.10" }));
        return (shared, profile);
    }

    private static LaunchVerdict Check(string shared, string profile, LaunchSwap? swap) =>
        LaunchPreflight.Evaluate("1.21.1", shared, profile, true, ModManager.IsManaged, ModManager.IsOriginProvisioned,
            swap: swap);

    [Fact]
    public async Task An_alternate_starts_a_profile_the_shipped_set_cannot()
    {
        var (shared, profile) = PinnedOldSodiumProfile();
        var http = new Counting(() => Jar);
        var store = new ComponentStore(Path.Combine(_root, "components"), new HttpClient(http));
        var alt = ConfigurationCatalog.For("1.21.1")[1];

        Assert.NotEmpty(Check(shared, profile, null).Blocking);                 // shipped Sodium 0.6.13 is too new
        var swap = ConfigurationLaunch.SwapFor(alt, store)!;
        Assert.Empty(Check(shared, profile, swap).Blocking);                    // judged before anything is fetched
        Assert.Equal(0, http.Calls);

        // The Play path: picks the alternate, then fetches it. A stub cannot serve the real pin's bytes (its SHA-512 is
        // Modrinth's), so the fetch fails and the shipped set is what gets the final check (which would prompt).
        var ran = new List<string?>();
        var lines = new List<string>();
        var choice = await ConfigurationLaunch.ChooseAsync("1.21.1", null, store,
            s => Check(shared, profile, s), s => ran.Add(s?.Id), Array.Empty<string>(), lines.Add, default);
        Assert.Equal("default", choice.Configuration.Id);
        Assert.Null(choice.Swap);
        Assert.Equal(new string?[] { null }, ran);
        Assert.Contains(lines, l => l.Contains("Picked sodium-iris-0.6.5+1.8.1"));
        Assert.Contains(lines, l => l.Contains("could not be fetched"));
        Assert.Equal(1, http.Calls);
    }

    [Fact]
    public async Task A_picked_alternate_already_in_the_store_is_launched()
    {
        var (shared, profile) = PinnedOldSodiumProfile();
        var http = new Counting(() => Array.Empty<byte>(), HttpStatusCode.NotFound);
        var store = new ComponentStore(Path.Combine(_root, "components"), new HttpClient(http));
        var alt = ConfigurationCatalog.For("1.21.1")[1];
        // EnsureAsync accepts a file whose SHA-512 matches its pin; the real Modrinth bytes are not available here, so
        // this places files and marks them checked the way a finished fetch would (the store's own hash path is
        // covered by the store tests above).
        foreach (var f in alt.Files) PlaceAsFetched(store, f);

        var ran = new List<string?>();
        var choice = await ConfigurationLaunch.ChooseAsync("1.21.1", null, store,
            s => Check(shared, profile, s), s => ran.Add(s?.Id), Array.Empty<string>(), _ => { }, default);
        Assert.Equal(alt.Id, choice.Configuration.Id);
        Assert.Equal(new string?[] { alt.Id }, ran);
        Assert.Equal(0, http.Calls);
    }

    private static void PlaceAsFetched(ComponentStore store, ComponentFile f)
    {
        var path = store.PathFor(f.Family, f.Pin)!;
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, Kit.FabricJar(f.ModId, f.ModVersion, name: f.ModId));
        ComponentStore.MarkVerifiedForTests(path);
    }

    [Fact]
    public async Task A_materialised_alternate_is_launched_with_its_real_files_checked()
    {
        var (shared, profile) = PinnedOldSodiumProfile();
        var store = new ComponentStore(Path.Combine(_root, "components"), new HttpClient(new Counting(() => Array.Empty<byte>(),
            HttpStatusCode.NotFound)));
        var alt = ConfigurationCatalog.For("1.21.1")[1];
        // Put files at the store paths as if fetched (bytes the test controls; the store's hash check is covered above).
        foreach (var f in alt.Files)
        {
            var path = store.PathFor(f.Family, f.Pin)!;
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllBytes(path, Kit.FabricJar(f.ModId, f.ModVersion, name: f.ModId));
        }
        var swap = ConfigurationLaunch.SwapFor(alt, store)!;
        var verdict = Check(shared, profile, swap);
        Assert.Empty(verdict.Blocking);
        Assert.NotEqual(Check(shared, profile, null).SetKey, verdict.SetKey); // a different set, a different override key

        // A real file that still declares the too-new version is caught once it is there.
        File.WriteAllBytes(swap.Files[0].Path, Kit.FabricJar("sodium", "0.6.13+mc1.21.1", name: "Sodium"));
        Assert.NotEmpty(Check(shared, profile, swap).Blocking);
        await Task.CompletedTask;
    }

    [Fact]
    public async Task Refused_while_the_profile_runs_on_another_configuration()
    {
        var store = new ComponentStore(Path.Combine(_root, "components"), new HttpClient(new Counting(() => Jar)));
        await Assert.ThrowsAsync<ConfigurationInUseException>(() => ConfigurationLaunch.ChooseAsync("1.21.11", null, store,
            _ => LaunchVerdict.Clear, _ => { }, new[] { "sodium-iris-0.8.4+1.10.5" }, _ => { }, default));
    }

    [Fact]
    public void Swap_takes_the_nested_copies_out_of_origins_jar_only()
    {
        var swap = new LaunchSwap("x", ConfigurationCatalog.ReplacedPrefixes, ConfigurationCatalog.ReplacedIds, Array.Empty<SwapFile>());
        var info = new ModInfo
        {
            FileName = "originclient-1.21.1.jar", Id = "originclient",
            Nested = new[] { new NestedMod("sodium", "0.6.13", "Sodium"), new NestedMod("iris", "1.8.8", "Iris"), new NestedMod("modmenu", "11", "Mod Menu") },
        };
        Assert.Equal(new[] { "modmenu" }, swap.WithoutReplaced(info).Nested.Select(n => n.Id));
        Assert.True(swap.Replaces("Sodium-Fabric-0.6.13+mc1.21.1.jar"));
        Assert.False(swap.Replaces("sodium-extra-0.6.0.jar"));
    }
}
