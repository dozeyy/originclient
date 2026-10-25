using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;
using Card = (string Key, System.Windows.FrameworkElement? Item, double ZoneLuminance, double Strength, double Reach);

namespace OriginLauncher.App.UI;

// Render mode only (UI/RenderShots.cs, launcher-shots.yml): the version picker's numbers on the cards (U3.4).
//
// Pictures: step 1 (the cards) and step 2 (a family's versions) at 960x600, 1180x740 and 1920x1080, step 2 opened on
// the card with the LIGHTEST art under its number and on the one with the DARKEST (the scrim has to work on both).
// Every shipped card art is dark, so two cards carry stand-in LIGHT art for the run (VersionSelectOverlay.TourSwapArt:
// the scrim is worked out for it exactly as for real art) and the real art is put back afterwards.
//
// Measurement: for every card, with the number itself left out of the picture, the contrast of the number's ink
// colour against the rendered pixels in the box the figures stand in, with no scrim and with the real one, on the
// grey and the colour state of the art. numeral-contrast.txt holds the table; "min" is the single worst pixel,
// "p05" the worst 5 %, "median" the middle. WCAG contrast, ink = Color.NumeralInk.
internal static class CardShots
{
    private static readonly Size[] Sizes = { new(960, 600), new(1180, 740), new(1920, 1080) };

    // Stand-in light art: a flat light picture with a gentle fall-off toward the bottom (sky-like). Levels are 0..255
    // channel values of a neutral grey, the stand-ins for "very light" and "light" art.
    private const int VeryLight = 240, Light = 200, Fall = 24;

    public static void Run(MainWindow window, string outDir)
    {
        var overlay = Find<VersionSelectOverlay>(window);
        if (overlay == null)
        {
            File.AppendAllText(Path.Combine(outDir, "card-shots-error.txt"), "VersionSelectOverlay not found in the window" + Environment.NewLine);
            return;
        }

        var (w0, h0) = (window.Width, window.Height);
        var report = Path.Combine(outDir, "numeral-contrast.txt");
        File.WriteAllText(report, "size card zoneLum strength reach state | no scrim min/p05/median | scrim min/p05/median (WCAG contrast of the number's ink)" + Environment.NewLine);

        // Open once so the cards exist, put the stand-in art on the first two, and let the layout settle.
        window.TourGoTo(0);
        RenderShots.Pump(500);
        window.TourShowVersions(-1);
        RenderShots.Pump(600);
        overlay.TourSwapArt(0, StandIn(VeryLight));
        overlay.TourSwapArt(1, StandIn(Light));
        window.TourHideVersions();
        RenderShots.Pump(300);

        foreach (var size in Sizes)
        {
            var label = $"{(int)size.Width}x{(int)size.Height}";
            // The CI desktop caps a real window (1044 wide); a bigger one is laid out in-process (RenderShots.Snap).
            var inProcess = size.Width > 1000;
            window.Width = inProcess ? 1044 : size.Width;
            window.Height = inProcess ? 788 : size.Height;
            window.TourGoTo(0);
            RenderShots.Pump(500);

            Open(window, overlay, -1, size, inProcess);
            var cards = overlay.TourCards();
            RenderShots.Snap(window, outDir, $"p3cards-{label}-step1", size: size, laidOut: inProcess);

            var light = IndexOf(cards, max: true);
            var dark = IndexOf(cards, max: false);
            File.AppendAllText(report, $"# {label}: lightest art under the number = {cards[light].Key} ({cards[light].ZoneLuminance:0.000}), darkest = {cards[dark].Key} ({cards[dark].ZoneLuminance:0.000}){Environment.NewLine}");
            Measure(cards, label, report);

            foreach (var (tag, index) in new[] { ("light", light), ("dark", dark) })
            {
                Open(window, overlay, index, size, inProcess);
                RenderShots.Snap(window, outDir, $"p3cards-{label}-step2-{tag}-{cards[index].Key}", size: size, laidOut: inProcess);
            }
            window.TourHideVersions();
            RenderShots.Pump(300);
        }

        overlay.TourRestoreArt();
        window.Width = w0;
        window.Height = h0;
        window.TourGoTo(0);
        RenderShots.Pump(400);
    }

    // A neutral grey picture, 16:9, at the given level, a little darker toward the bottom.
    private static BitmapSource StandIn(int level)
    {
        const int width = 320, height = 180;
        var stride = width * 4;
        var pixels = new byte[stride * height];
        for (var y = 0; y < height; y++)
        {
            var v = (byte)(level - y * Fall / height);
            for (var x = 0; x < width; x++)
            {
                var i = y * stride + x * 4;
                pixels[i] = pixels[i + 1] = pixels[i + 2] = v;
                pixels[i + 3] = 255;
            }
        }
        var bmp = BitmapSource.Create(width, height, 96, 96, PixelFormats.Bgra32, null, pixels, stride);
        bmp.Freeze();
        return bmp;
    }

    // Open the picker (on a family, or on the grid for -1) and settle it at the picture size.
    internal static void Open(MainWindow window, VersionSelectOverlay overlay, int index, Size size, bool inProcess)
    {
        window.TourShowVersions(index);
        RenderShots.Pump(600);
        if (!inProcess) return;
        // The overlay sizes its cards from its own width and height when a layout pass reports them, which a hand
        // layout does not trigger: run the sizing by hand and lay out again, until nothing moves.
        for (var i = 0; i < 3; i++)
        {
            window.TourLayoutAt(size);
            overlay.TourRelayout();
        }
        window.TourLayoutAt(size);
    }

    private static int IndexOf(IReadOnlyList<Card> cards, bool max)
    {
        // Only a family with several releases has a step 2 to open (a one-release card picks and closes), so only those
        // can stand for "the lightest / darkest card" in the step-2 pictures.
        var best = -1;
        for (var i = 0; i < cards.Count; i++)
        {
            if (VersionCatalog.Groups[i].Versions.Count < 2) continue;
            if (best < 0 || (max ? cards[i].ZoneLuminance > cards[best].ZoneLuminance : cards[i].ZoneLuminance < cards[best].ZoneLuminance)) best = i;
        }
        return Math.Max(0, best);
    }

    private static void Measure(IReadOnlyList<Card> cards, string label, string report)
    {
        var inkColour = (Color)Application.Current.FindResource("Color.NumeralInk");
        var inkLum = CardArtScrim.Luminance(inkColour.R / 255.0, inkColour.G / 255.0, inkColour.B / 255.0);
        foreach (var (key, item, zone, strength, reach) in cards)
        {
            if (item == null) continue;
            var number = Find<VersionCardNumeral>(item);
            var colour = (item as Control)?.Template?.FindName("Colour", item) as Image;
            if (number == null || colour == null || VisualTreeHelper.GetParent(number) is not FrameworkElement art) continue;
            var ink = number.InkBounds;
            // The number is left out of the picture (Opacity 0 keeps it out of the art's brush) and its shade is
            // drawn on top by hand, so what is measured is the ground the figures stand on.
            number.Opacity = 0;
            foreach (var state in new[] { "grey", "colour" })
            {
                colour.Opacity = state == "colour" ? 1 : 0;
                var bare = Stats(art, ink, inkLum, number, 0, 1);
                var real = Stats(art, ink, inkLum, number, strength, reach);
                File.AppendAllText(report,
                    $"{label} {key,-5} {zone:0.000} {strength:0.00} {reach:0.00} {state,-6} | {bare.Min:0.00}/{bare.P05:0.00}/{bare.Median:0.00} | {real.Min:0.00}/{real.P05:0.00}/{real.Median:0.00}{Environment.NewLine}");
            }
            colour.ClearValue(UIElement.OpacityProperty);
            number.Strength = strength;
            number.Reach = reach;
            number.ClearValue(UIElement.OpacityProperty);
        }
    }

    private static (double Min, double P05, double Median) Stats(FrameworkElement art, Rect ink, double inkLum, VersionCardNumeral number, double strength, double reach)
    {
        number.Strength = strength;
        number.Reach = reach;
        // No layout pass here (it would undo a hand layout at a size the desktop cannot hold). Drawn through a
        // VisualBrush so the card's place in the window does not offset the picture.
        var width = (int)Math.Ceiling(art.ActualWidth);
        var height = (int)Math.Ceiling(art.ActualHeight);
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.DrawRectangle(new VisualBrush(art) { Stretch = Stretch.None, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top },
                null, new Rect(0, 0, width, height));
            number.Draw(dc, false);
        }
        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        var stride = width * 4;
        var pixels = new byte[stride * height];
        bitmap.CopyPixels(pixels, stride, 0);

        var x0 = Math.Max(0, (int)Math.Floor(ink.Left));
        var x1 = Math.Min(width, (int)Math.Ceiling(ink.Right));
        var y0 = Math.Max(0, (int)Math.Floor(ink.Top));
        var y1 = Math.Min(height, (int)Math.Ceiling(ink.Bottom));
        var contrasts = new List<double>();
        for (var y = y0; y < y1; y++)
            for (var x = x0; x < x1; x++)
            {
                var i = y * stride + x * 4;
                contrasts.Add(CardArtScrim.Contrast(inkLum, CardArtScrim.Luminance(pixels[i + 2] / 255.0, pixels[i + 1] / 255.0, pixels[i] / 255.0)));
            }
        if (contrasts.Count == 0) return (double.NaN, double.NaN, double.NaN);
        contrasts.Sort();
        return (contrasts[0], contrasts[(int)(contrasts.Count * 0.05)], contrasts[contrasts.Count / 2]);
    }

    internal static T? Find<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T hit) return hit;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            if (Find<T>(VisualTreeHelper.GetChild(root, i)) is { } found) return found;
        return null;
    }
}
