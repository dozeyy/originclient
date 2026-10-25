using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginSetup.UI;

// Page changes use the Workbench scene motion (spec 9): a short fade + small rise, timed by the
// Motion.Scene token; the progress fill glides with Motion.Base. Both are zero when Windows animations are off.
public static class PageMotion
{
    /// <summary>Set by the CI screenshot tour: every change lands immediately.</summary>
    public static bool Instant { get; set; }

    public static void Show(FrameworkElement page, params FrameworkElement[] others)
    {
        foreach (var o in others)
            if (!ReferenceEquals(o, page)) o.Visibility = Visibility.Collapsed;

        page.Visibility = Visibility.Visible;
        if (Instant)
        {
            page.BeginAnimation(UIElement.OpacityProperty, null);
            page.Opacity = 1;
            page.RenderTransform = new TranslateTransform();
            return;
        }
        var duration = (Duration)Application.Current.Resources["Motion.Scene"];
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var rise = page.RenderTransform as TranslateTransform ?? new TranslateTransform();
        page.RenderTransform = rise;
        page.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, duration) { EasingFunction = ease });
        rise.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(10, 0, duration) { EasingFunction = ease });
    }

    /// <summary>Glides a bar's width to the target (a progress fill).</summary>
    public static void Width(FrameworkElement bar, double to)
    {
        if (Instant || !SystemParameters.ClientAreaAnimation)
        {
            bar.BeginAnimation(FrameworkElement.WidthProperty, null);
            bar.Width = to;
            return;
        }
        var duration = (Duration)Application.Current.Resources["Motion.Base"];
        bar.BeginAnimation(FrameworkElement.WidthProperty,
            new DoubleAnimation(to, duration), HandoffBehavior.SnapshotAndReplace);
    }
}
