using System.IO;

namespace OriginLauncher.App.Core.Import.Detection;

/// <summary>
/// One launcher entry before it is judged: the instance as the launcher describes it (name, folder, recency),
/// plus the launcher's own version statement, if it made one.
/// </summary>
public sealed record SetupMember(DetectedInstance Instance)
{
    /// <summary>The launcher's version/loader statements for this entry (empty when it has none, e.g. Modrinth).</summary>
    public IReadOnlyList<VersionEvidence> Metadata { get; init; } = Array.Empty<VersionEvidence>();

    /// <summary>An official latest-release / latest-snapshot profile: only ever an alias when a custom profile shares its folder.</summary>
    public bool IsLatestProfile { get; init; }

    /// <summary>The launcher's data is in a format this code refuses to guess at (Prism formatVersion 2): shown "Couldn't read details" as is.</summary>
    public bool NoInference { get; init; }
}

/// <summary>Per-detector switches.</summary>
public sealed record ResolveOptions
{
    /// <summary>Official launcher: a profile whose folder has no game data and that was never played is not a setup (debug line only).</summary>
    public bool DropEmptyNeverPlayed { get; init; }

    /// <summary>Official launcher: a profile pointing at a folder that isn't there says so.</summary>
    public bool FlagMissingFolder { get; init; }
}

// Turns launcher entries into setups. The rules:
//   * ONE setup per game folder. Entries whose GameDir is the same full path
//     (Path.GetFullPath, case-insensitive on Windows, links not resolved) are
//     collapsed — within one launcher (two official profiles on the default
//     .minecraft) and across launchers (a later detector's entry merges into the
//     setup an earlier one emitted). The mods/settings in one folder can't belong
//     to two versions, so a bulk import must never see it twice.
//   * The setup's version is the evidence winner (EvidenceJudge: every profile's
//     record + the folder's log + its mods).
//   * Its name is the launcher's name for it: the most recently used entry; a
//     "Latest release"/"Latest snapshot" profile only when no custom profile
//     uses the folder. The other entries' names become Aliases.
//   * Recency is the newest of the entries; playtime / launch count the largest
//     (never summed: it is one folder); pinned if any entry is.
//   * Official only: never-played profiles on a folder with no game data
//     (no mods/, options.txt or saves/) are dropped with a debug line.
public static class SetupResolver
{
    /// <summary>Judges one entry on its own (Browse, or a launcher's single-instance reader).</summary>
    public static DetectedInstance ResolveOne(SetupMember member, SourceFs fs, CancellationToken ct, ResolveOptions? options = null)
    {
        var folder = member.NoInference ? FolderEvidence.None : FolderEvidence.For(member.Instance, fs, ct);
        return Resolve(new[] { member }, folder, options ?? new ResolveOptions());
    }

    /// <summary>
    /// Collapses <paramref name="members"/> by game folder, judges each folder once, and adds the setups to
    /// <paramref name="into"/> — merging into a setup already there for the same folder (an earlier detector's).
    /// </summary>
    public static void AddSetups(ScanResult into, IEnumerable<SetupMember> members, SourceFs fs, CancellationToken ct, ResolveOptions? options = null)
    {
        options ??= new ResolveOptions();
        var groups = new List<(string Key, List<SetupMember> Members)>();
        var byKey = new Dictionary<string, int>(SetupKey.Comparer);
        foreach (var member in members)
        {
            var key = SetupKey.Of(member.Instance);
            if (byKey.TryGetValue(key, out var index)) groups[index].Members.Add(member);
            else
            {
                byKey[key] = groups.Count;
                groups.Add((key, new List<SetupMember> { member }));
            }
        }
        if (groups.Count == 0) return;

        var existing = new Dictionary<string, int>(SetupKey.Comparer);
        for (var i = 0; i < into.Instances.Count; i++) existing.TryAdd(SetupKey.Of(into.Instances[i]), i);

        foreach (var (key, group) in groups)
        {
            ct.ThrowIfCancellationRequested();
            var all = new List<SetupMember>();
            existing.TryGetValue(key, out var at);
            var merging = existing.ContainsKey(key);
            if (merging) all.Add(FromSetup(into.Instances[at]));
            all.AddRange(group);

            var folder = all.All(m => m.NoInference) ? FolderEvidence.None : FolderEvidence.For(group[0].Instance, fs, ct);
            if (!merging && options.DropEmptyNeverPlayed && !folder.HasGameData && group.All(m => m.Instance.LastPlayed == null))
            {
                into.Debug.Add($"import scan: left out {group.Count} never-played {group[0].Instance.Kind} profile(s) with no game data: "
                    + string.Join(", ", group.Select(m => m.Instance.Name)));
                continue;
            }

            var setup = Resolve(all, folder, options);
            if (merging)
            {
                into.Instances[at] = setup;
                into.Debug.Add($"import scan: {group[0].Instance.Kind} entry \"{group[0].Instance.Name}\" shares a folder with \"{setup.Name}\"; merged into one setup");
            }
            else
            {
                existing[key] = into.Instances.Count;
                into.Instances.Add(setup);
            }
        }
    }

    // An already-emitted setup as a member, so a later detector's entry for the same folder can merge into it.
    private static SetupMember FromSetup(DetectedInstance setup) => new(setup)
    {
        Metadata = setup.Evidence.Where(e => e.Source == EvidenceSource.LauncherMetadata).ToList(),
        NoInference = setup.Problem == DetectedInstance.CouldntRead && setup.Evidence.Count == 0,
    };

    private static DetectedInstance Resolve(IReadOnlyList<SetupMember> members, FolderEvidence folder, ResolveOptions options)
    {
        var metadata = members.SelectMany(m => m.Metadata).ToList();
        var noInference = members.All(m => m.NoInference) || (metadata.Count == 0 && members.Any(m => m.NoInference));
        var verdict = noInference ? null : EvidenceJudge.Judge(metadata, folder.Log, folder.Mods, folder.Options);

        var primary = NameWinner(members, verdict);

        // A folder only latest-* profiles use has no name of its own: "Latest snapshot" would describe a version
        // the launcher follows, not this folder. A plain, stable name instead; the profiles become aliases.
        var name = members.All(m => m.IsLatestProfile)
            ? $"Minecraft Launcher ({FolderLeaf(primary.Instance.GameDir)})"
            : primary.Instance.Name;
        var aliases = members
            .SelectMany(m => m.Instance.Aliases.Prepend(m.Instance.Name))
            .Where(n => !string.IsNullOrWhiteSpace(n) && !string.Equals(n, name, StringComparison.OrdinalIgnoreCase))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();

        var setup = primary.Instance with
        {
            Name = name,
            Aliases = aliases,
            LastPlayed = members.Max(m => m.Instance.LastPlayed),
            PlaytimeSeconds = members.Max(m => m.Instance.PlaytimeSeconds),
            LaunchCount = members.Max(m => m.Instance.LaunchCount),
            Pinned = members.Any(m => m.Instance.Pinned),
        };

        if (verdict == null)
        {
            return setup with
            {
                Problem = primary.Instance.Problem ?? DetectedInstance.CouldntRead,
                VersionCertain = false, LoaderCertain = false, Confidence = Confidence.Inferred,
                UncertainReason = "Origin couldn't read this setup's details.",
                Evidence = metadata,
            };
        }

        var evidence = new List<VersionEvidence>(metadata);
        if (folder.Log != null) evidence.Add(folder.Log.ToEvidence());
        if (folder.Mods != null) evidence.Add(folder.Mods.ToEvidence());
        if (folder.Options != null) evidence.Add(folder.Options.ToEvidence());

        setup = setup with
        {
            McVersion = verdict.McVersion,
            Loader = verdict.Loader,
            LoaderVersion = verdict.LoaderVersion,
            Confidence = verdict.Confidence,
            VersionCertain = verdict.Certain,
            LoaderCertain = verdict.LoaderCertain,
            UncertainReason = verdict.Reason,
            Evidence = evidence,
            Problem = verdict.McVersion == null ? DetectedInstance.CouldntRead : null,
        };
        if (options.FlagMissingFolder && setup.Problem == null && !Directory.Exists(setup.GameDir))
            setup = setup with { Problem = "This profile's game folder isn't there" };
        return setup;
    }

    private static string FolderLeaf(string gameDir)
    {
        var leaf = Path.GetFileName(Path.TrimEndingDirectorySeparator(gameDir));
        return string.IsNullOrWhiteSpace(leaf) ? ".minecraft" : leaf;
    }

    // The most recently used entry; "latest" profiles only when nothing else uses the folder. When none of them
    // was ever played, the one whose record matches the evidence winner; then the first listed.
    private static SetupMember NameWinner(IReadOnlyList<SetupMember> members, Judgement? verdict)
    {
        var pool = members.Where(m => !m.IsLatestProfile).ToList();
        if (pool.Count == 0) pool = members.ToList();
        if (pool.Any(m => m.Instance.LastPlayed != null))
            return pool.OrderByDescending(m => m.Instance.LastPlayed ?? DateTimeOffset.MinValue).First(); // OrderBy is stable
        if (verdict?.McVersion != null
            && pool.FirstOrDefault(m => m.Metadata.Any(e =>
                string.Equals(e.McVersion, verdict.McVersion, StringComparison.OrdinalIgnoreCase) && (e.Loader ?? verdict.Loader) == verdict.Loader)) is { } match)
            return match;
        return pool[0];
    }
}
