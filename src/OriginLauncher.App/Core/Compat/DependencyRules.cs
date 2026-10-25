namespace OriginLauncher.App.Core.Compat;

/// <summary>One version of an id that is on offer: from a mod file (<see cref="Source"/>) or from Origin/the platform.</summary>
public readonly record struct ProvidedVersion(string? Version, ModFile? Source, bool IsOrigin);

/// <summary>
/// What a mod's requirements are checked against: for each id, the versions that would be there. A version of null
/// means "present, version not stated" and meets any requirement.
/// </summary>
public interface IProvidedMods
{
    /// <summary>
    /// False = <paramref name="id"/> is unknown to this environment. True with an empty list = known but absent
    /// (turned off). Otherwise the versions on offer, best first.
    /// </summary>
    bool TryGetVersions(string id, out IReadOnlyList<ProvidedVersion> versions);
}

public enum DependencyProblemKind { Missing, WrongVersion, Breaks, Conflicts, MissingRecommended }

/// <summary>One requirement of a mod that the environment does not meet.</summary>
/// <param name="Providers">The mod files that answer to the id (the mods in the way for Breaks/Conflicts), if any.</param>
public sealed record DependencyProblem(
    DependencyProblemKind Kind, ModDependency Dependency, string? Found, bool TargetIsOrigin, IReadOnlyList<ModFile> Providers);

/// <summary>What <see cref="DependencyRules.Evaluate"/> leaves alone.</summary>
/// <param name="IgnoreIds">Ids never judged (the caller checks them itself, or they are the platform).</param>
/// <param name="IgnoreUnknown">A dependency on an id the environment does not know is not a problem (it may be nested in a jar this read can't see).</param>
/// <param name="ConflictChecks">Also evaluate breaks / conflicts / recommends. Off for callers that only ask "is everything it needs there".</param>
public sealed record DependencyOptions(
    IReadOnlySet<string>? IgnoreIds = null, bool IgnoreUnknown = false, bool ConflictChecks = true);

/// <summary>
/// THE place where a mod's depends / breaks / conflicts / recommends are held against an environment, with Fabric's
/// version rules. The engine, the import resolver, the imported-mod fit check and the shared-folder ledger all go
/// through here (or through <see cref="ModDependency.IsSatisfiedBy"/>), so no caller keeps its own copy of the rules.
/// Pure.
/// </summary>
public static class DependencyRules
{
    public static List<DependencyProblem> Evaluate(ModInfo info, IProvidedMods env, DependencyOptions? options = null)
    {
        options ??= new DependencyOptions();
        var problems = new List<DependencyProblem>();

        foreach (var dep in info.Depends.OrderBy(d => d.Id, StringComparer.Ordinal))
        {
            if (options.IgnoreIds?.Contains(dep.Id) == true) continue;
            if (!env.TryGetVersions(dep.Id, out var versions))
            {
                if (!options.IgnoreUnknown) problems.Add(new DependencyProblem(DependencyProblemKind.Missing, dep, null, false, Array.Empty<ModFile>()));
                continue;
            }
            if (versions.Count == 0)
            {
                problems.Add(new DependencyProblem(DependencyProblemKind.Missing, dep, null, false, Array.Empty<ModFile>()));
                continue;
            }
            if (versions.Any(v => dep.IsSatisfiedBy(v.Version))) continue;
            problems.Add(new DependencyProblem(DependencyProblemKind.WrongVersion, dep, versions[0].Version,
                versions[0].IsOrigin, Sources(versions)));
        }

        if (!options.ConflictChecks) return problems;

        // A conflict needs the other mod to be PRESENT in a version the entry names (or one that states none).
        AddConflicts(info.Breaks, DependencyProblemKind.Breaks);
        AddConflicts(info.Conflicts, DependencyProblemKind.Conflicts);

        foreach (var rec in info.Recommends.OrderBy(d => d.Id, StringComparer.Ordinal))
        {
            if (options.IgnoreIds?.Contains(rec.Id) == true) continue;
            if (env.TryGetVersions(rec.Id, out var versions) && versions.Count > 0) continue;
            problems.Add(new DependencyProblem(DependencyProblemKind.MissingRecommended, rec, null, false, Array.Empty<ModFile>()));
        }
        return problems;

        void AddConflicts(IReadOnlyList<ModDependency> list, DependencyProblemKind kind)
        {
            foreach (var entry in list.OrderBy(d => d.Id, StringComparer.Ordinal))
            {
                if (options.IgnoreIds?.Contains(entry.Id) == true) continue;
                if (!env.TryGetVersions(entry.Id, out var versions)) continue;
                var hit = versions.Where(v => entry.IsSatisfiedBy(v.Version)).ToList();
                if (hit.Count == 0) continue;
                problems.Add(new DependencyProblem(kind, entry, hit[0].Version, hit[0].IsOrigin, Sources(hit)));
            }
        }
    }

    private static IReadOnlyList<ModFile> Sources(IEnumerable<ProvidedVersion> versions) =>
        versions.Where(v => v.Source != null).Select(v => v.Source!).Distinct().ToList();

    /// <summary>"Needs sodium >=0.6" / "Needs sodium": the requirement as a player reads it, without a full stop.</summary>
    public static string NeedsText(ModDependency dep) =>
        dep.Predicate.MatchesAnything ? $"Needs {dep.Id}" : $"Needs {dep.Id} {dep.Predicate.Display}";

    /// <summary>"1.21.1+build.4" -> "1.21.1": the part of a version a player cares about.</summary>
    public static string Shown(string version) => version.Split('+')[0];
}
