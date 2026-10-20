using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.Core.Import.Launcher;

// The disk side of ImportMemory (the pure rules): reads and writes LauncherSettings.ImportedSetupsJson. Writes go
// through SettingsStore.Update only (the merge runs inside the update, on the freshest settings), and every read
// is defensive: nothing here can throw into an import or a scan, and a store that cannot be read means "nothing
// was imported" (the setups are simply offered again).
internal static class ImportMemoryStore
{
    public static List<ImportedSetupRecord> Load()
    {
        try { return ImportMemory.Parse(SettingsStore.Load().ImportedSetupsJson); }
        catch (Exception) { return new List<ImportedSetupRecord>(); }
    }

    /// <summary>Splits a scan into offered / already imported, using what is stored and the sources' current content.</summary>
    public static (List<DetectedInstance> Offered, List<DetectedInstance> AlreadyImported) Partition(IEnumerable<DetectedInstance> setups)
    {
        var all = setups.ToList();
        try
        {
            var records = Load();
            return records.Count == 0 ? (all, new List<DetectedInstance>()) : ImportMemory.Partition(all, records);
        }
        catch (Exception)
        {
            return (all, new List<DetectedInstance>());
        }
    }

    public static void Remember(IReadOnlyCollection<ImportedSetupRecord> fresh)
    {
        if (fresh.Count == 0) return;
        try { SettingsStore.Update(s => s.ImportedSetupsJson = ImportMemory.Serialize(ImportMemory.Merge(ImportMemory.Parse(s.ImportedSetupsJson), fresh))); }
        catch (Exception) { /* remembering is a nicety: a failed write must never fail a finished import */ }
    }

    /// <summary>
    /// What the sources look like right now, per setup. Read BEFORE the copy starts, so a source that changes while the
    /// import runs is offered again afterwards instead of being recorded as imported.
    /// </summary>
    public static Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> PreSign(IEnumerable<DetectedInstance> setups)
    {
        var pre = new Dictionary<string, IReadOnlyDictionary<ImportCategories, string>>(StringComparer.OrdinalIgnoreCase);
        try
        {
            foreach (var setup in setups)
                if (!pre.ContainsKey(setup.SetupId)) pre[setup.SetupId] = SetupSignature.Compute(setup);
        }
        catch (Exception) { pre.Clear(); }
        return pre;
    }

    /// <summary>A finished single import: remembers its source (the signatures read before the copy).</summary>
    public static void RememberImport(ImportPlan plan, ImportResult result, Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> pre)
    {
        if (result.State != ImportState.Committed) return;
        Remember(ImportMemory.RecordsFor(new[] { (plan.Source, ImportMemory.Covered(plan)) }, DateTime.UtcNow, From(pre)));
    }

    /// <summary>A finished bulk run: remembers every setup that really imported, with what the run covered.</summary>
    public static void RememberRun(BulkImportPlan plan, BulkImportResult result, Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> pre) =>
        Remember(ImportMemory.RecordsFor(ImportMemory.CoveredByRun(plan, result), DateTime.UtcNow, From(pre)));

    private static Func<DetectedInstance, ImportCategories, IReadOnlyDictionary<ImportCategories, string>> From(
        Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> pre) =>
        (setup, categories) => pre.TryGetValue(setup.SetupId, out var all)
            ? all.Where(kv => (categories & kv.Key) != 0).ToDictionary(kv => kv.Key, kv => kv.Value)
            : new Dictionary<ImportCategories, string>(); // never pre-read: record nothing rather than a guess
}
