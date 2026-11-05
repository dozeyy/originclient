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
/// smaller tier that exists, decoded off the UI thread with the newest pick winning and the last 3 kept (WPF's PERF fix:
/// a 2560-3840 wide JPEG decoded on the UI thread was a hitch on every version pick and resize, PERF-2). The drift (1.05
/// to 1.10 and a few px of travel, 44 s each way, 15 fps) runs on a timer, is off with motion off (render-shots), and
/// holds where it stands while nobody can see it: another page (under the page dim), the window minimised or in the
/// background (WPF's LU-14 pause, PERF-3).
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
    // The drift's own clock: it only runs while the drift does, so a pause resumes exactly where the art stood.
    private readonly System.Diagnostics.Stopwatch _driftClock = new();
    // Set once; each tick changes their values instead of building a new transform group 15 times a second.
    private readonly ScaleTransform _driftScale = new(1.05, 1.05);
    private readonly TranslateTransform _driftShift = new();
    private bool _home = true, _windowAway, _attached;

    public ArtStage()
    {
        Instance = this;
        ClipToBounds = true;
        IsHitTestVisible = false;
        Background = Find<IBrush>("Brush.Bg");

        RenderOptions.SetBitmapInterpolationMode(_art, BitmapInterpolationMode.HighQuality);
        _stage.RenderTransform = new TransformGroup { Children = { _driftScale, _driftShift } };
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
        var key = FamilyTheme.GroupOf(version).Key;
        // The same family at the same size is the same picture (1.21.1 -> 1.21.4): nothing to load.
        if (key == _artKey && _tier != 0 && _tier == CurrentTier()) return;
        _artKey = key;
        LoadArt();
    }

    /// <summary>Home shows the numeral and clear art; every other page dims both (and holds the drift).</summary>
    public void SetPage(bool home)
    {
        _dim.Opacity = home ? 0 : 1;
        _numeral.Opacity = home ? 1 : 0;
        _home = home;
        ApplyDrift();
    }

    /// <summary>MainWindow: the window is minimised or in the background (the player is in game, or in another app).</summary>
    public void SetWindowAway(bool away)
    {
        _windowAway = away;
        ApplyDrift();
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
        _attached = true;
        ApplyDrift();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        _attached = false;
        ApplyDrift();
    }

    // Runs only while someone can see it: attached, on Home, the window up front, motion on. A 15 fps full-window
    // bitmap redrawn forever costs CPU/GPU (a MacBook's battery) the game wants. Stopped, it holds where it stands.
    private void ApplyDrift()
    {
        var run = _attached && _home && !_windowAway && !Motion.Off;
        if (run)
        {
            _drift ??= new DispatcherTimer(TimeSpan.FromMilliseconds(1000.0 / 15), DispatcherPriority.Render, (_, _) => Drift());
            if (!_drift.IsEnabled) _drift.Start();
            _driftClock.Start();
        }
        else
        {
            _drift?.Stop();
            _driftClock.Stop();
        }
    }

    // 44 s each way, sine in-out: scale 1.05 to 1.10, x -1.2 % to +1.2 % of the width, y +0.8 % to -0.8 % of the height.
    private void Drift()
    {
        var t = _driftClock.Elapsed.TotalSeconds / 44.0;
        var phase = t % 2;
        var p = phase <= 1 ? phase : 2 - phase;
        var e = -(Math.Cos(Math.PI * p) - 1) / 2;
        var s = 1.05 + 0.05 * e;
        var w = Math.Max(Bounds.Width, 960);
        var h = Math.Max(Bounds.Height, 600);
        _driftScale.ScaleX = s;
        _driftScale.ScaleY = s;
        _driftShift.X = (-0.012 + 0.024 * e) * w;
        _driftShift.Y = (0.008 - 0.016 * e) * h;
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
        var key = _artKey;
        if (CacheGet(key, tier) is { } hit)
        {
            SetArt(hit);
            return;
        }
        // Synchronous only for render shots (a shot must show the art it asked for). Everyone else decodes on the
        // pool, and only the newest request lands (a fast run of picks never shows an older family last).
        if (RenderShots.Active)
        {
            if (DecodeBest(key, tier) is { } now) SetArt(CachePut(key, tier, now));
            return;
        }
        _ = Task.Run(() => DecodeBest(key, tier)).ContinueWith(t =>
        {
            if (t.Result is not { } bmp) return;
            var kept = CachePut(key, tier, bmp);
            if (_artKey == key && _tier == tier) SetArt(kept);
        }, TaskScheduler.FromCurrentSynchronizationContext());
    }

    // The tier asked for, else the next smaller one that exists. Off the UI thread is fine: Avalonia's Bitmap reads
    // the stream whole into its own memory.
    private static Bitmap? DecodeBest(string key, int tier)
    {
        try
        {
            for (var i = Array.IndexOf(Tiers, tier); i >= 0; i--)
                if (Decode($"{key}-c{Tiers[i]}.jpg") is { } bmp) return bmp;
        }
        catch (Exception)
        {
            // fail soft: the dark background stays
        }
        return null;
    }

    private void SetArt(Bitmap bmp)
    {
        if (ReferenceEquals(_art.Source, bmp)) return;
        var old = _art.Source as Bitmap;
        _art.Source = bmp;
        // A picture the cache no longer holds was only kept alive by the screen: free it now.
        if (old != null && !CacheHolds(old)) old.Dispose();
    }

    // The last 3 full-window pictures (family + tier), most recent last: back and forth between families is instant.
    private const int CacheSize = 3;
    private static readonly List<(string Key, Bitmap Bmp)> Cache = new();

    private static Bitmap? CacheGet(string key, int tier)
    {
        var id = $"{key}@{tier}";
        var i = Cache.FindIndex(c => c.Key == id);
        if (i < 0) return null;
        var hit = Cache[i];
        Cache.RemoveAt(i);
        Cache.Add(hit);
        return hit.Bmp;
    }

    // Returns the bitmap to show: an equal entry already cached (a second decode of the same picture) wins.
    private Bitmap CachePut(string key, int tier, Bitmap bmp)
    {
        var id = $"{key}@{tier}";
        if (CacheGet(key, tier) is { } existing)
        {
            if (!ReferenceEquals(existing, bmp)) bmp.Dispose();
            return existing;
        }
        Cache.Add((id, bmp));
        while (Cache.Count > CacheSize)
        {
            var evicted = Cache[0].Bmp;
            Cache.RemoveAt(0);
            // The picture on screen stays alive until SetArt replaces it.
            if (!ReferenceEquals(_art.Source, evicted)) evicted.Dispose();
        }
        return bmp;
    }

    private static bool CacheHolds(Bitmap bmp) => Cache.Exists(c => ReferenceEquals(c.Bmp, bmp));

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
