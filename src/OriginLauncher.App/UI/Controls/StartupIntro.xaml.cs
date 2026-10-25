using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace OriginLauncher.App.UI.Controls;

// The opening animation. Rules it keeps:
// - Short and skippable: MainWindow builds Home before this plays, so nothing
//   waits on the intro except the player's eyes - about 0.75 s in all (0.54 s
//   for the logo to ease up, a 0.2 s fade) - and any click or key skips it at once. It
//   leaves at max(animation end, UI ready), never later than HardCap.
// - Honest: no progress bar. "Ready" is the one real signal - the window has
//   rendered (ContentRendered) and the dispatcher reached ApplicationIdle.
// - Windows' "Show animations" off (App.DisableMotion zeroes Motion.Scene):
//   no intro at all.
// - Fail-soft: any exception removes the layer at once.
// - Gone for good afterwards: it removes itself from the visual tree.
public partial class StartupIntro : UserControl
{
    private static readonly TimeSpan Rise = TimeSpan.FromMilliseconds(540);
    private static readonly TimeSpan Exit = TimeSpan.FromMilliseconds(200);
    private static readonly TimeSpan SkipExit = TimeSpan.FromMilliseconds(120);
    private static readonly TimeSpan HardCap = TimeSpan.FromMilliseconds(2200);

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
            var grow = new DoubleAnimation(0.94, 1.0, Rise) { EasingFunction = ease };
            grow.Completed += (_, _) => { _animDone = true; TryLeave(); };
            MarkScale.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
            MarkScale.BeginAnimation(ScaleTransform.ScaleYProperty, new DoubleAnimation(0.94, 1.0, Rise) { EasingFunction = ease });
            Mark.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, Rise) { EasingFunction = ease });
        }
        catch (Exception)
        {
            Remove();
        }
    }

    private static bool MotionOff() =>
        Application.Current?.TryFindResource("Motion.Scene") is Duration d && d.HasTimeSpan && d.TimeSpan == TimeSpan.Zero;

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
