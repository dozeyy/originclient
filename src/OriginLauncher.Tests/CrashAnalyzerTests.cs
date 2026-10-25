using System.IO.Compression;
using OriginLauncher.App.Core.Launch;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Mod blame from a real launch log (2026-10-20): Fabric names the mod whose entrypoint was running ("provided by
/// 'modernfix'") but the failure is the deepest "Caused by", in another jar's code. The deepest frame must win.
/// </summary>
public class CrashAnalyzerTests : IDisposable
{
    private const string FeatherJar = "feather-fabric-1.21.1-1.0.0-SNAPSHOT.temp.jar";
    private const string ModernFixJar = "modernfix-fabric-5.25.1+mc1.21.1.jar";

    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-crashanalyzer-" + Guid.NewGuid().ToString("N"));
    private string ModsDir => Path.Combine(_root, "mods");
    private string GameDir => Path.Combine(_root, "accounts", "offline");

    public CrashAnalyzerTests()
    {
        Directory.CreateDirectory(ModsDir);
        Directory.CreateDirectory(GameDir);
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch { /* temp */ }
    }

    private void Jar(string fileName, string modId, params string[] classes)
    {
        using var zip = ZipFile.Open(Path.Combine(ModsDir, fileName), ZipArchiveMode.Create);
        using (var w = new StreamWriter(zip.CreateEntry("fabric.mod.json").Open()))
            w.Write($"{{\"schemaVersion\":1,\"id\":\"{modId}\",\"version\":\"1.0.0\"}}");
        foreach (var c in classes) zip.CreateEntry(c).Open().Dispose();
    }

    private string Log(string text)
    {
        var path = Path.Combine(_root, "launch.log");
        File.WriteAllText(path, text);
        return path;
    }

    private void ModernFixJarOnly() =>
        Jar(ModernFixJar, "modernfix", "org/embeddedt/modernfix/ModernFixPreLaunchFabric.class");

    private void BothJars()
    {
        ModernFixJarOnly();
        Jar(FeatherJar, "feather", "net/digitalingot/feather/mixin/FeatherMixinPlugin.class", "net/digitalingot/feather/tk.class");
    }

    [Fact]
    public void Feather_mixin_plugin_crash_blames_feather_not_modernfix()
    {
        BothJars();

        var analysis = CrashAnalyzer.Analyze(GameDir, Log(CrashFixtures.FeatherSentryLaunchLog));

        var culprit = Assert.Single(analysis.Culprits);
        Assert.Equal("feather", culprit.ModId);
        Assert.Equal(FeatherJar, culprit.JarFileName);
        Assert.True(culprit.IsExternal);
    }

    [Fact]
    public void Feather_crash_offers_Relaunch_without_Feather()
    {
        BothJars();
        var analysis = CrashAnalyzer.Analyze(GameDir, Log(CrashFixtures.FeatherSentryLaunchLog));

        var d = CrashClassifier.Classify(analysis, CrashFixtures.FeatherSentryLaunchLog, 1, 4096, 16384);

        Assert.Equal(CrashCause.ModProblem, d.Cause);
        Assert.Equal(CrashAction.DisableMod, d.Action);
        Assert.Equal("feather", d.ModId);
        Assert.Equal(FeatherJar, d.ModJar);
        Assert.Contains("feather", d.ActionLabel, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void A_known_client_core_in_the_stack_is_blamed_even_when_its_jar_is_not_in_the_index()
    {
        // The jar can't be read, or sits where the index doesn't look: the stack alone still names Feather.
        ModernFixJarOnly();

        var analysis = CrashAnalyzer.Analyze(GameDir, Log(CrashFixtures.FeatherSentryLaunchLog));

        var culprit = Assert.Single(analysis.Culprits);
        Assert.Equal("feather", culprit.ModId);
    }

    [Fact]
    public void A_crash_inside_the_entrypoint_mods_own_code_still_blames_that_mod()
    {
        // A user mod, not ModernFix: ModernFix is a catalog perf jar Origin ships itself, so it is never blamed.
        Jar("somemod-1.0.0.jar", "somemod", "com/example/somemod/SomeModPreLaunch.class");
        const string log = """
Caused by: java.lang.RuntimeException: Could not execute entrypoint stage 'preLaunch' due to errors, provided by 'somemod' at 'com.example.somemod.SomeModPreLaunch'!
	at net.fabricmc.loader.impl.FabricLoaderImpl.invokeEntrypoints(FabricLoaderImpl.java:416)
Caused by: java.lang.IllegalStateException: boom
	at knot//com.example.somemod.SomeModPreLaunch.onPreLaunch(SomeModPreLaunch.java:12)
	at net.fabricmc.loader.impl.FabricLoaderImpl.invokeEntrypoints(FabricLoaderImpl.java:414)
""";

        var analysis = CrashAnalyzer.Analyze(GameDir, Log(log));

        var culprit = Assert.Single(analysis.Culprits);
        Assert.Equal("somemod", culprit.ModId);
    }
}
