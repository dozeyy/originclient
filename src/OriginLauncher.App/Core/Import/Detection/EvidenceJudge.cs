using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import.Detection;

/// <summary>The best answer for one setup and whether it is certain.</summary>
public sealed record Judgement(
    string? McVersion, LoaderKind Loader, string? LoaderVersion, Confidence Confidence,
    bool McCertain, bool LoaderCertain, string? Reason)
{
    public bool Certain => McCertain && LoaderCertain;
}

// Decides a setup's Minecraft version + loader, and whether that answer is CERTAIN.
// Will: "if you can't find which one then ask". Importing a setup into the wrong
// version imports the wrong thing, so certainty is ONE simple gate, and any
// conflict it can't explain is "not sure" with a plain reason naming both sides.
//
// THE EVIDENCE (each a "piece" that states a version and/or a loader):
//   record   a launcher's record: Prism mmc-pack, CurseForge json, an official
//            profile (lastVersionId + version json). Time = an official profile's
//            lastUsed (a LAUNCH time) or the Prism/CurseForge file's mtime (a CHANGE
//            time). Official latest-release/latest-snapshot records are not
//            "explicit": they follow whatever is newest today.
//   log      logs/latest.log (written by the game): version + loader line; a
//            version with no loader line is a vanilla game. Time = its mtime.
//   options  options.txt `version:` DataVersion (written by the game): a version,
//            no loader. Time = its mtime.
//   mods     the jars: a version "fits" when >= 80 % of the declaring jars accept
//            it; the loader most jars are for. No time: the mods are simply what
//            gets imported.
//
// NOT EVIDENCE (dropped before anything else):
//   * never-played latest-* profiles (the launcher creates them; they're aliases);
//   * never-played vanilla records when the folder's log or jars carry a loader
//     (a vanilla profile nobody launched says nothing about a modded folder);
//   * a LAUNCH recorded after the log was last written that names something else:
//     the game rewrites latest.log at every start, so that launch never ran the
//     game here. (Proven on Will's real .minecraft: "Latest release/snapshot"
//     lastUsed in October, a July Fabric 1.21.1 log, Fabric 1.21.1 mods.)
//
// PRESELECTION (heuristic; the answer is ALWAYS a version+loader PAIR stated by one
// source — a loader is only borrowed from a piece that states the same version):
//   1. a launcher record CHANGED after the newest game-written evidence (the player
//      changed the version after the last play);
//   2. else the newest game-written evidence (log / options.txt) — replaced by the
//      mods' consensus pair only when that is NEWER than what the game last ran
//      (the mods moved on; never preselect a version older than the worlds were
//      last opened in);
//   3. else launcher records: played ones newest first, then never-played explicit
//      ones; a vanilla record is skipped when the folder's log or jars carry a loader;
//   4. else the mods' pair (consensus, else the version most jars accept);
//   5. else whatever record is left (never-played latest-* profiles).
//
// THE CERTAINTY GATE: the answer is certain only when
//   (a) every remaining piece agrees with it on version AND loader, except pieces
//       strictly OLDER than an agreeing pair (two agreeing pieces) whose newest
//       game-written piece is newer than them — a piece with no time (a
//       never-played record, the mods) can never be outranked; and
//   (b) it is corroborated: the version by an explicit launcher record or by two
//       pieces; the loader by a launcher record or by two pieces.
// Confidence is Exact only when certain AND the answer is an explicit launcher record's.
public static class EvidenceJudge
{
    private enum PieceKind { Record, Log, Options, Mods }

    private sealed record Piece(PieceKind Kind, string? Mc, LoaderKind? Loader, string? LoaderVersion, DateTimeOffset? Time)
    {
        public bool Explicit { get; init; }
        public bool LaunchTime { get; init; }
        public string? From { get; init; }
        public bool Played => Time != null;
        public bool GameWritten => Kind is PieceKind.Log or PieceKind.Options;
    }

    public static Judgement Judge(IReadOnlyList<VersionEvidence> metadata, LogEvidence? log, ModsEvidence? mods, OptionsEvidence? options = null)
    {
        // ---- the pieces
        Piece? logPiece = log != null && (log.McVersion != null || log.Loader != null)
            ? new Piece(PieceKind.Log, log.McVersion, log.Loader ?? (log.McVersion != null ? LoaderKind.None : null), log.LoaderVersion, log.ModifiedUtc)
            : null;
        Piece? optionsPiece = options?.McVersion != null ? new Piece(PieceKind.Options, options.McVersion, null, null, options.ModifiedUtc) : null;
        Piece? modsPiece = mods != null && (mods.BestVersion != null || mods.MajorityLoader != null)
            ? new Piece(PieceKind.Mods, mods.ConsensusVersion ?? mods.BestVersion, mods.MajorityLoader, null, null)
            : null;

        var folderLoader = (logPiece?.Loader is { } ll && ll != LoaderKind.None) || mods?.MajorityLoader is { } ml && ml != LoaderKind.None;

        var allRecords = metadata
            .Where(e => e.Source == EvidenceSource.LauncherMetadata && (e.McVersion != null || e.Loader != null))
            .Select(e => new Piece(PieceKind.Record, e.McVersion, e.Loader, e.LoaderVersion, e.ObservedUtc)
            {
                Explicit = e.Explicit, LaunchTime = e.TimeIsLaunch, From = e.From,
            })
            .ToList();
        var records = allRecords.Where(r =>
                !(!r.Played && !r.Explicit)                                            // never-played latest-*: alias
                && !(!r.Played && r.Loader == LoaderKind.None && folderLoader)          // never-played vanilla in a modded folder
                && !(logPiece != null && r.LaunchTime && r.Time > logPiece.Time && !Agrees(r, logPiece.Mc, logPiece.Loader))) // launch the log never saw
            .ToList();

        // ---- preselection
        var gameWritten = new[] { logPiece, optionsPiece }
            .Where(p => p?.Mc != null).Select(p => p!)
            .OrderByDescending(p => p.Time).ThenBy(p => p.Kind) // newest first; the log on a tie
            .ToList();
        var newestGame = gameWritten.FirstOrDefault();
        var anchorEligible = records.Where(r => r.Mc != null && !(folderLoader && r.Loader == LoaderKind.None)).ToList();

        Piece? anchor =
            anchorEligible.Where(r => !r.LaunchTime && r.Played && (newestGame == null || r.Time > newestGame.Time))
                .OrderByDescending(r => r.Time).FirstOrDefault()                                                   // 1
            ?? (newestGame != null
                ? (modsPiece?.Mc != null && mods!.ConsensusVersion != null && McVersion.Compare(mods.ConsensusVersion, newestGame.Mc!) > 0
                    ? modsPiece : newestGame)                                                                      // 2
                : null)
            ?? anchorEligible.Where(r => r.Played).OrderByDescending(r => r.Time).FirstOrDefault()                 // 3
            ?? anchorEligible.FirstOrDefault(r => r.Explicit)
            ?? (modsPiece?.Mc != null ? modsPiece : null)                                                          // 4
            ?? allRecords.Where(r => r.Mc != null).OrderByDescending(r => r.Time ?? DateTimeOffset.MinValue).FirstOrDefault(); // 5

        if (anchor?.Mc == null)
            return new Judgement(null, LoaderKind.None, null, Confidence.Inferred, false, false,
                "Origin couldn't tell which Minecraft version this is.");

        var mc = anchor.Mc;
        var loader = anchor.Loader;
        var loaderVersion = anchor.LoaderVersion;
        if (loader == null)
        {
            // Borrow the loader only from a piece that states this SAME version (never invent a pair).
            var donor = (logPiece?.Loader != null && Same(logPiece.Mc, mc) ? logPiece : null)
                ?? allRecords.Where(r => r.Loader != null && Same(r.Mc, mc)).OrderByDescending(r => r.Time ?? DateTimeOffset.MinValue).FirstOrDefault()
                ?? (modsPiece?.Loader != null && mods!.Fits(mc) ? modsPiece : null);
            loader = donor?.Loader;
            loaderVersion = donor?.LoaderVersion;
        }
        var finalLoader = loader ?? LoaderKind.None;

        // ---- the gate (a): agreement, or explained by time
        var timedPieces = records.Concat(new[] { logPiece, optionsPiece }.Where(p => p != null).Select(p => p!)).ToList();
        var support = timedPieces.Where(p => Agrees(p, mc, loader)).ToList();
        var modsAgreeMc = mods is { HasRanges: true } && mods.Fits(mc);
        var modsAgreeLoader = mods?.MajorityLoader is { } jl && loader != null && ModsEvidence.Compatible(loader.Value, jl);
        var supportCount = support.Count + (modsAgreeMc || modsAgreeLoader ? 1 : 0);
        var newestAgreeingGame = support.Where(p => p.GameWritten).Select(p => p.Time).Max(); // null when none

        var conflicts = new List<string>();
        var mcCertain = true;
        var loaderCertain = true;
        foreach (var piece in timedPieces)
        {
            var mcClash = piece.Mc != null && !Same(piece.Mc, mc);
            var loaderClash = piece.Loader != null && loader != null && piece.Loader != loader;
            if (!mcClash && !loaderClash) continue;
            var outranked = piece.Time is { } t && supportCount >= 2 && newestAgreeingGame is { } g && g > t;
            if (outranked) continue;
            if (mcClash) mcCertain = false;
            if (loaderClash) loaderCertain = false;
            conflicts.Add(Describe(piece, mods));
        }
        if (mods is { HasRanges: true } && !mods.Fits(mc))
        {
            mcCertain = false;
            conflicts.Add(mods.Rejects(mc) && mods.BestVersion is { } b && !Same(b, mc)
                ? $"most mods here are built for Minecraft {b}"
                : $"only some of the mods here are built for Minecraft {mc}");
        }
        if (mods != null && mods.MostlyIncompatibleWith(finalLoader))
        {
            loaderCertain = false;
            conflicts.Add($"most mods here are {Name(mods.MajorityLoader ?? LoaderKind.Unknown)} mods");
        }

        // ---- the gate (b): corroboration
        var reasons = new List<string>();
        if (conflicts.Count > 0)
            reasons.Add(Cap($"{Describe(anchor with { Loader = loader }, mods)}, but {string.Join(", and ", conflicts.Distinct().Take(2))}."));

        var mcStatements = support.Count(p => p.Mc != null) + (modsAgreeMc ? 1 : 0);
        if (mcCertain && !(support.Any(p => p.Kind == PieceKind.Record && p.Explicit && p.Mc != null) || mcStatements >= 2))
        {
            mcCertain = false;
            reasons.Add(anchor.Kind == PieceKind.Record && !anchor.Explicit
                ? $"The launcher's \"latest\" profile follows the newest version, and nothing in the folder confirms {mc}."
                : $"Only {SourceName(anchor)} {(anchor.Kind == PieceKind.Mods ? "point" : "points")} to Minecraft {mc}; nothing else confirms it.");
        }
        var loaderStatements = support.Count(p => p.Loader != null) + (modsAgreeLoader ? 1 : 0);
        if (loader == null)
        {
            loaderCertain = false;
            reasons.Add("Origin couldn't tell which mod loader this uses.");
        }
        else if (loaderCertain && !(support.Any(p => p.Kind == PieceKind.Record && p.Loader != null) || loaderStatements >= 2))
        {
            loaderCertain = false;
            reasons.Add(allRecords.Any(r => r.Loader == null && Same(r.Mc, mc))
                ? $"The launcher didn't record a mod loader; only one source says {Name(loader.Value)}."
                : $"Only one source says {Name(loader.Value)}; nothing else confirms the loader.");
        }

        var certain = mcCertain && loaderCertain;
        var confidence = certain && anchor.Kind == PieceKind.Record && anchor.Explicit && anchor.Loader != null
            ? Confidence.Exact : Confidence.Inferred;
        var reason = certain ? null : string.Join(" ", reasons.Distinct());
        if (!certain && string.IsNullOrWhiteSpace(reason)) reason = "Origin isn't sure which version this is.";
        return new Judgement(mc, finalLoader, loaderVersion, confidence, mcCertain, loaderCertain, reason);
    }

    // A piece agrees when it states nothing different: same version (if it names one) and same loader (if it names one).
    private static bool Agrees(Piece p, string? mc, LoaderKind? loader) =>
        (p.Mc == null || mc == null || Same(p.Mc, mc)) && (p.Loader == null || loader == null || p.Loader == loader);

    private static string Describe(Piece p, ModsEvidence? mods) => p.Kind switch
    {
        PieceKind.Log => $"the game last ran {Pair(p.Mc, p.Loader)}",
        PieceKind.Options => $"the game's settings were last saved by Minecraft {p.Mc}",
        PieceKind.Mods => $"most mods here are built for {Pair(p.Mc, p.Loader)}",
        _ when p.LaunchTime && p.Time is { } t => $"the launcher profile \"{p.From}\" last played {Pair(p.Mc, p.Loader)} on {t:MMM d}",
        _ when p.LaunchTime => $"the launcher profile \"{p.From}\" is set to {Pair(p.Mc, p.Loader)} (never played)",
        _ => $"the launcher says {Pair(p.Mc, p.Loader)}",
    };

    private static string SourceName(Piece p) => p.Kind switch
    {
        PieceKind.Log => "the game's last log",
        PieceKind.Options => "the game's settings file",
        PieceKind.Mods => "the mods",
        _ => "the launcher",
    };

    private static string Pair(string? mc, LoaderKind? loader) => loader switch
    {
        null => mc ?? "an unknown version",
        LoaderKind.None => $"vanilla {mc}",
        _ => $"{Name(loader.Value)} {mc}",
    };

    private static string Cap(string s) => s.Length == 0 ? s : char.ToUpperInvariant(s[0]) + s[1..];

    private static bool Same(string? a, string? b) => a != null && b != null && string.Equals(a.Trim(), b.Trim(), StringComparison.OrdinalIgnoreCase);

    private static string Name(LoaderKind loader) => loader switch
    {
        LoaderKind.None => "vanilla",
        LoaderKind.NeoForge => "NeoForge",
        LoaderKind.Unknown => "other",
        _ => loader.ToString(),
    };
}
