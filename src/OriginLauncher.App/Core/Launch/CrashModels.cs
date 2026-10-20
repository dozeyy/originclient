namespace OriginLauncher.App.Core.Launch;

/// <summary>One mod implicated in a crash: its mod id (Fabric's, or Forge's
/// on 1.8.9) and, when it could be mapped, the jar file in the instance's mods
/// folder.</summary>
public sealed record CrashCulprit(string ModId, string? JarFileName, bool IsExternal);

/// <summary>What the mod-blame analyzer concluded. Culprits is empty when no mod could
/// be blamed with confidence — the UI must then say "unknown" plainly rather
/// than guessing.</summary>
public sealed record CrashAnalysis(
    IReadOnlyList<CrashCulprit> Culprits,
    string Reason,
    string? DetailExcerpt);

/// <summary>Why the game stopped, in the words a player needs (CrashClassifier decides).</summary>
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

/// <summary>The ONE thing the crash window offers as its main button.</summary>
public enum CrashAction
{
    /// <summary>Only the secondary buttons (open logs, copy report, close).</summary>
    None,
    /// <summary>Raise the memory setting, then launch again.</summary>
    RaiseMemory,
    /// <summary>Turn off the one added mod that failed, then launch again.</summary>
    DisableMod,
    /// <summary>Launch again with only Origin's own mods.</summary>
    OriginOnly,
    /// <summary>Launch again after the next launch re-checks the game files.</summary>
    Relaunch,
    /// <summary>Open the logs folder (nothing the launcher can fix by itself).</summary>
    OpenLogs,
}

/// <summary>A classified crash: a plain headline, what it means, and the one action to offer.</summary>
public sealed record CrashDiagnosis(
    CrashCause Cause,
    string Tag,
    string Headline,
    string Explanation,
    CrashAction Action,
    string ActionLabel,
    string? ModId = null,
    string? ModJar = null,
    int? NewRamMb = null);
