using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginLauncher.App.UI;

/// <summary>
/// The Poster motion table in code (README "Motion"): durations and easings for
/// animations built in C#. Off (reduced motion, or the CI shot render) makes every
/// duration zero so each animation lands on its final value in the same frame.
/// Only opacity, translate, scale and colour are ever animated.
/// </summary>
public static class Motion
{
    /// <summary>Set by App.DisableMotion: Windows "show animations" is off, or a CI render.</summary>
    public static bool Off { get; set; }

    public static Duration Ms(double ms) => new(Off ? TimeSpan.Zero : TimeSpan.FromMilliseconds(ms));
    public static TimeSpan Delay(double ms) => Off ? TimeSpan.Zero : TimeSpan.FromMilliseconds(ms);

    // Ease-Out (.22,1,.36,1) is close to a quintic ease-out; Ease-In (.55,0,.75,.2) to a
    // cubic ease-in; Ease-Expo (.16,1,.3,1) to an exponential ease-out; Ease-Back to BackEase.
    public static readonly IEasingFunction Out = Freeze(new QuinticEase { EasingMode = EasingMode.EaseOut });
    public static readonly IEasingFunction In = Freeze(new CubicEase { EasingMode = EasingMode.EaseIn });
    public static readonly IEasingFunction Expo = Freeze(new ExponentialEase { EasingMode = EasingMode.EaseOut, Exponent = 7 });
    public static readonly IEasingFunction Back = Freeze(new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.45 });
    public static readonly IEasingFunction InOut = Freeze(new SineEase { EasingMode = EasingMode.EaseInOut });

    private static IEasingFunction Freeze(EasingFunctionBase e) { e.Freeze(); return e; }

    /// <summary>Emphasized, cubic-bezier(.2,.8,.2,1): a fast start that settles softly. The version picker's card
    /// growing into its panel (and shrinking back) uses it.</summary>
    public static readonly IEasingFunction Emphasized = Freeze(new CubicBezierEase(0.2, 0.8, 0.2, 1));

    // A CSS-style cubic-bezier: x(t) is solved for t by Newton steps (bisection as the fallback), then y(t) is the
    // eased value. Pure arithmetic per frame, no allocation.
    private sealed class CubicBezierEase : EasingFunctionBase
    {
        private readonly double _x1, _y1, _x2, _y2;

        public CubicBezierEase(double x1, double y1, double x2, double y2)
        {
            _x1 = x1; _y1 = y1; _x2 = x2; _y2 = y2;
            EasingMode = EasingMode.EaseIn; // EaseIn = the curve exactly as written
        }

        private static double At(double a, double b, double t) => ((1 - 3 * b + 3 * a) * t + (3 * b - 6 * a)) * t * t + 3 * a * t;
        private static double Slope(double a, double b, double t) => 3 * (1 - 3 * b + 3 * a) * t * t + 2 * (3 * b - 6 * a) * t + 3 * a;

        protected override double EaseInCore(double x)
        {
            if (x <= 0) return 0;
            if (x >= 1) return 1;
            var t = x;
            for (var i = 0; i < 8; i++)
            {
                var err = At(_x1, _x2, t) - x;
                if (Math.Abs(err) < 1e-6) return At(_y1, _y2, t);
                var d = Slope(_x1, _x2, t);
                if (Math.Abs(d) < 1e-6) break;
                t -= err / d;
            }
            double lo = 0, hi = 1;
            t = x;
            for (var i = 0; i < 30; i++)
            {
                var v = At(_x1, _x2, t);
                if (Math.Abs(v - x) < 1e-6) break;
                if (v < x) lo = t; else hi = t;
                t = (lo + hi) / 2;
            }
            return At(_y1, _y2, t);
        }

        protected override Freezable CreateInstanceCore() => new CubicBezierEase(_x1, _y1, _x2, _y2);
    }

    /// <summary><paramref name="from"/> held through the delay, then eased to <paramref name="to"/>. Fill stops
    /// back to the property's own value, so a zero duration or an interrupted clock leaves the plain end state.</summary>
    public static DoubleAnimationUsingKeyFrames Hold(double from, double to, TimeSpan delay, TimeSpan length)
    {
        var a = new DoubleAnimationUsingKeyFrames { FillBehavior = FillBehavior.Stop };
        a.KeyFrames.Add(new DiscreteDoubleKeyFrame(from, KeyTime.FromTimeSpan(TimeSpan.Zero)));
        if (delay > TimeSpan.Zero)
            a.KeyFrames.Add(new DiscreteDoubleKeyFrame(from, KeyTime.FromTimeSpan(delay)));
        a.KeyFrames.Add(new EasingDoubleKeyFrame(to, KeyTime.FromTimeSpan(delay + length),
            new CubicEase { EasingMode = EasingMode.EaseOut }));
        return a;
    }

    public static DoubleAnimation To(double to, double ms, IEasingFunction? ease = null, double delayMs = 0) =>
        new(to, Ms(ms)) { EasingFunction = ease ?? Out, BeginTime = Delay(delayMs) };

    public static DoubleAnimation FromTo(double from, double to, double ms, IEasingFunction? ease = null, double delayMs = 0) =>
        new(from, to, Ms(ms)) { EasingFunction = ease ?? Out, BeginTime = Delay(delayMs), FillBehavior = FillBehavior.HoldEnd };

    /// <summary>
    /// The ONE way to put a colour brush that tweens into a resource dictionary
    /// (Brush.Accent): a fresh brush under <paramref name="key"/>, going from → to.
    /// Application and theme dictionaries freeze every Freezable written into them
    /// (ResourceDictionary.SealValue; proven 2026-10-20: IsFrozen flips to true on the
    /// assignment itself, no consumer needed), and BeginAnimation on a frozen brush
    /// throws. So the animation starts BEFORE the brush is published: a brush with a
    /// running animation can't freeze, the dictionary skips it and it stays live. With
    /// no tween (Off, same colour) the brush lands on <paramref name="to"/> and may
    /// freeze, which is fine. Reading the old brush's Color mid-tween gives its current
    /// animated colour, so rapid changes start from what is on screen.
    /// Because the published brush stays unfrozen and is SHARED by every consumer, it
    /// must never be animated through XAML: a Storyboard aimed at a property bound to
    /// it (e.g. Background.Color on a {DynamicResource Brush.Accent} Background) animates
    /// the shared brush in place and recolours every accent element at once. Animate a
    /// local brush there instead.
    /// </summary>
    public static SolidColorBrush PublishColor(ResourceDictionary res, object key, Color from, Color to, double ms, IEasingFunction? ease = null)
    {
        var tween = !Off && ms > 0 && from != to;
        var brush = new SolidColorBrush(tween ? from : to);
        if (tween)
            brush.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(from, to, Ms(ms)) { EasingFunction = ease ?? Out });
        res[key] = brush;
        return brush;
    }
}
