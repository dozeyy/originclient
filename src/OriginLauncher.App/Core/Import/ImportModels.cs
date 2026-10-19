using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.Core.Import;

// The data the import engine passes around: what a scan finds, what a plan
// decides, what an apply did. All plain records and enums — no IO, no launcher
// state — so every piece is unit tested on Linux (src/OriginLauncher.Tests/Import).
//
// "Import from another Minecraft instance" copies a player's mods, settings,
// packs (and, only if asked, single-player worlds) out of another launcher's
// instance into an Origin game folder. It is read-only on the source and
// reversible on the destination (ImportTransaction), and it never reads an
// account or token file (CredentialGuard).

/// <summary>Which launcher (or plain folder) an instance was found in.</summary>
public enum SourceKind
{
    OfficialLauncher,
    Prism,       // Prism Launcher and MultiMC share the layout
    Modrinth,
    CurseForge,
    Folder,      // "Browse..." — the player pointed at a folder
}

public enum LoaderKind { None, Fabric, Quilt, Forge, NeoForge, Unknown }

/// <summary>Exact = the launcher's own metadata said so; Inferred = guessed from logs or mods.</summary>
public enum Confidence { Exact, Inferred }

/// <summary>One instance a scan found: everything the version picker and the plan need to know.</summary>
public sealed record DetectedInstance
{
    public required SourceKind Kind { get; init; }
    public required string Name { get; init; }

    /// <summary>The folder with options.txt, mods/, saves/ ... (the "game dir").</summary>
    public required string GameDir { get; init; }

    /// <summary>The Minecraft version, or null when it could not be told (the instance is then "Couldn't read details").</summary>
    public string? McVersion { get; init; }
    public LoaderKind Loader { get; init; }
    public string? LoaderVersion { get; init; }

    /// <summary>The launcher marks this one as the player's pinned/favorite instance.</summary>
    public bool Pinned { get; init; }
    public long? PlaytimeSeconds { get; init; }
    public DateTimeOffset? LastPlayed { get; init; }
    public int? LaunchCount { get; init; }
    public Confidence Confidence { get; init; } = Confidence.Exact;

    /// <summary>A plain sentence when the instance's details could not be read ("Couldn't read details"); null when fine.</summary>
    public string? Problem { get; init; }

    public const string CouldntRead = "Couldn't read details";

    /// <summary>How the source launcher is named in a profile name: "Minecraft: Hypixel".</summary>
    public string SourceLabel => Kind switch
    {
        SourceKind.OfficialLauncher => "Minecraft",
        SourceKind.Prism => "Prism",
        SourceKind.Modrinth => "Modrinth",
        SourceKind.CurseForge => "CurseForge",
        _ => "Folder",
    };

    // ---- detection accuracy (Core/Import/Detection): what the version answer rests on ----

    /// <summary>
    /// Every signal the scan weighed for this setup's version and loader: the launcher's own record (one per
    /// profile that uses this folder), the game's last log, the mods' depends.minecraft. McVersion / Loader
    /// above are the best answer from these (EvidenceJudge documents the rules).
    /// </summary>
    public IReadOnlyList<VersionEvidence> Evidence { get; init; } = Array.Empty<VersionEvidence>();

    /// <summary>
    /// True when the Minecraft version AND the loader are backed by agreeing evidence. False means "not sure":
    /// the UI / bulk import must ask instead of importing silently. <see cref="UncertainReason"/> says why.
    /// </summary>
    public bool VersionCertain { get; init; } = true;

    /// <summary>The loader part of <see cref="VersionCertain"/> on its own.</summary>
    public bool LoaderCertain { get; init; } = true;

    /// <summary>A short plain sentence when not certain ("The launcher says 1.20.1 but the game last ran 1.20.4."); null when certain.</summary>
    public string? UncertainReason { get; init; }

    /// <summary>
    /// Other launcher profile names that point at this same game folder (one folder is one setup, never two).
    /// <see cref="Name"/> is the display name; these are the rest.
    /// </summary>
    public IReadOnlyList<string> Aliases { get; init; } = Array.Empty<string>();

    /// <summary>
    /// A stable id for this setup: derived only from the game folder's full path (one folder is one setup), so a
    /// selection or an unticked setup survives a rescan, another launcher winning the name, and Browse.
    /// </summary>
    public string SetupId => Detection.SetupKey.StableId(GameDir);
}

/// <summary>Where a piece of version evidence came from.</summary>
public enum EvidenceSource
{
    LauncherMetadata, // the launcher's own record: launcher_profiles.json + version json, mmc-pack.json, minecraftinstance.json
    LatestLog,        // logs/latest.log: what the game last actually ran
    ModDepends,       // the mods' depends.minecraft consensus and their loader types
    OptionsFile,      // options.txt's `version:` DataVersion: the game that last wrote it, whatever the loader
}

/// <summary>
/// One signal about a setup's version. McVersion / Loader are null when the signal says nothing about them
/// (LoaderKind.None means "vanilla", which IS a statement). ObservedUtc: the profile's lastUsed, the metadata
/// file's last change, or the log's mtime; null when unknown.
/// </summary>
public sealed record VersionEvidence(
    EvidenceSource Source, string? McVersion, LoaderKind? Loader, string? LoaderVersion, DateTimeOffset? ObservedUtc)
{
    /// <summary>
    /// The launcher names a concrete version (Prism component, CurseForge gameVersion, an official profile's
    /// lastVersionId). False for an official "latest-release/latest-snapshot" profile: that follows whatever is
    /// newest today, not what the folder last ran.
    /// </summary>
    public bool Explicit { get; init; } = true;

    /// <summary>Which profile / file said it (a profile name, "latest.log", "mods"). Never a path or a secret.</summary>
    public string? From { get; init; }

    /// <summary>
    /// ObservedUtc is a LAUNCH time (an official profile's lastUsed), not the time the record was changed (a Prism /
    /// CurseForge metadata file's mtime). The game rewrites logs/latest.log at every start, so a launch recorded after
    /// the log was last written, naming something else, never actually ran the game in this folder.
    /// </summary>
    public bool TimeIsLaunch { get; init; }
}

/// <summary>What a scan found, plus plain notes about things it could not read.</summary>
public sealed class ScanResult
{
    public List<DetectedInstance> Instances { get; } = new();

    /// <summary>One sentence each, e.g. "Couldn't read the Minecraft launcher's profile list". Never a path to a secret, never a value.</summary>
    public List<string> Notes { get; } = new();

    public TimeSpan Elapsed { get; set; }

    /// <summary>Debug-only lines (e.g. an empty, never-played profile that was left out). Not shown to the player; names only, never a path.</summary>
    public List<string> Debug { get; } = new();
}

// ---- the plan ----------------------------------------------------------------

public enum PlanItemKind { Mod, Setting, Config, ResourcePack, ShaderPack, World, GameFile, Cache }

/// <summary>The summary.txt sections, in the order they are written.</summary>
public enum SummarySection { Imported, ReusedGameFiles, ReusedCaches, NeedsAttention, NotImported }

/// <summary>One line of the summary: what, under which section, and why when it matters.</summary>
public sealed record SummaryLine(SummarySection Section, PlanItemKind Kind, string Name, string? Reason = null, long Bytes = 0);

public enum ModOutcome
{
    Copied,             // lands as name.jar
    CopiedDisabled,     // lands as name.jar.disabled because of a missing dependency / conflict / known problem
    CopiedAsBefore,     // it was disabled in the source and stays disabled
    BuiltIn,            // Origin already includes it: not copied
    WrongMinecraft,     // built for another Minecraft version: not copied
    WrongLoader,        // Forge / NeoForge / Quilt / not a mod: not copied
    Duplicate,          // another version of the same mod id is imported instead
    Unreadable,         // not a readable jar: not copied
}

/// <summary>What happens to one jar of the source's mods folder, and why.</summary>
public sealed record ModDecision(
    string FileName, string? ModId, string? DisplayName, string? Version,
    ModOutcome Outcome, string? Reason, string? DestFileName, string? SourcePath)
{
    public bool IsCopied => Outcome is ModOutcome.Copied or ModOutcome.CopiedDisabled or ModOutcome.CopiedAsBefore;
}

/// <summary>The optional switches on the import panel's Advanced section.</summary>
public sealed record ImportOptions
{
    /// <summary>A differing destination settings file is kept instead of replaced (the replaced one is backed up otherwise).</summary>
    public bool KeepMyOriginSettings { get; init; }

    /// <summary>Also copy resource / shader packs the source does not use.</summary>
    public bool CopyUnusedPacks { get; init; }

    /// <summary>Copy single-player worlds (off by default: they are big and the player may not want them).</summary>
    public bool CopyWorlds { get; init; }

    /// <summary>
    /// Engine B: when Origin already has a Distant Horizons / Voxy cache where one would land, replace it
    /// (the old one is backed up first). Off by default: an existing cache is never merged into or overwritten.
    /// </summary>
    public bool ReplaceExistingCaches { get; init; }

    /// <summary>
    /// When Origin already has a server list that differs from the old one, replace it (the old Origin list is
    /// backed up first). Off by default: an existing Origin server list is kept; the old one is imported only
    /// where Origin has none.
    /// </summary>
    public bool ReplaceServerList { get; init; }
}

public enum OpKind
{
    CopyFile,         // one file; replaced (after a backup) when a different one is there
    CopyTree,         // a folder, staged then renamed in (worlds, folder packs)
    WriteOptions,     // options.txt: key-merged in the target era's format
    MergeProperties,  // iris.properties: key-merged, destination-only keys kept
    MergeJson,        // sodium-options.json: key-merged by JsonConfigFormat.ThirdParty
    WriteText,        // a small file the importer composes itself
    Custom,           // a step a plan contributor supplies (game files, caches): PlannedOp.Run does the work inside the transaction
}

/// <summary>What a contributor's <see cref="PlannedOp.Run"/> gets: the open transaction, the guarded source reader and the cancel token.</summary>
public sealed class ImportOpContext
{
    public required ImportTransaction Tx { get; init; }
    public required SourceFs Fs { get; init; }
    public required ImportPlan Plan { get; init; }
    public CancellationToken Ct { get; init; }

    /// <summary>Lines the step adds to the summary while it runs (e.g. "in use, left in the source").</summary>
    public required List<SummaryLine> Extra { get; init; }
    public IProgress<ImportProgress>? Progress { get; init; }

    public void Report(ImportStage stage, int done, int total, string? item = null) =>
        Progress?.Report(new ImportProgress(stage, done, total, item));
}

/// <summary>One thing apply does, in order. Data only — ImportEngine executes it.</summary>
public sealed record PlannedOp
{
    public required OpKind Kind { get; init; }
    public required PlanItemKind Item { get; init; }

    /// <summary>What the player recognises it by ("Faithful.zip", "options.txt").</summary>
    public required string Label { get; init; }
    public string? Source { get; init; }
    public required string Dest { get; init; }
    public long Bytes { get; init; }

    /// <summary>The file is a setting: its mtime becomes "now" so Settings Sync never overwrites it with an older copy.</summary>
    public bool TouchNow { get; init; }

    /// <summary>For the merge ops: the keys to write; for WriteOptions the resourcePacks value is Extra.</summary>
    public IReadOnlyDictionary<string, string?>? Desired { get; init; }
    public string? Text { get; init; }
    public string? TargetVersion { get; init; }
    public string? Extra { get; init; }

    /// <summary>For <see cref="OpKind.Custom"/>: the work, run in order inside the transaction.</summary>
    public Action<ImportOpContext>? Run { get; init; }

    /// <summary>
    /// Bulk import: what this op claims in its target folder (a path or a cache identity, DestinationLedger.PathKey /
    /// CacheKey). Null for ops nobody else could claim.
    /// </summary>
    public string? ClaimKey { get; init; }

    /// <summary>
    /// Bulk import: a stand-in for a copy another setup claimed first. It runs only when that claim is released —
    /// its setup was skipped mid-run, or its copy failed on the source side — and only once per claim. Never counted
    /// in a plan's bytes; a single import never runs one.
    /// </summary>
    public bool Fallback { get; init; }

    /// <summary>
    /// For a stand-in: the plan line that says this item comes from another setup ("already brought over from X",
    /// "Another of your setups already brings this one"). When the stand-in lands, that line is no longer true and
    /// is left out of the result.
    /// </summary>
    public SummaryLine? DeferredLine { get; init; }
}

/// <summary>
/// The launcher-settings change an import makes, applied by the glue in ONE
/// atomic settings update. The previous values travel with it so a rollback can
/// put them back. PlayWithExternalMods is never part of it.
/// </summary>
public sealed class SettingsDelta
{
    public ModProfile? AddProfile { get; set; }

    /// <summary>"Import everything": one profile per Minecraft version, all added in the same single settings write. Null for a single-instance import and for journals written before this existed.</summary>
    public List<ModProfile>? AddProfiles { get; set; }
    public string? SetSelectedVersion { get; set; }
    public string? SetSelectedProfileId { get; set; }
    public string? PreviousSelectedVersion { get; set; }
    public string? PreviousSelectedProfileId { get; set; }

    /// <summary>
    /// A bulk import ("import everything") adds one profile per setup with mods, in ONE settings change. Apply adds
    /// each (once); revert removes each. <see cref="AddProfile"/> stays for single imports and older journals.
    /// </summary>
    public List<ModProfile>? AddProfiles { get; set; }
}

/// <summary>Where the launcher's settings are changed (the glue implements it with one atomic settings update; tests use a file).</summary>
public interface IImportSettings
{
    void Apply(SettingsDelta delta);
    void Revert(SettingsDelta delta);
}

public sealed class ImportPlan
{
    public required string ImportId { get; init; }
    public required DetectedInstance Source { get; init; }
    public required string TargetVersion { get; init; }
    public string TargetGameDir { get; set; } = "";
    public string? ProfileId { get; set; }
    public string? ProfileName { get; set; }
    public required ImportOptions Options { get; init; }

    /// <summary>Plain sentences. Anything in here means Apply is refused ("Close Minecraft first").</summary>
    public List<string> Blocked { get; } = new();
    public bool CanApply => Blocked.Count == 0;

    public List<PlannedOp> Ops { get; } = new();
    public List<ModDecision> Mods { get; } = new();
    public List<SummaryLine> Lines { get; } = new();

    /// <summary>Files left alone because they look like credentials; names only.</summary>
    public List<string> SkippedForSafety { get; } = new();
    public SettingsDelta? SettingsDelta { get; set; }
    public bool BuiltInListIncomplete { get; set; }

    // ---- Engine B plug-in data ----

    /// <summary>
    /// Held for the whole apply when the plan writes shared version files (game files): the glue wires it to
    /// the version's provisioning gate, so a launch or install of the same version waits for the import.
    /// </summary>
    public IVersionGate? ProvisioningGate { get; set; }

    /// <summary>What game-file reuse found: reused vs to download (null when it did not run).</summary>
    public GameFilePlanStats? GameFiles { get; set; }

    /// <summary>Distant Horizons / Voxy caches the planner looked at, what happens to each and why.</summary>
    public List<CachePlanItem> Caches { get; } = new();

    public long BytesToCopy { get; set; }
    public int Created { get; set; }
    public int Replaced { get; set; }
    public int Kept { get; set; }
    public int Unchanged { get; set; }
}

public enum ImportState { Planned, Applying, Committed, RolledBack }

public enum ImportStage { Scanning, Planning, Mods, Settings, Packs, Worlds, GameFiles, Caches, Finishing }

public sealed record ImportProgress(ImportStage Stage, int Done, int Total, string? Item = null);

public sealed class ImportResult
{
    public required string ImportId { get; init; }
    public required ImportState State { get; init; }
    public bool Cancelled { get; init; }

    /// <summary>A plain sentence when the import failed and was rolled back; null otherwise.</summary>
    public string? Error { get; init; }
    public string SummaryText { get; init; } = "";
    public bool Succeeded => State == ImportState.Committed;
}

// ---- Engine B: game-file reuse and cache migration (plug in through IImportPlanContributor) ----

/// <summary>
/// The version's provisioning gate (the lock VersionManager takes while it installs or verifies a
/// version). The import holds it while it writes the version's shared files, so nothing downloads
/// into the same folder at the same time. Injectable: the glue wires it to VersionManager.
/// </summary>
public interface IVersionGate
{
    /// <summary>Waits until nobody else is provisioning <paramref name="version"/>; dispose the result to release it.</summary>
    Task<IAsyncDisposable> AcquireAsync(string version, CancellationToken ct);
}

/// <summary>What game-file reuse found. Bytes are file sizes from Mojang's metadata.</summary>
public sealed record GameFilePlanStats(
    int ReusedCount, long ReusedBytes, int ToDownloadCount, long ToDownloadBytes, int AlreadyPresentCount,
    bool MetadataUnavailable, bool JavaSkipped);

public enum CacheKind { DistantHorizons, Voxy }

public enum CacheScope { SinglePlayer, Multiplayer }

/// <summary>One cache the planner found: its identity (kind, scope, world or server, dimension), the decision and the reason.</summary>
public sealed record CachePlanItem(
    CacheKind Kind, CacheScope Scope, string Owner, string Dimension, bool WillImport, string? Reason, long Bytes);
