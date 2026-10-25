using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>The art-aware shade under a version number on a card (DESIGN-SYSTEM.md 11.6): light art gets the strong
/// shade, dark art the weak one, and wherever the design allows it the number holds its contrast.</summary>
public class CardArtScrimTests
{
    // The tokens' values at the time of writing (Theme/Typography.xaml, Colors.xaml); the maths takes them as data.
    private static readonly NumeralShade Shade = new(0.88, 0.5, 0.45, 1, 0.8);

    private static ScrimSpec Spec(double floor = 0.35) =>
        new(Shade, 0.96, 0.96, 0.96, 0.055, 0.067, 0.063, 4.5, floor, 0.5, 0.4, 1.5);

    private static CardArtGrid Flat(double r, double g, double b)
    {
        const int cols = 32, rows = 18;
        var rgb = new double[cols * rows * 3];
        for (var i = 0; i < rgb.Length; i += 3) { rgb[i] = r; rgb[i + 1] = g; rgb[i + 2] = b; }
        return new CardArtGrid(cols, rows, rgb);
    }

    [Fact]
    public void ShadeProfileRunsPeakMidZero()
    {
        Assert.Equal(0.88, Shade.AlphaAt(0, 0), 6);
        Assert.Equal(0.5, Shade.AlphaAt(0.45, 0), 6);          // MidAt of the width radius
        Assert.Equal(0, Shade.AlphaAt(1, 0), 6);               // the edge
        Assert.Equal(0, Shade.AlphaAt(0, 0.8), 6);             // and the height radius
        Assert.True(Shade.AlphaAt(0.2, 0.2) > Shade.AlphaAt(0.4, 0.4));
    }

    [Fact]
    public void LightArtGetsAStrongerShadeThanDarkArt()
    {
        var light = CardArtScrim.Analyse(Flat(0.95, 0.95, 0.9), Spec());
        var dark = CardArtScrim.Analyse(Flat(0.08, 0.1, 0.09), Spec());
        Assert.True(light.Strength > 0.9, $"light {light.Strength}");
        Assert.Equal(0.35, dark.Strength, 6);                  // dark art needs nothing: the floor
        Assert.True(light.ZoneLuminance > dark.ZoneLuminance);
    }

    [Fact]
    public void StrengthNeverLeavesFloorToOne()
    {
        foreach (var v in new[] { 0.0, 0.2, 0.4, 0.6, 0.8, 1.0 })
        {
            var r = CardArtScrim.Analyse(Flat(v, v, v), Spec());
            Assert.InRange(r.Strength, 0.35, 1.0);
        }
    }

    [Fact]
    public void BrighterArtNeverGetsAWeakerShade()
    {
        // Strength alone can step down where the shade starts to reach further, so compare what is actually laid on
        // the art at the zone's far corner (the cell the number needs most help at on flat art).
        var last = 0.0;
        foreach (var v in new[] { 0.0, 0.2, 0.4, 0.5, 0.6, 0.8, 1.0 })
        {
            var r = CardArtScrim.Analyse(Flat(v, v, v), Spec());
            var corner = r.Strength * Shade.AlphaAt(15.5 / 32, 1 - 11.5 / 18, r.Reach);
            Assert.True(corner >= last - 1e-9, $"{v}: {corner} < {last}");
            last = corner;
        }
    }

    [Fact]
    public void AMidArtHoldsTheTargetContrastOnceShaded()
    {
        var art = Flat(0.5, 0.5, 0.45);
        var spec = Spec();
        var result = CardArtScrim.Analyse(art, spec);
        Assert.True(result.Strength < 1);                       // reachable, so not pinned at the maximum
        Assert.True(CardArtScrim.WorstContrast(art, spec, result.Strength) >= 4.5 - 1e-6);
    }

    [Fact]
    public void LightArtWidensTheShadeInsteadOfStoppingAtFullStrength()
    {
        // Light enough that full strength at the design's reach cannot hold the far corner of the zone.
        var art = Flat(0.78, 0.78, 0.78);
        var spec = Spec();
        var result = CardArtScrim.Analyse(art, spec);
        Assert.True(result.Reach > 1, $"reach {result.Reach}");
        Assert.True(result.Reach <= spec.ReachMax + 1e-9);
        Assert.True(CardArtScrim.WorstContrast(art, spec, result.Strength, result.Reach) >= 4.5 - 1e-6);
        Assert.True(CardArtScrim.WorstContrast(art, spec, result.Strength, 1) < 4.5);
    }

    [Fact]
    public void ArtTheDesignHoldsAtItsOwnReachKeepsReachOne()
    {
        foreach (var v in new[] { 0.0, 0.2, 0.4 })
            Assert.Equal(1, CardArtScrim.Analyse(Flat(v, v, v), Spec()).Reach, 6);
    }

    [Fact]
    public void ReachNeverLeavesOneToMax()
    {
        foreach (var v in new[] { 0.0, 0.5, 0.8, 0.95, 1.0 })
        {
            var r = CardArtScrim.Analyse(Flat(v, v, v), Spec());
            Assert.InRange(r.Reach, 1.0, 1.5);
            Assert.InRange(r.Strength, 0.35, 1.0);
        }
    }

    [Fact]
    public void TheShadeOnlyMattersWhereTheNumberStands()
    {
        // Bright art far from the corner (top right, outside the number's zone) must not drive the strength.
        var art = Flat(0.08, 0.1, 0.09);
        for (var row = 0; row < 6; row++)
            for (var col = 20; col < 32; col++)
            {
                var o = (row * 32 + col) * 3;
                art.Rgb[o] = art.Rgb[o + 1] = art.Rgb[o + 2] = 1;
            }
        Assert.Equal(0.35, CardArtScrim.Analyse(art, Spec()).Strength, 6);
    }

    [Fact]
    public void ABrightSpotUnderTheNumberRaisesTheShade()
    {
        var art = Flat(0.08, 0.1, 0.09);
        var o = (16 * 32 + 3) * 3;                               // bottom row, near the corner
        art.Rgb[o] = art.Rgb[o + 1] = art.Rgb[o + 2] = 1;
        Assert.True(CardArtScrim.Analyse(art, Spec()).Strength > 0.35);
    }

    [Fact]
    public void ContrastMatchesTheWcagFormula()
    {
        Assert.Equal(21, CardArtScrim.Contrast(CardArtScrim.Luminance(1, 1, 1), CardArtScrim.Luminance(0, 0, 0)), 6);
        Assert.Equal(1, CardArtScrim.Contrast(0.3, 0.3), 6);
    }

    [Fact]
    public void FromBgraAveragesBlocksAndReadsChannelsInOrder()
    {
        // 4x2 image: left half pure red (BGRA 0,0,255,255), right half pure blue.
        var px = new byte[4 * 2 * 4];
        for (var y = 0; y < 2; y++)
            for (var x = 0; x < 4; x++)
            {
                var i = (y * 4 + x) * 4;
                if (x < 2) px[i + 2] = 255; else px[i] = 255;
                px[i + 3] = 255;
            }
        var grid = CardArtGrid.FromBgra(px, 4, 2, 16, 2, 1);
        Assert.Equal(new[] { 1.0, 0, 0, 0, 0, 1.0 }, grid.Rgb);
    }

    [Fact]
    public void AnEmptyZoneDoesNotThrow()
    {
        var art = new CardArtGrid(1, 1, new[] { 1.0, 1.0, 1.0 });
        var r = CardArtScrim.Analyse(art, Spec() with { ZoneWidth = 0.01, ZoneHeight = 0.01 });
        Assert.Equal(0.35, r.Strength, 6);
    }
}
