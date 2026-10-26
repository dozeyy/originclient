namespace OriginLauncher.App.Core.Compat;

// The vocabulary of the compatibility engine (Core/Compat). Everything here is plain data: the engine
// (CompatEngine.Check) turns mod descriptors into a CompatReport, the planner (RepairPlanner.Plan) turns a
// report into proposed repairs, and only the executor (RepairExecutor.Apply) touches disk.

/// <summary>Who put the file where it is. Decides what may be blamed or repaired: Origin's own jars never are.</summary>
public enum ModRole
{
    /// <summary>Origin provisioned it (originclient, Fabric API, the performance stack). Never disabled or blamed.</summary>
    Managed,

    /// <summary>The player's mod inside a profile's own folder.</summary>
    Profile,

    /// <summary>A loose player mod in the version's shared mods folder.</summary>
    SharedLoose,

    /// <summary>A jar an import is considering; not installed anywhere yet.</summary>
    ImportCandidate,
}

/// <summary>One mod file in the set being checked: where it is, whether it loads, and what it says about itself.</summary>
public sealed record ModFile(string Path, string FileName, bool Enabled, ModRole Role, ModInfo Info)
{
    /// <summary>The id the mod states, or null when its metadata could not be read.</summary>
    public string? Id => Info.Id;

    /// <summary>"Sodium", else its id, else the file name: what to call it to a player.</summary>
    public string DisplayName => Info.Name ?? Info.Id ?? FileName;
}

public enum LoaderFamily
{
    /// <summary>Fabric Loader: every supported version except 1.8.9.</summary>
    Fabric,

    /// <summary>Forge 11.15.1.2318 (+ OptiFine): 1.8.9 only. Its mod metadata (mcmod.info) is advisory, see <see cref="Certainty"/>.</summary>
    LegacyForge,
}

/// <summary>Which side the mod set runs on. The launcher is a client; a dedicated server drops client-only mods.</summary>
public enum RunSide { Client, Server }

/// <summary>
/// What the mod set is checked against. <paramref name="BuiltIns"/> is what Origin ships that is NOT among the entries
/// (the import case, where Origin's own jars are not listed); when the entries include Origin's jars
/// (<see cref="ModRole.Managed"/>), leave it null.
/// </summary>
public sealed record CompatPlatform(
    string McVersion, LoaderFamily Loader, string? LoaderVersion, string JavaVersion,
    BuiltInModSet? BuiltIns = null, RunSide Side = RunSide.Client)
{
    public bool IsLegacyForge => Loader == LoaderFamily.LegacyForge;

    /// <summary>
    /// The mods being judged go where the player's own copy of a mod Origin also ships wins (a mod profile or a Fabric
    /// version's shared folder; Core/Compat/PlayerOverrides): such a copy is judged as the player's mod, not refused as
    /// "Origin includes it". Off on 1.8.9 (Forge), where Origin's copy stays.
    /// </summary>
    public bool PlayerCopiesWin { get; init; }
}

public enum FindingKind
{
    /// <summary>The jar can't be read, or its metadata is invalid.</summary>
    Unreadable,

    /// <summary>A Forge/NeoForge/Quilt jar (or no mod metadata at all) where the loader is Fabric, or the reverse on 1.8.9.</summary>
    WrongLoader,

    /// <summary>Another client's own core jar (Feather, Lunar...). It only works inside its own launcher.</summary>
    ForeignClient,

    /// <summary>The mod is built for another Minecraft version than the one in use.</summary>
    WrongMinecraft,

    /// <summary>The mod lives on the other side only (a client-only mod on a server, or the reverse): the loader skips it.</summary>
    WrongSide,

    /// <summary>
    /// Two jars carry the same mod id. Fabric loads one of them (the higher version) and starts, so this is a note
    /// there; Forge 1.8.9 refuses two turned-on copies.
    /// </summary>
    DuplicateId,

    /// <summary>Origin already ships this mod (same id, or provided by one of Origin's): a second copy conflicts with it.</summary>
    OriginManagedConflict,

    /// <summary>On Origin's short list of mods that don't work with what it ships, though they don't declare it.</summary>
    KnownIncompatible,

    /// <summary>A required mod (or Minecraft/Java/loader) is missing.</summary>
    MissingDependency,

    /// <summary>A required mod is there, but not in the version range the mod asks for.</summary>
    WrongVersion,

    /// <summary>The mod declares it breaks another mod that is present.</summary>
    Breaks,

    /// <summary>"conflicts": declared to misbehave next to another present mod. The loader only warns.</summary>
    Conflicts,

    /// <summary>"recommends": a mod it works best with is missing. Information only.</summary>
    MissingRecommended,

    /// <summary>A crash report blamed this jar (CrashRepair). Not from metadata.</summary>
    CrashBlamed,
}

public enum Severity
{
    /// <summary>Nothing needs doing.</summary>
    Info,

    /// <summary>The mod will be skipped, or may misbehave, but the game still starts.</summary>
    Warning,

    /// <summary>The loader would stop the game from starting (or Origin treats the mod as unusable).</summary>
    Blocking,
}

/// <summary>
/// How we know. <see cref="Confirmed"/> = the metadata states it and the loader's own rules give the verdict.
/// <see cref="Likely"/> = a heuristic, a hand-kept table, advisory metadata (1.8.9), or a check made without seeing
/// everything that could answer it. Never present a Likely finding as a fact.
/// </summary>
public enum Certainty { Confirmed, Likely }

/// <summary>One thing the engine found about one mod.</summary>
/// <param name="Subject">The mod the finding is about.</param>
/// <param name="Related">The other mod involved: the copy that is kept (duplicates), or the mod that is in the way (breaks).</param>
/// <param name="TargetId">The mod id a dependency/breaks entry names ("sodium", "minecraft", "java").</param>
/// <param name="Range">The requirement text as the player reads it (">=0.6"), or null.</param>
/// <param name="Found">The version of the target that IS there, or null.</param>
/// <param name="Summary">One plain sentence for the player.</param>
/// <param name="Evidence">The metadata that says so ("depends: sodium = >=0.6"), for technical details.</param>
/// <param name="Excludes">The engine treats the mod as not loading because of this (later checks skip it, dependants lose it).</param>
/// <param name="CausedBy">For a knock-on finding: the mods that were excluded first and left this one without something it needs.</param>
/// <param name="TargetIsOrigin">The target is something Origin ships, so the player cannot just swap it.</param>
public sealed record CompatFinding(
    FindingKind Kind, Severity Severity, Certainty Certainty, ModFile Subject, string Summary)
{
    public ModFile? Related { get; init; }
    public string? TargetId { get; init; }
    public string? Range { get; init; }
    public string? Found { get; init; }
    public string Evidence { get; init; } = "";
    public bool Excludes { get; init; }
    public IReadOnlyList<ModFile> CausedBy { get; init; } = Array.Empty<ModFile>();
    public bool TargetIsOrigin { get; init; }

    public bool IsKnockOn => CausedBy.Count > 0;
}

/// <summary>The engine's verdict on a mod set. Findings are in the order the checks ran.</summary>
public sealed class CompatReport
{
    private readonly Dictionary<ModFile, List<CompatFinding>> _byEntry = new(ReferenceEqualityComparer.Instance);

    public CompatReport(IReadOnlyList<ModFile> entries, IReadOnlyList<CompatFinding> findings)
    {
        Entries = entries;
        Findings = findings;
        foreach (var f in findings)
        {
            if (!_byEntry.TryGetValue(f.Subject, out var list)) _byEntry[f.Subject] = list = new List<CompatFinding>();
            list.Add(f);
        }
    }

    public IReadOnlyList<ModFile> Entries { get; }
    public IReadOnlyList<CompatFinding> Findings { get; }

    /// <summary>At least one finding would stop the game from starting.</summary>
    public bool HasBlocking => Findings.Any(f => f.Severity == Severity.Blocking);

    /// <summary>True when nothing above Info was found.</summary>
    public bool IsClean => Findings.All(f => f.Severity == Severity.Info);

    public IReadOnlyList<CompatFinding> For(ModFile entry) =>
        _byEntry.TryGetValue(entry, out var list) ? list : Array.Empty<CompatFinding>();

    /// <summary>The first finding that takes the mod out of the picture, in check order; null when it loads.</summary>
    public CompatFinding? Terminal(ModFile entry) => For(entry).FirstOrDefault(f => f.Excludes);

    /// <summary>
    /// The plain sentence for why a mod is out: all its unmet requirements together ("Needs a, Needs b >=1."), else
    /// the first conflict it declares, else the terminal finding's own summary. Null when it loads.
    /// </summary>
    public string? ReasonFor(ModFile entry)
    {
        var terminal = Terminal(entry);
        if (terminal == null) return null;
        if (terminal.Kind is FindingKind.MissingDependency or FindingKind.WrongVersion)
        {
            var needs = For(entry)
                .Where(f => f.Excludes && f.Kind is FindingKind.MissingDependency or FindingKind.WrongVersion)
                .Select(f => f.Summary.TrimEnd('.')).ToList();
            return string.Join(", ", needs) + ".";
        }
        return terminal.Summary;
    }
}
