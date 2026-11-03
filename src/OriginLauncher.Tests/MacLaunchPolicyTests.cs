using System.Runtime.InteropServices;
using CmlLib.Core;
using CmlLib.Core.Auth;
using CmlLib.Core.Java;
using CmlLib.Core.ProcessBuilder;
using CmlLib.Core.Rules;
using CmlLib.Core.VersionLoader;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Versions;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The Mac launch rules (Core/Launch/MacLaunchPolicy.cs) for every version the picker offers: which run
/// natively on Apple Silicon, which need Rosetta, which are LWJGL 2, and that CmlLib then looks for Java
/// in the right Mojang runtime folder. Pure, so it runs on every OS; the macos CI job's --launch-plan
/// proves the same rules against Mojang's real files.
/// </summary>
public sealed class MacLaunchPolicyTests : IDisposable
{
    // Mojang ships no arm64 Java or arm64 LWJGL natives for these (LWJGL 3.2.x, jre-legacy/alpha/beta;
    // 1.8.9 is LWJGL 2 + Temurin 8 x64).
    private static readonly HashSet<string> NeedsRosetta = new(StringComparer.Ordinal) { "1.8.9", "1.16.5", "1.17.1", "1.18.2" };

    public static IEnumerable<object[]> LiveVersions => VersionCatalog.SupportedVersions.Select(v => new object[] { v });

    [Theory]
    [MemberData(nameof(LiveVersions))]
    public void Apple_silicon_runs_native_from_1_19_and_rosetta_below(string version)
    {
        var shape = MacLaunchPolicy.For(version, Architecture.Arm64);
        var rosetta = NeedsRosetta.Contains(version);
        Assert.Equal(rosetta ? MacJavaArch.X64 : MacJavaArch.Arm64, shape.JavaArch);
        Assert.Equal(rosetta, shape.UnderRosetta);
        Assert.Equal(version == "1.8.9", shape.Lwjgl2);
        Assert.Equal(rosetta ? "mac-os" : "mac-os-arm64",
            MinecraftJavaManifestResolver.GetOSNameForJava(MacLaunchPolicy.RulesOs(shape, "15.0")));
    }

    [Theory]
    [MemberData(nameof(LiveVersions))]
    public void Intel_mac_runs_everything_x64_without_rosetta(string version)
    {
        var shape = MacLaunchPolicy.For(version, Architecture.X64);
        Assert.Equal(MacJavaArch.X64, shape.JavaArch);
        Assert.False(shape.UnderRosetta);
        var os = MacLaunchPolicy.RulesOs(shape, "15.0");
        Assert.Equal(("osx", "64"), (os.Name, os.Arch));
        Assert.Equal("mac-os", MinecraftJavaManifestResolver.GetOSNameForJava(os));
    }

    // The import copies Java into the same runtime folder CmlLib launches from (RulesOs -> GetOSNameForJava),
    // so an imported 1.8.9 / 1.18.2 Java on Apple Silicon is the x64 one, never a mac-os-arm64 that doesn't exist.
    [Theory]
    [MemberData(nameof(LiveVersions))]
    public void Import_java_platform_matches_the_launch_on_every_mac(string version)
    {
        foreach (var host in new[] { Architecture.Arm64, Architecture.X64 })
        {
            var shape = MacLaunchPolicy.For(version, host);
            Assert.Equal(MinecraftJavaManifestResolver.GetOSNameForJava(MacLaunchPolicy.RulesOs(shape, "15.0")),
                MacLaunchPolicy.JavaRuntimePlatform(shape));
            Assert.Equal(shape.JavaArch == MacJavaArch.Arm64 ? "arm64" : "x86_64", MacLaunchPolicy.RuleArch(shape));
        }
        Assert.Equal("mac-os", MacLaunchPolicy.JavaRuntimePlatform(MacLaunchPolicy.For("1.18.2", Architecture.Arm64)));
        Assert.Equal("mac-os-arm64", MacLaunchPolicy.JavaRuntimePlatform(MacLaunchPolicy.For("1.19", Architecture.Arm64)));
    }

    [Theory]
    [MemberData(nameof(LiveVersions))]
    public void Every_live_version_has_a_mac_record(string version)
    {
        var record = MacVersionCapabilities.For(version);
        // No pinned shader stack has macOS-failure evidence today (MacVersionCapabilities' notes); a "no" needs its cited entry.
        Assert.True(record.ShadersAvailable);
        Assert.Null(record.ShaderNote);
        Assert.Equal(NeedsRosetta.Contains(version), record.NeedsRosettaOnAppleSilicon);
    }

    [Theory]
    [InlineData("1.12.2", true, true)]
    [InlineData("1.13", false, true)]
    [InlineData("1.19", false, false)]
    [InlineData("26.3", false, false)]
    [InlineData("27.1", false, false)]
    [InlineData("24w14a", false, true)] // a snapshot name parses as old: x64 is the safe answer
    public void Unlisted_versions_follow_the_same_lines(string version, bool lwjgl2, bool rosetta)
    {
        Assert.Equal(lwjgl2, MacLaunchPolicy.UsesLwjgl2(version));
        Assert.Equal(rosetta, MacLaunchPolicy.For(version, Architecture.Arm64).UnderRosetta);
    }

    [Fact]
    public void Missing_rosetta_offers_the_install_instead_of_a_retry()
    {
        var failure = PlayFailure.From(new RosettaRequiredException("1.8.9"), "1.8.9");
        Assert.True(failure.OffersRosettaInstall);
        Assert.Contains("Rosetta", failure.Title);
        Assert.DoesNotContain(PlayFailure.RetryLine, failure.Message);
        Assert.False(PlayFailure.From(new IOException("x"), "1.8.9").OffersRosettaInstall);
    }

    // -- CmlLib's half of the LWJGL 2 rule: a legacy JSON (no jvm arguments) gets CmlLib's defaults, which
    //    add -XstartOnFirstThread on osx; JvmArgumentOverrides (what PlatformLaunch.Mac sets for LWJGL 2)
    //    replaces them and still carries Origin's extra flags, -cp and the main class.

    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-macpolicy-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    [Fact]
    public async Task Legacy_json_on_osx_gets_first_thread_from_cmllib_defaults_unless_overridden()
    {
        Write("1.8.9", """
        { "id": "1.8.9", "type": "release", "mainClass": "net.minecraft.client.main.Main",
          "minecraftArguments": "--username ${auth_player_name} --version ${version_name}",
          "libraries": [ { "name": "com.google.guava:guava:17.0" } ] }
        """);
        var plain = await ArgsFor("1.8.9", option => { });
        Assert.Contains(MacLaunchPolicy.StartOnFirstThread, plain);

        var overridden = await ArgsFor("1.8.9", option =>
            option.JvmArgumentOverrides = (option.ExtraJvmArguments ?? Enumerable.Empty<MArgument>()).ToList());
        Assert.DoesNotContain(MacLaunchPolicy.StartOnFirstThread, overridden);
        Assert.Contains("-Dorigin.test=1", overridden);
        Assert.Contains("-cp", overridden);
        Assert.Contains("net.minecraft.client.main.Main", overridden);
    }

    [Fact]
    public async Task Lwjgl3_json_keeps_mojangs_own_first_thread_argument()
    {
        Write("1.21.1", """
        { "id": "1.21.1", "type": "release", "mainClass": "net.minecraft.client.main.Main",
          "arguments": {
            "game": [ "--username", "${auth_player_name}" ],
            "jvm": [ { "rules": [ { "action": "allow", "os": { "name": "osx" } } ], "value": [ "-XstartOnFirstThread" ] },
                     "-Djava.library.path=${natives_directory}", "-cp", "${classpath}" ] },
          "libraries": [ { "name": "org.lwjgl:lwjgl:3.3.3" } ] }
        """);
        Assert.Contains(MacLaunchPolicy.StartOnFirstThread, await ArgsFor("1.21.1", option => { }));
    }

    // -- Where java reads the main class (JavaArgv, used by the Mac --launch-plan proof). v1.0.84 on a Mac: CmlLib's
    //    DockName came out as "-Xdock:name" "Minecraft", java ran "Minecraft" as the main class and no version started,
    //    while the old check only asked whether the real main class appeared ANYWHERE in the line.

    private const string Knot = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    private static readonly string[] VanillaMain = ["net.minecraft.client.main.Main"];

    private static string[] Line(params string[] dock) =>
        ["-XstartOnFirstThread", "-Djava.library.path=/n", "-cp", "/a.jar:/b.jar", "-DFabricMcEmu= net.minecraft.client.main.Main ",
         "-Xmx4096m", "-Xms4096m", .. dock, "-Dlog4j.configurationFile=/c.xml", Knot, "--username", "Tester"];

    [Fact]
    public void Main_class_check_fails_the_split_dock_name_and_passes_the_whole_one()
    {
        // The 1.21.1 shape CI built on 2026-11-02 (paths shortened).
        var split = JavaArgv.MainClassProblem(Line("-Xdock:name", "Minecraft"), [Knot]);
        Assert.NotNull(split);
        Assert.Contains("-Xdock:name", split);
        Assert.Null(JavaArgv.MainClassProblem(Line(MacLaunchPolicy.DockNameArgument), [Knot]));

        // A value option's own value is not the main class; a stray word before the main class is, wherever it sits.
        Assert.Null(JavaArgv.MainClassProblem(["--add-opens", "java.base/java.lang=ALL-UNNAMED", "-classpath", "x", Knot], [Knot]));
        Assert.NotNull(JavaArgv.MainClassProblem(["-cp", "x", "Minecraft", Knot], [Knot]));
        Assert.NotNull(JavaArgv.MainClassProblem(["-cp", "x", "-Xmx1G"], [Knot]));
    }

    [Fact]
    public async Task Cmllib_splits_dock_name_but_the_mac_argument_reaches_java_whole()
    {
        Write("1.21.1", """
        { "id": "1.21.1", "type": "release", "mainClass": "net.minecraft.client.main.Main",
          "arguments": { "game": [ "--username", "${auth_player_name}" ], "jvm": [ "-cp", "${classpath}" ] },
          "libraries": [ { "name": "org.lwjgl:lwjgl:3.3.3" } ] }
        """);
        // Pins CmlLib 4.0.6's behaviour: if a CmlLib update writes "-Xdock:name=..." itself, this says so.
        Assert.NotNull(JavaArgv.MainClassProblem(await ArgsFor("1.21.1", o => o.DockName = "Minecraft"), VanillaMain));

        var whole = await ArgsFor("1.21.1", o =>
            o.ExtraJvmArguments = (o.ExtraJvmArguments ?? Enumerable.Empty<MArgument>()).Append(new MArgument(MacLaunchPolicy.DockNameArgument)).ToList());
        Assert.Contains(MacLaunchPolicy.DockNameArgument, whole);
        Assert.Null(JavaArgv.MainClassProblem(whole, VanillaMain));
    }

#if ORIGIN_MAC
    // The real Mac seam on the macos CI job: one whole -Xdock:name argument, and java reads the real main class.
    [Fact]
    public async Task Mac_seam_names_the_dock_without_moving_the_main_class()
    {
        Write("1.8.9", """
        { "id": "1.8.9", "type": "release", "mainClass": "net.minecraft.client.main.Main",
          "minecraftArguments": "--username ${auth_player_name}", "libraries": [] }
        """);
        foreach (var version in new[] { "1.8.9", "1.21.1" })
        {
            var args = await ArgsFor("1.8.9", o => PlatformLaunch.AdjustOption(version, o));
            Assert.Contains(MacLaunchPolicy.DockNameArgument, args);
            Assert.DoesNotContain("-Xdock:name", args);
            Assert.Null(JavaArgv.MainClassProblem(args, VanillaMain));
        }
    }

    // The real Mac seam (PlatformLaunch.Mac.cs) on the macos CI job: drops the flag for LWJGL 2 only.
    [Fact]
    public async Task Mac_seam_drops_first_thread_for_lwjgl2_only()
    {
        Write("1.8.9", """
        { "id": "1.8.9", "type": "release", "mainClass": "net.minecraft.client.main.Main",
          "minecraftArguments": "--username ${auth_player_name}", "libraries": [] }
        """);
        Assert.DoesNotContain(MacLaunchPolicy.StartOnFirstThread, await ArgsFor("1.8.9", o => PlatformLaunch.AdjustOption("1.8.9", o)));
        Assert.Contains(MacLaunchPolicy.StartOnFirstThread, await ArgsFor("1.8.9", o => PlatformLaunch.AdjustOption("1.16.5", o)));
    }
#endif

    private async Task<List<string>> ArgsFor(string versionName, Action<MLaunchOption> adjust)
    {
        var path = new MinecraftPath(_root);
        var versions = await new LocalJsonVersionLoader(path).GetVersionMetadatasAsync();
        var version = await versions.GetAndSaveVersionAsync(versionName, path);
        var launcher = new MinecraftLauncher(_root)
        {
            RulesContext = new RulesEvaluatorContext(MacLaunchPolicy.RulesOs(MacLaunchPolicy.For(versionName, Architecture.Arm64), "15.0")),
        };
        var option = new MLaunchOption
        {
            Session = MSession.CreateOfflineSession("Tester"),
            JavaPath = "java",
            ExtraJvmArguments = [new MArgument("-Dorigin.test=1")],
        };
        adjust(option);
        var process = launcher.BuildProcess(version, option);
        return process.StartInfo.Arguments.Split(' ', StringSplitOptions.RemoveEmptyEntries).Select(a => a.Trim('"')).ToList();
    }

    private void Write(string id, string json)
    {
        var dir = Path.Combine(_root, "versions", id);
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, id + ".json"), json);
    }
}
