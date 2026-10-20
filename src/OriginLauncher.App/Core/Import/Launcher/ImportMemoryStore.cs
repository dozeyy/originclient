using System.IO;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.Core.Import.Launcher;

// The launcher side of ImportMemoryKeeper (which holds the rules and is tested): where the records live and where
// Origin's instances are. Reads LauncherSettings.ImportedSetupsJson; writes go through SettingsStore.Update only (the
// merge runs inside the update, on the freshest settings). The settings field is one JSON string, so a damaged value
// can never stop the launcher's settings from loading.
internal static class ImportMemoryStore
{
    private static readonly ImportMemoryKeeper Keeper = new(
        read: () => SettingsStore.Load().ImportedSetupsJson,
        update: mutate => SettingsStore.Update(s => s.ImportedSetupsJson = mutate(s.ImportedSetupsJson)),
        // The Origin instance an import landed in: instances/<version>/accounts/<account>. Reset or deleted = not imported.
        targetExists: (account, version) => Directory.Exists(InstanceLayout.GameDir(OriginPaths.Instances, version, account)));

    /// <summary>Splits a scan into offered / already imported into <paramref name="account"/> (null: nobody signed in, everything is offered).</summary>
    public static (List<DetectedInstance> Offered, List<DetectedInstance> AlreadyImported) Partition(IEnumerable<DetectedInstance> setups, string? account) =>
        Keeper.Partition(setups, account);

    public static Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> PreSign(IEnumerable<DetectedInstance> setups) =>
        Keeper.PreSign(setups);

    public static void RememberImport(ImportPlan plan, ImportResult result, Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> pre) =>
        Keeper.RememberImport(plan, result, pre);

    public static void RememberRun(BulkImportPlan plan, BulkImportResult result, Dictionary<string, IReadOnlyDictionary<ImportCategories, string>> pre) =>
        Keeper.RememberRun(plan, result, pre);
}
