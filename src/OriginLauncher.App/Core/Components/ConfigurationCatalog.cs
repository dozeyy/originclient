using OriginLauncher.App.Core.Loaders;

namespace OriginLauncher.App.Core.Components;

/// <summary>One file an alternate configuration loads instead of the shipped copy of its family.</summary>
/// <param name="Family">"sodium" or "iris": the store folder, and which shipped jar it replaces.</param>
/// <param name="ModId">The mod id the file declares (what the compatibility check sees before the file is fetched).</param>
/// <param name="ModVersion">Its version as the file name states it ("0.6.5+mc1.21.1").</param>
/// <param name="Pin">The exact Modrinth file and its SHA-512 (ManagedPins.Alternate).</param>
public sealed record ComponentFile(string Family, string ModId, string ModVersion, PerfMod Pin);

/// <summary>
/// One set of Origin's components a profile may launch with. The default is what Origin ships for that version;
/// an alternate swaps the Sodium + Iris pair (they move together, docs/overhaul/spike-sodium-iris.md) for an older
/// pair that the spike booted with Origin's mixins applied.
/// </summary>
/// <param name="Id">"default", or "sodium-iris-&lt;pairId&gt;". Safe as part of a folder name.</param>
/// <param name="Rank">Higher = newer. Play prefers the highest rank that starts.</param>
/// <param name="PairId">The pin value for the "sodium-iris" family ("0.6.5+1.8.1"); null for the default.</param>
/// <param name="Files">What it loads instead of the shipped pair; empty for the default.</param>
/// <param name="Evidence">Why it is on the list (the spike run it passed), for the log.</param>
public sealed record VettedConfiguration(
    string Id, string McVersion, int Rank, string? PairId, IReadOnlyList<ComponentFile> Files, string Evidence)
{
    public bool IsDefault => Files.Count == 0;
}

/// <summary>
/// Which configurations each version may launch with. ONLY pairs proven in CI are listed (boot-smoke input
/// `pairs`, docs/overhaul/configuration-matrix.md); every other version has its shipped set and nothing else. Never
/// add a pair here without a green boot-smoke row for it: zero mixin-apply failures and that exact pair loaded.
/// </summary>
public static class ConfigurationCatalog
{
    public const string DefaultId = "default";

    /// <summary>The pin family of the Sodium + Iris pair.</summary>
    public const string PairFamily = "sodium-iris";

    /// <summary>File-name prefixes of the shipped jars an alternate replaces in the launch folder.</summary>
    public static readonly IReadOnlyList<string> ReplacedPrefixes = new[] { "sodium-fabric-", "iris-fabric-" };

    /// <summary>Mod ids an alternate replaces (also inside originclient-1.21.1.jar, which nests the pair).</summary>
    public static readonly IReadOnlyCollection<string> ReplacedIds = new[] { "sodium", "iris" };

    // Newest pair first; the default (rank above all of them) is added by For().
    private static readonly IReadOnlyDictionary<string, (string Pair, string Evidence)[]> Alternates =
        new Dictionary<string, (string, string)[]>(StringComparer.Ordinal)
        {
            ["1.21.1"] = new[]
            {
                // Re-proven 2026-10-25 against the jar that nests Sodium 0.8.13 + Iris 1.8.14-beta.1 (boot-smoke
                // pairs row on claude/deps-all); the fallback when the shipped 0.8 pair won't start.
                ("0.6.5+1.8.1", "CI boot-smoke on claude/deps-all 2026-10-25 over the Sodium 0.8.13 jar, first 40252040490 (prod layout, 0 mixin-apply failures, pair loaded over the nested one); spike dev 13/13, signatures 46/46; Smart Terrain off"),
            },
            ["1.21.11"] = new[]
            {
                ("0.8.4+1.10.5", "CI boot-smoke 40252040490 (prod layout, 0 mixin-apply failures, pair loaded); spike dev 10/10, signatures 32/32; Smart Terrain off"),
                ("0.8.0+1.10.0", "CI boot-smoke 40252040490 (prod layout, 0 mixin-apply failures, pair loaded); spike dev 10/10, signatures 32/32; Smart Terrain off"),
            },
        };

    private static readonly Dictionary<string, IReadOnlyList<VettedConfiguration>> Cache = new(StringComparer.Ordinal);

    /// <summary>True when <paramref name="mcVersion"/> has at least one vetted alternate.</summary>
    public static bool HasAlternates(string? mcVersion) => mcVersion != null && For(mcVersion).Count > 1;

    /// <summary>The vetted configurations of a version, newest (highest rank) first; always starts with the default.</summary>
    public static IReadOnlyList<VettedConfiguration> For(string mcVersion)
    {
        lock (Cache)
        {
            if (Cache.TryGetValue(mcVersion, out var hit)) return hit;
            var list = new List<VettedConfiguration>();
            var pairs = Alternates.GetValueOrDefault(mcVersion) ?? Array.Empty<(string, string)>();
            list.Add(new VettedConfiguration(DefaultId, mcVersion, pairs.Length + 1, null, Array.Empty<ComponentFile>(),
                "shipped set"));
            for (var i = 0; i < pairs.Length; i++)
            {
                var (pair, evidence) = pairs[i];
                var sodium = ManagedPins.Alternate(mcVersion, "sodium", pair);
                var iris = ManagedPins.Alternate(mcVersion, "iris", pair);
                if (sodium == null || iris == null) continue; // a pair without both pins is not offered
                list.Add(new VettedConfiguration(PairFamily + "-" + pair, mcVersion, pairs.Length - i, pair, new[]
                {
                    new ComponentFile("sodium", "sodium", VersionOf(sodium.FileName, "sodium-fabric-"), sodium),
                    new ComponentFile("iris", "iris", VersionOf(iris.FileName, "iris-fabric-"), iris),
                }, evidence));
            }
            return Cache[mcVersion] = list;
        }
    }

    // "sodium-fabric-0.6.5+mc1.21.1.jar" -> "0.6.5+mc1.21.1".
    private static string VersionOf(string fileName, string prefix)
    {
        var name = fileName.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) ? fileName[..^4] : fileName;
        return name.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) ? name[prefix.Length..] : name;
    }
}
