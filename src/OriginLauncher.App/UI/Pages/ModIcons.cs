using System.Windows.Media;
using OriginLauncher.App.Core.Mods;

namespace OriginLauncher.App.UI.Pages;

/// <summary>
/// The Origin icon set for the Mods page: one stroke glyph per well-known mod
/// (same drawings the mockup and the in-game mod menu use), matched by name so
/// a jar the player adds gets its icon too. Anything unknown keeps the jar glyph.
/// Geometries are 24-unit stroke paths; the page draws them with Icon.Base.
/// </summary>
internal static class ModIcons
{
    private static readonly Dictionary<string, Geometry> Cache = new();

    // keyword (in the lower-cased display name or file name) -> glyph key. First match wins.
    private static readonly (string Keyword, string Glyph)[] Map =
    {
        ("origin", "mark"),
        ("xaero", "map"), ("minimap", "map"), ("worldmap", "map"),
        ("litematica", "cube"), ("worldedit", "cube"), ("schematic", "cube"),
        ("appleskin", "drop"),
        ("sodium", "pulse"), ("ferrite", "pulse"), ("modernfix", "pulse"),
        ("iris", "aperture"), ("shader", "aperture"),
        ("lithium", "chip"),
        ("modmenu", "sliders"), ("mod menu", "sliders"),
        ("jei", "search"), ("just enough", "search"), ("emi", "search"), ("rei", "search"),
        ("zoom", "zoom"),
    };

    private static readonly Dictionary<string, string> Paths = new()
    {
        ["map"] = "M1,6 V22 L8,18 L16,22 L23,18 V2 L16,6 L8,2 Z M8,2 V18 M16,6 V22",
        ["cube"] = "M21,16 V8 A2,2 0 0 0 20,6.27 L13,2.27 A2,2 0 0 0 11,2.27 L4,6.27 A2,2 0 0 0 3,8 V16 A2,2 0 0 0 4,17.73 L11,21.73 A2,2 0 0 0 13,21.73 L20,17.73 A2,2 0 0 0 21,16 Z M3.27,6.96 L12,12.01 L20.73,6.96 M12,22.08 V12 M16.5,9.4 L7.5,4.21",
        ["drop"] = "M12,2.69 L17.66,8.35 A8,8 0 1 1 6.35,8.35 Z",
        ["pulse"] = "M22,12 H18 L15,21 L9,3 L6,12 H2",
        ["aperture"] = "M2,12 A10,10 0 1 0 22,12 A10,10 0 1 0 2,12 Z M14.31,8 L20.05,17.94 M9.69,8 L21.17,8 M7.38,12 L13.12,2.06 M9.69,16 L3.95,6.06 M14.31,16 L2.83,16 M16.62,12 L10.88,21.94",
        ["chip"] = "M6,4 H18 A2,2 0 0 1 20,6 V18 A2,2 0 0 1 18,20 H6 A2,2 0 0 1 4,18 V6 A2,2 0 0 1 6,4 Z M9,9 H15 V15 H9 Z M9,1 V4 M15,1 V4 M9,20 V23 M15,20 V23 M20,9 H23 M20,14 H23 M1,9 H4 M1,14 H4",
        ["sliders"] = "M4,6 H20 M4,12 H20 M4,18 H20 M6.5,6 A1.5,1.5 0 1 0 9.5,6 A1.5,1.5 0 1 0 6.5,6 Z M13.5,12 A1.5,1.5 0 1 0 16.5,12 A1.5,1.5 0 1 0 13.5,12 Z M8.5,18 A1.5,1.5 0 1 0 11.5,18 A1.5,1.5 0 1 0 8.5,18 Z",
        ["search"] = "M3,3 H10 V10 H3 Z M14,3 H21 V10 H14 Z M3,14 H10 V21 H3 Z M13.5,17 A3.5,3.5 0 1 0 20.5,17 A3.5,3.5 0 1 0 13.5,17 Z M20,20 L22,22",
        ["zoom"] = "M3,10 A7,7 0 1 0 17,10 A7,7 0 1 0 3,10 Z M15,15 L20,20",
    };

    /// <summary>The glyph for a mod, or null for the plain jar glyph.</summary>
    public static Geometry? For(ModEntry mod)
    {
        var hay = (mod.DisplayName + " " + mod.FileName).ToLowerInvariant();
        foreach (var (keyword, glyph) in Map)
            if (hay.Contains(keyword)) return Get(glyph);
        return null;
    }

    private static Geometry Get(string glyph)
    {
        if (Cache.TryGetValue(glyph, out var cached)) return cached;
        Geometry g;
        if (glyph == "mark")
        {
            // The Origin mark: three rings sharing one centre (0 / 60 / 120 degrees) and a dot.
            var group = new GeometryGroup();
            foreach (var angle in new[] { 0.0, 60.0, 120.0 })
                group.Children.Add(new EllipseGeometry(new System.Windows.Point(12, 12), 9, 3.8)
                {
                    Transform = new RotateTransform(angle, 12, 12),
                });
            group.Children.Add(new EllipseGeometry(new System.Windows.Point(12, 12), 1.2, 1.2));
            g = group;
        }
        else g = Geometry.Parse(Paths[glyph]);
        g.Freeze();
        Cache[glyph] = g;
        return g;
    }

    // One line under a built-in tile's name (the mockup's wording).
    public static string Blurb(ModEntry mod)
    {
        var name = mod.DisplayName.ToLowerInvariant();
        if (name.Contains("origin")) return "Menus, loading screens and HUD";
        if (name.Contains("sodium")) return "Rendering performance";
        if (name.Contains("iris")) return "Shader packs";
        if (name.Contains("lithium")) return "Game logic performance";
        if (name.Contains("mod menu") || name.Contains("modmenu")) return "Mod list and settings";
        if (name.Contains("just enough") || name.Contains("jei")) return "Recipes";
        return "Built in";
    }
}
