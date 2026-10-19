namespace OriginLauncher.App.Core.Import;

/// <summary>
/// Where each launcher keeps its instances on this machine. The pure engine
/// never looks these up itself (it asks the OS for no special folders): the glue passes
/// them in, so tests point them at temp folders. Null = that launcher is not
/// installed / not looked at.
/// </summary>
public sealed record SourceRoots
{
    /// <summary>The official launcher's data folder (.minecraft: launcher_profiles.json, versions/, ...).</summary>
    public string? OfficialMinecraftDir { get; init; }

    /// <summary>Prism Launcher's data folder (holds instances/).</summary>
    public string? PrismDataDir { get; init; }

    /// <summary>MultiMC's folder (holds instances/).</summary>
    public string? MultiMcDir { get; init; }

    /// <summary>The Modrinth app's data folder (holds profiles/).</summary>
    public string? ModrinthDir { get; init; }

    /// <summary>CurseForge's instances folder (one folder per instance).</summary>
    public string? CurseForgeDir { get; init; }
}
