namespace OriginLauncher.App.Core.Import.Launcher;

// The one-time "Bring over your mods and settings from <Launcher>?" card (the first-run entry
// point). Pure rules: the glue runs the quick scan off the UI thread and asks these two questions.
public static class ImportPrompt
{
    /// <summary>
    /// Show the card only when ALL hold: the player has never seen it, the launcher was not opened straight
    /// into the import panel, someone can import (an account or offline test mode is selected: otherwise
    /// the import would be blocked on "Sign in first" and the one-time card would be wasted), and the scan
    /// found at least one instance.
    /// </summary>
    public static bool ShouldShow(bool alreadySeen, bool openedWithImportFlag, bool canImport, int instancesFound) =>
        !alreadySeen && !openedWithImportFlag && canImport && instancesFound > 0;

    /// <summary>"Bring over your mods and settings from Prism Launcher?" - names the launcher, or says "your other launchers".</summary>
    public static string Question(IReadOnlyCollection<DetectedInstance> instances)
    {
        var names = instances.Select(i => Name(i.Kind)).Distinct(StringComparer.Ordinal).ToList();
        var from = names.Count switch { 0 => "another launcher", 1 => names[0], _ => "your other launchers" };
        return $"Bring over your mods and settings from {from}?";
    }

    private static string Name(SourceKind kind) => kind switch
    {
        SourceKind.OfficialLauncher => "the Minecraft launcher",
        SourceKind.Prism => "Prism Launcher",
        SourceKind.Modrinth => "the Modrinth App",
        SourceKind.CurseForge => "CurseForge",
        _ => "another launcher",
    };
}
