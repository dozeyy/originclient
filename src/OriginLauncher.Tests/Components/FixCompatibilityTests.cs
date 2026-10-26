using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.Tests.Import;
using Xunit;

namespace OriginLauncher.Tests.Components;

// Phase 5.3 Fix Compatibility: the planner/executor make a fixed COPY (the original is never changed), the executor is
// journaled and recovers, and the only thing sent to Modrinth is a missing mod's id. Temp folders and stub HTTP only.
public sealed class FixCompatibilityTests : IDisposable
{
    private const string Mc = "1.21.1";
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-fix-" + Guid.NewGuid().ToString("N"));
    private readonly string _shared;
    private readonly string _profiles;
    private readonly string _journals;
    private readonly LauncherSettings _settings = new();
    private readonly ModProfile _profile;

    public FixCompatibilityTests()
    {
        _shared = Directory.CreateDirectory(Path.Combine(_root, "mods")).FullName;
        _profiles = Directory.CreateDirectory(Path.Combine(_root, "profiles")).FullName;
        _journals = Path.Combine(_root, "state", "repairs");
        _profile = new ModProfile { Id = ProfileRules.NewId(), Name = "Hypixel PvP", Version = Mc, Tint = "sky" };
        _settings.ModProfiles.Add(_profile);
        _settings.SelectedProfileId = _profile.Id;
        LaunchPreflight.ClearCache();
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    private string ProfileMods => Path.Combine(_profiles, _profile.Id, "mods");

    private void Update(Action<LauncherSettings> mutate) => mutate(_settings);

    private FixSource Source() =>
        new(Mc, _profile, _shared, ProfileMods, _ => false, _ => false);

    private void Put(string folder, string file, byte[] jar)
    {
        Directory.CreateDirectory(folder);
        File.WriteAllBytes(Path.Combine(folder, file), jar);
    }

    // Every file under a folder: relative path -> (bytes hash, last write).
    private static Dictionary<string, (string, DateTime)> Snapshot(string folder) =>
        Directory.EnumerateFiles(folder, "*", SearchOption.AllDirectories).ToDictionary(
            f => Path.GetRelativePath(folder, f),
            f => (Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(f))), File.GetLastWriteTimeUtc(f)));

    private FixProposal Plan(IDependencyCatalog? catalog = null)
    {
        var report = LaunchPreflight.Inspect(Mc, _shared, ProfileMods, true, _ => false, _ => false)!;
        var shipped = ConfigurationCatalog.For(Mc).First(c => c.IsDefault);
        return FixCompatibility.Choose(Source(), new[] { (shipped, report) }, catalog, LaunchPreflight.PlatformFor(Mc));
    }

    private RepairCopyResult Apply(FixProposal proposal, IDependencyFetcher? fetcher = null, Action<Action<LauncherSettings>>? update = null) =>
        FixCompatibility.Apply(proposal, _profiles, _journals, update ?? Update, fetcher,
            id => _settings.ModProfiles.Any(p => p.Id == id), _ => _profiles);

    // ---- planner + executor: the copy ----

    [Fact]
    public void Fix_makes_a_copy_and_leaves_the_original_byte_identical()
    {
        Put(ProfileMods, "oldmod-1.0.jar", Kit.FabricJar("oldmod", minecraft: "1.20.4", name: "Old Mod"));
        Put(ProfileMods, "goodmod-2.0.jar", Kit.FabricJar("goodmod", "2.0.0", name: "Good Mod"));
        Put(ProfileMods, "already-off.jar.disabled", Kit.FabricJar("off"));
        Directory.CreateDirectory(Path.Combine(ProfileMods, "config"));
        File.WriteAllText(Path.Combine(ProfileMods, "config", "x.json"), "{}");
        var before = Snapshot(Path.Combine(_profiles, _profile.Id));
        var settingsBefore = JsonSerializer.Serialize(_profile);

        var proposal = Plan();
        Assert.True(proposal.CanFix);
        Assert.Contains(proposal.Changes, c => c.StartsWith("Turn off Old Mod:"));
        Assert.Equal(before, Snapshot(Path.Combine(_profiles, _profile.Id))); // planning reads only

        var result = Apply(proposal);

        Assert.True(result.Ok, result.Error);
        Assert.Equal(before, Snapshot(Path.Combine(_profiles, _profile.Id)));
        Assert.Equal(settingsBefore, JsonSerializer.Serialize(_profile));
        var copyMods = Path.Combine(_profiles, result.ProfileId!, "mods");
        Assert.True(File.Exists(Path.Combine(copyMods, "oldmod-1.0.jar.disabled")));
        Assert.False(File.Exists(Path.Combine(copyMods, "oldmod-1.0.jar")));
        Assert.True(File.Exists(Path.Combine(copyMods, "goodmod-2.0.jar")));
        Assert.True(File.Exists(Path.Combine(copyMods, "config", "x.json")));
        Assert.Contains("Turned off oldmod-1.0.jar", File.ReadAllText(Path.Combine(_profiles, result.ProfileId!, RepairExecutor.NotesFile)));
        var copy = Assert.Single(_settings.ModProfiles, p => p.Id == result.ProfileId);
        Assert.Equal("Hypixel PvP fixed", copy.Name);
        Assert.Equal("sky", copy.Tint);
        Assert.Equal(copy.Id, _settings.SelectedProfileId);
        Assert.Empty(Directory.GetFiles(_journals));
        Assert.Empty(Directory.GetDirectories(_profiles, "*" + RepairExecutor.StagingSuffix));

        // The copy now passes the same check Play runs.
        var verdict = LaunchPreflight.Evaluate(Mc, _shared, copyMods, true, _ => false, _ => false);
        Assert.Empty(verdict.Blocking);
    }

    [Fact]
    public void A_fix_of_the_shared_folder_copies_only_the_players_mods_and_never_changes_the_folder()
    {
        Put(_shared, "originclient-0.4.2.jar", Kit.OriginClientJar());
        Put(_shared, "oldmod-1.0.jar", Kit.FabricJar("oldmod", minecraft: "1.20.4", name: "Old Mod"));
        Put(_shared, "goodmod-2.0.jar", Kit.FabricJar("goodmod", name: "Good Mod"));
        var before = Snapshot(_shared);
        var source = new FixSource(Mc, null, _shared, null, n => n.StartsWith("originclient"), n => n.StartsWith("originclient"));
        var report = LaunchPreflight.Inspect(Mc, _shared, null, true, source.IsManaged, source.IsOriginProvisioned)!;
        var proposal = FixCompatibility.Choose(source, new[] { (ConfigurationCatalog.For(Mc)[0], report) }, null,
            LaunchPreflight.PlatformFor(Mc));

        var result = Apply(proposal);

        Assert.True(result.Ok, result.Error);
        Assert.Equal(before, Snapshot(_shared));
        var files = Directory.GetFiles(Path.Combine(_profiles, result.ProfileId!, "mods")).Select(Path.GetFileName).Order().ToArray();
        Assert.Equal(new[] { "goodmod-2.0.jar", "oldmod-1.0.jar.disabled" }, files);
        Assert.Equal("My mods fixed", _settings.ModProfiles.Single(p => p.Id == result.ProfileId).Name);
    }

    private sealed class OneOffer(string id, byte[] jar) : IDependencyCatalog
    {
        public DependencyOffer? Find(string wanted, VersionPredicate range, CompatPlatform platform) =>
            wanted != id ? null : new DependencyOffer(id, "15.0.140", id + "-15.0.140.jar",
                new ModInfo { FileName = id + "-15.0.140.jar", Loader = ModLoader.Fabric, Id = id, Version = "15.0.140" },
                "https://cdn.modrinth.com/data/x/" + id + ".jar", Sha512: Convert.ToHexString(SHA512.HashData(jar)));
    }

    private sealed class Serving(byte[] bytes) : IDependencyFetcher
    {
        public int Calls;

        public bool Fetch(DependencyOffer offer, string destinationPath, out string? error)
        {
            Calls++;
            File.WriteAllBytes(destinationPath, bytes);
            error = null;
            return true;
        }
    }

    [Fact]
    public void A_missing_dependency_is_added_to_the_copy_only_when_its_checksum_matches()
    {
        Put(ProfileMods, "somelib-2.1.jar", Kit.FabricJar("somelib", depends: new() { ["cloth-config"] = ">=15.0.0" }, name: "Some Lib"));
        var cloth = Kit.FabricJar("cloth-config", "15.0.140", name: "Cloth Config");
        var before = Snapshot(ProfileMods);
        var proposal = Plan(new OneOffer("cloth-config", cloth));
        Assert.Contains(proposal.Changes, c => c.StartsWith("Add cloth-config 15.0.140 from Modrinth"));

        // Wrong bytes: nothing is registered, the staging copy is gone, the original is untouched.
        var bad = Apply(proposal, new Serving(Kit.FabricJar("cloth-config", "9.9.9")));
        Assert.False(bad.Ok);
        Assert.Single(_settings.ModProfiles);
        Assert.Equal(new[] { _profile.Id }, Directory.GetDirectories(_profiles).Select(Path.GetFileName).ToArray());
        Assert.Equal(before, Snapshot(ProfileMods));

        var good = Apply(proposal, new Serving(cloth));
        Assert.True(good.Ok, good.Error);
        Assert.Equal(cloth, File.ReadAllBytes(Path.Combine(_profiles, good.ProfileId!, "mods", "cloth-config-15.0.140.jar")));
        Assert.Equal(before, Snapshot(ProfileMods));
    }

    [Fact]
    public void An_older_vetted_pair_is_pinned_when_it_needs_fewer_changes()
    {
        var platform = LaunchPreflight.PlatformFor(Mc);
        var configs = ConfigurationCatalog.For(Mc);
        var shipped = configs.First(c => c.IsDefault);
        var older = configs.First(c => !c.IsDefault);
        ModFile Mod(string id, string version, ModRole role, params ModDependency[] deps) =>
            new(Path.Combine(ProfileMods, id + ".jar"), id + ".jar", true, role,
                new ModInfo { FileName = id + ".jar", Loader = ModLoader.Fabric, Id = id, Version = version, Name = id, Depends = deps });
        var addon = Mod("oldaddon", "1.0.0", ModRole.Profile, new ModDependency("sodium", new[] { "<0.6.10" }));
        var withShipped = CompatEngine.Check(new[] { Mod("sodium", "0.6.13+mc1.21.1", ModRole.Managed), addon }, platform);
        var withOlder = CompatEngine.Check(new[] { Mod("sodium", "0.6.5+mc1.21.1", ModRole.Managed), addon }, platform);

        var proposal = FixCompatibility.Choose(Source(), new[] { (shipped, withShipped), (older, withOlder) }, null, platform);

        Assert.Same(older, proposal.Configuration);
        Assert.True(proposal.PinChanged);
        Assert.True(proposal.Plan.IsEmpty);
        Assert.Equal("sodium-iris=" + older.PairId, proposal.ComponentPins);
        Assert.StartsWith("Use Sodium 0.6.5", proposal.Changes[0]);

        var result = Apply(proposal);
        Assert.True(result.Ok, result.Error);
        Assert.Equal("sodium-iris=" + older.PairId, _settings.ModProfiles.Single(p => p.Id == result.ProfileId).ComponentPins);
        Assert.Null(_profile.ComponentPins); // the original keeps picking for itself
    }

    [Fact]
    public void Nothing_to_click_says_why_in_plain_words()
    {
        var platform = LaunchPreflight.PlatformFor(Mc);
        var origin = new ModFile(Path.Combine(_shared, "originclient.jar"), "originclient.jar", true, ModRole.Managed,
            new ModInfo { FileName = "originclient.jar", Loader = ModLoader.Fabric, Id = "originclient", Name = "Origin Client",
                Version = "0.4.2", Depends = new[] { new ModDependency("minecraft", new[] { "1.20.4" }) } });
        var report = CompatEngine.Check(new[] { origin }, platform);
        var proposal = FixCompatibility.Choose(Source(), new[] { (ConfigurationCatalog.For(Mc)[0], report) }, null, platform);

        Assert.False(proposal.CanFix);
        Assert.NotEmpty(proposal.NoFix);
    }

    // Holds the first fix inside its download until the test lets it go.
    private sealed class Held(byte[] bytes) : IDependencyFetcher
    {
        public readonly ManualResetEventSlim Entered = new();
        public readonly ManualResetEventSlim Go = new();

        public bool Fetch(DependencyOffer offer, string destinationPath, out string? error)
        {
            Entered.Set();
            Go.Wait(TimeSpan.FromSeconds(30));
            File.WriteAllBytes(destinationPath, bytes);
            error = null;
            return true;
        }
    }

    [Fact]
    public async Task A_second_fix_while_one_is_running_is_refused_and_leaves_the_first_ones_copy_alone()
    {
        Put(ProfileMods, "somelib-2.1.jar", Kit.FabricJar("somelib", depends: new() { ["cloth-config"] = ">=15.0.0" }, name: "Some Lib"));
        Put(ProfileMods, "goodmod-2.0.jar", Kit.FabricJar("goodmod", name: "Good Mod"));
        var cloth = Kit.FabricJar("cloth-config", "15.0.140", name: "Cloth Config");
        var proposal = Plan(new OneOffer("cloth-config", cloth));
        var held = new Held(cloth);

        var first = Task.Run(() => Apply(proposal, held));
        Assert.True(held.Entered.Wait(TimeSpan.FromSeconds(30)));
        var staging = Assert.Single(Directory.GetDirectories(_profiles, "*" + RepairExecutor.StagingSuffix));
        var journal = Assert.Single(Directory.GetFiles(_journals));

        var second = Apply(proposal, new Serving(cloth));

        Assert.False(second.Ok);
        Assert.Equal(FixCompatibility.BusyText, second.Error);
        Assert.True(Directory.Exists(staging));
        Assert.True(File.Exists(journal));
        Assert.True(File.Exists(Path.Combine(staging, "mods", "goodmod-2.0.jar")));
        Assert.Single(_settings.ModProfiles);

        held.Go.Set();
        var done = await first;
        Assert.True(done.Ok, done.Error);
        var mods = Path.Combine(_profiles, done.ProfileId!, "mods");
        Assert.True(File.Exists(Path.Combine(mods, "goodmod-2.0.jar")));
        Assert.True(File.Exists(Path.Combine(mods, "somelib-2.1.jar")));
        Assert.True(File.Exists(Path.Combine(mods, "cloth-config-15.0.140.jar")));
        Assert.Equal(2, _settings.ModProfiles.Count);
    }

    // ---- journal + recovery ----

    [Fact]
    public void A_crash_before_settings_were_written_is_cleaned_up_and_a_registered_copy_is_kept()
    {
        Put(ProfileMods, "oldmod-1.0.jar", Kit.FabricJar("oldmod", minecraft: "1.20.4"));
        var proposal = Plan();

        // Settings can't be written (the power went, the disk is full): the copy's folder is left with its journal.
        var failed = Apply(proposal, update: _ => throw new IOException("disk full"));
        Assert.False(failed.Ok);
        var journal = Assert.Single(Directory.GetFiles(_journals));
        var orphan = Path.Combine(_profiles, Path.GetFileNameWithoutExtension(journal));
        Assert.True(Directory.Exists(orphan));
        Assert.Contains("moved", File.ReadAllText(journal));

        // Recovery: settings don't name it, so the folder and journal go; the original is still there.
        Assert.Equal(1, RepairExecutor.RecoverCopies(_journals, _ => _profiles, id => _settings.ModProfiles.Any(p => p.Id == id)));
        Assert.False(Directory.Exists(orphan));
        Assert.Empty(Directory.GetFiles(_journals));
        Assert.True(File.Exists(Path.Combine(ProfileMods, "oldmod-1.0.jar")));

        // A journal whose copy settings DO name (the crash came after the settings write): the copy stays.
        var kept = Apply(proposal);
        File.WriteAllText(Path.Combine(_journals, kept.ProfileId + ".json"),
            $"{{\"Version\":\"{Mc}\",\"ProfileId\":\"{kept.ProfileId}\",\"Stage\":\"moved\"}}");
        Assert.Equal(1, RepairExecutor.RecoverCopies(_journals, _ => _profiles, id => _settings.ModProfiles.Any(p => p.Id == id)));
        Assert.True(Directory.Exists(Path.Combine(_profiles, kept.ProfileId!)));
    }

    [Fact]
    public void Recovery_clears_a_staging_folder_and_ignores_a_damaged_journal_safely()
    {
        var id = ProfileRules.NewId();
        var staging = Directory.CreateDirectory(Path.Combine(_profiles, id + RepairExecutor.StagingSuffix, "mods")).Parent!.FullName;
        File.WriteAllText(Path.Combine(staging, "mods", "half.jar"), "x");
        Directory.CreateDirectory(_journals);
        File.WriteAllText(Path.Combine(_journals, id + ".json"), $"{{\"Version\":\"{Mc}\",\"ProfileId\":\"{id}\",\"Stage\":\"copying\"}}");
        // A journal that names a path outside the profiles folder is never trusted: paths come from version + id only.
        File.WriteAllText(Path.Combine(_journals, "evil.json"), "{\"Version\":\"..\\\\..\",\"ProfileId\":\"evil\",\"Stage\":\"moved\"}");
        File.WriteAllText(Path.Combine(_journals, ProfileRules.NewId() + ".json"), "not json");

        RepairExecutor.RecoverCopies(_journals, _ => _profiles, _ => false);

        Assert.False(Directory.Exists(staging));
        Assert.Empty(Directory.GetFiles(_journals));
        Assert.True(Directory.Exists(_shared));
    }

    // ---- Modrinth: what is sent ----

    private sealed class Recording(string json) : HttpMessageHandler
    {
        public readonly List<(HttpMethod Method, Uri Uri, string? Body, string Headers)> Requests = new();

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var body = request.Content == null ? null : await request.Content.ReadAsStringAsync(ct);
            Requests.Add((request.Method, request.RequestUri!, body, request.Headers.ToString()));
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(json, Encoding.UTF8, "application/json") };
        }
    }

    private static string Version(string number, string type, string[] loaders, string[] games, string url, string file, string? sha512) =>
        JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["version_number"] = number, ["version_type"] = type, ["loaders"] = loaders, ["game_versions"] = games,
            ["files"] = new[]
            {
                new Dictionary<string, object?>
                {
                    ["url"] = url, ["filename"] = file, ["primary"] = true,
                    ["hashes"] = sha512 == null ? new Dictionary<string, string>() : new Dictionary<string, string> { ["sha512"] = sha512 },
                },
            },
        });

    [Fact]
    public async Task Modrinth_is_asked_with_the_missing_mod_id_and_nothing_else()
    {
        var hash = new string('a', 128);
        var cdn = "https://cdn.modrinth.com/data/9s6osm5g/versions/x/";
        var json = "[" + string.Join(",",
            Version("16.0.1", "beta", new[] { "fabric" }, new[] { Mc }, cdn + "b.jar", "b.jar", hash),
            Version("15.0.141", "release", new[] { "forge" }, new[] { Mc }, cdn + "f.jar", "f.jar", hash),
            Version("15.0.140", "release", new[] { "fabric" }, new[] { "1.21" }, cdn + "o.jar", "o.jar", hash),
            Version("15.0.139", "release", new[] { "fabric" }, new[] { Mc }, "https://evil.example/c.jar", "c.jar", hash),
            Version("15.0.138", "release", new[] { "fabric" }, new[] { Mc }, cdn + "n.jar", "n.jar", null),
            Version("15.0.137", "release", new[] { "fabric", "quilt" }, new[] { Mc }, cdn + "cloth-config-15.0.137.jar",
                "cloth-config-15.0.137.jar", hash),
            Version("14.0.1", "release", new[] { "fabric" }, new[] { Mc }, cdn + "old.jar", "old.jar", hash)) + "]";
        var handler = new Recording(json);
        using var http = new HttpClient(handler);

        var catalog = await ModrinthDependencyCatalog.ResolveAsync(http,
            new[] { "cloth-config", "minecraft", "Not An Id", "../../etc", "cloth-config" }, Mc, default);

        var request = Assert.Single(handler.Requests);
        Assert.Equal(HttpMethod.Get, request.Method);
        Assert.Equal("https://api.modrinth.com/v2/project/cloth-config/version", request.Uri.AbsoluteUri);
        Assert.Equal("", request.Uri.Query);
        Assert.Null(request.Body); // no body at all: the mod id in the path is the whole request
        Assert.Equal("", request.Headers.Trim());

        var platform = LaunchPreflight.PlatformFor(Mc);
        var offer = catalog.Find("cloth-config", VersionPredicate.Parse(">=15.0.0"), platform);
        Assert.NotNull(offer);
        Assert.Equal("15.0.137", offer!.Version);
        Assert.Equal(hash, offer.Sha512);
        Assert.Equal("cloth-config-15.0.137.jar", offer.FileName);
        Assert.Null(catalog.Find("cloth-config", VersionPredicate.Parse(">=16.0.0"), platform));
        Assert.Null(catalog.Find("other", VersionPredicate.Parse("*"), platform));
    }

    [Fact]
    public void Only_ids_the_report_is_missing_are_ever_asked_about()
    {
        var platform = LaunchPreflight.PlatformFor(Mc);
        var mod = new ModFile(Path.Combine(ProfileMods, "a.jar"), "a.jar", true, ModRole.Profile,
            new ModInfo { FileName = "a.jar", Loader = ModLoader.Fabric, Id = "a", Version = "1.0.0", Depends = new[]
            {
                new ModDependency("cloth-config", new[] { "*" }), new ModDependency("minecraft", new[] { "1.21.1" }),
            } });
        var ids = ModrinthDependencyCatalog.MissingIds(new[] { CompatEngine.Check(new[] { mod }, platform) });
        Assert.Equal(new[] { "cloth-config" }, ids);
    }
}
