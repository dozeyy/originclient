using System.Globalization;
using System.Text;

namespace OriginLauncher.App.Core.Import;

// The plain-language result the player reads (summary.txt, and the panel's
// last page). Five sections, always in this order, each with a reason where one
// helps: Imported, Reused game files, Reused caches, Needs attention, Not imported.
// Names and reasons only — no file contents, no paths into the player's secrets.
public static class ImportSummary
{
    public static string Render(ImportPlan plan, IReadOnlyList<SummaryLine>? extraLines = null, string? outcome = null)
    {
        var lines = plan.Lines.Concat(extraLines ?? Array.Empty<SummaryLine>()).ToList();
        var sb = new StringBuilder();
        sb.AppendLine("Origin import");
        sb.AppendLine($"From: {plan.Source.SourceLabel}: {plan.Source.Name}"
            + (plan.Source.McVersion == null ? "" : $" (Minecraft {plan.Source.McVersion}"
                + (plan.Source.Loader is LoaderKind.None or LoaderKind.Unknown ? ")" : $", {plan.Source.Loader}{(plan.Source.Confidence == Confidence.Inferred ? ", guessed" : "")})")));
        sb.AppendLine($"Into: Minecraft {plan.TargetVersion}" + (plan.ProfileName == null ? "" : $", mod profile \"{plan.ProfileName}\""));
        if (outcome != null) sb.AppendLine(outcome);
        sb.AppendLine();

        Section(sb, "Imported", lines, SummarySection.Imported);

        sb.AppendLine("Reused game files");
        var reusedFiles = lines.Where(l => l.Section == SummarySection.ReusedGameFiles).ToList();
        sb.AppendLine(reusedFiles.Count == 0
            ? "  (none)"
            : $"  {reusedFiles.Count} {(reusedFiles.Count == 1 ? "file" : "files")}, {Megabytes(reusedFiles.Sum(l => l.Bytes))}");
        sb.AppendLine();

        Section(sb, "Reused caches", lines, SummarySection.ReusedCaches);
        Section(sb, "Needs attention", lines, SummarySection.NeedsAttention);
        Section(sb, "Not imported", lines, SummarySection.NotImported);

        sb.AppendLine("Settings files");
        sb.AppendLine($"  {plan.Created} new, {plan.Replaced} replaced (the old copies are saved in the import's backup folder), "
            + $"{plan.Kept} kept as they were, {plan.Unchanged} already the same");
        return sb.ToString();
    }

    private static void Section(StringBuilder sb, string title, List<SummaryLine> all, SummarySection section)
    {
        sb.AppendLine(title);
        var items = all.Where(l => l.Section == section).ToList();
        if (items.Count == 0)
        {
            sb.AppendLine("  (none)");
        }
        else
        {
            foreach (var item in items)
            {
                var size = item.Bytes > 0 ? $" [{Megabytes(item.Bytes)}]" : "";
                sb.AppendLine(item.Reason == null ? $"  - {item.Name}{size}" : $"  - {item.Name}{size}: {item.Reason}");
            }
        }
        sb.AppendLine();
    }

    public static string Megabytes(long bytes) =>
        (bytes / 1048576.0).ToString("0.#", CultureInfo.InvariantCulture) + " MB";
}
