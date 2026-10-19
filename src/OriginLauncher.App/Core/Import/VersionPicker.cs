using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import;

public enum PickMode
{
    None,    // nothing to pick from
    Auto,    // one clear winner: use it, tell the player which
    Choose,  // ask the player, with Preselected already highlighted
}

/// <summary>What the picker decided: the candidates to show (best first) and which is highlighted / used.</summary>
public sealed record PickResult(PickMode Mode, IReadOnlyList<DetectedInstance> Options, DetectedInstance? Preselected);

/// <summary>Whether a source's Minecraft version can be imported into Origin as it is, and what to suggest if not.</summary>
public sealed record SupportCheck(bool Supported, string? Reason, string? Suggestion);

// Which of a launcher's instances to import from (D7) and which Minecraft
// versions Origin can take (D8). Pure functions of the data the detectors read.
public static class VersionPicker
{
    private const double ClearWinnerRatio = 1.5;
    private const int ChooseFrom = 3;

    /// <summary>
    /// D7, in order:
    ///  1. exactly one pinned instance -> it; several pinned -> choose among them;
    ///  2. playtime known: the top one if it has 1.5x the runner-up (or nothing
    ///     competes), else choose among the top 3;
    ///  3. only "last played" known: choose, most recent highlighted;
    ///  4. nothing known: choose, newest Minecraft version first.
    /// A lone candidate is always just used.
    /// </summary>
    public static PickResult Pick(IReadOnlyList<DetectedInstance> candidates)
    {
        if (candidates.Count == 0) return new PickResult(PickMode.None, Array.Empty<DetectedInstance>(), null);
        if (candidates.Count == 1) return Auto(candidates[0]);

        var pinned = candidates.Where(c => c.Pinned).ToList();
        if (pinned.Count == 1) return Auto(pinned[0]);
        if (pinned.Count > 1) return Choose(Recency(pinned).ToList());

        if (candidates.Any(c => c.PlaytimeSeconds is > 0))
        {
            var ranked = candidates
                .OrderByDescending(c => c.PlaytimeSeconds ?? 0)
                .ThenByDescending(c => c.LastPlayed ?? DateTimeOffset.MinValue)
                .ToList();
            var top = ranked[0].PlaytimeSeconds ?? 0;
            var runnerUp = ranked[1].PlaytimeSeconds ?? 0;
            if (runnerUp == 0 || top >= ClearWinnerRatio * runnerUp) return Auto(ranked[0]);
            return Choose(ranked.Take(ChooseFrom).ToList());
        }

        if (candidates.Any(c => c.LastPlayed != null))
            return Choose(Recency(candidates).ToList());

        return Choose(candidates
            .OrderByDescending(c => c.McVersion, Comparer<string?>.Create(CompareVersions))
            .ThenBy(c => c.Name, StringComparer.OrdinalIgnoreCase)
            .ToList());
    }

    private static IEnumerable<DetectedInstance> Recency(IEnumerable<DetectedInstance> items) =>
        items.OrderByDescending(c => c.LastPlayed ?? DateTimeOffset.MinValue)
             .ThenByDescending(c => c.PlaytimeSeconds ?? 0)
             .ThenBy(c => c.Name, StringComparer.OrdinalIgnoreCase);

    private static int CompareVersions(string? a, string? b)
    {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        return McVersion.Compare(a, b);
    }

    private static PickResult Auto(DetectedInstance one) => new(PickMode.Auto, new[] { one }, one);

    private static PickResult Choose(List<DetectedInstance> options) => new(PickMode.Choose, options, options[0]);

    // ---- supported target versions (D8) ----

    /// <summary>
    /// The versions an import can target: what the version picker shows
    /// (VersionCatalog.SupportedVersions) AND what has an Origin build
    /// (VersionManager.OriginSupportedVersions). Passed in as data so this stays pure.
    /// </summary>
    public static IReadOnlyList<string> SupportedTargets(IEnumerable<string> catalogVersions, IEnumerable<string> originBuildVersions)
    {
        var builds = new HashSet<string>(originBuildVersions, StringComparer.Ordinal);
        return catalogVersions.Where(builds.Contains).Distinct(StringComparer.Ordinal).ToList();
    }

    /// <summary>
    /// Can a source on <paramref name="sourceVersion"/> go straight into Origin? If not: a plain reason that
    /// names the version, and the suggestion — the nearest LOWER patch of the same major.minor, else the
    /// nearest supported version overall.
    /// </summary>
    public static SupportCheck CheckSupport(string? sourceVersion, IReadOnlyCollection<string> supported)
    {
        if (string.IsNullOrWhiteSpace(sourceVersion))
            return new SupportCheck(false, "Origin couldn't tell which Minecraft version this is.", Newest(supported));
        if (supported.Contains(sourceVersion))
            return new SupportCheck(true, null, null);
        var suggestion = Suggest(sourceVersion, supported);
        var reason = $"Minecraft {sourceVersion}: Origin doesn't support it."
            + (suggestion == null ? "" : $" Try {suggestion} instead.");
        return new SupportCheck(false, reason, suggestion);
    }

    private static string? Suggest(string version, IReadOnlyCollection<string> supported)
    {
        if (supported.Count == 0) return null;
        var parts = Numeric(version);
        if (parts.Length == 0) return Newest(supported);

        // Same major.minor, lower patch: the nearest one below.
        var sameLine = supported
            .Where(s => Numeric(s) is { Length: > 0 } p && p[0] == parts[0] && Minor(p) == Minor(parts))
            .Where(s => McVersion.Compare(s, version) < 0)
            .OrderByDescending(s => s, Comparer<string>.Create(McVersion.Compare))
            .FirstOrDefault();
        if (sameLine != null) return sameLine;

        // Otherwise the closest by (major, minor, patch) distance; on a tie the older one.
        return supported
            .Select(s => (Version: s, Parts: Numeric(s)))
            .Where(x => x.Parts.Length > 0)
            .OrderBy(x => Math.Abs(x.Parts[0] - parts[0]))
            .ThenBy(x => Math.Abs(Minor(x.Parts) - Minor(parts)))
            .ThenBy(x => Math.Abs(Patch(x.Parts) - Patch(parts)))
            .ThenBy(x => McVersion.Compare(x.Version, version) > 0 ? 1 : 0)
            .Select(x => x.Version)
            .FirstOrDefault() ?? Newest(supported);
    }

    private static string? Newest(IReadOnlyCollection<string> supported) =>
        supported.OrderByDescending(s => s, Comparer<string>.Create(McVersion.Compare)).FirstOrDefault();

    // The leading dotted numbers of a version: "1.21.5" -> [1,21,5], "26.4-snapshot-3" -> [26,4], "24w14a" -> [].
    private static int[] Numeric(string version)
    {
        var numbers = new List<int>();
        foreach (var part in version.Split('.'))
        {
            var digits = new string(part.TakeWhile(char.IsAsciiDigit).ToArray());
            if (digits.Length == 0 || !int.TryParse(digits, out var n)) break;
            numbers.Add(n);
            if (digits.Length != part.Length) break; // "4-snapshot-3": the number ends the run
        }
        return numbers.ToArray();
    }

    private static int Minor(int[] parts) => parts.Length > 1 ? parts[1] : 0;
    private static int Patch(int[] parts) => parts.Length > 2 ? parts[2] : 0;
}
