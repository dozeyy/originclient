using System;
using System.Windows;
using System.Windows.Controls;

namespace OriginLauncher.App.UI;

/// <summary>
/// The one way to make a capsule (pill, chip, badge, switch track): <c>ui:Capsule.Round="True"</c> on a Border keeps
/// its corner radius at exactly half its short side, so the ends are true semicircles and the long sides stay straight
/// (Will, 2026-10-25: "pill shaped like apple and never oval shaped ever again").
///
/// Why not a huge CornerRadius (the old Radius.Full = 999): WPF's Border scales over-large radii per axis, each to its
/// own side, so a 999 radius on a 96 x 20 box becomes 48 x 10 elliptical corners that meet in the middle, and the
/// capsule renders as an ellipse. A square box still comes out a circle here, so round dots can use it too.
/// The radius is set only when the size changes; nothing runs per frame.
/// </summary>
public static class Capsule
{
    public static readonly DependencyProperty RoundProperty = DependencyProperty.RegisterAttached(
        "Round", typeof(bool), typeof(Capsule), new PropertyMetadata(false, OnRoundChanged));

    public static bool GetRound(DependencyObject d) => (bool)d.GetValue(RoundProperty);
    public static void SetRound(DependencyObject d, bool value) => d.SetValue(RoundProperty, value);

    private static void OnRoundChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not Border b) return;
        b.SizeChanged -= OnSizeChanged;
        if (e.NewValue is true)
        {
            b.SizeChanged += OnSizeChanged;
            Apply(b);
        }
    }

    private static void OnSizeChanged(object sender, SizeChangedEventArgs e) => Apply((Border)sender);

    /// <summary>Half the short side, the radius of a true capsule (0 before the first layout).</summary>
    public static double RadiusFor(double width, double height) => Math.Max(0, Math.Min(width, height) / 2);

    private static void Apply(Border b)
    {
        var r = RadiusFor(b.ActualWidth, b.ActualHeight);
        if (b.CornerRadius.TopLeft != r || b.CornerRadius.BottomRight != r) b.CornerRadius = new CornerRadius(r);
    }
}
