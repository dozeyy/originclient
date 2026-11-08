using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Media.Transformation;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Kit;

/// <summary>
/// One visual's opacity and/or render-transform motion, the Avalonia twin of the WPF launcher's DoubleAnimations with
/// HandoffBehavior.SnapshotAndReplace: each <see cref="To"/> runs from wherever the visual is now (a close during an
/// open turns around mid-way) for the given token duration.
///
/// Only Transitions on the VISUAL's own Opacity / RenderTransform properties, never a keyframe Animation run on a
/// Transform object: that is the Mac v1.0.92 crash class (Avalonia's TransformAnimator casting a Transform to Visual).
/// One transition object per property is kept and only its Duration changes between calls, so a change mid-flight
/// never resets the transition state (replacing the Transitions collection would snap the visual to its target first).
/// With Motion.Off (reduced motion, render runs) no transition is attached and every value lands at once.
/// </summary>
public sealed class Tween
{
    private readonly Visual _visual;
    private readonly DoubleTransition? _opacity;
    private readonly TransformOperationsTransition? _transform;

    public Tween(Visual visual, bool opacity = true, bool transform = false, Easing? opacityEasing = null, Easing? transformEasing = null)
    {
        _visual = visual;
        if (Motion.Off) return;
        var transitions = new Transitions();
        if (opacity)
        {
            _opacity = new DoubleTransition { Property = Visual.OpacityProperty, Easing = opacityEasing ?? new LinearEasing() };
            transitions.Add(_opacity);
        }
        if (transform)
        {
            _transform = new TransformOperationsTransition { Property = Visual.RenderTransformProperty, Easing = transformEasing ?? new LinearEasing() };
            transitions.Add(_transform);
        }
        if (visual is Animatable a) a.Transitions = transitions;
    }

    /// <summary>Puts the visual where a motion starts, with no transition (call before it is shown).</summary>
    public void Jump(double? opacity = null, string? transform = null)
    {
        var saved = (_visual as Animatable)?.Transitions;
        if (_visual is Animatable a) a.Transitions = null;
        if (opacity is { } o) _visual.Opacity = o;
        if (transform != null) _visual.RenderTransform = TransformOperations.Parse(transform);
        if (_visual is Animatable b) b.Transitions = saved;
    }

    /// <summary>Moves to the given opacity / transform ("none", "translateX(380px)", "scale(0.97)") over duration.</summary>
    public void To(TimeSpan duration, double? opacity = null, string? transform = null)
    {
        if (_opacity != null) _opacity.Duration = duration;
        if (_transform != null) _transform.Duration = duration;
        if (opacity is { } o) _visual.Opacity = o;
        if (transform != null) _visual.RenderTransform = TransformOperations.Parse(transform);
    }
}
