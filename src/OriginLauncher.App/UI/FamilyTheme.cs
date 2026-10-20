using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI;

/// <summary>
/// The version family on screen (Poster): its art, its two-line label and its one
/// splash of colour. The accent lives in the app resource "Brush.Acc" and is only
/// ever used by indicators (nav underline, Play ring, switches, slider fill, the
/// small squares), never by text. Every consumer binds it with DynamicResource, so
/// <see cref="Apply"/> swaps in one live brush and tweens its colour (700 ms).
/// </summary>
public static class FamilyTheme
{
    public sealed record Family(string Key, string Label, string Range, Color Accent, int Count);

    // Labels and accents from the approved mockup (A2 Poster, FAM table). The range
    // and count come from VersionCatalog, so they follow whatever ships.
    private static readonly Dictionary<string, (string Label, string Hex)> Look = new()
    {
        ["26"] = ("NEWEST", "#F2C94C"),
        ["1.21"] = ("TRICKY TRIALS", "#E8914F"),
        ["1.20"] = ("TRAILS & TALES", "#F29BC0"),
        ["1.19"] = ("THE WILD UPDATE", "#3FD0D4"),
        ["1.18"] = ("CAVES & CLIFFS II", "#B48CF2"),
        ["1.17"] = ("CAVES & CLIFFS I", "#5ED6A0"),
        ["1.16"] = ("NETHER UPDATE", "#F2545B"),
        ["1.8"] = ("CLASSIC PVP", "#7BC043"),
    };

    public static Family Current { get; private set; } = Of(VersionCatalog.DefaultVersion);

    /// <summary>Raised after the family changes (the art stage and Home follow it).</summary>
    public static event Action<Family, Family>? Changed;

    public static VersionGroup GroupOf(string? version) =>
        VersionCatalog.Groups.FirstOrDefault(g => version != null && g.Versions.Contains(version)) ?? VersionCatalog.Groups[0];

    public static Family Of(string? version)
    {
        var g = GroupOf(version);
        var look = Look.TryGetValue(g.Key, out var l) ? l : (g.Tagline.ToUpperInvariant(), "#E0E0E0");
        var range = g.Versions.Count > 1 ? $"{g.Versions[^1]} TO {g.Versions[0]}" : g.Versions[0];
        return new Family(g.Key, look.Item1, range, (Color)ColorConverter.ConvertFromString(look.Item2), g.Versions.Count);
    }

    /// <summary>Make <paramref name="version"/>'s family the one on screen.</summary>
    public static void Apply(string? version, bool animate = true)
    {
        var next = Of(version);
        var prev = Current;
        var res = Application.Current?.Resources;
        if (res == null) return;

        var from = res["Brush.Acc"] is SolidColorBrush b ? b.Color : next.Accent;
        // A fresh unfrozen brush each time: style setters freeze the brushes they hold.
        var brush = new SolidColorBrush(animate && !Motion.Off ? from : next.Accent);
        res["Brush.Acc"] = brush;
        var soft = new SolidColorBrush(Color.FromArgb(0x2E, next.Accent.R, next.Accent.G, next.Accent.B));
        soft.Freeze();
        res["Brush.AccSoft"] = soft;
        // The giant background numeral: the family colour at the neutral ink's low
        // opacity (0x26, ~15%), so it tints the art without competing with the text.
        var ink = new SolidColorBrush(Color.FromArgb(0x26, next.Accent.R, next.Accent.G, next.Accent.B));
        ink.Freeze();
        res["Brush.NumeralInk"] = ink;
        if (animate && !Motion.Off && from != next.Accent)
            brush.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(from, next.Accent, Motion.Ms(700)) { EasingFunction = Motion.Out });

        Current = next;
        Changed?.Invoke(prev, next);
    }
}
