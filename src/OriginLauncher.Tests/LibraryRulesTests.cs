using CmlLib.Core;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using CmlLib.Core.Rules;
using CmlLib.Core.VersionLoader;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// 1.8.9's version JSON lists LWJGL twice under one name: 2.9.4 for every OS but osx, then 2.9.2
/// for osx only. A launcher that merges libraries by name before applying the OS rules keeps the
/// first entry, drops it on a Mac, and the game starts with no LWJGL (the mac-boot-probe trap).
/// The launcher builds the classpath through CmlLib, so these pin that CmlLib applies the rules per
/// entry across the inheritsFrom chain (Forge 1.8.9 inherits vanilla 1.8.9): a Mac gets 2.9.2,
/// Windows keeps 2.9.4, and neither gets both. Runs on any OS: the OS is CmlLib's rules context.
/// </summary>
public sealed class LibraryRulesTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-librules-" + Guid.NewGuid().ToString("N"));

    public LibraryRulesTests()
    {
        Write("1.8.9", """
        {
          "id": "1.8.9", "type": "release", "mainClass": "net.minecraft.client.main.Main",
          "minecraftArguments": "--username ${auth_player_name} --version ${version_name}",
          "libraries": [
            { "name": "org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209",
              "rules": [ { "action": "allow" }, { "action": "disallow", "os": { "name": "osx" } } ] },
            { "name": "org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209",
              "rules": [ { "action": "allow" }, { "action": "disallow", "os": { "name": "osx" } } ] },
            { "name": "org.lwjgl.lwjgl:lwjgl:2.9.2-nightly-20140822",
              "rules": [ { "action": "allow", "os": { "name": "osx" } } ] },
            { "name": "org.lwjgl.lwjgl:lwjgl_util:2.9.2-nightly-20140822",
              "rules": [ { "action": "allow", "os": { "name": "osx" } } ] },
            { "name": "com.google.guava:guava:17.0" }
          ]
        }
        """);
        Write("forge-1.8.9", """
        {
          "id": "forge-1.8.9", "inheritsFrom": "1.8.9", "jar": "1.8.9", "type": "release",
          "mainClass": "net.minecraft.launchwrapper.Launch",
          "minecraftArguments": "--username ${auth_player_name} --version ${version_name} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker",
          "libraries": [ { "name": "net.minecraft:launchwrapper:1.12" } ]
        }
        """);
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    [Theory]
    [InlineData("osx", "arm64", "2.9.2-nightly-20140822", "2.9.4-nightly-20150209")]
    [InlineData("osx", "x64", "2.9.2-nightly-20140822", "2.9.4-nightly-20150209")]
    [InlineData("windows", "x64", "2.9.4-nightly-20150209", "2.9.2-nightly-20140822")]
    [InlineData("linux", "x64", "2.9.4-nightly-20150209", "2.9.2-nightly-20140822")]
    [InlineData("linux", "arm64", "2.9.4-nightly-20150209", "2.9.2-nightly-20140822")]
    public async Task Forge_189_classpath_takes_the_lwjgl_its_os_rules_allow(string os, string arch, string wanted, string unwanted)
    {
        var classpath = await ClasspathFor("forge-1.8.9", new LauncherOSRule(os, arch, ""));

        Assert.Contains(classpath, e => e.EndsWith($"lwjgl-{wanted}.jar", StringComparison.Ordinal));
        Assert.Contains(classpath, e => e.EndsWith($"lwjgl_util-{wanted}.jar", StringComparison.Ordinal));
        Assert.DoesNotContain(classpath, e => e.Contains(unwanted, StringComparison.Ordinal));
        Assert.Contains(classpath, e => e.EndsWith("launchwrapper-1.12.jar", StringComparison.Ordinal)); // the child's own library
        Assert.Contains(classpath, e => e.EndsWith("guava-17.0.jar", StringComparison.Ordinal));        // the parent's rule-free library
    }

    // LWJGL 3 versions (1.21.1 shown) list each native as its own library with an os rule. Mojang ships
    // natives-linux (x64) only: no natives-linux-arm64 exists in its metadata (checked 2026-11-02), so an
    // arm64 Linux machine gets the same natives-linux entry from the rules and needs arm64 natives from
    // elsewhere to open a game window (an open item of the Linux port, not something the rules can fix).
    [Theory]
    [InlineData("linux", "x64", "natives-linux", "natives-windows")]
    [InlineData("linux", "arm64", "natives-linux", "natives-windows")]
    [InlineData("windows", "x64", "natives-windows", "natives-linux")]
    public async Task Lwjgl3_natives_follow_the_os_rule(string os, string arch, string wanted, string unwanted)
    {
        Write("1.21.1", """
        {
          "id": "1.21.1", "type": "release", "mainClass": "net.minecraft.client.main.Main",
          "minecraftArguments": "--username ${auth_player_name} --version ${version_name}",
          "libraries": [
            { "name": "org.lwjgl:lwjgl:3.3.3" },
            { "name": "org.lwjgl:lwjgl:3.3.3:natives-linux", "rules": [ { "action": "allow", "os": { "name": "linux" } } ] },
            { "name": "org.lwjgl:lwjgl:3.3.3:natives-windows", "rules": [ { "action": "allow", "os": { "name": "windows" } } ] },
            { "name": "org.lwjgl:lwjgl:3.3.3:natives-macos-arm64", "rules": [ { "action": "allow", "os": { "name": "osx" } } ] }
          ]
        }
        """);
        var classpath = await ClasspathFor("1.21.1", new LauncherOSRule(os, arch, ""));

        Assert.Contains(classpath, e => e.EndsWith("lwjgl-3.3.3.jar", StringComparison.Ordinal));
        Assert.Contains(classpath, e => e.EndsWith($"lwjgl-3.3.3-{wanted}.jar", StringComparison.Ordinal));
        Assert.DoesNotContain(classpath, e => e.Contains(unwanted, StringComparison.Ordinal));
        Assert.DoesNotContain(classpath, e => e.Contains("natives-macos", StringComparison.Ordinal));
    }

    private async Task<string[]> ClasspathFor(string versionName, LauncherOSRule os)
    {
        var path = new MinecraftPath(_root);
        var versions = await new LocalJsonVersionLoader(path).GetVersionMetadatasAsync();
        var version = await versions.GetAndSaveVersionAsync(versionName, path);
        var launcher = new MinecraftLauncher(_root) { RulesContext = new RulesEvaluatorContext(os) };
        var process = launcher.BuildProcess(version, new MLaunchOption
        {
            Session = MSession.CreateOfflineSession("Tester"),
            JavaPath = "java",
        });
        var args = process.StartInfo.ArgumentList.Count > 0
            ? process.StartInfo.ArgumentList.ToList()
            : SplitArgs(process.StartInfo.Arguments);
        var cp = args.IndexOf("-cp");
        Assert.True(cp >= 0 && cp + 1 < args.Count, "no -cp in: " + string.Join(' ', args));
        return args[cp + 1].Split(Path.PathSeparator);
    }

    // CmlLib quotes arguments that contain spaces; the temp root has none, so a plain split is exact.
    private static List<string> SplitArgs(string line) =>
        line.Split(' ', StringSplitOptions.RemoveEmptyEntries).Select(a => a.Trim('"')).ToList();

    private void Write(string id, string json)
    {
        var dir = Path.Combine(_root, "versions", id);
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, id + ".json"), json);
    }
}
