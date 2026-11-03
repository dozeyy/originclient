using System.Globalization;
using Avalonia;
using Avalonia.Data.Converters;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// Twin of the WPF CardCornersConverter (UI/Controls/CardConverters.cs): round only the corners a row owns, the card's
/// radius token on the first row's top and the last row's bottom (CardPos, the shared RowModels.cs).
/// </summary>
public sealed class CardCornersConverter : IMultiValueConverter
{
    public object? Convert(IList<object?> values, Type targetType, object? parameter, CultureInfo culture)
    {
        var pos = values.Count > 0 && values[0] is CardPos p ? p : CardPos.Only;
        var radius = values.Count > 1 && values[1] is CornerRadius r ? r : new CornerRadius(0);
        return pos switch
        {
            CardPos.First => new CornerRadius(radius.TopLeft, radius.TopRight, 0, 0),
            CardPos.Last => new CornerRadius(0, 0, radius.BottomRight, radius.BottomLeft),
            CardPos.Middle => new CornerRadius(0),
            _ => radius,
        };
    }
}

/// <summary>
/// True when a row sits at the bottom of its card (Last or Only): the WPF DataTrigger "Pos = Last" as a class switch
/// (Avalonia templates have no triggers).
/// </summary>
public sealed class CardEndsConverter : IValueConverter
{
    public static readonly CardEndsConverter Instance = new();

    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is CardPos.Last;

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
