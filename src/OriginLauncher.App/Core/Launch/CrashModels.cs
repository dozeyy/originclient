namespace OriginLauncher.App.Core.Launch;

// Crash Report V3 vocabulary (docs/overhaul/audit/architecture.md section 5, C2): evidence -> facts -> ordered rules ->
// one finding the window shows in five sections. Every type here is plain data so the whole path is testable from
// strings (src/OriginLauncher.Tests/CrashCorpus).

/// <summary>One mod implicated in a crash: its mod id (Fabric's, or Forge's on 1.8.9) and, when it could be mapped,
/// the jar file the game loaded it from. IsExternal = the player added it (not one of Origin's own jars).</summary>
public sealed record CrashCulprit(string ModId, string? JarFileName, bool IsExternal);

/// <summary>Why the game stopped, in the words a player needs.</summary>
public enum CrashCause
{
    OutOfMemory,
    JavaProblem,
    DamagedFiles,
    GraphicsDriver,
    ModProblem,
    NativeCrash,
    Unknown,
}

/// <summary>
/// How sure the diagnosis is, from the KIND of evidence (never a guessed number). Shown to the player verbatim
/// (<see cref="CrashConfidenceWords.Word"/>), and the fix button exists only at Strong or Confirmed.
/// </summary>
public enum CrashConfidence
{
    /// <summary>Nothing recognisable.</summary>
    Unknown,
    /// <summary>A stack-package match, a native frame in a GPU driver, a native exit code.</summary>
    Possible,
    /// <summary>A mixin apply failure in one mod's config, a single "Suspected Mods" entry in the launch set, a known other-client core.</summary>
    Strong,
    /// <summary>The loader or the JVM names the cause in a structured message (Fabric dependency block, OutOfMemoryError, ...).</summary>
    Confirmed,
}

public static class CrashConfidenceWords
{
    /// <summary>The exact word the window, the Copy report and the corpus use.</summary>
    public static string Word(this CrashConfidence c) => c switch
    {
        CrashConfidence.Confirmed => "confirmed",
        CrashConfidence.Strong => "strong",
        CrashConfidence.Possible => "possible",
        _ => "unknown",
    };

    public static CrashConfidence Parse(string word) => word.Trim().ToLowerInvariant() switch
    {
        "confirmed" => CrashConfidence.Confirmed,
        "strong" => CrashConfidence.Strong,
        "possible" => CrashConfidence.Possible,
        _ => CrashConfidence.Unknown,
    };
}

/// <summary>What the fix button does. Every kind is reversible: a mod is turned off by a rename (Mods tab turns it
/// back on), memory keeps its old value for the settings slider, a file check only re-downloads what is broken, and
/// reset Java arguments are kept for Undo.</summary>
public enum CrashFixKind
{
    /// <summary>No safe fix: the window shows no fix button.</summary>
    None,
    /// <summary>Raise the memory setting (+2 GB, capped by RamAdvice), then launch again.</summary>
    RaiseMemory,
    /// <summary>Turn off the named jars through the repair executor (Core/Compat/CrashRepair), then launch again.</summary>
    DisableMods,
    /// <summary>Make the next launch re-check every game file, then launch again.</summary>
    RepairFiles,
    /// <summary>Put this version's own Java arguments back to Origin's (the old ones are kept: Undo in Settings, Java
    /// arguments), then launch again. Only offered when the version HAS arguments of its own (CrashFacts.CustomJvmArgs).</summary>
    ResetJavaArgs,
}

/// <summary>The one safe fix a finding offers. Only built by a rule at Strong or Confirmed (CrashRules enforces it).</summary>
/// <param name="Jars">DisableMods: the jar file names (never a path) to turn off in the folder the game loaded them from.</param>
public sealed record CrashFix(CrashFixKind Kind, string Label, IReadOnlyList<string> Jars, int? NewRamMb = null);

/// <summary>
/// What one rule concluded. Maps 1:1 to the window: Headline (title), WhatHappened, LikelyCause + Confidence,
/// RecommendedFix, Fix (the button, null when not safe), TechnicalLines (folded details).
/// </summary>
public sealed record CrashFinding(
    string Rule,
    CrashCause Cause,
    CrashConfidence Confidence,
    IReadOnlyList<CrashCulprit> Culprits,
    string Tag,
    string Headline,
    string WhatHappened,
    string LikelyCause,
    string RecommendedFix,
    CrashFix? Fix,
    IReadOnlyList<string> TechnicalLines);

/// <summary>
/// The whole post-mortem: was it a crash at all (decided FIRST: a normal close never opens a window), the finding to
/// show, and the weaker findings for the technical details.
/// </summary>
/// <param name="CloseReason">Why it counts as a crash or as a normal close (one short line, for the log and the corpus).</param>
public sealed record CrashVerdict(bool IsCrash, string CloseReason, CrashFinding Finding, IReadOnlyList<CrashFinding> Others)
{
    /// <summary>The fix button's fix, or null (the window shows none).</summary>
    public CrashFix? SafeFix => Finding.Fix is { Kind: not CrashFixKind.None } f
                                && Finding.Confidence >= CrashConfidence.Strong ? f : null;

    /// <summary>Show the one-time "Try once without my mods" button (cause below strong, added mods present, no
    /// retry spent). It changes nothing on disk: only the next launch leaves the player's own mods out.</summary>
    public bool OfferTryWithoutAddedMods { get; init; }
}
