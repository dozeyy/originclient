using System.Buffers.Binary;
using System.IO;
using System.IO.Compression;

namespace OriginLauncher.App.Core.Skins;

/// <summary>
/// Mac only (Windows decodes with WPF): a skin PNG to straight-alpha BGRA pixels, in managed code,
/// so a head never needs the UI toolkit or a native image library just to read 64x64 pixels, and
/// the decode is the same on every Mac. Covers what skin files are: 8-bit greyscale, grey+alpha,
/// RGB, RGBA, and palette images at 1/2/4/8 bits (with tRNS palette alpha), non-interlaced.
/// Anything else, or any damaged byte, returns null: the player keeps their default head.
/// Untrusted input: the size comes from IHDR and must already be a skin size (SkinFace.IsSkinSize),
/// so the inflated data is bounded before a single pixel is decoded, and every chunk length is
/// checked against the file.
/// </summary>
internal static class SkinPng
{
    public static byte[]? DecodeBgra(byte[] png)
    {
        try
        {
            return Decode(png);
        }
        catch (Exception e) when (e is InvalidDataException or IOException or ArgumentException or IndexOutOfRangeException or OverflowException)
        {
            return null;
        }
    }

    private static byte[]? Decode(byte[] png)
    {
        if (SkinFace.PngSize(png) is not { } size || !SkinFace.IsSkinSize(size.Width, size.Height)) return null;
        int width = size.Width, height = size.Height;
        int bitDepth = -1, colorType = -1;
        byte[]? palette = null, paletteAlpha = null;
        using var idat = new MemoryStream();

        var pos = 8;
        while (pos + 12 <= png.Length)
        {
            var length = BinaryPrimitives.ReadInt32BigEndian(png.AsSpan(pos));
            if (length < 0 || length > png.Length - pos - 12) return null;
            var type = System.Text.Encoding.ASCII.GetString(png, pos + 4, 4);
            var data = png.AsSpan(pos + 8, length);
            switch (type)
            {
                case "IHDR":
                    if (length < 13) return null;
                    bitDepth = data[8];
                    colorType = data[9];
                    if (data[10] != 0 || data[11] != 0 || data[12] != 0) return null; // deflate, filter set 0, no interlace
                    break;
                case "PLTE":
                    palette = data.ToArray();
                    break;
                case "tRNS":
                    paletteAlpha = data.ToArray();
                    break;
                case "IDAT":
                    idat.Write(data);
                    break;
                case "IEND":
                    pos = png.Length;
                    continue;
            }
            pos += 12 + length;
        }

        var channels = colorType switch { 0 => 1, 2 => 3, 3 => 1, 4 => 2, 6 => 4, _ => 0 };
        if (channels == 0) return null;
        if (colorType == 3 ? bitDepth is not (1 or 2 or 4 or 8) : bitDepth != 8) return null;
        if (colorType == 3 && (palette == null || palette.Length % 3 != 0)) return null;

        var bitsPerPixel = channels * bitDepth;
        var rowBytes = (width * bitsPerPixel + 7) / 8;
        var bpp = Math.Max(1, bitsPerPixel / 8); // filter byte distance
        var raw = new byte[height * (rowBytes + 1)];
        idat.Position = 0;
        using (var inflate = new ZLibStream(idat, CompressionMode.Decompress))
        {
            var read = 0;
            while (read < raw.Length)
            {
                var n = inflate.Read(raw, read, raw.Length - read);
                if (n == 0) return null; // truncated image data
                read += n;
            }
        }

        var pixels = new byte[width * height * 4];
        var prev = new byte[rowBytes];
        var cur = new byte[rowBytes];
        for (var y = 0; y < height; y++)
        {
            var rowStart = y * (rowBytes + 1);
            var filter = raw[rowStart];
            Array.Copy(raw, rowStart + 1, cur, 0, rowBytes);
            if (!Unfilter(filter, cur, prev, bpp)) return null;
            for (var x = 0; x < width; x++)
            {
                byte r, g, b, a = 255;
                switch (colorType)
                {
                    case 0: r = g = b = cur[x]; break;
                    case 4: r = g = b = cur[x * 2]; a = cur[x * 2 + 1]; break;
                    case 2: r = cur[x * 3]; g = cur[x * 3 + 1]; b = cur[x * 3 + 2]; break;
                    case 6: r = cur[x * 4]; g = cur[x * 4 + 1]; b = cur[x * 4 + 2]; a = cur[x * 4 + 3]; break;
                    default:
                        var bit = x * bitDepth;
                        var index = (cur[bit >> 3] >> (8 - bitDepth - (bit & 7))) & ((1 << bitDepth) - 1);
                        if (index * 3 + 2 >= palette!.Length) return null;
                        r = palette[index * 3]; g = palette[index * 3 + 1]; b = palette[index * 3 + 2];
                        if (paletteAlpha != null && index < paletteAlpha.Length) a = paletteAlpha[index];
                        break;
                }
                var o = (y * width + x) * 4;
                pixels[o] = b; pixels[o + 1] = g; pixels[o + 2] = r; pixels[o + 3] = a;
            }
            (prev, cur) = (cur, prev);
        }
        return pixels;
    }

    // PNG's five row filters (spec section 9), in place on `cur`.
    private static bool Unfilter(byte filter, byte[] cur, byte[] prev, int bpp)
    {
        switch (filter)
        {
            case 0:
                return true;
            case 1:
                for (var i = bpp; i < cur.Length; i++) cur[i] += cur[i - bpp];
                return true;
            case 2:
                for (var i = 0; i < cur.Length; i++) cur[i] += prev[i];
                return true;
            case 3:
                for (var i = 0; i < cur.Length; i++)
                    cur[i] += (byte)(((i >= bpp ? cur[i - bpp] : 0) + prev[i]) >> 1);
                return true;
            case 4:
                for (var i = 0; i < cur.Length; i++)
                {
                    int a = i >= bpp ? cur[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
                    int p = a + b - c, pa = Math.Abs(p - a), pb = Math.Abs(p - b), pc = Math.Abs(p - c);
                    cur[i] += (byte)(pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                }
                return true;
            default:
                return false;
        }
    }
}
