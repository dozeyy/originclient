using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Caches;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Import.Mods;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// What the import panel needs from the machine. The panel only ever talks to this, so the CI screen
/// render can hand it a made-up backend (no disk, no scan) and the real one is the launcher glue below.
/// Every method is async and cancellable; none touches the UI thread.
/// </summary>
public interface IImportBackend
{
    /// <summary>The Minecraft versions an import can go into (the version picker's list that also has an Origin build).</summary>
    IReadOnlyList<string> SupportedTargets { get; }

    /// <summary>imports/ (where each run's summary.txt and import.log live).</summary>
    string ImportsRoot { get; }

    Task<ScanResult> ScanAsync(IProgress<ImportProgress>? progress, CancellationToken ct);

    /// <summary>"Browse for a folder...": works out what the picked folder is.</summary>
    Task<FolderInspection> InspectFolderAsync(string folder, CancellationToken ct);

    /// <summary>
    /// "Browse for a folder...", with its OWN cancellation: never the scan's token. Replaced only by a newer
    /// Browse. The panel should call this instead of InspectFolderAsync(folder, _scanCts.Token), so picking a folder
    /// never cancels (and loses) a scan that is still running. The default keeps older backends working.
    /// </summary>
    Task<FolderInspection> InspectPickedFolderAsync(string folder) => InspectFolderAsync(folder, CancellationToken.None);

    Task<ImportPlan> PlanAsync(DetectedInstance source, string? targetVersion, ImportOptions options,
        IProgress<ImportProgress>? progress, CancellationToken ct);

    Task<ImportResult> ApplyAsync(ImportPlan plan, IProgress<ImportProgress>? progress, CancellationToken ct);

    // ---- "import everything" (Core/Import/Bulk) ----

    /// <summary>
    /// Plans every selected setup and merges them per Minecraft version. <paramref name="progress"/> reports each
    /// setup as it is planned (or skipped), so the panel can fill in while the rest still plans.
    /// </summary>
    Task<BulkImportPlan> PlanBulkAsync(BulkSelection selection, ImportOptions options,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct);

    /// <summary>The player picked a different settings winner for one version: re-plans that group only.</summary>
    Task<BulkImportPlan> ReplanGroupAsync(BulkImportPlan plan, string version, DetectedInstance newWinner, CancellationToken ct);

    Task<BulkImportResult> ApplyBulkAsync(BulkImportPlan plan, IProgress<BulkProgress> progress, CancellationToken ct);
}

/// <summary>Lets the engine wait for a version's provisioning gate: a launch or install of that version holds it too.</summary>
public sealed class LauncherVersionGate : IVersionGate
{
    public Task<IAsyncDisposable> AcquireAsync(string version, CancellationToken ct) =>
        VersionManager.AcquireVersionGateAsync(version, ct);
}

/// <summary>The settings change an import makes: one atomic SettingsStore.Update each way.</summary>
public sealed class LauncherImportSettings : IImportSettings
{
    public void Apply(SettingsDelta delta) => SettingsStore.Update(s => ImportSettingsRules.Apply(s, delta));

    public void Revert(SettingsDelta delta) => SettingsStore.Update(s => ImportSettingsRules.Revert(s, delta));
}

/// <summary>
/// What the backend needs from this PC, built once on the thread pool at the first scan/plan (never in the
/// constructor): the Windows folders, the engine wired to them, and the Minecraft versions an import can target.
/// </summary>
internal sealed record BackendParts(MachineFolders Folders, ImportEngine Engine, IReadOnlyList<string> SupportedTargets);

/// <summary>
/// The real backend: the engine wired to this PC's folders, the launcher's HTTP, version gate, account and
/// settings. <paramref name="dryRun"/> (the --import-plan command) plans against a placeholder account and
/// refuses to apply, so nothing about it can write.
///
/// Constructing it does NO work (it is created on the UI thread when a card opens): the folder probes, the
/// version-catalog static initialisation and the engine are built lazily on the thread pool inside the first
/// scan/plan. Every scan/plan/apply first waits for the startup recovery (ImportStartup.Recovery) so nothing
/// reads half-restored folders, and a launch that is still starting (LaunchRunState) blocks planning and
/// refuses applying.
/// </summary>
public sealed class LauncherImportBackend : IImportBackend
{
    private readonly bool _dryRun;
    private readonly Deferred<BackendParts> _parts;
    private readonly Func<Task> _recovery;
    private CancellationTokenSource? _inspectCts;

    public LauncherImportBackend(bool dryRun = false) : this(dryRun, () => Build(dryRun), null)
    {
    }

    /// <summary>Test/render seam: a custom builder (to prove the constructor does not call it) and a custom recovery wait.</summary>
    internal LauncherImportBackend(bool dryRun, Func<BackendParts> build, Func<Task>? recovery)
    {
        _dryRun = dryRun;
        _parts = new Deferred<BackendParts>(build);
        // A dry run never starts the recovery; the property is read at call time (it is set at app start).
        _recovery = recovery ?? (() => dryRun ? Task.CompletedTask : ImportStartup.Recovery);
    }

    private static BackendParts Build(bool dryRun)
    {
        var folders = MachineFolders.Current();
        var gameFiles = ImportRoots.GameFiles(folders, OriginPaths.Instances);
        var engine = new ImportEngine(new ImportEnvironment
        {
            InstancesRoot = OriginPaths.Instances,
            ImportsRoot = OriginPaths.Imports,
            // The launcher's own started games first, then the "latest.log is held open" probe (a game
            // started before this launcher was restarted): the same test Settings Sync uses.
            IsGameRunning = SettingsSync.IsGameRunning,
            Log = dryRun ? null : ImportLog.Write, // a dry run appends to no file at all
            VersionGate = new LauncherVersionGate(),
            Contributors = new IImportPlanContributor[]
            {
                new GameFileContributor(gameFiles, new ImportMetadataHttp(), new LauncherVersionGate()),
                new CacheContributor(),
            },
        });
        var targets = VersionPicker.SupportedTargets(VersionCatalog.SupportedVersions, VersionManager.OriginSupportedVersions);
        return new BackendParts(folders, engine, targets);
    }

    /// <summary>
    /// The versions an import can go into. The first read builds the parts (it touches VersionManager's static
    /// state), so the scan reads it on the pool before it returns: by the time a panel reads this after a scan
    /// it is already cached.
    /// </summary>
    public IReadOnlyList<string> SupportedTargets => _parts.Value.SupportedTargets;

    public string ImportsRoot => OriginPaths.Imports;

    // Everything below runs on the thread pool (Task.Run), after the recovery, and keeps its continuations
    // there (ConfigureAwait(false)): none of the engine's synchronous probing can land on the UI thread.
    private Task<T> OnPool<T>(Func<BackendParts, Task<T>> body, CancellationToken ct) =>
        Task.Run(() => ImportRecoveryRunner.AfterRecoveryAsync(_recovery(), async () =>
        {
            ct.ThrowIfCancellationRequested();
            var parts = await _parts.GetAsync().ConfigureAwait(false);
            return await body(parts).ConfigureAwait(false);
        }), ct);

    public Task<ScanResult> ScanAsync(IProgress<ImportProgress>? progress, CancellationToken ct) =>
        OnPool(parts => parts.Engine.ScanAsync(ImportRoots.Sources(parts.Folders), ImportEngine.DefaultSources(), progress, ct), ct);

    // Browse: reads only the picked folder (no Origin folder, no engine), so it needs no recovery.
    public Task<FolderInspection> InspectFolderAsync(string folder, CancellationToken ct) =>
        FolderSource.InspectAsync(folder, new SourceFs(), ct);

    // Its own token, replaced only by a newer Browse: a running scan is never cancelled by picking a folder.
    public Task<FolderInspection> InspectPickedFolderAsync(string folder)
    {
        var mine = new CancellationTokenSource();
        Interlocked.Exchange(ref _inspectCts, mine)?.Cancel();
        return FolderSource.InspectAsync(folder, new SourceFs(), mine.Token);
    }

    public Task<ImportPlan> PlanAsync(DetectedInstance source, string? targetVersion, ImportOptions options,
        IProgress<ImportProgress>? progress, CancellationToken ct) =>
        OnPool(async parts =>
        {
            var plan = await parts.Engine.PlanAsync(BuildRequest(parts, source, targetVersion, options), progress, ct).ConfigureAwait(false);
            BlockWhileLaunching(plan.Blocked);
            return plan;
        }, ct);

    public Task<ImportResult> ApplyAsync(ImportPlan plan, IProgress<ImportProgress>? progress, CancellationToken ct)
    {
        if (_dryRun) throw new InvalidOperationException("A dry run never applies.");
        // Not tied to ct up front: a cancel is the engine's to handle (it rolls back and reports Cancelled).
        // The launch check runs after the recovery wait, right before the engine writes: a launch can have
        // started since the plan was made (the panel marks the import running before it calls this).
        return OnPool(parts => LaunchRunState.ImportRefusal() is { } refusal
            ? Task.FromResult(new ImportResult { ImportId = plan.ImportId, State = ImportState.RolledBack, Error = refusal })
            : parts.Engine.ApplyAsync(plan, new LauncherImportSettings(), progress, ct), CancellationToken.None);
    }

    // ---- "import everything": wired to the contract; the engine side is a clearly marked seam ----

    public Task<BulkImportPlan> PlanBulkAsync(BulkSelection selection, ImportOptions options,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct) =>
        OnPool(async parts =>
        {
            var plan = await BulkPlanSeam(parts, selection, options, progress, ct).ConfigureAwait(false);
            BlockWhileLaunching(plan.Blocked);
            return plan;
        }, ct);

    public Task<BulkImportPlan> ReplanGroupAsync(BulkImportPlan plan, string version, DetectedInstance newWinner, CancellationToken ct) =>
        OnPool(async parts =>
        {
            var replanned = await BulkReplanSeam(parts, plan, version, newWinner, ct).ConfigureAwait(false);
            BlockWhileLaunching(replanned.Blocked);
            return replanned;
        }, ct);

    public Task<BulkImportResult> ApplyBulkAsync(BulkImportPlan plan, IProgress<BulkProgress> progress, CancellationToken ct)
    {
        if (_dryRun) throw new InvalidOperationException("A dry run never applies.");
        return OnPool(parts => LaunchRunState.ImportRefusal() is { } refusal
            ? Task.FromResult(new BulkImportResult { RunId = plan.RunId, State = ImportState.RolledBack, Error = refusal })
            : BulkApplySeam(parts, plan, progress, ct), CancellationToken.None);
    }

    // TODO(integrator): the three seams below are the ONLY place the bulk engine plugs in. When ImportEngine gets
    // PlanBulkAsync / ReplanGroupAsync / ApplyBulkAsync, call them here with parts.Engine (and, for the plan,
    // BuildRequest(parts, ...) per setup, plus a new LauncherImportSettings() for apply). Everything around them
    // (recovery wait, thread pool, launch refusal, dry-run refusal) already applies to the real call.
    private static Task<BulkImportPlan> BulkPlanSeam(BackendParts parts, BulkSelection selection, ImportOptions options,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct) =>
        Task.FromResult(NotBuilt());

    private static Task<BulkImportPlan> BulkReplanSeam(BackendParts parts, BulkImportPlan plan, string version,
        DetectedInstance newWinner, CancellationToken ct) =>
        Task.FromResult(NotBuilt());

    private static Task<BulkImportResult> BulkApplySeam(BackendParts parts, BulkImportPlan plan, IProgress<BulkProgress> progress, CancellationToken ct) =>
        Task.FromResult(new BulkImportResult
        {
            RunId = plan.RunId, State = ImportState.RolledBack, Error = "Importing everything isn't built yet. Nothing was changed.",
        });

    private static BulkImportPlan NotBuilt()
    {
        var plan = new BulkImportPlan { RunId = Guid.NewGuid().ToString("N") };
        plan.Blocked.Add("Importing everything isn't built yet.");
        return plan;
    }

    // A launch that is still starting blocks planning too, so the panel says so before the player gets as far as Import.
    private static void BlockWhileLaunching(List<string> blocked)
    {
        if (LaunchRunState.ImportRefusal() is { } refusal && !blocked.Contains(refusal)) blocked.Insert(0, refusal);
    }

    private ImportRequest BuildRequest(BackendParts parts, DetectedInstance source, string? targetVersion, ImportOptions options)
    {
        var settings = SettingsStore.Load();
        // The dry run never opens the accounts file: it plans for the placeholder (offline) folder.
        var account = _dryRun ? InstanceLayout.OfflineKey : AccountFolders.SelectedKey();
        // What the engine will resolve the target to: the picked version, else the source's own when Origin has it.
        var target = !string.IsNullOrWhiteSpace(targetVersion) ? targetVersion
            : parts.SupportedTargets.Contains(source.McVersion ?? "") ? source.McVersion : null;
        return new ImportRequest
        {
            Source = source,
            TargetVersion = targetVersion,
            AccountKey = account,
            Options = options,
            SupportedTargets = parts.SupportedTargets,
            BundledOriginJarPath = target == null ? null : VersionManager.BundledJarFor(target),
            CatalogMods = target == null ? Array.Empty<CatalogMod>() : CatalogModsFor(target),
            ExistingProfiles = settings.ModProfiles,
            PlayWithExternalMods = settings.PlayWithExternalMods,
            SelectedVersion = settings.SelectedVersion,
            SelectedProfileId = settings.SelectedProfileId,
        };
    }

    /// <summary>PerformanceModCatalog's standalone stack for a version, as families the built-in mod set understands.</summary>
    internal static IReadOnlyList<CatalogMod> CatalogModsFor(string version)
    {
        if (PerformanceModCatalog.TryGet(version) is not { } p) return Array.Empty<CatalogMod>();
        var list = new List<CatalogMod>();
        void Add(string family, PerfMod? mod) { if (mod != null) list.Add(new CatalogMod(family, mod.FileName)); }
        Add("sodium", p.Sodium);
        Add("indium", p.Indium);
        Add("lithium", p.Lithium);
        Add("ferritecore", p.Ferrite);
        Add("krypton", p.Krypton);
        Add("iris", p.Iris);
        Add("immediatelyfast", p.ImmediatelyFast);
        Add("modernfix", p.ModernFix);
        Add("modmenu", p.ModMenu);
        return list;
    }
}
