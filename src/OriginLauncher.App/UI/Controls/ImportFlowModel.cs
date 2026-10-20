using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.UI.Controls;

// The import drawer's choices, with no WPF: which launcher, which Minecraft versions, which setups, and what each
// version brings. The drawer (ImportPanel) draws it; the engine gets it as a BulkSelection plus ImportOptions
// (BringByVersion). Pure, so it is linked into OriginLauncher.Tests (ImportFlowModelTests).
//
//   Choose a launcher -> its versions ("Import from all versions", "What to bring" once for all)
//     -> one version (what it brings, which setups, whose settings) -> Review -> Import
//
// Rules it keeps:
//  * a version Origin can't run (or a setup whose version is unknown) is listed, dimmed, with the reason: never dropped;
//  * versions map exactly (never remapped): one row per Minecraft version found;
//  * whose settings win follows the engine's own rule (BulkRanking order, then VersionPicker), so the row says what
//    the plan will do; the player's pick (Choices) wins over it, as in the engine.

/// <summary>What a launcher tile says.</summary>
internal enum LauncherState
{
    Looking,       // the scan is still running
    Found,         // setups to import
    Empty,         // installed, nothing to import
    NotInstalled,  // its folder isn't on this PC
}

/// <summary>One launcher on the first page: its name, whether anything was found, and a short count.</summary>
internal sealed record LauncherTile(SourceKind Kind, string Title, LauncherState State, string Caption, int Setups)
{
    public bool Enabled => State == LauncherState.Found;
}

/// <summary>One Minecraft version in the chosen launcher(s): its setups, and whether Origin can take it.</summary>
internal sealed class VersionEntry
{
    /// <summary>The version ("1.21.1"), or <see cref="ImportFlowModel.UnknownKey"/> for setups whose version is unknown.</summary>
    public required string Key { get; init; }
    public string? Version => Key == ImportFlowModel.UnknownKey ? null : Key;
    public required IReadOnlyList<DetectedInstance> Setups { get; init; }
    public bool Supported { get; init; }

    /// <summary>Why it can't come over (null when it can): "Not supported", "Version unknown".</summary>
    public string? Reason { get; init; }

    /// <summary>"1.21.1", or "Version unknown".</summary>
    public string Title => Version ?? "Version unknown";
}

/// <summary>
/// How the next import is chosen. Ticks: the setups ticked on the Import page (Import everything). Fast: "Bring
/// everything from" one launcher (all its setups, the most played one's settings on every version). Grid: "Choose what
/// to import" (a row per launcher, a tick per thing: ImportGridModel).
/// </summary>
internal enum ImportMode { Ticks, Fast, Grid }

/// <summary>Whose settings a version gets: the setup, the picker's verdict, and whether the player should pick.</summary>
internal sealed record WinnerPreview(DetectedInstance? Winner, PickResult Pick, bool NeedsChoice, IReadOnlyList<DetectedInstance> Participants);

internal sealed class ImportFlowModel
{
    public const string UnknownKey = "?";

    /// <summary>What a version brings until the player says otherwise: everything but worlds (worlds can be big; the engine's default).</summary>
    public const ImportCategories DefaultBring = ImportCategories.All & ~ImportCategories.Worlds;

    /// <summary>The launcher tiles, in the order they are shown (the official launcher first, then the PvP clients, then the instance launchers).</summary>
    public static readonly IReadOnlyList<SourceKind> TileOrder = new[]
    {
        SourceKind.OfficialLauncher, SourceKind.Lunar, SourceKind.Feather, SourceKind.LabyMod, SourceKind.Badlion,
        SourceKind.Prism, SourceKind.CurseForge, SourceKind.Modrinth,
    };

    private readonly List<DetectedInstance> _setups = new();
    private readonly HashSet<string> _supported;
    private readonly HashSet<string> _unselectedVersions = new(StringComparer.Ordinal);
    private readonly HashSet<string> _untickedSetups = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, ImportCategories> _bring = new(StringComparer.Ordinal);

    public ImportFlowModel(IEnumerable<DetectedInstance> setups, IEnumerable<string> supportedTargets)
    {
        _setups.AddRange(setups);
        _supported = new HashSet<string>(supportedTargets, StringComparer.Ordinal);
    }

    public IReadOnlyList<DetectedInstance> Setups => _setups;

    private IReadOnlyDictionary<string, SetupContents> _contents = new Dictionary<string, SetupContents>();

    /// <summary>What each setup holds (by SetupId); empty until the drawer has counted. The grid greys its empty cells from it.</summary>
    public IReadOnlyDictionary<string, SetupContents> Contents
    {
        get => _contents;
        set
        {
            _contents = value;
            if (GridModel != null) GridModel.Contents = value;
        }
    }

    /// <summary>The launchers installed on this PC; null = unknown (then a launcher with nothing found just says so).</summary>
    public IReadOnlySet<SourceKind>? Installed { get; set; }

    /// <summary>The version the player picked whose settings to use, by version (the engine's WinnerChoices).</summary>
    public Dictionary<string, string> Choices { get; } = new(StringComparer.Ordinal);

    /// <summary>The launcher being imported from; null = every launcher.</summary>
    public SourceKind? Scope { get; private set; }

    /// <summary>How the next import is chosen (see <see cref="ImportMode"/>).</summary>
    public ImportMode Mode { get; private set; } = ImportMode.Ticks;

    /// <summary>The grid of "Choose what to import" (Mode Grid); kept while the page lives, so going back keeps the ticks.</summary>
    public ImportGridModel? GridModel { get; private set; }

    /// <summary>Import everything: the ticked setups of every launcher.</summary>
    public void UseTicks()
    {
        Mode = ImportMode.Ticks;
        Scope = null;
    }

    /// <summary>"Bring everything from <paramref name="launcher"/>": all of its setups Origin can run, nothing else.</summary>
    public void UseFast(SourceKind launcher)
    {
        Mode = ImportMode.Fast;
        Scope = launcher;
    }

    /// <summary>"Choose what to import": the grid over every setup Origin can run (made once, then kept).</summary>
    public ImportGridModel UseGrid()
    {
        Mode = ImportMode.Grid;
        Scope = null;
        GridModel ??= new ImportGridModel(_setups.Where(CanGo));
        GridModel.Contents = Contents;
        return GridModel;
    }

    /// <summary>The setups "Bring everything from" takes for a launcher: every one Origin can run.</summary>
    public IReadOnlyList<DetectedInstance> FastSetups(SourceKind launcher) => _setups.Where(s => s.Kind == launcher && CanGo(s)).ToList();

    /// <summary>The launchers that have something to import (the tiles with a "Bring everything" button).</summary>
    public IReadOnlyList<SourceKind> LaunchersWithSetups() =>
        Tiles(false).Where(t => t.State == LauncherState.Found && FastSetups(t.Kind).Count > 0).Select(t => t.Kind).ToList();

    /// <summary>The engine's grid (Mode Grid), else null.</summary>
    public ImportGrid? Grid() => Mode == ImportMode.Grid ? GridModel?.Grid() : null;

    /// <summary>Browse added setups (one folder is one setup: a folder already listed is not added twice). Returns how many were new.</summary>
    public int Add(IEnumerable<DetectedInstance> picked)
    {
        var added = 0;
        foreach (var setup in picked)
        {
            if (_setups.Any(s => s.BulkId() == setup.BulkId())) continue;
            _setups.Add(setup);
            added++;
        }
        return added;
    }

    public void SetScope(SourceKind? launcher) => Scope = launcher;

    public IEnumerable<DetectedInstance> InScope => _setups.Where(s => Scope == null || s.Kind == Scope);

    public bool InScopeOf(DetectedInstance setup) => Scope == null || setup.Kind == Scope;

    // ---------------------------------------------------------------- launchers

    public static string LauncherTitle(SourceKind kind) => kind switch
    {
        SourceKind.OfficialLauncher => "Minecraft Launcher",
        SourceKind.Lunar => "Lunar Client",
        SourceKind.Feather => "Feather Client",
        SourceKind.LabyMod => "LabyMod",
        SourceKind.Badlion => "Badlion Client",
        SourceKind.Prism => "Prism / MultiMC",
        SourceKind.CurseForge => "CurseForge",
        SourceKind.Modrinth => "Modrinth App",
        _ => "Picked folders",
    };

    /// <summary>
    /// Every launcher Origin can import from, plus "Picked folders" once Browse found one. <paramref name="scanning"/>:
    /// the tiles show "Looking..." until the scan is in.
    /// </summary>
    public IReadOnlyList<LauncherTile> Tiles(bool scanning)
    {
        var tiles = new List<LauncherTile>();
        foreach (var kind in TileOrder) tiles.Add(Tile(kind, scanning));
        if (_setups.Any(s => s.Kind == SourceKind.Folder)) tiles.Add(Tile(SourceKind.Folder, false));
        return tiles;
    }

    private LauncherTile Tile(SourceKind kind, bool scanning)
    {
        var title = LauncherTitle(kind);
        var mine = _setups.Where(s => s.Kind == kind).ToList();
        if (mine.Count == 0)
        {
            if (scanning) return new LauncherTile(kind, title, LauncherState.Looking, "Looking...", 0);
            if (Installed != null && !Installed.Contains(kind) && kind != SourceKind.Folder)
                return new LauncherTile(kind, title, LauncherState.NotInstalled, "Not installed", 0);
            return new LauncherTile(kind, title, LauncherState.Empty, "Nothing to import", 0);
        }
        return new LauncherTile(kind, title, LauncherState.Found, Summary(mine), mine.Count);
    }

    /// <summary>"4 versions, 12 worlds": how many versions, and the worlds once counted (each saves folder once).</summary>
    public string Summary(IReadOnlyCollection<DetectedInstance> setups)
    {
        var versions = setups.Select(s => s.McVersion ?? UnknownKey).Distinct(StringComparer.Ordinal).Count();
        var parts = new List<string> { ImportLogic.Plural(versions, "version", "versions") };
        var worlds = Total(setups, ImportCategories.Worlds);
        if (worlds > 0) parts.Add(ImportLogic.Plural(worlds, "world", "worlds"));
        return string.Join(", ", parts);
    }

    // ---------------------------------------------------------------- versions

    /// <summary>The chosen launcher's versions: Origin's newest first, then the ones it can't take, then "Version unknown".</summary>
    public IReadOnlyList<VersionEntry> Versions()
    {
        var list = new List<VersionEntry>();
        foreach (var group in InScope.GroupBy(KeyOf, StringComparer.Ordinal))
        {
            var setups = group.OrderBy(s => s, BulkRanking.Order).ToList();
            var key = group.Key;
            var supported = key != UnknownKey && _supported.Contains(key);
            list.Add(new VersionEntry
            {
                Key = key, Setups = setups, Supported = supported,
                Reason = key == UnknownKey ? "Version unknown" : supported ? null : BulkRules.UnsupportedCaption,
            });
        }
        return list
            .OrderBy(v => v.Key == UnknownKey ? 2 : v.Supported ? 0 : 1)
            .ThenBy(v => v.Key == UnknownKey ? "" : v.Key, BulkRanking.NewestFirst)
            .ToList();
    }

    /// <summary>A setup's version row: its own version, or "unknown" (the engine skips it with UnknownVersionReason).</summary>
    public static string KeyOf(DetectedInstance setup) =>
        setup.Problem != null || string.IsNullOrWhiteSpace(setup.McVersion) ? UnknownKey : setup.McVersion!;

    public VersionEntry? Version(string key) => Versions().FirstOrDefault(v => v.Key == key);

    public bool IsSelected(VersionEntry version) => version.Supported && !_unselectedVersions.Contains(version.Key);

    public void SetSelected(string key, bool on)
    {
        if (on) _unselectedVersions.Remove(key); else _unselectedVersions.Add(key);
    }

    /// <summary>"Import from all versions": true when every version Origin can take is ticked, false when none, null in between.</summary>
    public bool? AllVersions()
    {
        var supported = Versions().Where(v => v.Supported).ToList();
        if (supported.Count == 0) return false;
        var on = supported.Count(IsSelected);
        return on == supported.Count ? true : on == 0 ? false : null;
    }

    /// <summary>Ticks (or unticks) every version Origin can take; versions it can't stay as they are (off).</summary>
    public void SetAllVersions(bool on)
    {
        foreach (var version in Versions().Where(v => v.Supported)) SetSelected(version.Key, on);
    }

    // ---------------------------------------------------------------- setups

    public bool IsTicked(DetectedInstance setup) => !_untickedSetups.Contains(setup.BulkId());

    public void SetTicked(DetectedInstance setup, bool on)
    {
        if (on) _untickedSetups.Remove(setup.BulkId()); else _untickedSetups.Add(setup.BulkId());
    }

    /// <summary>The setups of a version that come over: the version is ticked and so is the setup.</summary>
    public IReadOnlyList<DetectedInstance> Going(VersionEntry version) =>
        IsSelected(version) ? version.Setups.Where(IsTicked).ToList() : Array.Empty<DetectedInstance>();

    public IReadOnlyList<DetectedInstance> AllGoing() => Versions().SelectMany(Going).ToList();

    // The Import page ticks per setup (a launcher's tile ticks all of its setups at once). A setup can only come over when
    // Origin runs its Minecraft version; a version the player switched off in "What to bring" holds its setups back.

    /// <summary>Origin runs this setup's Minecraft version, so it can be ticked at all.</summary>
    public bool CanGo(DetectedInstance setup)
    {
        var key = KeyOf(setup);
        return key != UnknownKey && _supported.Contains(key);
    }

    /// <summary>The setup comes over: its version is supported and on, and the setup itself is ticked.</summary>
    public bool IsGoing(DetectedInstance setup) =>
        CanGo(setup) && !_unselectedVersions.Contains(KeyOf(setup)) && IsTicked(setup);

    /// <summary>Takes a setup in or out. In brings its version back too (a tick means "bring it"); a setup Origin can't run is never ticked.</summary>
    public void SetGoing(DetectedInstance setup, bool on)
    {
        if (!CanGo(setup)) return;
        SetTicked(setup, on);
        if (on) _unselectedVersions.Remove(KeyOf(setup));
    }

    /// <summary>True when every setup that can come over is going, false when none is, null in between (or no setup can).</summary>
    public bool? GoingState(IEnumerable<DetectedInstance> setups)
    {
        var can = setups.Where(CanGo).ToList();
        if (can.Count == 0) return false;
        var going = can.Count(IsGoing);
        return going == can.Count ? true : going == 0 ? false : null;
    }

    /// <summary>Every setup that is going (all launchers).</summary>
    public IReadOnlyList<DetectedInstance> GoingSetups() => _setups.Where(IsGoing).ToList();

    public int SelectedVersionCount => Versions().Count(v => IsSelected(v) && Going(v).Count > 0);

    // ---------------------------------------------------------------- what to bring

    /// <summary>
    /// What a version with no choice of its own brings. <see cref="DefaultBring"/> unless the page sets it (the Import page
    /// brings worlds too: "bring it all over"); the player's switches always win.
    /// </summary>
    public ImportCategories BringByDefault { get; set; } = DefaultBring;

    public ImportCategories BringFor(string version) => _bring.TryGetValue(version, out var bring) ? bring : BringByDefault;

    public void SetBring(string version, ImportCategories category, bool on)
    {
        var bring = BringFor(version);
        _bring[version] = on ? bring | category : bring & ~category;
    }

    /// <summary>"What to bring" for every version at once: on for all, or off for all.</summary>
    public void SetBringAll(ImportCategories category, bool on)
    {
        foreach (var version in Versions().Where(v => v.Supported)) SetBring(version.Key, category, on);
    }

    /// <summary>The all-versions switch's state for one category: true when every ticked version brings it, false when none, null when some.</summary>
    public bool? BringsAll(ImportCategories category)
    {
        var versions = Versions().Where(IsSelected).ToList();
        if (versions.Count == 0) versions = Versions().Where(v => v.Supported).ToList();
        if (versions.Count == 0) return (BringByDefault & category) != 0;
        var on = versions.Count(v => (BringFor(v.Key) & category) != 0);
        return on == versions.Count ? true : on == 0 ? false : null;
    }

    /// <summary>How many of <paramref name="category"/> the setups hold, each folder counted once (two Lunar versions share one saves folder).</summary>
    public int Total(IEnumerable<DetectedInstance> setups, ImportCategories category)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var total = 0;
        foreach (var setup in setups)
        {
            if (!Contents.TryGetValue(setup.SetupId, out var contents)) continue;
            if (!seen.Add(FolderOf(setup, category))) continue;
            total += contents.Count(category);
        }
        return total;
    }

    public bool Counted(IEnumerable<DetectedInstance> setups) => setups.Any(s => Contents.ContainsKey(s.SetupId));

    private static string FolderOf(DetectedInstance setup, ImportCategories category) => category switch
    {
        ImportCategories.Worlds => setup.SavesFolder,
        ImportCategories.Screenshots => setup.ScreenshotsFolder,
        ImportCategories.Mods => setup.ModsFolder,
        ImportCategories.ShaderPacks => setup.ShaderPacksFolder,
        _ => setup.GameDir + "|" + category,
    };

    /// <summary>"12 mods, 4 worlds, 3 packs, servers": what a set of setups holds, in a few words (empty until counted).</summary>
    public string ContentsLine(IReadOnlyCollection<DetectedInstance> setups)
    {
        if (!Counted(setups)) return "";
        var parts = new List<string>();
        void Add(ImportCategories c, string one, string many)
        {
            var n = Total(setups, c);
            if (n > 0) parts.Add(ImportLogic.Plural(n, one, many));
        }
        Add(ImportCategories.Mods, "mod", "mods");
        Add(ImportCategories.Worlds, "world", "worlds");
        var packs = Total(setups, ImportCategories.ResourcePacks) + Total(setups, ImportCategories.ShaderPacks);
        if (packs > 0) parts.Add(ImportLogic.Plural(packs, "pack", "packs"));
        Add(ImportCategories.Screenshots, "screenshot", "screenshots");
        if (Total(setups, ImportCategories.Servers) > 0) parts.Add("servers");
        return parts.Count == 0 ? "Settings only" : string.Join(", ", parts);
    }

    /// <summary>The count a category switch shows ("12"), empty for the yes/no kinds (servers, settings) and before counting.</summary>
    public string CountText(IReadOnlyCollection<DetectedInstance> setups, ImportCategories category)
    {
        if (!Counted(setups) || category is ImportCategories.Servers or ImportCategories.Settings) return "";
        return Total(setups, category).ToString(System.Globalization.CultureInfo.InvariantCulture);
    }

    /// <summary>A category the setups have none of can't be switched (unknown before counting: then it can).</summary>
    public bool Has(IReadOnlyCollection<DetectedInstance> setups, ImportCategories category) =>
        !Counted(setups) || Total(setups, category) > 0;

    // ---------------------------------------------------------------- whose settings

    /// <summary>
    /// Whose settings <paramref name="version"/> gets: the engine's rule (packs-only setups never win; BulkRanking order;
    /// VersionPicker; the player's pick wins), over the setups that are going.
    /// </summary>
    public WinnerPreview Winner(VersionEntry version)
    {
        var participants = version.Setups.Where(IsTicked)
            .Where(s => version.Version == null || !BulkRules.IsPacksOnly(s, version.Version))
            .OrderBy(s => s, BulkRanking.Order).ToList();
        if (participants.Count == 0) return new WinnerPreview(null, new PickResult(PickMode.None, Array.Empty<DetectedInstance>(), null), false, participants);
        var pick = VersionPicker.Pick(participants);
        if (Choices.TryGetValue(version.Key, out var chosenId)
            && participants.FirstOrDefault(p => string.Equals(p.BulkId(), chosenId, StringComparison.OrdinalIgnoreCase)) is { } chosen)
            return new WinnerPreview(chosen, pick, false, participants);
        return new WinnerPreview(pick.Preselected ?? participants[0], pick, pick.Mode == PickMode.Choose, participants);
    }

    /// <summary>The version row's line: "3 setups - settings from Hypixel" (one setup: just "1 setup").</summary>
    public string VersionCaption(VersionEntry version)
    {
        if (!version.Supported) return ImportLogic.Plural(version.Setups.Count, "setup", "setups");
        var going = version.Setups.Where(IsTicked).ToList();
        var text = ImportLogic.Plural(version.Setups.Count, "setup", "setups");
        if (going.Count < version.Setups.Count) text = $"{going.Count} of {text}";
        if (going.Count > 1 && Winner(version) is { Winner: { } winner }) text += " - settings from " + winner.Name;
        return text;
    }

    // ---------------------------------------------------------------- what the engine gets

    /// <summary>
    /// The engine's selection: the chosen launcher's setups (or every one), minus those the player left out (a version
    /// unticked, a setup unticked). Setups on versions Origin can't take stay IN, so the plan and the Done page list each
    /// with its reason instead of dropping it.
    /// </summary>
    public BulkSelection Selection()
    {
        // The fast path is the whole launcher: the engine reads OfLauncher with no grid as "same settings everywhere".
        if (Mode == ImportMode.Fast && Scope is { } fast) return BulkSelection.OfLauncher(fast);
        // The grid is every setup: its ticks decide who brings what (a row with nothing ticked brings nothing).
        if (Mode == ImportMode.Grid) return BulkSelection.All();
        var selection = Scope is { } launcher ? BulkSelection.OfLauncher(launcher) : BulkSelection.All();
        foreach (var version in Versions().Where(v => v.Supported))
            foreach (var setup in version.Setups)
                if (!IsSelected(version) || !IsTicked(setup))
                    selection = selection.Without(setup.BulkId());
        return selection;
    }

    /// <summary>What each going version brings (the engine's BringByVersion).</summary>
    public CategoryMap BringMap() => new(Versions().Where(v => v.Supported)
        .Select(v => new KeyValuePair<string, ImportCategories>(v.Key, BringFor(v.Key))));

    /// <summary>
    /// The options a plan is made with: the Details switches (<paramref name="details"/>) and what each version brings.
    /// Worlds and screenshots are the categories' to decide, so their switches are on here.
    /// </summary>
    public ImportOptions Options(ImportOptions details) => Mode == ImportMode.Ticks
        ? details with { CopyWorlds = true, CopyScreenshots = true, LeaveOut = ImportCategories.None, BringByVersion = BringMap() }
        // Fast and grid bring every pack of the setups that bring packs (the union), not just the ones switched on.
        : details with { CopyWorlds = true, CopyScreenshots = true, CopyUnusedPacks = true, LeaveOut = ImportCategories.None, BringByVersion = null };

    /// <summary>"Worlds, Screenshots": the categories a version leaves out, by name (empty: nothing).</summary>
    public static string LeftOutNames(ImportCategories bring) =>
        string.Join(", ", CategoryFilter.Each.Where(c => (bring & c) == 0).Select(CategoryFilter.Name));

    /// <summary>"a, b, c" or "a, b and 4 more": a list of names that stays one or two lines in the drawer.</summary>
    public static string JoinFew(IReadOnlyList<string> names, int shown = 3) =>
        names.Count <= shown ? string.Join(", ", names)
            : string.Join(", ", names.Take(shown - 1)) + $" and {names.Count - (shown - 1)} more";

    /// <summary>Whether a skipped setup belongs on the Review and Done pages: setups of another launcher were never part of this import.</summary>
    public bool Shows(SkippedSetup skipped) => skipped.Kind != SkipKind.NotSelected && InScopeOf(skipped.Setup);

    /// <summary>
    /// The result the Done page reads: the same run, without the setups that were never part of it (another launcher's,
    /// NotSelected) in its skipped list or its "didn't come over" lines. Everything else is passed on as the engine wrote it.
    /// </summary>
    public BulkImportResult ForDone(BulkImportResult result)
    {
        var hidden = result.Skipped.Where(s => !Shows(s)).ToList();
        if (hidden.Count == 0) return result;
        var hiddenLabels = new HashSet<string>(hidden.Select(s => s.Setup.BulkLabel()), StringComparer.Ordinal);
        var shownLabels = new HashSet<string>(result.Skipped.Where(Shows).Select(s => s.Setup.BulkLabel()), StringComparer.Ordinal);
        return new BulkImportResult
        {
            RunId = result.RunId, State = result.State, Cancelled = result.Cancelled, Error = result.Error,
            Versions = result.Versions, Notes = result.Notes, SummaryText = result.SummaryText,
            Skipped = result.Skipped.Where(Shows).ToList(),
            // A whole-setup line of a hidden setup goes with it (a shown setup with the same label keeps its line).
            DidntComeOver = result.DidntComeOver
                .Where(d => !(hiddenLabels.Contains(d.SetupLabel) && !shownLabels.Contains(d.SetupLabel) && d.Name == WholeSetup))
                .ToList(),
        };
    }

    /// <summary>The engine's name for a skipped setup's line in "didn't come over" (BulkEngine).</summary>
    public const string WholeSetup = "Whole setup";

    /// <summary>
    /// Everything <see cref="Selection"/> and the settings choices depend on, as one string: two equal keys (with equal
    /// options) plan the same, so the Review page reuses its plan when the player only looked around.
    /// </summary>
    public string SelectionKey()
    {
        // Fast and grid ignore the page's ticks and the settings picks: the launcher (or the grid) is the whole choice.
        if (Mode == ImportMode.Fast) return $"fast|{Scope}|{_setups.Count}";
        if (Mode == ImportMode.Grid) return $"grid:{GridModel?.Key()}|{_setups.Count}";
        var left = new List<string>();
        foreach (var version in Versions().Where(v => v.Supported))
            foreach (var setup in version.Setups)
                if (!IsSelected(version) || !IsTicked(setup))
                    left.Add(setup.BulkId());
        left.Sort(StringComparer.Ordinal);
        var choices = Choices.OrderBy(c => c.Key, StringComparer.Ordinal).Select(c => c.Key + "=" + c.Value);
        return $"ticks|{Scope?.ToString() ?? "all"}|{_setups.Count}|{string.Join(",", left)}|{string.Join(",", choices)}";
    }
}
