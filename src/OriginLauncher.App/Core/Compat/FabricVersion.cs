using System.Globalization;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Compat;

// A port of Fabric Loader's own version model (net.fabricmc.loader.impl.util.version: VersionParser,
// SemanticVersionImpl), so "does this mod accept that version" gets the loader's answer, not a guess. Read
// against fabric-loader master on 2026-10-25. What it does:
//   * "1.21.1+build.5": everything after the first '+' is build metadata and is IGNORED when comparing.
//   * "1.21-rc1": everything after the first '-' is the pre-release, dot-separated identifiers. A pre-release
//     sorts BELOW its release ("1.21-rc1" < "1.21"). An EMPTY pre-release ("1.21-") is the lowest one of that
//     number: mods write ">=1.21- <1.22-" to include snapshots of 1.21 and exclude those of 1.22.
//   * The core is dot-separated non-negative integers; a missing component counts as 0 ("1.21" == "1.21.0").
//   * Pre-release identifiers compare one by one: two numbers by length then text, a number is below a word,
//     two words by text, and with all shared identifiers equal the one with more identifiers is higher.
//   * Anything that is not that shape ("24w14a", "mc1.21") is a plain STRING version (Fabric's StringVersion):
//     it only ever equals itself and has no order against numbers.
// Deliberate deviation: input is trimmed (the loader does not), because a stray space in a pack's metadata
// should not make a number into a string here.
public sealed class FabricVersion : IComparable<FabricVersion>
{
    /// <summary>The component value for "x" / "X" / "*" in a RANGE ("1.20.x"). Never present in a mod's own version.</summary>
    public const int Wildcard = int.MinValue;

    // Fabric's pattern for a pre-release: dot-separated [-0-9A-Za-z] identifiers (the empty string is allowed).
    private static readonly Regex DotSeparatedId =
        new(@"^(|[-0-9A-Za-z]+(\.[-0-9A-Za-z]+)*)$", RegexOptions.CultureInvariant | RegexOptions.Compiled);

    private readonly int[] _components;

    private FabricVersion(string raw, int[]? components, string? prerelease, string? build)
    {
        Raw = raw;
        _components = components ?? Array.Empty<int>();
        IsSemantic = components != null;
        Prerelease = prerelease;
        Build = build;
    }

    /// <summary>The text as written (trimmed).</summary>
    public string Raw { get; }

    /// <summary>True for a numeric version; false for a plain string version (which only equals itself).</summary>
    public bool IsSemantic { get; }

    /// <summary>The numeric components ("1.21.1" -> 1, 21, 1). Empty for a string version.</summary>
    public IReadOnlyList<int> Components => _components;

    /// <summary>The pre-release text ("rc1"), "" for the lowest pre-release ("1.21-"), null for a release.</summary>
    public string? Prerelease { get; }

    /// <summary>Build metadata (ignored by comparisons), or null.</summary>
    public string? Build { get; }

    public bool HasWildcard => _components.Contains(Wildcard);

    /// <summary>Component <paramref name="index"/>, 0 when the version is shorter (Fabric: a missing component is 0).</summary>
    public int Component(int index) => index < _components.Length ? _components[index] : 0;

    /// <summary>A numeric version parsed from <paramref name="text"/>; false for empty text and for a string version.</summary>
    public static bool TryParse(string? text, out FabricVersion version)
    {
        version = null!;
        if (string.IsNullOrWhiteSpace(text)) return false;
        var parsed = Parse(text, allowWildcard: false);
        if (!parsed.IsSemantic) return false;
        version = parsed;
        return true;
    }

    /// <summary>
    /// Fabric's VersionParser.parse: a numeric version when the text has that shape, else a string version.
    /// <paramref name="allowWildcard"/> accepts a trailing "x" / "X" / "*" component (used for ranges only).
    /// </summary>
    public static FabricVersion Parse(string text, bool allowWildcard)
    {
        var raw = text.Trim();
        return TryParseSemantic(raw, allowWildcard, out var semantic) ? semantic : new FabricVersion(raw, null, null, null);
    }

    private static bool TryParseSemantic(string raw, bool allowWildcard, out FabricVersion version)
    {
        version = null!;
        if (raw.Length == 0) return false;
        var rest = raw;
        string? build = null;
        var plus = rest.IndexOf('+');
        if (plus >= 0) { build = rest[(plus + 1)..]; rest = rest[..plus]; }

        string? pre = null;
        var dash = rest.IndexOf('-');
        if (dash >= 0) { pre = rest[(dash + 1)..]; rest = rest[..dash]; }
        if (pre != null && !DotSeparatedId.IsMatch(pre)) return false;
        if (rest.Length == 0 || rest.StartsWith('.') || rest.EndsWith('.')) return false;

        var parts = rest.Split('.');
        var components = new List<int>(parts.Length);
        foreach (var part in parts)
        {
            if (allowWildcard && part is "x" or "X" or "*")
            {
                if (pre != null) return false;             // "Pre-release versions are not allowed to use X-ranges!"
                components.Add(Wildcard);
            }
            else if (int.TryParse(part, NumberStyles.None, CultureInfo.InvariantCulture, out var n))
            {
                components.Add(n);
            }
            else
            {
                return false;
            }
        }

        // Wildcards may only trail ("1.x.2" is refused); extra trailing ones collapse ("1.x.x" == "1.x").
        var firstWildcard = components.IndexOf(Wildcard);
        if (firstWildcard >= 0)
        {
            if (firstWildcard == 0) return false;          // a bare "x"
            for (var i = firstWildcard; i < components.Count; i++)
                if (components[i] != Wildcard) return false;
            components.RemoveRange(firstWildcard + 1, components.Count - firstWildcard - 1);
        }

        version = new FabricVersion(raw, components.ToArray(), pre, build);
        return true;
    }

    /// <summary>The same numbers with the lowest pre-release ("1.22" -> "1.22-"): the bound Fabric uses for "~", "^" and "x" ranges.</summary>
    internal FabricVersion WithLowestPrerelease(IEnumerable<int>? components = null)
    {
        var parts = (components ?? _components).ToArray();
        return new FabricVersion(string.Join('.', parts) + "-", parts, "", null);
    }

    public int CompareTo(FabricVersion? other)
    {
        if (other == null) return 1;
        // A string version has no order against numbers: Fabric falls back to comparing the text.
        if (!IsSemantic || !other.IsSemantic) return Math.Sign(string.CompareOrdinal(Raw, other.Raw));

        for (var i = 0; i < Math.Max(_components.Length, other._components.Length); i++)
        {
            var a = Component(i);
            var b = other.Component(i);
            if (a == Wildcard || b == Wildcard) continue;
            if (a != b) return a < b ? -1 : 1;
        }
        if (Prerelease == null && other.Prerelease == null) return 0;
        if (Prerelease == null) return other.HasWildcard ? 0 : 1; // a release is above any pre-release
        if (other.Prerelease == null) return HasWildcard ? 0 : -1;
        return ComparePrerelease(Prerelease, other.Prerelease);
    }

    private static int ComparePrerelease(string a, string b)
    {
        var pa = a.Length == 0 ? Array.Empty<string>() : a.Split('.');
        var pb = b.Length == 0 ? Array.Empty<string>() : b.Split('.');
        for (var i = 0; i < Math.Min(pa.Length, pb.Length); i++)
        {
            var c = CompareIdentifier(pa[i], pb[i]);
            if (c != 0) return c;
        }
        return Math.Sign(pa.Length.CompareTo(pb.Length)); // more identifiers (all shared equal) = higher
    }

    private static int CompareIdentifier(string a, string b)
    {
        var an = a.Length > 0 && a.All(char.IsAsciiDigit);
        var bn = b.Length > 0 && b.All(char.IsAsciiDigit);
        if (an && bn)
        {
            var byLength = a.Length.CompareTo(b.Length);
            return byLength != 0 ? Math.Sign(byLength) : Math.Sign(string.CompareOrdinal(a, b));
        }
        if (an) return -1; // numbers sort below words
        if (bn) return 1;
        return Math.Sign(string.CompareOrdinal(a, b));
    }

    public override string ToString() => Raw;

    /// <summary>Orders two version strings: as versions when both are numeric, else by text.</summary>
    public static int Compare(string? a, string? b)
    {
        if (TryParse(a, out var x) && TryParse(b, out var y)) return x.CompareTo(y);
        return string.CompareOrdinal(a, b);
    }
}
