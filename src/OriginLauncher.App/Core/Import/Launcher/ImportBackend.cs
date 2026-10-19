using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.GameFolders.Launcher;
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

    Task<ImportPlan> PlanAsync(DetectedInstance source, string? targetVersion, ImportOptions options,
        IProgress<ImportProgress>? progress, CancellationToken ct);

    Task<ImportResult> ApplyAsync(ImportPlan plan, IProgress<ImportProgress>? progress, CancellationToken ct);
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
/// The real backend: the engine wired to this PC's folders, the launcher's HTTP, version gate, account and
/// settings. <paramref name="dryRun"/> (the --import-plan command) plans against a placeholder account and
/// refuses to apply, so nothing about it can write.
/// </summary>
public sealed class LauncherImportBackend : IImportBackend
{
    private readonly bool _dryRun;
    private readonly ImportEngine _engine;
    private readonly MachineFolders _folders;

    public LauncherImportBackend(bool dryRun = false)
    {
        _dryRun = dryRun;
        _folders = MachineFolders.Current();
        var gameFiles = ImportRoots.GameFiles(_folders, OriginPaths.Instances);
        _engine = new ImportEngine(new ImportEnvironment
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
    }

    public IReadOnlyList<string> SupportedTargets { get; } =
        VersionPicker.SupportedTargets(VersionCatalog.SupportedVersions, VersionManager.OriginSupportedVersions);

    public string ImportsRoot => OriginPaths.Imports;

    public Task<ScanResult> ScanAsync(IProgress<ImportProgress>? progress, CancellationToken ct) =>
        _engine.ScanAsync(ImportRoots.Sources(_folders), ImportEngine.DefaultSources(), progress, ct);

    public Task<FolderInspection> InspectFolderAsync(string folder, CancellationToken ct) =>
        FolderSource.InspectAsync(folder, new SourceFs(), ct);

    public Task<ImportPlan> PlanAsync(DetectedInstance source, string? targetVersion, ImportOptions options,
        IProgress<ImportProgress>? progress, CancellationToken ct) =>
        Task.Run(() => _engine.PlanAsync(BuildRequest(source, targetVersion, options), progress, ct), ct);

    public Task<ImportResult> ApplyAsync(ImportPlan plan, IProgress<ImportProgress>? progress, CancellationToken ct)
    {
        if (_dryRun) throw new InvalidOperationException("A dry run never applies.");
        return _engine.ApplyAsync(plan, new LauncherImportSettings(), progress, ct);
    }

    private ImportRequest BuildRequest(DetectedInstance source, string? targetVersion, ImportOptions options)
    {
        var settings = SettingsStore.Load();
        // The dry run never opens the accounts file: it plans for the placeholder (offline) folder.
        var account = _dryRun ? InstanceLayout.OfflineKey : AccountFolders.SelectedKey();
        // What the engine will resolve the target to: the picked version, else the source's own when Origin has it.
        var target = !string.IsNullOrWhiteSpace(targetVersion) ? targetVersion
            : SupportedTargets.Contains(source.McVersion ?? "") ? source.McVersion : null;
        return new ImportRequest
        {
            Source = source,
            TargetVersion = targetVersion,
            AccountKey = account,
            Options = options,
            SupportedTargets = SupportedTargets,
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
