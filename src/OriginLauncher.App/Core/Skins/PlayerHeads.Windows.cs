global using HeadImage = System.Windows.Media.ImageSource;
using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace OriginLauncher.App.Core.Skins;

// Windows: WPF's decoder (any PNG flavour, colour-converted to Bgra32) and a frozen BitmapSource,
// which any thread can hand to the UI.
public static partial class PlayerHeads
{
    private static byte[]? DecodeBgra(byte[] png, int width, int height)
    {
        using var stream = new MemoryStream(png, writable: false);
        var frame = BitmapDecoder.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.None).Frames[0];
        if (frame.PixelWidth != width || frame.PixelHeight != height) return null;
        var bgra = new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);
        var pixels = new byte[width * height * 4];
        bgra.CopyPixels(pixels, width * 4, 0);
        return pixels;
    }

    private static HeadImage? ToImage(byte[]? head)
    {
        if (head == null) return null;
        var bitmap = BitmapSource.Create(SkinFace.Size, SkinFace.Size, 96, 96, PixelFormats.Bgra32, null, head, SkinFace.Size * 4);
        bitmap.Freeze();
        return bitmap;
    }
}
