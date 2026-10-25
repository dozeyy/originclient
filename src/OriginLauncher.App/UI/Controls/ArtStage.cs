using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// The backdrop behind every page: the version family's art in full colour (Will's approved Workbench Home:
/// no greyscale), darkened only by one soft shade that starts 40 % of the way down, with the giant version numeral
/// (VersionNumeral) in front of it.
///
/// Layers (back to front): two colour art layers (the family crossfade swaps between them), the shade
/// (Fill.ArtShade, 7:1 text over art at the bottom row), the numeral with its orbit thread, and the page dim
/// (every page but Home). There is no top scrim: the top bar is its own surface (MainWindow BarGlass /
/// BarSurface), so the art runs full strength right up under it (DESIGN-SYSTEM 12).
///
/// Art is decoded off the UI thread, frozen, and picked by the window's physical
/// width with room for the 1.19x peak scale (1280 / 2560 / 3840 wide, see TierFor).
/// Motion is opacity, translate and scale only; the slow 44 s drift
/// runs at 15 fps (nothing in it moves faster than a fraction of a pixel a frame).
/// </summary>
public sealed class ArtStage : Grid
{
    public static ArtStage? Instance { get; private set; }

    // Home picks its version in its constructor, which runs before MainWindow builds the stage
    // (HomePage is a field initializer there). A request with no stage yet is kept here and
    // shown when the stage is built, so cold start opens on the art and numeral, not a blank.
    private static string? _pendingVersion;
    private static bool _hasPending;

    // Where Home's strip starts, reported the same way (HomePage lays out before the stage exists).
    private static double _stripTop = double.NaN;

    /// <summary>
    /// Home's strip starts at <paramref name="stripTop"/> (window coordinates): the numeral stands in the band between
    /// the top bar and that line. Kept for a stage that is not built yet.
    /// </summary>
    public static void SetStripTop(double stripTop)
    {
        _stripTop = stripTop;
        Instance?._numeral.SetBand(stripTop);
    }

    /// <summary>The render tour's manual layout raises no SizeChanged: place the numeral for the size it was laid out at.</summary>
    internal void TourRelayout() => _numeral.Relayout();

    /// <summary>The numeral's ink and band, for the render tour's measurements.</summary>
    internal (Rect? Ink, double BandTop, double BandBottom) NumeralGeometry =>
        (_numeral.InkRect, _numeral.CurrentBand.Top, _numeral.CurrentBand.Bottom);

    /// <summary>The orbit thread's capsule size, for the render tour's measurements.</summary>

    /// <summary>Show <paramref name="version"/>'s family now, or as soon as the stage exists.</summary>
    public static void Request(string? version, bool animate)
    {
        if (Instance is { IsLoaded: true } stage)
        {
            stage.Show(version, animate);
            return;
        }
        _pendingVersion = version;
        _hasPending = true;
    }

    private readonly Grid _stage = new() { RenderTransformOrigin = new Point(0.5, 0.5) };
    private readonly ScaleTransform _launchZoom = new();
    private readonly TranslateTransform _drift = new();
    private readonly ScaleTransform _driftScale = new(1.05, 1.05);
    private readonly Layer[] _art = [new(), new()];
    private int _front;
    private readonly VersionNumeral _numeral = new();
    private readonly Border _dim;
    private string? _artKey;
    private int _tier;   // 0 until the first layout says how wide the window really is
    private bool _home = true;

    private sealed class Layer
    {
        public readonly Image Image = new() { Stretch = Stretch.UniformToFill, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        public readonly TranslateTransform Shift = new();
        public readonly ScaleTransform Scale = new();
        public Layer()
        {
            Image.RenderTransformOrigin = new Point(0.5, 0.5);
            Image.RenderTransform = new TransformGroup { Children = { Scale, Shift } };
            RenderOptions.SetBitmapScalingMode(Image, BitmapScalingMode.HighQuality);
        }
    }

    public ArtStage()
    {
        Instance = this;
        ClipToBounds = true;
        IsHitTestVisible = false;
        Background = (Brush)Application.Current.FindResource("Brush.Bg");

        _stage.RenderTransform = new TransformGroup { Children = { _driftScale, _drift, _launchZoom } };
        _art[1].Image.Opacity = 0;
        _stage.Children.Add(_art[0].Image);
        _stage.Children.Add(_art[1].Image);
        Children.Add(_stage);

        // The one darkening: clear down to 40 %, then up to the strong end at the bottom edge (Fill.ArtShade). Full
        // height, so it follows the window instead of a fixed 330 px that crept into the numeral on a short one.
        Children.Add(new Border { Background = (Brush)Application.Current.FindResource("Fill.ArtShade") });
        // The numeral sits ABOVE the shade, which is clear where the figures stand.
        Children.Add(_numeral);
        if (!double.IsNaN(_stripTop)) _numeral.SetBand(_stripTop);
        _dim = new Border { Background = (Brush)Application.Current.FindResource("Brush.PageDim"), Opacity = 0 };
        Children.Add(_dim);

        SizeChanged += (_, _) => PickResolution();
        Loaded += (_, _) =>
        {
            // After the first layout, so the art is decoded once at the size the window really is.
            if (_hasPending)
            {
                _hasPending = false;
                Show(_pendingVersion, animate: false);
            }
            StartDrift();
        };
    }

    /// <summary>Show the family of <paramref name="version"/> and its numeral (first frame: no animation).</summary>
    public void Show(string? version, bool animate)
    {
        _numeral.SetText(version ?? "", animate);
        var key = FamilyTheme.GroupOf(version).Key;
        if (key == _artKey) return;
        var first = _artKey == null;
        _artKey = key;
        if (first || !animate || Motion.Off)
        {
            Load(_art[_front].Image, key, "c", fadeIn: first && !Motion.Off);
            return;
        }

        // Old layer drifts left and fades; the new one slides in from the right, settling from 1.06.
        var old = _art[_front];
        _front = 1 - _front;
        var next = _art[_front];
        Panel.SetZIndex(old.Image, 0);
        Panel.SetZIndex(next.Image, 1);
        Load(next.Image, key, "c", fadeIn: false, then: () =>
        {
            next.Image.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 700));
            next.Shift.BeginAnimation(TranslateTransform.XProperty, Motion.FromTo(48, 0, 700));
            next.Scale.BeginAnimation(ScaleTransform.ScaleXProperty, Motion.FromTo(1.06, 1, 700));
            next.Scale.BeginAnimation(ScaleTransform.ScaleYProperty, Motion.FromTo(1.06, 1, 700));
            old.Image.BeginAnimation(OpacityProperty, Motion.FromTo(1, 0, 560));
            old.Shift.BeginAnimation(TranslateTransform.XProperty, Motion.FromTo(0, -48, 560));
        });
    }

    /// <summary>Home shows the numeral and clear art; every other page dims both.</summary>
    public void SetPage(bool home)
    {
        if (home == _home) return;
        _home = home;
        _dim.BeginAnimation(OpacityProperty, Motion.To(home ? 0 : 1, 260));
        _numeral.BeginAnimation(OpacityProperty, Motion.To(home ? 1 : 0, 260));
    }

    /// <summary>The launch push-in: the art slowly zooms to 1.08 (12 s) as the numeral fades; it is already in colour.</summary>
    public void BeginLaunch()
    {
        _numeral.BeginAnimation(OpacityProperty, Motion.To(0, 260));
        var zoom = new DoubleAnimation(1.08, Motion.Ms(12000));
        _launchZoom.BeginAnimation(ScaleTransform.ScaleXProperty, zoom);
        _launchZoom.BeginAnimation(ScaleTransform.ScaleYProperty, zoom);
    }

    public void EndLaunch()
    {
        if (_home) _numeral.BeginAnimation(OpacityProperty, Motion.To(1, 420));
        _launchZoom.BeginAnimation(ScaleTransform.ScaleXProperty, Motion.To(1, 600));
        _launchZoom.BeginAnimation(ScaleTransform.ScaleYProperty, Motion.To(1, 600));
    }

    private void StartDrift()
    {
        if (Motion.Off) return;
        Animate(_drift, TranslateTransform.XProperty, -0.012, 0.012, w: true);
        Animate(_drift, TranslateTransform.YProperty, 0.008, -0.008, w: false);
        var s = new DoubleAnimation(1.05, 1.10, new Duration(TimeSpan.FromSeconds(44)))
        { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = Motion.InOut };
        Timeline.SetDesiredFrameRate(s, 15);
        _driftScale.BeginAnimation(ScaleTransform.ScaleXProperty, s);
        _driftScale.BeginAnimation(ScaleTransform.ScaleYProperty, s);

        void Animate(TranslateTransform t, DependencyProperty p, double from, double to, bool w)
        {
            var size = w ? Math.Max(ActualWidth, 960) : Math.Max(ActualHeight, 600);
            var a = new DoubleAnimation(from * size, to * size, new Duration(TimeSpan.FromSeconds(44)))
            { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = Motion.InOut };
            Timeline.SetDesiredFrameRate(a, 15);
            t.BeginAnimation(p, a);
        }
    }

    // Art widths on disk, smallest first. A file only exists for a tier the source can fill natively
    // (tools/launcher-art/build_art.py), so a missing tier falls back to the next one down.
    private static readonly int[] Tiers = [1280, 2560, 3840];

    // The art is drawn at up to 1.10 (drift) x 1.08 (launch push-in) = 1.19 of the window, so a tier
    // must hold that many pixels across or the stage stretches it and the art goes soft.
    private const double ArtHeadroom = 1.19;

    /// <summary>The smallest tier that still covers a window of <paramref name="physicalWidth"/> pixels with headroom.</summary>
    internal static int TierFor(double physicalWidth)
    {
        var need = physicalWidth * ArtHeadroom;
        foreach (var tier in Tiers)
            if (tier >= need) return tier;
        return Tiers[^1];
    }

    // The tier for the window as laid out now; 0 until layout gives it a width (then nothing loads yet,
    // so the first decode is at the real size and never repeated).
    private int CurrentTier()
    {
        var width = ActualWidth * VisualTreeHelper.GetDpi(this).DpiScaleX;
        return width > 0 ? TierFor(width) : 0;
    }

    // Reload the shown family if the window's size or DPI moved it to another tier.
    private void PickResolution()
    {
        var tier = CurrentTier();
        if (tier == 0 || tier == _tier) return;
        if (_tier != 0) Cache.Clear();   // other tiers' frames are dead weight now (shown layers keep their own)
        _tier = tier;
        if (_artKey == null) return;
        Load(_art[_front].Image, _artKey, "c", fadeIn: false);
    }

    // Decoded art by file name, least recently used out first. Three frames = the current family plus the two
    // families the arrow keys step to, so stepping and the crossfade never wait on a decode (six held both the
    // grey and colour layers before Home went colour only); at 3840 that is a few hundred MB at most, at 1280
    // about 20 MB. A layer on
    // screen holds its own reference, so eviction never blanks it.
    private const int CacheFrames = 3;
    private static readonly Dictionary<string, (BitmapSource Bmp, long Stamp)> Cache = new();
    private static long _stamp;

    private static BitmapSource? CacheGet(string name)
    {
        if (!Cache.TryGetValue(name, out var e)) return null;
        Cache[name] = (e.Bmp, ++_stamp);
        return e.Bmp;
    }

    private static void CachePut(string name, BitmapSource bmp)
    {
        Cache[name] = (bmp, ++_stamp);
        while (Cache.Count > CacheFrames)
            Cache.Remove(Cache.MinBy(kv => kv.Value.Stamp).Key);
    }

    // Files known not to exist (a family with no file at some tier), so a miss costs one failed probe ever,
    // not a thrown-and-caught exception per load.
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, byte> Absent = new();

    /// <summary>The file that answers a request: the tier's own, else the next one down not known to be missing.</summary>
    private static string? Candidate(string key, string kind, int tier)
    {
        for (var i = Array.IndexOf(Tiers, tier); i >= 0; i--)
        {
            var name = $"{key}-{kind}{Tiers[i]}.jpg";
            if (!Absent.ContainsKey(name)) return name;
        }
        return null;
    }

    /// <summary>Decode the best file for the tier, remembering any tier that turns out to be missing.</summary>
    private static (string Name, BitmapSource Bmp)? DecodeBest(string key, string kind, int tier)
    {
        while (Candidate(key, kind, tier) is { } name)
        {
            if (Decode(name) is { } bmp) return (name, bmp);
            Absent[name] = 0;
        }
        return null;
    }

    private void Load(Image target, string key, string kind, bool fadeIn, Action? then = null)
    {
        if (_tier == 0) _tier = CurrentTier();
        if (_tier == 0) return;   // no layout yet; PickResolution loads the shown family when there is one
        var tier = _tier;
        if (Candidate(key, kind, tier) is { } name && CacheGet(name) is { } hit)
        {
            Set(target, hit, fadeIn, then);
            return;
        }
        if (Motion.Off)
        {
            var d = DecodeBest(key, kind, tier);
            if (d is { } got) CachePut(got.Name, got.Bmp);
            Set(target, d?.Bmp, fadeIn, then);
            return;
        }
        _ = System.Threading.Tasks.Task.Run(() => DecodeBest(key, kind, tier)).ContinueWith(t =>
        {
            if (t.Result is { } got)
            {
                CachePut(got.Name, got.Bmp);
                // Only the newest request for this layer lands.
                if (_artKey == key && _tier == tier) Set(target, got.Bmp, fadeIn, then);
            }
        }, System.Threading.Tasks.TaskScheduler.FromCurrentSynchronizationContext());
    }

    private static void Set(Image target, BitmapSource? bmp, bool fadeIn, Action? then)
    {
        target.Source = bmp;
        if (fadeIn) target.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 320));
        then?.Invoke();
    }

    // Off the UI thread is fine: the stream is read whole and the bitmap frozen.
    internal static BitmapSource? Decode(string name, int decodeWidth = 0)
    {
        try
        {
            var info = Application.GetResourceStream(new Uri($"pack://application:,,,/Assets/Art/{name}", UriKind.Absolute));
            if (info == null) return null;
            using var stream = info.Stream;
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            if (decodeWidth > 0) bmp.DecodePixelWidth = decodeWidth;
            bmp.StreamSource = stream;
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch
        {
            return null; // fail soft: the dark background stays
        }
    }
}

