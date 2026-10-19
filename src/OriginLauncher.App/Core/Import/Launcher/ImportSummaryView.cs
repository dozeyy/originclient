namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>One section of the finished import's summary, ready to show: its title and its lines.</summary>
public sealed record SummaryBlock(string Title, SummarySection? Section, IReadOnlyList<string> Lines);

/// <summary>The Done page's content: the outcome sentence (rollbacks) and the sections.</summary>
public sealed record SummaryView(string? Outcome, IReadOnlyList<SummaryBlock> Blocks);

// Reads the plain-language summary.txt text (ImportSummary.Render) back into sections for the Done
// page, so the panel shows exactly what the file says - including the lines a step added while it ran,
// which only exist in the text. Display only; an unknown layout simply shows no sections.
public static class ImportSummaryView
{
    private static readonly (string Title, SummarySection? Section)[] Titles =
    {
        ("Imported", SummarySection.Imported),
        ("Reused game files", SummarySection.ReusedGameFiles),
        ("Reused caches", SummarySection.ReusedCaches),
        ("Needs attention", SummarySection.NeedsAttention),
        ("Not imported", SummarySection.NotImported),
        ("Settings files", null),
    };

    public static SummaryView Parse(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return new SummaryView(null, Array.Empty<SummaryBlock>());
        var lines = text.Replace("\r\n", "\n").Split('\n');

        // The header (before the first blank line): "Cancelled: nothing was changed." is its outcome line.
        string? outcome = null;
        var i = 0;
        for (; i < lines.Length && lines[i].Length > 0; i++)
        {
            var l = lines[i];
            if (l.StartsWith("Cancelled", StringComparison.Ordinal) || l.StartsWith("Failed", StringComparison.Ordinal))
                outcome = l;
        }

        var blocks = new List<SummaryBlock>();
        SummaryBlock? open = null;
        List<string>? items = null;
        void Close()
        {
            if (open != null && items != null) blocks.Add(open with { Lines = items });
            open = null;
            items = null;
        }

        for (; i < lines.Length; i++)
        {
            var l = lines[i];
            if (l.Length == 0) { Close(); continue; }
            if (l[0] != ' ')
            {
                Close();
                var match = Titles.FirstOrDefault(t => t.Title == l);
                if (match.Title != null)
                {
                    open = new SummaryBlock(match.Title, match.Section, Array.Empty<string>());
                    items = new List<string>();
                }
                continue;
            }
            var text2 = l.Trim();
            if (items == null || text2 == "(none)") continue;
            items.Add(text2.StartsWith("- ", StringComparison.Ordinal) ? text2[2..] : text2);
        }
        Close();
        return new SummaryView(outcome, blocks);
    }
}
