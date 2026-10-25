using System.Text.Json;
using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Install;

// The parsed contents of setup-choices.json (see SetupChoices) and how they
// land in the launcher's settings. Pure data and rules — no file, no store —
// so the hand-off format is unit-tested against the installer's own writer
// (Tests: SetupHandoffTests). Every field is optional: the installer only
// writes what the player actually set, and a file from an older installer
// simply lacks the newer fields.
public sealed class SetupChoicesData
{
    public int? RamMb { get; set; }
    public string? SelectedVersion { get; set; }

    /// <summary>Settings Sync (Core/SettingsSync.cs) switched on/off on the Customize page; null = untouched. Same name as the setting it sets.</summary>
    public bool? SyncSettingsAcrossVersions { get; set; }

    /// <summary>Parses the file's text. Throws <see cref="JsonException"/> on malformed JSON.</summary>
    public static SetupChoicesData? Parse(string json) => JsonSerializer.Deserialize<SetupChoicesData>(json);

    /// <summary>
    /// Copies the choices the player made into <paramref name="settings"/>; a
    /// field the file lacks leaves that setting exactly as it was. Called from
    /// inside SettingsStore.Update, the only settings writer.
    /// </summary>
    public void ApplyTo(LauncherSettings settings, int maxRamMb, Func<string?, bool> isSupportedVersion)
    {
        if (RamMb is int ram)
        {
            // The installer only sends RamMb when the player moved its slider, so it is a Manual choice.
            Launch.RamPolicy.SetManual(
                settings, Math.Clamp(ram / RamAdvice.StepMb * RamAdvice.StepMb, RamAdvice.SliderMinimumMb, maxRamMb));
        }
        if (isSupportedVersion(SelectedVersion))
            settings.SelectedVersion = SelectedVersion;
        if (SyncSettingsAcrossVersions is bool sync)
            settings.SyncSettingsAcrossVersions = sync;
    }
}
