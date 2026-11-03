using Avalonia;
using Avalonia.Controls;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// Twin of the WPF UI/Controls/PageColumn.cs (the same numbers, the same measure and arrange): an inner page's content
/// column, at most <see cref="ColumnMaxWidth"/> wide (Theme Page.Column), starting under the bar at the page's top
/// inset: 96, or 80 when the page's slot is under <see cref="ShortBelowHeight"/> tall.
/// </summary>
public sealed class PageColumn : Decorator
{
    public static readonly StyledProperty<double> ColumnMaxWidthProperty =
        AvaloniaProperty.Register<PageColumn, double>(nameof(ColumnMaxWidth), 1000);

    public double ColumnMaxWidth { get => GetValue(ColumnMaxWidthProperty); set => SetValue(ColumnMaxWidthProperty, value); }

    public const double TopInsetTall = 96, TopInsetShort = 80, ShortBelowHeight = 680;

    static PageColumn()
    {
        AffectsMeasure<PageColumn>(ColumnMaxWidthProperty);
    }

    public static double TopInsetFor(double slotHeight) =>
        !double.IsInfinity(slotHeight) && slotHeight > 0 && slotHeight < ShortBelowHeight ? TopInsetShort : TopInsetTall;

    private double TopInset(double height) => TopInsetFor(height + Margin.Top + Margin.Bottom);

    protected override Size MeasureOverride(Size constraint)
    {
        if (Child == null) return new Size();
        var width = double.IsInfinity(constraint.Width) ? ColumnMaxWidth : Math.Min(constraint.Width, ColumnMaxWidth);
        var top = TopInset(constraint.Height);
        Child.Measure(new Size(width, double.IsInfinity(constraint.Height) ? constraint.Height : Math.Max(0, constraint.Height - top)));
        // Ask for the whole row so the decorator itself fills the slot; only the child is capped.
        return new Size(double.IsInfinity(constraint.Width) ? Child.DesiredSize.Width : constraint.Width, Child.DesiredSize.Height + top);
    }

    protected override Size ArrangeOverride(Size arrangeSize)
    {
        var top = TopInset(arrangeSize.Height);
        Child?.Arrange(new Rect(0, top, Math.Min(arrangeSize.Width, ColumnMaxWidth), Math.Max(0, arrangeSize.Height - top)));
        return arrangeSize;
    }
}
