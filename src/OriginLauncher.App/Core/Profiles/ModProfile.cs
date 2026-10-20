using System.Text.Json.Serialization;

namespace OriginLauncher.App.Core.Profiles;

/// <summary>
/// One mod profile: a named set of the player's OWN mods for one Minecraft
/// version, optionally with a server to join in one click. Lives in
/// LauncherSettings.ModProfiles (so every change goes through
/// SettingsStore.Update); its jars live in
/// instances/&lt;version&gt;/profiles/&lt;Id&gt;/mods/ (OriginPaths.ProfileModsDir).
/// The folder is named by <see cref="Id"/>, never by the name, so renaming is free.
/// </summary>
public sealed class ModProfile
{
    /// <summary>Stable id: 32 lower-case hex characters (ProfileRules.NewId). Never changes.</summary>
    public string Id { get; set; } = "";

    public string Name { get; set; } = "";

    /// <summary>The Minecraft version it belongs to. Picked when the profile is created and fixed after.</summary>
    public string Version { get; set; } = "";

    /// <summary>Optional label for the server ("Hypixel"), shown on Home's join button.</summary>
    public string? ServerName { get; set; }

    /// <summary>Optional "host" / "host:port" the Home join button launches straight into.</summary>
    public string? ServerAddress { get; set; }

    /// <summary>
    /// The emblem colour the player picked: one of ProfileLook's tint names ("Slate"...).
    /// Null (profiles made before 2026-10-19, imports) = a colour picked from the id.
    /// </summary>
    public string? Tint { get; set; }

    /// <summary>The emblem's icon (UI/ProfileLook.EmblemIcons); null or unknown shows the
    /// name's first letter instead.</summary>
    public string? Icon { get; set; }

    [JsonIgnore]
    public bool HasServer => !string.IsNullOrWhiteSpace(ServerAddress);
}
