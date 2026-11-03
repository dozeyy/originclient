global using HeadImage = Avalonia.Media.Imaging.Bitmap;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Platform;

namespace OriginLauncher.App.Core.Skins;

// Mac: the managed decoder (SkinPng.Mac.cs, no UI toolkit needed to read a skin) and an Avalonia
// bitmap built from the 8x8 head. Avalonia bitmaps are immutable once made, so any thread can hand
// one to the UI. Making one needs the Avalonia platform to be up (the launcher's UI start).
public static partial class PlayerHeads
{
    private static byte[]? DecodeBgra(byte[] png, int width, int height)
    {
        var pixels = SkinPng.DecodeBgra(png);
        return pixels != null && pixels.Length == width * height * 4 ? pixels : null;
    }

    private static HeadImage? ToImage(byte[]? head)
    {
        if (head == null) return null;
        var pin = GCHandle.Alloc(head, GCHandleType.Pinned);
        try
        {
            // The constructor copies the pixels, so the pin only has to last for this call.
            return new HeadImage(PixelFormat.Bgra8888, AlphaFormat.Unpremul, pin.AddrOfPinnedObject(),
                new PixelSize(SkinFace.Size, SkinFace.Size), new Vector(96, 96), SkinFace.Size * 4);
        }
        finally
        {
            pin.Free();
        }
    }
}
