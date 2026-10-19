using System.Windows;
using System.Windows.Media;

namespace OriginLauncher.App.UI;

/// <summary>
/// How a mod profile looks (Poster): an emblem tile in one of six desaturated
/// tints, picked from the profile's stable id so it never changes, with the
/// name's first letter on it. Profiles store no look of their own.
/// </summary>
public static class ProfileLook
{
    private static readonly string[] Tints = ["Tint.Slate", "Tint.Plum", "Tint.Umber", "Tint.Moss", "Tint.Wine", "Tint.Graphite"];

    public static Brush Tint(string? id)
    {
        uint h = 2166136261;
        foreach (var c in id ?? "") h = unchecked((h ^ c) * 16777619);
        return (Brush)Application.Current.FindResource(Tints[h % (uint)Tints.Length]);
    }

    public static string Initial(string? name)
    {
        foreach (var c in name ?? "")
            if (char.IsLetterOrDigit(c)) return char.ToUpperInvariant(c).ToString();
        return "P";
    }
}
