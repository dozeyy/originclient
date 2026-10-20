using System.Windows;
using System.Windows.Controls;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// A page's content column (Theme/Surfaces.xaml: Page.Column). It fills the width it is given but never
/// more than <see cref="ColumnMaxWidth"/>, and sits against the LEFT edge (under the logo), where a plain
/// element with a MaxWidth and Stretch alignment would be centred (the old pages put their titles at four
/// different x positions that way). The child gets the column's width and the full height.
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

    protected override Size MeasureOverride(Size constraint)
    {
        if (Child == null) return new Size();
        var width = double.IsInfinity(constraint.Width) ? ColumnMaxWidth : Math.Min(constraint.Width, ColumnMaxWidth);
        Child.Measure(new Size(width, constraint.Height));
        // Ask for the whole row so the decorator itself fills the slot; only the child is capped.
        return new Size(double.IsInfinity(constraint.Width) ? Child.DesiredSize.Width : constraint.Width, Child.DesiredSize.Height);
    }

    protected override Size ArrangeOverride(Size arrangeSize)
    {
        Child?.Arrange(new Rect(0, 0, Math.Min(arrangeSize.Width, ColumnMaxWidth), arrangeSize.Height));
        return arrangeSize;
    }
}
