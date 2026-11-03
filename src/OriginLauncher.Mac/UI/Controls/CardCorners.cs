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
/// The drawer row's keyboard focus frame (Theme/Import.axaml Social.RowFocus): the WPF frame binds its corners to
/// AdornedElement.DataContext.Pos, but an Avalonia binding path can't name AdornerLayer inside a template ("Unable to
/// resolve type"), so the frame reads its row when the focus adorner is attached to it. Pos is a plain property
/// (no change notice, the same in WPF), so reading it once per focus is the whole job.
/// </summary>
public sealed class RowFocusFrame : Avalonia.Controls.Border
{
    /// <summary>The card's ring radius; the frame keeps the corners of it that its row owns.</summary>
    public static readonly StyledProperty<CornerRadius> RingRadiusProperty =
        AvaloniaProperty.Register<RowFocusFrame, CornerRadius>(nameof(RingRadius));

    public CornerRadius RingRadius { get => GetValue(RingRadiusProperty); set => SetValue(RingRadiusProperty, value); }

    protected override Type StyleKeyOverride => typeof(Avalonia.Controls.Border);

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == Avalonia.Controls.Primitives.AdornerLayer.AdornedElementProperty || change.Property == RingRadiusProperty)
            Refresh();
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        Refresh();
    }

    private void Refresh()
    {
        var row = Avalonia.Controls.Primitives.AdornerLayer.GetAdornedElement(this);
        var pos = row?.DataContext is CardRowVm vm ? vm.Pos : CardPos.Only;
        CornerRadius = (CornerRadius)new CardCornersConverter().Convert([pos, RingRadius], typeof(CornerRadius), null, CultureInfo.InvariantCulture)!;
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
