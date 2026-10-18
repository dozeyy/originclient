using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The launch scene lifted out of the launcher (launch experience v2,
/// docs/MOTION.md): a borderless, click-through, transparent window holding a
/// copy of the loading scene. It opens exactly over the launcher's scene, then
/// glides and stretches onto the rect where the game opened its still-invisible
/// window, re-laying the scene out for the game's size on the way. The game
/// window then fades in over (or under) it showing the identical picture, and
/// <see cref="FadeOutAndClose"/> removes it. Never activated, never in the
/// taskbar, owned by the launcher so it can't end up behind it.
/// </summary>
internal sealed class SceneHandoffWindow : Window
{
    private readonly Canvas _canvas = new();
    private readonly LoadingScene _scene = new();
    private readonly Rect _from, _to;           // in this window's coordinates (DIPs)
    private readonly (double Unit, double Width, double Height) _fromLayout, _toLayout;
    private readonly int _toScale;
    private readonly double _glideMs;
    private long _glideAt;
    private bool _gliding;
    private bool _closing;

    /// <summary>Screen rects in DIPs. Layouts: DIPs per GUI unit and the screen in GUI units at each end.</summary>
    public SceneHandoffWindow(Window owner, Rect fromScreen, (double, double, double) fromLayout,
        Rect toScreen, (double, double, double) toLayout, int toScale, double? progress, Duration glide)
    {
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ResizeMode = ResizeMode.NoResize;
        ShowInTaskbar = false;
        ShowActivated = false;
        Focusable = false;
        Topmost = false;
        Owner = owner;

        // One fixed window over both ends: the scene moves inside it, so the
        // glide never resizes a window (which would stutter).
        var bounds = Rect.Union(fromScreen, toScreen);
        Left = bounds.Left;
        Top = bounds.Top;
        Width = bounds.Width;
        Height = bounds.Height;
        _from = Offset(fromScreen, bounds);
        _to = Offset(toScreen, bounds);
        _fromLayout = fromLayout;
        _toLayout = toLayout;
        _toScale = toScale;
        _glideMs = glide.HasTimeSpan ? glide.TimeSpan.TotalMilliseconds : 560;

        _scene.Progress = progress;
        _scene.LayoutOverride = fromLayout;
        Place(_from);
        _canvas.Children.Add(_scene);
        Content = _canvas;
        SourceInitialized += (_, _) => MakeClickThrough();
    }

    /// <summary>The bar keeps following the launcher's stage while the scene is out here.</summary>
    public double? Progress
    {
        get => _scene.Progress;
        set => _scene.Progress = value;
    }

    /// <summary>Starts the glide (call once the window has drawn its first frame over the launcher's scene).</summary>
    public void Glide()
    {
        if (_gliding || _closing) return;
        _gliding = true;
        _glideAt = Environment.TickCount64;
        CompositionTarget.Rendering += OnRendering;
    }

    /// <summary>The game window is showing the same scene now: dissolve this copy away and close.</summary>
    public void FadeOutAndClose(Duration duration)
    {
        if (_closing) return;
        _closing = true;
        if (!SystemParameters.ClientAreaAnimation || !IsLoaded)
        {
            Finish();
            return;
        }
        var fade = new DoubleAnimation(0, duration) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } };
        fade.Completed += (_, _) => Finish();
        BeginAnimation(OpacityProperty, fade);
    }

    private void Finish()
    {
        CompositionTarget.Rendering -= OnRendering;
        try { Close(); } catch (InvalidOperationException) { /* already closing */ }
    }

    private void OnRendering(object? sender, EventArgs e)
    {
        var t = Math.Clamp((Environment.TickCount64 - _glideAt) / _glideMs, 0, 1);
        if (t >= 1)
        {
            CompositionTarget.Rendering -= OnRendering;
            Place(_to);
            // Landed: lay out exactly as the game does at its size and scale.
            _scene.GuiScale = _toScale;
            _scene.LayoutOverride = null;
            return;
        }
        var k = t < 0.5 ? 4 * t * t * t : 1 - Math.Pow(-2 * t + 2, 3) / 2;
        Place(new Rect(
            Lerp(_from.X, _to.X, k), Lerp(_from.Y, _to.Y, k),
            Lerp(_from.Width, _to.Width, k), Lerp(_from.Height, _to.Height, k)));
        _scene.LayoutOverride = (
            Lerp(_fromLayout.Unit, _toLayout.Unit, k),
            Lerp(_fromLayout.Width, _toLayout.Width, k),
            Lerp(_fromLayout.Height, _toLayout.Height, k));
    }

    private void Place(Rect r)
    {
        Canvas.SetLeft(_scene, r.X);
        Canvas.SetTop(_scene, r.Y);
        _scene.Width = r.Width;
        _scene.Height = r.Height;
    }

    private static Rect Offset(Rect r, Rect origin) => new(r.X - origin.X, r.Y - origin.Y, r.Width, r.Height);
    private static double Lerp(double a, double b, double t) => a + (b - a) * t;

    // Clicks fall through to whatever is underneath (the launcher, the game),
    // and the window can never take focus or show in Alt+Tab.
    private void MakeClickThrough()
    {
        try
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            var style = GetWindowLong(hwnd, GwlExStyle);
            SetWindowLong(hwnd, GwlExStyle, style | WsExTransparent | WsExNoActivate | WsExToolWindow);
        }
        catch
        {
            // purely cosmetic: the window is gone within a second either way
        }
    }

    private const int GwlExStyle = -20;
    private const int WsExTransparent = 0x20;
    private const int WsExToolWindow = 0x80;
    private const int WsExNoActivate = 0x08000000;

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
    private static extern int GetWindowLong(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongW")]
    private static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);
}
