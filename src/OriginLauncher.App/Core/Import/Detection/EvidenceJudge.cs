namespace OriginLauncher.App.Core.Import.Detection;

/// <summary>The best answer for one setup and whether it is certain.</summary>
public sealed record Judgement(
    string? McVersion, LoaderKind Loader, string? LoaderVersion, Confidence Confidence,
    bool McCertain, bool LoaderCertain, string? Reason)
{
    public bool Certain => McCertain && LoaderCertain;
}

// Cross-checks a setup's version and loader. "Accuracy is key": importing a setup
// into the wrong version imports the wrong thing, so when the signals don't line
// up the answer is "not sure" (VersionCertain = false + a plain reason) and the
// player is asked. The best answer is still filled in so the question can be
// preselected.
//
// The signals:
//   M  launcher metadata (one per profile that uses the folder): Prism mmc-pack,
//      CurseForge gameVersion/baseModLoader, official lastVersionId (+ version json).
//      "Explicit" unless it is an official latest-release/latest-snapshot profile.
//      Its time = the profile's lastUsed (official) or the metadata file's mtime;
//      "played" = an official profile with a real lastUsed (Prism/CurseForge always have a time).
//   R  what the folder last RAN: logs/latest.log's version (time = its mtime), else
//      options.txt's `version:` DataVersion mapped to a Minecraft version (time =
//      its mtime). Both are written by the game itself, so they're independent of
//      any launcher. If log and options.txt name different versions -> not certain.
//   L  the log's loader line (Fabric / Quilt / Forge / NeoForge).
//   D  the mods: a version "fits" when >= 80 % of the jars declaring depends.minecraft accept it.
//
// MINECRAFT VERSION RULES
//   V0  Vanilla records never speak for modded folders. When the folder's log
//       names a loader or most of its jars are for one, records with no loader
//       (every official latest-release/latest-snapshot profile, a vanilla Prism
//       pack, a plain "1.21.4" profile) are set aside and R + D decide — unless
//       an explicit loader-bearing record exists, which then decides as usual.
//       An EXPLICIT vanilla record set aside this way leaves the loader not
//       certain; latest-* profiles are only aliases and cost no certainty.
//       Recency among launcher records only matters when there's no loader evidence.
//       (Proven on a real .minecraft, 2026-10-18: two latest-* profiles with a
//       recent lastUsed, a Fabric 1.21.1 log and Fabric 1.21.1 mods came out as
//       "26.4-snapshot-3, vanilla, Exact" before this rule.)
//   V1  Several metadata statements (profiles sharing the folder): weigh only
//       PLAYED ones if any (a never-played profile never beats a played one,
//       explicit or not), else all. If they name different versions, the one
//       matching R (or that D fits) is used; the jar loader / log loader only
//       break ties between records of the SAME version. Nothing tells them apart
//       -> not certain, the most recently used is preselected (so a "Latest
//       release" played yesterday beats an old Fabric profile, but unsure).
//   V2  M and R agree -> certain.
//   V3  M and R disagree:
//         R is newer than M's time, or M is a non-explicit "latest" record (its
//         lastUsed never outranks what the game wrote)  -> not certain, R preferred;
//         otherwise -> not certain, M preferred, EXCEPT when M is strictly newer
//         than R (R is simply stale: the launcher was changed after the last
//         play) AND D fits M but not R -> certain M.
//   V4  Only M (no R): certain when M is explicit; a non-explicit "latest" profile
//       is certain only when D fits it.
//   V5  No M: R alone -> not certain; R with D fitting it -> certain;
//       R and D disagree -> not certain, D's 80 % consensus is preferred (the
//       mods are what gets imported), R when the mods have no consensus;
//       D alone -> not certain (its best version is the guess).
//   V6  Whatever was chosen, if the mods declare ranges and D does NOT fit it
//       (>= 80 % reject it, or the mods are split) -> not certain.
//
// LOADER RULES (same idea)
//   W1  The loader comes from the same place as the version: M's loader, unless
//       V3 preferred R (then the log's loader when it names one).
//   W2  M and L both name a loader and they differ -> not certain (L preferred
//       only when the log is newer than M).
//   W3  M names no loader (CurseForge baseModLoader null): L's loader, certain only
//       when the jars' majority agrees; else the jars' majority, not certain.
//   W4  More than half of the loader-typed jars can't run on the chosen loader
//       (Forge jars under Fabric, Fabric jars under vanilla) -> not certain.
//
// Confidence: Exact = the version AND loader came from the launcher's own record
// AND both are certain; anything else is Inferred (never Exact while unsure).
public static class EvidenceJudge
{
    public static Judgement Judge(IReadOnlyList<VersionEvidence> metadata, LogEvidence? log, ModsEvidence? mods, OptionsEvidence? options = null)
    {
        var reasons = new List<string>();

        // ---- R: what the folder last ran (log first, else options.txt)
        string? ranMc = null;
        DateTimeOffset? ranTime = null;
        var ranFrom = "the game's last log";
        if (log?.McVersion != null) { ranMc = log.McVersion; ranTime = log.ModifiedUtc; }
        else if (options?.McVersion != null) { ranMc = options.McVersion; ranTime = options.ModifiedUtc; ranFrom = "the game's settings file"; }
        var ranConflict = log?.McVersion != null && options?.McVersion != null && !Same(log.McVersion, options.McVersion);
        if (ranConflict)
            reasons.Add($"The game's log says {log!.McVersion} but its settings were last saved by Minecraft {options!.McVersion}.");

        // ---- V0
        var folderLoader = log?.Loader is { } fl && fl != LoaderKind.None ? fl : mods?.MajorityLoader;
        var vanillaOverridden = false;
        if (folderLoader is { } folderLoaderKind && folderLoaderKind != LoaderKind.None            && metadata.Any(e => e.Source == EvidenceSource.LauncherMetadata && e.Loader == LoaderKind.None))
        {
            var loaderBearing = metadata.Where(e => e.Loader != LoaderKind.None).ToList();
            vanillaOverridden = loaderBearing.Count == 0
                && metadata.Any(e => e.Source == EvidenceSource.LauncherMetadata && e.Loader == LoaderKind.None && e.Explicit);
            metadata = loaderBearing;
            if (vanillaOverridden)
                reasons.Add(log?.Loader is { } ll && ll != LoaderKind.None
                    ? $"The launcher says vanilla but the game last ran {Name(ll)}."
                    : $"The launcher says vanilla but most mods here are {Name(folderLoaderKind)} mods.");
        }

        var meta = PickMetadata(metadata, ranMc, log, mods, out var mcAmbiguous, out var loaderAmbiguous);

        string? mc = null;
        var mcCertain = false;
        var fromMeta = false;
        var ranWon = false;

        if (meta?.McVersion is { } m)
        {
            fromMeta = true;
            mc = m;
            if (ranMc != null && !Same(ranMc, m))
            {
                // V3 (a non-explicit "latest" record's lastUsed never outranks what the game wrote)
                if (!meta.Explicit || (meta.ObservedUtc is { } t && ranTime > t))
                {
                    mc = ranMc;
                    fromMeta = false;
                    ranWon = true;
                    reasons.Add($"The launcher says {m} but the game last ran {ranMc}.");
                }
                else if (meta.ObservedUtc is { } t2 && t2 > ranTime && mods != null && mods.Fits(m) && !mods.Fits(ranMc))
                {
                    mcCertain = true; // stale R, explained by time, mods confirm the launcher
                }
                else
                {
                    reasons.Add($"The launcher says {m} but the game last ran {ranMc}.");
                }
            }
            else if (ranMc != null)
            {
                mcCertain = true; // V2
            }
            else if (meta.Explicit)
            {
                mcCertain = true; // V4
            }
            else if (mods != null && mods.Fits(m))
            {
                mcCertain = true; // V4, latest profile confirmed by the mods
            }
            else
            {
                reasons.Add($"The launcher's \"latest\" profile follows the newest version, so Origin can't tell if this folder last ran {m}.");
            }

            if (mcAmbiguous && !(ranMc != null && Same(ranMc, mc)))
            {
                mcCertain = false; // V1: nothing told the profiles apart
                reasons.Add("The launcher profiles that use this folder name different versions.");
            }
        }
        else if (ranMc != null)
        {
            mc = ranMc; // V5
            if (mods != null && mods.Fits(ranMc)) mcCertain = true;
            else if (mods?.ConsensusVersion is { } consensus)
            {
                mc = consensus; // R and mods disagree: the mods are what gets imported
                reasons.Add($"The game last ran {ranMc} but most mods here are built for Minecraft {consensus}.");
            }
            else reasons.Add($"Guessed {ranMc} from {ranFrom}; nothing else confirms it.");
        }
        else if (mods?.BestVersion is { } best)
        {
            mc = best; // V5
            reasons.Add($"Guessed {best} from the mods; there's no launcher record or game log.");
        }

        if (ranConflict) mcCertain = false;

        // V6
        if (mc != null && mods is { HasRanges: true } && !mods.Fits(mc))
        {
            mcCertain = false;
            if (mods.Rejects(mc))
                reasons.Add(mods.BestVersion is { } b && !Same(b, mc)
                    ? $"Most mods here are built for Minecraft {b}, not {mc}."
                    : $"Most mods here aren't built for Minecraft {mc}.");
            else
                reasons.Add($"Only some of the mods here are built for Minecraft {mc}.");
        }

        // ---- loader
        var metaLoader = meta?.Loader;
        var logLoader = log?.Loader;
        LoaderKind loader;
        string? loaderVersion;
        var loaderCertain = false;
        var loaderFromLog = false;

        if (ranWon && logLoader != null)
        {
            loader = logLoader.Value; // W1
            loaderVersion = log!.LoaderVersion;
            loaderCertain = metaLoader == null || metaLoader == logLoader;
            loaderFromLog = true;
        }
        else if (metaLoader != null)
        {
            loader = metaLoader.Value;
            loaderVersion = meta!.LoaderVersion;
            loaderCertain = true;
            if (logLoader != null && logLoader != metaLoader)
            {
                // W2
                loaderCertain = false;
                reasons.Add($"The launcher says {Name(metaLoader.Value)} but the game last ran {Name(logLoader.Value)}.");
                if (!meta.Explicit || (meta.ObservedUtc is { } t && log!.ModifiedUtc > t))
                {
                    loader = logLoader.Value;
                    loaderVersion = log!.LoaderVersion;
                    loaderFromLog = true;
                }
            }
        }
        else if (logLoader != null)
        {
            loader = logLoader.Value; // W3
            loaderVersion = log!.LoaderVersion;
            loaderCertain = mods?.MajorityLoader is { } jars && ModsEvidence.Compatible(loader, jars);
            if (!loaderCertain)
                reasons.Add(metadata.Count > 0
                    ? $"The launcher didn't record a mod loader; {Name(loader)} is a guess from the game's last log."
                    : $"{Name(loader)} is a guess from the game's last log.");
        }
        else if (mods?.MajorityLoader is { } jarLoader)
        {
            loader = jarLoader; // W3
            loaderVersion = null;
            reasons.Add($"{Name(loader)} is a guess from the mods.");
        }
        else
        {
            loader = LoaderKind.None;
            loaderVersion = null;
            if (mc != null) reasons.Add("Origin couldn't tell which mod loader this uses.");
        }

        // V1 for the loader: records that agree on the version but not the loader, and nothing told them apart
        if (loaderAmbiguous && !ranWon && !(logLoader != null && logLoader == loader))
        {
            loaderCertain = false;
            reasons.Add("The launcher profiles that use this folder name different mod loaders.");
        }

        // W4
        if (mods != null && loaderCertain && mods.MostlyIncompatibleWith(loader))
        {
            loaderCertain = false;
            reasons.Add(loader == LoaderKind.None
                ? $"This setup has no mod loader, but its mods folder has {Name(mods.MajorityLoader ?? LoaderKind.Unknown)} mods."
                : $"This setup is set to {Name(loader)}, but most mods here are {Name(mods.MajorityLoader ?? LoaderKind.Unknown)} mods.");
        }

        if (vanillaOverridden) loaderCertain = false; // V0: an explicit vanilla record contradicts the folder's loader

        if (mc == null)
        {
            mcCertain = false;
            loaderCertain = false;
        }

        // Exact only when the launcher's own record gave both answers AND both are certain (never Exact while unsure).
        var confidence = fromMeta && metaLoader != null && !ranWon && !loaderFromLog && mcCertain && loaderCertain
            ? Confidence.Exact : Confidence.Inferred;
        var certain = mcCertain && loaderCertain;
        var reason = certain ? null
            : mc == null ? "Origin couldn't tell which Minecraft version this is."
            : string.Join(" ", reasons.Distinct());
        if (!certain && string.IsNullOrWhiteSpace(reason)) reason = "Origin isn't sure which version this is.";
        return new Judgement(mc, loader, loaderVersion, confidence, mcCertain, loaderCertain, reason);
    }

    /// <summary>V1: which metadata statement to weigh when several profiles share a folder.</summary>
    private static VersionEvidence? PickMetadata(IReadOnlyList<VersionEvidence> metadata, string? ranMc, LogEvidence? log, ModsEvidence? mods,
        out bool mcAmbiguous, out bool loaderAmbiguous)
    {
        mcAmbiguous = loaderAmbiguous = false;
        var usable = metadata.Where(e => e.Source == EvidenceSource.LauncherMetadata && (e.McVersion != null || e.Loader != null)).ToList();
        if (usable.Count == 0) return null;

        // A never-played profile never beats a played one, explicit or not.
        var pool = usable.Where(e => e.ObservedUtc != null).ToList();
        if (pool.Count == 0) pool = usable;

        // Most recent first; OrderBy is stable, so the first listed wins ties.
        var recent = pool.OrderByDescending(e => e.ObservedUtc ?? DateTimeOffset.MinValue).ToList();
        if (recent.Select(Key).Distinct(StringComparer.Ordinal).Count() <= 1) return recent[0];

        // Only version evidence (R, D) may decide between different versions...
        int McScore(VersionEvidence e) =>
            (ranMc != null && e.McVersion != null && Same(ranMc, e.McVersion) ? 2 : 0) + (mods != null && mods.Fits(e.McVersion) ? 1 : 0);
        var topMc = recent.Max(McScore);
        var byMc = recent.Where(e => McScore(e) == topMc).ToList();
        mcAmbiguous = byMc.Select(e => e.McVersion ?? "?").Distinct(StringComparer.OrdinalIgnoreCase).Count() > 1;

        // ...and loader evidence (the log's loader, the jars') only between records of the same version.
        int LoaderScore(VersionEvidence e) =>
            (log?.Loader != null && e.Loader == log.Loader ? 2 : 0)
            + (mods?.MajorityLoader is { } j && e.Loader is { } l && ModsEvidence.Compatible(l, j) ? 1 : 0);
        var topLoader = byMc.Max(LoaderScore);
        var best = byMc.Where(e => LoaderScore(e) == topLoader).ToList();
        loaderAmbiguous = best.Select(e => e.Loader?.ToString() ?? "?").Distinct(StringComparer.Ordinal).Count() > 1;
        return best[0];
    }

    private static string Key(VersionEvidence e) => (e.McVersion ?? "?") + "|" + (e.Loader?.ToString() ?? "?");

    private static bool Same(string a, string? b) => string.Equals(a.Trim(), b?.Trim(), StringComparison.OrdinalIgnoreCase);

    private static string Name(LoaderKind loader) => loader switch
    {
        LoaderKind.None => "vanilla",
        LoaderKind.NeoForge => "NeoForge",
        LoaderKind.Unknown => "other",
        _ => loader.ToString(),
    };
}
