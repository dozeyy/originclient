using System.IO;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Compat;

/// <summary>A mod Origin already ships for a target version. Version null = not known (it then satisfies any requirement).</summary>
public sealed record BuiltInMod(string Id, string? Version, string DisplayName)
{
    /// <summary>"Origin includes Sodium 0.6.13" (the +build part of a version is left out).</summary>
    public string Describe()
    {
        var shown = Version == null ? null : Version.Split('+')[0];
        return shown == null ? $"Origin includes {DisplayName}" : $"Origin includes {DisplayName} {shown}";
    }
}

/// <summary>One entry of the standalone perf catalog for a version: which family and the version (or file name) it is pinned to.</summary>
public sealed record CatalogMod(string Family, string? VersionOrFileName);

// What Origin already includes for ONE target version — built at run time, never
// a hand-kept per-version list (it would rot the moment a bundled jar is bumped):
//  * the jars nested inside the bundled originclient-<jar>.jar (1.21.1 carries
//    Sodium, Iris, Lithium, ... jar-in-jar), read for their real ids + versions;
//  * the standalone perf catalog's families for the version, mapped to mod ids
//    (Sodium, Indium, Lithium, FerriteCore, Krypton, Iris, ImmediatelyFast,
//    ModernFix, Mod Menu, EntityCulling) — the other versions install these beside the client;
//  * Fabric API (id "fabric" below 1.18, "fabric-api" from 1.18);
//  * Simple Voice Chat ("voicechat"), which Origin installs on every Fabric version.
// When the bundled jar can't be read the list is catalog-only and flagged
// incomplete, so the plan says so instead of silently copying a duplicate.
// Pure: the jar arrives as a stream, the catalog as data (the glue maps
// PerformanceModCatalog into CatalogMods).
public sealed class BuiltInModSet
{
    private static readonly Dictionary<string, (string Id, string Display)> Families = new(StringComparer.OrdinalIgnoreCase)
    {
        ["sodium"] = ("sodium", "Sodium"),
        ["indium"] = ("indium", "Indium"),
        ["lithium"] = ("lithium", "Lithium"),
        ["ferritecore"] = ("ferritecore", "FerriteCore"),
        ["krypton"] = ("krypton", "Krypton"),
        ["iris"] = ("iris", "Iris"),
        ["immediatelyfast"] = ("immediatelyfast", "ImmediatelyFast"),
        ["modernfix"] = ("modernfix", "ModernFix"),
        ["modmenu"] = ("modmenu", "Mod Menu"),
        ["entityculling"] = ("entityculling", "EntityCulling"),
    };

    // Fabric API's modules are separate mod ids a mod may depend on (fabric-resource-loader-v0,
    // fabric-api-base ...). Two third-party mods share the shape and are NOT part of it.
    private static readonly Regex FabricApiModule = new(@"^fabric-[a-z0-9-]+-v\d+$|^fabric-api-base$", RegexOptions.CultureInvariant);
    private static readonly HashSet<string> NotFabricApi = new(StringComparer.Ordinal)
    {
        "fabric-permissions-api-v0", "fabric-language-kotlin",
    };

    private static readonly Regex CatalogVersionPattern = new(@"\d+(\.\d+)+", RegexOptions.CultureInvariant);
    private static readonly Regex McTag = new(@"mc\d+(\.\d+)*[-+]?", RegexOptions.CultureInvariant | RegexOptions.IgnoreCase);

    private readonly Dictionary<string, BuiltInMod> _byId = new(StringComparer.Ordinal);

    public IReadOnlyCollection<BuiltInMod> Mods => _byId.Values;

    /// <summary>The bundled jar could not be read: only the catalog + Fabric API + voice chat are known.</summary>
    public bool Incomplete { get; private set; }

    public static BuiltInModSet Build(string targetVersion, Stream? bundledOriginJar, IEnumerable<CatalogMod> catalog)
    {
        var set = new BuiltInModSet();

        if (bundledOriginJar != null)
        {
            var info = ModMetadataReader.Read(bundledOriginJar, "originclient.jar");
            if (info.ReadFailed || info.Id == null)
            {
                set.Incomplete = true;
            }
            else
            {
                set.Add(new BuiltInMod(info.Id, info.Version, info.Name ?? "Origin Client"));
                foreach (var nested in info.Nested)
                    set.Add(new BuiltInMod(nested.Id, nested.Version, nested.Name ?? nested.Id));
            }
        }
        else
        {
            set.Incomplete = true;
        }

        foreach (var entry in catalog)
        {
            if (!Families.TryGetValue(entry.Family, out var family)) continue;
            set.Add(new BuiltInMod(family.Id, CatalogVersion(entry.VersionOrFileName), family.Display));
        }

        // Fabric API provides "fabric" too; below 1.18 "fabric" is its only id.
        set.Add(new BuiltInMod("fabric", null, "Fabric API"));
        if (McVersion.AtLeast(targetVersion, "1.18")) set.Add(new BuiltInMod("fabric-api", null, "Fabric API"));
        set.Add(new BuiltInMod("voicechat", null, "Simple Voice Chat"));
        return set;
    }

    /// <summary>Adds a mod; the first entry for an id wins (nested jars carry the real version, the catalog only fills gaps).</summary>
    private void Add(BuiltInMod mod) => _byId.TryAdd(mod.Id, mod);

    public BuiltInMod? Find(string id)
    {
        if (_byId.TryGetValue(id, out var mod)) return mod;
        // Any Fabric API module id counts as Fabric API itself.
        if (!NotFabricApi.Contains(id) && FabricApiModule.IsMatch(id) && _byId.ContainsKey("fabric") && _byId.TryGetValue("fabric", out var api))
            return api with { Id = id };
        return null;
    }

    /// <summary>"sodium-fabric-0.8.12+mc1.21.11.jar" -> "0.8.12+mc1.21.11"; a bare version passes through; null when none is found.</summary>
    public static string? CatalogVersion(string? versionOrFileName)
    {
        if (string.IsNullOrWhiteSpace(versionOrFileName)) return null;
        var cleaned = McTag.Replace(versionOrFileName, "");
        var match = CatalogVersionPattern.Match(cleaned);
        return match.Success ? match.Value : null;
    }
}
