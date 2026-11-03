using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Styling;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// Code-driven one-shot motion (the WPF Motion.FromTo calls on the Mods page): a property runs from one value to another
/// once. Only opacity, translate and scale. With <see cref="Motion.Off"/> (render-shots) the end value is set at once.
/// </summary>
internal static class MotionFx
{
    public static readonly Easing Out = new CubicEaseOut();
    public static readonly Easing Back = new BackEaseOut();

    /// <returns>Done when the end value is set (at once with motion off).</returns>
    public static Task Run(Animatable target, AvaloniaProperty property, double from, double to, int ms, Easing? ease = null, int delayMs = 0)
    {
        if (Motion.Off)
        {
            target.SetValue(property, to);
            return Task.CompletedTask;
        }
        target.SetValue(property, from);
        var animation = new Animation
        {
            Duration = TimeSpan.FromMilliseconds(ms),
            Delay = TimeSpan.FromMilliseconds(delayMs),
            Easing = ease ?? Out,
            FillMode = FillMode.Both,
            Children =
            {
                new KeyFrame { Cue = new Cue(0), Setters = { new Setter(property, from) } },
                new KeyFrame { Cue = new Cue(1), Setters = { new Setter(property, to) } },
            },
        };
        return animation.RunAsync(target).ContinueWith(_ => Avalonia.Threading.Dispatcher.UIThread.InvokeAsync(() => target.SetValue(property, to)).GetTask()).Unwrap();
    }
}
