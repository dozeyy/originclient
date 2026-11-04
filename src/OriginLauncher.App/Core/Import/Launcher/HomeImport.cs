using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>Where a one-click transfer is: still working out what comes over, or copying (with a real fraction).</summary>
public enum HomeImportStep { Checking, Copying }

public sealed record HomeImportProgress(HomeImportStep Step, double Fraction);

/// <summary>
/// How a one-click transfer ended: <see cref="Line"/> is the one plain sentence Home shows ("Your Lunar setup is in
/// Origin."), <see cref="LeftOut"/> what didn't come over (null when everything did).
/// </summary>
public sealed record HomeImportOutcome(bool Succeeded, string Line, string? LeftOut = null)
{
    public bool Cancelled { get; init; }
}

/// <summary>
/// The words of Home's one-click transfer (ImportPrompt; the run itself is HomeImportRunner). Pure, so the lines every
/// launcher's Home shows (Windows, Mac, Linux) are written and tested once.
/// </summary>
public static class HomeImport
{
    /// <summary>The engine's result as Home's one line (and what didn't come over).</summary>
    public static HomeImportOutcome Outcome(BulkImportResult result, string? sourceName)
    {
        if (result.Cancelled) return Stopped;
        if (!result.Succeeded) return new HomeImportOutcome(false, NothingChanged(result.Error ?? "The import couldn't finish"));
        var failed = result.Versions.SelectMany(v => v.Setups)
            .Where(s => s.Kind == SetupResultKind.Failed)
            .Select(s => s.Setup.BulkLabel())
            .ToList();
        var line = sourceName != null ? $"Your {sourceName} setup is in Origin." : "Your setups are in Origin.";
        return new HomeImportOutcome(true, line, LeftOutLine(Shown(result.Skipped), failed));
    }

    /// <summary>The plan found nothing to copy: one line, and which setups stayed behind.</summary>
    public static HomeImportOutcome NothingToCopy(IEnumerable<SkippedSetup> skipped) =>
        new(false, "Nothing here can come over yet.", LeftOutLine(Shown(skipped), Array.Empty<string>()));

    /// <summary>The run stopped before copying (a game starting, a setup in use): nothing was written.</summary>
    public static HomeImportOutcome Refused(string reason) => new(false, NothingChanged(reason));

    /// <summary>The copy threw (the engine had no result to give): what happened, and that Origin repairs it.</summary>
    public static HomeImportOutcome Crashed(string plainError) =>
        new(false, $"{Sentence(plainError)} If anything changed, Origin undoes it at the next start.");

    /// <summary>The player pressed Stop: the engine put everything back.</summary>
    public static readonly HomeImportOutcome Stopped = new(false, "Stopped. Nothing changed.") { Cancelled = true };

    /// <summary>The line while it runs: "Checking what can come over..." then "Bringing over your Lunar setup, 42 %".</summary>
    public static string RunningLine(string? sourceName, HomeImportProgress? progress)
    {
        if (progress is not { Step: HomeImportStep.Copying } copying) return "Checking what can come over...";
        var what = sourceName != null ? $"your {sourceName} setup" : "your setups";
        return $"Bringing over {what}, {Percent(copying.Fraction)} %";
    }

    public static int Percent(double fraction) => (int)Math.Round(Math.Clamp(fraction, 0, 1) * 100);

    /// <summary>
    /// "Didn't come over: Lunar: 1.8.9. Origin can't run this version." for one skipped setup, "Didn't come over: A, B
    /// and 2 more." for several (the Import page's report has every reason). Null when everything came over.
    /// </summary>
    public static string? LeftOutLine(IReadOnlyList<SkippedSetup> skipped, IReadOnlyList<string> failed)
    {
        var names = skipped.Select(s => s.Setup.BulkLabel()).Concat(failed).Distinct(StringComparer.Ordinal).ToList();
        if (names.Count == 0) return null;
        if (names.Count == 1 && skipped.Count == 1) return $"Didn't come over: {names[0]}. {Sentence(skipped[0].Reason)}";
        var shown = names.Count <= 3 ? string.Join(", ", names) : $"{string.Join(", ", names.Take(2))} and {names.Count - 2} more";
        return $"Didn't come over: {shown}.";
    }

    /// <summary>The copy's real fraction: bytes when the engine counts them, else setups done plus the current one's step.</summary>
    public static double Fraction(BulkProgress b)
    {
        if (b.BytesTotal > 0) return Math.Clamp(b.BytesDone / (double)b.BytesTotal, 0, 1);
        var inner = b.Inner.Total > 0 ? Math.Clamp(b.Inner.Done / (double)b.Inner.Total, 0, 1) : 0;
        return Math.Clamp((b.SetupIndex + inner) / Math.Max(1, b.SetupCount), 0, 1);
    }

    // Setups that were never part of this transfer (another launcher's, when one was picked) are not "left out".
    private static IReadOnlyList<SkippedSetup> Shown(IEnumerable<SkippedSetup> skipped) =>
        skipped.Where(s => s.Kind != SkipKind.NotSelected).ToList();

    private static string NothingChanged(string reason) => $"Nothing changed. {Sentence(reason)}";

    // The engine's reasons are sentences that start with a name ("Origin can't run 1.21.5"), so they are never
    // lower-cased into a longer sentence: they follow as their own, with one full stop.
    private static string Sentence(string text)
    {
        var trimmed = text.Trim();
        return trimmed.EndsWith('.') || trimmed.EndsWith('!') || trimmed.EndsWith('?') ? trimmed : trimmed + ".";
    }
}
