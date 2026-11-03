using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Media;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Pages;

/// <summary>
/// The Mods page's icon set (port of the WPF UI/Pages/ModIcons.cs): one stroke glyph per well-known mod, matched
/// by name so a jar the player adds gets its icon too. Anything unknown keeps the plain jar glyph. Each glyph wears
/// one of the five fixed hues (tile = HueTile.*, ink = Hue.*, Theme/Colors.xaml); unknown mods stay neutral.
/// </summary>
public static class ModIcons
{
    // keyword (in the lower-cased display name or file name) -> glyph key. First match wins.
    private static readonly (string Keyword, string Glyph)[] Map =
    [
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
    ];

    // The same 24-unit drawings as the WPF launcher and the in-game mod menu.
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

    // glyph -> the hue name (Colors.xaml "Hue.<name>" ink, "HueTile.<name>" wash).
    private static readonly Dictionary<string, string> Hues = new()
    {
        ["map"] = "Emerald", ["cube"] = "Sky", ["drop"] = "Coral", ["pulse"] = "Gold", ["aperture"] = "Lilac",
        ["chip"] = "Sky", ["sliders"] = "Lilac", ["search"] = "Gold", ["zoom"] = "Emerald",
    };

    private static readonly Dictionary<string, Geometry> Parsed = new();

    private static string? GlyphKey(ModEntry mod)
    {
        var hay = (mod.DisplayName + " " + mod.FileName).ToLowerInvariant();
        foreach (var (keyword, glyph) in Map)
            if (hay.Contains(keyword)) return glyph;
        return null;
    }

    /// <summary>The mod's hue name ("Emerald"...), or null (unknown mods stay neutral).</summary>
    public static string? Hue(ModEntry mod) => GlyphKey(mod) is { } g && Hues.TryGetValue(g, out var h) ? h : null;

    /// <summary>True for Origin Client's own entry, whose icon is the official logo image, never a drawing.</summary>
    public static bool IsOrigin(ModEntry mod) =>
        (mod.DisplayName + " " + mod.FileName).Contains("origin", StringComparison.OrdinalIgnoreCase);

    /// <summary>The glyph for a mod (24-unit stroke path), or null for the plain jar glyph (and Origin itself).</summary>
    public static Geometry? For(ModEntry mod)
    {
        if (GlyphKey(mod) is not { } glyph || glyph == "mark") return null;
        if (Parsed.TryGetValue(glyph, out var g)) return g;
        g = Geometry.Parse(Paths[glyph]);
        Parsed[glyph] = g;
        return g;
    }

    /// <summary>One line under a built-in tile's name (the mockup's wording).</summary>
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

/// <summary>
/// A 24-unit drawing shown at Size (scaled; the stroke is not): the Mods page's per-mod glyphs and the profile
/// emblems. Layers, bottom up: an optional tone (the outline filled at ToneOpacity), the round-capped outline at
/// StrokeWidth, and an optional Solid geometry filled in the ink. With no Brush it follows the text colour.
/// </summary>
public sealed class PathGlyph : Control
{
    public static readonly StyledProperty<IBrush?> BrushProperty = AvaloniaProperty.Register<PathGlyph, IBrush?>(nameof(Brush));

    static PathGlyph() => AffectsRender<PathGlyph>(BrushProperty, TextElement.ForegroundProperty);

    private readonly Geometry _outline;
    private readonly Geometry? _solid;
    private readonly double _size, _stroke, _tone;

    public PathGlyph(Geometry outline, double size, double strokeWidth = Sizes.IconStroke, double tone = 0, Geometry? solid = null)
    {
        _size = size;
        _stroke = strokeWidth;
        _tone = tone;
        var k = size / 24.0;
        _outline = Scaled(outline, k);
        _solid = solid == null ? null : Scaled(solid, k);
        Width = size;
        Height = size;
        IsHitTestVisible = false;
        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
    }

    public IBrush? Brush { get => GetValue(BrushProperty); set => SetValue(BrushProperty, value); }

    private static Geometry Scaled(Geometry g, double k)
    {
        var c = g.Clone();
        if (k != 1) c.Transform = new ScaleTransform(k, k);
        return c;
    }

    public override void Render(DrawingContext context)
    {
        var brush = Brush ?? TextElement.GetForeground(this) ?? Palette.TextDim;
        using (context.PushTransform(Matrix.CreateTranslation((Bounds.Width - _size) / 2, (Bounds.Height - _size) / 2)))
        {
            if (_tone > 0)
                using (context.PushOpacity(_tone))
                    context.DrawGeometry(brush, null, _outline);
            context.DrawGeometry(null, new Pen(brush, _stroke, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round), _outline);
            if (_solid != null) context.DrawGeometry(brush, null, _solid);
        }
    }
}
