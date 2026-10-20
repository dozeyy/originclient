using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.App.UI;

/// <summary>
/// How a mod profile looks (Poster): an emblem tile in one of six desaturated
/// tints with the name's first letter on it. The tint is the one the player picked
/// (ModProfile.Tint), else one picked from the profile's stable id so it never changes.
/// </summary>
public static class ProfileLook
{
    /// <summary>The picker's order; each name is a Theme/Colors.xaml "Tint.*" brush.</summary>
    public static readonly string[] TintNames = ["Slate", "Plum", "Umber", "Moss", "Wine", "Graphite"];

    /// <summary>The emblem picker's icons in order, after the first-letter tile. Each is a
    /// Theme/Icons.xaml geometry "Icon.Emblem.&lt;name&gt;" (plus an optional ".Solid" layer for
    /// details). The name is what ModProfile.Icon stores, so never rename one.</summary>
    public static readonly string[] EmblemIcons =
        ["Star", "Zap", "Map", "Camera", "Target", "Sun", "Pen", "User", "Box", "Crown", "Globe", "Server",
         "Sword", "Pickaxe", "Diamond", "Heart", "Creeper", "Potion", "Torch", "Chest"];

    /// <summary>How much of the ink shows as the tint inside each outline (0..1).</summary>
    public const double ToneOpacity = 0.26;

    /// <summary>Emblems are drawn on a 24 px grid and shown at exactly 24 px (the Origin mark's
    /// size), so 2 px strokes land on whole device pixels at 100% and 200%.</summary>
    public const double EmblemSize = 24;

    /// <summary>True when the profile shows an icon (a known one), false when it shows its letter.</summary>
    public static bool HasIcon(string? icon) => icon != null && Array.IndexOf(EmblemIcons, icon) >= 0;

    /// <summary>The icon drawn in <paramref name="brush"/>, or null when the profile shows its
    /// letter. Always <see cref="EmblemSize"/> square: <paramref name="size"/> is kept so callers
    /// don't change, but a scaled glyph would blur its strokes, so it is ignored.</summary>
    public static FrameworkElement? EmblemGlyph(string? icon, double size, Brush brush)
    {
        if (!HasIcon(icon)) return null;
        var outline = (Geometry)Application.Current.FindResource("Icon.Emblem." + icon);
        var canvas = new Canvas
        {
            Width = EmblemSize,
            Height = EmblemSize,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            SnapsToDevicePixels = true,
            UseLayoutRounding = true,
            IsHitTestVisible = false,
        };
        // Layer 1: the tint inside the outline. Opacity on its own path keeps the ink brush live.
        canvas.Children.Add(new Path { Data = outline, Fill = brush, Opacity = ToneOpacity });
        // Layer 2: the 2 px outline.
        canvas.Children.Add(new Path
        {
            Data = outline,
            Stroke = brush,
            StrokeThickness = 2,
            StrokeStartLineCap = PenLineCap.Round,
            StrokeEndLineCap = PenLineCap.Round,
            StrokeLineJoin = PenLineJoin.Round,
        });
        // Layer 3: solid details.
        if (Application.Current.TryFindResource("Icon.Emblem." + icon + ".Solid") is Geometry solid)
            canvas.Children.Add(new Path { Data = solid, Fill = brush });
        return canvas;
    }
    public static Brush Tint(ModProfile profile) => TintBrush(TintName(profile));

    public static Brush Tint(string? id) => TintBrush(HashedTint(id));

    public static Brush TintBrush(string tintName) => (Brush)Application.Current.FindResource("Tint." + tintName);

    /// <summary>The light ink drawn on a profile's tile (its glyph or letter), the tile's own hue.</summary>
    public static Brush Ink(ModProfile profile) => InkBrush(TintName(profile));

    public static Brush InkBrush(string tintName) => (Brush)Application.Current.FindResource("TintInk." + tintName);

    /// <summary>The profile's tint name: its own pick when it is a known one, else from its id.</summary>
    public static string TintName(ModProfile profile) =>
        profile.Tint is { } own && Array.IndexOf(TintNames, own) >= 0 ? own : HashedTint(profile.Id);

    private static string HashedTint(string? id)
    {
        uint h = 2166136261;
        foreach (var c in id ?? "") h = unchecked((h ^ c) * 16777619);
        return TintNames[h % (uint)TintNames.Length];
    }

    public static string Initial(string? name)
    {
        foreach (var c in name ?? "")
            if (char.IsLetterOrDigit(c)) return char.ToUpperInvariant(c).ToString();
        return "P";
    }
}
