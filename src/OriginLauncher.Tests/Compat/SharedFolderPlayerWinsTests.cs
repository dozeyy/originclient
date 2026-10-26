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
        // A folder this launcher has seen before (its record started): nothing already here is ever adopted. The
        // first-run adoption of older launchers' unpinned jars has its own test, which starts without a record.
        OriginInstallRecord.AdoptLegacy(_mods);
    }

    public void Dispose()
    {
        try { Directory.Delete(Path.GetDirectoryName(_mods)!, recursive: true); } catch (IOException) { }
        ModManager.ClearOwnershipCache();
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

        var gone = SharedFolderCleanup.PurgeStandalonePerf(_mods, ModManager.OwnerOf);

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
            new HashSet<string>(StringComparer.OrdinalIgnoreCase) { Path.GetFileName(pin) }, ModManager.OwnerOf);

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
            Assert.Empty(SharedFolderCleanup.PurgeStandalonePerf(_mods, ModManager.OwnerOf));
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

    // ---- review fixes (2026-10-26) ----

    [Fact]
    public void A_jar_known_only_by_a_pin_match_is_set_aside_with_its_bytes_never_deleted()
    {
        // A player's own Modrinth download of a version Origin once pinned has exactly the pinned bytes: a pin match
        // alone is never proof enough to delete. The cleanup renames it aside (".jar.disabled") instead.
        var bytes = Kit.FabricJar("sodium", "0.6.5+mc1.21.1");
        var maybeTheirs = Put("sodium-fabric-0.6.5+mc1.21.1.jar", bytes);
        var aside = Put("sodium-fabric-0.6.5+mc1.21.1.jar.disabled", Kit.FabricJar("sodium", "0.6.4+mc1.21.1")); // name taken

        var gone = SharedFolderCleanup.PurgeStandalonePerf(_mods, p => p == maybeTheirs ? JarOwner.OriginByPin : JarOwner.Player);

        Assert.Single(gone);
        Assert.False(File.Exists(maybeTheirs));
        var setAside = Path.Combine(_mods, "sodium-fabric-0.6.5+mc1.21.1 (2).jar.disabled");
        Assert.Equal(bytes, File.ReadAllBytes(setAside));                      // every byte kept
        Assert.Equal(Kit.FabricJar("sodium", "0.6.4+mc1.21.1"), File.ReadAllBytes(aside)); // the file already there untouched
    }

    [Fact]
    public void A_set_aside_jar_the_player_turns_back_on_is_theirs_from_then_on()
    {
        var api = Origins("fabric-api-0.116.6+1.21.1.jar", Kit.FabricJar("fabric-api", "0.116.6+1.21.1"));
        var old = Put("fabric-api-0.100.0+1.21.1.jar", Kit.FabricJar("fabric-api", "0.100.0+1.21.1"));
        var sha = Convert.ToHexString(System.Security.Cryptography.SHA512.HashData(File.ReadAllBytes(old)));
        File.AppendAllText(Path.Combine(_mods, OriginInstallRecord.FileName), "adopted " + sha.ToLowerInvariant() + " x.jar\n");
        ModManager.ClearOwnershipCache();
        Assert.Equal(JarOwner.OriginByPin, ModManager.OwnerOf(old));

        var pins = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { Path.GetFileName(api) };
        Assert.Single(SharedFolderCleanup.DedupeOriginFamilies(_mods, pins, ModManager.OwnerOf)); // set aside, not deleted
        Assert.True(File.Exists(old + ".disabled"));

        Assert.True(ModManager.SetEnabledIn(_mods, Path.GetFileName(old), enabled: true).Ok);
        Assert.Equal(JarOwner.Player, ModManager.OwnerOf(old));
        Assert.Empty(SharedFolderCleanup.DedupeOriginFamilies(_mods, pins, ModManager.OwnerOf));
        Assert.True(File.Exists(old));
    }

    [Fact]
    public void Updating_from_a_launcher_that_installed_fabric_api_unpinned_still_launches_with_origins_fabric_api()
    {
        // Review finding 1: launchers up to v1.0.65 installed Fabric API (and Voice Chat) as "newest release", no pin.
        // Its bytes are in no pin and there was no record yet: without adoption it turned into "the player's", the
        // installer still skipped (by name) and a profile / Origin-only launch had no Fabric API at all.
        File.Delete(Path.Combine(_mods, OriginInstallRecord.FileName)); // a folder from before the record existed
        var oldApi = Put("fabric-api-0.110.0+1.21.1.jar", Kit.FabricJar("fabric-api", "0.110.0+1.21.1"));
        var oldVc = Put("voicechat-fabric-1.21.1-2.5.20.jar", Kit.FabricJar("voicechat", "1.21.1-2.5.20"));
        var mine = PlayersJars(); // the player's own Sodium etc. are NOT adopted (only the two unpinned families)
        mine.Remove("fabric-api-0.116.99+1.21.1.jar");
        File.Delete(Path.Combine(_mods, "fabric-api-0.116.99+1.21.1.jar"));
        Put("originclient.jar", Kit.OriginClientJar());

        var adopted = OriginInstallRecord.AdoptLegacy(_mods);

        Assert.Equal(new[] { Path.GetFileName(oldVc), Path.GetFileName(oldApi) }.OrderBy(n => n), adopted.OrderBy(n => n));
        Assert.Equal(JarOwner.OriginByPin, ModManager.OwnerOf(oldApi));            // Origin's, but never deleted
        Assert.True(ModManager.HasOriginsCopy(_mods, "fabric-api-"));             // the installer's skip is right
        foreach (var name in mine.Keys) Assert.Equal(JarOwner.Player, ModManager.OwnerOf(Path.Combine(_mods, name)));
        var profileSet = LaunchModFolder.LaunchSet(Directory.EnumerateFiles(_mods), Enumerable.Empty<string>(),
            ModManager.IsOriginProvisioned, ModManager.IsOriginsCopy);
        Assert.Contains(Path.GetFileName(oldApi), profileSet.Keys);              // the profile launch has its Fabric API
        Assert.Empty(OriginInstallRecord.AdoptLegacy(_mods));                     // once per folder
    }

    [Fact]
    public void A_players_own_fabric_api_does_not_stop_origin_installing_its_own()
    {
        Put("fabric-api-0.116.99+1.21.1.jar", Kit.FabricJar("fabric-api", "0.116.99+1.21.1"));
        Assert.False(ModManager.HasOriginsCopy(_mods, "fabric-api-"));
    }

    [Fact]
    public void A_shared_launch_with_no_decision_loads_the_shared_folder_and_clears_old_launch_folders()
    {
        var game = Path.Combine(Path.GetDirectoryName(_mods)!, "account");
        Directory.CreateDirectory(Path.Combine(game, "mods-shared-old"));
        Directory.CreateDirectory(Path.Combine(game, "mods-sharedx")); // not ours: kept
        Put("originclient.jar", Kit.OriginClientJar());

        var folder = LaunchModFolder.PrepareSharedLaunch(game, _mods, PlayerOverridePlan.Empty, null, null, _ => true);

        Assert.Equal(_mods, folder);
        Assert.False(Directory.Exists(Path.Combine(game, "mods-shared-old")));
        Assert.True(Directory.Exists(Path.Combine(game, "mods-sharedx")));
    }

    [Fact]
    public void A_shared_launch_that_withholds_one_of_origins_jars_links_every_other_jar_and_leaves_the_shared_folder_alone()
    {
        var game = Path.Combine(Path.GetDirectoryName(_mods)!, "account");
        Put("originclient.jar", Kit.OriginClientJar());
        var originSodium = Origins("sodium-fabric-mc1.20.1-0.5.11.jar", Kit.FabricJar("sodium", "0.5.11+mc1.20.1"));
        var theirs = Put("sodium-fabric-0.5.13+mc1.20.1.jar", Kit.FabricJar("sodium", "0.5.13+mc1.20.1"));
        var other = Put("appleskin-fabric-3.0.5.jar", Kit.FabricJar("appleskin", "3.0.5"));
        var before = Directory.GetFiles(_mods).ToDictionary(f => f, File.ReadAllBytes);
        var plan = new PlayerOverridePlan
        {
            WithheldPaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { Path.GetFullPath(originSodium) },
        };

        var folder = LaunchModFolder.PrepareSharedLaunch(game, _mods, plan, null, null, _ => true);

        Assert.Equal(Path.Combine(game, LaunchModFolder.SharedFolderName), folder);
        var names = Directory.GetFiles(folder).Select(Path.GetFileName).OrderBy(n => n).ToList();
        Assert.Equal(new[] { "appleskin-fabric-3.0.5.jar", "originclient.jar", Path.GetFileName(theirs) }.OrderBy(n => n), names);
        foreach (var (path, bytes) in before) Assert.Equal(bytes, File.ReadAllBytes(path)); // shared folder untouched
        if (OperatingSystem.IsWindows())
        {
            // A hard link, not a copy: the same file under a second name (no copying ~80 jars on Play, no extra disk).
            File.AppendAllText(other, "x");
            Assert.Equal(File.ReadAllBytes(other), File.ReadAllBytes(Path.Combine(folder, Path.GetFileName(other))));
        }

        // The decision goes away: the next launch loads the shared folder and the old launch folder is cleared.
        Assert.Equal(_mods, LaunchModFolder.PrepareSharedLaunch(game, _mods, PlayerOverridePlan.Empty, null, null, _ => true));
        Assert.False(Directory.Exists(folder));
        Assert.True(File.Exists(other));
    }

    [Fact]
    public void A_shared_launch_folder_that_cannot_be_built_falls_back_to_the_shared_folder()
    {
        var notADir = Path.Combine(Path.GetDirectoryName(_mods)!, "account-file");
        File.WriteAllText(notADir, "a file where the account folder should be");
        var jar = Origins("sodium-fabric-mc1.20.1-0.5.11.jar", Kit.FabricJar("sodium", "0.5.11+mc1.20.1"));
        var plan = new PlayerOverridePlan
        {
            WithheldPaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { Path.GetFullPath(jar) },
        };
        var logged = new List<string>();

        var folder = LaunchModFolder.PrepareSharedLaunch(notADir, _mods, plan, null, null, _ => true, logged.Add);

        Assert.Equal(_mods, folder);
        Assert.Single(logged);
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
