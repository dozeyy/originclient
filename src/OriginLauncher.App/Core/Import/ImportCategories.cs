using System.IO;
using System.Collections.ObjectModel;

namespace OriginLauncher.App.Core.Import;

// What the player chose to bring, per kind of thing ("worlds", "servers"), and the one place a plan is trimmed to
// that choice. The import drawer sets it per Minecraft version (ImportOptions.BringByVersion); every setup of one
// version gets the same choice, so the bulk ledger (who claims which file) stays consistent inside a version.
//
// Worlds and screenshots already had engine switches (CopyWorlds / CopyScreenshots): ForVersion maps the choice
// onto them, so those are never even read when left out. The rest is planned as before and then filtered here,
// after SettingsMigrator and before the contributors and Finish, so the byte count, the summary and apply all see
// the trimmed plan. Nothing here runs when nothing is left out: an import without a choice is unchanged.

/// <summary>The kinds of thing an import can bring. Flags: one value says what a version brings (or leaves out).</summary>
[Flags]
public enum ImportCategories
{
    None = 0,
    Mods = 1,
    Settings = 2,       // options.txt, video and shader settings (Sodium, Iris, OptiFine); mod configs travel with Mods
    Servers = 4,        // servers.dat
    ResourcePacks = 8,
    ShaderPacks = 16,
    Worlds = 32,
    Screenshots = 64,
    All = Mods | Settings | Servers | ResourcePacks | ShaderPacks | Worlds | Screenshots,
}

/// <summary>What each Minecraft version brings ("1.21.1" -> mods, settings, servers). Value-equal, so a re-plan with the same choice reuses the plan.</summary>
public sealed class CategoryMap : IEquatable<CategoryMap>
{
    private readonly SortedDictionary<string, ImportCategories> _map;

    public CategoryMap(IEnumerable<KeyValuePair<string, ImportCategories>> entries)
    {
        _map = new SortedDictionary<string, ImportCategories>(StringComparer.Ordinal);
        foreach (var (version, bring) in entries) _map[version] = bring & ImportCategories.All;
    }

    public IReadOnlyDictionary<string, ImportCategories> Entries => new ReadOnlyDictionary<string, ImportCategories>(_map);

    /// <summary>What <paramref name="version"/> brings; a version the map doesn't name brings everything the options allow.</summary>
    public ImportCategories For(string version) => _map.TryGetValue(version, out var bring) ? bring : ImportCategories.All;

    public bool Equals(CategoryMap? other) => other != null && _map.Count == other._map.Count
        && _map.All(kv => other._map.TryGetValue(kv.Key, out var v) && v == kv.Value);

    public override bool Equals(object? obj) => Equals(obj as CategoryMap);

    public override int GetHashCode()
    {
        var hash = new HashCode();
        foreach (var (k, v) in _map) { hash.Add(k, StringComparer.Ordinal); hash.Add(v); }
        return hash.ToHashCode();
    }
}

public static class CategoryFilter
{
    /// <summary>The player's word for a kind of thing ("server list").</summary>
    public static string Name(ImportCategories one) => one switch
    {
        ImportCategories.Mods => "Mods",
        ImportCategories.Settings => "Settings",
        ImportCategories.Servers => "Server list",
        ImportCategories.ResourcePacks => "Resource packs",
        ImportCategories.ShaderPacks => "Shader packs",
        ImportCategories.Worlds => "Worlds",
        ImportCategories.Screenshots => "Screenshots",
        _ => one.ToString(),
    };

    /// <summary>Each single category in display order.</summary>
    public static readonly IReadOnlyList<ImportCategories> Each = new[]
    {
        ImportCategories.Mods, ImportCategories.Settings, ImportCategories.Servers, ImportCategories.ResourcePacks,
        ImportCategories.ShaderPacks, ImportCategories.Worlds, ImportCategories.Screenshots,
    };

    /// <summary>The category a planned item belongs to (None: game files and caches, which always follow the setup).</summary>
    public static ImportCategories Of(PlanItemKind kind, string name) => kind switch
    {
        PlanItemKind.Mod => ImportCategories.Mods,
        PlanItemKind.Setting when IsServerList(name) => ImportCategories.Servers,
        PlanItemKind.Config when IsVideoSettings(name) => ImportCategories.Settings,
        PlanItemKind.Config => ImportCategories.Mods, // a mod's config file belongs with the mod
        PlanItemKind.Setting => ImportCategories.Settings,
        PlanItemKind.ResourcePack => ImportCategories.ResourcePacks,
        PlanItemKind.ShaderPack => ImportCategories.ShaderPacks,
        PlanItemKind.World => ImportCategories.Worlds,
        PlanItemKind.Screenshot => ImportCategories.Screenshots,
        _ => ImportCategories.None,
    };

    // Sodium's and Iris's files are the game's video / shader settings, not a mod's own config (SettingsMigrator's labels:
    // "config/sodium-options.json", "Sodium video settings", "Shader settings (iris.properties)").
    private static bool IsVideoSettings(string name) =>
        name.Contains("sodium-options.json", StringComparison.OrdinalIgnoreCase) || name.Contains("iris.properties", StringComparison.OrdinalIgnoreCase)
        || name == "Sodium video settings";

    // The server list's op is labelled "servers.dat"; its summary lines are "servers.dat (server list)".
    private static bool IsServerList(string name) => name.StartsWith("servers.dat", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Takes everything in <paramref name="leaveOut"/> out of <paramref name="plan"/>: its ops (stand-ins too), the lines
    /// that say it comes over, and the plan's created/replaced counts; adds one "you left it out" line per category that
    /// had something to bring. Options.txt keeps its settings but no longer switches on packs that were left out.
    /// </summary>
    public static void Apply(ImportPlan plan, ImportCategories leaveOut)
    {
        leaveOut &= ImportCategories.All;
        if (leaveOut == ImportCategories.None) return;
        var removed = new Dictionary<ImportCategories, int>();
        for (var i = plan.Ops.Count - 1; i >= 0; i--)
        {
            var op = plan.Ops[i];
            var category = Of(op.Item, op.Label);
            if (category == ImportCategories.None || (leaveOut & category) == 0)
            {
                if (op.Kind == OpKind.WriteOptions && (leaveOut & ImportCategories.ResourcePacks) != 0) plan.Ops[i] = WithoutPacks(op);
                continue;
            }
            plan.Ops.RemoveAt(i);
            if (op.Fallback) continue;
            removed[category] = removed.GetValueOrDefault(category) + 1;
            if (File.Exists(op.Dest) || Directory.Exists(op.Dest)) { if (plan.Replaced > 0) plan.Replaced--; }
            else if (plan.Created > 0) plan.Created--;
        }
        // A left-out category's own lines go (what it would bring, what needs attention, why one file was kept).
        var hadLines = new HashSet<ImportCategories>();
        plan.Lines.RemoveAll(line =>
        {
            var category = Of(line.Kind, line.Name);
            if (category == ImportCategories.None || (leaveOut & category) == 0) return false;
            hadLines.Add(category);
            return true;
        });
        foreach (var category in Each)
        {
            if ((leaveOut & category) == 0 || (!removed.ContainsKey(category) && !hadLines.Contains(category))) continue;
            plan.Lines.Add(new SummaryLine(SummarySection.NotImported, Kind(category), Name(category), LeftOutReason(category)));
        }
        if ((leaveOut & ImportCategories.Mods) != 0) plan.Mods.Clear();
    }

    public static string LeftOutReason(ImportCategories category) => category == ImportCategories.Settings
        ? "You left settings out. Origin keeps its own; packs that come over are not switched on."
        : "You left these out.";

    private static PlanItemKind Kind(ImportCategories category) => category switch
    {
        ImportCategories.Mods => PlanItemKind.Mod,
        ImportCategories.Servers or ImportCategories.Settings => PlanItemKind.Setting,
        ImportCategories.ResourcePacks => PlanItemKind.ResourcePack,
        ImportCategories.ShaderPacks => PlanItemKind.ShaderPack,
        ImportCategories.Worlds => PlanItemKind.World,
        _ => PlanItemKind.Screenshot,
    };

    // options.txt still comes over, but no longer names resource packs the player left behind.
    private static PlannedOp WithoutPacks(PlannedOp op)
    {
        Dictionary<string, string?>? desired = null;
        if (op.Desired != null)
        {
            desired = new Dictionary<string, string?>(op.Desired, StringComparer.Ordinal);
            desired.Remove("resourcePacks");
            desired.Remove("incompatibleResourcePacks");
        }
        return op with { Extra = null, Desired = desired ?? op.Desired };
    }
}
