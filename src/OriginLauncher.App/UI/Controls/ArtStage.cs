using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The Poster backdrop behind every page: the version family's art in greyscale,
/// darkened by scrims, with the giant version numeral at 15% ink in front of it.
///
/// Layers (back to front): two grey art layers (the family crossfade swaps
/// between them), a colour layer (the launch bloom), the numeral, the top and
/// bottom scrims (7:1 text over art), and the page dim (every page but Home).
///
/// Art is decoded off the UI thread, frozen, and sized to the window (1280 or
/// 2560 wide). Motion is opacity, translate and scale only; the slow 44 s drift
/// runs at 15 fps (nothing in it moves faster than a fraction of a pixel a frame).
/// </summary>
public sealed class ArtStage : Grid
{
    public static ArtStage? Instance { get; private set; }

    private readonly Grid _stage = new() { RenderTransformOrigin = new Point(0.5, 0.5) };
    private readonly ScaleTransform _launchZoom = new();
    private readonly TranslateTransform _drift = new();
    private readonly ScaleTransform _driftScale = new(1.05, 1.05);
    private readonly Layer[] _grey = [new(), new()];
    private readonly Layer _colour = new();
    private int _front;
    private readonly VersionNumeral _numeral = new();
    private readonly Border _dim;
    private string? _artKey;
    private bool _wide;
    private bool _home = true;

    private sealed class Layer
    {
        public readonly Image Image = new() { Stretch = Stretch.UniformToFill, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        public readonly TranslateTransform Shift = new();
        public readonly ScaleTransform Scale = new();
        public Layer()
        {
            Image.RenderTransformOrigin = new Point(0.5, 0.5);
            Image.RenderTransform = new TransformGroup { Children = { Scale, Shift } };
            RenderOptions.SetBitmapScalingMode(Image, BitmapScalingMode.HighQuality);
        }
    }

    public ArtStage()
    {
        Instance = this;
        ClipToBounds = true;
        IsHitTestVisible = false;
        Background = (Brush)Application.Current.FindResource("Brush.Bg");

        _stage.RenderTransform = new TransformGroup { Children = { _driftScale, _drift, _launchZoom } };
        _colour.Image.Opacity = 0;
        _grey[1].Image.Opacity = 0;
        _stage.Children.Add(_grey[0].Image);
        _stage.Children.Add(_grey[1].Image);
        _stage.Children.Add(_colour.Image);
        Children.Add(_stage);

        Children.Add(_numeral);

        Children.Add(new Border
        {
            Height = 140, VerticalAlignment = VerticalAlignment.Top,
            Background = (Brush)Application.Current.FindResource("Brush.ScrimTop"),
        });
        Children.Add(new Border
        {
            Height = 330, VerticalAlignment = VerticalAlignment.Bottom,
            Background = (Brush)Application.Current.FindResource("Brush.ScrimBottom"),
        });
        _dim = new Border { Background = (Brush)Application.Current.FindResource("Brush.PageDim"), Opacity = 0 };
        Children.Add(_dim);

        SizeChanged += (_, _) => PickResolution();
        Loaded += (_, _) => StartDrift();
    }

    /// <summary>Show the family of <paramref name="version"/> and its numeral (first frame: no animation).</summary>
    public void Show(string? version, bool animate)
    {
        _numeral.SetText(version ?? "", animate);
        var key = FamilyTheme.GroupOf(version).Key;
        if (key == _artKey) return;
        var first = _artKey == null;
        _artKey = key;
        if (first || !animate || Motion.Off)
        {
            Load(_grey[_front].Image, key, "g", fadeIn: first && !Motion.Off);
            Load(_colour.Image, key, "c", fadeIn: false);
            return;
        }

        // Old layer drifts left and fades; the new one slides in from the right, settling from 1.06.
        var old = _grey[_front];
        _front = 1 - _front;
        var next = _grey[_front];
        Panel.SetZIndex(old.Image, 0);
        Panel.SetZIndex(next.Image, 1);
        Load(next.Image, key, "g", fadeIn: false, then: () =>
        {
            next.Image.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 700));
            next.Shift.BeginAnimation(TranslateTransform.XProperty, Motion.FromTo(48, 0, 700));
            next.Scale.BeginAnimation(ScaleTransform.ScaleXProperty, Motion.FromTo(1.06, 1, 700));
            next.Scale.BeginAnimation(ScaleTransform.ScaleYProperty, Motion.FromTo(1.06, 1, 700));
            old.Image.BeginAnimation(OpacityProperty, Motion.FromTo(1, 0, 560));
            old.Shift.BeginAnimation(TranslateTransform.XProperty, Motion.FromTo(0, -48, 560));
        });
        Load(_colour.Image, key, "c", fadeIn: false);
    }

    /// <summary>Home shows the numeral and clear art; every other page dims both.</summary>
    public void SetPage(bool home)
    {
        if (home == _home) return;
        _home = home;
        _dim.BeginAnimation(OpacityProperty, Motion.To(home ? 0 : 1, 260));
        _numeral.BeginAnimation(OpacityProperty, Motion.To(home ? 1 : 0, 260));
    }

    /// <summary>The launch bloom: the art fades to colour (900 ms) and slowly pushes in to 1.08 (12 s).</summary>
    public void BeginLaunch()
    {
        _colour.Image.BeginAnimation(OpacityProperty, Motion.To(1, 900));
        _numeral.BeginAnimation(OpacityProperty, Motion.To(0, 260));
        var zoom = new DoubleAnimation(1.08, Motion.Ms(12000));
        _launchZoom.BeginAnimation(ScaleTransform.ScaleXProperty, zoom);
        _launchZoom.BeginAnimation(ScaleTransform.ScaleYProperty, zoom);
    }

    public void EndLaunch()
    {
        _colour.Image.BeginAnimation(OpacityProperty, Motion.To(0, 420));
        if (_home) _numeral.BeginAnimation(OpacityProperty, Motion.To(1, 420));
        _launchZoom.BeginAnimation(ScaleTransform.ScaleXProperty, Motion.To(1, 600));
        _launchZoom.BeginAnimation(ScaleTransform.ScaleYProperty, Motion.To(1, 600));
    }

    private void StartDrift()
    {
        if (Motion.Off) return;
        Animate(_drift, TranslateTransform.XProperty, -0.012, 0.012, w: true);
        Animate(_drift, TranslateTransform.YProperty, 0.008, -0.008, w: false);
        var s = new DoubleAnimation(1.05, 1.10, new Duration(TimeSpan.FromSeconds(44)))
        { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = Motion.InOut };
        Timeline.SetDesiredFrameRate(s, 15);
        _driftScale.BeginAnimation(ScaleTransform.ScaleXProperty, s);
        _driftScale.BeginAnimation(ScaleTransform.ScaleYProperty, s);

        void Animate(TranslateTransform t, DependencyProperty p, double from, double to, bool w)
        {
            var size = w ? Math.Max(ActualWidth, 960) : Math.Max(ActualHeight, 600);
            var a = new DoubleAnimation(from * size, to * size, new Duration(TimeSpan.FromSeconds(44)))
            { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = Motion.InOut };
            Timeline.SetDesiredFrameRate(a, 15);
            t.BeginAnimation(p, a);
        }
    }

    // 2560 art once the window is wider than 1400 physical pixels; reload the shown family if that flips.
    private void PickResolution()
    {
        var dpi = VisualTreeHelper.GetDpi(this).DpiScaleX;
        var wide = ActualWidth * dpi > 1400;
        if (wide == _wide) return;
        _wide = wide;
        if (_artKey == null) return;
        Load(_grey[_front].Image, _artKey, "g", fadeIn: false);
        Load(_colour.Image, _artKey, "c", fadeIn: false);
    }

    private static readonly Dictionary<string, BitmapSource> Cache = new();

    private void Load(Image target, string key, string kind, bool fadeIn, Action? then = null)
    {
        var name = $"{key}-{kind}{(_wide ? "2560" : "1280")}.jpg";
        if (Cache.TryGetValue(name, out var hit) || Motion.Off && (hit = Decode(name)) != null)
        {
            if (hit != null) Cache[name] = hit;
            Set(target, hit, fadeIn, then);
            return;
        }
        _ = System.Threading.Tasks.Task.Run(() => Decode(name)).ContinueWith(t =>
        {
            if (t.Result is { } bmp)
            {
                Cache[name] = bmp;
                // Only the newest request for this layer lands.
                if (_artKey == key) Set(target, bmp, fadeIn, then);
            }
        }, System.Threading.Tasks.TaskScheduler.FromCurrentSynchronizationContext());
    }

    private static void Set(Image target, BitmapSource? bmp, bool fadeIn, Action? then)
    {
        target.Source = bmp;
        if (fadeIn) target.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 320));
        then?.Invoke();
    }

    // Off the UI thread is fine: the stream is read whole and the bitmap frozen.
    internal static BitmapSource? Decode(string name, int decodeWidth = 0)
    {
        try
        {
            var info = Application.GetResourceStream(new Uri($"pack://application:,,,/Assets/Art/{name}", UriKind.Absolute));
            if (info == null) return null;
            using var stream = info.Stream;
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            if (decodeWidth > 0) bmp.DecodePixelWidth = decodeWidth;
            bmp.StreamSource = stream;
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch
        {
            return null; // fail soft: the dark background stays
        }
    }
}

/// <summary>
/// The giant version numeral: Inter ExtraBold narrowed to 70%, filling 90% of the
/// width (at most 60% of the height), centred, its digits' middle at 42% of the
/// height. A change rolls the old characters up and out and the new ones in from
/// below, 28 ms apart (mockup: 320 ms in / 420 ms out, 140 ms overlap).
/// </summary>
public sealed class VersionNumeral : Canvas
{
    private const double Narrow = 0.7;
    private readonly List<Glyph> _shown = new();
    private string _text = "";

    public VersionNumeral()
    {
        IsHitTestVisible = false;
        ClipToBounds = false;
        SizeChanged += (_, _) => Layout(_shown);
    }

    public void SetText(string text, bool animate)
    {
        if (text == _text && _shown.Count > 0) return;
        _text = text;
        var old = _shown.ToList();
        _shown.Clear();
        foreach (var ch in text)
        {
            var g = new Glyph(ch.ToString(), this);
            _shown.Add(g);
            Children.Add(g);
        }
        Layout(_shown);

        if (!animate || Motion.Off)
        {
            foreach (var g in old) Children.Remove(g);
            return;
        }
        var lift = FontSize() * 0.17;
        for (var i = 0; i < old.Count; i++)
        {
            var g = old[i];
            g.Shift.BeginAnimation(TranslateTransform.YProperty, Motion.FromTo(0, -lift, 320, Motion.In, i * 28));
            var fade = Motion.FromTo(1, 0, 320, Motion.In, i * 28);
            if (i == old.Count - 1) fade.Completed += (_, _) => { foreach (var o in old) Children.Remove(o); };
            g.BeginAnimation(OpacityProperty, fade);
        }
        var drop = FontSize() * 0.2;
        for (var i = 0; i < _shown.Count; i++)
        {
            var g = _shown[i];
            g.Opacity = 0;
            g.Shift.BeginAnimation(TranslateTransform.YProperty, Motion.FromTo(drop, 0, 420, Motion.Out, 140 + i * 28));
            g.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 420, Motion.Out, 140 + i * 28));
        }
    }

    private double FontSize()
    {
        var w = ActualWidth > 0 ? ActualWidth : 1180;
        var h = ActualHeight > 0 ? ActualHeight : 740;
        var at100 = Measure100(_text);
        return Math.Min(at100 > 0 ? w * 0.9 / at100 * 100 : h * 0.6, h * 0.6);
    }

    private double Measure100(string text)
    {
        double w = 0;
        foreach (var ch in text) w += Glyph.Shape(ch.ToString(), 100, this).WidthIncludingTrailingWhitespace * Narrow;
        return w;
    }

    private void Layout(List<Glyph> glyphs)
    {
        if (glyphs.Count == 0 || ActualWidth <= 0) return;
        var fs = FontSize();
        var total = 0.0;
        foreach (var g in glyphs) { g.Size(fs); total += g.Width; }
        var x = (ActualWidth - total) / 2;
        // Put the middle of the digits (baseline minus half the cap height) at 42% of the height.
        var sample = Glyph.Shape("1", fs, this);
        var caps = sample.CapsHeight();
        var top = ActualHeight * 0.42 - (sample.Baseline - caps * fs / 2);
        foreach (var g in glyphs)
        {
            SetLeft(g, x);
            SetTop(g, top);
            x += g.Width;
        }
    }

    private sealed class Glyph : FrameworkElement
    {
        private readonly string _ch;
        private readonly Visual _host;
        private FormattedText? _ft;
        public readonly TranslateTransform Shift = new();

        public Glyph(string ch, Visual host)
        {
            _ch = ch;
            _host = host;
            RenderTransform = Shift;
        }

        public void Size(double fs)
        {
            _ft = Shape(_ch, fs, _host);
            Width = _ft.WidthIncludingTrailingWhitespace * Narrow;
            Height = _ft.Height;
            InvalidateVisual();
        }

        public static FormattedText Shape(string ch, double fs, Visual host)
        {
            var family = (FontFamily)Application.Current.FindResource("Font.Display");
            var face = new Typeface(family, FontStyles.Normal, FontWeights.ExtraBold, FontStretches.Normal);
            return new FormattedText(ch, CultureInfo.InvariantCulture, FlowDirection.LeftToRight, face, fs,
                (Brush)Application.Current.FindResource("Brush.NumeralInk"), VisualTreeHelper.GetDpi(host).PixelsPerDip);
        }

        protected override void OnRender(DrawingContext dc)
        {
            if (_ft == null) return;
            dc.PushTransform(new ScaleTransform(Narrow, 1));
            dc.DrawText(_ft, new Point(0, 0));
            dc.Pop();
        }
    }
}

internal static class FormattedTextCaps
{
    /// <summary>The numeral face's cap height as a fraction of the em (0.73 if the face can't say).</summary>
    public static double CapsHeight(this FormattedText _)
    {
        var family = (FontFamily)Application.Current.FindResource("Font.Display");
        var face = new Typeface(family, FontStyles.Normal, FontWeights.ExtraBold, FontStretches.Normal);
        return face.TryGetGlyphTypeface(out var gt) ? gt.CapsHeight : 0.73;
    }
}
