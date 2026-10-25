using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Import.Mods;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.Tests.Import;

/// <summary>
/// Many setups at once for the bulk ("import everything") tests: each setup is its own source folder under the rig's
/// temp root, plus the Origin instances/ + imports/ folders and the fake settings of a <see cref="Rig"/>.
/// Nothing here looks at a real launcher or the real %LocalAppData%\OriginLauncher.
/// </summary>
internal sealed class BulkFixture : IDisposable
{
    public Rig Rig { get; } = new();
    public List<DetectedInstance> Setups { get; } = new();

    /// <summary>The rig's supported versions plus 1.20.1 (a Fabric version for the Forge packs-only rule).</summary>
    public static readonly string[] Supported = Rig.Supported.Concat(new[] { "1.20.1" }).ToArray();

    public string SetupsRoot => Path.Combine(Rig.Root, "setups");

    public void Dispose() => Rig.Dispose();

    /// <summary>Adds a scanned setup (and, unless <paramref name="populate"/> is false, a realistic folder for it).</summary>
    /// <summary>The "not sure" reason the fixture gives an inferred setup (the engine must show it, not a stock sentence).</summary>
    public const string GuessReason = "The launcher says 1.21.1, but the game last ran 1.21.4.";

    public DetectedInstance Add(string name, string? mc = "1.21.1", SourceKind kind = SourceKind.Prism, LoaderKind loader = LoaderKind.Fabric,
        long? playtimeHours = null, bool pinned = false, DateTimeOffset? lastPlayed = null, Confidence confidence = Confidence.Exact,
        bool populate = true, string? gameDir = null, string? problem = null)
    {
        var dir = gameDir ?? Path.Combine(SetupsRoot, $"{Setups.Count:D2}-{kind}-{name}");
        if (populate && gameDir == null) TypicalSource.Populate(dir);
        else Directory.CreateDirectory(dir);
        var setup = new DetectedInstance
        {
            Kind = kind, Name = name, GameDir = dir, McVersion = mc, Loader = loader, Pinned = pinned,
            PlaytimeSeconds = playtimeHours * 3600, LastPlayed = lastPlayed, Confidence = confidence, Problem = problem,
            // As the detectors write it (EvidenceJudge): an inferred version is not certain, and says why.
            VersionCertain = confidence != Confidence.Inferred,
            UncertainReason = confidence == Confidence.Inferred ? GuessReason : null,
        };
        Setups.Add(setup);
        return setup;
    }

    /// <summary>The import grid with every cell of every launcher and version ticked (each launcher's most played setup brings its mods).</summary>
    public ImportGrid AllTicked()
    {
        var grid = new ImportGrid();
        foreach (var s in Setups.Where(s => s.McVersion != null)) grid.Set(s.McVersion!, s.Kind, ImportGrid.Things);
        return grid;
    }

    public string Dir(DetectedInstance setup, string rel = "") => Path.Combine(setup.GameDir, rel.Replace('/', Path.DirectorySeparatorChar));

    public string Target(string version, string rel = "") => Path.Combine(Rig.GameDir(version), rel.Replace('/', Path.DirectorySeparatorChar));

    public BulkRequest Request(BulkSelection? selection = null, ImportOptions? options = null, string? account = Rig.Account,
        IReadOnlyDictionary<string, string>? choices = null, bool playWithExternalMods = true, ImportGrid? grid = null) => new()
    {
        Setups = Setups.ToList(),
        Selection = selection ?? BulkSelection.All(),
        AccountKey = account,
        Options = options ?? new ImportOptions(),
        SupportedTargets = Supported,
        Facts = v => v == "1.21.1"
            ? new TargetFacts { BundledOriginJarPath = Rig.BundledJar }
            : new TargetFacts { CatalogMods = new[] { new CatalogMod("sodium", "sodium-fabric-0.8.12.jar"), new CatalogMod("iris", "iris-fabric-1.10.7.jar") } },
        WinnerChoices = choices ?? new Dictionary<string, string>(),
        Grid = grid,
        PlayWithExternalMods = playWithExternalMods,
    };

    public ImportEngine Engine(IReadOnlyList<IImportPlanContributor>? contributors = null, Func<string, long>? freeSpace = null,
        Func<string, bool>? isRunning = null, IVersionGate? versionGate = null) =>
        EngineBKit.Engine(Rig, contributors ?? Array.Empty<IImportPlanContributor>(), freeSpace, isRunning, versionGate);

    public async Task<BulkImportPlan> Plan(ImportEngine engine, BulkRequest? request = null, IProgress<BulkPlanProgress>? progress = null) =>
        await engine.PlanBulkAsync(request ?? Request(), progress, CancellationToken.None);

    /// <summary>Hashes of every setup's source folder (with modification times): a source must never change.</summary>
    public Dictionary<string, string> SourceHashes() =>
        Setups.Select(s => s.GameDir).Distinct().ToDictionary(d => d, d => Kit.TreeHash(d, mtimes: true));
}

/// <summary>Collects bulk progress synchronously (Progress&lt;T&gt; posts asynchronously).</summary>
internal sealed class SyncBulkProgress<T> : IProgress<T>
{
    private readonly Action<T> _on;
    public List<T> Seen { get; } = new();
    public SyncBulkProgress(Action<T>? on = null) => _on = on ?? (_ => { });
    public void Report(T value)
    {
        lock (Seen) Seen.Add(value);
        _on(value);
    }
}

/// <summary>
/// A version-wide contributor standing in for game-file reuse: one small file written into the version root through
/// the transaction, with the version's gate. Records how often it planned each version.
/// </summary>
internal sealed class FakeVersionWork : IVersionWideContributor
{
    private readonly IVersionGate? _gate;
    private readonly string _instances;
    public List<string> PlannedVersions { get; } = new();

    public FakeVersionWork(string instances, IVersionGate? gate)
    {
        _instances = instances;
        _gate = gate;
    }

    public string FileFor(string version) => Path.Combine(_instances, version, "fake-version-work.txt");

    public Task ContributeAsync(ImportPlan plan, ImportRequest request, SourceFs fs, CancellationToken ct)
    {
        PlannedVersions.Add(plan.TargetVersion);
        var dest = FileFor(plan.TargetVersion);
        plan.Ops.Add(new PlannedOp
        {
            Kind = OpKind.Custom, Item = PlanItemKind.GameFile, Label = "version work", Dest = dest, Bytes = 10,
            Run = ctx => ctx.Tx.WriteBytes(dest, "VERSION-WORK"u8.ToArray(), false),
        });
        plan.ProvisioningGate = _gate;
        return Task.CompletedTask;
    }
}

/// <summary>A per-setup contributor that adds one custom step to the setup whose name matches; the step runs <paramref name="run"/>.</summary>
internal sealed class StepFor : IImportPlanContributor
{
    private readonly string _setupName;
    private readonly Action<ImportOpContext> _run;

    public StepFor(string setupName, Action<ImportOpContext> run)
    {
        _setupName = setupName;
        _run = run;
    }

    public Task ContributeAsync(ImportPlan plan, ImportRequest request, SourceFs fs, CancellationToken ct)
    {
        if (plan.Source.Name == _setupName)
            plan.Ops.Add(new PlannedOp { Kind = OpKind.Custom, Item = PlanItemKind.Cache, Label = "step", Dest = plan.TargetGameDir, Run = _run });
        return Task.CompletedTask;
    }
}

/// <summary>
/// A gate per version with real mutual exclusion that records the order it is taken in. A second acquire of a
/// version it already holds would wait forever (like the launcher's): here it fails after a few seconds instead.
/// </summary>
internal sealed class RecordingGate : IVersionGate
{
    private readonly Dictionary<string, SemaphoreSlim> _locks = new();
    public List<string> Acquired { get; } = new();
    public List<string> Released { get; } = new();

    public SemaphoreSlim LockFor(string version)
    {
        lock (_locks)
        {
            if (!_locks.TryGetValue(version, out var s)) _locks[version] = s = new SemaphoreSlim(1, 1);
            return s;
        }
    }

    public async Task<IAsyncDisposable> AcquireAsync(string version, CancellationToken ct)
    {
        if (!await LockFor(version).WaitAsync(TimeSpan.FromSeconds(5), ct))
            throw new TimeoutException($"the gate for {version} was taken twice");
        lock (Acquired) Acquired.Add(version);
        return new Lease(this, version);
    }

    private sealed class Lease : IAsyncDisposable
    {
        private readonly RecordingGate _gate;
        private readonly string _version;
        public Lease(RecordingGate gate, string version) { _gate = gate; _version = version; }
        public ValueTask DisposeAsync()
        {
            lock (_gate.Released) _gate.Released.Add(_version);
            _gate.LockFor(_version).Release();
            return ValueTask.CompletedTask;
        }
    }
}
