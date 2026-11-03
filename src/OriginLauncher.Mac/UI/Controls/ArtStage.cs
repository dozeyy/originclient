using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;
using Avalonia.Threading;
using OriginLauncher.App.UI;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// The poster backdrop behind every page (twin of the WPF UI/Controls/ArtStage.cs): the version family's art filling
/// the window (UniformToFill, at the drift's 1.05 rest scale), the one darkening (Fill.ArtShade), the version numeral
/// above the shade, and the page dim that every page but Home fades in. The art file is picked like WPF's: the smallest
/// width tier (1280 / 2560 / 3840) that covers the window's PHYSICAL width with the 1.19 headroom, falling back to a
/// smaller tier that exists. The drift (1.05 to 1.10 and a few px of travel, 44 s each way, 15 fps) runs on a timer and
/// is off with motion off (render-shots).
/// Not ported yet: the family crossfade on a version change and the launch push-in (no picker or launch screen on Mac yet).
/// </summary>
public sealed class ArtStage : Panel
{
    public static ArtStage? Instance { get; private set; }
    private static double _stripTop = double.NaN;

    /// <summary>Home's strip starts at <paramref name="stripTop"/> (window coordinates).</summary>
    public static void SetStripTop(double stripTop)
    {
        _stripTop = stripTop;
        Instance?._numeral.SetBand(stripTop);
    }

    private static readonly int[] Tiers = [1280, 2560, 3840];
    private const double ArtHeadroom = 1.19;

    private readonly Panel _stage = new() { RenderTransformOrigin = RelativePoint.Center };
    private readonly Image _art = new()
    {
        Stretch = Stretch.UniformToFill,
        HorizontalAlignment = HorizontalAlignment.Center,
        VerticalAlignment = VerticalAlignment.Center,
    };
    private readonly VersionNumeral _numeral = new();
    private readonly Border _dim;
    private string? _artKey;
    private int _tier;
    private DispatcherTimer? _drift;
    private readonly DateTime _driftStart = DateTime.UtcNow;

    public ArtStage()
    {
        Instance = this;
        ClipToBounds = true;
        IsHitTestVisible = false;
        Background = Find<IBrush>("Brush.Bg");

        RenderOptions.SetBitmapInterpolationMode(_art, BitmapInterpolationMode.HighQuality);
        _stage.RenderTransform = new ScaleTransform(1.05, 1.05);
        _stage.Children.Add(_art);
        Children.Add(_stage);
        // The one darkening: clear down to 40 %, then up to the strong end at the bottom edge.
        Children.Add(new Border { Background = Find<IBrush>("Fill.ArtShade") });
        Children.Add(_numeral);
        if (!double.IsNaN(_stripTop)) _numeral.SetBand(_stripTop);
        _dim = new Border { Background = Find<IBrush>("Brush.PageDim"), Opacity = 0 };
        Children.Add(_dim);
    }

    internal VersionNumeral Numeral => _numeral;

    private static T? Find<T>(string key) where T : class =>
        Application.Current?.TryGetResource(key, null, out var v) == true ? v as T : null;

    /// <summary>Show the family of <paramref name="version"/> and its numeral.</summary>
    public void Show(string? version)
    {
        _numeral.SetText(version ?? "");
        _artKey = FamilyTheme.GroupOf(version).Key;
        LoadArt();
    }

    /// <summary>Home shows the numeral and clear art; every other page dims both.</summary>
    public void SetPage(bool home)
    {
        _dim.Opacity = home ? 0 : 1;
        _numeral.Opacity = home ? 1 : 0;
    }

    protected override void OnSizeChanged(SizeChangedEventArgs e)
    {
        base.OnSizeChanged(e);
        var tier = CurrentTier();
        if (tier != 0 && tier != _tier) LoadArt();
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        if (Motion.Off || _drift != null) return;
        _drift = new DispatcherTimer(TimeSpan.FromMilliseconds(1000.0 / 15), DispatcherPriority.Render, (_, _) => Drift());
        _drift.Start();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        _drift?.Stop();
        _drift = null;
    }

    // 44 s each way, sine in-out: scale 1.05 to 1.10, x -1.2 % to +1.2 % of the width, y +0.8 % to -0.8 % of the height.
    private void Drift()
    {
        var t = (DateTime.UtcNow - _driftStart).TotalSeconds / 44.0;
        var phase = t % 2;
        var p = phase <= 1 ? phase : 2 - phase;
        var e = -(Math.Cos(Math.PI * p) - 1) / 2;
        var s = 1.05 + 0.05 * e;
        var w = Math.Max(Bounds.Width, 960);
        var h = Math.Max(Bounds.Height, 600);
        _stage.RenderTransform = new TransformGroup
        {
            Children =
            {
                new ScaleTransform(s, s),
                new TranslateTransform((-0.012 + 0.024 * e) * w, (0.008 - 0.016 * e) * h),
            },
        };
    }

    internal static int TierFor(double physicalWidth)
    {
        var need = physicalWidth * ArtHeadroom;
        foreach (var tier in Tiers)
            if (tier >= need) return tier;
        return Tiers[^1];
    }

    private int CurrentTier()
    {
        var scale = TopLevel.GetTopLevel(this)?.RenderScaling ?? 1;
        var width = Bounds.Width * scale;
        return width > 0 ? TierFor(width) : 0;
    }

    private void LoadArt()
    {
        if (_artKey == null) return;
        var tier = CurrentTier();
        if (tier == 0) return;   // first layout comes next and loads at the real size
        _tier = tier;
        for (var i = Array.IndexOf(Tiers, tier); i >= 0; i--)
            if (Decode($"{_artKey}-c{Tiers[i]}.jpg") is { } bmp)
            {
                (_art.Source as IDisposable)?.Dispose();
                _art.Source = bmp;
                return;
            }
    }

    /// <summary>One art file from the app's resources, or null when that tier doesn't exist.</summary>
    internal static Bitmap? Decode(string name, int decodeWidth = 0)
    {
        var uri = new Uri($"avares://OriginLauncher/Assets/Art/{name}");
        if (!AssetLoader.Exists(uri)) return null;
        using var stream = AssetLoader.Open(uri);
        return decodeWidth > 0
            ? Bitmap.DecodeToWidth(stream, decodeWidth, BitmapInterpolationMode.HighQuality)
            : new Bitmap(stream);
    }
}
