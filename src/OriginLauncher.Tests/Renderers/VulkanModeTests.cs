using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Renderers;
using Xunit;

namespace OriginLauncher.Tests;

// 1.21.1's opt-in Vulkan graphics (Core/Renderers). The block rule runs on real jars built from the fixture
// fabric.mod.json files in Renderers/Fixtures; the folder plan, the disableModIds merge and the boot fallback are pure;
// the renderer files run on temp folders and a stub HTTP handler, never the network or the player's data.
public sealed class VulkanModeTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-vulkan-" + Guid.NewGuid().ToString("N"));

    public VulkanModeTests() => Directory.CreateDirectory(_root);

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    private string P(params string[] parts) => Path.Combine([_root, .. parts]);

    // A jar holding only the named fixture as its fabric.mod.json.
    private string JarFromFixture(string fixture, string jarName, string? folder = null)
    {
        var dir = folder ?? P("mods");
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, jarName);
        var json = File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Renderers", "Fixtures", fixture + ".json"));
        using (var zip = ZipFile.Open(path, ZipArchiveMode.Create))
        using (var writer = new StreamWriter(zip.CreateEntry("fabric.mod.json").Open()))
            writer.Write(json);
        return path;
    }

    private static string Touch(string path, string text = "x")
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, text);
        return path;
    }

    // ---- block rule (fixture fabric.mod.json files) ----------------------------------------------------------------

    [Fact]
    public void A_mod_that_depends_on_Sodium_blocks_Vulkan_and_is_named()
    {
        var jar = JarFromFixture("sodium-extra", "sodium-extra-0.6.0+mc1.21.1.jar");
        var blockers = VulkanRendererFiles.ScanBlockers([jar], _ => false);
        var b = Assert.Single(blockers);
        Assert.Equal("Sodium Extra", b.Name);
        Assert.Equal("sodium", b.NeedsId);
        Assert.Equal("sodium-extra-0.6.0+mc1.21.1.jar", b.FileName);
    }

    [Fact]
    public void Recommends_own_id_and_provides_all_block()
    {
        var jars = new[]
        {
            JarFromFixture("recommends-iris", "picker.jar"),
            JarFromFixture("own-sodium", "sodium-fabric-0.6.13.jar"),
            JarFromFixture("provides-embeddium", "rubidium.jar"),
        };
        var blockers = VulkanRendererFiles.ScanBlockers(jars, _ => false);
        Assert.Equal(["picker.jar", "rubidium.jar", "sodium-fabric-0.6.13.jar"], blockers.Select(b => b.FileName));
        Assert.Equal(["iris", "embeddium", "sodium"], blockers.Select(b => b.NeedsId));
    }

    [Fact]
    public void Clean_mods_a_breaks_entry_and_unreadable_jars_do_not_block()
    {
        var jars = new[]
        {
            JarFromFixture("clean-lithium", "lithium.jar"),
            JarFromFixture("breaks-only", "betterhud.jar"),
            Touch(P("mods", "broken.jar"), "not a zip"),
            Touch(P("mods", "notes.txt")),
        };
        Assert.Empty(VulkanRendererFiles.ScanBlockers(jars, _ => false));
    }

    [Fact]
    public void Origins_own_jars_are_never_scanned()
    {
        // Origin's 1.21.1 jar carries Sodium and Iris by design; the disableModIds switch handles it instead.
        var origin = JarFromFixture("own-sodium", "originclient-1.21.1.jar");
        var player = JarFromFixture("sodium-extra", "sodium-extra.jar");
        var blockers = VulkanRendererFiles.ScanBlockers([origin, player], p => p == origin);
        Assert.Equal("sodium-extra.jar", Assert.Single(blockers).FileName);
    }

    [Fact]
    public void State_and_lines_name_the_mods_in_plain_words()
    {
        Assert.Equal(VulkanState.MissingJars, VulkanMode.StateOf(jarsPresent: false, blockerCount: 2));
        Assert.Equal(VulkanState.Blocked, VulkanMode.StateOf(true, 1));
        Assert.Equal(VulkanState.Available, VulkanMode.StateOf(true, 0));

        VulkanBlocker B(string name) => new(name + ".jar", name, "sodium");
        Assert.Equal("Can't turn on while you have A: it only works with normal graphics.",
            VulkanMode.BlockedSettingsLine([B("A")]));
        Assert.Equal("Can't turn on while you have A and B: they only work with normal graphics.",
            VulkanMode.BlockedSettingsLine([B("A"), B("B")]));
        Assert.Equal("A, B, C and 2 more", VulkanMode.Names([B("A"), B("B"), B("C"), B("D"), B("E")]));
        Assert.Equal("Normal graphics this time: A only works with it, so Vulkan stayed off",
            VulkanMode.BlockedHomeLine([B("A")]));
    }

    [Fact]
    public void Only_1_21_1_with_the_setting_on_wants_Vulkan()
    {
        Assert.True(VulkanMode.Wanted(true, "1.21.1"));
        Assert.False(VulkanMode.Wanted(false, "1.21.1"));
        Assert.False(VulkanMode.Wanted(true, "1.21.11"));
        Assert.False(VulkanMode.Wanted(true, "1.20.1"));
        Assert.False(VulkanMode.Wanted(true, null));
    }

    // ---- folder plan + args ----------------------------------------------------------------------------------------

    [Fact]
    public void Folder_plan_is_the_normal_jars_plus_the_renderer_jars_and_nothing_else()
    {
        string M(string n) => P("m", n);
        string R(string n) => P("r", n);
        var plan = VulkanMode.FolderPlan(
            [M("originclient-1.21.1.jar"), M("lithium.jar"), M("old.jar.disabled"), M("readme.txt"), M("shared.jar")],
            [R("VulkanMod-0.6.7+1.21.1.jar"), R("vulkairis-origin-1.0.jar"), R("shared.jar")]);
        Assert.Equal(
            new[] { "originclient-1.21.1.jar", "lithium.jar", "shared.jar", "VulkanMod-0.6.7+1.21.1.jar", "vulkairis-origin-1.0.jar" }
                .OrderBy(n => n, StringComparer.OrdinalIgnoreCase),
            plan.Keys.OrderBy(n => n, StringComparer.OrdinalIgnoreCase));
        Assert.Equal(R("shared.jar"), plan["shared.jar"]); // the renderer's copy wins a name clash
    }

    [Fact]
    public void The_plan_builds_mods_vulkan_from_hard_links_without_touching_the_sources()
    {
        var baseJar = Touch(P("instance", "mods", "lithium.jar"), "lithium");
        var vulkanJar = Touch(P("renderers", "vulkan", "VulkanMod-0.6.7+1.21.1.jar"), "vulkan");
        var folder = P("instance", "accounts", "offline", VulkanMode.LaunchFolderName);
        Touch(Path.Combine(folder, "stale.jar"), "left from last time");

        LaunchModFolder.Sync(folder, VulkanMode.FolderPlan([baseJar], [vulkanJar]), static _ => true);

        Assert.Equal(["lithium.jar", "VulkanMod-0.6.7+1.21.1.jar"],
            Directory.GetFiles(folder).Select(Path.GetFileName).OrderBy(n => n, StringComparer.OrdinalIgnoreCase));
        Assert.Equal("lithium", File.ReadAllText(Path.Combine(folder, "lithium.jar")));
        Assert.Equal("vulkan", File.ReadAllText(Path.Combine(folder, "VulkanMod-0.6.7+1.21.1.jar")));
        Assert.Equal("vulkan", File.ReadAllText(vulkanJar)); // the source stays where it was
        Assert.False(File.Exists(Path.Combine(folder, "stale.jar")));
    }

    [Fact]
    public void The_disable_argument_merges_with_the_player_plan_into_one()
    {
        Assert.Null(VulkanMode.DisableModIdsArgument([], vulkan: false));
        Assert.Equal("-Dfabric.debug.disableModIds=sodium,immediatelyfast", VulkanMode.DisableModIdsArgument([], vulkan: true));
        Assert.Equal("-Dfabric.debug.disableModIds=freelook", VulkanMode.DisableModIdsArgument(["freelook"], vulkan: false));
        // The loader reads one argument only: the player plan's ids first, each id once.
        Assert.Equal("-Dfabric.debug.disableModIds=freelook,sodium,immediatelyfast",
            VulkanMode.DisableModIdsArgument(["freelook", " sodium ", "", "SODIUM"], vulkan: true));
    }

    // ---- boot fallback ---------------------------------------------------------------------------------------------

    [Theory]
    [InlineData(true, HandoffStage.Loading, false, 1, true)]    // died while loading on Vulkan: fall back
    [InlineData(true, HandoffStage.Starting, false, -1, true)]  // never opened a window
    [InlineData(true, HandoffStage.Ready, false, 1, false)]     // crashed after the title screen: a normal crash
    [InlineData(true, HandoffStage.Loading, true, 1, false)]    // the player pressed Cancel
    [InlineData(true, HandoffStage.Loading, false, 0, false)]   // quit cleanly
    [InlineData(false, HandoffStage.Loading, false, 1, false)]  // a normal-graphics launch never falls back
    public void Fallback_only_for_a_Vulkan_game_that_failed_before_the_title_screen(
        bool vulkan, HandoffStage reached, bool stopped, int exit, bool expected) =>
        Assert.Equal(expected, VulkanMode.ShouldFallBack(vulkan, reached, stopped, exit));

    // ---- renderer files --------------------------------------------------------------------------------------------

    [Fact]
    public void Bundled_jars_need_both_and_take_the_newest_name()
    {
        var dir = P("bundled");
        Assert.Null(VulkanRendererFiles.BundledJars(dir));
        Touch(Path.Combine(dir, "vulkairis-origin-1.0.0.jar"));
        Touch(Path.Combine(dir, "vulkairis-origin-1.0.1.jar"));
        Touch(Path.Combine(dir, "vulkairis-origin-1.0.2-sources.jar"));
        Assert.Null(VulkanRendererFiles.BundledJars(dir)); // flywheel-vulkan missing
        Touch(Path.Combine(dir, "flywheel-vulkan-0.1.0.jar"));
        var jars = VulkanRendererFiles.BundledJars(dir)!;
        Assert.Equal(["vulkairis-origin-1.0.1.jar", "flywheel-vulkan-0.1.0.jar"], jars.Select(Path.GetFileName));
    }

    private sealed class Stub(byte[] body) : HttpMessageHandler
    {
        public int Calls;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Calls++;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(body) });
        }
    }

    private static readonly byte[] VulkanModBytes = [0x50, 0x4B, 0x03, 0x04, 9, 8, 7, 6];

    private static PerfMod Pin(byte[] bytes) => new("0.6.7+1.21.1",
        "https://cdn.modrinth.com/data/JYQhtZtO/versions/2eqroDKD/VulkanMod-0.6.7%2B1.21.1.jar",
        "VulkanMod-0.6.7+1.21.1.jar", Convert.ToHexString(SHA512.HashData(bytes)).ToLowerInvariant());

    private string Bundle()
    {
        var dir = P("bundled");
        Touch(Path.Combine(dir, "vulkairis-origin-1.0.0.jar"), "vi");
        Touch(Path.Combine(dir, "flywheel-vulkan-0.1.0.jar"), "fw");
        return dir;
    }

    [Fact]
    public async Task Ensure_downloads_VulkanMod_once_on_a_hash_match_and_copies_the_bundle_in()
    {
        var stub = new Stub(VulkanModBytes);
        var renderer = P("renderers", "vulkan");
        var files = await VulkanRendererFiles.EnsureAsync(new HttpClient(stub), Pin(VulkanModBytes), Bundle(), renderer, CancellationToken.None);

        Assert.True(files.Ready);
        Assert.Equal(["VulkanMod-0.6.7+1.21.1.jar", "vulkairis-origin-1.0.0.jar", "flywheel-vulkan-0.1.0.jar"],
            files.Jars!.Select(Path.GetFileName));
        Assert.Equal(VulkanModBytes, File.ReadAllBytes(Path.Combine(renderer, "VulkanMod-0.6.7+1.21.1.jar")));
        Assert.Equal("fw", File.ReadAllText(Path.Combine(renderer, "flywheel-vulkan-0.1.0.jar")));
        Assert.Empty(Directory.GetFiles(renderer, "*.download"));

        var again = await VulkanRendererFiles.EnsureAsync(new HttpClient(stub), Pin(VulkanModBytes), Bundle(), renderer, CancellationToken.None);
        Assert.True(again.Ready);
        Assert.Equal(1, stub.Calls); // already in place and verified: no second download
    }

    [Fact]
    public async Task A_download_that_does_not_match_the_pin_is_refused_and_the_launch_stays_on_normal_graphics()
    {
        var stub = new Stub([1, 2, 3, 4]);
        var renderer = P("renderers", "vulkan");
        var files = await VulkanRendererFiles.EnsureAsync(new HttpClient(stub), Pin(VulkanModBytes), Bundle(), renderer, CancellationToken.None);
        Assert.False(files.Ready);
        Assert.Equal(VulkanMode.SetupFailedLine, files.Problem);
        Assert.False(File.Exists(Path.Combine(renderer, "VulkanMod-0.6.7+1.21.1.jar")));
    }

    [Fact]
    public async Task A_damaged_VulkanMod_already_there_is_replaced_by_a_verified_one()
    {
        var renderer = P("renderers", "vulkan");
        Touch(Path.Combine(renderer, "VulkanMod-0.6.7+1.21.1.jar"), "damaged");
        var stub = new Stub(VulkanModBytes);
        var files = await VulkanRendererFiles.EnsureAsync(new HttpClient(stub), Pin(VulkanModBytes), Bundle(), renderer, CancellationToken.None);
        Assert.True(files.Ready);
        Assert.Equal(1, stub.Calls);
        Assert.Equal(VulkanModBytes, File.ReadAllBytes(Path.Combine(renderer, "VulkanMod-0.6.7+1.21.1.jar")));
    }

    [Fact]
    public async Task No_bundle_means_unavailable_and_nothing_is_downloaded()
    {
        var stub = new Stub(VulkanModBytes);
        var files = await VulkanRendererFiles.EnsureAsync(new HttpClient(stub), Pin(VulkanModBytes), P("nothing"), P("renderers", "vulkan"), CancellationToken.None);
        Assert.Equal(VulkanMode.MissingJarsLine, files.Problem);
        Assert.Equal(0, stub.Calls);
    }

    [Fact]
    public void The_shipped_VulkanMod_pin_is_a_Modrinth_file_with_a_SHA512()
    {
        var pin = ManagedPins.VulkanMod;
        Assert.StartsWith("https://cdn.modrinth.com/data/JYQhtZtO/versions/2eqroDKD/", pin.Url);
        Assert.Equal(128, pin.Sha512!.Length);
        Assert.EndsWith(".jar", pin.FileName);
    }
}
