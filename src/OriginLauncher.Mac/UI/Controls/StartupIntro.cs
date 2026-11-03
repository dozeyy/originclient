using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// The launcher's opening (twin of the WPF StartupIntro): the official logo (BrandLogo, Size.Logo.Intro) fades in and
/// eases up from 94 % to full size on the bg colour, then the layer fades away to reveal the launcher already built
/// underneath. Rules it keeps:
/// - Short and skippable: about 0.75 s in all (0.54 s rise, 0.2 s fade); any click or key skips it at once (the key
///   goes no further, so an Enter can't press Play on a screen the player can't see yet). Never longer than HardCap.
/// - Honest: no progress bar. "Ready" is the one real signal: the window has opened and the dispatcher went idle.
/// - Motion off (render shots): no intro at all. Fail-soft: any exception removes the layer at once.
/// - Gone for good afterwards: it removes itself from the visual tree.
/// </summary>
public sealed class StartupIntro : UserControl
{
    private static readonly TimeSpan Rise = TimeSpan.FromMilliseconds(540);
    private static readonly TimeSpan Exit = TimeSpan.FromMilliseconds(200);
    private static readonly TimeSpan SkipExit = TimeSpan.FromMilliseconds(120);
    private static readonly TimeSpan HardCap = TimeSpan.FromMilliseconds(2200);

    internal BrandLogo Mark { get; }
    internal ScaleTransform MarkScale { get; } = new(0.94, 0.94);

    private Window? _host;
    private DispatcherTimer? _cap;
    private bool _animDone, _ready, _leaving;

    public StartupIntro()
    {
        Focusable = false;
        Mark = new BrandLogo
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            RenderTransform = MarkScale,
            Opacity = 0,
        };
        Content = Mark;
        Bind(BackgroundProperty, this.GetResourceObservable("Brush.Bg"));
        Mark.Bind(WidthProperty, this.GetResourceObservable("Size.Logo.Intro"));
        Mark.Bind(HeightProperty, this.GetResourceObservable("Size.Logo.Intro"));
    }

    /// <summary>MainWindow calls this from its constructor, before Show.</summary>
    public void Play(Window host)
    {
        try
        {
            if (Motion.Off)
            {
                Remove();
                return;
            }
            _host = host;
            host.Opened += OnOpened;
            host.AddHandler(KeyDownEvent, OnHostKey, RoutingStrategies.Tunnel);
            AddHandler(PointerPressedEvent, OnSkipClick, RoutingStrategies.Tunnel);
            _cap = new DispatcherTimer(DispatcherPriority.Normal) { Interval = HardCap };
            _cap.Tick += (_, _) => Leave(Exit);
            _cap.Start();

            Animate(Rise, new CubicEaseOut());
            // Posted, so the 0.94 / 0 start values are drawn once before the transitions run to the end values.
            Dispatcher.UIThread.Post(() =>
            {
                MarkScale.ScaleX = MarkScale.ScaleY = 1;
                Mark.Opacity = 1;
                DispatcherTimer.RunOnce(() => { _animDone = true; TryLeave(); }, Rise);
            }, DispatcherPriority.Render);
        }
        catch (Exception)
        {
            Remove();
        }
    }

    private void Animate(TimeSpan d, Easing ease)
    {
        MarkScale.Transitions = new Transitions
        {
            new DoubleTransition { Property = ScaleTransform.ScaleXProperty, Duration = d, Easing = ease },
            new DoubleTransition { Property = ScaleTransform.ScaleYProperty, Duration = d, Easing = ease },
        };
        Mark.Transitions = new Transitions { new DoubleTransition { Property = OpacityProperty, Duration = d, Easing = ease } };
        Transitions = new Transitions { new DoubleTransition { Property = OpacityProperty, Duration = d, Easing = ease } };
    }

    private void OnOpened(object? sender, EventArgs e)
    {
        if (_host != null) _host.Opened -= OnOpened;
        // Shown once; "ready" is when the queued startup work has drained too.
        Dispatcher.UIThread.Post(() => { _ready = true; TryLeave(); }, DispatcherPriority.ApplicationIdle);
    }

    private void OnHostKey(object? sender, KeyEventArgs e)
    {
        // Cmd combinations (Cmd+Q, Cmd+W) still reach the window.
        if ((e.KeyModifiers & KeyModifiers.Meta) == 0) e.Handled = true;
        Leave(SkipExit);
    }

    private void OnSkipClick(object? sender, PointerPressedEventArgs e)
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
            Animate(duration, new CubicEaseIn());
            MarkScale.ScaleX = MarkScale.ScaleY = 1.06;
            Opacity = 0;
            DispatcherTimer.RunOnce(Remove, duration);
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
            _host.Opened -= OnOpened;
            _host.RemoveHandler(KeyDownEvent, OnHostKey);
        }
        RemoveHandler(PointerPressedEvent, OnSkipClick);
    }

    private void Remove()
    {
        Unhook();
        _host = null;
        IsVisible = false;
        (Parent as Panel)?.Children.Remove(this);
    }
}
