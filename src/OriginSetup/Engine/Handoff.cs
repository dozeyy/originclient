namespace OriginSetup.Engine;

// Writes the Customize-page choices for the launcher to apply on its next
// start (read by the launcher's Core/Install/SetupChoices.cs, which applies
// them through SettingsStore.Update — the only allowed writer of
// settings.json). Only fields the player actually set are written. The file
// name must match SetupChoices.FileName; tools/release/check_consistency.py
// fails CI if they drift (that file can't be linked here: it uses
// System.Text.Json, which .NET Framework lacks).
public static class Handoff
{
    public static string DataRoot =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OriginLauncher");

    public const string FileName = "setup-choices.json";

    public static string FilePath => Path.Combine(DataRoot, FileName);

    public static void Write(int? ramMb, string? defaultVersion, bool? settingsSync = null)
    {
        var json = Compose(ramMb, defaultVersion, settingsSync);
        if (json == null) return;
        Directory.CreateDirectory(DataRoot);
        File.WriteAllText(FilePath, json);
    }

    /// <summary>
    /// The file's text, or null when the player set nothing. settingsSync is
    /// Settings Sync on/off from the Customize page (launcher:
    /// LauncherSettings.SettingsSyncEnabled); null = left as it was, so the
    /// field is omitted and an old launcher or an old file is unaffected.
    /// </summary>
    public static string? Compose(int? ramMb, string? defaultVersion, bool? settingsSync)
    {
        var fields = new List<string>();
        if (ramMb is int ram) fields.Add($"\"RamMb\": {ram}");
        if (defaultVersion != null) fields.Add($"\"SelectedVersion\": \"{Escape(defaultVersion)}\"");
        if (settingsSync is bool sync) fields.Add($"\"SettingsSyncEnabled\": {(sync ? "true" : "false")}");
        return fields.Count == 0 ? null : "{ " + string.Join(", ", fields) + " }";
    }

    /// <summary>
    /// Whether an existing install already has Settings Sync on, so an update
    /// over it opens the Customize page showing the real state. Read-only; a
    /// missing or unreadable file means off (the launcher's default).
    /// </summary>
    public static bool IsSettingsSyncOn(string settingsPath)
    {
        try
        {
            return File.Exists(settingsPath) &&
                   System.Text.RegularExpressions.Regex.IsMatch(File.ReadAllText(settingsPath),
                       "\"SettingsSyncEnabled\"\\s*:\\s*true");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    public static string SettingsPath => Path.Combine(DataRoot, "settings.json");

    private static string Escape(string s) => s.Replace("\\", "\\\\").Replace("\"", "\\\"");
}
