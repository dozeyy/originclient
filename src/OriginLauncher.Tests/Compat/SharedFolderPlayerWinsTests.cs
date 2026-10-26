using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.Tests.Import;
using Xunit;

namespace OriginLauncher.Tests.Compat;

// Will's own setup: his mods (his own Sodium among them) sit in instances/1.21.1/mods, the version's SHARED folder,
// with no profile. DECISIONS.md 2026-10-25 ("a player's own mods win") applies there too:
//   - the launch cleanups delete only jars Origin itself installed (pins / install record), never by name or mod id;
//   - PlayerOverrides decides which copy loads, exactly as for a profile, without touching either file.
// Temp folders only.
public sealed class SharedFolderPlayerWinsTests : IDisposable
{
    private readonly string _mods = Path.Combine(Path.GetTempPath(), "origin-shared-wins-" + Guid.NewGuid().ToString("N"), "mods");

    public SharedFolderPlayerWinsTests()
    {
        Directory.CreateDirectory(_mods);
        ModManager.ClearOwnershipCache();
    }

    public void Dispose()
    {
        try { Directory.Delete(Path.GetDirectoryName(_mods)!, recursive: true); } catch (IOException) { }
    }

    private string Put(string name, byte[] bytes)
    {
        var path = Path.Combine(_mods, name);
        File.WriteAllBytes(path, bytes);
        return path;
    }

    // A jar an Origin installer put here (the installers record what they write).
    private string Origins(string name, byte[] bytes)
    {
        var path = Put(name, bytes);
        OriginInstallRecord.AddFile(path);
        return path;
    }

    // The player's jars of every shape Origin's own families use, plus addons named like them.
    private Dictionary<string, byte[]> PlayersJars()
    {
        var jars = new Dictionary<string, byte[]>
        {
            ["sodium-fabric-0.8.2+mc1.21.1.jar"] = Kit.FabricJar("sodium", "0.8.2+mc1.21.1"),
            ["iris-fabric-1.9.0+mc1.21.1.jar"] = Kit.FabricJar("iris", "1.9.0+mc1.21.1"),
            ["modmenu-11.0.4.jar"] = Kit.FabricJar("modmenu", "11.0.4"),
            ["lithium-fabric-0.15.1+mc1.21.1.jar"] = Kit.FabricJar("lithium", "0.15.1+mc1.21.1"),
            ["fabric-api-0.116.99+1.21.1.jar"] = Kit.FabricJar("fabric-api", "0.116.99+1.21.1"),
            ["sodium-extra-0.6.0+mc1.21.1.jar"] = Kit.FabricJar("sodium-extra", "0.6.0+mc1.21.1"),
        };
        foreach (var (name, bytes) in jars) Put(name, bytes);
        return jars;
    }

    private void AssertUntouched(Dictionary<string, byte[]> jars)
    {
        foreach (var (name, bytes) in jars)
        {
            var path = Path.Combine(_mods, name);
            Assert.True(File.Exists(path), name + " was deleted");
            Assert.Equal(bytes, File.ReadAllBytes(path));
        }
    }

    [Fact]
    public void The_121_purge_deletes_origins_old_standalone_copies_and_never_the_players_own()
    {
        var mine = PlayersJars();
        var leftover = Origins("sodium-fabric-0.5.11+mc1.21.1.jar", Kit.FabricJar("sodium", "0.5.11+mc1.21.1"));
        var leftoverIris = Origins("iris-1.7.3+mc1.21.jar", Kit.FabricJar("iris", "1.7.3+mc1.21"));
        Put("originclient.jar", Kit.OriginClientJar());

        var gone = SharedFolderCleanup.PurgeStandalonePerf(_mods, ModManager.IsOriginsOwnJar);

        Assert.Equal(new[] { Path.GetFileName(leftoverIris), Path.GetFileName(leftover) }.OrderBy(n => n), gone.OrderBy(n => n));
        Assert.False(File.Exists(leftover));
        AssertUntouched(mine);
        Assert.True(File.Exists(Path.Combine(_mods, "originclient.jar")));
    }

    [Fact]
    public void The_twin_cleanup_keeps_one_of_origins_copies_and_never_compares_the_players()
    {
        var mine = PlayersJars();
        var oldPin = Origins("fabric-api-0.100.0+1.21.1.jar", Kit.FabricJar("fabric-api", "0.100.0+1.21.1"));
        var pin = Origins("fabric-api-0.116.6+1.21.1.jar", Kit.FabricJar("fabric-api", "0.116.6+1.21.1"));

        var gone = SharedFolderCleanup.DedupeOriginFamilies(_mods,
            new HashSet<string>(StringComparer.OrdinalIgnoreCase) { Path.GetFileName(pin) }, ModManager.IsOriginsOwnJar);

        Assert.Equal(new[] { Path.GetFileName(oldPin) }, gone);
        Assert.True(File.Exists(pin));
        AssertUntouched(mine); // the player's newer Fabric API 0.116.99 is not "a twin" of Origin's: it stays
    }

    [Fact]
    public void A_jar_that_cannot_be_read_is_never_deleted_even_when_origin_recorded_it()
    {
        var leftover = Origins("sodium-fabric-0.5.11+mc1.21.1.jar", Kit.FabricJar("sodium", "0.5.11+mc1.21.1"));
        ModManager.ClearOwnershipCache();
        using (new FileStream(leftover, FileMode.Open, FileAccess.Read, FileShare.None))
            Assert.Empty(SharedFolderCleanup.PurgeStandalonePerf(_mods, ModManager.IsOriginsOwnJar));
        Assert.True(File.Exists(leftover));
    }

    [Fact]
    public void Shared_folder_player_sodium_with_no_tested_iris_turns_origins_nested_iris_off()
    {
        // 1.21.1: Origin's Sodium/Iris live inside originclient.jar. The player's own Sodium (a root jar) already beats
        // Origin's nested copy; Origin's nested Iris is untested with it, so the loader is told to skip it.
        Put("originclient.jar", Kit.OriginClientJar());
        var theirs = Put("sodium-fabric-0.6.9+mc1.21.1.jar", Kit.FabricJar("sodium", "0.6.9+mc1.21.1"));

        var plan = LaunchPreflight.SharedOverridesFor("1.21.1", _mods, ModManager.IsManaged, ModManager.IsOriginsCopy);

        Assert.Contains("iris", plan.DisabledIds);
        Assert.Contains("iris", plan.JvmArgument);
        Assert.Contains("sodium", plan.OverriddenIds);
        Assert.False(plan.Skips(theirs));
        Assert.True(File.Exists(theirs)); // planning never touches a file
    }

    [Fact]
    public void Shared_folder_player_iris_without_their_sodium_is_set_aside_for_the_launch_only()
    {
        Put("originclient.jar", Kit.OriginClientJar());
        var theirs = Put("iris-fabric-1.9.0+mc1.21.1.jar", Kit.FabricJar("iris", "1.9.0+mc1.21.1"));

        var plan = LaunchPreflight.SharedOverridesFor("1.21.1", _mods, ModManager.IsManaged, ModManager.IsOriginsCopy);

        Assert.True(plan.Skips(theirs));
        var set = LaunchModFolder.LaunchSet(Directory.EnumerateFiles(_mods), Enumerable.Empty<string>(),
            static _ => true, null, plan.Skips);
        Assert.DoesNotContain(Path.GetFileName(theirs), set.Keys);
        Assert.True(File.Exists(theirs)); // set aside = kept out of this launch's folder, the jar itself stays
    }

    [Fact]
    public void Shared_folder_player_sodium_replaces_origins_top_level_copy_in_the_launch_set()
    {
        // 1.20.1-style: Origin's Sodium and Iris are standalone jars in the shared folder. The player's own Sodium
        // loads instead of Origin's (and Origin's untested Iris stays out); both of Origin's files stay on disk.
        Put("originclient.jar", Kit.FabricJar("originclient", "0.4.2"));
        var api = Origins("fabric-api-0.92.2+1.20.1.jar", Kit.FabricJar("fabric-api", "0.92.2+1.20.1"));
        var originSodium = Origins("sodium-fabric-mc1.20.1-0.5.11.jar", Kit.FabricJar("sodium", "0.5.11+mc1.20.1"));
        var originIris = Origins("iris-mc1.20.1-1.7.2.jar", Kit.FabricJar("iris", "1.7.2+mc1.20.1"));
        var theirs = Put("sodium-fabric-0.5.13+mc1.20.1.jar", Kit.FabricJar("sodium", "0.5.13+mc1.20.1"));

        var plan = LaunchPreflight.SharedOverridesFor("1.20.1", _mods, ModManager.IsManaged, ModManager.IsOriginsCopy);
        var set = LaunchModFolder.LaunchSet(Directory.EnumerateFiles(_mods), Enumerable.Empty<string>(),
            static _ => true, null, plan.Skips);

        Assert.Contains(Path.GetFileName(theirs), set.Keys);
        Assert.Contains(Path.GetFileName(api), set.Keys);
        Assert.Contains("originclient.jar", set.Keys);
        Assert.DoesNotContain(Path.GetFileName(originSodium), set.Keys);
        Assert.DoesNotContain(Path.GetFileName(originIris), set.Keys);
        Assert.True(File.Exists(originSodium) && File.Exists(originIris) && File.Exists(theirs));
    }

    [Fact]
    public void A_toggle_never_overwrites_a_different_copy_under_the_other_name()
    {
        var on = Put("mymod-1.0.jar", Kit.FabricJar("mymod", "1.0.0"));
        var offBytes = Kit.FabricJar("mymod", "0.9.0");
        Put("mymod-1.0.jar.disabled", offBytes);

        var result = ModManager.SetEnabledIn(_mods, "mymod-1.0.jar", enabled: false);

        Assert.False(result.Ok);
        Assert.True(File.Exists(on));
        Assert.Equal(offBytes, File.ReadAllBytes(on + ".disabled"));

        // An identical twin (a crash between two toggles) is still just replaced.
        File.WriteAllBytes(on + ".disabled", File.ReadAllBytes(on));
        Assert.True(ModManager.SetEnabledIn(_mods, "mymod-1.0.jar", enabled: false).Ok);
        Assert.False(File.Exists(on));
    }

    [Fact]
    public void A_shared_folder_with_no_player_copies_launches_exactly_as_before()
    {
        Put("originclient.jar", Kit.OriginClientJar());
        Origins("fabric-api-0.116.6+1.21.1.jar", Kit.FabricJar("fabric-api", "0.116.6+1.21.1"));
        Put("appleskin-fabric-3.0.5.jar", Kit.FabricJar("appleskin", "3.0.5"));

        var plan = LaunchPreflight.SharedOverridesFor("1.21.1", _mods, ModManager.IsManaged, ModManager.IsOriginsCopy);

        Assert.True(plan.IsEmpty);
        Assert.Null(plan.JvmArgument);
    }
}
