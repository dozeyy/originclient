using System.IO;

namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// Turns a <see cref="CompatReport"/> into the smallest list of repairs that leaves nothing blocking. Pure: no disk,
/// no clock. It works on ROOT causes (findings that are not knock-ons), applies them to a copy of the mod set,
/// re-runs <see cref="CompatEngine.Check"/> and repeats, so the property holds by construction: applying the plan
/// and checking again yields no blocking finding, except the ones listed in <see cref="RepairPlan.Unfixable"/>.
/// Per root cause, in this order of preference:
///   * a missing dependency the catalog can supply -> add that file (nothing is lost);
///   * otherwise turn the mod that has the problem off (reversible).
/// It never touches Origin's own mods (<see cref="ModRole.Managed"/>) or a mod the caller protects, and it only acts
/// on Blocking findings: warnings and information stay information.
/// </summary>
public static class RepairPlanner
{
    // Each round fixes at least one root cause or stops; a mod set this broken is not a case to keep grinding on.
    private const int MaxRounds = 16;

    public static RepairPlan Plan(CompatReport report, RepairContext context)
    {
        var steps = new List<RepairStep>();
        var unfixable = new List<UnfixableFinding>();
        var added = new Dictionary<string, ModFile>(StringComparer.Ordinal);   // offered id -> the simulated file
        var skip = new HashSet<ModFile>(ReferenceEqualityComparer.Instance);   // gave up on: already in Unfixable
        var current = report;

        for (var round = 0; round < MaxRounds; round++)
        {
            var blocking = current.Findings
                .Where(f => f.Severity == Severity.Blocking && f.Excludes && f.Subject.Enabled && !skip.Contains(f.Subject))
                .ToList();
            if (blocking.Count == 0) break;

            // Root causes first: a knock-on goes away when its cause does. If every remaining one is a knock-on
            // (its cause can't be fixed), act on them directly.
            var roots = blocking.Where(f => !f.IsKnockOn).ToList();
            if (roots.Count == 0) roots = blocking;

            var progress = steps.Count + skip.Count;
            foreach (var group in roots.GroupBy(f => f.Subject, ReferenceEqualityComparer.Instance))
            {
                var mod = (ModFile)group.Key;
                var findings = group.ToList();
                if (TryAdd(findings, context, added, steps)) continue;

                var why = Refuse(mod, context) ?? (added.ContainsValue(mod) ? "Added by this repair, so it can't be turned off by it." : null);
                if (why != null)
                {
                    foreach (var f in findings) unfixable.Add(new UnfixableFinding(f, why));
                    skip.Add(mod);
                    continue;
                }
                steps.Add(new DisableModStep(mod, report.ReasonFor(mod) ?? findings[0].Summary, findings.Min(f => f.Certainty)));
            }
            if (steps.Count + skip.Count == progress) break; // nothing new could be done

            current = CompatEngine.Check(Simulate(report.Entries, steps, added.Values), context.Platform);
        }

        return steps.Count == 0 && unfixable.Count == 0 ? RepairPlan.Empty : new RepairPlan(steps, unfixable);
    }

    // Why a mod can't be turned off here, or null when it can.
    private static string? Refuse(ModFile mod, RepairContext context)
    {
        if (mod.Role == ModRole.Managed) return "This is one of Origin's own mods, so it stays as it is.";
        if (context.IsProtected?.Invoke(mod) == true) return "This mod is protected, so it stays as it is.";
        if (!mod.FileName.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) return "Only a turned-on .jar file can be turned off.";
        return null;
    }

    // A mod whose only blocking problems are missing dependencies the catalog can supply gets them added instead.
    private static bool TryAdd(
        List<CompatFinding> findings, RepairContext context, Dictionary<string, ModFile> added, List<RepairStep> steps)
    {
        if (context.Catalog == null) return false;
        if (findings.Any(f => f.Kind != FindingKind.MissingDependency || f.TargetIsOrigin || f.TargetId == null)) return false;

        var offers = new List<DependencyOffer>();
        foreach (var f in findings)
        {
            var offer = context.Catalog.Find(f.TargetId!, VersionPredicate.Parse(f.Range ?? "*"), context.Platform);
            if (offer == null || offer.FileName.IndexOfAny(new[] { '/', '\\' }) >= 0) return false;
            offers.Add(offer);
        }

        foreach (var offer in offers)
        {
            if (added.ContainsKey(offer.Id)) continue;
            var dest = Path.Combine(context.DestinationFolder, offer.FileName);
            steps.Add(new AddModStep(offer, dest, $"{offer.Info.Name ?? offer.Id} {offer.Version} is needed by {string.Join(", ", findings.Select(f => f.Subject.DisplayName).Distinct())}."));
            added[offer.Id] = new ModFile(dest, offer.FileName, true, context.AddedRole, offer.Info);
        }
        return true;
    }

    // The mod set as it will be after the steps so far: turned-off mods renamed, added files present.
    private static List<ModFile> Simulate(IReadOnlyList<ModFile> original, List<RepairStep> steps, IEnumerable<ModFile> added)
    {
        var disabled = new HashSet<ModFile>(steps.OfType<DisableModStep>().Select(s => s.Mod), ReferenceEqualityComparer.Instance);
        var result = new List<ModFile>(original.Count + 4);
        foreach (var e in original)
        {
            if (!disabled.Contains(e)) { result.Add(e); continue; }
            result.Add(e with { Path = e.Path + ".disabled", FileName = e.FileName + ".disabled", Enabled = false });
        }
        result.AddRange(added);
        return result;
    }
}
