using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginLauncher.App.UI;

/// <summary>
/// The one press-scale for every button (README "Motion": press 80 ms, release 140 ms). A style sets
/// <c>Press.Scale</c> (how far it shrinks, default 0.985) and raises <c>Press.Down</c> from an IsPressed
/// trigger; this class does the animation on a ScaleTransform it owns.
///
/// Why not a storyboard on "RenderTransform.ScaleX" in the style: that path only resolves while the
/// element's RenderTransform IS a ScaleTransform. Anything that gives a button its own transform (the Home
/// intro slides PLAY up with a TranslateTransform) made every later press throw
/// "Cannot resolve all property references in the property path 'RenderTransform.ScaleX'" (v1.0.58-60).
/// Here the scale is added next to whatever transform the element already has, so a consumer's transform
/// is kept and can never break the press. OriginLauncher.Tests/PressScaleTests forbids the old pattern.
/// </summary>
public static class Press
{
    public static readonly DependencyProperty ScaleProperty = DependencyProperty.RegisterAttached(
        "Scale", typeof(double), typeof(Press), new PropertyMetadata(0.985));

    public static double GetScale(DependencyObject d) => (double)d.GetValue(ScaleProperty);
    public static void SetScale(DependencyObject d, double value) => d.SetValue(ScaleProperty, value);

    public static readonly DependencyProperty DownProperty = DependencyProperty.RegisterAttached(
        "Down", typeof(bool), typeof(Press), new PropertyMetadata(false, OnDownChanged));

    public static bool GetDown(DependencyObject d) => (bool)d.GetValue(DownProperty);
    public static void SetDown(DependencyObject d, bool value) => d.SetValue(DownProperty, value);

    // The element's own press transform, kept so repeated presses reuse it.
    private static readonly DependencyProperty OwnScaleProperty = DependencyProperty.RegisterAttached(
        "OwnScale", typeof(ScaleTransform), typeof(Press), new PropertyMetadata(null));

    private static readonly IEasingFunction PressEase = Frozen(new QuadraticEase { EasingMode = EasingMode.EaseOut });
    private static readonly IEasingFunction ReleaseEase = Frozen(new CubicEase { EasingMode = EasingMode.EaseOut });

    private static IEasingFunction Frozen(EasingFunctionBase e) { e.Freeze(); return e; }

    private static void OnDownChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not FrameworkElement element) return;
        var down = (bool)e.NewValue;
        var scale = EnsureScale(element);
        // Motion.Press / Motion.Release are zeroed by App.DisableMotion (reduced motion, CI renders).
        var duration = element.TryFindResource(down ? "Motion.Press" : "Motion.Release") is Duration d2
            ? d2
            : Motion.Ms(down ? 80 : 140);
        var animation = new DoubleAnimation(down ? GetScale(element) : 1.0, duration)
        {
            EasingFunction = down ? PressEase : ReleaseEase,
        };
        animation.Freeze();
        scale.BeginAnimation(ScaleTransform.ScaleXProperty, animation);
        scale.BeginAnimation(ScaleTransform.ScaleYProperty, animation);
    }

    /// <summary>
    /// Returns the element's press ScaleTransform, making sure it is part of RenderTransform. No transform
    /// of its own: the scale becomes it. Some other transform (set before or after this ran): the scale is
    /// composed in front of it in a TransformGroup, so that transform and its running animations are kept.
    /// </summary>
    internal static ScaleTransform EnsureScale(UIElement element)
    {
        if (element.GetValue(OwnScaleProperty) is not ScaleTransform own)
        {
            own = new ScaleTransform(1, 1);
            element.SetValue(OwnScaleProperty, own);
        }

        var current = element.RenderTransform;
        if (ReferenceEquals(current, own)) return own;
        if (current is TransformGroup group && group.Children.Contains(own)) return own;

        element.RenderTransform = current is null || ReferenceEquals(current, Transform.Identity)
            ? own
            : new TransformGroup { Children = { own, current } };
        return own;
    }
}
