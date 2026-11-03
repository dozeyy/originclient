using System.Buffers.Binary;
using System.IO.Compression;
using System.Text;
using OriginLauncher.App.Core.Skins;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The Mac's managed skin decoder (SkinPng.Mac.cs; Windows decodes with WPF). Every PNG here is
/// written by the small encoder below, so each colour type and all five row filters are checked
/// pixel for pixel against the source, plus the damaged and non-skin files that must give null.
/// </summary>
public sealed class SkinPngTests
{
    private const int W = 64, H = 64;

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    public void Rgba_round_trips_under_every_filter(byte filter)
    {
        var rgba = Pattern(W, H);
        var bgra = SkinPng.DecodeBgra(Png(W, H, 6, 8, Rows(rgba, W * 4, H, 4, filter)));
        Assert.Equal(ToBgra(rgba), bgra);
    }

    [Fact]
    public void Rgb_and_grey_types_decode_opaque()
    {
        var rgba = Pattern(W, H);
        var rgb = new byte[W * H * 3];
        var grey = new byte[W * H];
        var greyAlpha = new byte[W * H * 2];
        for (var i = 0; i < W * H; i++)
        {
            rgb[i * 3] = rgba[i * 4]; rgb[i * 3 + 1] = rgba[i * 4 + 1]; rgb[i * 3 + 2] = rgba[i * 4 + 2];
            grey[i] = rgba[i * 4];
            greyAlpha[i * 2] = rgba[i * 4]; greyAlpha[i * 2 + 1] = rgba[i * 4 + 3];
        }

        var fromRgb = SkinPng.DecodeBgra(Png(W, H, 2, 8, Rows(rgb, W * 3, H, 3, 4)))!;
        var fromGrey = SkinPng.DecodeBgra(Png(W, H, 0, 8, Rows(grey, W, H, 1, 1)))!;
        var fromGreyAlpha = SkinPng.DecodeBgra(Png(W, H, 4, 8, Rows(greyAlpha, W * 2, H, 2, 3)))!;
        for (var i = 0; i < W * H; i++)
        {
            Assert.Equal(new[] { rgba[i * 4 + 2], rgba[i * 4 + 1], rgba[i * 4], (byte)255 }, fromRgb.AsSpan(i * 4, 4).ToArray());
            Assert.Equal(new[] { rgba[i * 4], rgba[i * 4], rgba[i * 4], (byte)255 }, fromGrey.AsSpan(i * 4, 4).ToArray());
            Assert.Equal(new[] { rgba[i * 4], rgba[i * 4], rgba[i * 4], rgba[i * 4 + 3] }, fromGreyAlpha.AsSpan(i * 4, 4).ToArray());
        }
    }

    [Theory]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(4)]
    [InlineData(8)]
    public void Palette_images_take_colour_and_trns_alpha(int bitDepth)
    {
        var colours = 1 << bitDepth;
        var palette = new byte[colours * 3];
        var alpha = new byte[colours];
        for (var c = 0; c < colours; c++)
        {
            palette[c * 3] = (byte)(c * 37); palette[c * 3 + 1] = (byte)(255 - c); palette[c * 3 + 2] = (byte)(c * 11);
            alpha[c] = (byte)(c == 0 ? 0 : 255 - c);
        }
        var rowBytes = (W * bitDepth + 7) / 8;
        var packed = new byte[rowBytes * H];
        var index = new int[W * H];
        for (var y = 0; y < H; y++)
        for (var x = 0; x < W; x++)
        {
            var v = (x + y * 3) % colours;
            index[y * W + x] = v;
            var bit = x * bitDepth;
            packed[y * rowBytes + (bit >> 3)] |= (byte)(v << (8 - bitDepth - (bit & 7)));
        }

        var png = Png(W, H, 3, bitDepth, Rows(packed, rowBytes, H, 1, 2), palette, alpha);
        var bgra = SkinPng.DecodeBgra(png)!;
        for (var i = 0; i < W * H; i++)
        {
            var c = index[i];
            Assert.Equal(new[] { palette[c * 3 + 2], palette[c * 3 + 1], palette[c * 3], alpha[c] }, bgra.AsSpan(i * 4, 4).ToArray());
        }
    }

    // Written by a different encoder (Python zlib with real CRCs): row y uses filter y % 5, so every
    // filter is exercised on data this file's own encoder never touched. Pixel (x, y) is
    // RGBA(x*4, y*4, (x+y)*2, (x+y)%7 == 0 ? 128 : 255).
    [Fact]
    public void Independently_encoded_skin_decodes_exactly()
    {
        var bgra = SkinPng.DecodeBgra(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "Fixtures", "skin-all-filters.png")))!;
        for (var y = 0; y < H; y++)
        for (var x = 0; x < W; x++)
        {
            var o = (y * W + x) * 4;
            Assert.Equal(new[] { (byte)((x + y) * 2), (byte)(y * 4), (byte)(x * 4), (byte)((x + y) % 7 == 0 ? 128 : 255) },
                bgra.AsSpan(o, 4).ToArray());
        }
        Assert.NotNull(SkinFace.FaceWithHat(bgra, W, H));
    }

    [Fact]
    public void Legacy_64x32_skins_decode()
    {
        var rgba = Pattern(64, 32);
        Assert.Equal(ToBgra(rgba), SkinPng.DecodeBgra(Png(64, 32, 6, 8, Rows(rgba, 256, 32, 4, 0))));
    }

    [Fact]
    public void Damaged_or_unsupported_files_give_null()
    {
        var good = Png(W, H, 6, 8, Rows(Pattern(W, H), W * 4, H, 4, 4));
        Assert.NotNull(SkinPng.DecodeBgra(good));

        Assert.Null(SkinPng.DecodeBgra(Array.Empty<byte>()));
        Assert.Null(SkinPng.DecodeBgra(good[..(good.Length / 2)]));                                    // truncated image data
        Assert.Null(SkinPng.DecodeBgra(Png(W, H, 6, 16, new byte[H * (W * 8 + 1)])));      // 16-bit
        Assert.Null(SkinPng.DecodeBgra(Png(W, H, 6, 8, Rows(Pattern(W, H), W * 4, H, 4, 0), interlace: 1)));
        Assert.Null(SkinPng.DecodeBgra(Png(W, H, 3, 8, Rows(new byte[W * H], W, H, 1, 0))));  // palette type with no PLTE
        Assert.Null(SkinPng.DecodeBgra(Png(100, 100, 6, 8, Rows(Pattern(100, 100), 400, 100, 4, 0)))); // not a skin size

        var badFilter = Rows(Pattern(W, H), W * 4, H, 4, 0);
        badFilter[0] = 9;
        Assert.Null(SkinPng.DecodeBgra(Png(W, H, 6, 8, badFilter)));

        var lyingLength = (byte[])good.Clone();
        BinaryPrimitives.WriteInt32BigEndian(lyingLength.AsSpan(33), int.MaxValue);                  // first chunk after IHDR
        Assert.Null(SkinPng.DecodeBgra(lyingLength));
    }

    private static byte[] Pattern(int w, int h)
    {
        var p = new byte[w * h * 4];
        for (var i = 0; i < w * h; i++)
        {
            p[i * 4] = (byte)(i * 7); p[i * 4 + 1] = (byte)(i / w * 13); p[i * 4 + 2] = (byte)(i % w * 5); p[i * 4 + 3] = (byte)(i % 3 == 0 ? 0 : 200 + i % 50);
        }
        return p;
    }

    private static byte[] ToBgra(byte[] rgba)
    {
        var o = new byte[rgba.Length];
        for (var i = 0; i < rgba.Length; i += 4) { o[i] = rgba[i + 2]; o[i + 1] = rgba[i + 1]; o[i + 2] = rgba[i]; o[i + 3] = rgba[i + 3]; }
        return o;
    }

    // Raw rows, each prefixed by its filter byte and filtered with it (PNG spec section 9).
    private static byte[] Rows(byte[] data, int rowBytes, int h, int bpp, byte filter)
    {
        var o = new byte[h * (rowBytes + 1)];
        for (var y = 0; y < h; y++)
        {
            o[y * (rowBytes + 1)] = filter;
            for (var i = 0; i < rowBytes; i++)
            {
                int x = data[y * rowBytes + i];
                int a = i >= bpp ? data[y * rowBytes + i - bpp] : 0;
                int b = y > 0 ? data[(y - 1) * rowBytes + i] : 0;
                int c = i >= bpp && y > 0 ? data[(y - 1) * rowBytes + i - bpp] : 0;
                var predictor = filter switch
                {
                    1 => a,
                    2 => b,
                    3 => (a + b) >> 1,
                    4 => Paeth(a, b, c),
                    _ => 0,
                };
                o[y * (rowBytes + 1) + 1 + i] = (byte)(x - predictor);
            }
        }
        return o;
    }

    private static int Paeth(int a, int b, int c)
    {
        int p = a + b - c, pa = Math.Abs(p - a), pb = Math.Abs(p - b), pc = Math.Abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static byte[] Png(int w, int h, byte colorType, int bitDepth, byte[] filteredRows,
        byte[]? palette = null, byte[]? trns = null, byte interlace = 0)
    {
        using var file = new MemoryStream();
        file.Write(new byte[] { 0x89, (byte)'P', (byte)'N', (byte)'G', 0x0D, 0x0A, 0x1A, 0x0A });
        var ihdr = new byte[13];
        BinaryPrimitives.WriteInt32BigEndian(ihdr, w);
        BinaryPrimitives.WriteInt32BigEndian(ihdr.AsSpan(4), h);
        ihdr[8] = (byte)bitDepth; ihdr[9] = colorType; ihdr[12] = interlace;
        Chunk(file, "IHDR", ihdr);
        if (palette != null) Chunk(file, "PLTE", palette);
        if (trns != null) Chunk(file, "tRNS", trns);
        using (var z = new MemoryStream())
        {
            using (var deflate = new ZLibStream(z, CompressionLevel.Optimal, leaveOpen: true)) deflate.Write(filteredRows);
            Chunk(file, "IDAT", z.ToArray());
        }
        Chunk(file, "IEND", Array.Empty<byte>());
        return file.ToArray();
    }

    // The decoder does not check CRCs (a bad byte fails inflate or the size checks instead), so zeros do.
    private static void Chunk(Stream s, string type, byte[] data)
    {
        Span<byte> len = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(len, data.Length);
        s.Write(len);
        s.Write(Encoding.ASCII.GetBytes(type));
        s.Write(data);
        s.Write(new byte[4]);
    }
}
