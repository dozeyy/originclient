using System.Windows;
using System.Windows.Media;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI;

/// <summary>
/// The version family on screen (Poster): its art, its two-line label and its
/// colour. The accent lives in the app resources "Brush.Acc" (full), "Brush.AccLine"
/// (60%) and "Brush.AccSoft" (18%) and is only ever used by indicators (nav underline,
/// Play ring, switches, slider fill, the small squares, focus rings), never by text.
/// Every consumer binds them with DynamicResource, so <see cref="Apply"/> swaps in
/// live brushes and tweens Brush.Acc's colour (700 ms).
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

    /// <summary>A version's own family colour, for anything that shows ONE version
    /// whatever family Home has on screen: a profile card (its version), the launch
    /// screen (the version being launched). Brush.Acc is Home's family only.</summary>
    public static Brush AccentBrush(string? version, byte alpha = 0xFF)
    {
        var c = Of(version).Accent;
        var brush = new SolidColorBrush(Color.FromArgb(alpha, c.R, c.G, c.B));
        brush.Freeze();
        return brush;
    }

    /// <summary>Alpha of Brush.AccLine (a quieter outline) and Brush.AccSoft (a wash).</summary>
    public const byte LineAlpha = 0x99, SoftAlpha = 0x2E;

    /// <summary>Make <paramref name="version"/>'s family the one on screen.</summary>
    public static void Apply(string? version, bool animate = true)
    {
        var next = Of(version);
        var prev = Current;
        var res = Application.Current?.Resources;
        if (res == null) return;

        var from = res["Brush.Acc"] is SolidColorBrush b ? b.Color : next.Accent;
        // A fresh brush each time, published through Motion.PublishColor: the app
        // dictionary freezes whatever is written into it, so animating the brush after
        // the assignment threw on every family change (v1.0.60 crash, arrow keys in the
        // version picker). PublishColor starts the tween first, which keeps it live.
        Motion.PublishColor(res, "Brush.Acc", from, next.Accent, animate ? 700 : 0);
        var soft = new SolidColorBrush(Color.FromArgb(SoftAlpha, next.Accent.R, next.Accent.G, next.Accent.B));
        soft.Freeze();
        res["Brush.AccSoft"] = soft;
        var line = new SolidColorBrush(Color.FromArgb(LineAlpha, next.Accent.R, next.Accent.G, next.Accent.B));
        line.Freeze();
        res["Brush.AccLine"] = line;
        // The giant numeral stays neutral (Brush.NumeralInk in Colors.xaml): Will
        // wanted it black and white again, 2026-10-19.

        Current = next;
        Changed?.Invoke(prev, next);
    }
}
