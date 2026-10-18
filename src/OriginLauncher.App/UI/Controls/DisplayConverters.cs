using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Windows.Data;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// A family's versions as the few chips a picker card has room for: the newest
/// <see cref="Shown"/> and a "+N" chip for the rest (the website's version cards
/// list every one; the launcher's cards are fixed-size, and the full list is a
/// click away in the detail view's version box).
/// </summary>
public sealed class VersionChipsConverter : IValueConverter
{
    private const int Shown = 3;

    public object Convert(object value, System.Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not IReadOnlyList<string> versions) return System.Array.Empty<string>();
        if (versions.Count <= Shown) return versions.ToList();
        var chips = versions.Take(Shown).ToList();
        chips.Add("+" + (versions.Count - Shown).ToString(CultureInfo.InvariantCulture));
        return chips;
    }

    public object ConvertBack(object value, System.Type targetType, object parameter, CultureInfo culture) =>
        throw new System.NotSupportedException();
}

/// <summary>Upper-cases a label for the eyebrow style (a TextBlock has no casing property).</summary>
public sealed class UpperCaseConverter : IValueConverter
{
    public object Convert(object value, System.Type targetType, object parameter, CultureInfo culture) =>
        (value as string)?.ToUpperInvariant() ?? string.Empty;

    public object ConvertBack(object value, System.Type targetType, object parameter, CultureInfo culture) =>
        throw new System.NotSupportedException();
}
