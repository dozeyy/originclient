using System.Runtime.CompilerServices;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Media;
using Avalonia.Styling;
using Avalonia.Threading;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// Code-driven one-shot motion (the WPF Motion.FromTo calls on the Mods page): a property runs from one value to another
/// once. Only opacity, translate and scale. With <see cref="Motion.Off"/> (render-shots) the end value is set at once.
/// A new run of the same property on the same object replaces the one still running, so a drawer opened and closed
/// quickly settles on the last value asked for.
/// </summary>
internal static class MotionFx
{
    // The WPF launcher's own curves (src/OriginLauncher.App/UI/Motion.cs), so a slide or a landing moves the same on
    // every OS: Out is WPF Motion.Out (quintic ease-out), Back is WPF Motion.Back (BackEase, amplitude 0.45; Avalonia's
    // BackEaseOut overshoots with amplitude 1, twice as far). Cubic is the drawers' and the sign-in card's slide/scale
    // (WPF: new CubicEase EaseOut there), Linear their scrim and fade (WPF: a DoubleAnimation with no easing).
    public static readonly Easing Out = new QuinticEaseOut();
    public static readonly Easing Back = new WpfBackEaseOut(0.45);
    public static readonly Easing Cubic = new CubicEaseOut();
    public static readonly Easing Linear = new LinearEasing();

    /// <summary>WPF's BackEase in EaseOut mode: 1 - f(1 - t) with f(t) = t^3 - t * amplitude * sin(pi * t).</summary>
    private sealed class WpfBackEaseOut(double amplitude) : Easing
    {
        public override double Ease(double progress)
        {
            var t = 1 - progress;
            return 1 - (t * t * t - t * amplitude * Math.Sin(Math.PI * t));
        }
    }

    // The run in flight per (object, property): the next run cancels it, so two runs never fight over one value and a
    // cancelled run never writes its stale end value over the newer one.
    private static readonly ConditionalWeakTable<AvaloniaObject, Dictionary<AvaloniaProperty, CancellationTokenSource>> Running = new();

    /// <summary>Runs that could not animate and jumped to their end value instead. --motion-check fails unless this stays 0.</summary>
    internal static int Fallbacks;

    /// <summary>
    /// Runs a property of <paramref name="target"/> itself (Opacity and the like). A property owned by a Transform must go
    /// through <see cref="Run(Visual, Transform, AvaloniaProperty, double, double, int, Easing?, int)"/> instead.
    /// </summary>
    /// <returns>Done when the end value is set (at once with motion off).</returns>
    public static Task Run(Animatable target, AvaloniaProperty property, double from, double to, int ms, Easing? ease = null, int delayMs = 0)
    {
        if (typeof(Transform).IsAssignableFrom(property.OwnerType))
            throw new ArgumentException($"{property.OwnerType.Name}.{property.Name} is a transform property: pass the control that wears the transform.", nameof(property));
        return Start(target, target, property, from, to, ms, ease, delayMs);
    }

    /// <summary>
    /// Runs a property of <paramref name="transform"/> (ScaleX/ScaleY, X/Y), which <paramref name="owner"/> wears as its
    /// RenderTransform or inside its RenderTransform group.
    /// Avalonia animates a Transform-owned property only through the Visual that wears it: its TransformAnimator casts the
    /// animation's target to Visual and then finds the transform of that type in the visual's RenderTransform
    /// (Avalonia 12.1.5 src/Avalonia.Base/Animation/Animators/TransformAnimator.cs). Running the animation on the transform
    /// object itself threw "Unable to cast ScaleTransform to Visual" (the v1.0.92 Mac crash on a signed-out Play).
    /// </summary>
    public static Task Run(Visual owner, Transform transform, AvaloniaProperty property, double from, double to, int ms, Easing? ease = null, int delayMs = 0)
    {
        if (!property.OwnerType.IsInstanceOfType(transform))
            throw new ArgumentException($"{property.OwnerType.Name}.{property.Name} is not a property of {transform.GetType().Name}.", nameof(property));
        if (!IsFoundBy(owner, transform))
        {
            // The animator would animate another transform (or none): set the end value so the screen is still right.
            System.Diagnostics.Debug.WriteLine($"MotionFx: {transform.GetType().Name} is not the one {owner.GetType().Name}'s animator would find; setting the end value.");
            Interlocked.Increment(ref Fallbacks);
            Cancel(transform, property);
            transform.SetValue(property, to);
            return Task.CompletedTask;
        }
        return Start(owner, transform, property, from, to, ms, ease, delayMs);
    }

    /// <summary>
    /// True when Avalonia's TransformAnimator, run on <paramref name="owner"/>, animates exactly <paramref name="transform"/>:
    /// it is the RenderTransform itself, or the first of its type in a RenderTransform group (the animator takes the first).
    /// </summary>
    internal static bool IsFoundBy(Visual owner, Transform transform) => owner.RenderTransform switch
    {
        Transform t when ReferenceEquals(t, transform) => true,
        TransformGroup group => ReferenceEquals(group.Children.FirstOrDefault(c => c.GetType() == transform.GetType()), transform),
        _ => false,
    };

    // runOn: the object the animation is applied to; valueOn: the object that holds the property's value.
    private static Task Start(Animatable runOn, AvaloniaObject valueOn, AvaloniaProperty property, double from, double to, int ms, Easing? ease, int delayMs)
    {
        Cancel(valueOn, property);
        if (Motion.Off)
        {
            valueOn.SetValue(property, to);
            return Task.CompletedTask;
        }
        var cts = new CancellationTokenSource();
        Running.GetOrCreateValue(valueOn)[property] = cts;
        valueOn.SetValue(property, from);
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
        Task run;
        try
        {
            run = animation.RunAsync(runOn, cts.Token);
        }
        catch (Exception e)
        {
            // An animator refusing the target must never take a screen down: land on the end value instead.
            System.Diagnostics.Debug.WriteLine($"MotionFx: {property.Name} could not animate ({e.GetType().Name}); setting the end value.");
            Interlocked.Increment(ref Fallbacks);
            Forget(valueOn, property, cts);
            valueOn.SetValue(property, to);
            return Task.CompletedTask;
        }
        // The end value is written as a local value once the run ends, so it holds after the animation's own value
        // goes away; a run replaced by a newer one leaves the value to the newer run.
        return run.ContinueWith(_ => Dispatcher.UIThread.InvokeAsync(() =>
        {
            if (cts.IsCancellationRequested) return;
            Forget(valueOn, property, cts);
            valueOn.SetValue(property, to);
        }).GetTask(), TaskScheduler.Default).Unwrap();
    }

    private static void Cancel(AvaloniaObject valueOn, AvaloniaProperty property)
    {
        if (Running.TryGetValue(valueOn, out var runs) && runs.Remove(property, out var previous))
            previous.Cancel();
    }

    private static void Forget(AvaloniaObject valueOn, AvaloniaProperty property, CancellationTokenSource cts)
    {
        if (Running.TryGetValue(valueOn, out var runs) && runs.TryGetValue(property, out var current) && ReferenceEquals(current, cts))
            runs.Remove(property);
    }
}
