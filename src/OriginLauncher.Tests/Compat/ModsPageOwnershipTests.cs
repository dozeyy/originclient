using OriginLauncher.App.Core.Mods;
using OriginLauncher.Tests.Import;
using Xunit;

namespace OriginLauncher.Tests.Compat;

// The Mods page before the first Play of a launcher session: Origin's own Fabric API / Sodium / Mod Menu must show as
// built-in (no switch, no Remove) without a launch having hashed them first, the player's own copy of the same mod
// stays theirs ("a player's own mods win"), and an Origin jar that was turned off before this fix is put back on by
// the installers instead of staying off for good. Temp folders only.
public sealed class ModsPageOwnershipTests : IDisposable
{
    private readonly string _mods = Path.Combine(Path.GetTempPath(), "origin-mods-page-" + Guid.NewGuid().ToString("N"), "mods");

    public ModsPageOwnershipTests()
    {
        Directory.CreateDirectory(_mods);
        ModManager.ClearOwnershipCache();
        OriginInstallRecord.AdoptLegacy(_mods); // a folder this launcher has seen before: nothing already here is adopted
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

    private string Origins(string name, byte[] bytes)
    {
        var path = Put(name, bytes);
        OriginInstallRecord.AddFile(path);
        return path;
    }

    private ModEntry Row(IReadOnlyList<ModEntry> rows, string fileName) => rows.Single(r => r.FileName == fileName);

    [Fact]
    public void Origins_jars_are_built_in_on_a_fresh_launcher_start_and_the_players_own_stay_theirs()
    {
        Origins("fabric-api-0.116.6+1.21.1.jar", Kit.FabricJar("fabric-api", "0.116.6+1.21.1"));
        Origins("modmenu-11.0.3.jar", Kit.FabricJar("modmenu", "11.0.3"));
        Put("sodium-fabric-0.8.2+mc1.21.1.jar", Kit.FabricJar("sodium", "0.8.2+mc1.21.1"));     // the player's own Sodium
        Put("lithium-fabric-addon.jar", Kit.FabricJar("lithium-addon", "1.0.0"));              // named like Origin's, another mod
        Put("cool-mod-1.0.jar", Kit.FabricJar("coolmod", "1.0"));
        ModManager.ClearOwnershipCache(); // a new launcher session: nothing remembered, nothing launched yet

        // The UI-thread read: no hashing. Nothing Origin-shaped gets a switch on a guess.
        var first = ModManager.EnumerateIn(_mods, isProfile: false);
        Assert.True(Row(first, "fabric-api-0.116.6+1.21.1.jar") is { IsManaged: true, Checking: true });
        Assert.True(Row(first, "modmenu-11.0.3.jar") is { IsManaged: true, Checking: true });
        Assert.True(Row(first, "sodium-fabric-0.8.2+mc1.21.1.jar") is { IsManaged: true, Checking: true });
        Assert.True(Row(first, "lithium-fabric-addon.jar") is { IsManaged: false, Checking: false }); // another mod id: theirs at once
        Assert.True(Row(first, "cool-mod-1.0.jar") is { IsManaged: false, Checking: false });

        // The page's background check (Task.Run) decides the three by their bytes.
        Assert.Equal(3, ModManager.WarmOwnership(_mods));
        var rows = ModManager.EnumerateIn(_mods, isProfile: false);
        Assert.DoesNotContain(rows, r => r.Checking);
        Assert.True(Row(rows, "fabric-api-0.116.6+1.21.1.jar").IsManaged);
        Assert.True(Row(rows, "modmenu-11.0.3.jar").IsManaged);
        Assert.False(Row(rows, "sodium-fabric-0.8.2+mc1.21.1.jar").IsManaged); // the player's own Sodium: switch + Remove
        Assert.Equal(0, ModManager.WarmOwnership(_mods));                      // nothing new: the page doesn't rebuild
    }

    [Fact]
    public void A_turned_off_jar_is_never_built_in_and_a_profile_folder_holds_only_the_players_mods()
    {
        var api = Origins("fabric-api-0.116.6+1.21.1.jar", Kit.FabricJar("fabric-api", "0.116.6+1.21.1"));
        File.Move(api, api + ".disabled");
        Origins("modmenu-11.0.3.jar", Kit.FabricJar("modmenu", "11.0.3"));
        ModManager.WarmOwnership(_mods);

        var rows = ModManager.EnumerateIn(_mods, isProfile: false);
        Assert.True(Row(rows, "fabric-api-0.116.6+1.21.1.jar") is { Enabled: false, IsManaged: false, Checking: false });
        Assert.True(Row(rows, "modmenu-11.0.3.jar").IsManaged);

        var profile = ModManager.EnumerateIn(_mods, isProfile: true);
        Assert.All(profile, r => Assert.False(r.IsManaged || r.Checking));
    }

    [Fact]
    public void Origins_own_jar_left_turned_off_comes_back_on_and_the_players_stay_off()
    {
        // Origin's pinned Fabric API, turned off on the old Mods page.
        var api = Origins("fabric-api-0.116.6+1.21.1.jar", Kit.FabricJar("fabric-api", "0.116.6+1.21.1"));
        File.Move(api, api + ".disabled");
        // The installer's skip test no longer counts it as a working copy ...
        Assert.False(ModManager.HasOriginsCopy(_mods, "fabric-api-"));
        // ... and it is turned back on by a rename, bytes untouched.
        Assert.True(ModManager.TryRestoreOriginsJar(_mods, "fabric-api-0.116.6+1.21.1.jar", _ => true));
        Assert.True(File.Exists(api));
        Assert.False(File.Exists(api + ".disabled"));
        Assert.True(ModManager.HasOriginsCopy(_mods, "fabric-api-"));

        // The player's own jar of the pinned name, turned off: theirs, stays off.
        var mine = Put("sodium-fabric-0.6.13+mc1.21.1.jar.disabled", Kit.FabricJar("sodium", "0.6.13+mc1.21.1"));
        Assert.False(ModManager.TryRestoreOriginsJar(_mods, "sodium-fabric-0.6.13+mc1.21.1.jar", _ => true));
        Assert.True(File.Exists(mine));

        // Bytes that are not the pin: stays off.
        var stale = Origins("lithium-fabric-0.15.0+mc1.21.1.jar", Kit.FabricJar("lithium", "0.15.0+mc1.21.1"));
        File.Move(stale, stale + ".disabled");
        Assert.False(ModManager.TryRestoreOriginsJar(_mods, "lithium-fabric-0.15.0+mc1.21.1.jar", _ => false));
        Assert.True(File.Exists(stale + ".disabled"));

        // Simple Voice Chat is the player's to switch off: Origin's turned-off copy still counts and is never turned on.
        var vc = Origins("voicechat-fabric-1.21.1-2.5.30.jar", Kit.FabricJar("voicechat", "1.21.1-2.5.30"));
        File.Move(vc, vc + ".disabled");
        Assert.True(ModManager.HasOriginsCopy(_mods, "voicechat-"));
        Assert.False(ModManager.TryRestoreOriginsJar(_mods, "voicechat-fabric-1.21.1-2.5.30.jar", _ => true));
        Assert.True(File.Exists(vc + ".disabled"));
    }
}
