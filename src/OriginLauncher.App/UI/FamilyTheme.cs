using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI;

/// <summary>
/// The version family on screen: its two-line label, its version range and its art key (ArtStage). Workbench
/// (docs/overhaul/DESIGN-SYSTEM.md 17.1) retired the per-family accent colour: the accent is the one emerald
/// (Brush.Accent in Theme/Colors.xaml) on every family, and colour lives in the five hues, so nothing here
/// publishes or tweens a brush any more.
/// </summary>
public static class FamilyTheme
{
    public sealed record Family(string Key, string Label, string Range, int Count);

    // Labels from the approved mockup (A2 Poster, FAM table). The range and count come from VersionCatalog, so
    // they follow whatever ships.
    private static readonly Dictionary<string, string> Labels = new()
    {
        ["26"] = "NEWEST",
        ["1.21"] = "TRICKY TRIALS",
        ["1.20"] = "TRAILS & TALES",
        ["1.19"] = "THE WILD UPDATE",
        ["1.18"] = "CAVES & CLIFFS II",
        ["1.17"] = "CAVES & CLIFFS I",
        ["1.16"] = "NETHER UPDATE",
        ["1.8"] = "CLASSIC PVP",
    };

    public static Family Current { get; private set; } = Of(VersionCatalog.DefaultVersion);

    /// <summary>Raised after the family changes (the art stage and Home follow it).</summary>
    public static event Action<Family, Family>? Changed;

    public static VersionGroup GroupOf(string? version) =>
        VersionCatalog.Groups.FirstOrDefault(g => version != null && g.Versions.Contains(version)) ?? VersionCatalog.Groups[0];

    public static Family Of(string? version)
    {
        var g = GroupOf(version);
        var label = Labels.TryGetValue(g.Key, out var l) ? l : g.Tagline.ToUpperInvariant();
        var range = g.Versions.Count > 1 ? $"{g.Versions[^1]} TO {g.Versions[0]}" : g.Versions[0];
        return new Family(g.Key, label, range, g.Versions.Count);
    }

    /// <summary>Make <paramref name="version"/>'s family the one on screen.</summary>
    public static void Apply(string? version, bool animate = true)
    {
        var next = Of(version);
        var prev = Current;
        Current = next;
        Changed?.Invoke(prev, next);
    }
}
