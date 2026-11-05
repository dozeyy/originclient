using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using OriginLauncher.App.UI;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Shots;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Shell;

// Everything behind the pages (DESIGN-SYSTEM.md sections 11.6, 12, 13): the selected family's colour art
// (full colour, never greyscale, tier picked from the window's physical width), Fill.ArtShade, the Home
// numeral centred in the band between the bar and the strip, and the page dim the other pages sit on.
// Nothing is drawn around the numeral (12.1). The art crossfades in motion.scene when the family changes.
// The full-window JPEG is decoded off the UI thread (newest pick wins) and the last 3 are kept, like WPF's ArtStage: a
// 1280-3840 wide decode on the UI thread was a hitch on the first pick of each family (PERF-2).
public sealed class ArtStage : Panel
{
    private readonly Image _back = new() { Stretch = Stretch.UniformToFill };
    private readonly Image _front = new() { Stretch = Stretch.UniformToFill };
    private readonly Numeral _numeral = new();
    private readonly Border _dim = new() { Background = Palette.PageDim, Opacity = 0, IsHitTestVisible = false };
    private string? _artKey;
    private int _tier;
    private bool _home = true;

    public ArtStage()
    {
        ClipToBounds = true;
        IsHitTestVisible = false;
        Background = Palette.Bg;
        foreach (var i in new[] { _back, _front })
            RenderOptions.SetBitmapInterpolationMode(i, BitmapInterpolationMode.HighQuality);
        if (!Motion.Off)
        {
            _front.Transitions = [new DoubleTransition { Property = OpacityProperty, Duration = Motion.Scene }];
            _dim.Transitions = [new DoubleTransition { Property = OpacityProperty, Duration = Motion.Scene }];
            _numeral.Transitions = [new DoubleTransition { Property = OpacityProperty, Duration = Motion.Scene }];
        }
        Children.Add(_back);
        Children.Add(_front);
        Children.Add(new Border { Background = Palette.ArtShade });
        Children.Add(_numeral);
        Children.Add(_dim);
        SizeChanged += (_, _) => PickTier();
    }

    /// <summary>The bar's bottom edge to the strip's top edge: the band the Home numeral centres in.</summary>
    public void SetBand(double barBottom, double stripTop) => _numeral.SetBand(barBottom, stripTop);

    public (Rect Ink, double BandTop, double BandBottom) NumeralGeometry => _numeral.Geometry;

    public void Show(string? version)
    {
        _numeral.Text = version ?? "";
        var key = FamilyTheme.GroupOf(version).Key;
        if (key == _artKey) return;
        var first = _artKey == null;
        _artKey = key;
        var tier = _tier == 0 ? 1280 : _tier;
        // Cached (back to a family seen lately) or a render run (a shot must show its art): at once.
        var hit = CacheGet(key, tier);
        if (hit != null || ShotRunner.Active)
        {
            Present(hit ?? Load(key, tier), first);
            return;
        }
        _ = Task.Run(() => Load(key, tier)).ContinueWith(t =>
        {
            // Only the newest pick lands: a fast run of picks never ends on an older family.
            if (_artKey == key) Present(t.Result, first);
        }, TaskScheduler.FromCurrentSynchronizationContext());
    }

    private void Present(Bitmap? bmp, bool first)
    {
        if (first || Motion.Off)
        {
            _front.Source = bmp;
            _back.Source = null;
            return;
        }
        // Crossfade: the old picture stays behind while the new one fades in on top.
        _back.Source = _front.Source;
        _front.Transitions = null;
        _front.Opacity = 0;
        _front.Source = bmp;
        _front.Transitions = [new DoubleTransition { Property = OpacityProperty, Duration = Motion.Scene }];
        _front.Opacity = 1;
    }

    /// <summary>Home shows the numeral on clean art; every other page sits on the art under PageDim.</summary>
    public void SetPage(bool home)
    {
        if (home == _home) return;
        _home = home;
        _dim.Opacity = home ? 0 : 1;
        _numeral.Opacity = home ? 1 : 0;
    }

    /// <summary>While the launch cover shows, the numeral steps aside (the cover names the version).</summary>
    public void SetLaunching(bool on) => _numeral.Opacity = on || !_home ? 0 : 1;

    // Tiers 1280 / 2560 / 3840: the smallest at least 1.19 x the physical width (the drift's headroom).
    private static readonly int[] Tiers = [1280, 2560, 3840];

    private void PickTier()
    {
        var scale = TopLevel.GetTopLevel(this)?.RenderScaling ?? 1;
        var need = Bounds.Width * scale * 1.19;
        var tier = Tiers.FirstOrDefault(t => t >= need, Tiers[^1]);
        if (tier == _tier) return;
        _tier = tier;
        if (_artKey is not { } key) return;
        var hit = CacheGet(key, tier);
        if (hit != null || ShotRunner.Active)
        {
            _front.Source = hit ?? Load(key, tier);
            return;
        }
        _ = Task.Run(() => Load(key, tier)).ContinueWith(t =>
        {
            if (_artKey == key && _tier == tier && t.Result != null) _front.Source = t.Result;
        }, TaskScheduler.FromCurrentSynchronizationContext());
    }

    // The last 3 full-window pictures (family + the tier asked for), least recently used first. Decoded here, not
    // through Assets.Bitmap, whose cache keeps every file forever (the art is up to 3840 wide): a dropped picture is
    // freed by the GC once neither image layer shows it.
    private const int CacheSize = 3;
    private static readonly List<(string Id, Bitmap Bmp)> Cache = new();

    private static Bitmap? CacheGet(string key, int tier)
    {
        var id = $"{key}@{tier}";
        lock (Cache)
        {
            var at = Cache.FindIndex(c => c.Id == id);
            if (at < 0) return null;
            var hit = Cache[at];
            Cache.RemoveAt(at);
            Cache.Add(hit);
            return hit.Bmp;
        }
    }

    // The tier asked for, else the next smaller one that exists. Safe off the UI thread: the stream is read whole.
    private static Bitmap? Load(string key, int tier)
    {
        if (CacheGet(key, tier) is { } hit) return hit;
        for (var i = Array.IndexOf(Tiers, tier); i >= 0; i--)
        {
            Bitmap bmp;
            try
            {
                var uri = Assets.Uri($"Assets/Art/{key}-c{Tiers[i]}.jpg");
                if (!Avalonia.Platform.AssetLoader.Exists(uri)) continue;
                using var stream = Avalonia.Platform.AssetLoader.Open(uri);
                bmp = new Bitmap(stream);
            }
            catch (Exception)
            {
                continue; // fail soft: try the smaller tier, else the dark background stays
            }
            var id = $"{key}@{tier}";
            lock (Cache)
            {
                Cache.RemoveAll(c => c.Id == id);
                Cache.Add((id, bmp));
                while (Cache.Count > CacheSize) Cache.RemoveAt(0);
            }
            return bmp;
        }
        return null;
    }
}

// The Home numeral (11.6 Home placement): Inter 800 at natural width, -0.04 em tracking, parchment at 94 %,
// figures 32 % of the window height (never more than 60 % of the band), ink at most 2/3 of the width, the
// ink's middle on the window's middle and the band's middle (side bearings and the ink top/bottom are
// measured from the real glyph outlines, so a "1" at either end does not tip it). The shaped outline is
// cached per text + size, not rebuilt per frame.
public sealed class Numeral : Control
{
    private static readonly Typeface Face = new(Assets.Inter, FontStyle.Normal, FontWeight.ExtraBold);
    private string _text = "";
    private double _barBottom = Sizes.Bar;
    private double _stripTop = double.NaN;
    private Geometry? _ink;
    private (string, double) _inkKey;
    private Rect _inkRect;

    public Numeral() { IsHitTestVisible = false; }

    public string Text
    {
        get => _text;
        set { if (_text == value) return; _text = value; InvalidateVisual(); }
    }

    public void SetBand(double barBottom, double stripTop)
    {
        _barBottom = barBottom;
        _stripTop = stripTop;
        InvalidateVisual();
    }

    public (Rect Ink, double BandTop, double BandBottom) Geometry
    {
        get
        {
            var (top, bottom) = NumeralBand.Band(Bounds.Height, _barBottom, _stripTop);
            return (_inkRect, top, bottom);
        }
    }

    /// <summary>The figures as one outline at a font size, origin at the first glyph's pen position.</summary>
    private static Geometry Shape(string text, double size)
    {
        var group = new GeometryGroup();
        var x = 0.0;
        foreach (var ch in text)
        {
            var ft = new FormattedText(ch.ToString(), System.Globalization.CultureInfo.InvariantCulture,
                FlowDirection.LeftToRight, Face, size, Palette.NumeralInk);
            var g = ft.BuildGeometry(new Point(x, 0));
            if (g != null) group.Children.Add(g);
            x += ft.WidthIncludingTrailingWhitespace + NumeralBand.Tracking * size;
        }
        return group;
    }

    public override void Render(DrawingContext context)
    {
        if (_text.Length == 0 || Bounds.Width < 1) return;
        var w = Bounds.Width;
        var h = Bounds.Height;
        var (top, bottom) = NumeralBand.Band(h, _barBottom, _stripTop);
        // Measure at a 100 px reference: cap height (ink height of the figures) and ink width, in em.
        var reference = Shape(_text, 100).Bounds;
        var size = NumeralBand.FontSize(w, h, top, bottom, reference.Height / 100, reference.Width / 100);
        size = Math.Round(size, 1);
        if (_ink == null || _inkKey != (_text, size))
        {
            _ink = Shape(_text, size);
            _inkKey = (_text, size);
        }
        var b = _ink.Bounds;
        var dx = NumeralBand.InkLeft(w, b.Width) - b.X;
        var dy = NumeralBand.MiddleY(top, bottom) - (b.Y + b.Height / 2);
        _inkRect = new Rect(b.X + dx, b.Y + dy, b.Width, b.Height);
        using (context.PushTransform(Matrix.CreateTranslation(dx, dy)))
            context.DrawGeometry(Palette.NumeralInk, null, _ink);
    }
}
