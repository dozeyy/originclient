using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>One launcher Home's bring-over button can take setups from: its short name and how many setups qualify.</summary>
public sealed record ImportSourceChoice(SourceKind Kind, string Name, int Setups);

// Home's one-click "Bring over your Lunar setup" button (Will, 2026-11-04: "a 1 click button on the main screen that goes
// away when someone says no or they go through it; if they only have 1 other client it auto goes thru that and transfers;
// if its multiple then give options"). Pure rules: the glue runs the quick scan off the UI thread and asks these; the
// transfer itself is HomeImport.RunAsync. The ONE entry point on Home (it replaced the first-run welcome card).
public static class ImportPrompt
{
    /// <summary>The button's words when setups from more than one launcher can come over.</summary>
    public const string ManyLabel = "Bring over your Minecraft setups";

    /// <summary>The choice's "every launcher at once" row.</summary>
    public const string AllLabel = "All of them";

    /// <summary>The choice's way to the full Import page (detailed control).</summary>
    public const string MoreLabel = "Choose what comes over";

    /// <summary>The close button's accessible name: saying no hides the button for good.</summary>
    public const string NoThanksLabel = "No thanks";

    /// <summary>
    /// Show the button only when ALL hold: the player has not answered it yet (No thanks, a finished transfer, or the
    /// Import page opened from anywhere: <paramref name="answered"/>, LauncherSettings.ImportPromptSeen), the launcher was
    /// not opened straight into the Import page, someone can import (an account or offline test mode is selected:
    /// otherwise the transfer would stop on "Sign in first"), and the scan found at least one QUALIFYING setup
    /// (<see cref="Qualifying"/>): a machine whose setups are all on versions Origin does not have gets no button.
    /// </summary>
    public static bool ShouldShow(bool answered, bool openedWithImportFlag, bool canImport, int qualifyingSetups) =>
        !answered && !openedWithImportFlag && canImport && qualifyingSetups > 0;

    /// <summary>How long a check that found NOTHING to offer counts: such a PC is looked at again only after this.</summary>
    public static readonly TimeSpan RecheckAfter = TimeSpan.FromDays(7);

    /// <summary>
    /// Whether the button's background scan should run now: never once the player answered, never while nobody can
    /// import, and otherwise unless a check found nothing in the last <see cref="RecheckAfter"/>. Only a check that found
    /// nothing is stamped (<see cref="StampCheck"/>), so an offer the player has not answered yet is looked for again at
    /// every start and the button comes back until they say no or bring it over. A stamp in the future (the clock moved
    /// back) counts as stale.
    /// </summary>
    public static bool ShouldScan(bool answered, bool canImport, DateTime? lastCheckedUtc, DateTime nowUtc)
    {
        if (answered || !canImport) return false;
        if (lastCheckedUtc is not { } last) return true;
        var age = nowUtc - last;
        return age < TimeSpan.Zero || age >= RecheckAfter;
    }

    /// <summary>
    /// Whether a completed check is stamped (LauncherSettings.ImportPromptCheckedUtc): only when it found nothing to offer,
    /// so a PC with no other launcher does not scan after every start, while a waiting offer is shown again next start.
    /// </summary>
    public static bool StampCheck(int qualifyingSetups) => qualifyingSetups == 0;

    /// <summary>The setups an import can actually take: their Minecraft version is exactly one Origin supports.</summary>
    public static IReadOnlyList<DetectedInstance> Qualifying(IEnumerable<DetectedInstance> instances, IReadOnlyCollection<string> supportedTargets) =>
        instances.Where(i => i.McVersion != null && supportedTargets.Contains(i.McVersion)).ToList();

    /// <summary>
    /// The launchers the qualifying setups come from, most setups first (then by name). Exactly one = the button's click
    /// transfers it at once; more = the click offers one row per launcher plus <see cref="AllLabel"/>. Lunar plays out of
    /// the normal .minecraft, which the scan already folds into the Lunar source (Sources/LunarFold.cs), so a Lunar
    /// player with the official launcher installed still counts as one source.
    /// </summary>
    public static IReadOnlyList<ImportSourceChoice> Sources(IEnumerable<DetectedInstance> qualifying) =>
        qualifying.GroupBy(s => s.Kind)
            .Select(g => new ImportSourceChoice(g.Key, ShortName(g.Key), g.Count()))
            .OrderByDescending(c => c.Setups)
            .ThenBy(c => c.Name, StringComparer.Ordinal)
            .ToList();

    /// <summary>
    /// The button's words: "Bring over your Lunar setup" when every qualifying setup comes from one launcher (its short
    /// name, so the button stays one line), otherwise <see cref="ManyLabel"/>. Pass the QUALIFYING setups. The one source
    /// of the button's words (the CI renders ask it too).
    /// </summary>
    public static string Label(IEnumerable<DetectedInstance> qualifying) =>
        Sources(qualifying) is { Count: 1 } one ? OneLabel(one[0].Name) : ManyLabel;

    /// <summary>"Bring over your Lunar setup".</summary>
    public static string OneLabel(string name) => $"Bring over your {name} setup";

    /// <summary>The launcher's short name, as the player calls it ("Lunar", not "Lunar Client").</summary>
    public static string ShortName(SourceKind kind) => kind switch
    {
        SourceKind.OfficialLauncher => "Minecraft Launcher",
        SourceKind.Prism => "Prism",
        SourceKind.Modrinth => "Modrinth",
        SourceKind.CurseForge => "CurseForge",
        SourceKind.Lunar => "Lunar",
        SourceKind.Feather => "Feather",
        SourceKind.Badlion => "Badlion",
        SourceKind.LabyMod => "LabyMod",
        _ => "folder",
    };

    /// <summary>What one click brings: one launcher's setups (the Import page's "Import everything"), or every launcher's (null).</summary>
    public static BulkSelection Selection(SourceKind? source) =>
        source is { } kind ? BulkSelection.OfLauncher(kind) : BulkSelection.All();

    /// <summary>
    /// The one-click defaults: everything that qualifies under the engine's own rules (worlds, screenshots, every pack,
    /// servers, settings, each setup's mods in its own profile) — the same options as the Import page's "Import everything".
    /// </summary>
    public static ImportOptions Options() => new()
    {
        CopyWorlds = true,
        CopyScreenshots = true,
        CopyUnusedPacks = true,
        LeaveOut = ImportCategories.None,
    };
}
