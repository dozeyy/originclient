namespace OriginLauncher.App.Core.Import.Bulk;

// "Import everything": the data the bulk import passes around. Where the single-instance import
// (ImportModels) takes ONE detected instance into one Origin game folder, a bulk import takes MANY
// setups (every instance of every launcher found, or the ticked ones) and merges them per Minecraft
// version: one winner's settings, every compatible setup's mods and packs, one game-file reuse.
//
// Plain records and enums, no IO and no launcher state, so every piece is unit tested on Linux like the
// rest of Core/Import. The engine fills these in (BulkPlanner / BulkEngine, not built yet); the panel
// only reads them, and the CI screen render builds them by hand (UI/ImportShots.cs).

// ---- what to import ----------------------------------------------------------

public static class InstanceIds
{
    /// <summary>A stable name for a setup within one scan (launcher + game folder); what a <see cref="BulkSelection"/> refers to.</summary>
    public static string BulkId(this DetectedInstance setup) => setup.Kind + "|" + setup.GameDir.TrimEnd('\\', '/');
}

public enum BulkSelectionKind
{
    /// <summary>Every setup the scan found, minus <see cref="BulkSelection.Unticked"/>.</summary>
    All,

    /// <summary>Every setup of one launcher, minus <see cref="BulkSelection.Unticked"/>.</summary>
    Launcher,

    /// <summary>Exactly the setups in <see cref="BulkSelection.Ids"/>.</summary>
    Explicit,
}

/// <summary>
/// Which scanned setups a bulk import covers. "All" and "Launcher" are the one-click choices; the player
/// can then untick single setups (<see cref="Unticked"/>); "Explicit" is a hand-picked set. Setups are
/// named by <see cref="InstanceIds.BulkId"/>.
/// </summary>
public sealed record BulkSelection
{
    public BulkSelectionKind Kind { get; init; }

    /// <summary>For <see cref="BulkSelectionKind.Launcher"/>: which launcher.</summary>
    public SourceKind? Launcher { get; init; }

    /// <summary>For <see cref="BulkSelectionKind.Explicit"/>: the setups to import.</summary>
    public IReadOnlySet<string> Ids { get; init; } = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

    /// <summary>For All / Launcher: setups the player took out again.</summary>
    public IReadOnlySet<string> Unticked { get; init; } = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

    public static BulkSelection All() => new() { Kind = BulkSelectionKind.All };

    public static BulkSelection OfLauncher(SourceKind launcher) =>
        new() { Kind = BulkSelectionKind.Launcher, Launcher = launcher };

    public static BulkSelection Of(IEnumerable<string> ids) =>
        new() { Kind = BulkSelectionKind.Explicit, Ids = new HashSet<string>(ids, StringComparer.OrdinalIgnoreCase) };

    public BulkSelection Without(string id) =>
        this with { Unticked = new HashSet<string>(Unticked, StringComparer.OrdinalIgnoreCase) { id } };

    /// <summary>Whether this selection covers <paramref name="setup"/>.</summary>
    public bool Includes(DetectedInstance setup) => Kind switch
    {
        BulkSelectionKind.All => !Unticked.Contains(setup.BulkId()),
        BulkSelectionKind.Launcher => setup.Kind == Launcher && !Unticked.Contains(setup.BulkId()),
        _ => Ids.Contains(setup.BulkId()),
    };
}

// ---- the plan ----------------------------------------------------------------

/// <summary>What one setup contributes to its version's group.</summary>
public enum SetupRole
{
    /// <summary>The group's winner: settings, mods, packs, worlds if asked.</summary>
    Full,

    /// <summary>A second setup on the same version: its mods and packs are added, its settings are not used.</summary>
    ModsAndPacks,

    /// <summary>Only its resource and shader packs come over (e.g. Forge mods cannot run on Origin's Fabric).</summary>
    PacksOnly,
}

/// <summary>One setup's slot in a version group: its role, its own plan, and a plain sentence when the role is not obvious.</summary>
public sealed record PlannedSetup(DetectedInstance Setup, SetupRole Role, ImportPlan Plan, string? Note = null);

/// <summary>
/// Everything that goes into ONE Origin game folder (one Minecraft version). <see cref="Setups"/> are in the
/// order they are applied: the winner first, so its settings land before the others add mods and packs.
/// </summary>
public sealed class VersionGroup
{
    public required string Version { get; init; }
    public required string TargetGameDir { get; set; }

    /// <summary>The setup whose settings are used.</summary>
    public required DetectedInstance Winner { get; set; }

    /// <summary>How the winner was chosen (the picker's candidates and highlighted one).</summary>
    public required PickResult Pick { get; set; }

    /// <summary>The settings winner is unclear: the player picks which setup's settings to use.</summary>
    public bool NeedsChoice { get; set; }

    public List<PlannedSetup> Setups { get; } = new();

    /// <summary>Game-file reuse for this version, done once for the whole group (null when it does not run).</summary>
    public PlannedOp? GameFileOp { get; set; }
}

/// <summary>A setup that cannot come over at all, with the plain reason ("Origin doesn't support Minecraft 1.21.5, so this setup can't come over.").</summary>
public sealed record SkippedSetup(DetectedInstance Setup, string Reason);

public sealed class BulkImportPlan
{
    public required string RunId { get; init; }
    public List<VersionGroup> Groups { get; } = new();
    public List<SkippedSetup> Skipped { get; } = new();
    public long BytesToCopy { get; set; }

    /// <summary>Plain sentences. Anything in here means Apply is refused ("Close Minecraft first").</summary>
    public List<string> Blocked { get; } = new();
    public bool CanApply => Blocked.Count == 0 && Groups.Count > 0;
}

/// <summary>One step of planning: which setup was just planned (or skipped), and how far along it is.</summary>
public sealed record BulkPlanProgress(int Done, int Total, string SetupLabel, PlannedSetup? Planned = null, SkippedSetup? Skipped = null);

// ---- apply -------------------------------------------------------------------

public sealed record BulkProgress(
    int SetupIndex, int SetupCount, string SetupLabel, ImportProgress Inner, long BytesDone, long BytesTotal);

public enum SetupResultKind
{
    Imported,
    ImportedPartly,   // some of it came over (Forge setup: packs only)
    Skipped,          // never tried (unsupported version, blocked)
    Failed,           // tried, and put back
}

public sealed record SetupOutcome(
    DetectedInstance Setup, SetupRole Role, SetupResultKind Kind, string? Message = null, ImportResult? Result = null);

public sealed record VersionOutcome(string Version, IReadOnlyList<SetupOutcome> Setups);

/// <summary>One thing that did not come over, with the setup it was in and the plain reason.</summary>
public sealed record NotImportedItem(string SetupLabel, string Name, string Reason);

public sealed class BulkImportResult
{
    public required string RunId { get; init; }
    public required ImportState State { get; init; }
    public bool Cancelled { get; init; }

    /// <summary>A plain sentence when the whole import failed and was rolled back; null otherwise.</summary>
    public string? Error { get; init; }

    public IReadOnlyList<VersionOutcome> Versions { get; init; } = Array.Empty<VersionOutcome>();

    /// <summary>The "didn't come over" list: skipped setups, Forge mods, unsupported items.</summary>
    public IReadOnlyList<NotImportedItem> DidntComeOver { get; init; } = Array.Empty<NotImportedItem>();

    public string SummaryText { get; init; } = "";
    public bool Succeeded => State == ImportState.Committed;
}
