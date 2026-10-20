using System.Windows;
using System.Windows.Controls;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// A page's content column (Theme/Surfaces.xaml: Page.Column). It fills the width it is given but never
/// more than <see cref="ColumnMaxWidth"/>, and sits against the LEFT edge (under the logo), where a plain
/// element with a MaxWidth and Stretch alignment would be centred (the old pages put their titles at four
/// different x positions that way). The child gets the column's width and the full height, below a top inset
/// the column owns (<see cref="TopInset"/>: 96 under the bar, 80 in a short window), so every page's title sits
/// at the same y at every window size; no page sets its own.
/// </summary>
public sealed class PageColumn : Decorator
{
    public static readonly DependencyProperty ColumnMaxWidthProperty = DependencyProperty.Register(
        nameof(ColumnMaxWidth), typeof(double), typeof(PageColumn),
        new FrameworkPropertyMetadata(1000.0, FrameworkPropertyMetadataOptions.AffectsMeasure));

    /// <summary>The widest the column grows, in device-independent pixels.</summary>
    public double ColumnMaxWidth
    {
        get => (double)GetValue(ColumnMaxWidthProperty);
        set => SetValue(ColumnMaxWidthProperty, value);
    }

    /// <summary>Space above the column under the 56 px bar, and the same in a short window.</summary>
    public const double TopInsetTall = 96, TopInsetShort = 80, ShortBelowHeight = 680;

    /// <summary>The top inset for a page slot this tall (the column's own height plus its margins).</summary>
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
