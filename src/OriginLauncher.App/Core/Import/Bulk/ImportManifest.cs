namespace OriginLauncher.App.Core.Import.Bulk;

// The ONE list of "this source version -> that Origin version" copies an import makes. Every screen that talks about
// what an import does (the Review preview, Details, the Importing rows, the Done page) is drawn from THIS list, and
// it is built from the very plan object that ApplyBulkAsync executes: one planned setup in the plan is exactly one
// mapping here, in the same order the engine applies them (and reports them: SetupOutcome i of group g is mapping
// (g, i)). Nothing is added or dropped on the way.
//
// Why it exists (Will, 2026-10-20: "the importing process didn't say it was gunna copy anything from 1.8.9 but then
// when actually importing it said it was"): with a Lunar import the settings of the most played setup (Lunar 1.8.9)
// are written into EVERY version. The engine plans that as an extra planned setup inside each other version's group
// (PlannedSetup.OtherVersion = true), and the old Review drew a row per setup NAME with no source version, dropped the
// plan's "Settings from its Minecraft 1.8.9, set up for Minecraft 1.21.1" line (it lives in the Imported lines, which
// only the result summary prints) and counted the donor again in "Setups", so the preview never said 1.8.9 was a
// source for 1.21.1 while the run and the summary did.

/// <summary>One copy the import makes: one source setup (at its own Minecraft version) into one Origin version.</summary>
/// <param name="Index">Its place in the apply order across the whole plan (also the Importing row it gets).</param>
/// <param name="Brings">The categories this copy covers (what the plan brings: used to remember what was imported).</param>
public sealed record ImportMapping(
    int Index, string GroupVersion, int SlotInGroup, DetectedInstance Setup, string TargetVersion, SetupRole Role,
    bool FromOtherVersion, bool IsSettingsWinner, ImportCategories Brings, string Caption)
{
    /// <summary>The source's Minecraft version ("?" when the scan could not tell).</summary>
    public string SourceVersion => string.IsNullOrWhiteSpace(Setup.McVersion) ? "?" : Setup.McVersion!;

    /// <summary>"Lunar 1.8.9 -> 1.21.1": the launcher, its Minecraft version, then Origin's. ASCII only.</summary>
    public string Title => ImportManifest.TitleOf(Setup, TargetVersion);

    /// <summary>Unique per mapping: a setup that lends its settings to another version is two mappings with two ids.</summary>
    public string Id => $"{Setup.BulkId()}>{TargetVersion}";
}

public static class ImportManifest
{
    /// <summary>"Lunar 1.8.9 -> 1.21.1". A picked folder is named for itself ("Folder" is no name).</summary>
    public static string TitleOf(DetectedInstance setup, string targetVersion)
    {
        var launcher = setup.Kind == SourceKind.Folder ? setup.Name : setup.SourceLabel;
        var source = string.IsNullOrWhiteSpace(setup.McVersion) ? "?" : setup.McVersion;
        return $"{launcher} {source} -> {targetVersion}";
    }

    /// <summary>Every copy the plan makes, in apply order (groups newest version first, each settings setup first).</summary>
    public static IReadOnlyList<ImportMapping> From(BulkImportPlan plan)
    {
        var list = new List<ImportMapping>();
        foreach (var group in plan.Groups)
        {
            for (var slot = 0; slot < group.Setups.Count; slot++)
            {
                var planned = group.Setups[slot];
                list.Add(new ImportMapping(
                    list.Count, group.Version, slot, planned.Setup, group.Version, planned.Role, planned.OtherVersion,
                    IsSettingsWinner: planned.Role == SetupRole.Full && !planned.OtherVersion && group.Winner.BulkId() == planned.Setup.BulkId(),
                    planned.Brings, CaptionOf(planned, group.Version)));
            }
        }
        return list;
    }

    /// <summary>The single (one setup into one version) import as the same kind of list: one mapping.</summary>
    public static ImportMapping From(ImportPlan plan) =>
        new(0, plan.TargetVersion, 0, plan.Source, plan.TargetVersion, SetupRole.Full, false, true, ImportCategories.All,
            CaptionOf(plan.Source, plan.TargetVersion, SetupRole.Full, false, plan.Ops, plan.Mods.Count(m => m.IsCopied)));

    /// <summary>The mapping a result's outcome belongs to: SetupOutcome i of group g is mapping (g, i), the order the engine applies.</summary>
    public static ImportMapping? Of(IReadOnlyList<ImportMapping> mappings, string groupVersion, int slot) =>
        mappings.FirstOrDefault(m => m.GroupVersion == groupVersion && m.SlotInGroup == slot);

    /// <summary>How many different source setups the copies come from (a settings donor in two versions is one).</summary>
    public static int DistinctSetups(IEnumerable<ImportMapping> mappings) =>
        mappings.Select(m => m.Setup.BulkId()).Distinct(StringComparer.OrdinalIgnoreCase).Count();

    /// <summary>
    /// Where the copies into one Origin version come from, with the exact source versions: "Lunar 1.8.9 and 1.21.1",
    /// "Prism 1.21.1, Lunar 1.8.9". One entry per launcher (tile order of first appearance), its versions newest first.
    /// </summary>
    public static string FromText(IEnumerable<ImportMapping> mappings)
    {
        var parts = new List<string>();
        foreach (var byLauncher in mappings.GroupBy(m => m.Setup.Kind == SourceKind.Folder ? m.Setup.Name : m.Setup.SourceLabel))
        {
            var versions = byLauncher.Select(m => m.SourceVersion).Distinct(StringComparer.Ordinal).ToList();
            versions.Sort(BulkRanking.NewestFirst);
            parts.Add($"{byLauncher.Key} {(versions.Count == 1 ? versions[0] : string.Join(", ", versions.Take(versions.Count - 1)) + " and " + versions[^1])}");
        }
        return string.Join(", ", parts);
    }

    // ---- the words ----

    /// <summary>What a copy brings, from the plan's own steps: "Settings, 6 mods, packs, servers". Plain, no full stop.</summary>
    public static string CaptionOf(PlannedSetup planned, string targetVersion) =>
        CaptionOf(planned.Setup, targetVersion, planned.Role, planned.OtherVersion, planned.Plan.Ops, planned.Plan.Mods.Count(m => m.IsCopied));

    private static string CaptionOf(DetectedInstance setup, string targetVersion, SetupRole role, bool otherVersion,
        IReadOnlyList<PlannedOp> ops, int mods)
    {
        var present = new HashSet<ImportCategories>();
        foreach (var op in ops)
        {
            if (op.Fallback) continue;
            var category = CategoryFilter.Of(op.Item, op.Label);
            if (category != ImportCategories.None) present.Add(category);
        }
        if (mods > 0) present.Add(ImportCategories.Mods);

        var parts = new List<string>();
        if (present.Contains(ImportCategories.Settings)) parts.Add("settings");
        if (mods > 0) parts.Add(mods == 1 ? "1 mod" : $"{mods} mods");
        else if (present.Contains(ImportCategories.Mods)) parts.Add("mod settings");
        if (present.Contains(ImportCategories.ResourcePacks) || present.Contains(ImportCategories.ShaderPacks)) parts.Add("packs");
        if (present.Contains(ImportCategories.Servers)) parts.Add("servers");
        if (present.Contains(ImportCategories.Worlds)) parts.Add("worlds");
        if (present.Contains(ImportCategories.Screenshots)) parts.Add("screenshots");

        string text;
        if (parts.Count == 0) text = "Nothing new to copy";
        else
        {
            var joined = string.Join(", ", parts);
            text = char.ToUpperInvariant(joined[0]) + joined[1..];
            if (otherVersion) text += $" only, from its Minecraft {setup.McVersion}";
            else if (role == SetupRole.PacksOnly) text += " only";
        }
        // The setup's own name, unless it already says the version (a Lunar profile named "Lunar Client 1.8.9").
        var version = setup.McVersion;
        var named = !string.IsNullOrWhiteSpace(setup.Name) && (string.IsNullOrEmpty(version) || !setup.Name.Contains(version, StringComparison.Ordinal));
        return named && !otherVersion ? $"{setup.Name} - {text}" : text;
    }
}
