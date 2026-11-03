using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media.Transformation;
using Avalonia.Threading;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// The opening animation (port of WPF UI/Controls/StartupIntro.xaml(.cs)): the Origin logo file (Ui.Logo, never
// redrawn) fades in and eases up from 94 % on the bg colour, then the whole layer fades away to show the launcher
// that MainWindow already built underneath.
// - Short and skippable: about 0.75 s (0.54 s rise, 0.2 s fade); any click or key skips it at once (0.12 s), and
//   a key pressed during it goes no further, so an Enter can't press Play on a screen the player can't see yet.
// - Honest: no progress bar. It leaves at max(animation end, UI ready), "ready" being the window opened and the
//   dispatcher idle; never later than the 2.2 s hard cap.
// - Reduced motion (Motion.Off, render runs too): no intro at all.
// - Fail-soft: any exception removes the layer; it removes itself from the tree when done.
public sealed class StartupIntro : Panel
{
    private static readonly double RiseMs = 540, ExitMs = 200, SkipExitMs = 120, HardCapMs = 2200;
    private static readonly TransformOperations Small = TransformOperations.Parse("scale(0.94)");
    private static readonly TransformOperations Full = TransformOperations.Parse("scale(1)");
    private static readonly TransformOperations Lift = TransformOperations.Parse("scale(1.06)");

    private readonly Control _mark = Ui.Logo(Sizes.LogoIntro);
    private MainWindow? _host;
    private IDisposable? _cap;
    private bool _animDone, _ready, _leaving;

    private StartupIntro()
    {
        Background = Palette.Bg;
        _mark.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
        _mark.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
        _mark.RenderTransformOrigin = RelativePoint.Center;
        Children.Add(_mark);
    }

    /// <summary>MainWindow calls this once from its constructor, after Home is built.</summary>
    public static void Play(MainWindow window)
    {
        if (Motion.Off || window.Content is not Panel root) return;
        var intro = new StartupIntro();
        try
        {
            root.Children.Add(intro);
            intro.Start(window);
        }
        catch (Exception)
        {
            intro.Remove();
        }
    }

    /// <summary>Render runs: the intro's resting frame (logo full size, layer opaque), for the 00-startup-intro shot.</summary>
    public static void ShowStill(MainWindow window)
    {
        if (window.Content is Panel root) root.Children.Add(new StartupIntro());
    }

    private void Start(MainWindow window)
    {
        _host = window;
        window.Opened += OnOpened;
        window.AddHandler(KeyDownEvent, OnHostKey, RoutingStrategies.Tunnel);
        PointerPressed += OnSkipClick;

        _cap = DispatcherTimer.RunOnce(() => Leave(ExitMs), TimeSpan.FromMilliseconds(HardCapMs));

        _mark.Opacity = 0;
        _mark.RenderTransform = Small;
        var ease = new CubicEaseOut();
        _mark.Transitions =
        [
            new DoubleTransition { Property = OpacityProperty, Duration = TimeSpan.FromMilliseconds(RiseMs), Easing = ease },
            new TransformOperationsTransition { Property = RenderTransformProperty, Duration = TimeSpan.FromMilliseconds(RiseMs), Easing = ease },
        ];
        // Start the rise once the layer is on screen, so the first frame is the small, clear logo.
        Dispatcher.UIThread.Post(() =>
        {
            if (_leaving) return;
            _mark.Opacity = 1;
            _mark.RenderTransform = Full;
            DispatcherTimer.RunOnce(() => { _animDone = true; TryLeave(); }, TimeSpan.FromMilliseconds(RiseMs));
        }, DispatcherPriority.Render);
    }

    private void OnOpened(object? sender, EventArgs e)
    {
        if (_host != null) _host.Opened -= OnOpened;
        // Opened once; "ready" is when the queued startup work has drained too.
        Dispatcher.UIThread.Post(() => { _ready = true; TryLeave(); }, DispatcherPriority.ApplicationIdle);
    }

    private void OnHostKey(object? sender, KeyEventArgs e)
    {
        // Alt combinations (Alt+F4) still reach the window.
        if ((e.KeyModifiers & KeyModifiers.Alt) == 0 && e.Key is not (Key.LeftAlt or Key.RightAlt)) e.Handled = true;
        Leave(SkipExitMs);
    }

    private void OnSkipClick(object? sender, PointerPressedEventArgs e)
    {
        e.Handled = true;
        Leave(SkipExitMs);
    }

    private void TryLeave()
    {
        if (_animDone && _ready) Leave(ExitMs);
    }

    private void Leave(double ms)
    {
        if (_leaving) return;
        _leaving = true;
        Unhook();
        // From here the launcher underneath takes input straight away.
        IsHitTestVisible = false;
        try
        {
            var ease = new CubicEaseIn();
            var d = TimeSpan.FromMilliseconds(ms);
            Transitions = [new DoubleTransition { Property = OpacityProperty, Duration = d, Easing = ease }];
            _mark.Transitions = [new TransformOperationsTransition { Property = RenderTransformProperty, Duration = d, Easing = ease }];
            Opacity = 0;
            _mark.RenderTransform = Lift;
            DispatcherTimer.RunOnce(Remove, d);
        }
        catch (Exception)
        {
            Remove();
        }
    }

    private void Unhook()
    {
        _cap?.Dispose();
        _cap = null;
        if (_host != null)
        {
            _host.Opened -= OnOpened;
            _host.RemoveHandler(KeyDownEvent, OnHostKey);
        }
        PointerPressed -= OnSkipClick;
    }

    private void Remove()
    {
        Unhook();
        _host = null;
        IsVisible = false;
        (Parent as Panel)?.Children.Remove(this);
    }
}
