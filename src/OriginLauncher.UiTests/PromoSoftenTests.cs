using System.Reflection;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.UiTests;

/// <summary>
/// The promo card's Discord art is blurred ONCE on the pool thread (PromoBanner.Soften), not by a live BlurEffect.
/// Proves the blur keeps the size, returns a frozen bitmap, spreads a single bright pixel into its neighbours and
/// keeps a flat colour flat (clamped edges, no darkening at the border).
/// </summary>
public class PromoSoftenTests
{
    private static readonly MethodInfo Soften =
        typeof(PromoBanner).GetMethod("Soften", BindingFlags.NonPublic | BindingFlags.Static)!;

    private static byte[] Pixels(BitmapSource source)
    {
        var pixels = new byte[source.PixelWidth * source.PixelHeight * 4];
        source.CopyPixels(pixels, source.PixelWidth * 4, 0);
        return pixels;
    }

    private static BitmapSource Make(int size, Func<int, int, byte> value)
    {
        var data = new byte[size * size * 4];
        for (var y = 0; y < size; y++)
            for (var x = 0; x < size; x++)
            {
                var i = (y * size + x) * 4;
                data[i] = data[i + 1] = data[i + 2] = value(x, y);
                data[i + 3] = 255;
            }
        var image = BitmapSource.Create(size, size, 96, 96, PixelFormats.Bgra32, null, data, size * 4);
        image.Freeze();
        return image;
    }

    [Fact]
    public void A_single_bright_pixel_is_spread_and_the_size_is_kept()
    {
        var source = Make(16, (x, y) => (byte)(x == 8 && y == 8 ? 255 : 0));
        var result = (BitmapSource)Soften.Invoke(null, [source])!;
        Assert.True(result.IsFrozen);
        Assert.Equal(16, result.PixelWidth);
        Assert.Equal(16, result.PixelHeight);
        var pixels = Pixels(result);
        int At(int x, int y) => pixels[(y * 16 + x) * 4];
        Assert.InRange(At(8, 8), 1, 254);   // the peak is lowered
        Assert.True(At(9, 8) > 0);          // and its neighbours lit
        Assert.True(At(8, 10) > 0);
        Assert.Equal(0, At(0, 0));          // far corners stay dark
    }

    [Fact]
    public void A_flat_colour_stays_flat_at_the_edges()
    {
        var result = (BitmapSource)Soften.Invoke(null, [Make(12, (_, _) => 200)])!;
        var pixels = Pixels(result);
        for (var i = 0; i < pixels.Length; i += 4) Assert.InRange(pixels[i], 198, 200);
    }
}
