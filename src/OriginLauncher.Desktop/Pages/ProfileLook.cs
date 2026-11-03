using Avalonia.Controls;
using Avalonia.Media;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Pages;

/// <summary>
/// How a mod profile looks (port of the WPF UI/ProfileLook.cs): an emblem tile in one of six tints with the
/// profile's icon or first letter on it in the tint's ink. The tint is the player's pick (ModProfile.Tint), else
/// one picked from the profile's stable id so it never changes. The tints are Colors.xaml's own Tint.* brushes:
/// each is a HueTile.* wash with its Hue.* ink (Graphite: CardAlt with TextDim).
/// </summary>
public static class ProfileLook
{
    /// <summary>The picker's order. The name is what ModProfile.Tint stores, so never rename one.</summary>
    public static readonly string[] TintNames = ["Slate", "Plum", "Umber", "Moss", "Wine", "Graphite"];

    /// <summary>The emblem picker's icons in order, after the first-letter tile: Icons "Emblem.&lt;name&gt;" (plus an
    /// optional ".Solid" layer). The name is what ModProfile.Icon stores, so never rename one.</summary>
    public static readonly string[] EmblemIcons =
        ["Star", "Zap", "Map", "Camera", "Target", "Sun", "Pen", "User", "Box", "Crown", "Globe", "Server",
         "Sword", "Pickaxe", "Diamond", "Heart", "Creeper", "Potion", "Torch", "Chest"];

    /// <summary>How much of the ink shows as the tone inside each outline (0..1).</summary>
    public const double ToneOpacity = 0.26;

    /// <summary>Emblems are drawn on a 24 px grid and shown at exactly 24 px, so 2 px strokes land on whole pixels.</summary>
    public const double EmblemSize = 24;

    public static bool HasIcon(string? icon) => icon != null && Array.IndexOf(EmblemIcons, icon) >= 0;

    /// <summary>The icon drawn in <paramref name="ink"/>, or null when the profile shows its letter.</summary>
    public static Control? EmblemGlyph(string? icon, IBrush ink)
    {
        if (!HasIcon(icon)) return null;
        var solidKey = "Emblem." + icon + ".Solid";
        return new PathGlyph(Icons.Get("Emblem." + icon), EmblemSize, 2, ToneOpacity,
            Icons.Has(solidKey) ? Icons.Get(solidKey) : null) { Brush = ink };
    }

    public static IBrush Tint(ModProfile profile) => TintBrush(TintName(profile));

    public static IBrush TintBrush(string tintName) => Palette.Brush(tintName switch
    {
        "Slate" => "HueTile.Sky",
        "Plum" => "HueTile.Lilac",
        "Umber" => "HueTile.Gold",
        "Moss" => "HueTile.Emerald",
        "Wine" => "HueTile.Coral",
        _ => "CardAlt",
    });

    /// <summary>The ink drawn on a profile's tile (its glyph or letter), the tile's own hue.</summary>
    public static IBrush Ink(ModProfile profile) => InkBrush(TintName(profile));

    public static IBrush InkBrush(string tintName) => Palette.Brush(tintName switch
    {
        "Slate" => "Hue.Sky",
        "Plum" => "Hue.Lilac",
        "Umber" => "Hue.Gold",
        "Moss" => "Hue.Emerald",
        "Wine" => "Hue.Coral",
        _ => "TextDim",
    });

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
