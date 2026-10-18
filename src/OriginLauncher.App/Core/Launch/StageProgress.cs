namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// A loading-scene stage line split into what the bar can honestly show.
/// Install steps report "Downloading libraries (12/340)" (InstallProgressThrottle);
/// that count is real, so the bar fills to it. Every other stage ("Signing in",
/// "Starting Minecraft") has no measurable progress, so <see cref="Fraction"/> is
/// null and the bar sweeps instead of inventing a percentage.
/// </summary>
public readonly record struct StageProgress(string Label, double? Fraction)
{
    public static StageProgress Parse(string? text)
    {
        var line = (text ?? "").Trim();
        // "label (done/total)" — only the trailing counter, nothing else in the line.
        if (line.EndsWith(')'))
        {
            var open = line.LastIndexOf('(');
            var slash = line.LastIndexOf('/');
            if (open > 0 && slash > open
                && int.TryParse(line.AsSpan(open + 1, slash - open - 1), out var done)
                && int.TryParse(line.AsSpan(slash + 1, line.Length - slash - 2), out var total)
                && total > 0 && done >= 0)
            {
                var label = line[..open].TrimEnd();
                return new StageProgress(label, Math.Min(1.0, (double)done / total));
            }
        }
        return new StageProgress(line, null);
    }
}
