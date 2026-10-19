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
//      Its time = the profile's lastUsed (official) or the metadata file's mtime.
//   L  logs/latest.log: what the game last ran; its time = the log's mtime.
//   D  the mods: a version "fits" when >= 80 % of the jars declaring depends.minecraft accept it.
//
// MINECRAFT VERSION RULES
//   V1  Several metadata statements (profiles sharing the folder): weigh only
//       explicit ones of played profiles if any, else explicit ones, else all.
//       If they disagree, the one matching L (then D, then the jar loader) is
//       used; if nothing tells them apart -> not certain, most recent used.
//   V2  M and L agree -> certain.
//   V3  M and L disagree:
//         L is newer than M's time  -> not certain, L's version is preferred;
//         otherwise                 -> not certain, M's version is preferred,
//         EXCEPT when M is strictly newer than L (the log is simply stale: the
//         launcher was changed after the last play) AND D fits M but not L
//         -> certain M (two signals agree and the odd one out is explained).
//   V4  Only M (no L): certain when M is explicit; a non-explicit "latest" profile
//       is certain only when D fits it.
//   V5  No M: L alone -> not certain; L with D fitting it -> certain;
//       D alone -> not certain (its best version is the guess).
//   V6  Whatever was chosen, if the mods declare ranges and D does NOT fit it
//       (>= 80 % reject it, or the mods are split) -> not certain.
//
// LOADER RULES (same idea)
//   W1  The loader comes from the same place as the version: M's loader, unless
//       V3 preferred L (then L's loader when it names one).
//   W2  M and L both name a loader and they differ -> not certain (L preferred
//       only when L is newer than M).
//   W3  M names no loader (CurseForge baseModLoader null): L's loader, certain only
//       when the jars' majority agrees; else the jars' majority, not certain.
//   W4  More than half of the loader-typed jars can't run on the chosen loader
//       (Forge jars under Fabric, Fabric jars under vanilla) -> not certain.
//
// Confidence stays what it always meant: Exact = the version AND loader came
// from the launcher's own record; Inferred = from the log or the mods.
public static class EvidenceJudge
{
    public static Judgement Judge(IReadOnlyList<VersionEvidence> metadata, LogEvidence? log, ModsEvidence? mods)
    {
        var reasons = new List<string>();
        var meta = PickMetadata(metadata, log, mods, out var mcAmbiguous, out var loaderAmbiguous);
        var logMc = log?.McVersion;

        string? mc = null;
        var mcCertain = false;
        var fromMeta = false;
        var logWon = false;

        if (meta?.McVersion is { } m)
        {
            fromMeta = true;
            mc = m;
            if (logMc != null && !Same(logMc, m))
            {
                // V3
                if (meta.ObservedUtc is { } t && log!.ModifiedUtc > t)
                {
                    mc = logMc;
                    fromMeta = false;
                    logWon = true;
                    reasons.Add($"The launcher says {m} but the game last ran {logMc}.");
                }
                else if (meta.ObservedUtc is { } t2 && t2 > log!.ModifiedUtc && mods != null && mods.Fits(m) && !mods.Fits(logMc))
                {
                    mcCertain = true; // stale log, explained by time, mods confirm the launcher
                }
                else
                {
                    reasons.Add($"The launcher says {m} but the game last ran {logMc}.");
                }
            }
            else if (logMc != null)
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

            if (mcAmbiguous && !(logMc != null && Same(logMc, mc)))
            {
                mcCertain = false; // V1: nothing told the profiles apart
                reasons.Add("The launcher profiles that use this folder name different versions.");
            }
        }
        else if (logMc != null)
        {
            mc = logMc; // V5
            if (mods != null && mods.Fits(logMc)) mcCertain = true;
            else reasons.Add($"Guessed {logMc} from the game's last log; nothing else confirms it.");
        }
        else if (mods?.BestVersion is { } best)
        {
            mc = best; // V5
            reasons.Add($"Guessed {best} from the mods; there's no launcher record or game log.");
        }

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

        if (logWon && logLoader != null)
        {
            loader = logLoader.Value; // W1
            loaderVersion = log!.LoaderVersion;
            loaderCertain = metaLoader == null || metaLoader == logLoader;
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
                if (meta.ObservedUtc is { } t && log!.ModifiedUtc > t)
                {
                    loader = logLoader.Value;
                    loaderVersion = log.LoaderVersion;
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

        // V1 for the loader: profiles that agree on the version but not the loader, and nothing told them apart
        if (loaderAmbiguous && !logWon && !(logLoader != null && logLoader == loader))
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

        if (mc == null)
        {
            mcCertain = false;
            loaderCertain = false;
        }

        var confidence = fromMeta && metaLoader != null && !logWon && !loaderFromLog ? Confidence.Exact : Confidence.Inferred;
        var certain = mcCertain && loaderCertain;
        var reason = certain ? null
            : mc == null ? "Origin couldn't tell which Minecraft version this is."
            : string.Join(" ", reasons.Distinct());
        if (!certain && string.IsNullOrWhiteSpace(reason)) reason = "Origin isn't sure which version this is.";
        return new Judgement(mc, loader, loaderVersion, confidence, mcCertain, loaderCertain, reason);
    }

    /// <summary>V1: which metadata statement to weigh when several profiles share a folder.</summary>
    private static VersionEvidence? PickMetadata(IReadOnlyList<VersionEvidence> metadata, LogEvidence? log, ModsEvidence? mods,
        out bool mcAmbiguous, out bool loaderAmbiguous)
    {
        mcAmbiguous = loaderAmbiguous = false;
        var usable = metadata.Where(e => e.Source == EvidenceSource.LauncherMetadata && (e.McVersion != null || e.Loader != null)).ToList();
        if (usable.Count == 0) return null;

        var pool = usable.Where(e => e.Explicit && e.ObservedUtc != null).ToList();
        if (pool.Count == 0) pool = usable.Where(e => e.Explicit).ToList();
        if (pool.Count == 0) pool = usable;

        var recent = pool.OrderByDescending(e => e.ObservedUtc ?? DateTimeOffset.MinValue).ToList(); // stable: first-listed wins ties
        if (pool.Select(Key).Distinct(StringComparer.Ordinal).Count() <= 1) return recent[0];

        int Score(VersionEvidence e)
        {
            var score = 0;
            if (log?.McVersion != null && e.McVersion != null && Same(log.McVersion, e.McVersion)) score += 8;
            if (log?.Loader != null && e.Loader == log.Loader) score += 4;
            if (mods != null && mods.Fits(e.McVersion)) score += 2;
            if (mods?.MajorityLoader is { } j && e.Loader is { } l && ModsEvidence.Compatible(l, j)) score += 1;
            return score;
        }

        var top = recent.Max(Score);
        var best = top == 0 ? recent : recent.Where(e => Score(e) == top).ToList();
        mcAmbiguous = best.Select(e => e.McVersion ?? "?").Distinct(StringComparer.OrdinalIgnoreCase).Count() > 1;
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
