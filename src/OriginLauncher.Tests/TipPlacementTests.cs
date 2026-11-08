using OriginLauncher.App.UI;
using Xunit;

namespace OriginLauncher.Tests;

// Where a hover label goes (UI/TipPlacement): the one rule the Windows and Mac launchers share. Below the control and
// centred, flipped above when the window ends first, slid so it never hangs past the window's edge.
public class TipPlacementTests
{
    private const double WinW = 1180, WinH = 740;

    [Fact]
    public void A_label_sits_centred_below_its_control()
    {
        // A 32 x 32 icon at (500, 14), a 60 x 26 label.
        var (side, x, y) = TipPlacement.Place(new TipPlacement.Box(500, 14, 32, 32), 60, 26, WinW, WinH);

        Assert.Equal(TipPlacement.Side.Below, side);
        Assert.Equal(-14, x);             // (32 - 60) / 2: centred on the icon
        Assert.Equal(TipPlacement.Gap, y);
    }

    [Fact]
    public void A_label_at_the_right_edge_slides_back_inside_the_window()
    {
        // The account pill's neighbour near the right edge: centred, the label would end at 1190.
        var (side, x, _) = TipPlacement.Place(new TipPlacement.Box(1130, 14, 40, 32), 120, 26, WinW, WinH);

        Assert.Equal(TipPlacement.Side.Below, side);
        Assert.Equal(WinW - 120 - TipPlacement.EdgeMargin, 1130 + x);
    }

    [Fact]
    public void A_label_at_the_left_edge_keeps_the_edge_margin()
    {
        var (_, x, _) = TipPlacement.Place(new TipPlacement.Box(2, 14, 20, 32), 120, 26, WinW, WinH);

        Assert.Equal(TipPlacement.EdgeMargin, 2 + x);
    }

    [Fact]
    public void A_label_near_the_bottom_flips_above()
    {
        var (side, x, y) = TipPlacement.Place(new TipPlacement.Box(600, 700, 32, 32), 60, 26, WinW, WinH);

        Assert.Equal(TipPlacement.Side.Above, side);
        Assert.Equal(-14, x);
        Assert.Equal(-TipPlacement.Gap, y);
    }

    [Fact]
    public void Right_placement_sits_beside_the_control_when_it_fits_and_falls_back_below_when_not()
    {
        var (side, x, y) = TipPlacement.Place(new TipPlacement.Box(10, 200, 40, 40), 80, 20, WinW, WinH, wantRight: true);
        Assert.Equal(TipPlacement.Side.Right, side);
        Assert.Equal(TipPlacement.Gap, x);
        Assert.Equal(10, y);              // (40 - 20) / 2: centred beside it

        var (fallback, _, _) = TipPlacement.Place(new TipPlacement.Box(1100, 200, 40, 40), 80, 20, WinW, WinH, wantRight: true);
        Assert.Equal(TipPlacement.Side.Below, fallback);
    }
}
