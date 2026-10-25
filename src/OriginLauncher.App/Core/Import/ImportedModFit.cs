using OriginLauncher.App.Core.Import.Mods;

namespace OriginLauncher.App.Core.Import;

/// <summary>
/// The second half of "will the imported mods boot next to Origin's mods". <see cref="ModSetResolver"/> checks an
/// imported mod's dependencies against the OTHER IMPORTED mods, but a mod Origin already has (same id) is never
/// copied over, so the imported mods that need a different version of it (Counter, Spawn Chaining and Unchained want
/// Cobblemon 1.7.3 while Origin has 1.8.0) would land next to a version they refuse, and Fabric stops the whole game
/// ("only the wrong version is present", 2026-10-20). Those mods are imported TURNED OFF instead, with the reason,
/// so the game still starts and the Mods page shows what to update.
/// Only "the dependency is in the folder at the wrong version" (or was just turned off here) counts: a dependency
/// that isn't a top-level jar may be nested inside another jar, which this read can't see, so it is never a reason.
/// </summary>
internal static class ImportedModFit
{
    private static readonly HashSet<string> Platform = new(StringComparer.Ordinal) { "minecraft", "java", "fabricloader", "fabric" };

    /// <param name="decisions">The resolved mod set of one setup.</param>
    /// <param name="infoOf">What a decision's jar says about itself (its depends / provides), or null.</param>
    /// <param name="ledger">The shared mods folder as it will be.</param>
    /// <param name="landingOf">Whether and how a copied mod lands (<see cref="SharedModsLedger.Decide"/>).</param>
    public static List<ModDecision> Apply(
        IReadOnlyList<ModDecision> decisions, Func<ModDecision, ModInfo?> infoOf, SharedModsLedger ledger,
        Func<ModDecision, ModLanding> landingOf)
    {
        var present = ledger.PresentVersions();
        // Mods that land turned on: they join the folder, replacing Origin's copy when the ledger says so.
        var landing = new List<ModDecision>();
        foreach (var d in decisions)
        {
            if (d.Outcome != ModOutcome.Copied || d.ModId is not { Length: > 0 } id) continue;
            if (landingOf(d).Holder != null) continue;
            landing.Add(d);
            present[id] = d.Version;
            foreach (var provided in infoOf(d)?.Provides ?? Array.Empty<string>()) present[provided] = d.Version;
        }
        if (landing.Count == 0) return decisions.ToList();

        var known = new HashSet<string>(present.Keys, StringComparer.Ordinal);
        var turnedOff = new Dictionary<ModDecision, string>(ReferenceEqualityComparer.Instance);
        bool changed;
        do
        {
            changed = false;
            foreach (var d in landing)
            {
                if (turnedOff.ContainsKey(d)) continue;
                var problem = FindProblem(infoOf(d), present, known);
                if (problem == null) continue;
                turnedOff[d] = problem;
                // Turned off, it loads nothing. If it was going to replace Origin's older copy, that copy stays on
                // (SharedModsLedger.Swap keeps it), so put Origin's version back instead of dropping the id: its
                // dependants that accept any version must not be turned off for a mod that is still there.
                RestoreOriginsCopy(d.ModId!, ledger, present);
                foreach (var provided in infoOf(d)?.Provides ?? Array.Empty<string>()) RestoreOriginsCopy(provided, ledger, present);
                changed = true;
            }
        } while (changed);
        if (turnedOff.Count == 0) return decisions.ToList();

        return decisions.Select(d => turnedOff.TryGetValue(d, out var reason)
            ? d with { Outcome = ModOutcome.CopiedDisabled, Reason = reason, DestFileName = d.DestFileName is { } dest ? dest + ".disabled" : null }
            : d).ToList();
    }

    private static void RestoreOriginsCopy(string id, SharedModsLedger ledger, Dictionary<string, string?> present)
    {
        if (ledger.TryGetOriginVersion(id, out var version)) present[id] = version;
        else present.Remove(id);
    }

    /// <summary>True for a reason this class wrote (the import was turned off because it would stop the game from starting).</summary>
    internal static bool IsFitReason(string? reason) =>
        reason != null && reason.StartsWith(ReasonPrefix, StringComparison.Ordinal);

    private const string ReasonPrefix = "Would stop the game from starting: ";

    private static string? FindProblem(ModInfo? info, Dictionary<string, string?> present, HashSet<string> known)
    {
        if (info == null) return null;
        var problems = new List<string>();
        foreach (var dep in info.Depends.OrderBy(x => x.Id, StringComparer.Ordinal))
        {
            if (Platform.Contains(dep.Id) || !known.Contains(dep.Id)) continue;
            var range = dep.Predicate;
            if (!present.TryGetValue(dep.Id, out var version))
                problems.Add($"needs {dep.Id}, which is turned off here");
            else if (version != null && !range.IsSatisfiedBy(version))
                problems.Add($"needs {dep.Id} {range.Display}, but Origin has {version}");
        }
        return problems.Count == 0 ? null
            : ReasonPrefix + string.Join("; ", problems) + ". Update that mod on the Mods page, then turn this one on.";
    }
}
