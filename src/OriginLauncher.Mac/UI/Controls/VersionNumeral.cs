using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// The giant version numeral on Home (twin of the WPF UI/Controls/VersionNumeral.cs): Inter ExtraBold at its natural
/// width, -0.04 em tracking, parchment ink at 94 % (Brush.NumeralInk), alone on the art. It stands in the band
/// between the top bar and the Home strip, figures centred in that band and ink centred across the window; size and
/// placement are the shared <see cref="NumeralBand"/> maths (the same file the WPF launcher compiles). Nothing is
/// drawn around it and it carries no shadow effect (DECISIONS, Look).
///
/// The face's per-character metrics (advance, side bearings, cap height) are read once from the shaped outline at a
/// reference size; the WPF control reads the same numbers from the font's tables.
/// Not ported yet: the roll-up / roll-in animation on a version change (Mac Home has no picker yet).
/// </summary>
public sealed class VersionNumeral : Control
{
    private string _text = "";
    private double _stripTop = double.NaN;
    private readonly double _barBottom;

    private readonly Dictionary<char, FormattedText> _shaped = new();
    private double _shapedSize;

    public VersionNumeral()
    {
        IsHitTestVisible = false;
        ClipToBounds = false;
        _barBottom = Application.Current?.TryGetResource("Size.Bar", null, out var bar) == true && bar is double b ? b : 48;
    }

    /// <summary>The ink rectangle of the text on screen, for the render shots' measurements (null before layout).</summary>
    internal Rect? InkRect { get; private set; }

    /// <summary>Home's strip starts at <paramref name="stripTop"/> (window coordinates); the bar ends at the bar token.</summary>
    public void SetBand(double stripTop)
    {
        if (!double.IsNaN(_stripTop) && Math.Abs(stripTop - _stripTop) < 0.5) return;
        _stripTop = stripTop;
        InvalidateVisual();
    }

    public void SetText(string text)
    {
        if (text == _text) return;
        _text = text;
        InvalidateVisual();
    }

    protected override void OnSizeChanged(SizeChangedEventArgs e)
    {
        base.OnSizeChanged(e);
        InvalidateVisual();
    }

    public override void Render(DrawingContext context)
    {
        var w = Bounds.Width;
        var h = Bounds.Height;
        if (_text.Length == 0 || w <= 0 || h <= 0) { InkRect = null; return; }
        var (bandTop, bandBottom) = NumeralBand.Band(h, _barBottom, _stripTop);

        var first = Face.Metrics(_text[0]);
        var last = Face.Metrics(_text[^1]);
        var inkEm = -first.Left - last.Right;
        for (var i = 0; i < _text.Length; i++)
            inkEm += Face.Metrics(_text[i]).Advance + (i < _text.Length - 1 ? NumeralBand.Tracking : 0);

        var fs = NumeralBand.FontSize(w, h, bandTop, bandBottom, Face.CapsEm, inkEm);
        if (Math.Abs(fs - _shapedSize) > 0.01)
        {
            _shaped.Clear();
            _shapedSize = fs;
        }

        var x = NumeralBand.InkLeft(w, inkEm * fs) - first.Left * fs;
        var inkLeft = x + first.Left * fs;
        double baseline = 0;
        var placed = new List<(FormattedText Ft, double X)>(_text.Length);
        foreach (var ch in _text)
        {
            if (!_shaped.TryGetValue(ch, out var ft)) _shaped[ch] = ft = Face.Shape(ch, fs);
            baseline = ft.Baseline;
            placed.Add((ft, x));
            x += (Face.Metrics(ch).Advance + NumeralBand.Tracking) * fs;
        }

        // The middle of the figures (baseline minus half the cap height) on the middle of the band.
        var top = NumeralBand.MiddleY(bandTop, bandBottom) - (baseline - Face.CapsEm * fs / 2);
        foreach (var (ft, gx) in placed) context.DrawText(ft, new Point(gx, top));
        InkRect = new Rect(inkLeft, top + baseline - Face.CapsEm * fs, inkEm * fs, Face.CapsEm * fs);
    }

    /// <summary>The numeral face (Inter ExtraBold) and its metrics, resolved once.</summary>
    private static class Face
    {
        private const double Ref = 200;   // the reference size the metrics are read at
        private static Typeface? _face;
        private static IBrush? _ink;
        private static double _caps;
        private static readonly Dictionary<char, (double Advance, double Left, double Right)> Cache = new();

        private static Typeface Typeface => _face ??= new Typeface(
            Application.Current?.TryGetResource("Font.Primary", null, out var f) == true && f is FontFamily ff ? ff : FontFamily.Default,
            FontStyle.Normal, FontWeight.ExtraBold, FontStretch.Normal);

        private static IBrush Ink => _ink ??=
            Application.Current?.TryGetResource("Brush.NumeralInk", null, out var b) == true && b is IBrush ib ? ib : Brushes.White;

        /// <summary>The figures' height as a fraction of the em: the ink height of a flat-topped capital.</summary>
        public static double CapsEm
        {
            get
            {
                if (_caps > 0) return _caps;
                var ink = Shape('H', Ref).BuildGeometry(new Point(0, 0))?.Bounds;
                return _caps = ink is { Height: > 0 } r ? r.Height / Ref : 0.73;
            }
        }

        /// <summary>Advance and the left / right side bearings of <paramref name="ch"/>, in em.</summary>
        public static (double Advance, double Left, double Right) Metrics(char ch)
        {
            if (Cache.TryGetValue(ch, out var m)) return m;
            var ft = Shape(ch, Ref);
            var advance = ft.WidthIncludingTrailingWhitespace;
            var ink = ft.BuildGeometry(new Point(0, 0))?.Bounds;
            m = ink is { Width: > 0 } r
                ? (advance / Ref, r.X / Ref, (advance - r.Right) / Ref)
                : (advance / Ref, 0, 0);
            return Cache[ch] = m;
        }

        public static FormattedText Shape(char ch, double size) =>
            new(ch.ToString(), CultureInfo.InvariantCulture, FlowDirection.LeftToRight, Typeface, size, Ink);
    }
}
