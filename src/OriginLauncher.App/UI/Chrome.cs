using System.Windows;

namespace OriginLauncher.App.UI;

/// <summary>
/// One per-control hook the shared button template (Theme/Buttons.xaml) reads: <c>Corner</c>. One template serves
/// every size step, and each size style sets its corner-radius token (design system section 6: 28 and 32 tall use
/// Radius.Sm, 36 and 40 tall use Radius.Md). A style sets it from a token, so no radius is written in code.
/// </summary>
public static class Chrome
{
    public static readonly DependencyProperty CornerProperty = DependencyProperty.RegisterAttached(
        "Corner", typeof(CornerRadius), typeof(Chrome), new FrameworkPropertyMetadata(new CornerRadius()));

    public static CornerRadius GetCorner(DependencyObject d) => (CornerRadius)d.GetValue(CornerProperty);
    public static void SetCorner(DependencyObject d, CornerRadius v) => d.SetValue(CornerProperty, v);
}
