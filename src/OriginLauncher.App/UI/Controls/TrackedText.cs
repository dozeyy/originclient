using System.Globalization;
using System.Windows;
using System.Windows.Automation.Peers;
using System.Windows.Documents;
using System.Windows.Media;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// One line of text with letter-spacing (WPF's TextBlock has none). The Poster
/// type uses tracked caps everywhere: eyebrows (.14em), nav tabs, PLAY (.05em).
/// Draws each character's FormattedText with <see cref="Tracking"/> em of extra
/// advance after every character but the last. Digits are tabular: each sits centred
/// in one shared cell (the widest digit), so "26.3", "1.21.11" and a counting
/// percentage never jitter or reflow as they change. Glyphs are never stretched
/// (a horizontal squish made the caps look smeared; ScaleX stays for callers that
/// truly need it, default 1). Font, size, weight and colour inherit like a
/// TextBlock's. Never wraps or trims: size its slot to fit.
/// </summary>
public sealed class TrackedText : FrameworkElement
{
    public static readonly DependencyProperty TextProperty = DependencyProperty.Register(
        nameof(Text), typeof(string), typeof(TrackedText),
        new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender, (d, _) => ((TrackedText)d)._glyphs = null));

    public static readonly DependencyProperty TrackingProperty = DependencyProperty.Register(
        nameof(Tracking), typeof(double), typeof(TrackedText),
        new FrameworkPropertyMetadata(0.14, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty ScaleXProperty = DependencyProperty.Register(
        nameof(ScaleX), typeof(double), typeof(TrackedText),
        new FrameworkPropertyMetadata(1.0, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender));

    /// <summary>Digits in one shared cell (true, for counters that change) or at their own
    /// width (false, for a fixed label such as a version, where "1.21.11" would look gappy).</summary>
    public static readonly DependencyProperty TabularDigitsProperty = DependencyProperty.Register(
        nameof(TabularDigits), typeof(bool), typeof(TrackedText),
        new FrameworkPropertyMetadata(true, FrameworkPropertyMetadataOptions.AffectsMeasure | FrameworkPropertyMetadataOptions.AffectsRender, Invalidate));

    public static readonly DependencyProperty FontFamilyProperty = TextElement.FontFamilyProperty.AddOwner(typeof(TrackedText),
        new FrameworkPropertyMetadata(SystemFonts.MessageFontFamily, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsMeasure, Invalidate));
    public static readonly DependencyProperty FontSizeProperty = TextElement.FontSizeProperty.AddOwner(typeof(TrackedText),
        new FrameworkPropertyMetadata(12.0, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsMeasure, Invalidate));
    public static readonly DependencyProperty FontWeightProperty = TextElement.FontWeightProperty.AddOwner(typeof(TrackedText),
        new FrameworkPropertyMetadata(FontWeights.Normal, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsMeasure, Invalidate));
    public static readonly DependencyProperty ForegroundProperty = TextElement.ForegroundProperty.AddOwner(typeof(TrackedText),
        new FrameworkPropertyMetadata(SystemColors.ControlTextBrush, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsRender, Invalidate));

    public string Text { get => (string)GetValue(TextProperty); set => SetValue(TextProperty, value); }
    /// <summary>Extra advance after each character, in em (CSS letter-spacing).</summary>
    public double Tracking { get => (double)GetValue(TrackingProperty); set => SetValue(TrackingProperty, value); }
    public double ScaleX { get => (double)GetValue(ScaleXProperty); set => SetValue(ScaleXProperty, value); }
    public bool TabularDigits { get => (bool)GetValue(TabularDigitsProperty); set => SetValue(TabularDigitsProperty, value); }
    public FontFamily FontFamily { get => (FontFamily)GetValue(FontFamilyProperty); set => SetValue(FontFamilyProperty, value); }
    public double FontSize { get => (double)GetValue(FontSizeProperty); set => SetValue(FontSizeProperty, value); }
    public FontWeight FontWeight { get => (FontWeight)GetValue(FontWeightProperty); set => SetValue(FontWeightProperty, value); }
    public Brush Foreground { get => (Brush)GetValue(ForegroundProperty); set => SetValue(ForegroundProperty, value); }

    private FormattedText[]? _glyphs;
    private double _height;
    private double _digit; // the tabular cell: the widest of 0-9 in this face and size


    private static void Invalidate(DependencyObject d, DependencyPropertyChangedEventArgs e) => ((TrackedText)d)._glyphs = null;

    private FormattedText[] Glyphs()
    {
        if (_glyphs != null) return _glyphs;
        var text = Text ?? "";
        var face = new Typeface(FontFamily, FontStyles.Normal, FontWeight, FontStretches.Normal);
        var dip = VisualTreeHelper.GetDpi(this).PixelsPerDip;
        _glyphs = new FormattedText[text.Length];
        _height = 0;
        for (var i = 0; i < text.Length; i++)
        {
            var ft = new FormattedText(text[i].ToString(), CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
                face, FontSize, Foreground, dip);
            _glyphs[i] = ft;
            _height = Math.Max(_height, ft.Height);
        }
        _digit = 0;
        foreach (var ch in text)
        {
            if (!TabularDigits) break;
            if (!char.IsAsciiDigit(ch)) continue;
            for (var d = '0'; d <= '9'; d++)
                _digit = Math.Max(_digit, new FormattedText(d.ToString(), CultureInfo.CurrentUICulture,
                    FlowDirection.LeftToRight, face, FontSize, Foreground, dip).WidthIncludingTrailingWhitespace);
            break;
        }
        if (text.Length == 0)
            _height = new FormattedText(" ", CultureInfo.CurrentUICulture, FlowDirection.LeftToRight, face, FontSize, Foreground, dip).Height;
        return _glyphs;
    }

    private double Width0()
    {
        var g = Glyphs();
        double w = 0;
        for (var i = 0; i < g.Length; i++) w += Advance(i, g);
        if (g.Length > 1) w += Tracking * FontSize * (g.Length - 1);
        return w * ScaleX;
    }

    private double Advance(int i, FormattedText[] g) =>
        char.IsAsciiDigit(Text[i]) && _digit > 0 ? _digit : g[i].WidthIncludingTrailingWhitespace;

    /// <summary>Distance from the top of the line box to the baseline, for lining up two
    /// sizes on one baseline (the launch count and its %).</summary>
    public double Baseline
    {
        get
        {
            var face = new Typeface(FontFamily, FontStyles.Normal, FontWeight, FontStretches.Normal);
            return new FormattedText("0", CultureInfo.CurrentUICulture, FlowDirection.LeftToRight, face, FontSize,
                Foreground, VisualTreeHelper.GetDpi(this).PixelsPerDip).Baseline;
        }
    }

    protected override Size MeasureOverride(Size availableSize)
    {
        var w = Width0();
        return new Size(Math.Ceiling(w), Math.Ceiling(_height));
    }

    protected override void OnRender(DrawingContext dc)
    {
        var g = Glyphs();
        if (g.Length == 0) return;
        var scaled = Math.Abs(ScaleX - 1) > 0.001;
        if (scaled) dc.PushTransform(new ScaleTransform(ScaleX, 1));
        var x = 0.0;
        var step = Tracking * FontSize;
        // The brush may have changed since the glyphs were shaped (an animated foreground).
        for (var i = 0; i < g.Length; i++)
        {
            g[i].SetForegroundBrush(Foreground);
            var advance = Advance(i, g);
            dc.DrawText(g[i], new Point(x + (advance - g[i].WidthIncludingTrailingWhitespace) / 2, 0));
            x += advance + step;
        }
        if (scaled) dc.Pop();
    }

    protected override AutomationPeer OnCreateAutomationPeer() => new Peer(this);

    private sealed class Peer(TrackedText owner) : FrameworkElementAutomationPeer(owner)
    {
        protected override string GetNameCore() => ((TrackedText)Owner).Text ?? "";
        protected override AutomationControlType GetAutomationControlTypeCore() => AutomationControlType.Text;
        protected override string GetClassNameCore() => nameof(TrackedText);
    }
}
