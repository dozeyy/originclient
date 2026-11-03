using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Styling;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// Theme tokens for code-built layout (the WPF pages' FindResource): every value still comes from Theme/*.axaml, never
/// a number typed into code. The app runs in the Dark variant only.
/// </summary>
internal static class Res
{
    public static T Get<T>(string key)
    {
        if (Application.Current is { } app && app.TryGetResource(key, ThemeVariant.Dark, out var value) && value is T t) return t;
        throw new KeyNotFoundException("Theme key missing: " + key);
    }

    public static double D(string key) => Get<double>(key);
    public static IBrush Brush(string key) => Get<IBrush>(key);
    public static CornerRadius Radius(string key) => Get<CornerRadius>(key);
    public static Thickness Thick(string key) => Get<Thickness>(key);
    public static ControlTheme Theme(string key) => Get<ControlTheme>(key);
    public static Geometry Geo(string key) => Get<Geometry>(key);
    public static FontFamily Font => Get<FontFamily>("Font.Primary");

    /// <summary>A Grid with the given column widths ("Auto", "*", or a number).</summary>
    public static Grid Columns(params string[] widths)
    {
        var grid = new Grid();
        foreach (var w in widths) grid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Parse(w)));
        return grid;
    }

    public static T At<T>(this T element, int column) where T : Control
    {
        Grid.SetColumn(element, column);
        return element;
    }
}
