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

    /// <summary>The setups an import can actually take: their Minecraft version is exactly one Origin supports.</summary>
    public static IReadOnlyList<DetectedInstance> Qualifying(IEnumerable<DetectedInstance> instances, IReadOnlyCollection<string> supportedTargets) =>
        instances.Where(i => i.McVersion != null && supportedTargets.Contains(i.McVersion)).ToList();

    /// <summary>
    /// The card's title: "Bring over your setup from Prism Launcher?" when exactly one setup qualifies,
    /// otherwise "Bring over your Minecraft setups?". Pass the QUALIFYING setups.
    /// </summary>
    public static string Question(IReadOnlyCollection<DetectedInstance> qualifying) =>
        qualifying.Count == 1 ? $"Bring over your setup from {Name(qualifying.First().Kind)}?" : Title;

    private static string Name(SourceKind kind) => kind switch
    {
        SourceKind.OfficialLauncher => "the Minecraft launcher",
        SourceKind.Prism => "Prism Launcher",
        SourceKind.Modrinth => "the Modrinth App",
        SourceKind.CurseForge => "CurseForge",
        _ => "another launcher",
    };
}
