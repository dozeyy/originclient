using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Mods;
using Xunit;

namespace OriginLauncher.Tests;

// The standalone perf-mod catalog (PerformanceModCatalog.Data.cs) is hand-maintained data that decides
// which jars land in a player's mods folder. A bad row fails at someone's launch ("the game never
// starts"), so the data is held consistent here: every pin hashed, no two jars of one mod in a
// version, nothing the Mods page can't classify, and the speed set present on every live Fabric
// version that has a stable build. Pure: reads the compiled-in catalog, never the network.
public class PerformanceModCatalogTests
{
    // Every live Fabric version (CLAUDE.md "Supported versions"; 1.8.9 is Forge + OptiFine, see
    // LegacyStackInstaller). tools/release/check_consistency.py keeps the picker and the builds in
    // step; this list keeps the catalog in step with the picker. Add a version here when it ships.
    private static readonly string[] LiveFabricVersions =
    {
        "1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.3", "1.19.4", "1.20", "1.20.1", "1.20.2", "1.20.4",
        "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.6", "1.21.7", "1.21.8", "1.21.10", "1.21.11",
        "26.1", "26.1.1", "26.1.2", "26.2", "26.3",
    };

    // All catalog keys (live plus the kept-but-pulled ones such as 1.21.5), read off the Data table.
    private static IEnumerable<VersionPerfProfile> AllProfiles()
    {
        foreach (var v in LiveFabricVersions.Concat(new[] { "1.19", "1.19.1", "1.20.5", "1.20.6", "1.21.5", "1.21.9" }))
            if (PerformanceModCatalog.TryGet(v) is { } p) yield return p;
    }

    private static IEnumerable<(string Version, PerfMod Mod)> AllPins() =>
        AllProfiles().SelectMany(p => p.Mods().Select(m => (p.McVersion, m)));

    [Fact]
    public void Every_live_fabric_version_has_a_full_shader_stack()
    {
        foreach (var v in LiveFabricVersions)
        {
            Assert.True(PerformanceModCatalog.HasShaderStack(v), $"{v}: no Sodium + Iris pair in the catalog");
            Assert.Equal(PerfStackTier.Full, PerformanceModCatalog.TryGet(v)!.Tier);
        }
    }

    [Fact]
    public void Every_pin_has_a_sha512_a_modrinth_cdn_url_and_a_matching_file_name()
    {
        var hex = new Regex("^[0-9a-f]{128}$", RegexOptions.CultureInvariant);
        var pins = AllPins().ToList();
        Assert.True(pins.Count > 150, "the catalog looks empty");
        foreach (var (version, mod) in pins)
        {
            var who = $"{version}: {mod.FileName}";
            Assert.True(mod.Sha512 != null && hex.IsMatch(mod.Sha512), $"{who}: missing or malformed SHA-512");
            Assert.StartsWith("https://cdn.modrinth.com/data/", mod.Url, StringComparison.Ordinal);
            Assert.EndsWith(".jar", mod.FileName, StringComparison.Ordinal);
            Assert.DoesNotContain('/', mod.FileName);
            Assert.DoesNotContain('\\', mod.FileName);
            // The CDN URL's last segment IS the file Modrinth serves; it must be the file we save.
            Assert.Equal(mod.FileName, Uri.UnescapeDataString(mod.Url[(mod.Url.LastIndexOf('/') + 1)..]));
        }
    }

    [Fact]
    public void A_version_never_installs_two_jars_of_the_same_mod()
    {
        // Fabric refuses to boot on a duplicate mod id. Same filename, or two files of one managed family.
        foreach (var p in AllProfiles())
        {
            var mods = p.Mods().ToList();
            Assert.Equal(mods.Count, mods.Select(m => m.FileName.ToLowerInvariant()).Distinct().Count());
            var families = mods.Select(m => ModManager.ModFamilyKey(m.FileName)).ToList();
            Assert.True(families.Count == families.Distinct(StringComparer.OrdinalIgnoreCase).Count(),
                $"{p.McVersion}: two jars share a mod family ({string.Join(", ", mods.Select(m => m.FileName))})");
        }
    }

    [Fact]
    public void Every_catalog_jar_is_managed_so_the_mods_page_locks_it_and_launch_dedupes_it()
    {
        foreach (var (version, mod) in AllPins())
        {
            Assert.True(ModManager.IsManaged(mod.FileName), $"{version}: {mod.FileName} is not classified as managed");
            // Family key must be a real prefix, not the file's own name (= "unmanaged singleton").
            Assert.NotEqual(mod.FileName.ToLowerInvariant(), ModManager.ModFamilyKey(mod.FileName));
        }
    }

    [Fact]
    public void Indium_only_rides_the_sodium_eras_that_need_it()
    {
        // Sodium 0.6.x on 1.21.2+ and 0.7+/0.8/0.9 ship their own Fabric Rendering API implementation;
        // a pinned Indium next to them is the double-install the per-version notes warn about.
        foreach (var p in AllProfiles().Where(p => p.Indium != null))
        {
            var minor = SodiumMinor(p.Sodium!.FileName);
            Assert.True(minor is >= 0 and <= 6 && !IsAtLeast1212(p.McVersion),
                $"{p.McVersion}: Indium pinned beside Sodium {p.Sodium.FileName}");
        }
    }

    [Fact]
    public void EntityCulling_is_a_single_optional_slot_never_on_a_bundled_or_buildless_version()
    {
        // 1.21.1 carries its perf stack jar-in-jar (no catalog install; EntityCulling was pulled from that
        // build), 1.21.2 has no EntityCulling build at all, and the 2022-23 builds are held back.
        foreach (var v in new[] { "1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.20", "1.21.1", "1.21.2" })
            Assert.Null(PerformanceModCatalog.TryGet(v)!.EntityCulling);

        // Everywhere it IS pinned it is the Fabric build named for that jar family and carries a hash.
        foreach (var p in AllProfiles().Where(p => p.EntityCulling != null))
        {
            Assert.StartsWith("entityculling-fabric-", p.EntityCulling!.FileName, StringComparison.Ordinal);
            Assert.True(p.Mods().Contains(p.EntityCulling), $"{p.McVersion}: EntityCulling missing from Mods()");
        }
        Assert.Equal(18, AllProfiles().Count(p => p.EntityCulling != null));
    }

    [Fact]
    public void Ferritecore_covers_every_live_version_that_has_a_build()
    {
        // No FerriteCore build lists 1.21.2 at all; every other live version has one.
        foreach (var v in LiveFabricVersions.Where(v => v != "1.21.2"))
            Assert.NotNull(PerformanceModCatalog.TryGet(v)!.Ferrite);
        Assert.Null(PerformanceModCatalog.TryGet("1.21.2")!.Ferrite);
    }

    [Fact]
    public void Core_speed_mods_are_present_on_every_live_version()
    {
        foreach (var v in LiveFabricVersions)
        {
            var p = PerformanceModCatalog.TryGet(v)!;
            Assert.NotNull(p.Sodium);
            Assert.NotNull(p.Iris);
            Assert.NotNull(p.Lithium);
            Assert.NotNull(p.Krypton);
        }
    }

    // ---- the hash check itself ----------------------------------------------------------------

    // SHA-512("abc"), the FIPS 180 test vector.
    private const string AbcSha512 =
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";

    [Fact]
    public void Matches_accepts_the_right_bytes_and_rejects_anything_else()
    {
        var path = Path.Combine(Directory.CreateTempSubdirectory("perfmod").FullName, "x.jar");
        File.WriteAllText(path, "abc");
        Assert.True(new PerfMod("v", "u", "x.jar", AbcSha512).Matches(path));
        Assert.True(new PerfMod("v", "u", "x.jar", AbcSha512.ToUpperInvariant()).Matches(path));
        Assert.False(new PerfMod("v", "u", "x.jar", new string('0', 128)).Matches(path));
        File.WriteAllText(path, "abd"); // a corrupted download
        Assert.False(new PerfMod("v", "u", "x.jar", AbcSha512).Matches(path));
        Assert.True(new PerfMod("v", "u", "x.jar").Matches(path)); // no pin = nothing to check
    }

    // ---- Mods page classification of the families this audit touched ---------------------------

    [Fact]
    public void EntityCulling_and_both_immediatelyfast_name_shapes_are_managed_single_families()
    {
        Assert.True(ModManager.IsManaged("entityculling-fabric-1.11.3-mc1.21.4.jar"));
        Assert.True(ModManager.IsManaged("entityculling-fabric-mc1.16.5-1.5.2.jar"));
        Assert.Equal(ModManager.ModFamilyKey("entityculling-fabric-1.11.3-mc1.21.4.jar"),
                     ModManager.ModFamilyKey("entityculling-fabric-1.8.2-mc1.21.jar"));

        // The 1.18.2 / 1.19.3 builds are "ImmediatelyFast-1.1.12+..." (no "Fabric"): they used to fall
        // outside the managed set, so the Mods page offered them as a normal toggle and nothing
        // de-duplicated them.
        Assert.True(ModManager.IsManaged("ImmediatelyFast-1.1.12+1.19.3.jar"));
        Assert.True(ModManager.IsManaged("ImmediatelyFast-Fabric-1.4.0+1.19.2.jar"));
        Assert.Equal(ModManager.ModFamilyKey("ImmediatelyFast-1.1.12+1.19.3.jar"),
                     ModManager.ModFamilyKey("ImmediatelyFast-Fabric-1.4.0+1.19.2.jar"));
    }

    [Fact]
    public void EntityCulling_is_not_part_of_the_bundled_purge_set()
    {
        // IsBundledPerfJar is what the 1.21.1 launch path DELETES; nothing bundled there collides with
        // EntityCulling, so a player's own copy must survive. (Still managed: one copy per family.)
        Assert.False(ModManager.IsBundledPerfJar("entityculling-fabric-1.11.3-mc1.21.1.jar"));
        Assert.True(ModManager.IsCatalogOnlyPerfJar("entityculling-fabric-1.11.3-mc1.21.1.jar"));
        // User addons stay user-owned.
        Assert.False(ModManager.IsManaged("sodium-extra-fabric-0.6.0+mc1.21.1.jar"));
        Assert.False(ModManager.IsManaged("moreculling-fabric-1.21.1-1.2.0.jar"));
    }

    // "mc1.21.3-0.6.8-fabric" / "mc1.20-0.4.10" / "sodium-fabric-0.9.2+mc26.3.jar": the 0.X of Sodium 0.X.Y.
    private static int SodiumMinor(string fileName)
    {
        var m = Regex.Match(fileName, @"(?<![\d.])0\.(\d+)\.\d+");
        return m.Success ? int.Parse(m.Groups[1].Value) : -1;
    }

    // True for 1.21.2 and everything after it (Sodium there provides its own Fabric Rendering API).
    private static bool IsAtLeast1212(string mc)
    {
        if (mc.StartsWith("26.")) return true;
        var parts = mc.Split('.');
        return parts.Length >= 3 && parts[0] == "1" && parts[1] == "21" && int.TryParse(parts[2], out var patch) && patch >= 2;
    }
}
