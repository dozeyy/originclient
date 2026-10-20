using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace OriginLauncher.App.UI.Controls;

/// <summary>Where a row sits in its card group (Theme/Social.xaml: Social.DrawerRow, and the Import drawer rows).</summary>
public enum CardPos { Only, First, Middle, Last }

/// <summary>Round only the corners a row owns: the card's radius token on the first row's top and the last row's bottom.</summary>
public sealed class CardCornersConverter : IMultiValueConverter
{
    public object Convert(object[] values, Type targetType, object parameter, CultureInfo culture)
    {
        var pos = values.Length > 0 && values[0] is CardPos p ? p : CardPos.Only;
        var radius = values.Length > 1 && values[1] is CornerRadius r ? r : new CornerRadius(0);
        return pos switch
        {
            CardPos.First => new CornerRadius(radius.TopLeft, radius.TopRight, 0, 0),
            CardPos.Last => new CornerRadius(0, 0, radius.BottomRight, radius.BottomLeft),
            CardPos.Middle => new CornerRadius(0),
            _ => radius,
        };
    }

    public object[] ConvertBack(object value, Type[] targetTypes, object parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
