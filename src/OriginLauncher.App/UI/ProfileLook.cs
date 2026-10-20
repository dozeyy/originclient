using System.Windows;
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

    /// <summary>The emblem picker's icons in order, after the first-letter tile. Line icons
    /// are Theme/Icons.xaml geometries (the Origin set); the rest are <see cref="PixelIcons"/>.
    /// The name is what ModProfile.Icon stores, so never rename one.</summary>
    public static readonly string[] EmblemIcons =
        ["Star", "Zap", "Map", "Camera", "Target", "Sun", "Pen", "User", "Box", "Crown", "Globe", "Server",
         "Sword", "Pickaxe", "Diamond", "Heart", "Creeper", "Potion", "Torch", "Chest"];

    // The line icons' resource keys: the emblem-only ones, then three from the launcher's own set.
    private static readonly Dictionary<string, string> LineIcons = new()
    {
        ["Star"] = "Icon.Emblem.Star", ["Zap"] = "Icon.Emblem.Zap", ["Map"] = "Icon.Emblem.Map",
        ["Camera"] = "Icon.Emblem.Camera", ["Target"] = "Icon.Emblem.Target", ["Sun"] = "Icon.Emblem.Sun",
        ["Pen"] = "Icon.Emblem.Pen", ["User"] = "Icon.Emblem.User", ["Box"] = "Icon.Emblem.Box",
        ["Crown"] = "Icon.Crown", ["Globe"] = "Icon.Globe", ["Server"] = "Icon.Server",
    };

    /// <summary>True when the profile shows an icon (a known one), false when it shows its letter.</summary>
    public static bool HasIcon(string? icon) => icon != null && Array.IndexOf(EmblemIcons, icon) >= 0;

    /// <summary>The icon drawn in <paramref name="brush"/>, about <paramref name="size"/> px, or
    /// null when the profile shows its letter. Line icons stroke at that size; pixel icons
    /// fill at a whole multiple of 16 px (16 or 32) so every pixel stays square and crisp.</summary>
    public static FrameworkElement? EmblemGlyph(string? icon, double size, Brush brush)
    {
        if (!HasIcon(icon)) return null;
        if (LineIcons.TryGetValue(icon!, out var key))
            return new Path
            {
                Data = (Geometry)Application.Current.FindResource(key),
                Style = (Style)Application.Current.FindResource("Icon.Base"),
                Stroke = brush,
                Width = size,
                Height = size,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };
        var px = Math.Max(16, Math.Floor(size * 1.25 / 16) * 16);
        var path = new Path
        {
            Data = PixelGeometry(icon!, px / 16),
            Fill = brush,
            Width = px,
            Height = px,
            Stretch = Stretch.None,
            SnapsToDevicePixels = true,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        RenderOptions.SetEdgeMode(path, EdgeMode.Aliased);
        return path;
    }

    private static readonly Dictionary<(string, double), Geometry> PixelCache = new();

    // One rectangle per run of ink in each row, scaled to whole pixels; built once per size.
    private static Geometry PixelGeometry(string icon, double scale)
    {
        if (PixelCache.TryGetValue((icon, scale), out var cached)) return cached;
        var geometry = new StreamGeometry();
        using (var ctx = geometry.Open())
        {
            var rows = PixelIcons[icon];
            for (var y = 0; y < rows.Length; y++)
                for (var x = 0; x < rows[y].Length; x++)
                {
                    if (rows[y][x] != '#') continue;
                    var end = x;
                    while (end + 1 < rows[y].Length && rows[y][end + 1] == '#') end++;
                    ctx.BeginFigure(new Point(x * scale, y * scale), true, true);
                    ctx.PolyLineTo(new[]
                    {
                        new Point((end + 1) * scale, y * scale),
                        new Point((end + 1) * scale, (y + 1) * scale),
                        new Point(x * scale, (y + 1) * scale),
                    }, false, false);
                    x = end;
                }
        }
        geometry.Freeze();
        PixelCache[(icon, scale)] = geometry;
        return geometry;
    }

    /// <summary>Minecraft-style item icons, drawn for Origin (own 16x16 pixel art, no game
    /// textures): '#' is ink, '.' is clear.</summary>
    private static readonly Dictionary<string, string[]> PixelIcons = new()
    {
        ["Sword"] = [
            "............####",
            "...........#...#",
            "..........#...#.",
            ".........#...#..",
            "........#...#...",
            ".......#...#....",
            "..##..#...#.....",
            "..#.##...#......",
            "...#.#..#.......",
            "...##.##........",
            "....###.#.......",
            "...###.###......",
            "..###...##......",
            "####............",
            "#..#............",
            "####............",
        ],
        ["Pickaxe"] = [
            "................",
            "....#######.....",
            "...#########....",
            "..###.....###...",
            "..##.......###..",
            "..........#.###.",
            ".........###.##.",
            "........###..##.",
            ".......###...##.",
            "......###.....#.",
            ".....###........",
            "....###.........",
            "...###..........",
            "..###...........",
            ".###............",
            ".##.............",
        ],
        ["Diamond"] = [
            "................",
            "................",
            "....########....",
            "...#..#..#..#...",
            "..############..",
            ".##############.",
            ".#.##########.#.",
            "..#.########.#..",
            "...#.######.#...",
            "....#.####.#....",
            ".....#.##.#.....",
            "......#..#......",
            ".......##.......",
            "................",
            "................",
            "................",
        ],
        ["Heart"] = [
            "................",
            "................",
            "..####....####..",
            ".######..######.",
            "################",
            "##.#############",
            "##.#############",
            ".##############.",
            "..############..",
            "...##########...",
            "....########....",
            ".....######.....",
            "......####......",
            ".......##.......",
            "................",
            "................",
        ],
        ["Creeper"] = [
            "################",
            "################",
            "##....####....##",
            "##....####....##",
            "##....####....##",
            "##....####....##",
            "######....######",
            "######....######",
            "####........####",
            "####........####",
            "####........####",
            "####........####",
            "####..####..####",
            "####..####..####",
            "################",
            "################",
        ],
        ["Potion"] = [
            "................",
            "......####......",
            "......####......",
            ".......##.......",
            ".......##.......",
            "......#..#......",
            ".....#....#.....",
            "....#......#....",
            "...############.",
            "...############.",
            "...##.#########.",
            "...##.#########.",
            "...############.",
            "....##########..",
            ".....########...",
            "................",
        ],
        ["Torch"] = [
            "................",
            ".......#........",
            "......###.......",
            "......####......",
            ".....######.....",
            ".....######.....",
            "......####......",
            "......####......",
            ".......##.......",
            ".......##.......",
            ".......##.......",
            ".......##.......",
            ".......##.......",
            ".......##.......",
            ".......##.......",
            "................",
        ],
        ["Chest"] = [
            "................",
            "................",
            ".##############.",
            ".##############.",
            ".##############.",
            ".##############.",
            ".......##.......",
            ".######..######.",
            ".######..######.",
            ".##############.",
            ".##############.",
            ".##############.",
            ".##############.",
            ".##############.",
            "................",
            "................",
        ],
    };

    public static Brush Tint(ModProfile profile) => TintBrush(TintName(profile));

    public static Brush Tint(string? id) => TintBrush(HashedTint(id));

    public static Brush TintBrush(string tintName) => (Brush)Application.Current.FindResource("Tint." + tintName);

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
