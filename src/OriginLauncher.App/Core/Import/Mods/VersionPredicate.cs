using System.Globalization;

namespace OriginLauncher.App.Core.Import.Mods;

// Fabric's version rules, as far as an import needs them: is mod/Minecraft/Java
// version V inside the range a fabric.mod.json asks for?
//
// A version is dot-separated numbers, an optional "-prerelease" and an ignored
// "+build" ("0.8.12+mc1.21.11" == "0.8.12"). Pre-releases sort BELOW the
// release ("1.21-rc1" < "1.21"). A trailing dash with nothing after it
// ("1.21-") is the lowest pre-release of that number, which is how mods write
// ">=1.21- <1.22-" to include snapshots of 1.21 and exclude those of 1.22.
// A version that is not numeric ("24w14a", a snapshot id) only ever matches
// itself.
public sealed class FabricVersion : IComparable<FabricVersion>
{
    public string Raw { get; }
    public IReadOnlyList<long> Core { get; }

    // null = a release; empty list = the lowest pre-release ("1.21-"); else the identifiers.
    private readonly List<string>? _pre;

    private FabricVersion(string raw, List<long> core, List<string>? pre)
    {
        Raw = raw;
        Core = core;
        _pre = pre;
    }

    public static bool TryParse(string? text, out FabricVersion version)
    {
        version = null!;
        if (string.IsNullOrWhiteSpace(text)) return false;
        var raw = text.Trim();
        var s = raw;
        var plus = s.IndexOf('+');
        if (plus >= 0) s = s[..plus];
        List<string>? pre = null;
        var dash = s.IndexOf('-');
        if (dash >= 0)
        {
            var preText = s[(dash + 1)..];
            s = s[..dash];
            pre = preText.Length == 0 ? new List<string>() : preText.Split('.').ToList();
        }
        if (s.Length == 0) return false;
        var core = new List<long>();
        foreach (var part in s.Split('.'))
        {
            if (!long.TryParse(part, NumberStyles.None, CultureInfo.InvariantCulture, out var n)) return false;
            core.Add(n);
        }
        version = new FabricVersion(raw, core, pre);
        return true;
    }

    /// <summary>The same number with the lowest possible pre-release ("1.22" -> "1.22-"): the bound of "&lt;1.22-".</summary>
    internal FabricVersion WithLowestPreRelease() => new(Raw, Core.ToList(), new List<string>());

    internal static FabricVersion FromCore(IEnumerable<long> core, bool lowestPre) =>
        new(string.Join('.', core), core.ToList(), lowestPre ? new List<string>() : null);

    public int CompareTo(FabricVersion? other)
    {
        if (other == null) return 1;
        for (var i = 0; i < Math.Max(Core.Count, other.Core.Count); i++)
        {
            var a = i < Core.Count ? Core[i] : 0;
            var b = i < other.Core.Count ? other.Core[i] : 0;
            if (a != b) return a.CompareTo(b);
        }
        if (_pre == null && other._pre == null) return 0;
        if (_pre == null) return 1;        // a release is above any pre-release
        if (other._pre == null) return -1;
        // Both pre-releases: the lowest marker (empty) first, then identifier by identifier.
        for (var i = 0; i < Math.Min(_pre.Count, other._pre.Count); i++)
        {
            var c = CompareIdentifier(_pre[i], other._pre[i]);
            if (c != 0) return c;
        }
        return _pre.Count.CompareTo(other._pre.Count);
    }

    private static int CompareIdentifier(string a, string b)
    {
        var an = long.TryParse(a, NumberStyles.None, CultureInfo.InvariantCulture, out var x);
        var bn = long.TryParse(b, NumberStyles.None, CultureInfo.InvariantCulture, out var y);
        if (an && bn) return x.CompareTo(y);
        if (an) return -1; // numbers sort below words
        if (bn) return 1;
        return string.CompareOrdinal(a, b);
    }

    public override string ToString() => Raw;

    /// <summary>Orders two version strings: numerically when both parse, else by text.</summary>
    public static int Compare(string? a, string? b)
    {
        if (TryParse(a, out var x) && TryParse(b, out var y)) return x.CompareTo(y);
        return string.CompareOrdinal(a, b);
    }
}

/// <summary>
/// A fabric.mod.json version requirement: ">=1.2", "&lt;2", "~1.2.3", "^1.2.3",
/// "1.2.x", "*", "1.2.3" (exactly), several space-separated parts (all must
/// hold), and a JSON array of those (any one may hold).
/// </summary>
public sealed class VersionPredicate
{
    private enum Op { Any, Eq, Gt, Ge, Lt, Le }

    private sealed record Comparator(Op Op, FabricVersion? Version, string? LiteralText);

    private readonly List<List<Comparator>> _anyOf;

    /// <summary>The requirement as the player should read it ("&gt;=0.15.0", "1.21 or 1.21.1").</summary>
    public string Display { get; }

    /// <summary>A concrete version this requirement points at ("~1.21.1" -> "1.21.1", "1.21.x" -> "1.21"), or null for "*".</summary>
    public string? Anchor { get; }

    private VersionPredicate(List<List<Comparator>> anyOf, string display, string? anchor)
    {
        _anyOf = anyOf;
        Display = display;
        Anchor = anchor;
    }

    public static VersionPredicate Parse(string requirement) => Parse(new[] { requirement });

    public static VersionPredicate Parse(IEnumerable<string> alternatives)
    {
        var anyOf = new List<List<Comparator>>();
        var shown = new List<string>();
        string? anchor = null;
        foreach (var alternative in alternatives)
        {
            var text = (alternative ?? "").Trim();
            shown.Add(text.Length == 0 ? "*" : text);
            var all = new List<Comparator>();
            foreach (var token in text.Split(' ', StringSplitOptions.RemoveEmptyEntries))
            {
                all.AddRange(ParseToken(token, out var tokenAnchor));
                anchor ??= tokenAnchor;
            }
            if (all.Count == 0) all.Add(new Comparator(Op.Any, null, null));
            anyOf.Add(all);
        }
        if (anyOf.Count == 0) anyOf.Add(new List<Comparator> { new(Op.Any, null, null) });
        return new VersionPredicate(anyOf, string.Join(" or ", shown), anchor);
    }

    public bool IsSatisfiedBy(string? version)
    {
        var parsed = FabricVersion.TryParse(version, out var v) ? v : null;
        foreach (var all in _anyOf)
            if (all.All(c => Test(c, parsed, version))) return true;
        return false;
    }

    /// <summary>True for "*" or an empty requirement.</summary>
    public bool MatchesAnything => _anyOf.Any(all => all.All(c => c.Op == Op.Any));

    private static bool Test(Comparator c, FabricVersion? v, string? raw)
    {
        switch (c.Op)
        {
            case Op.Any: return true;
            case Op.Eq:
                if (c.Version != null && v != null) return v.CompareTo(c.Version) == 0;
                return string.Equals(raw?.Trim(), c.LiteralText, StringComparison.Ordinal);
        }
        if (c.Version == null || v == null) return false; // a non-numeric version has no order
        var cmp = v.CompareTo(c.Version);
        return c.Op switch
        {
            Op.Gt => cmp > 0,
            Op.Ge => cmp >= 0,
            Op.Lt => cmp < 0,
            Op.Le => cmp <= 0,
            _ => false,
        };
    }

    private static IEnumerable<Comparator> ParseToken(string token, out string? anchor)
    {
        anchor = null;
        if (token == "*") return new[] { new Comparator(Op.Any, null, null) };

        string rest;
        var op = Op.Eq;
        var range = '\0'; // '~' or '^' expand to a range
        if (token.StartsWith(">=")) { op = Op.Ge; rest = token[2..]; }
        else if (token.StartsWith("<=")) { op = Op.Le; rest = token[2..]; }
        else if (token.StartsWith('>')) { op = Op.Gt; rest = token[1..]; }
        else if (token.StartsWith('<')) { op = Op.Lt; rest = token[1..]; }
        else if (token.StartsWith('=')) { rest = token[1..]; }
        else if (token.StartsWith('~') || token.StartsWith('^')) { range = token[0]; rest = token[1..]; }
        else { rest = token; }

        // 1.2.x / 1.x / 1.2.* : a range over the part before the wildcard.
        var parts = rest.Split('.');
        var wildcard = Array.FindIndex(parts, p => p is "x" or "X" or "*");
        if (wildcard >= 0 && op == Op.Eq && range == '\0')
        {
            var prefix = parts[..wildcard];
            if (prefix.Length == 0) return new[] { new Comparator(Op.Any, null, null) };
            if (!FabricVersion.TryParse(string.Join('.', prefix), out var low)) return Literal(token);
            anchor = string.Join('.', prefix);
            var upper = low.Core.ToList();
            upper[^1]++;
            return new[]
            {
                new Comparator(Op.Ge, low, null),
                new Comparator(Op.Lt, FabricVersion.FromCore(upper, lowestPre: true), null),
            };
        }

        if (!FabricVersion.TryParse(rest, out var version)) return Literal(token);
        anchor = string.Join('.', version.Core);

        if (range == '~' || range == '^')
        {
            // ~1.2.3 -> >=1.2.3 <1.3 ; ~1 -> <2 ; ^1.2.3 -> >=1.2.3 <2 (Fabric treats ^ as "same major").
            var bumpIndex = range == '^' ? 0 : (version.Core.Count >= 2 ? 1 : 0);
            var upper = new List<long>();
            for (var i = 0; i <= bumpIndex; i++) upper.Add(i < version.Core.Count ? version.Core[i] : 0);
            upper[bumpIndex]++;
            return new[]
            {
                new Comparator(Op.Ge, version, null),
                new Comparator(Op.Lt, FabricVersion.FromCore(upper, lowestPre: true), null),
            };
        }
        return new[] { new Comparator(op, version, token.TrimStart('=')) };
    }

    // A version Fabric cannot order (a snapshot id): only an exact match counts.
    private static IEnumerable<Comparator> Literal(string token) =>
        new[] { new Comparator(Op.Eq, null, token.TrimStart('=')) };
}
