using System.Text;

namespace OriginLauncher.App.Core.Import.Bulk;

// What the player reads after "import everything": summary.txt and the panel's Done page, BOTH built from the one
// structured BulkImportResult (the text is the view model written out — nothing is ever parsed back out of text).
//   header   "Imported 5 setups into 3 Minecraft versions."
//   versions newest first: "Minecraft 1.21.1: settings from Prism: Survival", then each setup with what came over
//            (Imported / Reused / Needs attention / Not imported)
//   "Didn't come over": every setup that was left out, with why.
// Every scanned setup appears exactly once. Player words only: "setup" (never "instance"), "Files Minecraft needs
// (already on this PC)" (never "game files"), "Distant Horizons / Voxy terrain" (never "caches").

public sealed record BulkSetupView(
    string Label, string Version, SetupRole Role, SetupResultKind Kind, string Status,
    IReadOnlyList<string> Imported, IReadOnlyList<string> Reused, IReadOnlyList<string> NeedsAttention, IReadOnlyList<string> NotImported);

/// <param name="SettingsLine">"Minecraft 1.21.1: settings from Prism: Survival".</param>
/// <param name="Files">What the version reused from this PC (and what will download), one sentence each.</param>
public sealed record BulkVersionView(string Version, string SettingsLine, IReadOnlyList<string> Files, IReadOnlyList<BulkSetupView> Setups);

public sealed record BulkSkippedView(string Label, string Reason);

public sealed record BulkDoneView(
    string Header, string? Error, IReadOnlyList<string> Notes, IReadOnlyList<BulkVersionView> Versions, IReadOnlyList<BulkSkippedView> DidntComeOver);

public static class BulkSummary
{
    public const string FilesTitle = "Files Minecraft needs (already on this PC)";
    public const string TerrainTitle = "Distant Horizons / Voxy terrain";

    /// <summary>The Done page's data.</summary>
    public static BulkDoneView ToView(BulkImportResult result)
    {
        var versions = result.Versions.Select(VersionView).ToList();
        var skipped = result.Skipped.Count > 0
            ? result.Skipped.Select(k => new BulkSkippedView(SkippedLabel(k.Setup), k.Reason)).ToList()
            : result.DidntComeOver.Select(d => new BulkSkippedView(d.SetupLabel, d.Reason)).ToList();
        return new BulkDoneView(Header(result), result.Error, result.Notes, versions, skipped);
    }

    /// <summary>summary.txt: the view, written out.</summary>
    public static string Render(BulkImportResult result)
    {
        var view = ToView(result);
        var sb = new StringBuilder();
        sb.AppendLine("Origin import: everything");
        sb.AppendLine(view.Header);
        if (view.Error != null) sb.AppendLine(view.Error);
        sb.AppendLine();

        if (view.Notes.Count > 0)
        {
            sb.AppendLine("Needs attention");
            foreach (var note in view.Notes) sb.AppendLine("  - " + note);
            sb.AppendLine();
        }

        foreach (var version in view.Versions)
        {
            sb.AppendLine(version.SettingsLine);
            foreach (var line in version.Files) sb.AppendLine("  " + line);
            foreach (var setup in version.Setups)
            {
                sb.AppendLine($"  {setup.Label}: {setup.Status}");
                Block(sb, "Imported", setup.Imported);
                Block(sb, "Reused", setup.Reused);
                Block(sb, "Needs attention", setup.NeedsAttention);
                Block(sb, "Not imported", setup.NotImported);
            }
            sb.AppendLine();
        }

        sb.AppendLine("Didn't come over");
        if (view.DidntComeOver.Count == 0) sb.AppendLine("  (none)");
        foreach (var skipped in view.DidntComeOver) sb.AppendLine($"  - {skipped.Label}: {skipped.Reason}");
        return sb.ToString();
    }

    private static void Block(StringBuilder sb, string title, IReadOnlyList<string> items)
    {
        if (items.Count == 0) return;
        sb.AppendLine("    " + title);
        foreach (var item in items) sb.AppendLine("      - " + item);
    }

    private static string Header(BulkImportResult result)
    {
        if (result.State == ImportState.Committed)
        {
            var setups = result.Versions.SelectMany(v => v.Setups).Count(Came);
            var versions = result.Versions.Count(v => v.Setups.Any(Came));
            return $"Imported {setups} {(setups == 1 ? "setup" : "setups")} into {versions} Minecraft {(versions == 1 ? "version" : "versions")}.";
        }
        if (result.State == ImportState.Planned) return "Nothing was imported: nothing was changed.";
        return result.Cancelled ? "Cancelled: nothing was changed." : "The import didn't finish: nothing was changed.";
    }

    private static bool Came(SetupOutcome s) => s.Kind is SetupResultKind.Imported or SetupResultKind.ImportedPartly;

    private static BulkVersionView VersionView(VersionOutcome version)
    {
        var settings = version.SettingsFrom != null ? $"Minecraft {version.Version}: settings from {version.SettingsFrom}"
            : version.SettingsNote != null ? $"Minecraft {version.Version}: settings {version.SettingsNote}"
            : $"Minecraft {version.Version}: no settings came over";
        return new BulkVersionView(version.Version, settings, FilesLines(version),
            version.Setups.Select(s => SetupView(version.Version, s)).ToList());
    }

    // Game-file reuse in player words, from the structured stats (never the per-file lines).
    private static List<string> FilesLines(VersionOutcome version)
    {
        var lines = new List<string>();
        if (version.GameFiles is { } stats)
        {
            if (stats.MetadataUnavailable)
                lines.Add($"{FilesTitle}: Origin couldn't reach Mojang's version list, so nothing was reused. Minecraft downloads what it needs the first time you press Play.");
            else
            {
                if (stats.ReusedCount > 0)
                    lines.Add($"{FilesTitle}: {stats.ReusedCount} {(stats.ReusedCount == 1 ? "file" : "files")}, {ImportSummary.Megabytes(stats.ReusedBytes)}");
                if (stats.ToDownloadCount > 0)
                    lines.Add($"About {ImportSummary.Megabytes(stats.ToDownloadBytes)} will download the first time you press Play.");
                if (stats.JavaSkipped)
                    lines.Add("Java will download the first time you press Play.");
            }
        }
        // VersionOutcome.Lines carries the plan's non-file lines plus what happened while applying; a file line from
        // the apply means files that changed since the plan (they were left to download).
        var other = version.Lines ?? Array.Empty<SummaryLine>();
        if (other.Any(l => l.Kind == PlanItemKind.GameFile && l.Section == SummarySection.NotImported))
            lines.Add("Some files Minecraft needs changed while importing; they download the first time you press Play.");
        foreach (var line in other.Where(l => l.Kind != PlanItemKind.GameFile))
            lines.Add(Item(line));
        return lines;
    }

    private static BulkSetupView SetupView(string version, SetupOutcome outcome)
    {
        var lines = outcome.Lines ?? Array.Empty<SummaryLine>();
        List<string> Of(params SummarySection[] sections) =>
            lines.Where(l => sections.Contains(l.Section)).Select(Item).ToList();
        var status = outcome.Kind switch
        {
            SetupResultKind.Imported => outcome.Role == SetupRole.Full ? "imported, with its settings" : "imported (mods and packs)",
            SetupResultKind.ImportedPartly => "packs only",
            SetupResultKind.Skipped => "skipped" + (outcome.Message == null ? "" : $" ({outcome.Message.TrimEnd('.')})"),
            _ => "put back",
        };
        var reused = lines.Where(l => l.Section is SummarySection.ReusedCaches or SummarySection.ReusedGameFiles)
            .Select(l => l.Section == SummarySection.ReusedCaches ? $"{TerrainTitle}: {Item(l)}" : Item(l)).ToList();
        return new BulkSetupView(outcome.Setup.BulkLabel(), version, outcome.Role, outcome.Kind, status,
            Of(SummarySection.Imported), reused, Of(SummarySection.NeedsAttention), Of(SummarySection.NotImported));
    }

    private static string Item(SummaryLine line)
    {
        var size = line.Bytes > 0 ? $" [{ImportSummary.Megabytes(line.Bytes)}]" : "";
        return line.Reason == null ? line.Name + size : $"{line.Name}{size}: {line.Reason}";
    }

    private static string SkippedLabel(DetectedInstance setup) =>
        setup.BulkLabel() + (string.IsNullOrWhiteSpace(setup.McVersion) ? "" : $" (Minecraft {setup.McVersion})");
}
