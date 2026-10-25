using System.Windows;
using System.Windows.Media;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The "orbit thread" behind Home's giant numeral (DESIGN-SYSTEM, Signature effects): three tilted ellipses
/// sharing one centre, at 0, 60 and 120 degrees, as 1 px parchment hairlines (Brush.OrbitThread, 20 %).
/// It is the idea of the logo (atoms and orbits) used as a faint thread, NOT the logo mark: the mark itself
/// is only ever the logo file. Drawn once in OnRender (WPF keeps that as retained geometry), so it costs
/// nothing per frame; it is redrawn only when the numeral's size changes, and it sits inside
/// <see cref="VersionNumeral"/> so it fades and rolls with it.
/// </summary>
internal sealed class OrbitThread : FrameworkElement
{
    /// <summary>Minor axis over major axis of each ring.</summary>
    private const double Flatness = 0.4;

    private static readonly double[] Tilts = [0, 60, 120];

    private double _major;
    private Pen? _pen;

    public OrbitThread()
    {
        IsHitTestVisible = false;
        SnapsToDevicePixels = false;
    }

    /// <summary>Centre the thread on (<paramref name="cx"/>, <paramref name="cy"/>) with rings of semi-major axis <paramref name="major"/>.</summary>
    public void Place(double cx, double cy, double major)
    {
        major = Math.Max(1, Math.Round(major));
        System.Windows.Controls.Canvas.SetLeft(this, cx - major);
        System.Windows.Controls.Canvas.SetTop(this, cy - major);
        if (Math.Abs(major - _major) < 0.5) return;
        _major = major;
        Width = Height = major * 2;
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        if (_major <= 0) return;
        _pen ??= MakePen();
        dc.PushTransform(new TranslateTransform(_major, _major));
        foreach (var tilt in Tilts)
        {
            dc.PushTransform(new RotateTransform(tilt));
            dc.DrawEllipse(null, _pen, new Point(0, 0), _major - 0.5, (_major - 0.5) * Flatness);
            dc.Pop();
        }
        dc.Pop();
    }

    private static Pen MakePen()
    {
        // Not frozen: the brush is a theme resource, which stays live.
        return new Pen((Brush)Application.Current.FindResource("Brush.OrbitThread"), 1);
    }
}
