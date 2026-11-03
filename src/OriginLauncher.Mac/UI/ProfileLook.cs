using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using Path = Avalonia.Controls.Shapes.Path;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// Twin of the WPF UI/ProfileLook.cs (the same tints, icons, hashing and layers): how a mod profile looks, an emblem
/// tile in one of six tints with the name's first letter or a picked icon on it.
/// </summary>
public static class ProfileLook
{
    /// <summary>The picker's order; each name is a Theme/Colors.axaml "Tint.*" brush.</summary>
    public static readonly string[] TintNames = ["Slate", "Plum", "Umber", "Moss", "Wine", "Graphite"];

    /// <summary>The emblem picker's icons in order (ModProfile.Icon stores the name, so never rename one).</summary>
    public static readonly string[] EmblemIcons =
        ["Star", "Zap", "Map", "Camera", "Target", "Sun", "Pen", "User", "Box", "Crown", "Globe", "Server",
         "Sword", "Pickaxe", "Diamond", "Heart", "Creeper", "Potion", "Torch", "Chest"];

    public const double ToneOpacity = 0.26;
    public const double EmblemSize = 24;

    public static bool HasIcon(string? icon) => icon != null && Array.IndexOf(EmblemIcons, icon) >= 0;

    /// <summary>The icon drawn in <paramref name="brush"/>, or null when the profile shows its letter. Always 24 square.</summary>
    public static Control? EmblemGlyph(string? icon, IBrush brush)
    {
        if (!HasIcon(icon)) return null;
        var outline = Res.Geo("Icon.Emblem." + icon);
        var canvas = new Canvas
        {
            Width = EmblemSize,
            Height = EmblemSize,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            UseLayoutRounding = true,
            IsHitTestVisible = false,
        };
        // Layer 1: the tint inside the outline. Opacity on its own path keeps the ink brush shared.
        canvas.Children.Add(new Path { Data = outline, Fill = brush, Opacity = ToneOpacity });
        // Layer 2: the 2 px outline.
        canvas.Children.Add(new Path
        {
            Data = outline,
            Stroke = brush,
            StrokeThickness = 2,
            StrokeLineCap = PenLineCap.Round,
            StrokeJoin = PenLineJoin.Round,
        });
        // Layer 3: solid details.
        if (Avalonia.Application.Current!.TryGetResource("Icon.Emblem." + icon + ".Solid", Avalonia.Styling.ThemeVariant.Dark, out var s)
            && s is Geometry solid)
            canvas.Children.Add(new Path { Data = solid, Fill = brush });
        return canvas;
    }

    public static IBrush Tint(ModProfile profile) => TintBrush(TintName(profile));
    public static IBrush TintBrush(string tintName) => Res.Brush("Tint." + tintName);
    public static IBrush Ink(ModProfile profile) => InkBrush(TintName(profile));
    public static IBrush InkBrush(string tintName) => Res.Brush("TintInk." + tintName);

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
