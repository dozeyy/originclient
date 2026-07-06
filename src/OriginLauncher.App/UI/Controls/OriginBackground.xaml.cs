using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginLauncher.App.UI.Controls;

public partial class OriginBackground : UserControl
{
    private readonly List<AnimationClock> _clocks = new();

    public OriginBackground()
    {
        InitializeComponent();
    }

    private void OriginBackground_OnLoaded(object sender, RoutedEventArgs e)
    {
        StartRing(RingATransform, 40, reverse: false);
        StartRing(RingBTransform, 65, reverse: true);
        StartRing(RingCTransform, 90, reverse: false);
        StartRing(RingDTransform, 120, reverse: true);

        var window = Window.GetWindow(this);
        if (window != null)
            window.StateChanged += Window_StateChanged;
    }

    private void StartRing(RotateTransform transform, double periodSeconds, bool reverse)
    {
        var from = transform.Angle;
        var to = from + (reverse ? -360 : 360);
        var animation = new DoubleAnimation(from, to, new Duration(TimeSpan.FromSeconds(periodSeconds)))
        {
            RepeatBehavior = RepeatBehavior.Forever
        };
        var clock = animation.CreateClock();
        transform.ApplyAnimationClock(RotateTransform.AngleProperty, clock);
        _clocks.Add(clock);
    }

    private void Window_StateChanged(object? sender, EventArgs e)
    {
        var window = (Window)sender!;
        foreach (var clock in _clocks)
        {
            if (window.WindowState == WindowState.Minimized)
                clock.Controller?.Pause();
            else
                clock.Controller?.Resume();
        }
    }
}
