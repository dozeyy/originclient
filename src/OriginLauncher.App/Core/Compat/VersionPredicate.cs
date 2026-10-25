namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// A fabric.mod.json version requirement, with the loader's own rules (a port of Fabric Loader's
/// VersionPredicateParser + VersionComparisonOperator, read 2026-10-25):
/// <list type="bullet">
/// <item>Operators, tried in this order: <c>&gt;=  &lt;=  &gt;  &lt;  =  ~  ^</c>; none means <c>=</c>.</item>
/// <item><c>~1.2.3</c> = same major.minor and at least 1.2.3 (below <c>1.3-</c>); <c>~1</c> is below <c>1.1-</c>.
///   <c>^1.2.3</c> = same major and at least 1.2.3 (below <c>2-</c>).</item>
/// <item>A trailing wildcard is rewritten: <c>1.x</c> = <c>^1-</c>, <c>1.20.x</c> = <c>~1.20-</c>,
///   <c>1.2.3.x</c> = <c>&gt;=1.2.3- &lt;1.2.4-</c> (so snapshots of that number are inside).</item>
/// <item>Space-separated parts must ALL hold; a JSON array of such strings means ANY one may hold. "*" or empty = anything.</item>
/// <item>A requirement whose version is not numeric ("24w14a") can only be an equality; with &gt; or &lt;
///   Fabric rejects the whole mod, which here is <see cref="IsValid"/> = false and matches nothing.</item>
/// </list>
/// A tested version that is not numeric only satisfies a bound-inclusive operator when the text is identical.
/// </summary>
public sealed class VersionPredicate
{
    private enum Op { Ge, Le, Gt, Lt, Eq, Tilde, Caret }

    // Same order as Fabric's VersionComparisonOperator, so ">=" is not read as ">" + "=1.2".
    private static readonly (string Text, Op Op)[] Operators =
    {
        (">=", Op.Ge), ("<=", Op.Le), (">", Op.Gt), ("<", Op.Lt), ("=", Op.Eq), ("~", Op.Tilde), ("^", Op.Caret),
    };

    private sealed record Term(Op Op, FabricVersion Version);

    // One list per alternative; an empty list = anything.
    private readonly List<List<Term>> _anyOf;

    private VersionPredicate(List<List<Term>> anyOf, string display, string? anchor, string? error)
    {
        _anyOf = anyOf;
        Display = display;
        Anchor = anchor;
        Error = error;
    }

    /// <summary>The requirement as the player should read it ("&gt;=0.15.0", "1.21 or 1.21.1").</summary>
    public string Display { get; }

    /// <summary>A concrete version this requirement points at ("~1.21.1" -> "1.21.1", "1.21.x" -> "1.21"), or null for "*".</summary>
    public string? Anchor { get; }

    /// <summary>Why Fabric would reject this requirement (a bad operator/version), or null when it is fine.</summary>
    public string? Error { get; }

    public bool IsValid => Error == null;

    /// <summary>True for "*" or an empty requirement.</summary>
    public bool MatchesAnything => IsValid && _anyOf.Any(all => all.Count == 0);

    public static VersionPredicate Parse(string requirement) => Parse(new[] { requirement });

    public static VersionPredicate Parse(IEnumerable<string> alternatives)
    {
        var anyOf = new List<List<Term>>();
        var shown = new List<string>();
        string? anchor = null;
        string? error = null;
        foreach (var alternative in alternatives)
        {
            var text = (alternative ?? "").Trim();
            shown.Add(text.Length == 0 ? "*" : text);
            var all = new List<Term>();
            foreach (var token in text.Split(' ', StringSplitOptions.RemoveEmptyEntries))
            {
                if (token == "*") continue;
                if (!TryParseToken(token, all, out var tokenAnchor, out var tokenError))
                {
                    error ??= tokenError;
                    continue;
                }
                anchor ??= tokenAnchor;
            }
            anyOf.Add(all);
        }
        if (anyOf.Count == 0) anyOf.Add(new List<Term>());
        return new VersionPredicate(anyOf, string.Join(" or ", shown), anchor, error);
    }

    public bool IsSatisfiedBy(string? version)
    {
        if (!IsValid) return false;
        if (MatchesAnything) return true;
        if (string.IsNullOrWhiteSpace(version)) return false;
        var tested = FabricVersion.Parse(version, allowWildcard: false);
        foreach (var all in _anyOf)
            if (all.All(term => Test(term, tested))) return true;
        return false;
    }

    private static bool Test(Term term, FabricVersion tested)
    {
        var bound = term.Version;
        if (!tested.IsSemantic || !bound.IsSemantic)
        {
            // Only operators that include their bound can hold, and only for the very same text.
            var inclusive = term.Op is Op.Ge or Op.Le or Op.Eq or Op.Tilde or Op.Caret;
            return inclusive && string.Equals(tested.Raw, bound.Raw, StringComparison.Ordinal);
        }
        var cmp = tested.CompareTo(bound);
        return term.Op switch
        {
            Op.Ge => cmp >= 0,
            Op.Le => cmp <= 0,
            Op.Gt => cmp > 0,
            Op.Lt => cmp < 0,
            Op.Eq => cmp == 0,
            Op.Tilde => cmp >= 0 && tested.Component(0) == bound.Component(0) && tested.Component(1) == bound.Component(1),
            Op.Caret => cmp >= 0 && tested.Component(0) == bound.Component(0),
            _ => false,
        };
    }

    private static bool TryParseToken(string token, List<Term> into, out string? anchor, out string? error)
    {
        anchor = null;
        error = null;
        var op = Op.Eq;
        var text = token;
        foreach (var (opText, candidate) in Operators)
        {
            if (!token.StartsWith(opText, StringComparison.Ordinal)) continue;
            op = candidate;
            text = token[opText.Length..];
            break;
        }
        if (text.Length == 0) { error = $"'{token}' has no version after the operator"; return false; }

        var version = FabricVersion.Parse(text, allowWildcard: true);
        if (version.HasWildcard)
        {
            // Fabric: a wildcard is only allowed with no operator (or "=").
            if (op != Op.Eq) { error = $"'{token}': a wildcard can't be combined with an operator"; return false; }
            var prefix = version.Components.Take(version.Components.Count - 1).ToArray();
            anchor = string.Join('.', prefix);
            switch (version.Components.Count)
            {
                case 2: // a.x -> ^a-
                    into.Add(new Term(Op.Caret, version.WithLowestPrerelease(prefix)));
                    break;
                case 3: // a.b.x -> ~a.b-
                    into.Add(new Term(Op.Tilde, version.WithLowestPrerelease(prefix)));
                    break;
                default: // a.b.c.x -> >=a.b.c- <a.b.(c+1)-
                    var upper = (int[])prefix.Clone();
                    upper[^1]++;
                    into.Add(new Term(Op.Ge, version.WithLowestPrerelease(prefix)));
                    into.Add(new Term(Op.Lt, version.WithLowestPrerelease(upper)));
                    break;
            }
            return true;
        }

        if (!version.IsSemantic)
        {
            // Without an order, only "the same text" can be asked; > and < are an error for the loader.
            if (op is Op.Gt or Op.Lt) { error = $"'{token}': '{text}' isn't a number, so it can't be compared with > or <"; return false; }
            into.Add(new Term(Op.Eq, version));
            anchor = version.Raw;
            return true;
        }

        anchor = string.Join('.', version.Components);
        into.Add(new Term(op, version));
        return true;
    }
}
