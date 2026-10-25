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

    /// <summary>
    /// A field whose value is rejected: the text field template (Theme/Inputs.xaml) draws a Danger edge, and the page
    /// shows the reason right under it in the Input.ErrorText style. Cleared as soon as the value is fixed.
    /// </summary>
    public static readonly DependencyProperty InvalidProperty = DependencyProperty.RegisterAttached(
        "Invalid", typeof(bool), typeof(Chrome), new FrameworkPropertyMetadata(false));

    public static bool GetInvalid(DependencyObject d) => (bool)d.GetValue(InvalidProperty);
    public static void SetInvalid(DependencyObject d, bool v) => d.SetValue(InvalidProperty, v);
}
