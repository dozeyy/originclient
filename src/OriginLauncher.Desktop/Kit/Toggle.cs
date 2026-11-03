using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Kit;

// The switch (11.8): a capsule track, off = rgba(255,255,255,.10) with a 70 % parchment knob, on = accent
// with an onAccent knob; the knob slides in motion.base. Settings uses 32 x 18 (knob 14); Home's MY MODS
// uses 36 x 20 (knob 14, inset 3, off = progress-track fill + 1 px edge). Click flips it and raises Toggled;
// IsOn set from code never raises it (one control, one job: only the player's click writes a setting).
public sealed class Toggle : Pressable
{
    private readonly Border _track;
    private readonly Ellipse _knob;
    private readonly double _travel;
    private readonly bool _home;
    private bool _on;

    public event Action<bool>? Toggled;

    public Toggle(string accessibleName, bool home = false)
    {
        _home = home;
        var w = home ? Sizes.HomeSwitchW : Sizes.SwitchW;
        var h = home ? Sizes.HomeSwitchH : Sizes.SwitchH;
        var knob = home ? Sizes.HomeSwitchKnob : Sizes.SwitchKnob;
        var inset = (h - knob) / 2;
        _travel = w - knob - inset * 2;
        Look = Look.Plain;
        Width = w;
        Height = h;
        VerticalAlignment = VerticalAlignment.Center;
        _knob = new Ellipse
        {
            Width = knob, Height = knob, HorizontalAlignment = HorizontalAlignment.Left,
            Margin = new Thickness(inset, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center,
        };
        _track = new Border { CornerRadius = new CornerRadius(h / 2), Child = _knob };
        if (!Motion.Off)
        {
            _knob.Transitions = [new TransformOperationsTransition { Property = RenderTransformProperty, Duration = Motion.Base }];
            _track.Transitions = [new BrushTransition { Property = Border.BackgroundProperty, Duration = Motion.Base }];
        }
        Child = _track;
        CornerRadius = new CornerRadius(h / 2);
        Avalonia.Automation.AutomationProperties.SetName(this, accessibleName);
        Click += (_, _) =>
        {
            _on = !_on;
            Paint();
            Toggled?.Invoke(_on);
        };
        StateChanged += _ => Paint();
        Paint();
    }

    public bool IsOn
    {
        get => _on;
        set { _on = value; Paint(); }
    }

    private void Paint()
    {
        if (_track == null) return;
        var enabled = IsEffectivelyEnabled;
        _track.Background = _on ? (enabled ? Palette.Accent : Palette.AccentSoft) : _home ? Palette.ProgressTrack : Palette.LineStrong;
        _track.BorderBrush = !_on && _home ? Palette.LineStrong : null;
        _track.BorderThickness = new Thickness(!_on && _home ? 1 : 0);
        _knob.Fill = _on ? Palette.OnAccent : Palette.SwitchKnobOff;
        _knob.RenderTransform = TransformOperations.Parse(_on ? $"translateX({Fmt.N(_travel, "0.##")}px)" : "translateX(0px)");
        Opacity = enabled ? 1 : 0.5;
    }
}

// The progress rail (11.11): 3 tall capsule, track rgba(255,255,255,.08), fill accent. Value in 0..1;
// NaN = indeterminate (a 28 % segment sliding across in 1.2 s, only while no real number exists).
public sealed class Rail : Control
{
    public static readonly StyledProperty<double> ValueProperty = AvaloniaProperty.Register<Rail, double>(nameof(Value));
    public static readonly StyledProperty<IBrush?> FillProperty = AvaloniaProperty.Register<Rail, IBrush?>(nameof(Fill));

    static Rail() => AffectsRender<Rail>(ValueProperty, FillProperty);

    public double Value { get => GetValue(ValueProperty); set => SetValue(ValueProperty, value); }
    public IBrush? Fill { get => GetValue(FillProperty); set => SetValue(FillProperty, value); }

    private readonly DateTime _born = DateTime.UtcNow;
    private Avalonia.Threading.DispatcherTimer? _timer;

    public Rail() { Height = Sizes.Progress; }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        if (Motion.Off) return;
        _timer = new Avalonia.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
        _timer.Tick += (_, _) => { if (double.IsNaN(Value)) InvalidateVisual(); };
        _timer.Start();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        _timer?.Stop();
        _timer = null;
    }

    public override void Render(DrawingContext context)
    {
        var w = Bounds.Width;
        var h = Bounds.Height;
        var r = h / 2;
        context.DrawRectangle(Palette.ProgressTrack, null, new Rect(0, 0, w, h), r, r);
        var fill = Fill ?? Palette.Accent;
        if (double.IsNaN(Value))
        {
            var seg = w * 0.28;
            var t = Motion.Off ? 0.36 : (DateTime.UtcNow - _born).TotalSeconds % 1.2 / 1.2;
            var eased = t < 0.5 ? 2 * t * t : 1 - Math.Pow(-2 * t + 2, 2) / 2;
            var x = -seg + (w + seg) * eased;
            using (context.PushClip(new Rect(0, 0, w, h)))
                context.DrawRectangle(fill, null, new Rect(x, 0, seg, h), r, r);
            return;
        }
        var v = Math.Clamp(Value, 0, 1);
        if (v > 0) context.DrawRectangle(fill, null, new Rect(0, 0, Math.Max(h, w * v), h), r, r);
    }
}

// The spinner (11.11): 14 (12 in sm), a 1.7 stroke 270-degree arc in textDim, 0.8 s per turn. Only
// inside controls waiting on the network. Static (pointing up) in render runs.
public sealed class LoadingSpinner : Control
{
    private readonly double _size;
    private Avalonia.Threading.DispatcherTimer? _timer;
    private readonly DateTime _born = DateTime.UtcNow;

    public IBrush? Brush { get; set; }

    public LoadingSpinner(double size = 14)
    {
        _size = size;
        Width = size;
        Height = size;
        VerticalAlignment = VerticalAlignment.Center;
        HorizontalAlignment = HorizontalAlignment.Center;
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        if (Motion.Off) return;
        _timer = new Avalonia.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
        _timer.Tick += (_, _) => InvalidateVisual();
        _timer.Start();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        _timer?.Stop();
        _timer = null;
    }

    public override void Render(DrawingContext context)
    {
        var angle = Motion.Off ? 0 : (DateTime.UtcNow - _born).TotalSeconds % 0.8 / 0.8 * 360;
        var r = (_size - Sizes.IconStroke) / 2;
        var c = new Point(_size / 2, _size / 2);
        var geo = new StreamGeometry();
        using (var g = geo.Open())
        {
            var a0 = (angle - 90) * Math.PI / 180;
            var a1 = a0 + 1.5 * Math.PI;
            g.BeginFigure(new Point(c.X + r * Math.Cos(a0), c.Y + r * Math.Sin(a0)), false);
            g.ArcTo(new Point(c.X + r * Math.Cos(a1), c.Y + r * Math.Sin(a1)), new Avalonia.Size(r, r), 0, true, SweepDirection.Clockwise);
            g.EndFigure(false);
        }
        context.DrawGeometry(null, new Pen(Brush ?? Avalonia.Controls.Documents.TextElement.GetForeground(this) ?? Palette.TextDim,
            Sizes.IconStroke, lineCap: PenLineCap.Round), geo);
    }
}
