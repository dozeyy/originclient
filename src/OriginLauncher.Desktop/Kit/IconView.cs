using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Media;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Kit;

// One Origin Line icon (Theme/Icons.cs): the 24 px master scaled to Size, stroked at 1.7 px with round
// caps and joins at every size (the stroke is never scaled, section 10). With no Brush set it follows the
// inherited text colour, so a button's hover colour reaches its glyph with no extra wiring.
public sealed class IconView : Control
{
    public static readonly StyledProperty<string> GlyphProperty =
        AvaloniaProperty.Register<IconView, string>(nameof(Glyph), Icons.Close);
    public static readonly StyledProperty<double> SizeProperty =
        AvaloniaProperty.Register<IconView, double>(nameof(Size), Sizes.Icon);
    public static readonly StyledProperty<IBrush?> BrushProperty =
        AvaloniaProperty.Register<IconView, IBrush?>(nameof(Brush));
    public static readonly StyledProperty<bool> FilledProperty =
        AvaloniaProperty.Register<IconView, bool>(nameof(Filled));

    static IconView()
    {
        AffectsRender<IconView>(GlyphProperty, SizeProperty, BrushProperty, FilledProperty, TextElement.ForegroundProperty);
        AffectsMeasure<IconView>(SizeProperty);
    }

    public string Glyph { get => GetValue(GlyphProperty); set => SetValue(GlyphProperty, value); }
    public double Size { get => GetValue(SizeProperty); set => SetValue(SizeProperty, value); }
    public IBrush? Brush { get => GetValue(BrushProperty); set => SetValue(BrushProperty, value); }
    public bool Filled { get => GetValue(FilledProperty); set => SetValue(FilledProperty, value); }

    private Geometry? _scaled;
    private (string, double) _scaledKey;

    protected override Avalonia.Size MeasureOverride(Avalonia.Size availableSize) => new(Size, Size);

    public override void Render(DrawingContext context)
    {
        var brush = Brush ?? TextElement.GetForeground(this) ?? Palette.TextDim;
        if (_scaled == null || _scaledKey != (Glyph, Size))
        {
            var g = Icons.Get(Glyph).Clone();
            var k = Size / 24.0;
            g.Transform = new ScaleTransform(k, k);
            _scaled = g;
            _scaledKey = (Glyph, Size);
        }
        // Centre in whatever box layout gave us (the glyph itself is a Size x Size square).
        using (context.PushTransform(Matrix.CreateTranslation((Bounds.Width - Size) / 2, (Bounds.Height - Size) / 2)))
        {
            if (Filled) context.DrawGeometry(brush, null, _scaled);
            else context.DrawGeometry(null, new Pen(brush, Sizes.IconStroke, lineCap: PenLineCap.Round, lineJoin: PenLineJoin.Round), _scaled);
        }
    }
}
