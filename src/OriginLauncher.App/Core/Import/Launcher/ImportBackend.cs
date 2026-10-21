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
    /// <summary>
    /// The Minecraft versions an import can go into (the version picker's list that also has an Origin build).
    /// Built on the thread pool: the first call touches VersionManager's static state, which must never happen on the UI thread.
    /// </summary>
    Task<IReadOnlyList<string>> GetSupportedTargetsAsync(CancellationToken ct) =>
#pragma warning disable CS0618 // the compatibility default for backends that only have the property
        Task.FromResult(SupportedTargets);
#pragma warning restore CS0618

    /// <summary>
    /// Compatibility shim: NEVER read this from the UI thread before <see cref="GetSupportedTargetsAsync"/> or a scan
    /// has completed (the real backend builds it lazily and a first read does disk and static-init work). New code
    /// awaits <see cref="GetSupportedTargetsAsync"/>.
    /// </summary>
    [Obsolete("Use GetSupportedTargetsAsync: reading this on the UI thread can do disk work.")]
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
    /// <param name="grid">"Choose what to import"'s grid (who brings what per version); null = the defaults.</param>
    Task<BulkImportPlan> PlanBulkAsync(BulkSelection selection, ImportOptions options, ImportGrid? grid,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct);

    /// <summary>The player picked a different settings winner for one version: re-plans that group only.</summary>
    Task<BulkImportPlan> ReplanGroupAsync(BulkImportPlan plan, string version, DetectedInstance newWinner, CancellationToken ct);

    /// <summary>
    /// The player ticked or unticked setups: re-plans only the version groups whose setups changed, keeping the settings
    /// winners already picked (they travel in the plan's request). The default plans afresh for backends without it.
    /// </summary>
    Task<BulkImportPlan> ReplanSelectionAsync(BulkImportPlan plan, BulkSelection selection, ImportGrid? grid,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct) =>
        PlanBulkAsync(selection, plan.Request?.Options ?? new ImportOptions(), grid, progress, ct);

    Task<BulkImportResult> ApplyBulkAsync(BulkImportPlan plan, IProgress<BulkProgress> progress, CancellationToken ct);

    // ---- the drawer's launcher tiles and version rows (Core/Import/SetupInventory) ----

    /// <summary>
    /// The launchers installed on this PC (their folder exists), so a tile can say "Not installed" apart from
    /// "Installed, nothing to import". Null = unknown (the default: tiles then only say whether setups were found).
    /// </summary>
    Task<IReadOnlySet<SourceKind>?> GetInstalledLaunchersAsync(CancellationToken ct) =>
        Task.FromResult<IReadOnlySet<SourceKind>?>(null);

    /// <summary>What each setup holds (worlds, packs, mods...), by <see cref="DetectedInstance.SetupId"/>. Folder listings only, on the thread pool.</summary>
    Task<IReadOnlyDictionary<string, SetupContents>> GetContentsAsync(IReadOnlyList<DetectedInstance> setups, CancellationToken ct) =>
        Task.Run<IReadOnlyDictionary<string, SetupContents>>(() =>
        {
            var map = new Dictionary<string, SetupContents>(StringComparer.Ordinal);
            foreach (var setup in setups)
            {
                ct.ThrowIfCancellationRequested();
                map[setup.SetupId] = SetupInventory.Read(setup);
            }
            return map;
        }, ct);
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

    // The setups the last scan found: a bulk plan covers every one of them (selected or not), and the panel only
    // plans after its scan. Replaced whole by each scan (never mutated), so a plan reads one consistent list.
    private IReadOnlyList<DetectedInstance>? _lastScan;

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

    /// <summary>The versions an import can go into, built on the thread pool (and cached for every later call).</summary>
    public async Task<IReadOnlyList<string>> GetSupportedTargetsAsync(CancellationToken ct) =>
        (await _parts.GetAsync().WaitAsync(ct).ConfigureAwait(false)).SupportedTargets;

    /// <summary>
    /// Compatibility shim for a panel that still reads the property. Free once any scan/plan/GetSupportedTargetsAsync
    /// has built the parts (the panel only reads it after a scan); before that it builds on the calling thread.
    /// </summary>
    [Obsolete("Use GetSupportedTargetsAsync: reading this on the UI thread can do disk work.")]
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
        OnPool(async parts =>
        {
            var scan = await ScanCoreAsync(parts, progress, ct).ConfigureAwait(false);
            // What was already brought over and has not changed is not offered again (ImportMemory). A dry run
            // (--import-plan) shows everything: it is a diagnostic, not the player's list.
            if (!_dryRun)
            {
                var (offered, done) = ImportMemoryStore.Partition(scan.Instances, AccountFolders.SelectedKey());
                if (done.Count > 0)
                {
                    scan.Instances.Clear();
                    scan.Instances.AddRange(offered);
                    scan.AlreadyImported.AddRange(done);
                }
            }
            Volatile.Write(ref _lastScan, scan.Instances.ToList());
            return scan;
        }, ct);

    public Task<IReadOnlySet<SourceKind>?> GetInstalledLaunchersAsync(CancellationToken ct) =>
        Task.Run<IReadOnlySet<SourceKind>?>(async () =>
        {
            var parts = await _parts.GetAsync().WaitAsync(ct).ConfigureAwait(false);
            return SetupInventory.Installed(ImportRoots.Sources(parts.Folders));
        }, ct);

    private static Task<ScanResult> ScanCoreAsync(BackendParts parts, IProgress<ImportProgress>? progress, CancellationToken ct) =>
        parts.Engine.ScanAsync(ImportRoots.Sources(parts.Folders), ImportEngine.DefaultSources(), progress, ct);

    // Browse: reads only the picked folder (no Origin folder, no engine), so it needs no recovery.
    public Task<FolderInspection> InspectFolderAsync(string folder, CancellationToken ct) =>
        FolderSource.InspectAsync(folder, new SourceFs(), ct);

    // Its own token, replaced only by a newer Browse: a running scan is never cancelled by picking a folder. What it
    // finds joins the setups a bulk plan covers (one folder is one setup: a folder already found is not added twice).
    public async Task<FolderInspection> InspectPickedFolderAsync(string folder)
    {
        var mine = new CancellationTokenSource();
        Interlocked.Exchange(ref _inspectCts, mine)?.Cancel();
        var found = await FolderSource.InspectAsync(folder, new SourceFs(), mine.Token).ConfigureAwait(false);
        if (found.Instances.Count > 0) AddSetups(found.Instances);
        return found;
    }

    private void AddSetups(IEnumerable<DetectedInstance> picked)
    {
        while (true)
        {
            var before = Volatile.Read(ref _lastScan);
            var merged = (before ?? Array.Empty<DetectedInstance>()).ToList();
            foreach (var setup in picked)
                if (merged.All(m => m.SetupId != setup.SetupId)) merged.Add(setup);
            if (ReferenceEquals(Interlocked.CompareExchange(ref _lastScan, merged, before), before)) return;
        }
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
        return OnPool(async parts =>
        {
            if (LaunchRunState.ImportRefusal() is { } refusal)
                return new ImportResult { ImportId = plan.ImportId, State = ImportState.RolledBack, Error = refusal };
            var pre = ImportMemoryStore.PreSign(new[] { plan.Source });
            var result = await parts.Engine.ApplyAsync(plan, new LauncherImportSettings(), progress, ct).ConfigureAwait(false);
            ImportMemoryStore.RememberImport(plan, result, pre); // only a committed import is remembered
            return result;
        }, CancellationToken.None);
    }

    // ---- "import everything": the engine's bulk plan / re-plan / apply (Core/Import/Bulk) ----

    public Task<BulkImportPlan> PlanBulkAsync(BulkSelection selection, ImportOptions options, ImportGrid? grid,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct) =>
        OnPool(async parts =>
        {
            // The panel always scans first; a caller that didn't gets one here.
            var setups = Volatile.Read(ref _lastScan);
            if (setups == null)
            {
                setups = (await ScanCoreAsync(parts, null, ct).ConfigureAwait(false)).Instances.ToList();
                Volatile.Write(ref _lastScan, setups);
            }
            var plan = await parts.Engine.PlanBulkAsync(BuildBulkRequest(parts, setups, selection, options) with { Grid = grid }, progress, ct).ConfigureAwait(false);
            BlockWhileLaunching(plan.Blocked);
            return plan;
        }, ct);

    public Task<BulkImportPlan> ReplanGroupAsync(BulkImportPlan plan, string version, DetectedInstance newWinner, CancellationToken ct) =>
        OnPool(async parts =>
        {
            var replanned = await parts.Engine.ReplanGroupAsync(Fresh(plan), version, newWinner, null, ct).ConfigureAwait(false);
            BlockWhileLaunching(replanned.Blocked);
            return replanned;
        }, ct);

    public Task<BulkImportPlan> ReplanSelectionAsync(BulkImportPlan plan, BulkSelection selection, ImportGrid? grid,
        IProgress<BulkPlanProgress>? progress, CancellationToken ct) =>
        OnPool(async parts =>
        {
            var replanned = await parts.Engine.ReplanSelectionAsync(Fresh(plan), selection, grid, progress, ct).ConfigureAwait(false);
            BlockWhileLaunching(replanned.Blocked);
            return replanned;
        }, ct);

    public Task<BulkImportResult> ApplyBulkAsync(BulkImportPlan plan, IProgress<BulkProgress> progress, CancellationToken ct)
    {
        if (_dryRun) throw new InvalidOperationException("A dry run never applies.");
        // As ApplyAsync: the launch check runs after the recovery wait, right before the engine writes, and a cancel
        // is the engine's to handle (it rolls back and reports Cancelled), so ct is not tied to the pool task.
        return OnPool(async parts =>
        {
            if (LaunchRunState.ImportRefusal() is { } refusal)
                return new BulkImportResult { RunId = plan.RunId, State = ImportState.RolledBack, Error = refusal, Skipped = plan.Skipped.ToList() };
            var pre = ImportMemoryStore.PreSign(plan.AllSetups.Select(s => s.Setup));
            var result = await parts.Engine.ApplyBulkAsync(plan, new LauncherImportSettings(), progress, ct).ConfigureAwait(false);
            ImportMemoryStore.RememberRun(plan, result, pre); // only the setups that really imported are remembered
            return result;
        }, CancellationToken.None);
    }

    /// <summary>
    /// A re-plan starts from the plan's request, whose external-mods switch may be minutes old: it is read again, so the
    /// plan's "your own mods are off" note follows what is saved now. (Imports change no launcher setting: no profile,
    /// no selection.)
    /// </summary>
    private static BulkImportPlan Fresh(BulkImportPlan plan)
    {
        if (plan.Request is not { } request) return plan;
        var settings = SettingsStore.Load();
        var refreshed = new BulkImportPlan
        {
            RunId = plan.RunId,
            PlannedAt = plan.PlannedAt,
            BytesToCopy = plan.BytesToCopy,
            Request = request with { PlayWithExternalMods = settings.PlayWithExternalMods },
        };
        refreshed.Groups.AddRange(plan.Groups);
        refreshed.Skipped.AddRange(plan.Skipped);
        refreshed.Blocked.AddRange(plan.Blocked);
        refreshed.Notes.AddRange(plan.Notes);
        return refreshed;
    }

    private BulkRequest BuildBulkRequest(BackendParts parts, IReadOnlyList<DetectedInstance> setups, BulkSelection selection, ImportOptions options)
    {
        var settings = SettingsStore.Load();
        return new BulkRequest
        {
            Setups = setups,
            Selection = selection,
            // The dry run never opens the accounts file: it plans for the placeholder (offline) folder.
            AccountKey = _dryRun ? InstanceLayout.OfflineKey : AccountFolders.SelectedKey(),
            Options = options,
            SupportedTargets = parts.SupportedTargets,
            Facts = TargetFactsFor,
            PlayWithExternalMods = settings.PlayWithExternalMods,
        };
    }

    /// <summary>What the launcher knows about one target version: the bundled Origin jar (its built-ins) and the catalog's mods.</summary>
    internal static TargetFacts TargetFactsFor(string version) => new()
    {
        BundledOriginJarPath = VersionManager.BundledJarFor(version),
        CatalogMods = CatalogModsFor(version),
    };

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
            PlayWithExternalMods = settings.PlayWithExternalMods,
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
        Add("entityculling", p.EntityCulling);
        return list;
    }
}
