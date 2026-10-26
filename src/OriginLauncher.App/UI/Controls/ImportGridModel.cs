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
//  * Settings is ONE launcher for the whole grid (a radio: ticking one unticks the other), so no two launchers'
//    settings ever meet (Will, 2026-10-19). The default is the launcher the engine picks for the most versions
//    (BulkComposer.DefaultSettingsLauncher). A version that launcher has no setup on gets its MOST PLAYED setup's
//    settings, migrated to that version (Will, 2026-10-19; BulkComposer.SettingsDonor + SettingsMigrator);
//  * Mods start ticked for every launcher that has some: each setup's mods land in a profile of their own (Will,
//    2026-10-25, decision 04), so two launchers' mods never meet in one folder and there is no mixed-mods warning;
//  * every other column is a plain tick;
//    packs, shaders and servers come from every ticked launcher with duplicates removed, worlds all come;
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

    public static readonly IReadOnlyList<GridColumn> Columns = new[]
    {
        GridColumn.Settings, GridColumn.Mods, GridColumn.ResourcePacks, GridColumn.ShaderPacks, GridColumn.Worlds, GridColumn.Servers,
    };

    private readonly List<DetectedInstance> _setups;
    private readonly ImportGrid _cells = new();                                   // the ticks (Settings excluded)
    private SourceKind? _settingsPick;                                             // the player's Settings pick...
    private bool _settingsPicked;                                                  // ...once they made one
    private bool _modsTouched;                                                     // the player pressed a Mods tick
    private HashSet<DetectedInstance> _unavailable = new(ReferenceEqualityComparer.Instance); // the last plan can't take them

    /// <param name="setups">The setups that can come over (a supported, known Minecraft version).</param>
    public ImportGridModel(IEnumerable<DetectedInstance> setups)
    {
        _setups = setups.Where(s => !string.IsNullOrWhiteSpace(s.McVersion)).ToList();
        Versions = _setups.Select(s => s.McVersion!).Distinct(StringComparer.Ordinal).OrderBy(v => v, BulkRanking.NewestFirst).ToList();
        foreach (var version in Versions)
            foreach (var kind in LaunchersOn(version))
                _cells.Set(version, kind, BulkComposer.DefaultBrings(kind, version, _setups));
        DefaultMods();
        Tab = ShowTabs ? AllTab : Versions.FirstOrDefault() ?? AllTab;
    }

    /// <summary>
    /// What each setup holds (by SetupId), from the page's count; empty until counted (then every cell is open). The
    /// count can change which launcher has the most mods, so the Mods default is re-picked until the player presses one.
    /// </summary>
    public IReadOnlyDictionary<string, SetupContents> Contents
    {
        get => _contents;
        set
        {
            _contents = value;
            if (!_modsTouched) DefaultMods();
        }
    }
    private IReadOnlyDictionary<string, SetupContents> _contents = new Dictionary<string, SetupContents>();

    // Mods start ticked wherever there are mods to bring (each setup becomes its own profile, so nothing mixes); a cell
    // the count shows empty starts unticked. Re-run after a count until the player presses a Mods tick.
    private void DefaultMods()
    {
        foreach (var version in Versions)
            foreach (var kind in LaunchersOn(version))
            {
                var brings = _cells.Brings(kind, version);
                var on = Has(kind, version, GridColumn.Mods);
                _cells.Set(version, kind, on ? brings | ImportCategories.Mods : brings & ~ImportCategories.Mods);
            }
    }

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

    // ---------------------------------------------------------------- settings: one launcher for the whole grid

    /// <summary>The one launcher whose settings come over (the player's pick, else the default); null = none at all.</summary>
    public SourceKind? SettingsLauncher => _settingsPicked ? _settingsPick : DefaultSettingsLauncher();

    // The launcher the engine would pick for the most versions; a tie goes to the one it picks for the newest.
    private SourceKind? DefaultSettingsLauncher()
    {
        var picks = Versions.Select(v => BulkComposer.DefaultSettingsLauncher(v, _setups)).ToList();
        return picks.Where(k => k != null).Select(k => k!.Value).Distinct()
            .OrderByDescending(k => picks.Count(p => p == k)).ThenBy(k => picks.IndexOf(k))
            .Select(k => (SourceKind?)k).FirstOrDefault();
    }

    /// <summary>
    /// Whose settings <paramref name="version"/> gets: the one settings launcher, on EVERY version (null = none at all).
    /// Its own setup there when it has one; otherwise its most played setup, carried over (BulkComposer.SettingsDonor).
    /// </summary>
    public SourceKind? SettingsFrom(string version) =>
        SettingsLauncher is { } kind && BulkComposer.SettingsSetup(kind, Available) != null ? kind : null;

    /// <summary>
    /// The setups the last plan found can't come over at all (Minecraft running from them, the folder gone, inside
    /// Origin's own folders): the settings donor is picked among the rest, as the engine does (BulkEngine qualifies
    /// before it composes). True when that changed, so the grid is drawn again.
    /// </summary>
    public bool SetUnavailable(IEnumerable<SkippedSetup> skipped)
    {
        var next = new HashSet<DetectedInstance>(
            skipped.Where(s => s.Kind is SkipKind.SourceRunning or SkipKind.FolderMissing or SkipKind.InsideOrigin
                or SkipKind.UnknownVersion or SkipKind.Unsupported).Select(s => s.Setup),
            ReferenceEqualityComparer.Instance);
        if (next.SetEquals(_unavailable)) return false;
        _unavailable = next;
        return true;
    }

    // The setups a plan can take its settings from.
    private IEnumerable<DetectedInstance> Available => _setups.Where(s => !_unavailable.Contains(s));

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
            if (mine.Count == 0) return new GridCellState(false, false, $"{title}'s setups here bring packs only, not settings");
            // Versions this launcher has no setup on get its most played setup's settings: said in the tip.
            // The donor is the one the plan uses: a setup it can't take (Minecraft running from it) is passed over.
            var carried = Versions.Where(v => !SetupsOf(kind, v).Any(s => BulkComposer.CarriesSettings(s) && !_unavailable.Contains(s))).ToList();
            var tip = $"Use {title}'s settings (one launcher's settings for every version)"
                + (carried.Count > 0 && BulkComposer.SettingsSetup(kind, Available) is { McVersion: { } from }
                    ? $"; Minecraft {ImportFlowModel.JoinFew(carried)} gets your most played {title} settings (from {from})"
                    : "");
            return new GridCellState(SettingsLauncher == kind, true, tip);
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
    public const string PacksOnlyReason = "These setups bring their packs only";

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

    /// <summary>
    /// The launcher's name in a sentence ("Bring everything from Prism"): short enough that the tile's button never trims
    /// at the smallest window ("Minecraft Launcher" did, 2026-10-19 launcher-shots clip check).
    /// </summary>
    public static string ShortName(SourceKind kind) => kind switch
    {
        SourceKind.Folder => "your folders",
        SourceKind.OfficialLauncher => "Minecraft",
        SourceKind.Prism => "Prism",
        SourceKind.Modrinth => "Modrinth",
        _ => ImportFlowModel.LauncherTitle(kind).Replace(" Client", "", StringComparison.Ordinal),
    };

    /// <summary>
    /// A press on a cell. A tick turns on (or off) for every version of the tab where it can. Settings is one radio for
    /// the whole grid: on picks this launcher (and unticks the other); off brings no settings at all.
    /// </summary>
    public void Toggle(SourceKind kind, GridColumn column)
    {
        var cell = Cell(kind, column);
        if (!cell.Enabled) return;
        var on = cell.On != true;
        if (column == GridColumn.Settings)
        {
            _settingsPicked = true;
            _settingsPick = on ? kind : null;
            return;
        }
        if (column == GridColumn.Mods) _modsTouched = true;
        var category = Category(column);
        foreach (var version in TabVersions.Where(v => SetupsOf(kind, v).Count > 0 && Has(kind, v, column)))
        {
            var brings = _cells.Brings(kind, version);
            _cells.Set(version, kind, on ? brings | category : brings & ~category);
        }
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
            grid.SetSettings(version, SettingsFrom(version));
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
            parts.Add($"{version}|settings={SettingsFrom(version)?.ToString() ?? "none"}");
        }
        return string.Join(";", parts);
    }
}
