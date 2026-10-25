using System.Windows;
using System.Windows.Media;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The "orbit thread" behind Home's giant numeral (DESIGN-SYSTEM 12.1): three tilted capsule outlines sharing one
/// centre, at 0, 60 and 120 degrees, as 1 px parchment hairlines (Brush.OrbitThread, 20 %). Each ring is a true
/// capsule, never an ellipse (Will, 2026-10-25: "pill shaped like apple and never oval shaped ever again"): two straight
/// parallel sides and perfect semicircle ends, the end radius exactly half the short side.
/// It is the idea of the logo (atoms and orbits) used as a faint thread, NOT the logo mark: the mark itself is only ever
/// the logo file. The three rings are built once per size as frozen geometry (rotation and centring baked in) and drawn
/// in OnRender, which WPF keeps as retained geometry, so nothing is allocated or redrawn per frame; it sits inside
/// <see cref="VersionNumeral"/> so it fades and rolls with it.
/// </summary>
internal sealed class OrbitThread : FrameworkElement
{
    private static readonly double[] Tilts = [0, 60, 120];

    private double _length, _thickness;
    private Geometry[] _rings = [];
    private Pen? _pen;

    public OrbitThread()
    {
        IsHitTestVisible = false;
        SnapsToDevicePixels = false;
    }

    /// <summary>
    /// Centre the thread on (<paramref name="cx"/>, <paramref name="cy"/>) with capsules <paramref name="length"/> long
    /// and <paramref name="thickness"/> tall (before tilting).
    /// </summary>
    public void Place(double cx, double cy, double length, double thickness)
    {
        length = Math.Max(2, Math.Round(length));
        thickness = Math.Max(2, Math.Min(length, Math.Round(thickness)));
        if (Math.Abs(length - _length) >= 0.5 || Math.Abs(thickness - _thickness) >= 0.5)
        {
            _length = length;
            _thickness = thickness;
            Build();
        }
        System.Windows.Controls.Canvas.SetLeft(this, cx - Width / 2);
        System.Windows.Controls.Canvas.SetTop(this, cy - Height / 2);
    }

    /// <summary>Half the length, half the thickness and the end radius of one ring; for the render tour's log.</summary>
    internal (double Length, double Thickness, double Radius) Ring => (_length, _thickness, CapsuleRadius(_length, _thickness));

    /// <summary>The end radius of a capsule: exactly half its short side (the ends are semicircles).</summary>
    internal static double CapsuleRadius(double length, double thickness) => Math.Min(length, thickness) / 2;

    // The element covers the three tilted rings' joint bounds; each ring is a rounded rectangle whose corner radius is
    // half its short side (a capsule), turned about the shared centre. Built only when the size changes.
    private void Build()
    {
        // Inset half the pen so the 1 px line sits inside the capsule's box, like a stroke-only SVG rect.
        double halfLength = _length / 2 - 0.5, halfThickness = _thickness / 2 - 0.5;
        var r = CapsuleRadius(halfLength * 2, halfThickness * 2);
        double extentX = 0, extentY = 0;
        foreach (var tilt in Tilts)
        {
            var rad = tilt * Math.PI / 180;
            // A capsule's bounds when turned: its straight part's ends sweep (halfLength - r) along the tilt, plus r all round.
            extentX = Math.Max(extentX, (halfLength - r) * Math.Abs(Math.Cos(rad)) + r);
            extentY = Math.Max(extentY, (halfLength - r) * Math.Abs(Math.Sin(rad)) + r);
        }
        extentX = Math.Ceiling(extentX + 1);
        extentY = Math.Ceiling(extentY + 1);
        Width = extentX * 2;
        Height = extentY * 2;

        var rings = new Geometry[Tilts.Length];
        for (var i = 0; i < Tilts.Length; i++)
        {
            var ring = new RectangleGeometry(new Rect(-halfLength, -halfThickness, halfLength * 2, halfThickness * 2), r, r);
            var place = new TransformGroup();
            place.Children.Add(new RotateTransform(Tilts[i]));
            place.Children.Add(new TranslateTransform(extentX, extentY));
            ring.Transform = place;
            ring.Freeze();
            rings[i] = ring;
        }
        _rings = rings;
        InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        if (_rings.Length == 0) return;
        _pen ??= MakePen();
        foreach (var ring in _rings) dc.DrawGeometry(null, _pen, ring);
    }

    private static Pen MakePen()
    {
        // Not frozen: the brush is a theme resource, which stays live.
        return new Pen((Brush)Application.Current.FindResource("Brush.OrbitThread"), 1);
    }
}
