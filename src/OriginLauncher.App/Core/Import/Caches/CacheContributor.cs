using System.IO;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Mods;

namespace OriginLauncher.App.Core.Import.Caches;

// Cache migration (D20-D23): the Distant Horizons and Voxy data the player already built, brought
// across when — and only when — it is certain to belong where it lands and the mod that reads it
// will load. A cache is never worth a crash or a wrong terrain, and it always rebuilds, so every
// doubt means "left in the source; it will rebuild".
//
//  * Identity: (kind, single/multiplayer, world or server, dimension).
//  * Single player: only when Origin already has saves/<same folder> and its level.dat has the same
//    LevelName and seed (LevelDat). If the world is being copied by this import, its caches simply
//    travel inside the world folder.
//  * Multiplayer: the server's whole folder, only where Origin has no such folder (or an empty one);
//    never merged into a folder that has something in it. "Replace existing caches" backs the old one up first.
//  * Compatibility is judged from the imported MOD set: Distant Horizons enabled, same 2.x major, a
//    version at least the source's; Voxy enabled at exactly the source's version. Nothing is ever
//    opened with a SQLite engine, and Voxy's config.json is copied untouched (it has no version
//    field, and an edited one silently resets).
//  * A database group (.sqlite, -wal, -shm) is copied together or not at all; a -wal without its
//    database is skipped. Every file is probed for a lock before the copy and again after it; a lock
//    that appears discards the staged copy ("in use").
public sealed class CacheContributor : IImportPlanContributor
{
    /// <summary>Test seam: called after a cache is copied to staging and before the final lock re-check.</summary>
    public Action<string>? AfterStaged { get; init; }

    public Task ContributeAsync(ImportPlan plan, ImportRequest request, SourceFs fs, CancellationToken ct)
    {
        if (!plan.CanApply) return Task.CompletedTask;
        new Planner(plan, fs, ct, AfterStaged, request?.Ledger, plan.Source.BulkLabel(), request?.SetupKey).Run();
        return Task.CompletedTask;
    }

    private sealed class Planner
    {
        private readonly ImportPlan _plan;
        private readonly SourceFs _fs;
        private readonly CancellationToken _ct;
        private readonly Action<string>? _afterStaged;
        private readonly string _sourceDir;
        private readonly string _targetDir;
        private readonly bool _replace;
        private readonly string? _dhProblem;
        private readonly string? _voxyProblem;
        private readonly Dictionary<string, string?> _worldProblems = new(StringComparer.OrdinalIgnoreCase); // world -> why not (null = fine)
        private readonly DestinationLedger? _ledger; // a bulk run's: the first setup (in apply order) to bring a cache wins
        private readonly string _setupLabel;
        private readonly string? _setupKey;

        public Planner(ImportPlan plan, SourceFs fs, CancellationToken ct, Action<string>? afterStaged, DestinationLedger? ledger, string setupLabel,
            string? setupKey)
        {
            _ledger = ledger;
            _setupKey = setupKey;
            _setupLabel = setupLabel;
            _plan = plan;
            _fs = fs;
            _ct = ct;
            _afterStaged = afterStaged;
            _sourceDir = plan.Source.GameDir;
            _targetDir = plan.TargetGameDir;
            _replace = plan.Options.ReplaceExistingCaches;
            _dhProblem = DhProblem(plan.Mods);
            _voxyProblem = VoxyProblem(plan.Mods);
        }

        public void Run()
        {
            var scan = CacheScanner.Scan(_sourceDir, _fs, _ct);
            foreach (var never in scan.Never)
                Skip(never.Kind, CacheScope.Multiplayer, never.Name, "", never.Reason);

            foreach (var db in scan.DhSinglePlayer) PlanDhSinglePlayer(db);
            foreach (var voxy in scan.Voxy.Where(v => v.Scope == CacheScope.SinglePlayer)) PlanVoxySinglePlayer(voxy);
            foreach (var server in scan.DhServers) PlanDhServer(server);
            foreach (var voxy in scan.Voxy.Where(v => v.Scope == CacheScope.Multiplayer)) PlanVoxyMultiplayer(voxy);
        }

        // ------------------------------------------------------------ single player

        private void PlanDhSinglePlayer(DhDatabase db)
        {
            var label = $"{db.Owner} / {db.DimensionLabel}";
            if (db.LoneWal) { Skip(CacheKind.DistantHorizons, db.Scope, db.Owner, db.Dimension, "A leftover journal file with no database next to it: skipped."); return; }
            if (WorldTravels(db.Owner))
            {
                Travels(CacheKind.DistantHorizons, db.Owner, db.Dimension, label + " (Distant Horizons)");
                return;
            }
            if (WorldProblem(db.Owner) is { } noWorld) { Skip(CacheKind.DistantHorizons, db.Scope, db.Owner, db.Dimension, noWorld); return; }
            if (_dhProblem != null) { Skip(CacheKind.DistantHorizons, db.Scope, db.Owner, db.Dimension, _dhProblem); return; }
            // Locks first: a database a game holds open can't even have its header read.
            if (db.Members.Any(CacheProbes.IsLocked)) { Skip(CacheKind.DistantHorizons, db.Scope, db.Owner, db.Dimension, InUse); return; }
            if (!CacheProbes.LooksLikeSqlite(db.MainPath, _fs, out _)) { Skip(CacheKind.DistantHorizons, db.Scope, db.Owner, db.Dimension, "Not a readable Distant Horizons database: skipped."); return; }

            var destDir = Path.Combine(_targetDir, "saves", db.Owner, db.Dimension.Replace('/', Path.DirectorySeparatorChar), "data");
            if (!_replace && DhGroupNames.Any(n => File.Exists(Path.Combine(destDir, n))))
            {
                Skip(CacheKind.DistantHorizons, db.Scope, db.Owner, db.Dimension, "Origin already has Distant Horizons data for this world: left alone.");
                return;
            }
            var bytes = db.Members.Sum(SizeOf);
            // The group's members are found again when it is copied: a -wal can be checkpointed away meanwhile.
            Take(CacheKind.DistantHorizons, db.Scope, db.Owner, db.Dimension, label + " (Distant Horizons)", bytes,
                ctx => CopyDhGroup(ctx, db.Dir, destDir, label, _replace, _afterStaged));
        }

        private void PlanVoxySinglePlayer(VoxyBase voxy)
        {
            var label = $"{voxy.Owner} (Voxy)";
            if (WorldTravels(voxy.Owner)) { Travels(CacheKind.Voxy, voxy.Owner, "", label); return; }
            if (WorldProblem(voxy.Owner) is { } noWorld) { Skip(CacheKind.Voxy, voxy.Scope, voxy.Owner, "", noWorld); return; }
            PlanVoxy(voxy, label, Path.Combine(_targetDir, "saves", voxy.Owner, "voxy"));
        }

        // ------------------------------------------------------------ multiplayer

        private void PlanDhServer(DhServerFolder server)
        {
            var label = $"{server.DisplayName} (Distant Horizons)";
            if (_dhProblem != null) { SkipServer(server, _dhProblem); return; }
            var dest = Path.Combine(_targetDir, CacheScanner.DhServerRoot, server.Folder);
            if (Occupied(dest) && !_replace)
            {
                SkipServer(server, "Origin already has Distant Horizons data for this server: left alone.");
                return;
            }
            // Locks first: a database a game holds open can't even have its header read.
            if (server.Levels.Any(l => l.Members.Any(CacheProbes.IsLocked))) { SkipServer(server, InUse); return; }
            foreach (var level in server.Levels.Where(l => !l.LoneWal))
                if (!CacheProbes.LooksLikeSqlite(level.MainPath, _fs, out _))
                {
                    SkipServer(server, "Not a readable Distant Horizons database: skipped.");
                    return;
                }
            var bytes = DirectorySize(server.Dir, skipLoneWal: true);
            var identity = Identity(CacheKind.DistantHorizons, CacheScope.Multiplayer, server.Folder, "");
            Action<ImportOpContext> run = ctx => CopyFolder(ctx, server.Dir, dest, label, _replace, skipLoneWal: true, DhProbeFiles, _afterStaged);
            if (!ClaimFirst(identity))
            {
                AddFallback(identity, label, bytes, run, server.Dir, dest, SkipServer(server, AnotherSetup));
                return;
            }

            foreach (var level in server.Levels)
                _plan.Caches.Add(new CachePlanItem(CacheKind.DistantHorizons, CacheScope.Multiplayer, server.Folder, level.Dimension,
                    !level.LoneWal, level.LoneWal ? "A leftover journal file with no database next to it: skipped." : null, 0));
            _plan.Lines.Add(new SummaryLine(SummarySection.ReusedCaches, PlanItemKind.Cache, label, null, bytes));
            _plan.Ops.Add(new PlannedOp
            {
                Kind = OpKind.Custom, Item = PlanItemKind.Cache, Label = label, Source = server.Dir, Dest = dest, Bytes = bytes,
                Run = run, ClaimKey = _ledger == null ? null : DestinationLedger.CacheKey(_targetDir, identity),
            });
        }

        private void PlanVoxyMultiplayer(VoxyBase voxy)
        {
            var label = $"{voxy.Owner} (Voxy)";
            PlanVoxy(voxy, label, Path.Combine(_targetDir, ".voxy", "saves", voxy.Owner));
        }

        private void PlanVoxy(VoxyBase voxy, string label, string dest)
        {
            if (_voxyProblem != null) { Skip(CacheKind.Voxy, voxy.Scope, voxy.Owner, "", _voxyProblem); return; }
            if (Occupied(dest) && !_replace)
            {
                Skip(CacheKind.Voxy, voxy.Scope, voxy.Owner, "", "Origin already has Voxy data here: left alone.");
                return;
            }
            if (VoxyLocks(voxy.Dir).Any(CacheProbes.IsLocked)) { Skip(CacheKind.Voxy, voxy.Scope, voxy.Owner, "", InUse); return; }
            var bytes = DirectorySize(voxy.Dir, skipLoneWal: false);
            Take(CacheKind.Voxy, voxy.Scope, voxy.Owner, "", label, bytes,
                ctx => CopyFolder(ctx, voxy.Dir, dest, label, _replace, skipLoneWal: false, VoxyProbeFiles, _afterStaged));
        }

        // ------------------------------------------------------------ decisions

        private static readonly string[] DhGroupNames = DhDatabase.GroupNames;

        // True when this import copies the world folder itself: its caches travel inside it.
        private bool WorldTravels(string world)
        {
            var source = Path.GetFullPath(Path.Combine(_sourceDir, "saves", world));
            return _plan.Ops.Any(o => o.Item == PlanItemKind.World && o.Kind == OpKind.CopyTree && o.Source != null
                                      && SamePath(Path.GetFullPath(o.Source), source));
        }

        // Why a single-player cache can't be paired with an Origin world (null = it can): the destination
        // world must exist with the same LevelName and seed.
        private string? WorldProblem(string world)
        {
            if (_worldProblems.TryGetValue(world, out var known)) return known;
            const string none = "No matching Origin world — left in source.";
            string? problem = none;
            var sourceDat = Path.Combine(_sourceDir, "saves", world, "level.dat");
            var destDat = Path.Combine(_targetDir, "saves", world, "level.dat");
            if (File.Exists(sourceDat) && File.Exists(destDat))
            {
                var source = LevelDat.Read(sourceDat, _fs);
                var dest = LevelDat.Read(destDat, _fs);
                if (source != null && source.Matches(dest)) problem = null;
            }
            _worldProblems[world] = problem;
            return problem;
        }

        // A bulk run: one cache identity (kind, scope, world or server, dimension) comes over once, from the first
        // setup in apply order; a later setup's copy is never merged into it. A single import has no ledger.
        private static string Identity(CacheKind kind, CacheScope scope, string owner, string dimension) => $"{kind}|{scope}|{owner}|{dimension}";

        private bool ClaimFirst(string identity) => _ledger == null || _ledger.TryClaimCache(identity, _setupLabel, out _);

        // If the setup that claimed this cache is skipped mid-run, this setup's copy is brought instead (once).
        private void AddFallback(string identity, string label, long bytes, Action<ImportOpContext> run, string? source, string dest,
            SummaryLine deferredLine)
        {
            if (_setupKey == null) return;
            _plan.Ops.Add(new PlannedOp
            {
                Kind = OpKind.Custom, Item = PlanItemKind.Cache, Label = label, Source = source, Dest = dest, Bytes = bytes, Run = run,
                ClaimKey = DestinationLedger.CacheKey(_targetDir, identity), Fallback = true, DeferredLine = deferredLine,
            });
        }

        private void Take(CacheKind kind, CacheScope scope, string owner, string dimension, string label, long bytes, Action<ImportOpContext> run)
        {
            var identity = Identity(kind, scope, owner, dimension);
            if (!ClaimFirst(identity))
            {
                AddFallback(identity, label, bytes, run, null, _targetDir, Skip(kind, scope, owner, dimension, AnotherSetup));
                return;
            }
            _plan.Caches.Add(new CachePlanItem(kind, scope, owner, dimension, true, null, bytes));
            _plan.Lines.Add(new SummaryLine(SummarySection.ReusedCaches, PlanItemKind.Cache, label, null, bytes));
            _plan.Ops.Add(new PlannedOp
            {
                Kind = OpKind.Custom, Item = PlanItemKind.Cache, Label = label, Dest = _targetDir, Bytes = bytes, Run = run,
                ClaimKey = _ledger == null ? null : DestinationLedger.CacheKey(_targetDir, identity),
            });
        }

        private void Travels(CacheKind kind, string owner, string dimension, string label)
        {
            _plan.Caches.Add(new CachePlanItem(kind, CacheScope.SinglePlayer, owner, dimension, true, "travels with the world copy", 0));
            _plan.Lines.Add(new SummaryLine(SummarySection.ReusedCaches, PlanItemKind.Cache, label, "travels with the world copy"));
        }

        private SummaryLine Skip(CacheKind kind, CacheScope scope, string owner, string dimension, string reason)
        {
            _plan.Caches.Add(new CachePlanItem(kind, scope, owner, dimension, false, reason, 0));
            var what = kind == CacheKind.Voxy ? "Voxy" : "Distant Horizons";
            var name = dimension.Length == 0 ? $"{owner} ({what})" : $"{owner} / {dimension} ({what})";
            var line = new SummaryLine(SummarySection.NotImported, PlanItemKind.Cache, name, reason);
            _plan.Lines.Add(line);
            return line;
        }

        private SummaryLine SkipServer(DhServerFolder server, string reason)
        {
            foreach (var level in server.Levels)
                _plan.Caches.Add(new CachePlanItem(CacheKind.DistantHorizons, CacheScope.Multiplayer, server.Folder, level.Dimension, false, reason, 0));
            var line = new SummaryLine(SummarySection.NotImported, PlanItemKind.Cache, $"{server.DisplayName} (Distant Horizons)", reason);
            _plan.Lines.Add(line);
            return line;
        }

        // Distant Horizons: the imported mod set must run a DH of the same 2.x line and at least the source's version.
        internal static string? DhProblem(IReadOnlyList<ModDecision> mods)
        {
            var all = mods.Where(m => m.ModId == "distanthorizons").ToList();
            if (all.Count == 0)
                return "Distant Horizons isn't among the mods being imported, so it will rebuild — left in the source.";
            var enabled = all.Where(m => m.Outcome == ModOutcome.Copied).ToList();
            if (enabled.Count == 0)
            {
                var why = all[0];
                return why.Outcome switch
                {
                    ModOutcome.CopiedDisabled => $"Distant Horizons is imported turned off ({why.Reason?.TrimEnd('.')}), so it will rebuild — left in the source.",
                    ModOutcome.CopiedAsBefore => "Distant Horizons was turned off in your old setup, so it will rebuild — left in the source.",
                    _ => $"Distant Horizons isn't coming across ({why.Reason?.TrimEnd('.')}), so it will rebuild — left in the source.",
                };
            }
            var source = Highest(all);
            var destination = Highest(enabled);
            if (!FabricVersion.TryParse(source, out var s) || !FabricVersion.TryParse(destination, out var d))
                return "Distant Horizons' version couldn't be compared, so it will rebuild — left in the source.";
            if (s.Core[0] != 2 || d.Core[0] != 2)
                return $"Distant Horizons {(s.Core[0] != 2 ? "1.x" : "this")} data can't be used by Distant Horizons 2.x, so it will rebuild — left in the source.";
            if (d.CompareTo(s) < 0)
                return "The imported Distant Horizons is older than the one that built this data, so it will rebuild — left in the source.";
            return null;
        }

        // Voxy: enabled in the imported set at exactly the version that wrote the data.
        internal static string? VoxyProblem(IReadOnlyList<ModDecision> mods)
        {
            var all = mods.Where(m => m.ModId == "voxy").ToList();
            if (all.Count == 0)
                return "Voxy isn't among the mods being imported, so it will rebuild — left in the source.";
            var enabled = all.Where(m => m.Outcome == ModOutcome.Copied).ToList();
            if (enabled.Count == 0)
                return "Voxy isn't coming across turned on, so it will rebuild — left in the source.";
            return string.Equals(Highest(enabled), Highest(all), StringComparison.Ordinal)
                ? null
                : "The imported Voxy is a different version from the one that built this data, so it will rebuild — left in the source.";
        }

        private static string? Highest(IEnumerable<ModDecision> decisions) =>
            decisions.Select(d => d.Version).OrderByDescending(v => v, Comparer<string?>.Create(FabricVersion.Compare)).FirstOrDefault();

        // ------------------------------------------------------------ files

        private static bool Occupied(string dir) =>
            File.Exists(dir) || (Directory.Exists(dir) && Directory.EnumerateFileSystemEntries(dir).Any());

        private long DirectorySize(string dir, bool skipLoneWal)
        {
            long total = 0;
            foreach (var file in _fs.EnumerateFiles(dir, recursive: true))
            {
                if (skipLoneWal && IsLoneWal(file)) continue;
                total += SizeOf(file);
            }
            return total;
        }

        private IEnumerable<string> VoxyLocks(string baseDir) => VoxyProbeFiles(_fs, baseDir);
    }

    // ================================================================ apply

    // A size that can't be read (gone, no access) counts as nothing: a size is never a reason to fail the plan.
    private static long SizeOf(string file) => ImportFiles.SizeOf(file);

    private static bool SamePath(string a, string b) =>
        string.Equals(Path.TrimEndingDirectorySeparator(a), Path.TrimEndingDirectorySeparator(b),
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);

    private static bool IsLoneWal(string file) =>
        file.EndsWith(DhDatabase.MainName + "-wal", StringComparison.OrdinalIgnoreCase)
        && !File.Exists(file[..^"-wal".Length]);

    // The files whose lock means "a game has this open": every database file / Voxy's RocksDB LOCK.
    private static IEnumerable<string> DhProbeFiles(SourceFs fs, string dir) =>
        fs.EnumerateFiles(dir, recursive: true).Where(f =>
            Path.GetFileName(f).StartsWith(DhDatabase.MainName, StringComparison.OrdinalIgnoreCase));

    private static IEnumerable<string> VoxyProbeFiles(SourceFs fs, string dir) =>
        fs.EnumerateFiles(dir, recursive: true).Where(f => Path.GetFileName(f).Equals("LOCK", StringComparison.Ordinal));

    private static void CopyDhGroup(ImportOpContext ctx, string sourceDir, string destDir, string label, bool replace, Action<string>? afterStaged)
    {
        ctx.Report(ImportStage.Caches, 0, 1, label);
        ctx.Ct.ThrowIfCancellationRequested();
        // The group as it is NOW (not as planned): SQLite may have checkpointed the -wal away since.
        var group = DhDatabase.GroupNames.Select(n => Path.Combine(sourceDir, n)).Where(File.Exists).ToList();
        if (!File.Exists(Path.Combine(sourceDir, DhDatabase.MainName))) { Left(ctx, label, Rebuilds); return; }
        if (!ctx.Fs.IsLinkFree(sourceDir) || group.Any(f => !ctx.Fs.IsLinkFree(f))) { Left(ctx, label, Rebuilds); return; }
        if (group.Any(CacheProbes.IsLocked)) { Left(ctx, label, InUse); return; }

        if (!replace && DhDatabase.GroupNames.Any(n => File.Exists(Path.Combine(destDir, n))))
        {
            Left(ctx, label, "Origin already has Distant Horizons data for this world: left alone.");
            return;
        }

        // The whole group goes to staging first; only a complete, unlocked group is ever placed.
        var stage = ctx.Tx.NewStagingPath("dh-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(stage);
        try
        {
            foreach (var file in group)
            {
                ctx.Ct.ThrowIfCancellationRequested();
                if (!ctx.Fs.Allow(file)) { Discard(stage); Left(ctx, label, "Skipped for safety."); return; }
                File.Copy(file, Path.Combine(stage, Path.GetFileName(file)), overwrite: true);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // A cache is never worth the import: a file that vanished or is held (an antivirus scan) leaves it to rebuild.
            Discard(stage); Left(ctx, label, Rebuilds); return;
        }
        afterStaged?.Invoke(label);
        if (group.Any(CacheProbes.IsLocked)) { Discard(stage); Left(ctx, label, InUse); return; }

        // Replacing a group means all of it: stale -wal / -shm files of the old copy would corrupt the new one.
        foreach (var name in DhDatabase.GroupNames)
        {
            var destFile = Path.Combine(destDir, name);
            var staged = Path.Combine(stage, name);
            if (File.Exists(staged)) ctx.Tx.PlaceFile(staged, destFile);
            else ctx.Tx.RemoveWithBackup(destFile);
        }
        ctx.Tx.Log($"copied cache {label}");
    }

    private static void CopyFolder(ImportOpContext ctx, string sourceDir, string destDir, string label, bool replace, bool skipLoneWal,
        Func<SourceFs, string, IEnumerable<string>> probeFiles, Action<string>? afterStaged)
    {
        ctx.Report(ImportStage.Caches, 0, 1, label);
        ctx.Ct.ThrowIfCancellationRequested();
        // The walk follows the folder it starts at: that folder must not be a link.
        if (!ctx.Fs.IsLinkFree(sourceDir)) { Left(ctx, label, Rebuilds); return; }
        if (probeFiles(ctx.Fs, sourceDir).Any(CacheProbes.IsLocked)) { Left(ctx, label, InUse); return; }

        var occupied = File.Exists(destDir) || (Directory.Exists(destDir) && Directory.EnumerateFileSystemEntries(destDir).Any());
        if (occupied && !replace) { Left(ctx, label, "Origin already has data here: left alone."); return; }

        var stage = ctx.Tx.NewStagingPath("cache-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(stage);
        var skippedUnreadable = 0;
        var unreadableBefore = ctx.Fs.UnreadableHits; // every hit of THIS walk counts, even a short name seen before
        try
        {
            foreach (var file in ctx.Fs.EnumerateFiles(sourceDir, recursive: true))
            {
                ctx.Ct.ThrowIfCancellationRequested();
                if (skipLoneWal && IsLoneWal(file)) continue;
                if (!ctx.Fs.AllowWalked(file)) { skippedUnreadable++; continue; } // the walk refused links; sourceDir was checked above
                var target = Path.Combine(stage, Path.GetRelativePath(sourceDir, file));
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                File.Copy(file, target, overwrite: true); // as-is: nothing in a cache folder is ever edited
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Discard(stage); Left(ctx, label, Rebuilds); return;
        }
        afterStaged?.Invoke(label);
        if (probeFiles(ctx.Fs, sourceDir).Any(CacheProbes.IsLocked)) { Discard(stage); Left(ctx, label, InUse); return; }
        if (skippedUnreadable > 0 || ctx.Fs.UnreadableHits > unreadableBefore) { Discard(stage); Left(ctx, label, "Some of it couldn't be read, so none of it was copied."); return; }

        if (Directory.Exists(destDir) || File.Exists(destDir)) ctx.Tx.RemoveWithBackup(destDir);
        ctx.Tx.PlaceDirectory(stage, destDir);
        ctx.Tx.Log($"copied cache {label}");
    }

    private const string InUse = "In use right now, so it was left in the source.";
    private const string AnotherSetup = "Another of your setups already brings this one.";
    private const string Rebuilds = "Couldn't be read; it will rebuild — left in the source.";


    private static void Left(ImportOpContext ctx, string label, string reason) =>
        ctx.Extra.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.Cache, label, reason));

    private static void Discard(string stage)
    {
        try { if (Directory.Exists(stage)) Directory.Delete(stage, recursive: true); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* removed with the run's staging folder */ }
    }
}
