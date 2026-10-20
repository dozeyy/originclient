namespace OriginLauncher.App.Core.Mods.Icons;

/// <summary>
/// Where the Mods page gets icons from: ONLY the mod's own jar (JarIconReader). No network, no
/// download, no disk cache; a jar without an icon gets the generated tinted icon.
/// This holder only carries the render-mode override.
/// </summary>
public static class ModIconSource
{
    // Render mode (UI/RenderShots.cs): in-memory icons for the made-up rows (they have no jar on disk).
    public static IReadOnlyDictionary<string, byte[]>? DemoIcons { get; set; }
}
