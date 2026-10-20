using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.Core.Import;

// What was already imported, so the same thing is never offered twice (Will, 2026-10-20: "after you import you can't
// import the exact same things; if I imported all of Lunar Client it shouldn't show up anymore unless something
// changes later").
//
// A record is kept per SOURCE setup (one game folder = one SetupId) AND per Origin account (instances are per account:
// settings, worlds and servers live under instances/<version>/accounts/<id>), and names the Origin versions it landed
// in. Importing into account A never hides the setup for account B, and a record whose Origin instance is gone (reset,
// deleted) is ignored, so the source is offered again. It holds a content signature per category
// (mods, settings, servers, packs, worlds, screenshots) of what the import covered: file names, sizes and modification
// times, hashed, never a path or a file's contents. A setup is "already imported" when a record exists for it at the
// same Minecraft version and every category that has content now matches the recorded signature. Anything new,
// removed from or changed in the source changes a signature, and the setup is offered again. A category the player
// switched off (a grid cell unticked, "Mods" left out) is never recorded, so it keeps the setup on offer; worlds and
// screenshots are the exception (off by default, huge): not copying them does not keep a setup on offer.
//
// Pure rules + the disk reads of the signatures (the same folders SetupInventory lists). Persisting goes through
// the launcher's one settings writer in Launcher/ImportMemoryStore (this file never touches launcher state). Stored as one JSON string so a damaged value can
// never stop the launcher's settings from loading: unreadable = nothing was imported.

/// <summary>One source setup that was imported.</summary>
public sealed class ImportedSetupRecord
{
    /// <summary><see cref="DetectedInstance.SetupId"/>: from the game folder only.</summary>
    public string SetupId { get; set; } = "";

    /// <summary>For people reading settings.json: the launcher the setup came from.</summary>
    public string Launcher { get; set; } = "";

    /// <summary>The source's own Minecraft version when it was imported.</summary>
    public string? Version { get; set; }

    /// <summary>The Origin account (game-folder key) the setup was imported into. A record without one is never matched.</summary>
    public string Account { get; set; } = "";

    /// <summary>The Origin versions the import landed in (instances/&lt;version&gt;/accounts/&lt;Account&gt;). Gone folder = not imported.</summary>
    public List<string> Targets { get; set; } = new();

    /// <summary>Category name -> signature of what was imported from it.</summary>
    public Dictionary<string, string> Signatures { get; set; } = new();

    public DateTime ImportedUtc { get; set; }
}

public static class SetupSignature
{
    private const ImportCategories Singles = ImportCategories.Mods | ImportCategories.Settings | ImportCategories.Servers
        | ImportCategories.ResourcePacks | ImportCategories.ShaderPacks | ImportCategories.Worlds | ImportCategories.Screenshots;

    /// <summary>
    /// The signature of each of <paramref name="categories"/> that has content in <paramref name="setup"/> right now.
    /// A category that is empty or cannot be listed is left out. Folder listings only: no file is opened.
    /// </summary>
    public static Dictionary<ImportCategories, string> Compute(DetectedInstance setup, ImportCategories categories = ImportCategories.All)
    {
        var result = new Dictionary<ImportCategories, string>();
        foreach (var category in CategoryFilter.Each)
        {
            if ((categories & category) == 0 || (category & Singles) == 0) continue;
            var lines = Lines(setup, category);
            if (lines.Count == 0) continue;
            lines.Sort(StringComparer.Ordinal);
            var hash = SHA256.HashData(Encoding.UTF8.GetBytes(string.Join('\n', lines)));
            result[category] = Convert.ToHexString(hash, 0, 8); // 16 hex chars: plenty to notice a change
        }
        return result;
    }

    private static List<string> Lines(DetectedInstance setup, ImportCategories category)
    {
        var lines = new List<string>();
        try
        {
            switch (category)
            {
                case ImportCategories.Mods:
                    Files(setup.ModsFolder, "*.jar*", lines);
                    break;
                case ImportCategories.Settings:
                    foreach (var name in new[] { "options.txt", "optionsof.txt", Path.Combine("config", "iris.properties"), Path.Combine("config", "sodium-options.json") })
                        File1(Path.Combine(setup.GameDir, name), name, lines);
                    break;
                case ImportCategories.Servers:
                    File1(Path.Combine(setup.GameDir, "servers.dat"), "servers.dat", lines);
                    break;
                case ImportCategories.ResourcePacks:
                    Packs(Path.Combine(setup.GameDir, "resourcepacks"), lines);
                    break;
                case ImportCategories.ShaderPacks:
                    Packs(setup.ShaderPacksFolder, lines);
                    break;
                case ImportCategories.Worlds:
                    // A world is its level.dat (the game rewrites it whenever the world is played).
                    if (Directory.Exists(setup.SavesFolder))
                        foreach (var world in Directory.EnumerateDirectories(setup.SavesFolder))
                            File1(Path.Combine(world, "level.dat"), Path.GetFileName(world) + "/level.dat", lines);
                    break;
                case ImportCategories.Screenshots:
                    Files(setup.ScreenshotsFolder, "*.png", lines, withTime: false);
                    break;
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            lines.Clear(); // unreadable now = no content now
        }
        return lines;
    }

    private static void File1(string path, string label, List<string> lines)
    {
        var info = new FileInfo(path);
        if (info.Exists) lines.Add($"{label}|{info.Length}|{info.LastWriteTimeUtc.Ticks}");
    }

    private static void Files(string dir, string pattern, List<string> lines, bool withTime = true)
    {
        if (!Directory.Exists(dir)) return;
        foreach (var path in Directory.EnumerateFiles(dir, pattern))
        {
            var info = new FileInfo(path);
            lines.Add(withTime ? $"{info.Name}|{info.Length}|{info.LastWriteTimeUtc.Ticks}" : $"{info.Name}|{info.Length}");
        }
    }

    private static void Packs(string dir, List<string> lines)
    {
        if (!Directory.Exists(dir)) return;
        Files(dir, "*.zip", lines);
        foreach (var sub in Directory.EnumerateDirectories(dir))
            lines.Add($"{Path.GetFileName(sub)}/|{Directory.GetLastWriteTimeUtc(sub).Ticks}");
    }
}

public static class ImportMemory
{
    /// <summary>The most records kept (the oldest go first): the settings file stays small.</summary>
    public const int MaxRecords = 200;

    // ---- stored form ----

    /// <summary>Reads the stored records. Missing, empty, damaged or half-wrong data is "nothing imported", never an exception.</summary>
    public static List<ImportedSetupRecord> Parse(string? json)
    {
        var list = new List<ImportedSetupRecord>();
        if (string.IsNullOrWhiteSpace(json)) return list;
        try
        {
            var records = JsonSerializer.Deserialize<List<ImportedSetupRecord>>(json);
            if (records == null) return list;
            foreach (var r in records)
                if (r != null && !string.IsNullOrWhiteSpace(r.SetupId) && r.Signatures != null
                    && !string.IsNullOrWhiteSpace(r.Account) && r.Targets is { Count: > 0 }) list.Add(r);
        }
        catch (Exception e) when (e is JsonException or NotSupportedException or InvalidOperationException)
        {
            return new List<ImportedSetupRecord>();
        }
        return list;
    }

    public static string Serialize(IEnumerable<ImportedSetupRecord> records) => JsonSerializer.Serialize(records.ToList());

    // ---- is it already in Origin? ----

    /// <summary>The record for one setup in one account (null: it was never imported there).</summary>
    private static ImportedSetupRecord? RecordFor(DetectedInstance setup, string account, IEnumerable<ImportedSetupRecord> records) =>
        records.FirstOrDefault(r => string.Equals(r.SetupId, setup.SetupId, StringComparison.OrdinalIgnoreCase)
            && string.Equals(r.Account, account, StringComparison.OrdinalIgnoreCase));

    /// <summary>
    /// True when <paramref name="setup"/> was imported into <paramref name="account"/>, every Origin instance it landed in
    /// still exists (<paramref name="targetExists"/>(account, version)), and nothing in it changed since: same Minecraft
    /// version, and every category with content now has the signature it had at import. <paramref name="sign"/> reads the
    /// setup's signatures (the default reads the disk; tests hand in their own).
    /// </summary>
    public static bool IsAlreadyImported(DetectedInstance setup, string account, IReadOnlyList<ImportedSetupRecord> records,
        Func<string, string, bool> targetExists, Func<DetectedInstance, IReadOnlyDictionary<ImportCategories, string>>? sign = null)
    {
        if (string.IsNullOrWhiteSpace(account)) return false;
        var record = RecordFor(setup, account, records);
        if (record == null) return false;
        if (record.Targets.Count == 0 || record.Targets.Any(t => !targetExists(account, t))) return false;
        if (!string.Equals(record.Version ?? "", setup.McVersion ?? "", StringComparison.Ordinal)) return false;
        var now = (sign ?? (s => SetupSignature.Compute(s)))(setup);
        foreach (var (category, signature) in now)
        {
            if (record.Signatures.TryGetValue(category.ToString(), out var had)) { if (had != signature) return false; }
            // Worlds and screenshots are optional extras (worlds are off by default: they can be huge). Not copying them
            // must not keep a fully imported launcher on offer forever; once they WERE copied, a change brings it back.
            else if ((category & Extras) == 0) return false;
        }
        return true;
    }

    private const ImportCategories Extras = ImportCategories.Worlds | ImportCategories.Screenshots;

    /// <summary>
    /// Splits a scan into what is still on offer and what is already in Origin for <paramref name="account"/>. Setups with
    /// no record are never read from disk. An empty or unreadable memory offers everything.
    /// </summary>
    public static (List<DetectedInstance> Offered, List<DetectedInstance> AlreadyImported) Partition(
        IEnumerable<DetectedInstance> setups, string account, IReadOnlyList<ImportedSetupRecord> records,
        Func<string, string, bool> targetExists, Func<DetectedInstance, IReadOnlyDictionary<ImportCategories, string>>? sign = null)
    {
        var offered = new List<DetectedInstance>();
        var imported = new List<DetectedInstance>();
        foreach (var setup in setups)
            (records.Count > 0 && IsAlreadyImported(setup, account, records, targetExists, sign) ? imported : offered).Add(setup);
        return (offered, imported);
    }

    // ---- what a finished import covered ----

    /// <summary>
    /// What a copy of the plan covers from its source setup: what it brings, plus what the plan decided another setup
    /// brings instead (the version's settings come from the settings setup; one setup per launcher brings mods; a Forge
    /// setup on a Fabric version brings packs only by design) - but only a setup that really imported in this run
    /// (<paramref name="outcome"/>: Imported / ImportedPartly, never Skipped or Failed) can stand in for it. What the
    /// PLAYER switched off (worlds, screenshots, the "left out" kinds, an unticked grid cell) is not covered, so that
    /// setup stays on offer.
    /// </summary>
    public static ImportCategories Covered(VersionGroup group, PlannedSetup planned, BulkRequest? request, VersionOutcome outcome)
    {
        var covered = planned.Brings;
        var others = new List<PlannedSetup>();
        for (var i = 0; i < group.Setups.Count && i < outcome.Setups.Count; i++)
            if (!ReferenceEquals(group.Setups[i], planned) && outcome.Setups[i].Kind is SetupResultKind.Imported or SetupResultKind.ImportedPartly)
                others.Add(group.Setups[i]);
        if (planned.Role == SetupRole.PacksOnly)
            covered |= ImportCategories.Mods | ImportCategories.Settings | ImportCategories.Servers | ImportCategories.Worlds;
        if (others.Any(s => (s.Brings & ImportCategories.Settings) != 0)) covered |= ImportCategories.Settings;
        // Mods: the default rule gives one setup's mods to the version; with the player's own grid only the same launcher's.
        if (others.Any(s => (s.Brings & ImportCategories.Mods) != 0 && (request?.Grid == null || s.Setup.Kind == planned.Setup.Kind)))
            covered |= ImportCategories.Mods;
        return Switches(covered, planned.Plan.Options);
    }

    /// <summary>The covered categories of a single import: everything its options allow.</summary>
    public static ImportCategories Covered(ImportPlan plan) => Switches(ImportCategories.All, plan.Options);

    /// <summary>One setup a run covered: what of it, and which Origin versions it landed in.</summary>
    public sealed record CoveredSetup(DetectedInstance Setup, ImportCategories Covered, IReadOnlyList<string> Targets);

    private static ImportCategories Switches(ImportCategories covered, ImportOptions options)
    {
        covered &= ~options.LeaveOut;
        if (!options.CopyWorlds) covered &= ~ImportCategories.Worlds;
        if (!options.CopyScreenshots) covered &= ~ImportCategories.Screenshots;
        return covered & ImportCategories.All;
    }

    /// <summary>The setups a bulk run covered, each with the union of its copies' categories and targets (a setup appears once).</summary>
    public static List<CoveredSetup> CoveredByRun(BulkImportPlan plan, BulkImportResult result)
    {
        var list = new List<CoveredSetup>();
        if (!result.Succeeded) return list;
        foreach (var versionOutcome in result.Versions)
        {
            var group = plan.Groups.FirstOrDefault(g => g.Version == versionOutcome.Version);
            if (group == null) continue;
            for (var slot = 0; slot < group.Setups.Count && slot < versionOutcome.Setups.Count; slot++)
            {
                if (versionOutcome.Setups[slot].Kind is not (SetupResultKind.Imported or SetupResultKind.ImportedPartly)) continue;
                var planned = group.Setups[slot];
                var covered = Covered(group, planned, plan.Request, versionOutcome);
                var at = list.FindIndex(x => x.Setup.SetupId == planned.Setup.SetupId);
                if (at < 0) list.Add(new CoveredSetup(planned.Setup, covered, new[] { group.Version }));
                else list[at] = list[at] with
                {
                    Covered = list[at].Covered | covered,
                    Targets = list[at].Targets.Contains(group.Version) ? list[at].Targets : list[at].Targets.Append(group.Version).ToList(),
                };
            }
        }
        return list;
    }

    /// <summary>The setup a committed single import covered (null when it did not commit).</summary>
    public static CoveredSetup? CoveredBySingle(ImportPlan plan, ImportResult result) =>
        result.State == ImportState.Committed ? new CoveredSetup(plan.Source, Covered(plan), new[] { plan.TargetVersion }) : null;

    /// <summary>The records to store for a run: the covered categories' signatures (read from the source now, or before the copy), for one account.</summary>
    public static List<ImportedSetupRecord> RecordsFor(IEnumerable<CoveredSetup> covered, string account,
        DateTime nowUtc, Func<DetectedInstance, ImportCategories, IReadOnlyDictionary<ImportCategories, string>>? sign = null)
    {
        var records = new List<ImportedSetupRecord>();
        if (string.IsNullOrWhiteSpace(account)) return records;
        foreach (var (setup, categories, targets) in covered)
        {
            if (targets.Count == 0) continue;
            var signatures = (sign ?? ((s, c) => SetupSignature.Compute(s, c)))(setup, categories);
            records.Add(new ImportedSetupRecord
            {
                SetupId = setup.SetupId, Launcher = setup.SourceLabel, Version = setup.McVersion, ImportedUtc = nowUtc,
                Account = account, Targets = targets.ToList(),
                Signatures = signatures.ToDictionary(kv => kv.Key.ToString(), kv => kv.Value),
            });
        }
        return records;
    }

    /// <summary>
    /// Adds <paramref name="fresh"/> to <paramref name="existing"/>: a setup imported again into the same account keeps the
    /// categories and targets it already had and takes the new signatures (a different Minecraft version starts its record
    /// over); the oldest records go past <see cref="MaxRecords"/>. With <paramref name="targetExists"/>, targets whose Origin
    /// instance is gone are dropped from the OLD records, and a record left with none is dropped. Returns a new list; the
    /// inputs are not changed.
    /// </summary>
    public static List<ImportedSetupRecord> Merge(IEnumerable<ImportedSetupRecord> existing, IEnumerable<ImportedSetupRecord> fresh,
        Func<string, string, bool>? targetExists = null)
    {
        static string Key(ImportedSetupRecord r) => r.SetupId.ToLowerInvariant() + "|" + r.Account.ToLowerInvariant();
        var map = new Dictionary<string, ImportedSetupRecord>();
        foreach (var r in existing)
        {
            var targets = targetExists == null ? r.Targets : r.Targets.Where(t => targetExists(r.Account, t)).ToList();
            if (targets.Count == 0) continue;
            map[Key(r)] = targets.Count == r.Targets.Count ? r : With(r, targets, r.Signatures);
        }
        foreach (var r in fresh)
        {
            if (map.TryGetValue(Key(r), out var old) && string.Equals(old.Version ?? "", r.Version ?? "", StringComparison.Ordinal))
            {
                var signatures = new Dictionary<string, string>(old.Signatures);
                foreach (var (k, v) in r.Signatures) signatures[k] = v;
                var targets = old.Targets.Union(r.Targets, StringComparer.Ordinal).ToList();
                map[Key(r)] = new ImportedSetupRecord
                {
                    SetupId = r.SetupId, Launcher = r.Launcher, Version = r.Version, ImportedUtc = r.ImportedUtc,
                    Account = r.Account, Targets = targets, Signatures = signatures,
                };
            }
            else
            {
                map[Key(r)] = r;
            }
        }
        return map.Values.OrderByDescending(r => r.ImportedUtc).Take(MaxRecords).ToList();
    }

    private static ImportedSetupRecord With(ImportedSetupRecord r, List<string> targets, Dictionary<string, string> signatures) => new()
    {
        SetupId = r.SetupId, Launcher = r.Launcher, Version = r.Version, ImportedUtc = r.ImportedUtc,
        Account = r.Account, Targets = targets, Signatures = signatures,
    };
}
