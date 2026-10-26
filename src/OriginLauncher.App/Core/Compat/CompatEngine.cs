namespace OriginLauncher.App.Core.Compat;

// THE compatibility check of a mod set (Phase 5.1). One pure function: mod descriptors in, a typed report out.
// Nothing here reads a file or the clock; callers read the jars (ModFolderScanner / ModMetadataReader) and decide
// what to do with the report (RepairPlanner, the import resolver, the crash path).
//
// Phases, in this order (the order is the design; the import resolver's old order is preserved):
//   1. per mod, terminal: unreadable -> another client's core jar -> wrong loader -> no id -> Origin already ships
//      it -> built for another Minecraft -> wrong side
//   2. two jars with one mod id: the enabled, then the higher version, then the file name is kept. On Fabric this is
//      a note, never a stop: Fabric Loader loads one jar per id and the game starts (see FindDuplicates)
//   3. Origin's short list of mods that don't work with what it ships (hand-kept, so Likely)
//   4. dependencies and "breaks", repeated until nothing more changes: a mod left out for a missing dependency
//      can't satisfy another's, so its dependants get a knock-on finding (CausedBy)
//   5. what is left over is advice only: "conflicts" (warning) and "recommends" (info)
// Origin's own jars (ModRole.Managed) are part of the world other mods are checked against, and are never the
// subject of a finding: the player's mod is the one that gives way.
// Certainty: Confirmed when fabric.mod.json states it and the loader's rules (DependencyRules) give the verdict;
// Likely for hand-kept tables, for 1.8.9 (mcmod.info is advisory, FML enforces the real dependencies in class
// files) and for anything decided without seeing everything that could answer it.
public static class CompatEngine
{
    public static CompatReport Check(IReadOnlyList<ModFile> entries, CompatPlatform platform) =>
        new Run(entries, platform).Execute();

    private sealed class Run
    {
        // Legacy Forge ids a mod's requiredMods may name that are the platform itself, not a mod to find.
        private static readonly HashSet<string> LegacyPlatformIds =
            new(StringComparer.OrdinalIgnoreCase) { "forge", "fml", "mcp", "minecraft", "fmlcorelibs", "forgemodloader" };

        private static readonly HashSet<string> MinecraftOnly = new(StringComparer.Ordinal) { "minecraft" };

        private readonly IReadOnlyList<ModFile> _entries;
        private readonly List<ModFile> _ordered;
        private readonly CompatPlatform _platform;
        private readonly List<CompatFinding> _findings = new();
        private readonly HashSet<ModFile> _excluded = new(ReferenceEqualityComparer.Instance);
        // Fabric: the turned-on copies a duplicate id left out. The loader may still pick one of them to meet a
        // requirement, so they answer "depends" (never "breaks", which the loader avoids by picking another copy).
        private readonly HashSet<ModFile> _alternates = new(ReferenceEqualityComparer.Instance);
        private readonly Dictionary<string, (BuiltInMod Mod, ModFile? Source)> _origin = new(StringComparer.Ordinal);

        public Run(IReadOnlyList<ModFile> entries, CompatPlatform platform)
        {
            _entries = entries;
            _platform = platform;
            // File-name order, like the import resolver: findings come out in a stable order for the same folder.
            _ordered = entries.OrderBy(e => e.FileName, StringComparer.OrdinalIgnoreCase).ToList();
            foreach (var e in _ordered)
                if (e.Role == ModRole.Managed && e.Enabled && !e.Info.ReadFailed) AddOrigin(e);
        }

        public CompatReport Execute()
        {
            foreach (var e in _ordered)
                if (!IsManaged(e)) Classify(e);

            FindDuplicates();

            if (!_platform.IsLegacyForge)
            {
                foreach (var e in _ordered)
                    if (CanJudge(e)) CheckKnown(e);
                ResolveDependencies();
                AdviseConflicts();
            }
            else
            {
                AdviseLegacyDependencies();
            }
            return new CompatReport(_entries, _findings);
        }

        // ------------------------------------------------------------------ helpers

        private static bool IsManaged(ModFile e) => e.Role == ModRole.Managed;

        // A mod the dependency phases look at: Origin's player-facing mods that are turned on and still in the picture.
        private bool CanJudge(ModFile e) => !IsManaged(e) && e.Enabled && !_excluded.Contains(e);

        // A mod that is turned off loads nothing, so whatever is wrong with it cannot stop the game.
        private static Severity Sev(ModFile e, Severity severity) => e.Enabled ? severity : Severity.Info;

        private void AddOrigin(ModFile e)
        {
            var info = e.Info;
            if (info.Id != null)
                _origin.TryAdd(info.Id, (new BuiltInMod(info.Id, info.Version, info.Name ?? info.Id), e));
            foreach (var id in info.Provides)
                _origin.TryAdd(id, (new BuiltInMod(id, info.VersionProvided(id), info.Name ?? id), e));
            foreach (var nested in info.Nested)
            {
                var mod = new BuiltInMod(nested.Id, nested.Version, nested.Name ?? nested.Id);
                _origin.TryAdd(nested.Id, (mod, e));
                foreach (var id in nested.Provides) _origin.TryAdd(id, (mod with { Id = id }, e));
            }
        }

        private BuiltInMod? FindOrigin(string id) =>
            _origin.TryGetValue(id, out var hit) ? hit.Mod : _platform.BuiltIns?.Find(id);

        private ModFile? OriginEntry(string id) => _origin.TryGetValue(id, out var hit) ? hit.Source : null;

        private void Add(ModFile subject, FindingKind kind, Severity severity, Certainty certainty, string summary,
            bool excludes = false, ModFile? related = null, string? targetId = null, string? range = null,
            string? found = null, string evidence = "", IReadOnlyList<ModFile>? causedBy = null, bool targetIsOrigin = false)
        {
            _findings.Add(new CompatFinding(kind, Sev(subject, severity), certainty, subject, summary)
            {
                Excludes = excludes,
                Related = related,
                TargetId = targetId,
                Range = range,
                Found = found,
                Evidence = evidence,
                CausedBy = causedBy ?? Array.Empty<ModFile>(),
                TargetIsOrigin = targetIsOrigin,
            });
            if (excludes) _excluded.Add(subject);
        }

        // ------------------------------------------------------------------ phase 1

        private void Classify(ModFile e)
        {
            var info = e.Info;
            if (info.ReadFailed)
            {
                Add(e, FindingKind.Unreadable, Severity.Warning, Certainty.Confirmed, "Couldn't read this file.", excludes: true);
                return;
            }

            // Another client's own core jar (Feather, Lunar, Badlion, LabyMod) can never load here: it needs libraries
            // only its own launcher ships and hooks every class, so it would stop the game at the first one (2026-10-20).
            if (ClientCoreMods.Find(info.Id, info.FileName, info.Provides) is { } clientCore)
            {
                Add(e, FindingKind.ForeignClient, Severity.Blocking, Certainty.Likely, clientCore.Reason, excludes: true,
                    evidence: $"matches {clientCore.Name}'s core jar");
                return;
            }

            if (_platform.IsLegacyForge) { ClassifyLegacy(e); return; }

            if (info.Loader != ModLoader.Fabric)
            {
                var known = KnownIncompatible.Check(info, FindOrigin);
                Add(e, FindingKind.WrongLoader, Severity.Warning, Certainty.Confirmed, known ?? info.Loader switch
                {
                    ModLoader.Forge or ModLoader.LegacyForge => "This is a Forge mod. Origin runs Fabric, so it can't load it.",
                    ModLoader.NeoForge => "This is a NeoForge mod. Origin runs Fabric, so it can't load it.",
                    ModLoader.Quilt => "This is a Quilt-only mod. Origin runs Fabric, so it can't load it.",
                    _ => "Not a Fabric mod (no mod information found in it).",
                }, excludes: true, evidence: $"loader: {info.Loader}");
                return;
            }

            if (info.Id == null)
            {
                Add(e, FindingKind.Unreadable, Severity.Warning, Certainty.Confirmed, "Couldn't read this mod's details.", excludes: true);
                return;
            }

            // Origin ships this mod (by its id, or one it provides): a second copy would be a duplicate id.
            var shippedId = FindOrigin(info.Id) != null ? info.Id : info.Provides.FirstOrDefault(p => FindOrigin(p) != null);
            // In a mod profile the player's own copy (for this Minecraft version, another version than Origin's) wins:
            // it is judged like any other mod of theirs (PlayerOverrides decides the launch).
            if (shippedId == info.Id && _platform.PlayerCopiesWin
                && (info.MinecraftRange is not { } fits || fits.IsSatisfiedBy(_platform.McVersion))
                && PlayerOverrides.MayReplace(info.Id, info.Version, FindOrigin(info.Id)!.Version))
                shippedId = null;
            if (shippedId != null)
            {
                var shipped = FindOrigin(shippedId)!;
                // A candidate that is not installed yet only "would" conflict; an installed copy stops the game.
                Add(e, FindingKind.OriginManagedConflict, e.Role == ModRole.ImportCandidate ? Severity.Info : Severity.Blocking,
                    Certainty.Confirmed, shipped.Describe() + ".", excludes: true, related: OriginEntry(shippedId),
                    targetId: shippedId, found: shipped.Version, targetIsOrigin: true,
                    evidence: $"id: {info.Id}");
                return;
            }

            if (info.MinecraftRange is { } range && !range.IsSatisfiedBy(_platform.McVersion))
            {
                Add(e, FindingKind.WrongMinecraft, Severity.Blocking, Certainty.Confirmed,
                    $"Built for Minecraft {range.Display}, not {_platform.McVersion}.", excludes: true,
                    targetId: "minecraft", range: range.Display, found: _platform.McVersion, targetIsOrigin: true,
                    evidence: $"depends: minecraft {range.Display}");
                return;
            }

            var wrongSide = _platform.Side == RunSide.Server ? info.Environment == ModEnvironment.Client
                : info.Environment == ModEnvironment.Server;
            if (wrongSide)
            {
                var only = info.Environment == ModEnvironment.Client ? "client" : "server";
                var here = _platform.Side == RunSide.Server ? "a server" : "the client";
                Add(e, FindingKind.WrongSide, Severity.Warning, Certainty.Confirmed,
                    $"This mod only runs on the {only} side, so it is skipped on {here}.", excludes: true,
                    evidence: $"environment: {only}");
            }
        }

        // 1.8.9: Forge reads mcmod.info only as advice, so what it says about the Minecraft version is Likely.
        private void ClassifyLegacy(ModFile e)
        {
            var info = e.Info;
            if (info.Loader is ModLoader.Fabric or ModLoader.Quilt)
            {
                Add(e, FindingKind.WrongLoader, Severity.Warning, Certainty.Confirmed,
                    $"{_platform.McVersion} runs on Forge in Origin, so Fabric mods can't load there.", excludes: true,
                    evidence: $"loader: {info.Loader}");
            }
            else if (info.Loader == ModLoader.LegacyForge)
            {
                if (info.McVersion != null && info.McVersion != _platform.McVersion)
                    Add(e, FindingKind.WrongMinecraft, Severity.Warning, Certainty.Likely,
                        $"Built for Minecraft {info.McVersion}, not {_platform.McVersion}.", excludes: true,
                        targetId: "minecraft", range: info.McVersion, found: _platform.McVersion, targetIsOrigin: true,
                        evidence: $"mcmod.info mcversion: {info.McVersion}");
            }
            else
            {
                Add(e, FindingKind.WrongLoader, Severity.Warning, Certainty.Confirmed,
                    $"Not a Forge {_platform.McVersion} mod.", excludes: true, evidence: $"loader: {info.Loader}");
            }
        }

        // ------------------------------------------------------------------ phase 2

        private void FindDuplicates()
        {
            var groups = _ordered
                .Where(e => !IsManaged(e) && !_excluded.Contains(e) && e.Id != null)
                .GroupBy(e => e.Id!, StringComparer.Ordinal);
            foreach (var group in groups)
            {
                if (group.Count() < 2) continue;
                var ranked = group
                    .OrderBy(e => e.Enabled ? 0 : 1)
                    .ThenByDescending(e => e.Info.Version, Comparer<string?>.Create(FabricVersion.Compare))
                    .ThenBy(e => e.FileName, StringComparer.OrdinalIgnoreCase)
                    .ToList();
                var keep = ranked[0];
                var shown = DependencyRules.Shown(keep.Info.Version ?? "");
                var keepText = keep.DisplayName + (shown.Length > 0 ? " " + shown : "");
                foreach (var other in ranked.Skip(1))
                {
                    if (_platform.IsLegacyForge)
                    {
                        // Forge 1.8.9 refuses two enabled copies of one mod id; a turned-off twin is just clutter.
                        var severity = other.Enabled && keep.Enabled ? Severity.Blocking : Severity.Info;
                        Add(other, FindingKind.DuplicateId, severity, Certainty.Confirmed,
                            $"Duplicate of {keepText}: only one copy can load.",
                            excludes: true, related: keep, targetId: group.Key, evidence: $"id: {group.Key}");
                        continue;
                    }

                    // Fabric Loader 0.19.3 (ModSolver: ROOT_FORCELOAD "at least one" + UNIQUE_ID "at most one" per id)
                    // loads exactly one jar of the id, the higher version first (ModPrioSorter), and the game starts:
                    // same version or not, it is never a stop (read from the 0.19.3 loader source, 2026-10-25). A real
                    // boot of two versions of one id reached the title screen (crash-plant plant-duplicate-id).
                    var summary = !other.Enabled ? $"Turned off; {keepText} is the copy in use."
                        : FabricVersion.Compare(other.Info.Version, keep.Info.Version) == 0
                            ? $"Second copy of {keepText}: the game loads only one of them."
                            : $"Older copy of {keep.DisplayName}: the game loads {keepText} and ignores this one.";
                    Add(other, FindingKind.DuplicateId, Severity.Info, Certainty.Confirmed, summary,
                        excludes: true, related: keep, targetId: group.Key, evidence: $"id: {group.Key}");
                    if (other.Enabled && keep.Enabled) _alternates.Add(other);
                }
            }
        }

        // ------------------------------------------------------------------ phase 3

        private void CheckKnown(ModFile e)
        {
            if (KnownIncompatible.Check(e.Info, FindOrigin) is not { } reason) return;
            Add(e, FindingKind.KnownIncompatible, Severity.Blocking, Certainty.Likely, reason, excludes: true,
                evidence: "on Origin's list of mods that don't work with what it ships");
        }

        // ------------------------------------------------------------------ phase 4

        private void ResolveDependencies()
        {
            bool changed;
            do
            {
                changed = false;
                var env = new World(this);
                var needs = _alternates.Count == 0 ? env : new World(this, withAlternates: true);
                var nestedHidden = _ordered.Any(m => m.Enabled && !_excluded.Contains(m) && !m.Info.ReadFailed && m.Info.NestedIncomplete);
                var round = new List<(ModFile Mod, List<CompatFinding> Findings)>();

                foreach (var e in _ordered)
                {
                    if (!CanJudge(e)) continue;
                    var findings = new List<CompatFinding>();
                    IEnumerable<DependencyProblem> problems = DependencyRules.Evaluate(e.Info, env, new DependencyOptions(IgnoreIds: MinecraftOnly));
                    if (needs != env) // requirements may be met by a left-out duplicate copy; "breaks" only by what loads
                        problems = DependencyRules.Evaluate(e.Info, needs, new DependencyOptions(IgnoreIds: MinecraftOnly, ConflictChecks: false))
                            .Concat(problems.Where(p => p.Kind == DependencyProblemKind.Breaks));
                    foreach (var p in problems)
                    {
                        if (p.Kind is DependencyProblemKind.Missing or DependencyProblemKind.WrongVersion && nestedHidden)
                        {
                            // Some jar's nested jars could not be read, so the id may well be in there: a guess, not a fact.
                            AdviseUnconfirmed(e, p);
                            continue;
                        }
                        if (p.Kind is DependencyProblemKind.Missing or DependencyProblemKind.WrongVersion or DependencyProblemKind.Breaks)
                            findings.Add(ToFinding(e, p));
                    }
                    findings.AddRange(BreaksFromOrigin(e));
                    if (findings.Count > 0) round.Add((e, findings));
                }

                // The round is judged against the world as it stood at its start, then applied together.
                foreach (var (mod, findings) in round)
                {
                    _findings.AddRange(findings);
                    _excluded.Add(mod);
                    changed = true;
                }
            } while (changed);
        }

        // A requirement that looks unmet, but a jar in the set has nested jars that were not readable (ModInfo.NestedIncomplete).
        // Reported once, as a Likely warning that takes nothing out of the picture, so it can never stop a launch.
        private readonly HashSet<string> _unconfirmed = new(StringComparer.Ordinal);

        private void AdviseUnconfirmed(ModFile e, DependencyProblem p)
        {
            var dep = p.Dependency;
            if (!_unconfirmed.Add(e.Path + "|" + dep.Id)) return;
            _findings.Add(new CompatFinding(
                p.Kind == DependencyProblemKind.Missing ? FindingKind.MissingDependency : FindingKind.WrongVersion,
                Sev(e, Severity.Warning), Certainty.Likely, e, DependencyRules.NeedsText(dep) + ".")
            {
                TargetId = dep.Id,
                Range = dep.Predicate.MatchesAnything ? null : dep.Predicate.Display,
                Found = p.Found,
                Related = p.Providers.FirstOrDefault(),
                Evidence = $"depends: {dep.Id} (not confirmed: some bundled jars could not be read)",
            });
        }

        private CompatFinding ToFinding(ModFile e, DependencyProblem p)
        {
            var dep = p.Dependency;
            var range = dep.Predicate.MatchesAnything ? null : dep.Predicate.Display;
            var shownRange = range ?? "*";
            switch (p.Kind)
            {
                case DependencyProblemKind.Breaks:
                    return new CompatFinding(FindingKind.Breaks, Sev(e, Severity.Blocking), Certainty.Confirmed, e,
                        $"Conflicts with {dep.Id}{(p.Found == null ? "" : " " + DependencyRules.Shown(p.Found))}.")
                    {
                        Excludes = true,
                        Related = p.Providers.FirstOrDefault(),
                        TargetId = dep.Id,
                        Range = range,
                        Found = p.Found,
                        TargetIsOrigin = p.TargetIsOrigin,
                        Evidence = $"breaks: {dep.Id} {shownRange}",
                    };
                default:
                    return new CompatFinding(
                        p.Kind == DependencyProblemKind.Missing ? FindingKind.MissingDependency : FindingKind.WrongVersion,
                        Sev(e, Severity.Blocking), Certainty.Confirmed, e, DependencyRules.NeedsText(dep) + ".")
                    {
                        Excludes = true,
                        Related = p.Providers.FirstOrDefault(),
                        TargetId = dep.Id,
                        Range = range,
                        Found = p.Found,
                        TargetIsOrigin = p.TargetIsOrigin,
                        Evidence = $"depends: {dep.Id} {shownRange}",
                        CausedBy = LeftBehind(dep),
                    };
            }
        }

        // Mods already taken out of the picture that would have answered this requirement: the root cause of a knock-on.
        private IReadOnlyList<ModFile> LeftBehind(ModDependency dep)
        {
            List<ModFile>? hit = null;
            foreach (var other in _excluded)
            {
                if (IsManaged(other) || !other.Enabled) continue;
                foreach (var (id, version) in World.Provided(other.Info))
                {
                    if (id != dep.Id || !dep.IsSatisfiedBy(version)) continue;
                    (hit ??= new List<ModFile>()).Add(other);
                    break;
                }
            }
            return hit?.OrderBy(m => m.FileName, StringComparer.OrdinalIgnoreCase).ToList() ?? (IReadOnlyList<ModFile>)Array.Empty<ModFile>();
        }

        // Origin's own mods may declare "breaks" against a player's mod (Sodium breaks old Iris). The player's mod is
        // the one that gives way, so that is the subject; the declaring Origin mod is Related.
        private IEnumerable<CompatFinding> BreaksFromOrigin(ModFile player)
        {
            if (_origin.Count == 0) yield break;
            foreach (var declarer in _ordered)
            {
                if (!IsManaged(declarer) || !declarer.Enabled || declarer.Info.Breaks.Count == 0) continue;
                foreach (var brk in declarer.Info.Breaks)
                {
                    foreach (var (id, version) in World.Provided(player.Info))
                    {
                        if (id != brk.Id || !brk.IsSatisfiedBy(version)) continue;
                        yield return new CompatFinding(FindingKind.Breaks, Sev(player, Severity.Blocking), Certainty.Confirmed, player,
                            $"Conflicts with {declarer.DisplayName}{(declarer.Info.Version == null ? "" : " " + DependencyRules.Shown(declarer.Info.Version))}, which Origin includes.")
                        {
                            Excludes = true,
                            Related = declarer,
                            TargetId = declarer.Id,
                            Range = brk.Predicate.MatchesAnything ? null : brk.Predicate.Display,
                            Found = version,
                            TargetIsOrigin = true,
                            Evidence = $"{declarer.Id} breaks: {brk.Id} {brk.Predicate.Display}",
                        };
                    }
                }
            }
        }

        // ------------------------------------------------------------------ phase 5

        private void AdviseConflicts()
        {
            var env = new World(this);
            foreach (var e in _ordered)
            {
                if (!CanJudge(e)) continue;
                foreach (var p in DependencyRules.Evaluate(e.Info, env, new DependencyOptions(IgnoreIds: MinecraftOnly)))
                {
                    var dep = p.Dependency;
                    var range = dep.Predicate.MatchesAnything ? null : dep.Predicate.Display;
                    if (p.Kind == DependencyProblemKind.Conflicts)
                        _findings.Add(new CompatFinding(FindingKind.Conflicts, Sev(e, Severity.Warning), Certainty.Confirmed, e,
                            $"May not work well with {dep.Id}{(p.Found == null ? "" : " " + DependencyRules.Shown(p.Found))}.")
                        {
                            Related = p.Providers.FirstOrDefault(), TargetId = dep.Id, Range = range, Found = p.Found,
                            TargetIsOrigin = p.TargetIsOrigin, Evidence = $"conflicts: {dep.Id} {range ?? "*"}",
                        });
                    else if (p.Kind == DependencyProblemKind.MissingRecommended)
                        _findings.Add(new CompatFinding(FindingKind.MissingRecommended, Severity.Info, Certainty.Confirmed, e,
                            $"Works best with {dep.Id}{(range == null ? "" : " " + range)}, which isn't installed.")
                        {
                            TargetId = dep.Id, Range = range, Evidence = $"recommends: {dep.Id} {range ?? "*"}",
                        });
                }
            }
        }

        // 1.8.9: mcmod.info "requiredMods" is advice (FML enforces the @Mod annotation's own list), so a missing one is
        // Likely and never takes the mod out of the picture.
        private void AdviseLegacyDependencies()
        {
            var env = new World(this);
            foreach (var e in _ordered)
            {
                if (!CanJudge(e)) continue;
                foreach (var p in DependencyRules.Evaluate(e.Info, env,
                             new DependencyOptions(IgnoreIds: LegacyPlatformIds, ConflictChecks: false)))
                {
                    var dep = p.Dependency;
                    _findings.Add(new CompatFinding(
                        p.Kind == DependencyProblemKind.Missing ? FindingKind.MissingDependency : FindingKind.WrongVersion,
                        Sev(e, Severity.Warning), Certainty.Likely, e, DependencyRules.NeedsText(dep) + ".")
                    {
                        TargetId = dep.Id,
                        Range = dep.Predicate.MatchesAnything ? null : dep.Predicate.Display,
                        Found = p.Found,
                        Related = p.Providers.FirstOrDefault(),
                        Evidence = $"mcmod.info requiredMods: {dep.Id}",
                    });
                }
            }
        }

        // ------------------------------------------------------------------ the world a mod is held against

        // Every id that would be there: the platform, Origin's mods, then the player's mods that are on and still in
        // the picture (own id, what they provide, what they nest). Rebuilt each round because mods leave it.
        private sealed class World : IProvidedMods
        {
            private readonly Dictionary<string, List<ProvidedVersion>> _byId = new(StringComparer.Ordinal);
            private readonly CompatPlatform _platform;

            public World(Run run, bool withAlternates = false)
            {
                _platform = run._platform;
                Put("minecraft", _platform.McVersion, null, true);
                Put("java", string.IsNullOrEmpty(_platform.JavaVersion) ? null : _platform.JavaVersion, null, true); // unknown (pre-launch): meets any range
                Put("fabricloader", _platform.LoaderVersion, null, true); // newest at install time: unknown, meets any range
                // What the loader jar itself carries (mixinextras): a mod may depend on it without bundling it.
                if (_platform.Loader == LoaderFamily.Fabric)
                    foreach (var (id, version) in FabricLoaderMods.For(_platform.LoaderVersion)) Put(id, version, null, true);
                if (_platform.BuiltIns != null)
                    foreach (var mod in _platform.BuiltIns.Mods) Put(mod.Id, mod.Version, null, true);

                foreach (var role in new[] { true, false })
                {
                    foreach (var e in run._ordered)
                    {
                        if (IsManaged(e) != role || !e.Enabled || run._excluded.Contains(e) || e.Info.ReadFailed) continue;
                        foreach (var (id, version) in Provided(e.Info)) Put(id, version, e, role);
                    }
                }
                // After everything that loads, so the copy the loader prefers stays first ("best first").
                if (withAlternates)
                    foreach (var e in run._ordered)
                        if (run._alternates.Contains(e) && !e.Info.ReadFailed)
                            foreach (var (id, version) in Provided(e.Info)) Put(id, version, e, false);
            }

            private void Put(string id, string? version, ModFile? source, bool isOrigin)
            {
                if (!_byId.TryGetValue(id, out var list)) _byId[id] = list = new List<ProvidedVersion>(1);
                list.Add(new ProvidedVersion(version, source, isOrigin));
            }

            public bool TryGetVersions(string id, out IReadOnlyList<ProvidedVersion> versions)
            {
                if (_byId.TryGetValue(id, out var list)) { versions = list; return true; }
                // Fabric API's modules (fabric-resource-loader-v0 ...) all answer to the one bundled Fabric API.
                if (_platform.BuiltIns?.Find(id) is { } built)
                {
                    versions = new[] { new ProvidedVersion(built.Version, null, true) };
                    return true;
                }
                versions = Array.Empty<ProvidedVersion>();
                return false;
            }

            /// <summary>Every (id, version) a jar answers to: its own, what it provides, what it nests (and they provide).</summary>
            public static IEnumerable<(string Id, string? Version)> Provided(ModInfo info)
            {
                if (info.Id != null) yield return (info.Id, info.Version);
                foreach (var id in info.Provides) yield return (id, info.VersionProvided(id));
                foreach (var nested in info.Nested)
                {
                    yield return (nested.Id, nested.Version);
                    foreach (var id in nested.Provides) yield return (id, nested.Version);
                }
            }
        }
    }
}
