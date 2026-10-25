using System.Windows;
using System.Windows.Controls;

namespace OriginLauncher.App.UI;

/// <summary>
/// A true Apple capsule: a Border's corner radius is exactly half its real height, kept in step as the height changes.
/// Why this exists: WPF's Border shrinks an oversized radius (Radius.Full, 999) separately along each axis, so on any
/// box wider than it is tall the corners become 50%-wide by 50%-tall ellipses and the whole shape is an oval, never a
/// capsule (Will, 2026-10-25: "pill shaped like apple and never oval shaped ever again"; DECISIONS.md Look).
/// Every pill, chip and badge sets <c>ui:Capsule.On="True"</c> (Surface.Capsule does it for its users). Round status
/// dots and circular avatars are squares, where height / 2 is already a circle.
/// </summary>
public static class Capsule
{
    public static readonly DependencyProperty OnProperty = DependencyProperty.RegisterAttached(
        "On", typeof(bool), typeof(Capsule), new PropertyMetadata(false, OnChanged));

    public static bool GetOn(DependencyObject element) => (bool)element.GetValue(OnProperty);

    public static void SetOn(DependencyObject element, bool value) => element.SetValue(OnProperty, value);

    private static void OnChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Border border) return;
        border.SizeChanged -= Resized;
        if (e.NewValue is not true) return;
        border.SizeChanged += Resized;
        Apply(border);
    }

    // Layout raises SizeChanged before the first frame is drawn, so the oval never shows.
    private static void Resized(object sender, SizeChangedEventArgs e)
    {
        if (e.HeightChanged) Apply((Border)sender);
    }

    private static void Apply(Border border)
    {
        var height = border.ActualHeight;
        if (height > 0) border.CornerRadius = new CornerRadius(height / 2);
    }
}
