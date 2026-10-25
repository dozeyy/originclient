using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The Origin mark: the official logo image (Brand.Logo in Theme/Brand.xaml) and nothing else. Size it with
/// Width/Height (use the Size.Logo.* tokens). It is never redrawn, rotated, glowed or recoloured; fading,
/// translating and uniform scaling of the whole control are fine (design system 1.4).
/// </summary>
public sealed class BrandLogo : Image
{
    public BrandLogo()
    {
        Stretch = Stretch.Uniform;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.HighQuality);
        SnapsToDevicePixels = true;
        // A design-time or very early construction has no application resources; the control is then just empty.
        if (Application.Current?.TryFindResource("Brand.Logo") is ImageSource logo) Source = logo;
    }
}
