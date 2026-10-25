using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>The Home numeral's placement maths: a band between the bar and the strip, centred, never wider than 2/3.</summary>
public class NumeralBandTests
{
    // Inter ExtraBold: figures 0.7275 em; "1.21.1" is about 2.7 em of ink, "26.3" about 2.2 em.
    private const double Caps = 0.7275;

    [Theory]
    [InlineData(960, 600)]
    [InlineData(1180, 740)]
    [InlineData(1920, 1080)]
    public void Figures_are_centred_in_the_band_at_every_window_size(double w, double h)
    {
        // The strip is about 76 tall with a 32 edge under it (Home at rest).
        var (top, bottom) = NumeralBand.Band(h, 48, h - 108);
        var fs = NumeralBand.FontSize(w, h, top, bottom, Caps, 2.2);
        Assert.Equal(48, top);
        Assert.Equal(h - 108, bottom);
        Assert.Equal((48 + h - 108) / 2, NumeralBand.MiddleY(top, bottom), 6);
        // The figures fit the band with air: never more than 60 % of it.
        Assert.True(Caps * fs <= (bottom - top) * NumeralBand.FigureShareOfBand + 1e-6);
    }

    [Fact]
    public void Spec_size_at_the_default_window_is_a_quarter_of_the_height()
    {
        // DESIGN-SYSTEM 11.6: Home 1180 x 740 has figures 185 tall (font 254); 960 x 600 has 150 (font 206).
        var (top, bottom) = NumeralBand.Band(740, 48, 740 - 108);
        Assert.Equal(185, Caps * NumeralBand.FontSize(1180, 740, top, bottom, Caps, 2.2), 0);
        (top, bottom) = NumeralBand.Band(600, 48, 600 - 108);
        Assert.Equal(150, Caps * NumeralBand.FontSize(960, 600, top, bottom, Caps, 2.2), 0);
    }

    [Fact]
    public void A_long_key_shrinks_to_two_thirds_of_the_width_not_squashed()
    {
        var (top, bottom) = NumeralBand.Band(740, 48, 632);
        const double inkEm = 3.1;
        var fs = NumeralBand.FontSize(1180, 740, top, bottom, Caps, inkEm);
        Assert.Equal(1180 * NumeralBand.InkShareOfWidth, inkEm * fs, 6);
    }

    [Fact]
    public void A_short_window_is_held_to_the_band()
    {
        // Band 100 tall: figures at most 60 of it even though a quarter of the height would be larger.
        var (top, bottom) = NumeralBand.Band(300, 48, 148);
        var fs = NumeralBand.FontSize(1180, 300, top, bottom, Caps, 2.2);
        Assert.Equal(60, Caps * fs, 6);
    }

    [Fact]
    public void A_strip_that_has_not_reported_gives_the_whole_space_under_the_bar()
    {
        Assert.Equal((48.0, 740.0), NumeralBand.Band(740, 48, double.NaN));
        // A strip taller than the window leaves no band: still a place for the numeral, never inverted.
        var (top, bottom) = NumeralBand.Band(400, 48, 20);
        Assert.True(bottom > top);
    }

    [Fact]
    public void Ink_is_centred_across_the_window()
    {
        Assert.Equal(300, NumeralBand.InkLeft(1180, 580), 6);
    }
}
