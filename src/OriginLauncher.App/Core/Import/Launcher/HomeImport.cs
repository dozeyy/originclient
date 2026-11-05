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
        var leftOut = LeftOutLine(Shown(result.Skipped), failed);
        if (SettingsWaiting(result) is { } settings) leftOut = leftOut == null ? settings : $"{leftOut} {settings}";
        return new HomeImportOutcome(true, line, leftOut);
    }

    // A version whose settings file couldn't be made yet (its game was never installed, so its data version is unknown:
    // 26.1.x before the first Play). The Import page's report says so; Home said nothing, and its offer never comes back
    // after a success, so the player never learned their keybinds stayed behind (bug run 11 PROF-12).
    private static string? SettingsWaiting(BulkImportResult result)
    {
        var versions = result.Versions
            .Where(v => AllLines(v).Any(l => l.Section == SummarySection.NeedsAttention
                && string.Equals(l.Reason, SettingsMigrator.OptionsNotYet(v.Version), StringComparison.Ordinal)))
            .Select(v => v.Version).Distinct(StringComparer.Ordinal).ToList();
        if (versions.Count == 0) return null;
        var which = versions.Count == 1 ? $"Minecraft {versions[0]}" : $"Minecraft {string.Join(", ", versions.Take(versions.Count - 1))} and {versions[^1]}";
        return $"Your settings for {which} come over once you've played it: press Play once, then import again from Profiles.";
    }

    private static IEnumerable<SummaryLine> AllLines(VersionOutcome version) =>
        (version.Lines ?? Array.Empty<SummaryLine>())
            .Concat(version.Setups.SelectMany(s => (s.Lines ?? Array.Empty<SummaryLine>()).Concat(s.LeftOut ?? Array.Empty<SummaryLine>())));

    /// <summary>
    /// Whether a plan may be copied from Home, or the outcome that stops it. A real blocker (no account, not enough disk
    /// space) is said as it is. When the plan simply has nothing to copy, Home names the setups that stayed behind and
    /// why ("Minecraft is running from this setup. Close it..."): the engine's "The list below says why" line points at
    /// the Import page's list, which Home doesn't have, so it is never Home's answer.
    /// </summary>
    public static HomeImportOutcome? BeforeCopy(BulkImportPlan plan)
    {
        var blocker = plan.Blocked.FirstOrDefault(b => !string.Equals(b, BulkRules.NoneCanComeOver, StringComparison.Ordinal));
        if (blocker != null) return Refused(HomeBlocker(blocker));
        if (plan.Groups.Count == 0 || plan.Blocked.Count > 0) return NothingToCopy(plan.Skipped);
        return null;
    }

    // The disk-space line's "leave worlds out" is something only the Import page can do: Home says where.
    private static string HomeBlocker(string blocker) =>
        blocker.EndsWith(BulkRules.LeaveWorldsOutHint, StringComparison.Ordinal)
            ? $"{blocker} {ImportPrompt.MoreLabel} lets you leave worlds out."
            : blocker;

    /// <summary>The plan found nothing to copy: one line, and which setups stayed behind.</summary>
    public static HomeImportOutcome NothingToCopy(IEnumerable<SkippedSetup> skipped) =>
        new(false, "Nothing here can come over yet.", LeftOutLine(Shown(skipped), Array.Empty<string>()));

    /// <summary>The run stopped before copying (a game starting, a setup in use): nothing was written.</summary>
    public static HomeImportOutcome Refused(string reason) => new(false, NothingChanged(reason));

    /// <summary>The copy threw (the engine had no result to give): what happened, and that Origin repairs it.</summary>
    public static HomeImportOutcome Crashed(string plainError) =>
        new(false, $"{Sentence(plainError)} If anything changed, Origin undoes it at the next start.");

    /// <summary>The account was switched while Origin was still checking: nothing was copied.</summary>
    public const string AccountChanged = "The account changed while Origin was checking. Try again.";

    /// <summary>
    /// The transfer may go on only when the account read at its start is the one the plan copies into and still the
    /// selected one: the scan's "already brought over" list and the plan's folders then belong to the same account.
    /// </summary>
    public static bool SameAccount(string atStart, string? planned, string? now) =>
        string.Equals(atStart, planned, StringComparison.Ordinal) && string.Equals(atStart, now, StringComparison.Ordinal);

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
    /// and 2 more." for several (the Import page's report has every reason). When several stayed behind for the same
    /// reason (the old launcher still open is the common one), that reason follows, so Home always says what fixes it.
    /// Null when everything came over.
    /// </summary>
    public static string? LeftOutLine(IReadOnlyList<SkippedSetup> skipped, IReadOnlyList<string> failed)
    {
        var names = skipped.Select(s => s.Setup.BulkLabel()).Concat(failed).Distinct(StringComparer.Ordinal).ToList();
        if (names.Count == 0) return null;
        if (names.Count == 1 && skipped.Count == 1) return $"Didn't come over: {names[0]}. {Sentence(skipped[0].Reason)}";
        var shown = names.Count <= 3 ? string.Join(", ", names) : $"{string.Join(", ", names.Take(2))} and {names.Count - 2} more";
        return SharedReason(skipped, failed) is { } reason ? $"Didn't come over: {shown}. {Sentence(reason)}" : $"Didn't come over: {shown}.";
    }

    // One reason for every setup left behind, or null when they differ (or one failed while copying).
    private static string? SharedReason(IReadOnlyList<SkippedSetup> skipped, IReadOnlyList<string> failed)
    {
        if (failed.Count > 0 || skipped.Count == 0) return null;
        var first = skipped[0].Reason;
        if (skipped.All(s => string.Equals(s.Reason, first, StringComparison.Ordinal))) return first;
        // "Origin's Minecraft 1.21.1 is still running" names each setup's own version: same fix, different words.
        if (skipped.All(s => s.Kind == SkipKind.TargetRunning)) return "Close Minecraft first: Origin's game for these versions is still running.";
        return null;
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
