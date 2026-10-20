using System.IO;
using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.Core.Import;

// The working side of ImportMemory: reads the stored string, remembers what a finished import covered, and splits a scan
// into "on offer" / "already in Origin". It knows nothing about where the string lives or where Origin's folders are:
// the launcher hands in a reader, an updater (the launcher's one settings writer runs the merge on the freshest value)
// and a folder check (Launcher/ImportMemoryStore). Tests hand in their own, so the ordering rules below can fail.
//
// Nothing here can throw into an import or a scan: an unreadable store means "nothing was imported" (the setups are
// simply offered again), and a failed write never fails a finished import.
public sealed class ImportMemoryKeeper
{
    private readonly Func<string?> _read;
    private readonly Action<Func<string?, string?>> _update;
    private readonly Func<string, string, bool> _targetExists;
    private readonly Func<DateTime> _now;
    private readonly Func<DetectedInstance, IReadOnlyDictionary<ImportCategories, string>> _sign;

    /// <param name="read">The stored string (null when there is none).</param>
    /// <param name="update">Runs the given function on the freshest stored string and stores what it returns (one atomic write).</param>
    /// <param name="targetExists">Whether Origin's instance for (account, version) still exists.</param>
    public ImportMemoryKeeper(Func<string?> read, Action<Func<string?, string?>> update, Func<string, string, bool> targetExists,
        Func<DateTime>? now = null, Func<DetectedInstance, IReadOnlyDictionary<ImportCategories, string>>? sign = null)
    {
        _read = read;
        _update = update;
        _targetExists = targetExists;
        _now = now ?? (() => DateTime.UtcNow);
        _sign = sign ?? (s => SetupSignature.Compute(s));
    }

    public List<ImportedSetupRecord> Load()
    {
        try { return ImportMemory.Parse(_read()); }
        catch (Exception) { return new List<ImportedSetupRecord>(); }
    }

    /// <summary>Splits a scan into offered / already imported into <paramref name="account"/>, using what is stored and the sources' current content.</summary>
    public (List<DetectedInstance> Offered, List<DetectedInstance> AlreadyImported) Partition(IEnumerable<DetectedInstance> setups, string? account)
    {
        var all = setups.ToList();
        if (string.IsNullOrWhiteSpace(account)) return (all, new List<DetectedInstance>());
        try
        {
            var records = Load();
            return records.Count == 0 ? (all, new List<DetectedInstance>())
                : ImportMemory.Partition(all, account, records, _targetExists, _sign);
        }
        catch (Exception)
        {
            return (all, new List<DetectedInstance>());
        }
    }

    /// <summary>
    /// What the sources look like right now, per setup. Read BEFORE the copy starts, so a source that changes while the
    /// import runs is offered again afterwards instead of being recorded as imported.
    /// </summary>
    public Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> PreSign(IEnumerable<DetectedInstance> setups)
    {
        var pre = new Dictionary<string, IReadOnlyDictionary<ImportCategories, string>>(StringComparer.OrdinalIgnoreCase);
        try
        {
            foreach (var setup in setups)
                if (!pre.ContainsKey(setup.SetupId)) pre[setup.SetupId] = _sign(setup);
        }
        catch (Exception) { pre.Clear(); }
        return pre;
    }

    /// <summary>A finished single import: remembers its source (the signatures read before the copy) for the account it went into.</summary>
    public void RememberImport(ImportPlan plan, ImportResult result, Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> pre)
    {
        var covered = ImportMemory.CoveredBySingle(plan, result);
        if (covered == null) return;
        var account = AccountOf(plan);
        if (account == null) return;
        Remember(ImportMemory.RecordsFor(new[] { covered }, account, _now(), From(pre)));
    }

    /// <summary>A finished bulk run: remembers every setup that really imported, with what the run covered, for the run's account.</summary>
    public void RememberRun(BulkImportPlan plan, BulkImportResult result, Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> pre)
    {
        var account = plan.Request?.AccountKey;
        if (string.IsNullOrWhiteSpace(account)) return;
        Remember(ImportMemory.RecordsFor(ImportMemory.CoveredByRun(plan, result), account, _now(), From(pre)));
    }

    public void Remember(IReadOnlyCollection<ImportedSetupRecord> fresh)
    {
        if (fresh.Count == 0) return;
        try { _update(stored => ImportMemory.Serialize(ImportMemory.Merge(ImportMemory.Parse(stored), fresh, _targetExists))); }
        catch (Exception) { /* remembering is a nicety: a failed write must never fail a finished import */ }
    }

    // A single plan's account is the last folder of its game folder (instances/<version>/accounts/<account>).
    private static string? AccountOf(ImportPlan plan)
    {
        if (string.IsNullOrWhiteSpace(plan.TargetGameDir)) return null;
        var name = Path.GetFileName(Path.TrimEndingDirectorySeparator(plan.TargetGameDir));
        return string.IsNullOrWhiteSpace(name) ? null : name;
    }

    private static Func<DetectedInstance, ImportCategories, IReadOnlyDictionary<ImportCategories, string>> From(
        Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> pre) =>
        (setup, categories) => pre.TryGetValue(setup.SetupId, out var all)
            ? all.Where(kv => (categories & kv.Key) != 0).ToDictionary(kv => kv.Key, kv => kv.Value)
            : new Dictionary<ImportCategories, string>(); // never pre-read: record nothing rather than a guess
}
