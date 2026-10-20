using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Sources;

namespace OriginLauncher.App.UI.Controls;

// "Choose what to import" for someone who plays several launchers: ONE screen, a row per launcher, a tick per thing
// (Settings, Mods, Resource packs, Shaders, Worlds, Servers). No WPF here: the Import page draws it, the engine gets
// Grid() as BulkRequest.Grid (BulkComposer turns it into who brings what per version). Pure, so it is linked into
// OriginLauncher.Tests (ImportGridModelTests).
//
// Rules it keeps:
//  * Settings is one launcher per Minecraft version (a radio): the default is the engine's own pick (VersionPicker,
//    BulkComposer.DefaultSettingsLauncher), so an untouched grid plans exactly like Import everything;
//  * every other column is a plain tick; mods from several launchers on one version are allowed, with a warning;
//  * a cell with nothing behind it is greyed with the reason ("Lunar's mods are built in, not files"), never hidden;
//  * with more than one version there are tabs: "All versions" (a tick there sets every version) and one per version.

/// <summary>The grid's columns, left to right.</summary>
public enum GridColumn { Settings, Mods, ResourcePacks, ShaderPacks, Worlds, Servers }

/// <summary>One cell: on (true), off (false) or mixed across versions (null); greyed when nothing can come; its tooltip.</summary>
internal sealed record GridCellState(bool? On, bool Enabled, string Tip);

internal sealed class ImportGridModel
{
    /// <summary>The "All versions" tab.</summary>
    public const string AllTab = "all";

    public const string MixedModsWarning = "Mixing mod sets can break the game";

    public static readonly IReadOnlyList<GridColumn> Columns = new[]
    {
        GridColumn.Settings, GridColumn.Mods, GridColumn.ResourcePacks, GridColumn.ShaderPacks, GridColumn.Worlds, GridColumn.Servers,
    };

    private readonly List<DetectedInstance> _setups;
    private readonly ImportGrid _cells = new();                                   // the ticks (Settings excluded)
    private readonly Dictionary<string, SourceKind?> _settings = new(StringComparer.Ordinal); // the player's Settings picks

    /// <param name="setups">The setups that can come over (a supported, known Minecraft version).</param>
    public ImportGridModel(IEnumerable<DetectedInstance> setups)
    {
        _setups = setups.Where(s => !string.IsNullOrWhiteSpace(s.McVersion)).ToList();
        Versions = _setups.Select(s => s.McVersion!).Distinct(StringComparer.Ordinal).OrderBy(v => v, BulkRanking.NewestFirst).ToList();
        foreach (var version in Versions)
            foreach (var kind in LaunchersOn(version))
                _cells.Set(version, kind, BulkComposer.DefaultBrings(kind, version, _setups));
        Tab = ShowTabs ? AllTab : Versions.FirstOrDefault() ?? AllTab;
    }

    /// <summary>What each setup holds (by SetupId), from the page's count; empty until counted (then every cell is open).</summary>
    public IReadOnlyDictionary<string, SetupContents> Contents { get; set; } = new Dictionary<string, SetupContents>();

    /// <summary>Every Minecraft version the setups are on, newest first.</summary>
    public IReadOnlyList<string> Versions { get; }

    public bool ShowTabs => Versions.Count > 1;

    /// <summary>"All versions" first, then one per version; none with a single version.</summary>
    public IReadOnlyList<string> Tabs => ShowTabs ? new[] { AllTab }.Concat(Versions).ToList() : Array.Empty<string>();

    public string Tab { get; private set; }

    public static string TabTitle(string tab) => tab == AllTab ? "All versions" : tab;

    public void SetTab(string tab)
    {
        if (tab == AllTab ? ShowTabs : Versions.Contains(tab)) Tab = tab;
    }

    public static string ColumnTitle(GridColumn column) => column switch
    {
        GridColumn.Settings => "Settings",
        GridColumn.Mods => "Mods",
        GridColumn.ResourcePacks => "Resource packs",
        GridColumn.ShaderPacks => "Shaders",
        GridColumn.Worlds => "Worlds",
        _ => "Servers",
    };

    /// <summary>What a column brings (screenshots ride with worlds).</summary>
    public static ImportCategories Category(GridColumn column) => column switch
    {
        GridColumn.Settings => ImportCategories.Settings,
        GridColumn.Mods => ImportCategories.Mods,
        GridColumn.ResourcePacks => ImportCategories.ResourcePacks,
        GridColumn.ShaderPacks => ImportCategories.ShaderPacks,
        GridColumn.Worlds => ImportCategories.Worlds | ImportCategories.Screenshots,
        _ => ImportCategories.Servers,
    };

    private IEnumerable<string> TabVersions => Tab == AllTab ? Versions : new[] { Tab };

    /// <summary>The rows of the current tab: every launcher with a setup on its versions, in the Import page's tile order.</summary>
    public IReadOnlyList<SourceKind> Rows() =>
        TabVersions.SelectMany(LaunchersOn).Distinct().OrderBy(TileIndex).ToList();

    private IEnumerable<SourceKind> LaunchersOn(string version) =>
        _setups.Where(s => s.McVersion == version).Select(s => s.Kind).Distinct().OrderBy(TileIndex);

    private static int TileIndex(SourceKind kind)
    {
        var i = ImportFlowModel.TileOrder.ToList().IndexOf(kind);
        return i < 0 ? int.MaxValue : i;
    }

    private IReadOnlyList<DetectedInstance> SetupsOf(SourceKind kind, string version) =>
        _setups.Where(s => s.Kind == kind && s.McVersion == version).ToList();

    /// <summary>A row's line under the launcher's name: "3 setups - 1.21.1, 1.20.4".</summary>
    public string RowCaption(SourceKind kind)
    {
        var mine = TabVersions.SelectMany(v => SetupsOf(kind, v)).ToList();
        var versions = mine.Select(s => s.McVersion!).Distinct(StringComparer.Ordinal).OrderBy(v => v, BulkRanking.NewestFirst).ToList();
        return ImportLogic.Plural(mine.Count, "setup", "setups") + " - " + ImportFlowModel.JoinFew(versions);
    }

    // ---------------------------------------------------------------- settings: one launcher per version

    /// <summary>Whose settings <paramref name="version"/> gets: the player's pick, else the engine's default; null = none.</summary>
    public SourceKind? SettingsFrom(string version) =>
        _settings.TryGetValue(version, out var picked) ? picked : BulkComposer.DefaultSettingsLauncher(version, _setups);

    // The tab's versions where this launcher has a setup that carries settings (a Forge one on Fabric brings packs only).
    private List<string> SettingsVersions(SourceKind kind) =>
        TabVersions.Where(v => SetupsOf(kind, v).Any(BulkComposer.CarriesSettings)).ToList();

    // ---------------------------------------------------------------- cells

    /// <summary>The cell of <paramref name="kind"/>'s row and <paramref name="column"/> on the current tab.</summary>
    public GridCellState Cell(SourceKind kind, GridColumn column)
    {
        var title = ImportFlowModel.LauncherTitle(kind);
        var where = Tab == AllTab ? "every version" : "Minecraft " + Tab;
        if (column == GridColumn.Settings)
        {
            var mine = SettingsVersions(kind);
            if (mine.Count == 0) return new GridCellState(false, false, $"{title}'s setups here bring packs only (Forge), not settings");
            return new GridCellState(Aggregate(mine.Select(v => SettingsFrom(v) == kind)), true,
                $"Use {title}'s settings for {where} (one launcher's settings per version)");
        }

        var versions = TabVersions.Where(v => SetupsOf(kind, v).Count > 0).ToList();
        var open = versions.Where(v => Has(kind, v, column)).ToList();
        if (open.Count == 0)
            return new GridCellState(false, false, versions.All(v => PacksOnly(kind, v, column)) ? PacksOnlyReason : EmptyReason(kind, column));
        var category = Category(column);
        return new GridCellState(Aggregate(open.Select(v => (_cells.Brings(kind, v) & category) != 0)), true,
            $"Bring {ColumnTitle(column).ToLowerInvariant()} from {title} for {where}");
    }

    private static bool? Aggregate(IEnumerable<bool> each)
    {
        var list = each.ToList();
        if (list.Count == 0) return false;
        var on = list.Count(b => b);
        return on == list.Count ? true : on == 0 ? false : null;
    }

    /// <summary>A Forge setup on a Fabric version brings its packs only (BulkRules.IsPacksOnly).</summary>
    public const string PacksOnlyReason = "Forge setups bring their packs only";

    // Forge setups on a Fabric version bring packs (and screenshots) only: their mods, worlds and servers stay.
    private bool PacksOnly(SourceKind kind, string version, GridColumn column) =>
        column is GridColumn.Mods or GridColumn.Worlds or GridColumn.Servers && !SetupsOf(kind, version).Any(BulkComposer.CarriesSettings);

    // Is there anything behind the cell? Unknown before the count: open. Mods come from one setup (the launcher's most
    // played on that version), so that setup's count is the one that matters.
    private bool Has(SourceKind kind, string version, GridColumn column)
    {
        var mine = SetupsOf(kind, version);
        if (mine.Count == 0 || PacksOnly(kind, version, column)) return false;
        if (column == GridColumn.Mods)
        {
            var setup = BulkComposer.ModsSetup(kind, version, mine)!;
            return !Contents.TryGetValue(setup.SetupId, out var c) || c.Mods > 0;
        }
        var counted = mine.Where(s => Contents.ContainsKey(s.SetupId)).ToList();
        if (counted.Count == 0) return true;
        return counted.Any(s => column switch
        {
            GridColumn.ResourcePacks => Contents[s.SetupId].ResourcePacks > 0,
            GridColumn.ShaderPacks => Contents[s.SetupId].ShaderPacks > 0,
            GridColumn.Worlds => Contents[s.SetupId].Worlds > 0 || Contents[s.SetupId].Screenshots > 0,
            _ => Contents[s.SetupId].Servers,
        });
    }

    /// <summary>Why a cell is greyed, in the player's words.</summary>
    public static string EmptyReason(SourceKind kind, GridColumn column)
    {
        var title = ImportFlowModel.LauncherTitle(kind);
        if (column == GridColumn.Mods && PvpClientSource.BuiltInModsNote(kind) != null)
            return $"{ShortName(kind)}'s mods are built in, not files";
        return column switch
        {
            GridColumn.Mods => $"No mods to bring from {title}",
            GridColumn.ResourcePacks => $"No resource packs in {title}",
            GridColumn.ShaderPacks => $"No shaders in {title}",
            GridColumn.Worlds => $"No worlds in {title}",
            _ => $"No saved servers in {title}",
        };
    }

    /// <summary>"Lunar", "Feather", "Badlion", "LabyMod": the client's name without "Client".</summary>
    public static string ShortName(SourceKind kind) => kind == SourceKind.Folder
        ? "your folders"
        : ImportFlowModel.LauncherTitle(kind).Replace(" Client", "", StringComparison.Ordinal);

    /// <summary>
    /// A press on a cell. A tick turns on (or off) for every version of the tab where it can. Settings is a radio per
    /// version: on picks this launcher; off leaves the version with no settings brought at all.
    /// </summary>
    public void Toggle(SourceKind kind, GridColumn column)
    {
        var cell = Cell(kind, column);
        if (!cell.Enabled) return;
        var on = cell.On != true;
        if (column == GridColumn.Settings)
        {
            foreach (var version in SettingsVersions(kind))
            {
                if (on) _settings[version] = kind;
                else if (SettingsFrom(version) == kind) _settings[version] = null;
            }
            return;
        }
        var category = Category(column);
        foreach (var version in TabVersions.Where(v => SetupsOf(kind, v).Count > 0 && Has(kind, v, column)))
        {
            var brings = _cells.Brings(kind, version);
            _cells.Set(version, kind, on ? brings | category : brings & ~category);
        }
    }

    /// <summary>The tab's versions whose mods come from more than one launcher.</summary>
    public IReadOnlyList<string> MixedModsVersions() =>
        TabVersions.Where(v => LaunchersOn(v).Count(k => Has(k, v, GridColumn.Mods) && (_cells.Brings(k, v) & ImportCategories.Mods) != 0) > 1).ToList();

    /// <summary>"Mixing mod sets can break the game (Minecraft 1.21.1)", or null when no version mixes them.</summary>
    public string? Warning()
    {
        var mixed = MixedModsVersions();
        if (mixed.Count == 0) return null;
        return Tab == AllTab ? $"{MixedModsWarning} (Minecraft {ImportFlowModel.JoinFew(mixed)})" : MixedModsWarning;
    }

    /// <summary>True when the grid brings nothing at all (Import is off then).</summary>
    public bool BringsNothing =>
        Versions.All(v => SettingsFrom(v) == null && LaunchersOn(v).All(k => (_cells.Brings(k, v) & ImportGrid.Things) == 0 || !AnyOpen(k, v)));

    private bool AnyOpen(SourceKind kind, string version) =>
        Columns.Where(c => c != GridColumn.Settings).Any(c => Has(kind, version, c) && (_cells.Brings(kind, version) & Category(c)) != 0);

    /// <summary>The engine's grid: a fresh copy (a plan's request keeps the one it was made with).</summary>
    public ImportGrid Grid()
    {
        var grid = new ImportGrid();
        foreach (var version in Versions)
        {
            foreach (var kind in LaunchersOn(version)) grid.Set(version, kind, _cells.Brings(kind, version));
            if (_settings.TryGetValue(version, out var picked)) grid.SetSettings(version, picked);
        }
        return grid;
    }

    /// <summary>Everything the plan depends on, as one string (two equal keys plan the same).</summary>
    public string Key()
    {
        var parts = new List<string>();
        foreach (var version in Versions)
        {
            foreach (var kind in LaunchersOn(version)) parts.Add($"{version}|{kind}={(int)_cells.Brings(kind, version)}");
            if (_settings.TryGetValue(version, out var picked)) parts.Add($"{version}|settings={picked?.ToString() ?? "none"}");
        }
        return string.Join(";", parts);
    }
}
