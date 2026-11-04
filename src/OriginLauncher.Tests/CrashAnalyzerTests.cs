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

    private CrashVerdict Diagnose(string log) =>
        CrashEngine.Diagnose(CrashAnalyzer.ReadEvidence(GameDir, Log(log), 1, false, true, 4096, 16384));

    [Fact]
    public void Feather_mixin_plugin_crash_blames_feather_not_modernfix()
    {
        BothJars();

        var v = Diagnose(CrashFixtures.FeatherSentryLaunchLog);

        var culprit = Assert.Single(v.Finding.Culprits);
        Assert.Equal("feather", culprit.ModId);
        Assert.Equal(FeatherJar, culprit.JarFileName);
        Assert.True(culprit.IsExternal);
        Assert.Equal(CrashConfidence.Strong, v.Finding.Confidence);
    }

    [Fact]
    public void Feather_crash_offers_to_turn_Feather_off()
    {
        BothJars();

        var v = Diagnose(CrashFixtures.FeatherSentryLaunchLog);

        Assert.True(v.IsCrash);
        Assert.Equal(CrashCause.ModProblem, v.Finding.Cause);
        Assert.Equal(CrashFixKind.DisableMods, v.SafeFix?.Kind);
        Assert.Equal([FeatherJar], v.SafeFix!.Jars);
        Assert.Contains("feather", v.SafeFix.Label, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void A_known_client_core_in_the_stack_is_blamed_even_when_its_jar_is_not_in_the_index()
    {
        // The jar can't be read, or sits where the index doesn't look: the stack alone still names Feather, but with
        // no file to turn off there is no fix button.
        ModernFixJarOnly();

        var v = Diagnose(CrashFixtures.FeatherSentryLaunchLog);

        Assert.Equal("feather", Assert.Single(v.Finding.Culprits).ModId);
        Assert.Null(v.SafeFix);
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

        var v = Diagnose(log);

        Assert.Equal("somemod", Assert.Single(v.Finding.Culprits).ModId);
    }

    [Fact]
    public void An_unreadable_jar_stays_in_the_mod_set_by_its_file_name()
    {
        File.WriteAllText(Path.Combine(ModsDir, "broken-1.0.jar"), "not a zip");
        Jar("somemod-1.0.0.jar", "somemod");

        var mods = CrashAnalyzer.ReadMods(GameDir);

        Assert.Contains(mods, m => m.JarFileName == "broken-1.0.jar" && m.ModId == "" && m.IsExternal);
        Assert.Contains(mods, m => m.JarFileName == "somemod-1.0.0.jar" && m.ModId == "somemod");
    }

    [Fact]
    public void Files_older_than_the_game_start_do_not_explain_its_exit()
    {
        var reports = Path.Combine(GameDir, "crash-reports");
        Directory.CreateDirectory(reports);
        var old = Path.Combine(reports, "crash-old.txt");
        File.WriteAllText(old, "Description: from yesterday");
        File.SetLastWriteTimeUtc(old, DateTime.UtcNow.AddHours(-1));

        var e = CrashAnalyzer.ReadEvidence(GameDir, null, 1, false, true, 4096, 16384, DateTime.UtcNow.AddMinutes(-1));

        Assert.Null(e.CrashReport);
        Assert.False(CrashEngine.Diagnose(e).IsCrash);
    }

    // ---- KC-1 (bug run 6): mods nested inside a jar (Fabric jar-in-jar) belong to that jar ----

    private static byte[] FabricJarBytes(string modId, params (string Name, byte[] Bytes)[] nested)
    {
        using var buffer = new MemoryStream();
        using (var zip = new ZipArchive(buffer, ZipArchiveMode.Create, leaveOpen: true))
        {
            using (var w = new StreamWriter(zip.CreateEntry("fabric.mod.json").Open()))
                w.Write($"{{\"schemaVersion\":1,\"id\":\"{modId}\",\"version\":\"1.0.0\"}}");
            foreach (var (name, bytes) in nested)
                using (var s = zip.CreateEntry("META-INF/jars/" + name).Open()) s.Write(bytes);
        }
        return buffer.ToArray();
    }

    [Fact]
    public void Nested_mods_are_listed_under_the_jar_that_carries_them()
    {
        var fabricApi = FabricJarBytes("fabric-api", ("fabric-rendering-v1.jar", FabricJarBytes("fabric-rendering-v1")));
        File.WriteAllBytes(Path.Combine(ModsDir, "bundle-1.0.jar"),
            FabricJarBytes("bundle", ("sodium.jar", FabricJarBytes("sodium")), ("fabric-api.jar", fabricApi),
                ("broken.jar", new byte[] { 1, 2, 3 })));

        var mods = CrashAnalyzer.ReadModsFrom(ModsDir);

        Assert.Equal(["bundle", "sodium", "fabric-api", "fabric-rendering-v1"], mods.Select(m => m.ModId));
        Assert.All(mods, m => Assert.Equal("bundle-1.0.jar", m.JarFileName)); // a damaged nested jar is just skipped
    }

    [Fact]
    public void A_top_level_copy_of_a_nested_mod_is_the_one_listed()
    {
        File.WriteAllBytes(Path.Combine(ModsDir, "bundle-1.0.jar"),
            FabricJarBytes("bundle", ("sodium.jar", FabricJarBytes("sodium"))));
        Jar("sodium-fabric-0.6.13+mc1.21.1.jar", "sodium");

        var sodium = Assert.Single(CrashAnalyzer.ReadModsFrom(ModsDir), m => m.ModId == "sodium");

        Assert.Equal("sodium-fabric-0.6.13+mc1.21.1.jar", sodium.JarFileName);
    }
}
