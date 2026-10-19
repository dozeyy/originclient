using System.Diagnostics;
using System.IO;
using System.Text;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.GameFiles;
using OriginLauncher.App.Core.Import.Mods;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import;

/// <summary>
/// Everything the engine needs from the machine, as parameters: folders, the
/// clock, free space, "is the game running". Nothing here reads the launcher's data folders or
/// Environment folders, so tests give it temp folders and fakes and never touch
/// the real %LocalAppData%\OriginLauncher.
/// </summary>
public sealed class ImportEnvironment
{
    /// <summary>instances/ (InstanceLayout's root).</summary>
    public required string InstancesRoot { get; init; }

    /// <summary>imports/ (ImportLayout's root).</summary>
    public required string ImportsRoot { get; init; }

    public Func<DateTime> Clock { get; init; } = () => DateTime.UtcNow;

    /// <summary>Free bytes on the volume holding a path (D30).</summary>
    public Func<string, long> FreeSpace { get; init; } = DriveFreeSpace;

    /// <summary>True when a game is running from this game folder (latest.log held open).</summary>
    public Func<string, bool> IsGameRunning { get; init; } = GameRunningProbe.IsHeld;

    /// <summary>Makes ids: import ids and profile ids (32 lower-case hex).</summary>
    public Func<string> NewId { get; init; } = ProfileRules.NewId;

    public CredentialGuard Guard { get; init; } = CredentialGuard.Default;

    /// <summary>Told about every source file the engine opens (the credential test records these).</summary>
    public Action<string>? OnSourceOpen { get; init; }

    /// <summary>Scan timing and other one-line facts (the glue sends them to the launcher log).</summary>
    public Action<string>? Log { get; init; }

    /// <summary>Plan steps added later (game-file reuse, caches).</summary>
    public IReadOnlyList<IImportPlanContributor> Contributors { get; init; } = Array.Empty<IImportPlanContributor>();

    /// <summary>The versions' provisioning gate, held while a finished import is undone (null = none to take).</summary>
    public IVersionGate? VersionGate { get; init; }

    private static long DriveFreeSpace(string path)
    {
        try
        {
            var root = Path.GetPathRoot(Path.GetFullPath(path));
            return string.IsNullOrEmpty(root) ? long.MaxValue : new DriveInfo(root).AvailableFreeSpace;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException or UnauthorizedAccessException)
        {
            return long.MaxValue; // unknown: don't block an import on a probe that failed
        }
    }
}

/// <summary>A plan step a later unit supplies: adds ops and summary lines to the plan (GameFile / Cache items).</summary>
public interface IImportPlanContributor
{
    Task ContributeAsync(ImportPlan plan, ImportRequest request, SourceFs fs, CancellationToken ct);
}

/// <summary>
/// A contributor whose work belongs to the target VERSION, not to one setup (game-file reuse: the files a version
/// needs are the same whichever setup is imported). A single import runs it like any other; a bulk import runs it
/// once per version group instead of once per setup.
/// </summary>
public interface IVersionWideContributor : IImportPlanContributor
{
}

/// <summary>"Is Minecraft running from this folder?": its latest.log cannot be opened exclusively.</summary>
public static class GameRunningProbe
{
    public static bool IsHeld(string gameDir)
    {
        var log = Path.Combine(gameDir, "logs", "latest.log");
        if (!File.Exists(log)) return false;
        try
        {
            using var _ = new FileStream(log, FileMode.Open, FileAccess.Read, FileShare.None);
            return false;
        }
        catch (IOException)
        {
            return true;
        }
        catch (UnauthorizedAccessException)
        {
            return true;
        }
    }
}

/// <summary>What the player chose on the import panel, plus the launcher facts a plan needs.</summary>
public sealed record ImportRequest
{
    public required DetectedInstance Source { get; init; }

    /// <summary>The Origin version to import into; null = the source's own version when Origin supports it.</summary>
    public string? TargetVersion { get; init; }

    /// <summary>The account whose game folder receives the import (InstanceLayout key); null/empty = nobody signed in yet.</summary>
    public string? AccountKey { get; init; }
    public ImportOptions Options { get; init; } = new();

    /// <summary>VersionPicker.SupportedTargets: the catalog's versions that also have an Origin build.</summary>
    public required IReadOnlyCollection<string> SupportedTargets { get; init; }

    /// <summary>The bundled originclient-&lt;jar&gt;.jar for the target (its jar-in-jar mods are Origin's built-ins); null when not found.</summary>
    public string? BundledOriginJarPath { get; init; }

    /// <summary>PerformanceModCatalog's mods for the target, mapped to families.</summary>
    public IReadOnlyList<CatalogMod> CatalogMods { get; init; } = Array.Empty<CatalogMod>();

    public bool? TargetHasIris { get; init; }

    // The launcher settings as they are now (the plan names its delta against these).
    public IReadOnlyList<ModProfile> ExistingProfiles { get; init; } = Array.Empty<ModProfile>();
    public bool PlayWithExternalMods { get; init; } = true;
    public string? SelectedVersion { get; init; }
    public string? SelectedProfileId { get; init; }

    // ---- set by a bulk import ("import everything") for each setup it plans; a single import leaves them alone ----

    /// <summary>What this setup brings to its version group (a single import is always Full).</summary>
    internal SetupRole Role { get; init; } = SetupRole.Full;

    /// <summary>The target folder's ledger, shared by every setup of the version in one run (null = a private one).</summary>
    internal DestinationLedger? Ledger { get; init; }

    /// <summary>The run's id: every setup of a bulk run shares it (null = a new id).</summary>
    internal string? ImportId { get; init; }

    /// <summary>Part of a bulk run: no per-setup space check, no per-setup settings notes (the run checks and says them once).</summary>
    internal bool Bulk { get; init; }

    /// <summary>The setup whose settings this version gets in a bulk run (for the "settings come from" line).</summary>
    internal string? SettingsWinnerLabel { get; init; }

    /// <summary>This setup's key in a bulk run (who owns a ledger claim); null in a single import.</summary>
    internal string? SetupKey { get; init; }

    /// <summary>One metadata session for every version a bulk plan looks at (null = fetch per plan).</summary>
    internal MetadataCache? Metadata { get; init; }
}

// Scan -> plan -> apply, each async, cancellable and progress-reporting, and
// none of them touching the UI thread. The engine only ever READS the source;
// everything it writes to the destination goes through ImportTransaction, so a
// cancel, an error or a crash puts the player's folders back exactly as they were.
public sealed partial class ImportEngine
{
    private readonly ImportEnvironment _env;

    public ImportEngine(ImportEnvironment env) => _env = env;

    /// <summary>The detectors for every launcher this build knows.</summary>
    public static IReadOnlyList<IInstanceSource> DefaultSources() => new IInstanceSource[]
    {
        new OfficialLauncherSource(),
        new PrismSource(),
        new ModrinthSource(),
        new CurseForgeSource(),
    };

    // ------------------------------------------------------------------ scan

    public async Task<ScanResult> ScanAsync(SourceRoots roots, IReadOnlyList<IInstanceSource> sources,
        IProgress<ImportProgress>? progress, CancellationToken ct)
    {
        var watch = Stopwatch.StartNew();
        var result = new ScanResult();
        var fs = new SourceFs(_env.Guard, _env.OnSourceOpen);
        for (var i = 0; i < sources.Count; i++)
        {
            ct.ThrowIfCancellationRequested();
            progress?.Report(new ImportProgress(ImportStage.Scanning, i, sources.Count, sources[i].Kind.ToString()));
            try
            {
                await sources[i].DetectAsync(roots, fs, result, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                // A detector never takes the scan down (D4): say so and carry on with the next launcher.
                result.Notes.Add($"Couldn't look through {sources[i].Kind}");
            }
        }
        watch.Stop();
        result.Elapsed = watch.Elapsed;
        _env.Log?.Invoke($"import scan: {result.Instances.Count} instances in {watch.ElapsedMilliseconds} ms");
        progress?.Report(new ImportProgress(ImportStage.Scanning, sources.Count, sources.Count));
        return result;
    }

    // ------------------------------------------------------------------ plan

    public Task<ImportPlan> PlanAsync(ImportRequest request, IProgress<ImportProgress>? progress, CancellationToken ct) =>
        Task.Run(async () =>
        {
            var plan = BuildPlan(request, progress, ct, out var fs);
            if (plan.CanApply)
                foreach (var contributor in _env.Contributors)
                    await contributor.ContributeAsync(plan, request, fs, ct).ConfigureAwait(false);
            Finish(plan, fs, checkSpace: true);
            return plan;
        }, ct);

    private ImportPlan BuildPlan(ImportRequest r, IProgress<ImportProgress>? progress, CancellationToken ct, out SourceFs fs)
    {
        fs = new SourceFs(_env.Guard, _env.OnSourceOpen) { InstanceRoot = r.Source.GameDir };
        var source = r.Source;

        // The target: what the player picked, else the source's own version when Origin has it.
        var target = !string.IsNullOrWhiteSpace(r.TargetVersion) ? r.TargetVersion!
            : r.SupportedTargets.Contains(source.McVersion ?? "") ? source.McVersion! : "";
        var plan = new ImportPlan
        {
            ImportId = r.ImportId ?? _env.NewId(), Source = source, TargetVersion = target, Options = r.Options,
        };

        // ---- blockers: any of these means no plan, with a plain sentence for the player ----
        var support = VersionPicker.CheckSupport(string.IsNullOrEmpty(target) ? source.McVersion : target, r.SupportedTargets);
        if (!support.Supported) plan.Blocked.Add(support.Reason ?? "Pick a Minecraft version Origin supports.");

        var account = r.AccountKey;
        var accountOk = !string.IsNullOrWhiteSpace(account) && InstanceLayout.IsAccountKey(account);
        if (!accountOk) plan.Blocked.Add("Sign in (or turn on offline test mode) first.");

        if (!Directory.Exists(source.GameDir)) plan.Blocked.Add("Couldn't find the old instance's folder.");

        if (support.Supported && accountOk)
            plan.TargetGameDir = InstanceLayout.GameDir(_env.InstancesRoot, target, account!);

        // Never import from Origin's own folders (Browse to an Origin instance, or a parent that contains them):
        // the copy would read and write the same files.
        if (Overlaps(source.GameDir, _env.InstancesRoot) || Overlaps(source.GameDir, _env.ImportsRoot)
            || (plan.TargetGameDir.Length > 0 && Overlaps(source.GameDir, plan.TargetGameDir)))
            plan.Blocked.Add("That folder is part of Origin's own game folders, so it can't be imported from. Pick an instance from another launcher.");

        if (Directory.Exists(source.GameDir) && _env.IsGameRunning(source.GameDir))
            plan.Blocked.Add("Close Minecraft first: it is still running from the instance you're importing.");
        if (plan.TargetGameDir.Length > 0 && Directory.Exists(plan.TargetGameDir) && _env.IsGameRunning(plan.TargetGameDir))
            plan.Blocked.Add("Close Minecraft first: Origin's game is still running.");
        if (!plan.CanApply) return plan;

        progress?.Report(new ImportProgress(ImportStage.Planning, 0, 3, source.Name));

        // ---- mods -> a new profile (a packs-only setup's mods are never even read) ----
        if (r.Role != SetupRole.PacksOnly) PlanMods(plan, r, fs, target, ct);
        progress?.Report(new ImportProgress(ImportStage.Planning, 1, 3, "settings"));

        // ---- settings, packs, worlds ----
        SettingsMigrator.Plan(new MigrationContext
        {
            Fs = fs, Source = source, TargetVersion = target, TargetGameDir = plan.TargetGameDir,
            Options = r.Options, TargetHasIris = r.TargetHasIris ?? target != "1.8.9", Plan = plan, Ct = ct,
            Role = r.Role, Ledger = r.Ledger ?? new DestinationLedger(), SetupLabel = source.BulkLabel(),
            SettingsWinnerLabel = r.SettingsWinnerLabel, SetupKey = r.SetupKey,
        });
        progress?.Report(new ImportProgress(ImportStage.Planning, 2, 3, "checking space"));
        return plan;
    }

    private void PlanMods(ImportPlan plan, ImportRequest r, SourceFs fs, string target, CancellationToken ct)
    {
        BuiltInModSet builtIns;
        Stream? bundled = null;
        try
        {
            if (r.BundledOriginJarPath != null && File.Exists(r.BundledOriginJarPath))
                bundled = new FileStream(r.BundledOriginJarPath, FileMode.Open, FileAccess.Read, FileShare.Read);
            builtIns = BuiltInModSet.Build(target, bundled, r.CatalogMods);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            builtIns = BuiltInModSet.Build(target, null, r.CatalogMods);
        }
        finally
        {
            bundled?.Dispose();
        }
        var is189 = target == "1.8.9";
        plan.BuiltInListIncomplete = builtIns.Incomplete && !is189;
        if (plan.BuiltInListIncomplete)
            plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Mod, "Origin's built-in mod list",
                "Origin's built-in mod list is incomplete for this version, so a mod Origin already includes may have been copied too."));

        var sourceMods = ModSetResolver.ReadSourceMods(Path.Combine(plan.Source.GameDir, "mods"), fs, ct);
        var decisions = ModSetResolver.Resolve(sourceMods, new ResolveContext(target, builtIns, JavaFor(target)));
        plan.Mods.AddRange(decisions);

        if (decisions.Any(d => d.IsCopied))
        {
            plan.ProfileId = _env.NewId();
            plan.ProfileName = MakeProfileName(plan.Source, target, r.ExistingProfiles);
        }

        foreach (var d in decisions)
        {
            var label = d.DisplayName ?? d.ModId ?? d.FileName;
            switch (d.Outcome)
            {
                case ModOutcome.Copied:
                case ModOutcome.CopiedAsBefore:
                case ModOutcome.CopiedDisabled:
                    if (ImportFiles.SizeOrNull(d.SourcePath!) is not { } bytes)
                    {
                        // It vanished (or became unreadable) since it was read: left out, never a reason to fail the plan.
                        plan.Lines.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.Mod, $"{label} ({d.FileName})",
                            "It disappeared from the old setup while Origin was looking at it."));
                        break;
                    }
                    plan.Ops.Add(new PlannedOp
                    {
                        Kind = OpKind.CopyFile, Item = PlanItemKind.Mod, Label = d.DestFileName!, Source = d.SourcePath,
                        Dest = Path.Combine(InstanceLayout.ProfileModsDir(_env.InstancesRoot, target, plan.ProfileId!), d.DestFileName!),
                        Bytes = bytes,
                    });
                    plan.Lines.Add(d.Outcome == ModOutcome.CopiedDisabled
                        ? new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Mod, label, "Imported but turned off: " + d.Reason, bytes)
                        : new SummaryLine(SummarySection.Imported, PlanItemKind.Mod, label,
                            d.Outcome == ModOutcome.CopiedAsBefore ? "imported turned off, as it was" : null, bytes));
                    break;
                default:
                    plan.Lines.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.Mod, $"{label} ({d.FileName})", d.Reason));
                    break;
            }
        }

        if (plan.ProfileId != null)
        {
            plan.SettingsDelta = new SettingsDelta
            {
                AddProfile = new ModProfile { Id = plan.ProfileId, Name = plan.ProfileName!, Version = target },
                SetSelectedVersion = target,
                SetSelectedProfileId = plan.ProfileId,
                PreviousSelectedVersion = r.SelectedVersion,
                PreviousSelectedProfileId = r.SelectedProfileId,
            };
            // Imported mods only load when "personal mods" is on; we never flip that switch for the player.
            // (A bulk run says this once for the whole run, not once per setup.)
            if (!r.PlayWithExternalMods && !r.Bulk)
                plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Mod, "Your own mods are turned off",
                    "The imported mods are in the new profile, but \"Play with my own mods\" is off, so they won't load until you turn it on."));
        }
        else
        {
            plan.SettingsDelta = new SettingsDelta { SetSelectedVersion = target, PreviousSelectedVersion = r.SelectedVersion };
        }
    }

    // True when one path is the other or inside it (full paths; case-insensitive where the file system is).
    private static bool Overlaps(string a, string b)
    {
        if (string.IsNullOrWhiteSpace(a) || string.IsNullOrWhiteSpace(b)) return false;
        var comparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
        string Full(string p) => Path.TrimEndingDirectorySeparator(Path.GetFullPath(p)) + Path.DirectorySeparatorChar;
        try
        {
            var x = Full(a);
            var y = Full(b);
            return x.StartsWith(y, comparison) || y.StartsWith(x, comparison);
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return false;
        }
    }

    private void Finish(ImportPlan plan, SourceFs fs, bool checkSpace)
    {
        if (!plan.CanApply) return;
        foreach (var link in fs.SkippedLinks)
            plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Setting, $"Linked item left out: {link}",
                "Links inside a setup's folder are never followed, so what it points to wasn't imported."));
        foreach (var unreadable in fs.UnreadablePaths)
            plan.Lines.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Setting, $"Couldn't read: {unreadable}",
                "Some of it may be missing from the import (and from the space check)."));
        foreach (var name in fs.SkippedForSafety)
        {
            plan.SkippedForSafety.Add(name);
            plan.Lines.Add(new SummaryLine(SummarySection.NotImported, PlanItemKind.Setting, name,
                "Skipped for safety: it looks like a login or account file."));
        }
        plan.BytesToCopy = plan.Ops.Where(o => !o.Fallback).Sum(o => o.Bytes); // a stand-in replaces a copy, it never adds one
        if (!checkSpace) return; // a bulk run checks the space for all of its setups at once

        // D30: the bytes to copy plus 10% must fit where they land.
        var needed = (long)(plan.BytesToCopy * 1.1);
        var free = _env.FreeSpace(_env.InstancesRoot);
        if (needed > free)
            plan.Blocked.Add($"Not enough free disk space: the import needs about {ImportSummary.Megabytes(needed)} and {ImportSummary.Megabytes(free)} is free.");
    }

    // "Minecraft: Hypixel" for the new profile: the source's name, unique among that version's profiles, within the 32-character limit.
    internal static string MakeProfileName(DetectedInstance source, string version, IReadOnlyList<ModProfile> existing)
    {
        var raw = new string($"{source.SourceLabel}: {source.Name}".Where(c => !char.IsControl(c)).ToArray()).Trim();
        if (raw.Length == 0) raw = "Imported";
        for (var n = 1; ; n++)
        {
            var suffix = n == 1 ? "" : $" {n}";
            var room = ProfileRules.MaxNameLength - suffix.Length;
            var candidate = (raw.Length > room ? raw[..room].TrimEnd() : raw) + suffix;
            if (ProfileRules.ValidateName(candidate, version, existing) == null) return candidate;
        }
    }

    // The Java each era runs (only used for "java" requirements in mods' depends).
    internal static string JavaFor(string mcVersion) =>
        McVersion.AtLeast(mcVersion, "26") ? "25"
        : McVersion.AtLeast(mcVersion, "1.20.5") ? "21"
        : McVersion.AtLeast(mcVersion, "1.18") ? "17"
        : McVersion.AtLeast(mcVersion, "1.17") ? "16"
        : "8";

    // ------------------------------------------------------------------ apply

    // Always completes with an ImportResult: whatever goes wrong, the caller (the import panel) gets a sentence
    // and a state, never a faulted task.
    public Task<ImportResult> ApplyAsync(ImportPlan plan, IImportSettings settings,
        IProgress<ImportProgress>? progress, CancellationToken ct) =>
        Task.Run(() =>
        {
            try
            {
                return Apply(plan, settings, progress, ct);
            }
            catch (Exception ex) when (ex is not OutOfMemoryException)
            {
                return new ImportResult
                {
                    ImportId = plan.ImportId, State = ImportState.RolledBack,
                    Error = $"The import didn't finish ({ImportErrors.Plain(ex)}). If anything was changed, Origin puts it back the next time it starts.",
                };
            }
        }, CancellationToken.None);

    private ImportResult Apply(ImportPlan plan, IImportSettings settings, IProgress<ImportProgress>? progress, CancellationToken ct)
    {
        if (!plan.CanApply)
            return new ImportResult { ImportId = plan.ImportId, State = ImportState.Planned, Error = string.Join(" ", plan.Blocked) };

        // The plan can be minutes old: a game started since must stop the import before it changes anything.
        if (Directory.Exists(plan.Source.GameDir) && _env.IsGameRunning(plan.Source.GameDir))
            return new ImportResult { ImportId = plan.ImportId, State = ImportState.Planned, Error = "Close Minecraft first: it is still running from the instance you're importing." };
        if (plan.TargetGameDir.Length > 0 && Directory.Exists(plan.TargetGameDir) && _env.IsGameRunning(plan.TargetGameDir))
            return new ImportResult { ImportId = plan.ImportId, State = ImportState.Planned, Error = "Close Minecraft first: Origin's game is still running." };

        var logRoot = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(_env.InstancesRoot)) ?? _env.InstancesRoot;
        using var tx = new ImportTransaction(_env.ImportsRoot, logRoot, plan.ImportId, _env.Clock, plan.Source.Name, plan.TargetVersion, _env.Guard,
            plan.TargetGameDir);
        var fs = new SourceFs(_env.Guard, _env.OnSourceOpen) { InstanceRoot = plan.Source.GameDir };
        var extra = new List<SummaryLine>();
        IAsyncDisposable? gate = null; // D28: held while shared version files are written (released in finally)
        try
        {
            tx.Begin();
            if (plan.ProvisioningGate != null)
            {
                gate = plan.ProvisioningGate.AcquireAsync(plan.TargetVersion, ct).GetAwaiter().GetResult();
                // Checked again WITH the gate held: a launch of this version holds the gate while it starts the game,
                // so only now is "not running" a fact that stays true for the rest of the apply.
                if (plan.TargetGameDir.Length > 0 && Directory.Exists(plan.TargetGameDir) && _env.IsGameRunning(plan.TargetGameDir))
                {
                    tx.Rollback(settings); // nothing was changed yet: this only closes the journal
                    return new ImportResult { ImportId = plan.ImportId, State = ImportState.Planned, Error = "Close Minecraft first: Origin's game is still running." };
                }
            }
            for (var i = 0; i < plan.Ops.Count; i++)
            {
                var op = plan.Ops[i];
                if (op.Fallback) continue; // only a bulk run has stand-ins (and runs them when a claim is released)
                progress?.Report(new ImportProgress(StageOf(op.Item), i, plan.Ops.Count, op.Label));
                ct.ThrowIfCancellationRequested();
                Execute(op, plan, tx, fs, ct, extra, progress);
            }

            progress?.Report(new ImportProgress(ImportStage.Finishing, plan.Ops.Count, plan.Ops.Count));
            ct.ThrowIfCancellationRequested(); // the last chance to cancel: past here the settings change is made
            if (plan.SettingsDelta != null)
            {
                tx.RecordSettingsDelta(plan.SettingsDelta);
                settings.Apply(plan.SettingsDelta);
            }
            tx.Commit();
            var text = ImportSummary.Render(plan, extra);
            WriteSummary(plan.ImportId, text);
            return new ImportResult { ImportId = plan.ImportId, State = ImportState.Committed, SummaryText = text };
        }
        catch (OperationCanceledException)
        {
            return RolledBack(plan, tx, settings, extra, cancelled: true, error: null);
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            return RolledBack(plan, tx, settings, extra, cancelled: false,
                error: $"The import didn't finish ({ImportErrors.Plain(ex)}). Everything was put back the way it was.");
        }
        finally
        {
            ReleaseGate(gate);
        }
    }

    // A single import's journal names one version (its gate is taken whenever there is a gate to take); a bulk run's
    // names the versions its apply gated, already in ascending order.
    private static IEnumerable<string> UndoGateVersions(ImportJournal journal)
    {
        if (journal.SetupCount != null) return journal.GateVersions ?? new List<string>();
        return string.IsNullOrEmpty(journal.TargetVersion) ? Array.Empty<string>() : new[] { journal.TargetVersion };
    }

    private static IEnumerable<string> TargetDirs(ImportJournal journal)
    {
        var dirs = new List<string>();
        if (!string.IsNullOrEmpty(journal.TargetGameDir)) dirs.Add(journal.TargetGameDir);
        if (journal.TargetGameDirs != null) dirs.AddRange(journal.TargetGameDirs.Where(d => !string.IsNullOrEmpty(d)));
        return dirs.Distinct(StringComparer.OrdinalIgnoreCase);
    }

    // Releasing the gate happens after the result is decided (often after Commit): a release that throws is
    // logged and never turns a committed import into a "rolled back" report.
    private void ReleaseGate(IAsyncDisposable? gate)
    {
        if (gate == null) return;
        try
        {
            gate.DisposeAsync().AsTask().GetAwaiter().GetResult();
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            _env.Log?.Invoke($"import: releasing the version gate failed ({ImportErrors.Plain(ex)})");
        }
    }

    private ImportResult RolledBack(ImportPlan plan, ImportTransaction tx, IImportSettings settings,
        List<SummaryLine> extra, bool cancelled, string? error)
    {
        var problems = tx.Rollback(settings); // does not throw
        var word = cancelled ? "Cancelled" : "Failed";
        var outcome = $"{word}: nothing was changed.";
        if (problems.Count > 0)
            outcome = $"{word}, but some changes couldn't be undone: " + string.Join(" ", problems);
        var text = ImportSummary.Render(plan, extra, outcome);
        WriteSummary(plan.ImportId, text);
        return new ImportResult
        {
            ImportId = plan.ImportId, State = ImportState.RolledBack, Cancelled = cancelled,
            Error = problems.Count > 0 ? string.Join(" ", new[] { error }.Where(e => e != null).Concat(problems)) : error,
            SummaryText = text,
        };
    }

    private void Execute(PlannedOp op, ImportPlan plan, ImportTransaction tx, SourceFs fs, CancellationToken ct,
        List<SummaryLine> extra, IProgress<ImportProgress>? progress)
    {
        switch (op.Kind)
        {
            case OpKind.Custom: // a contributor's step (game files, caches)
                op.Run!(new ImportOpContext { Tx = tx, Fs = fs, Plan = plan, Ct = ct, Extra = extra, Progress = progress });
                break;
            case OpKind.CopyFile:
                if (!fs.Allow(op.Source!))
                {
                    // Left alone, and said so — never a reason to abandon the whole import.
                    extra.Add(new SummaryLine(SummarySection.NotImported, op.Item, Path.GetFileName(op.Source!),
                        "Skipped for safety: it looks like a login or account file."));
                    break;
                }
                tx.CopyFile(op.Source!, op.Dest, op.TouchNow);
                break;
            case OpKind.CopyTree:
                tx.CopyTree(fs, op.Source!, op.Dest, ct);
                break;
            case OpKind.WriteText:
                tx.WriteBytes(op.Dest, new UTF8Encoding(false).GetBytes(op.Text ?? ""), op.TouchNow);
                break;
            case OpKind.WriteOptions:
                var wrote = tx.Rewrite(op.Dest,
                    temp => SettingsMigrator.ApplyOptions(temp, op.Desired!, op.TargetVersion!, plan.TargetGameDir, op.Extra), op.TouchNow);
                if (!wrote && !File.Exists(op.Dest))
                    extra.Add(new SummaryLine(SummarySection.NeedsAttention, PlanItemKind.Setting, "options.txt",
                        $"Couldn't create the settings file for Minecraft {plan.TargetVersion} yet; start the game once and import again to carry your settings over."));
                break;
            case OpKind.MergeProperties:
                tx.Rewrite(op.Dest, temp => SettingsMigrator.ApplyIris(temp, op.Desired!, op.TargetVersion!, plan.TargetGameDir), op.TouchNow);
                break;
            case OpKind.MergeJson:
                tx.Rewrite(op.Dest, temp => SettingsMigrator.ApplyJson(temp, op.Desired!, op.TargetVersion!, plan.TargetGameDir), op.TouchNow);
                break;
        }
    }

    private static ImportStage StageOf(PlanItemKind kind) => kind switch
    {
        PlanItemKind.Mod => ImportStage.Mods,
        PlanItemKind.Setting or PlanItemKind.Config => ImportStage.Settings,
        PlanItemKind.ResourcePack or PlanItemKind.ShaderPack => ImportStage.Packs,
        PlanItemKind.World => ImportStage.Worlds,
        PlanItemKind.GameFile => ImportStage.GameFiles,
        _ => ImportStage.Caches,
    };

    private void WriteSummary(string importId, string text)
    {
        try { AtomicFile.WriteAllText(ImportLayout.Summary(_env.ImportsRoot, importId), text); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* the summary is a courtesy */ }
    }

    // ------------------------------------------------------------------ undo a finished import

    /// <summary>
    /// Undoes an import that already finished (the player's "undo"): files go back
    /// from the import's backup folder, new files are removed, the launcher
    /// settings are reverted. Backups of only the newest few imports are kept.
    /// </summary>
    // Not reachable from the UI yet (no "undo" button); ImportRetention keeps the newest 3 imports' backups for it.
    public Task<ImportResult> RollbackAsync(string importId, IImportSettings settings) =>
        Task.Run(() =>
        {
            try
            {
                if (!ImportLayout.IsValidId(importId))
                    return new ImportResult { ImportId = importId, State = ImportState.Planned, Error = "That import wasn't found." };
                var journal = ImportJournalStore.Load(ImportLayout.Journal(_env.ImportsRoot, importId));
                if (journal == null)
                    return new ImportResult { ImportId = importId, State = ImportState.Planned, Error = "That import wasn't found." };
                if (journal.State == ImportState.RolledBack)
                    return new ImportResult { ImportId = importId, State = ImportState.RolledBack };

                // Undoing a FINISHED import must never destroy anything: it refuses when its backups were
                // cleared, or when the player has used what it created (a world played in, settings changed).
                // (A crashed or cancelled apply is different: nobody has used those files, so it always rolls back.)
                if (journal.State == ImportState.Committed && ImportUndo.CannotUndoReason(journal) is { } why)
                    return new ImportResult { ImportId = importId, State = ImportState.Planned, Error = why };

                var committed = journal.State == ImportState.Committed;

                // The versions' gates FIRST, so nothing installs or launches those versions while their folders change —
                // and only with the gates held is "no game is running" a fact (a launch holds its version's gate while
                // it starts the game). A bulk run takes the same versions in the same (ascending) order its apply did.
                var gates = new List<IAsyncDisposable>();
                try
                {
                    if (committed && _env.VersionGate != null)
                        foreach (var version in UndoGateVersions(journal))
                            gates.Add(_env.VersionGate.AcquireAsync(version, CancellationToken.None).GetAwaiter().GetResult());

                    // A finished import's files may be in use: never undo under a running game (any of a bulk run's folders).
                    if (committed && TargetDirs(journal).Any(dir => Directory.Exists(dir) && _env.IsGameRunning(dir)))
                        return new ImportResult { ImportId = importId, State = ImportState.Planned, Error = "Close Minecraft first: Origin's game is still running." };

                    var logRoot = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(_env.InstancesRoot)) ?? _env.InstancesRoot;
                    using var tx = new ImportTransaction(_env.ImportsRoot, logRoot, journal, _env.Clock);
                    // Reused game files stay after a finished import (JournalEntry.GameFile).
                    var problems = tx.Rollback(settings, keepGameFiles: committed);
                    return new ImportResult
                    {
                        ImportId = importId, State = ImportState.RolledBack,
                        Error = problems.Count > 0 ? string.Join(" ", problems) : null,
                    };
                }
                finally
                {
                    for (var i = gates.Count - 1; i >= 0; i--) ReleaseGate(gates[i]);
                }
            }
            catch (Exception ex) when (ex is not OutOfMemoryException)
            {
                return new ImportResult { ImportId = importId, State = ImportState.Planned, Error = $"Couldn't undo the import ({ImportErrors.Plain(ex)})." };
            }
        });
}
