using OriginLauncher.App.Core.Import.Mods;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.Core.Import.Bulk;

// "Import everything": the data the bulk import passes around. Where the single-instance import
// (ImportModels) takes ONE detected instance into one Origin game folder, a bulk import takes MANY
// setups (every instance of every launcher found, or the ticked ones) and merges them per Minecraft
// version: one winner's settings, every compatible setup's mods and packs, one game-file reuse.
//
// Plain records and enums, no IO and no launcher state, so every piece is unit tested on Linux like the
// rest of Core/Import. The engine fills these in (ImportEngine.PlanBulkAsync / ApplyBulkAsync in
// BulkEngine.cs); the panel only reads them, and the CI screen render builds them by hand (UI/ImportShots.cs).

// ---- what to import ----------------------------------------------------------

public static class InstanceIds
{
    /// <summary>
    /// The setup's one id (<see cref="DetectedInstance.SetupId"/>: from the game folder only), what a <see cref="BulkSelection"/>,
    /// an untick and a settings-winner choice refer to. One folder is one setup, so a rescan, Browse or another launcher
    /// naming the same folder never splits it into two ids.
    /// </summary>
    public static string BulkId(this DetectedInstance setup) => setup.SetupId;

    /// <summary>How a setup is named to the player in a bulk import: "Prism: Survival" (the launcher, then its own name).</summary>
    public static string BulkLabel(this DetectedInstance setup) => $"{setup.SourceLabel}: {setup.Name}";
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

    /// <summary>For <see cref="BulkSelectionKind.Launcher"/>: which launcher (Prism covers MultiMC: they share a layout and a SourceKind).</summary>
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

    /// <summary>True when the player took this setup out by hand (as opposed to a launcher filter leaving it out).</summary>
    public bool IsUnticked(DetectedInstance setup) =>
        Kind == BulkSelectionKind.Explicit ? !Ids.Contains(setup.BulkId()) : Unticked.Contains(setup.BulkId());
}

/// <summary>What the launcher knows about one target version (the glue fills it in; tests hand it in).</summary>
public sealed record TargetFacts
{
    /// <summary>The bundled originclient-&lt;jar&gt;.jar for the version (its jar-in-jar mods are Origin's built-ins); null when not found.</summary>
    public string? BundledOriginJarPath { get; init; }

    /// <summary>PerformanceModCatalog's mods for the version, mapped to families.</summary>
    public IReadOnlyList<CatalogMod> CatalogMods { get; init; } = Array.Empty<CatalogMod>();

    public bool? TargetHasIris { get; init; }
}

/// <summary>Everything a bulk plan needs: the scan, the selection, the account, the switches and the launcher facts.</summary>
public sealed record BulkRequest
{
    /// <summary>Every setup the scan found (selected or not): each one appears exactly once in the result.</summary>
    public required IReadOnlyList<DetectedInstance> Setups { get; init; }

    public BulkSelection Selection { get; init; } = BulkSelection.All();

    /// <summary>The account whose game folders receive the import (one account per run); null/empty = nobody signed in.</summary>
    public string? AccountKey { get; init; }

    public ImportOptions Options { get; init; } = new();

    /// <summary>VersionPicker.SupportedTargets. A setup qualifies only when its version is EXACTLY in here (never remapped).</summary>
    public required IReadOnlyCollection<string> SupportedTargets { get; init; }

    /// <summary>Per-version launcher facts (bundled jar, catalog mods); null = none known.</summary>
    public Func<string, TargetFacts>? Facts { get; init; }

    /// <summary>The player's settings-winner choices: version -> <see cref="InstanceIds.BulkId"/> of the setup whose settings to use.</summary>
    public IReadOnlyDictionary<string, string> WinnerChoices { get; init; } = new Dictionary<string, string>(StringComparer.Ordinal);

    // The launcher settings as they are now (the run's settings delta is named against these).
    public IReadOnlyList<ModProfile> ExistingProfiles { get; init; } = Array.Empty<ModProfile>();
    public bool PlayWithExternalMods { get; init; } = true;
    public string? SelectedVersion { get; init; }
    public string? SelectedProfileId { get; init; }
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
/// <param name="VersionUncertain">Origin guessed this setup's Minecraft version; it imports with the guess unless the player unticks it.</param>
public sealed record PlannedSetup(DetectedInstance Setup, SetupRole Role, ImportPlan Plan, string? Note = null, bool VersionUncertain = false);

/// <summary>
/// Everything that goes into ONE Origin game folder (one Minecraft version). <see cref="Setups"/> are in the
/// order they are applied: the winner first, so its settings land before the others add mods and packs.
/// </summary>
public sealed class VersionGroup
{
    public required string Version { get; init; }
    public required string TargetGameDir { get; set; }

    /// <summary>The setup whose settings are used (when every setup is packs-only, the first of them; see <see cref="CarriesSettings"/>).</summary>
    public required DetectedInstance Winner { get; set; }

    /// <summary>How the winner was chosen (the picker's candidates and highlighted one).</summary>
    public required PickResult Pick { get; set; }

    /// <summary>The settings winner is unclear: the player may pick which setup's settings to use (the preselected one is used otherwise).</summary>
    public bool NeedsChoice { get; set; }

    public List<PlannedSetup> Setups { get; } = new();

    /// <summary>Game-file reuse for this version, done once for the whole group (null when it does not run).</summary>
    public PlannedOp? GameFileOp { get; set; }

    /// <summary>What game-file reuse found for this version (null when it did not run).</summary>
    public GameFilePlanStats? GameFiles { get; set; }

    /// <summary>Version-wide lines (game-file reuse), not tied to one setup.</summary>
    public List<SummaryLine> Lines { get; } = new();

    /// <summary>True when one setup's settings come over (false when every setup in the group is packs-only).</summary>
    public bool CarriesSettings => Setups.Any(s => s.Role == SetupRole.Full);

    /// <summary>The version-wide plan the game-file op belongs to (its gate, its target): engine-internal.</summary>
    internal ImportPlan? VersionPlan { get; set; }
}

/// <summary>Why a whole setup didn't come over.</summary>
public enum SkipKind
{
    Unsupported,     // Origin has no build for its Minecraft version
    UnknownVersion,  // its version couldn't be told
    FolderMissing,
    InsideOrigin,    // the folder is part of Origin's own game folders
    SourceRunning,   // Minecraft is running from it
    TargetRunning,   // Origin's game for its version is running
    Unticked,        // the player took it out
    NotSelected,     // another launcher was picked for this import
}

/// <summary>A setup that cannot come over at all, with the plain reason ("Origin doesn't support Minecraft 1.21.5, so this setup can't come over.").</summary>
public sealed record SkippedSetup(DetectedInstance Setup, string Reason, SkipKind Kind = SkipKind.Unsupported);

public sealed class BulkImportPlan
{
    public required string RunId { get; init; }

    /// <summary>When planning started (<see cref="ImportTargetLocks.Now"/>): an apply refuses a plan older than any of its folders' last import.</summary>
    public long PlannedAt { get; init; }
    public List<VersionGroup> Groups { get; } = new();
    public List<SkippedSetup> Skipped { get; } = new();
    public long BytesToCopy { get; set; }

    /// <summary>Plain sentences. Anything in here means Apply is refused ("Sign in first").</summary>
    public List<string> Blocked { get; } = new();
    public bool CanApply => Blocked.Count == 0 && Groups.Count > 0;

    /// <summary>Run-wide "needs attention" sentences (e.g. "Play with my own mods" is off).</summary>
    public List<string> Notes { get; } = new();

    /// <summary>The request this plan was built from (kept for re-planning one group). Null for a hand-built plan.</summary>
    public BulkRequest? Request { get; init; }

    /// <summary>Every planned setup in apply order (groups newest version first, each winner first).</summary>
    public IEnumerable<PlannedSetup> AllSetups => Groups.SelectMany(g => g.Setups);
}

/// <summary>One step of planning: which setup was just planned (or skipped), and how far along it is.</summary>
public sealed record BulkPlanProgress(int Done, int Total, string SetupLabel, PlannedSetup? Planned = null, SkippedSetup? Skipped = null);

// ---- apply -------------------------------------------------------------------

/// <param name="WaitingFor">Set while the import waits for a version's install or launch to finish ("Waiting for Minecraft 1.21.1 to finish installing").</param>
public sealed record BulkProgress(
    int SetupIndex, int SetupCount, string SetupLabel, ImportProgress Inner, long BytesDone, long BytesTotal, string? WaitingFor = null);

public enum SetupResultKind
{
    Imported,
    ImportedPartly,   // some of it came over (Forge setup: packs only)
    Skipped,          // never tried (unsupported version, blocked, its game started mid-run)
    Failed,           // tried, and put back
}

/// <param name="Lines">What happened to this setup's items (Imported / Reused / Needs attention / Not imported).</param>
public sealed record SetupOutcome(
    DetectedInstance Setup, SetupRole Role, SetupResultKind Kind, string? Message = null, ImportResult? Result = null,
    IReadOnlyList<SummaryLine>? Lines = null);

/// <param name="SettingsFrom">The label of the setup whose settings this version got (null: none came over).</param>
/// <param name="GameFiles">What game-file reuse found for this version.</param>
/// <param name="Lines">Version-wide lines (game-file reuse while it ran).</param>
/// <param name="SettingsNote">Why no settings came over when the plan said they would ("didn't come over: Prism: A was left out while importing").</param>
public sealed record VersionOutcome(string Version, IReadOnlyList<SetupOutcome> Setups, string? SettingsFrom = null,
    GameFilePlanStats? GameFiles = null, IReadOnlyList<SummaryLine>? Lines = null, string? SettingsNote = null);

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

    /// <summary>The "didn't come over" list: whole setups that were skipped (unsupported, couldn't read, blocked, unticked).</summary>
    public IReadOnlyList<NotImportedItem> DidntComeOver { get; init; } = Array.Empty<NotImportedItem>();

    /// <summary>The same skipped setups, structured (kind + setup).</summary>
    public IReadOnlyList<SkippedSetup> Skipped { get; init; } = Array.Empty<SkippedSetup>();

    /// <summary>Run-wide "needs attention" sentences.</summary>
    public IReadOnlyList<string> Notes { get; init; } = Array.Empty<string>();

    public string SummaryText { get; init; } = "";
    public bool Succeeded => State == ImportState.Committed;
}
