using System.Globalization;
using System.Text;

namespace OriginLauncher.App.Core.Import;

// The plain-language result the player reads (summary.txt, and the panel's
// last page). Five sections, always in this order, each with a reason where one
// helps: Imported, Reused game files, Reused caches, Needs attention, Not imported.
// Names and reasons only — no file contents, no paths into the player's secrets.
public static class ImportSummary
{
    /// <summary>The name of a line that counts several ops: "1 screenshot", "40 screenshots".</summary>
    public static string CountName(PlanItemKind kind, int count) => kind switch
    {
        PlanItemKind.Screenshot => count == 1 ? "1 screenshot" : $"{count} screenshots",
        _ => count == 1 ? "1 file" : $"{count} files",
    };

    /// <summary>
    /// Lines that count several ops (<see cref="PlannedOp.CountedLine"/>), after apply left some of those ops out:
    /// each such line goes into <paramref name="dropped"/>, and when part of it still came over a line with the lower
    /// count and size is returned in its place. "3 screenshots" with one unreadable becomes "2 screenshots".
    /// </summary>
    public static List<SummaryLine> Recount(IEnumerable<PlannedOp> ops, IReadOnlyCollection<PlannedOp> leftOut, ISet<SummaryLine> dropped)
    {
        var replacements = new List<SummaryLine>();
        if (leftOut.Count == 0) return replacements;
        var all = ops.ToList();
        foreach (var group in leftOut.Where(o => o.CountedLine && o.PlannedLine != null)
                     .GroupBy(o => o.PlannedLine!, ReferenceEqualityComparer.Instance))
        {
            var line = (SummaryLine)group.Key!;
            var left = group.ToList();
            var remaining = all.Count(o => !o.Fallback && ReferenceEquals(o.PlannedLine, line)) - left.Count;
            dropped.Add(line);
            if (remaining > 0)
                replacements.Add(line with { Name = CountName(line.Kind, remaining), Bytes = Math.Max(0, line.Bytes - left.Sum(o => o.Bytes)) });
        }
        return replacements;
    }

    /// <param name="dropped">Plan lines apply proved untrue (an item left out because its source couldn't be read), by reference.</param>
    public static string Render(ImportPlan plan, IReadOnlyList<SummaryLine>? extraLines = null, string? outcome = null,
        IReadOnlySet<SummaryLine>? dropped = null)
    {
        var lines = plan.Lines.Where(l => dropped == null || !dropped.Contains(l))
            .Concat(extraLines ?? Array.Empty<SummaryLine>()).ToList();
        var sb = new StringBuilder();
        sb.AppendLine("Origin import");
        sb.AppendLine($"From: {plan.Source.SourceLabel}: {plan.Source.Name}"
            + (plan.Source.McVersion == null ? "" : $" (Minecraft {plan.Source.McVersion}"
                + (plan.Source.Loader is LoaderKind.None or LoaderKind.Unknown ? ")" : $", {plan.Source.Loader}{(plan.Source.Confidence == Confidence.Inferred ? ", guessed" : "")})")));
        sb.AppendLine($"Into: Minecraft {plan.TargetVersion}");
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
