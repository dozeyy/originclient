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

    public static void Write(int? ramMb, string? defaultVersion)
    {
        if (ramMb == null && defaultVersion == null) return;
        var fields = new List<string>();
        if (ramMb is int ram) fields.Add($"\"RamMb\": {ram}");
        if (defaultVersion != null) fields.Add($"\"SelectedVersion\": \"{Escape(defaultVersion)}\"");
        Directory.CreateDirectory(DataRoot);
        File.WriteAllText(FilePath, "{ " + string.Join(", ", fields) + " }");
    }

    private static string Escape(string s) => s.Replace("\\", "\\\\").Replace("\"", "\\\"");
}
