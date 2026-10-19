namespace OriginLauncher.App.Core.Import.Launcher;

// The one-time "Bring over your Minecraft setups?" card (the first-run entry point). Pure rules: the glue runs
// the quick scan off the UI thread and asks these questions.
public static class ImportPrompt
{
    /// <summary>The card's title when more than one setup can come over (or when it is not worth naming a launcher).</summary>
    public const string Title = "Bring over your Minecraft setups?";

    public const string ImportLabel = "Import";
    public const string DismissLabel = "Not now";

    /// <summary>
    /// Show the card only when ALL hold: the player has never seen it (an existing player sees it once too: the
    /// flag starts false for everyone), the launcher was not opened straight into the import panel, someone can
    /// import (an account or offline test mode is selected: otherwise the import would be blocked on "Sign in
    /// first" and the one-time card would be wasted), and the scan found at least one QUALIFYING setup (see
    /// <see cref="Qualifying"/>): a machine whose setups are all on versions Origin does not have gets no card.
    /// </summary>
    public static bool ShouldShow(bool alreadySeen, bool openedWithImportFlag, bool canImport, int qualifyingSetups) =>
        !alreadySeen && !openedWithImportFlag && canImport && qualifyingSetups > 0;

    /// <summary>How long a completed check for other launchers counts: the card's scan runs at most once in this time.</summary>
    public static readonly TimeSpan RecheckAfter = TimeSpan.FromDays(7);

    /// <summary>
    /// Whether the first-run card's background scan should run now: never once the card was seen (or the drawer opened),
    /// never while nobody can import (the card would be wasted on "Sign in first"), and otherwise only when no check has
    /// completed in the last <see cref="RecheckAfter"/>. A stamp in the future (the clock was moved back) counts as stale.
    /// A qualifying setup that appears later is still offered, at the next check.
    /// </summary>
    public static bool ShouldScan(bool alreadySeen, bool canImport, DateTime? lastCheckedUtc, DateTime nowUtc)
    {
        if (alreadySeen || !canImport) return false;
        if (lastCheckedUtc is not { } last) return true;
        var age = nowUtc - last;
        return age < TimeSpan.Zero || age >= RecheckAfter;
    }

    /// <summary>The setups an import can actually take: their Minecraft version is exactly one Origin supports.</summary>
    public static IReadOnlyList<DetectedInstance> Qualifying(IEnumerable<DetectedInstance> instances, IReadOnlyCollection<string> supportedTargets) =>
        instances.Where(i => i.McVersion != null && supportedTargets.Contains(i.McVersion)).ToList();

    /// <summary>
    /// The card's title: "Bring over your Prism setup?" when exactly one setup qualifies (the launcher's short name, so
    /// the question stays one line in the card), otherwise "Bring over your Minecraft setups?". Pass the QUALIFYING
    /// setups. The one source of the card's words (the CI render asks it too).
    /// </summary>
    public static string Question(IReadOnlyCollection<DetectedInstance> qualifying) =>
        qualifying.Count == 1 ? $"Bring over your {ShortName(qualifying.First().Kind)} setup?" : Title;

    private static string ShortName(SourceKind kind) => kind switch
    {
        SourceKind.OfficialLauncher => "Minecraft launcher",
        SourceKind.Prism => "Prism",
        SourceKind.Modrinth => "Modrinth",
        SourceKind.CurseForge => "CurseForge",
        SourceKind.Lunar => "Lunar Client",
        SourceKind.Feather => "Feather Client",
        SourceKind.Badlion => "Badlion Client",
        SourceKind.LabyMod => "LabyMod",
        _ => "other",
    };
}
