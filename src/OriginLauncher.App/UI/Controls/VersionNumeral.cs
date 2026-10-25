using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The giant version numeral on Home: Inter ExtraBold at its natural width (never squashed), -0.04 em tracking,
/// parchment ink at 94 % (Brush.NumeralInk), with the orbit thread (<see cref="OrbitThread"/>) behind it. It stands in the free band between the top bar's bottom edge and the Home strip's top edge
/// (<see cref="SetBand"/>, reported by HomePage), its figures centred in that band and its ink centred across the
/// window; size and placement are <see cref="NumeralBand"/>. A change rolls the old characters up and out and the
/// new ones in from below, 28 ms apart (mockup: 320 ms in / 420 ms out, 140 ms overlap).
/// The shaped text is cached per size: a resize that does not change the font size re-places the glyphs and
/// shapes nothing.
/// </summary>
public sealed class VersionNumeral : Canvas
{
    private readonly List<Glyph> _shown = new();
    private readonly OrbitThread _orbit = new();
    private string _text = "";

    // The band the numeral stands in (window coordinates); NaN until Home reports where its strip starts.
    private double _stripTop = double.NaN;
    private double _barBottom;

    // The shaped glyphs for the size on screen, keyed by character; dropped when the size or DPI changes.
    private readonly Dictionary<char, FormattedText> _shaped = new();
    private double _shapedSize;
    private double _shapedDip;
    private double _fontSize;

    public VersionNumeral()
    {
        IsHitTestVisible = false;
        ClipToBounds = false;
        _barBottom = (double)Application.Current.FindResource("Size.Bar");
        Children.Add(_orbit); // first child: behind every figure
        SizeChanged += (_, _) => Layout(_shown);
    }

    /// <summary>Home's strip starts at <paramref name="stripTop"/> (window coordinates); the bar ends at the bar token.</summary>
    public void SetBand(double stripTop)
    {
        if (!double.IsNaN(_stripTop) && Math.Abs(stripTop - _stripTop) < 0.5) return;
        _stripTop = stripTop;
        Layout(_shown);
    }

    internal void Relayout() => Layout(_shown);

    /// <summary>The ink rectangle of the text on screen, for the render tour's measurements (null before layout).</summary>
    internal Rect? InkRect { get; private set; }

    /// <summary>The orbit thread's ring size (length, thickness, end radius), for the render tour's measurements.</summary>
    internal (double Length, double Thickness, double Radius) OrbitRing => _orbit.Ring;

    /// <summary>The band in use (top, bottom), for the render tour's measurements.</summary>
    internal (double Top, double Bottom) CurrentBand => NumeralBand.Band(ActualHeight, _barBottom, _stripTop);

    public void SetText(string text, bool animate)
    {
        if (text == _text && _shown.Count > 0) return;
        _text = text;
        var old = _shown.ToList();
        _shown.Clear();
        foreach (var ch in text)
        {
            var g = new Glyph(ch);
            _shown.Add(g);
            Children.Add(g);
        }
        Layout(_shown);

        if (!animate || Motion.Off)
        {
            foreach (var g in old) Children.Remove(g);
            return;
        }
        var lift = _fontSize * 0.17;
        for (var i = 0; i < old.Count; i++)
        {
            var g = old[i];
            g.Shift.BeginAnimation(TranslateTransform.YProperty, Motion.FromTo(0, -lift, 320, Motion.In, i * 28));
            var fade = Motion.FromTo(1, 0, 320, Motion.In, i * 28);
            if (i == old.Count - 1) fade.Completed += (_, _) => { foreach (var o in old) Children.Remove(o); };
            g.BeginAnimation(OpacityProperty, fade);
        }
        var drop = _fontSize * 0.2;
        for (var i = 0; i < _shown.Count; i++)
        {
            var g = _shown[i];
            g.Opacity = 0;
            g.Shift.BeginAnimation(TranslateTransform.YProperty, Motion.FromTo(drop, 0, 420, Motion.Out, 140 + i * 28));
            g.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 420, Motion.Out, 140 + i * 28));
        }
    }

    private void Layout(List<Glyph> glyphs)
    {
        if (glyphs.Count == 0 || ActualWidth <= 0 || ActualHeight <= 0) return;
        var (bandTop, bandBottom) = NumeralBand.Band(ActualHeight, _barBottom, _stripTop);

        // Advances and side bearings come from the face's own tables (em fractions): no text is shaped to size it.
        var first = Face.Metrics(_text[0]);
        var last = Face.Metrics(_text[^1]);
        var inkEm = -first.Left - last.Right;
        for (var i = 0; i < _text.Length; i++)
            inkEm += Face.Metrics(_text[i]).Advance + (i < _text.Length - 1 ? NumeralBand.Tracking : 0);

        var fs = NumeralBand.FontSize(ActualWidth, ActualHeight, bandTop, bandBottom, Face.CapsEm, inkEm);
        _fontSize = fs;
        var dip = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        if (Math.Abs(fs - _shapedSize) > 0.01 || dip != _shapedDip)
        {
            _shaped.Clear();
            _shapedSize = fs;
            _shapedDip = dip;
        }

        var x = NumeralBand.InkLeft(ActualWidth, inkEm * fs) - first.Left * fs;
        var inkLeft = x + first.Left * fs;
        double baseline = 0;
        foreach (var g in glyphs)
        {
            if (!_shaped.TryGetValue(g.Char, out var ft))
                _shaped[g.Char] = ft = Face.Shape(g.Char, fs, dip);
            baseline = ft.Baseline;
            var advance = Face.Metrics(g.Char).Advance * fs;
            g.Apply(ft, advance + NumeralBand.Tracking * fs);
            SetLeft(g, x);
            x += advance + NumeralBand.Tracking * fs;
        }

        // The middle of the figures (baseline minus half the cap height) on the middle of the band.
        var top = NumeralBand.MiddleY(bandTop, bandBottom) - (baseline - Face.CapsEm * fs / 2);
        foreach (var g in glyphs) SetTop(g, top);
        var capTop = top + baseline - Face.CapsEm * fs;
        InkRect = new Rect(inkLeft, capTop, inkEm * fs, Face.CapsEm * fs);
        // The thread is centred on the figures' middle; its capsules frame them (OrbitLengthOfInk, OrbitThicknessOfFigures).
        _orbit.Place(inkLeft + inkEm * fs / 2, capTop + Face.CapsEm * fs / 2,
            inkEm * fs * NumeralBand.OrbitLengthOfInk, Face.CapsEm * fs * NumeralBand.OrbitThicknessOfFigures);
    }

    private sealed class Glyph : FrameworkElement
    {
        public readonly char Char;
        private FormattedText? _ft;
        public readonly TranslateTransform Shift = new();

        public Glyph(char ch)
        {
            Char = ch;
            RenderTransform = Shift;
        }

        public void Apply(FormattedText ft, double width)
        {
            if (ReferenceEquals(_ft, ft) && Width == width) return;
            _ft = ft;
            Width = width;
            Height = ft.Height;
            InvalidateVisual();
        }

        protected override void OnRender(DrawingContext dc)
        {
            if (_ft != null) dc.DrawText(_ft, new Point(0, 0));
        }
    }

    /// <summary>The numeral face (Inter ExtraBold) and its metrics, resolved once.</summary>
    private static class Face
    {
        private static Typeface? _face;
        private static GlyphTypeface? _glyphs;
        private static Brush? _ink;
        private static double _caps;
        private static readonly Dictionary<char, (double Advance, double Left, double Right)> Cache = new();

        private static Typeface Typeface =>
            _face ??= new Typeface((FontFamily)Application.Current.FindResource("Font.Primary"),
                FontStyles.Normal, FontWeights.ExtraBold, FontStretches.Normal);

        /// <summary>The figures' height as a fraction of the em (0.73 if the face can't say).</summary>
        public static double CapsEm
        {
            get
            {
                if (_caps > 0) return _caps;
                _glyphs ??= Typeface.TryGetGlyphTypeface(out var gt) ? gt : null;
                return _caps = _glyphs?.CapsHeight is > 0 ? _glyphs.CapsHeight : 0.73;
            }
        }

        /// <summary>Advance and the left / right side bearings of <paramref name="ch"/>, in em.</summary>
        public static (double Advance, double Left, double Right) Metrics(char ch)
        {
            if (Cache.TryGetValue(ch, out var m)) return m;
            _ = CapsEm; // resolves _glyphs
            if (_glyphs != null && _glyphs.CharacterToGlyphMap.TryGetValue(ch, out var index))
                m = (_glyphs.AdvanceWidths[index], _glyphs.LeftSideBearings[index], _glyphs.RightSideBearings[index]);
            else
                m = (Shape(ch, 100, 1).WidthIncludingTrailingWhitespace / 100, 0, 0);
            return Cache[ch] = m;
        }

        public static FormattedText Shape(char ch, double size, double pixelsPerDip) =>
            new(ch.ToString(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, Typeface, size,
                _ink ??= (Brush)Application.Current.FindResource("Brush.NumeralInk"), pixelsPerDip);
    }
}
