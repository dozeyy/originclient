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
/// advance after every character but the last. ScaleX narrows the glyphs for the
/// condensed poster caps (Inter has no width axis in WPF). Font, size, weight and
/// colour inherit like a TextBlock's. Never wraps or trims: size its slot to fit.
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

    public static readonly DependencyProperty FontFamilyProperty = TextElement.FontFamilyProperty.AddOwner(typeof(TrackedText),
        new FrameworkPropertyMetadata(SystemFonts.MessageFontFamily, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsMeasure, Invalidate));
    public static readonly DependencyProperty FontSizeProperty = TextElement.FontSizeProperty.AddOwner(typeof(TrackedText),
        new FrameworkPropertyMetadata(12.0, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsMeasure, Invalidate));
    public static readonly DependencyProperty FontWeightProperty = TextElement.FontWeightProperty.AddOwner(typeof(TrackedText),
        new FrameworkPropertyMetadata(FontWeights.Normal, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsMeasure, Invalidate));
    public static readonly DependencyProperty ForegroundProperty = TextElement.ForegroundProperty.AddOwner(typeof(TrackedText),
        new FrameworkPropertyMetadata(Brushes.White, FrameworkPropertyMetadataOptions.Inherits | FrameworkPropertyMetadataOptions.AffectsRender, Invalidate));

    public string Text { get => (string)GetValue(TextProperty); set => SetValue(TextProperty, value); }
    /// <summary>Extra advance after each character, in em (CSS letter-spacing).</summary>
    public double Tracking { get => (double)GetValue(TrackingProperty); set => SetValue(TrackingProperty, value); }
    public double ScaleX { get => (double)GetValue(ScaleXProperty); set => SetValue(ScaleXProperty, value); }
    public FontFamily FontFamily { get => (FontFamily)GetValue(FontFamilyProperty); set => SetValue(FontFamilyProperty, value); }
    public double FontSize { get => (double)GetValue(FontSizeProperty); set => SetValue(FontSizeProperty, value); }
    public FontWeight FontWeight { get => (FontWeight)GetValue(FontWeightProperty); set => SetValue(FontWeightProperty, value); }
    public Brush Foreground { get => (Brush)GetValue(ForegroundProperty); set => SetValue(ForegroundProperty, value); }

    private FormattedText[]? _glyphs;
    private double _height;

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
        if (text.Length == 0)
            _height = new FormattedText(" ", CultureInfo.CurrentUICulture, FlowDirection.LeftToRight, face, FontSize, Foreground, dip).Height;
        return _glyphs;
    }

    private double Width0()
    {
        var g = Glyphs();
        double w = 0;
        for (var i = 0; i < g.Length; i++) w += g[i].WidthIncludingTrailingWhitespace;
        if (g.Length > 1) w += Tracking * FontSize * (g.Length - 1);
        return w * ScaleX;
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
            dc.DrawText(g[i], new Point(x, 0));
            x += g[i].WidthIncludingTrailingWhitespace + step;
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
