using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;

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
    /// LauncherSettings.SyncSettingsAcrossVersions); null = left as it was, so the
    /// field is omitted and an old launcher or an old file is unaffected.
    /// </summary>
    public static string? Compose(int? ramMb, string? defaultVersion, bool? settingsSync)
    {
        var fields = new List<string>();
        if (ramMb is int ram) fields.Add($"\"RamMb\": {ram}");
        if (defaultVersion != null) fields.Add($"\"SelectedVersion\": \"{Escape(defaultVersion)}\"");
        if (settingsSync is bool sync) fields.Add($"\"SyncSettingsAcrossVersions\": {(sync ? "true" : "false")}");
        return fields.Count == 0 ? null : "{ " + string.Join(", ", fields) + " }";
    }

    /// <summary>
    /// Whether an existing install already has Settings Sync on, so an update
    /// over it opens the Customize page showing the real state. Read-only and
    /// never throws: a missing, unreadable or malformed file, or a
    /// SyncSettingsAcrossVersions that is not a top-level JSON boolean, means
    /// on (the launcher's default, since 2026-10-18). Parsed with the same
    /// DataContractJsonSerializer the release feed uses (System.Text.Json is not in .NET Framework 4.7.2), so a
    /// string that merely contains the key text can't be mistaken for it.
    /// </summary>
    public static bool IsSettingsSyncOn(string settingsPath)
    {
        try
        {
            if (!File.Exists(settingsPath)) return true;
            using var stream = File.OpenRead(settingsPath);
            var read = new DataContractJsonSerializer(typeof(SettingsProbe)).ReadObject(stream) as SettingsProbe;
            // Typed as object so "false" and 0 stay a string / number: only a real JSON false turns it off.
            return read?.SyncSettingsAcrossVersions is not bool on || on;
        }
        catch (Exception)
        {
            // Read-only probe: whatever goes wrong (IO, access, bad JSON, a
            // wrong-typed value), the answer is the launcher's default, on.
            return true;
        }
    }

    [DataContract]
    private sealed class SettingsProbe
    {
        [DataMember(Name = "SyncSettingsAcrossVersions")] public object? SyncSettingsAcrossVersions { get; set; }
    }

    public static string SettingsPath => Path.Combine(DataRoot, "settings.json");

    private static string Escape(string s) => s.Replace("\\", "\\\\").Replace("\"", "\\\"");
}
