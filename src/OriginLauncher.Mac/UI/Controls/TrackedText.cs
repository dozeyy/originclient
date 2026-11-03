using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Media;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// One line of tracked text, the twin of the WPF UI/Controls/TrackedText.cs so a label measures and sits the same on
/// both: each character drawn on its own with <see cref="Tracking"/> em of extra advance after every character but the
/// last (Avalonia's LetterSpacing also pads the last one, which would shift centred labels), and tabular digits by
/// default (each digit centred in the widest digit's cell, so "26.3" never jitters as it changes). Font, size, weight
/// and colour inherit like a TextBlock's. Never wraps or trims.
/// </summary>
public sealed class TrackedText : Control
{
    public static readonly StyledProperty<string?> TextProperty =
        AvaloniaProperty.Register<TrackedText, string?>(nameof(Text), "");
    public static readonly StyledProperty<double> TrackingProperty =
        AvaloniaProperty.Register<TrackedText, double>(nameof(Tracking), 0.14);
    public static readonly StyledProperty<bool> TabularDigitsProperty =
        AvaloniaProperty.Register<TrackedText, bool>(nameof(TabularDigits), true);
    public static readonly StyledProperty<FontFamily> FontFamilyProperty = TextElement.FontFamilyProperty.AddOwner<TrackedText>();
    public static readonly StyledProperty<double> FontSizeProperty = TextElement.FontSizeProperty.AddOwner<TrackedText>();
    public static readonly StyledProperty<FontWeight> FontWeightProperty = TextElement.FontWeightProperty.AddOwner<TrackedText>();
    public static readonly StyledProperty<IBrush?> ForegroundProperty = TextElement.ForegroundProperty.AddOwner<TrackedText>();

    public string? Text { get => GetValue(TextProperty); set => SetValue(TextProperty, value); }
    /// <summary>Extra advance after each character, in em (CSS letter-spacing).</summary>
    public double Tracking { get => GetValue(TrackingProperty); set => SetValue(TrackingProperty, value); }
    public bool TabularDigits { get => GetValue(TabularDigitsProperty); set => SetValue(TabularDigitsProperty, value); }
    public FontFamily FontFamily { get => GetValue(FontFamilyProperty); set => SetValue(FontFamilyProperty, value); }
    public double FontSize { get => GetValue(FontSizeProperty); set => SetValue(FontSizeProperty, value); }
    public FontWeight FontWeight { get => GetValue(FontWeightProperty); set => SetValue(FontWeightProperty, value); }
    public IBrush? Foreground { get => GetValue(ForegroundProperty); set => SetValue(ForegroundProperty, value); }

    private FormattedText[]? _glyphs;
    private double _height;
    private double _digit;

    static TrackedText()
    {
        AffectsMeasure<TrackedText>(TextProperty, TrackingProperty, TabularDigitsProperty, FontFamilyProperty,
            FontSizeProperty, FontWeightProperty);
        AffectsRender<TrackedText>(ForegroundProperty);
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == TextProperty || change.Property == TabularDigitsProperty
            || change.Property == FontFamilyProperty || change.Property == FontSizeProperty
            || change.Property == FontWeightProperty)
            _glyphs = null;
    }

    private FormattedText Shape(string s, Typeface face) =>
        new(s, CultureInfo.CurrentUICulture, FlowDirection.LeftToRight, face, FontSize, Foreground);

    private FormattedText[] Glyphs()
    {
        if (_glyphs != null) return _glyphs;
        var text = Text ?? "";
        var face = new Typeface(FontFamily, FontStyle.Normal, FontWeight, FontStretch.Normal);
        _glyphs = new FormattedText[text.Length];
        _height = 0;
        for (var i = 0; i < text.Length; i++)
        {
            _glyphs[i] = Shape(text[i].ToString(), face);
            _height = Math.Max(_height, _glyphs[i].Height);
        }
        _digit = 0;
        if (TabularDigits && text.Any(char.IsAsciiDigit))
            for (var d = '0'; d <= '9'; d++)
                _digit = Math.Max(_digit, Shape(d.ToString(), face).WidthIncludingTrailingWhitespace);
        if (text.Length == 0) _height = Shape(" ", face).Height;
        return _glyphs;
    }

    private double Advance(int i, FormattedText[] g) =>
        char.IsAsciiDigit(Text![i]) && _digit > 0 ? _digit : g[i].WidthIncludingTrailingWhitespace;

    protected override Size MeasureOverride(Size availableSize)
    {
        var g = Glyphs();
        double w = 0;
        for (var i = 0; i < g.Length; i++) w += Advance(i, g);
        if (g.Length > 1) w += Tracking * FontSize * (g.Length - 1);
        return new Size(Math.Ceiling(w), Math.Ceiling(_height));
    }

    public override void Render(DrawingContext context)
    {
        var g = Glyphs();
        var x = 0.0;
        var step = Tracking * FontSize;
        for (var i = 0; i < g.Length; i++)
        {
            g[i].SetForegroundBrush(Foreground);   // an animated foreground changes after shaping
            var advance = Advance(i, g);
            context.DrawText(g[i], new Point(x + (advance - g[i].WidthIncludingTrailingWhitespace) / 2, 0));
            x += advance + step;
        }
    }
}
