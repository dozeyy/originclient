using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// The Origin mark: the official logo image (textures/ui/origin_logo.png, App resource Brand.Logo) and nothing else.
/// Size it with Width/Height (the Size.Logo.* tokens). Never redrawn, rotated, glowed or recoloured (DECISIONS, Look).
/// </summary>
public sealed class BrandLogo : Image
{
    public BrandLogo()
    {
        Stretch = Stretch.Uniform;
        RenderOptions.SetBitmapInterpolationMode(this, BitmapInterpolationMode.HighQuality);
        if (Application.Current?.TryGetResource("Brand.Logo", null, out var logo) == true && logo is Bitmap bmp) Source = bmp;
    }
}
