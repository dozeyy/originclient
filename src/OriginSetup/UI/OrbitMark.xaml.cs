using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginSetup.UI;

public partial class OrbitMark : UserControl
{
    // Seconds per full turn at speed 1. Different and not multiples of each
    // other, so the rings drift through ever-changing alignments.
    private static readonly double[] Periods = { 26, 34, 42 };
    private static readonly int[] Directions = { 1, -1, 1 };

    /// <summary>False = a still mark (the title bar's small logo).</summary>
    public bool Animated { get; set; } = true;

    public OrbitMark()
    {
        InitializeComponent();
        Loaded += (_, _) => { if (Animated) SetSpeed(1); };
    }

    /// <summary>1 = idle orbit; higher = faster (installing); 0 = stop where they are.</summary>
    public void SetSpeed(double speed)
    {
        var rings = new[] { Ring1, Ring2, Ring3 };
        for (int i = 0; i < rings.Length; i++)
        {
            var ring = rings[i];
            double from = ring.Angle; // current animated value — continue from here
            if (speed <= 0 || !SystemParameters.ClientAreaAnimation)
            {
                ring.BeginAnimation(RotateTransform.AngleProperty, null);
                ring.Angle = from;
                continue;
            }
            var spin = new DoubleAnimation(from, from + 360 * Directions[i],
                new Duration(TimeSpan.FromSeconds(Periods[i] / speed)))
            {
                RepeatBehavior = RepeatBehavior.Forever,
            };
            ring.BeginAnimation(RotateTransform.AngleProperty, spin, HandoffBehavior.SnapshotAndReplace);
        }
    }
}
