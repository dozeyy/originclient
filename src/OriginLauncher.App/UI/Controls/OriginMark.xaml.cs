using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginLauncher.App.UI.Controls;

public partial class OriginMark : UserControl
{
    public OriginMark()
    {
        InitializeComponent();
    }

    /// <summary>
    /// The mark assembling itself (docs/MOTION.md "align"): the three rings
    /// start stacked on one axis and fan out to 0/60/120 while the mark fades
    /// up. One shot, after <paramref name="delay"/>. Every animation runs
    /// FillBehavior.Stop back to the XAML values, which ARE the finished mark,
    /// so a zero duration (reduced motion), an interrupted clock or a skipped
    /// call all leave the plain static mark — it can never stick half-built.
    /// </summary>
    public void Align(Duration duration, TimeSpan delay = default)
    {
        if (!duration.HasTimeSpan || duration.TimeSpan <= TimeSpan.Zero) return;
        var length = duration.TimeSpan;
        Spin(MiddleRing, 60, length, delay);
        Spin(InnerRing, 120, length, delay);
        // Visible a little before the fan-out finishes, so the motion reads
        // as rings settling, not rings popping in.
        Rings.BeginAnimation(OpacityProperty, Hold(0, 1, delay, TimeSpan.FromTicks(length.Ticks * 6 / 10)));
    }

    private static void Spin(RotateTransform ring, double angle, TimeSpan length, TimeSpan delay) =>
        ring.BeginAnimation(RotateTransform.AngleProperty, Hold(0, angle, delay, length));

    /// <summary><paramref name="from"/> held through the delay, then eased to <paramref name="to"/>.</summary>
    internal static DoubleAnimationUsingKeyFrames Hold(double from, double to, TimeSpan delay, TimeSpan length)
    {
        var a = new DoubleAnimationUsingKeyFrames { FillBehavior = FillBehavior.Stop };
        a.KeyFrames.Add(new DiscreteDoubleKeyFrame(from, KeyTime.FromTimeSpan(TimeSpan.Zero)));
        if (delay > TimeSpan.Zero)
            a.KeyFrames.Add(new DiscreteDoubleKeyFrame(from, KeyTime.FromTimeSpan(delay)));
        a.KeyFrames.Add(new EasingDoubleKeyFrame(to, KeyTime.FromTimeSpan(delay + length),
            new CubicEase { EasingMode = EasingMode.EaseOut }));
        return a;
    }
}
