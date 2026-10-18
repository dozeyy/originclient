using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace OriginLauncher.App.UI.Controls;

// The opening animation. Rules it keeps:
// - Never slows startup: MainWindow builds Home before this plays, and the
//   intro only covers the window for the ~0.8 s the first render + idle takes
//   anyway. It leaves at max(animation end, UI ready), never later than
//   HardCap, and any click or key skips it.
// - Honest: no progress bar. "Ready" is the one real signal - the window has
//   rendered (ContentRendered) and the dispatcher reached ApplicationIdle.
// - Windows' "Show animations" off (App.DisableMotion zeroes Motion.Scene):
//   no intro at all.
// - Fail-soft: any exception removes the layer at once.
// - Gone for good afterwards: it removes itself from the visual tree.
public partial class StartupIntro : UserControl
{
    private static readonly TimeSpan RingStagger = TimeSpan.FromMilliseconds(70);
    private static readonly TimeSpan RingDraw = TimeSpan.FromMilliseconds(560);
    private static readonly TimeSpan RingTurn = TimeSpan.FromMilliseconds(700);
    private static readonly TimeSpan Exit = TimeSpan.FromMilliseconds(260);
    private static readonly TimeSpan SkipExit = TimeSpan.FromMilliseconds(120);
    private static readonly TimeSpan HardCap = TimeSpan.FromMilliseconds(2200);
    // The rings start stacked at this angle and swing out to OriginMark's 0/60/120.
    private const double StartAngle = -40;

    private Window? _host;
    private DispatcherTimer? _cap;
    private bool _animDone, _ready, _leaving;

    public StartupIntro()
    {
        InitializeComponent();
    }

    /// <summary>MainWindow calls this from its constructor, before Show.</summary>
    public void Play(Window host)
    {
        try
        {
            if (!SystemParameters.ClientAreaAnimation || MotionOff())
            {
                Remove();
                return;
            }

            _host = host;
            host.ContentRendered += OnContentRendered;
            host.PreviewKeyDown += OnHostKey;
            PreviewMouseDown += OnSkipClick;

            _cap = new DispatcherTimer(DispatcherPriority.Normal) { Interval = HardCap };
            _cap.Tick += (_, _) => Leave(Exit);
            _cap.Start();

            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            var rings = new[] { (Ring0, Turn0, 0.0), (Ring1, Turn1, 60.0), (Ring2, Turn2, 120.0) };
            for (var i = 0; i < rings.Length; i++)
            {
                var (ring, turn, angle) = rings[i];
                var begin = TimeSpan.FromTicks(RingStagger.Ticks * i);
                var last = i == rings.Length - 1;

                // Dash units are multiples of StrokeThickness. One dash the length
                // of the whole ring plus an equal gap; sliding the offset from L to
                // 0 grows the dash from nothing to the full ring.
                var length = Perimeter(ring) / ring.StrokeThickness + 1;
                ring.StrokeDashCap = PenLineCap.Round;
                ring.StrokeDashArray = new DoubleCollection { length, length };
                ring.StrokeDashOffset = length;
                var draw = new DoubleAnimation(length, 0, RingDraw) { BeginTime = begin, EasingFunction = ease };
                draw.Completed += (_, _) =>
                {
                    // A finished ring is a plain closed stroke again (no dash seam).
                    ring.BeginAnimation(Shape.StrokeDashOffsetProperty, null);
                    ring.StrokeDashArray = null;
                };
                ring.BeginAnimation(Shape.StrokeDashOffsetProperty, draw);

                turn.Angle = angle;
                var swing = new DoubleAnimation(StartAngle, angle, RingTurn) { BeginTime = begin, EasingFunction = ease };
                if (last)
                    swing.Completed += (_, _) => { _animDone = true; TryLeave(); };
                turn.BeginAnimation(RotateTransform.AngleProperty, swing);
            }

            var grow = new DoubleAnimation(0.94, 1.0, RingTurn + RingStagger + RingStagger) { EasingFunction = ease };
            MarkScale.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
            MarkScale.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
        }
        catch (Exception)
        {
            Remove();
        }
    }

    private static bool MotionOff() =>
        Application.Current?.TryFindResource("Motion.Scene") is Duration d && d.HasTimeSpan && d.TimeSpan == TimeSpan.Zero;

    // Ramanujan's approximation of the stroked ellipse's centre line (the
    // geometry is inset by half the stroke).
    private static double Perimeter(Ellipse e)
    {
        var a = (e.Width - e.StrokeThickness) / 2;
        var b = (e.Height - e.StrokeThickness) / 2;
        return Math.PI * (3 * (a + b) - Math.Sqrt((3 * a + b) * (a + 3 * b)));
    }

    private void OnContentRendered(object? sender, EventArgs e)
    {
        if (_host != null) _host.ContentRendered -= OnContentRendered;
        // Rendered once; "ready" is when the queued startup work has drained too.
        Dispatcher.InvokeAsync(() => { _ready = true; TryLeave(); }, DispatcherPriority.ApplicationIdle);
    }

    // Keys pressed during the intro skip it and go no further, so an Enter
    // can't trigger Play on a screen the player can't see yet.
    private void OnHostKey(object sender, KeyEventArgs e)
    {
        // Alt-combinations (Alt+F4) still reach the window.
        if (e.Key != Key.System) e.Handled = true;
        Leave(SkipExit);
    }

    private void OnSkipClick(object sender, MouseButtonEventArgs e)
    {
        e.Handled = true;
        Leave(SkipExit);
    }

    private void TryLeave()
    {
        if (_animDone && _ready) Leave(Exit);
    }

    private void Leave(TimeSpan duration)
    {
        if (_leaving) return;
        _leaving = true;
        Unhook();
        // From here the launcher underneath takes input straight away.
        IsHitTestVisible = false;
        try
        {
            var ease = new CubicEase { EasingMode = EasingMode.EaseIn };
            var fade = new DoubleAnimation(0, duration) { EasingFunction = ease };
            fade.Completed += (_, _) => Remove();
            var lift = new DoubleAnimation(1.06, duration) { EasingFunction = ease };
            MarkScale.BeginAnimation(ScaleTransform.ScaleXProperty, lift);
            MarkScale.BeginAnimation(ScaleTransform.ScaleYProperty, lift);
            BeginAnimation(OpacityProperty, fade);
        }
        catch (Exception)
        {
            Remove();
        }
    }

    private void Unhook()
    {
        _cap?.Stop();
        _cap = null;
        if (_host != null)
        {
            _host.ContentRendered -= OnContentRendered;
            _host.PreviewKeyDown -= OnHostKey;
        }
        PreviewMouseDown -= OnSkipClick;
    }

    private void Remove()
    {
        Unhook();
        _host = null;
        Visibility = Visibility.Collapsed;
        (Parent as Panel)?.Children.Remove(this);
    }
}
