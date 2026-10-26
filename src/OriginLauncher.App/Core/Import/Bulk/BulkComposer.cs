using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import.Bulk;

// Who brings what into each Minecraft version (Will, 2026-10-19, amended 2026-10-25: each setup's mods become a profile; a player who used one
// launcher brings everything from it in one click; a player who mixed launchers picks, per launcher, what comes from
// it on one grid). Pure: it decides from the scan alone, so the engine plans with it and the Import page shows the
// same defaults it would plan.
//
// The rules (decision D2/D3 of the import-grid brief):
//  * Settings (options.txt, keybinds, Sodium / Iris / OptiFine settings): ONE setup per version.
//    - "Bring everything from <launcher>" (one launcher, no grid): that launcher's single most played setup, for
//      EVERY version, so every version plays the same and nothing needs setting again.
//    - Otherwise the version's settings launcher is the grid's pick, else the launcher of VersionPicker's pick among
//      the version's setups (B6). The setup: VersionPicker's pick when it is that launcher's, else the launcher's
//      most played setup of the version, else its most played setup of any version (written for this one).
//  * Mods: every setup that brings mods brings its OWN mods into an Origin profile of its own (Will, 2026-10-25), so
//    setups never mix: two Prism setups of one version become two profiles. By default every launcher brings mods.
//  * Resource packs, shader packs, worlds (+ screenshots), servers: from every setup of the launcher on the version.
//  * A Forge / NeoForge setup on a Fabric version brings packs (and screenshots) only (B4).

/// <summary>
/// The import grid the player filled in: per launcher and Minecraft version, what comes over; per version, whose settings.
/// A cell not set brings nothing; a version without a settings choice uses the default (<see cref="BulkComposer"/>).
/// Value-equal, so planning again with the same grid reuses the unchanged versions' plans.
/// </summary>
public sealed class ImportGrid : IEquatable<ImportGrid>
{
    /// <summary>The grid's columns other than Settings (screenshots ride with worlds).</summary>
    public const ImportCategories Things = ImportCategories.Mods | ImportCategories.ResourcePacks | ImportCategories.ShaderPacks
        | ImportCategories.Worlds | ImportCategories.Screenshots | ImportCategories.Servers;

    private readonly SortedDictionary<string, ImportCategories> _cells = new(StringComparer.Ordinal);
    private readonly SortedDictionary<string, SourceKind?> _settings = new(StringComparer.Ordinal);

    /// <summary>What <paramref name="launcher"/>'s setups of <paramref name="version"/> bring (Settings excluded: see <see cref="SetSettings"/>).</summary>
    public ImportGrid Set(string version, SourceKind launcher, ImportCategories brings)
    {
        _cells[Key(version, launcher)] = brings & Things;
        return this;
    }

    /// <summary>Whose settings <paramref name="version"/> gets: a launcher, or null for none at all.</summary>
    public ImportGrid SetSettings(string version, SourceKind? launcher)
    {
        _settings[version] = launcher;
        return this;
    }

    public ImportCategories Brings(SourceKind launcher, string version) =>
        _cells.TryGetValue(Key(version, launcher), out var brings) ? brings : ImportCategories.None;

    /// <summary>True when the player chose whose settings <paramref name="version"/> gets (<paramref name="launcher"/> null = none).</summary>
    public bool ChoseSettings(string version, out SourceKind? launcher) => _settings.TryGetValue(version, out launcher);

    private static string Key(string version, SourceKind launcher) => version + "|" + launcher;

    public bool Equals(ImportGrid? other) =>
        other != null && _cells.Count == other._cells.Count && _settings.Count == other._settings.Count
        && _cells.All(kv => other._cells.TryGetValue(kv.Key, out var v) && v == kv.Value)
        && _settings.All(kv => other._settings.TryGetValue(kv.Key, out var v) && v == kv.Value);

    public override bool Equals(object? obj) => Equals(obj as ImportGrid);

    public override int GetHashCode()
    {
        var hash = new HashCode();
        foreach (var (k, v) in _cells) { hash.Add(k, StringComparer.Ordinal); hash.Add(v); }
        foreach (var (k, v) in _settings) { hash.Add(k, StringComparer.Ordinal); hash.Add(v); }
        return hash.ToHashCode();
    }
}

/// <summary>One setup's part in one version: what it brings, and whether its mod configs lead.</summary>
/// <param name="PrimaryMods">The first setup of the version that brings mods (its mod configs replace Origin's).</param>
/// <param name="OtherVersion">The settings setup is from another Minecraft version (its settings are written for this one).</param>
public sealed record Contribution(DetectedInstance Setup, ImportCategories Brings, bool PrimaryMods, bool OtherVersion);

/// <summary>What one Minecraft version gets, in apply order (the settings setup first).</summary>
/// <param name="Left">The version's setups that bring nothing (the player unticked every cell of their launcher).</param>
public sealed record VersionComposition(string Version, IReadOnlyList<Contribution> Order, DetectedInstance? SettingsFrom,
    PickResult Pick, IReadOnlyList<DetectedInstance> Left);

public static class BulkComposer
{
    /// <summary>Most played first: playtime, then last played, then the bulk order (BulkRanking) as the tie-break.</summary>
    public static readonly IComparer<DetectedInstance> MostPlayed = Comparer<DetectedInstance>.Create((a, b) =>
    {
        var c = (b.PlaytimeSeconds ?? 0).CompareTo(a.PlaytimeSeconds ?? 0);
        if (c != 0) return c;
        c = (b.LastPlayed ?? DateTimeOffset.MinValue).CompareTo(a.LastPlayed ?? DateTimeOffset.MinValue);
        return c != 0 ? c : BulkRanking.Compare(a, b);
    });

    /// <summary>A setup that can give its settings and mods (a Forge setup on a Fabric version brings packs only).</summary>
    public static bool CarriesSettings(DetectedInstance setup) =>
        setup.McVersion != null && !BulkRules.IsPacksOnly(setup, setup.McVersion);

    /// <summary>The setup a launcher's settings come from: its most played setup that can carry settings (null: none).</summary>
    public static DetectedInstance? SettingsSetup(SourceKind launcher, IEnumerable<DetectedInstance> setups) =>
        setups.Where(s => s.Kind == launcher && CarriesSettings(s)).OrderBy(s => s, MostPlayed).FirstOrDefault();

    /// <summary>The setup of <paramref name="launcher"/> whose mods <paramref name="version"/> gets: its most played one there.</summary>
    public static DetectedInstance? ModsSetup(SourceKind launcher, string version, IEnumerable<DetectedInstance> setups) =>
        setups.Where(s => s.Kind == launcher && s.McVersion == version && CarriesSettings(s)).OrderBy(s => s, MostPlayed).FirstOrDefault();

    /// <summary>VersionPicker's pick among the version's setups that can carry settings (null: none can; B6).</summary>
    public static DetectedInstance? VersionWinner(string version, IReadOnlyList<DetectedInstance> setups, out PickResult pick)
    {
        var participants = setups.Where(s => s.McVersion == version && CarriesSettings(s)).OrderBy(s => s, BulkRanking.Order).ToList();
        if (participants.Count == 0)
        {
            pick = new PickResult(PickMode.None, Array.Empty<DetectedInstance>(), null);
            return null;
        }
        pick = VersionPicker.Pick(participants);
        return pick.Preselected ?? participants[0];
    }

    /// <summary>The default settings launcher of a version: the launcher of its VersionPicker pick (null: none of its setups can carry settings).</summary>
    public static SourceKind? DefaultSettingsLauncher(string version, IReadOnlyList<DetectedInstance> setups) =>
        VersionWinner(version, setups, out _)?.Kind;

    /// <summary>
    /// The setup whose settings <paramref name="version"/> gets. <paramref name="sameEverywhere"/>: the one-launcher fast
    /// path (every version gets the most played setup's settings). Otherwise <paramref name="grid"/>'s pick, else B6.
    /// </summary>
    public static DetectedInstance? SettingsDonor(string version, IReadOnlyList<DetectedInstance> setups, ImportGrid? grid,
        bool sameEverywhere, out PickResult pick)
    {
        var winner = VersionWinner(version, setups, out pick);
        if (sameEverywhere) return setups.Where(CarriesSettings).OrderBy(s => s, MostPlayed).FirstOrDefault();
        var launcher = grid != null && grid.ChoseSettings(version, out var chosen) ? chosen : winner?.Kind;
        if (launcher is not { } l) return null;
        if (winner != null && winner.Kind == l) return winner;
        return ModsSetup(l, version, setups) ?? SettingsSetup(l, setups);
    }

    /// <summary>The default mods launcher of a version: the launcher of its most played setup there that can carry mods.</summary>
    public static SourceKind? DefaultModsLauncher(string version, IReadOnlyList<DetectedInstance> setups) =>
        setups.Where(s => s.McVersion == version && CarriesSettings(s)).OrderBy(s => s, MostPlayed).FirstOrDefault()?.Kind;

    /// <summary>What a launcher's setups of a version bring when nobody changed the grid: everything.</summary>
    public static ImportCategories DefaultBrings(SourceKind launcher, string version, IReadOnlyList<DetectedInstance> setups)
    {
        // Every launcher brings its mods: each setup's land in a profile of their own, so nothing mixes.
        return ImportGrid.Things;
    }

    /// <summary>
    /// Every version's contributions, newest version first. <paramref name="setups"/> are the setups that qualified
    /// (exact supported version, folder there, nothing running); <paramref name="grid"/> null = the defaults;
    /// <paramref name="settingsChoices"/> pins a version's settings setup by <see cref="InstanceIds.BulkId"/>;
    /// <paramref name="sameSettingsEverywhere"/> is the one-launcher fast path (see <see cref="SettingsDonor"/>).
    /// </summary>
    public static List<VersionComposition> Compose(IReadOnlyList<DetectedInstance> setups, ImportGrid? grid,
        IReadOnlyDictionary<string, string>? settingsChoices = null, bool sameSettingsEverywhere = false)
    {
        var result = new List<VersionComposition>();
        foreach (var bucket in setups.Where(s => s.McVersion != null).GroupBy(s => s.McVersion!, StringComparer.Ordinal)
                     .OrderBy(g => g.Key, BulkRanking.NewestFirst))
        {
            var version = bucket.Key;
            var donor = SettingsDonor(version, setups, grid, sameSettingsEverywhere, out var pick);
            if (settingsChoices != null && settingsChoices.TryGetValue(version, out var pinnedId)
                && setups.FirstOrDefault(s => CarriesSettings(s) && string.Equals(s.BulkId(), pinnedId, StringComparison.OrdinalIgnoreCase)) is { } pinned)
                donor = pinned;

            ImportCategories Cell(SourceKind kind, string v) => grid != null ? grid.Brings(kind, v) : DefaultBrings(kind, v, setups);

            var members = bucket.OrderBy(s => s, BulkRanking.Order).ToList();
            var order = new List<Contribution>();
            var left = new List<DetectedInstance>();
            foreach (var setup in members)
            {
                var cell = Cell(setup.Kind, version);
                var brings = cell & ~ImportCategories.Mods;
                if (!CarriesSettings(setup)) brings &= ImportCategories.ResourcePacks | ImportCategories.ShaderPacks | ImportCategories.Screenshots;
                else if ((cell & ImportCategories.Mods) != 0) brings |= ImportCategories.Mods; // its own profile
                if (ReferenceEquals(setup, donor)) brings |= ImportCategories.Settings;
                if (brings == ImportCategories.None) left.Add(setup);
                else order.Add(new Contribution(setup, brings, false, false));
            }
            if (order.Count == 0)
            {
                result.Add(new VersionComposition(version, order, null, pick, left));
                continue;
            }

            // The settings setup goes first (its settings land before the others add their things). One from another
            // version brings its settings, plus the packs those settings switch on when its launcher brings packs.
            var inVersion = order.FindIndex(c => ReferenceEquals(c.Setup, donor));
            if (inVersion > 0)
            {
                var first = order[inVersion];
                order.RemoveAt(inVersion);
                order.Insert(0, first);
            }
            else if (inVersion < 0 && donor != null)
            {
                var packs = Cell(donor.Kind, donor.McVersion!) & (ImportCategories.ResourcePacks | ImportCategories.ShaderPacks);
                order.Insert(0, new Contribution(donor, ImportCategories.Settings | packs, false, true));
            }

            var primary = order.FindIndex(c => (c.Brings & ImportCategories.Mods) != 0);
            if (primary >= 0) order[primary] = order[primary] with { PrimaryMods = true };
            result.Add(new VersionComposition(version, order, donor, pick, left));
        }
        return result;
    }
}
