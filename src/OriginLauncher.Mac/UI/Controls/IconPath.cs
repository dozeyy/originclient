using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Media;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// One Origin Line glyph (Theme/Icons.axaml), drawn the way the WPF launcher's Path with Stretch="Uniform" draws it:
/// the outline is scaled so the glyph AND its stroke fit the control's box (WPF shrinks the geometry by the stroke
/// thickness), centred. Avalonia's Path scales the bare geometry to the box and lets the stroke hang outside it, which
/// made every Mac icon about 2 px bigger and heavier than its Windows twin (measured on the Home shot: the 16 px bag
/// inked 16x18 against WPF's 15x16). Use it wherever the WPF XAML has an icon Path: same Data keys, same Icon.* themes.
/// </summary>
public sealed class IconPath : Control
{
    public static readonly StyledProperty<Geometry?> DataProperty = Avalonia.Controls.Shapes.Path.DataProperty.AddOwner<IconPath>();
    public static readonly StyledProperty<IBrush?> StrokeProperty = Shape.StrokeProperty.AddOwner<IconPath>();
    public static readonly StyledProperty<IBrush?> FillProperty = Shape.FillProperty.AddOwner<IconPath>();
    public static readonly StyledProperty<double> StrokeThicknessProperty = Shape.StrokeThicknessProperty.AddOwner<IconPath>();
    public static readonly StyledProperty<PenLineCap> StrokeLineCapProperty = Shape.StrokeLineCapProperty.AddOwner<IconPath>();
    public static readonly StyledProperty<PenLineJoin> StrokeJoinProperty = Shape.StrokeJoinProperty.AddOwner<IconPath>();

    public Geometry? Data { get => GetValue(DataProperty); set => SetValue(DataProperty, value); }
    public IBrush? Stroke { get => GetValue(StrokeProperty); set => SetValue(StrokeProperty, value); }
    public IBrush? Fill { get => GetValue(FillProperty); set => SetValue(FillProperty, value); }
    public double StrokeThickness { get => GetValue(StrokeThicknessProperty); set => SetValue(StrokeThicknessProperty, value); }
    public PenLineCap StrokeLineCap { get => GetValue(StrokeLineCapProperty); set => SetValue(StrokeLineCapProperty, value); }
    public PenLineJoin StrokeJoin { get => GetValue(StrokeJoinProperty); set => SetValue(StrokeJoinProperty, value); }

    static IconPath()
    {
        AffectsRender<IconPath>(DataProperty, StrokeProperty, FillProperty, StrokeThicknessProperty,
            StrokeLineCapProperty, StrokeJoinProperty);
        AffectsMeasure<IconPath>(DataProperty);
    }

    // Sized by Width/Height (the Icon.* themes); with neither, the glyph's own outline plus its stroke.
    protected override Size MeasureOverride(Size availableSize)
    {
        if (Data is not { } g) return default;
        var b = g.Bounds;
        return new Size(b.Width + StrokeThickness, b.Height + StrokeThickness);
    }

    public override void Render(DrawingContext context)
    {
        if (Data is not { } g) return;
        var b = g.Bounds;
        var t = StrokeThickness;
        double w = Bounds.Width - t, h = Bounds.Height - t;
        if (w <= 0 || h <= 0) return;
        // Uniform: one scale for both axes (a flat glyph such as the chevron keeps its shape), centred in the box.
        var s = Math.Min(b.Width > 0 ? w / b.Width : double.PositiveInfinity, b.Height > 0 ? h / b.Height : double.PositiveInfinity);
        if (double.IsInfinity(s)) s = 1;
        var x = (Bounds.Width - b.Width * s) / 2 - b.X * s;
        var y = (Bounds.Height - b.Height * s) / 2 - b.Y * s;
        // The pen is scaled by the transform too, so it is built at t / s to stay StrokeThickness wide on screen.
        var pen = Stroke is { } stroke && t > 0 ? new Pen(stroke, t / s, lineCap: StrokeLineCap, lineJoin: StrokeJoin) : null;
        using (context.PushTransform(Matrix.CreateScale(s, s) * Matrix.CreateTranslation(x, y)))
            context.DrawGeometry(Fill, pen, g);
    }
}
