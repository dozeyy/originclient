using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.App.UI;

// Render mode only (UI/RenderShots.cs, launcher-shots.yml): the version picker's numbers on the cards (U3.4).
//
// Pictures: step 1 (the cards) and step 2 (a family's versions) at 960x600 and 1920x1080, step 2 opened on the card
// with the LIGHTEST art under its number and on the one with the DARKEST (the scrim has to work on both).
//
// Measurement: for every card, with the number itself left out of the picture, the contrast of the number's ink
// colour against the rendered pixels in the box the figures stand in, with no scrim and with the real one, on the
// grey and the colour state of the art. numeral-contrast.txt holds the table; "min" is the single worst pixel,
// "p05" the worst 5 %, "median" the middle. WCAG contrast, ink = Color.NumeralInk.
internal static class CardShots
{
    private static readonly Size[] Sizes = { new(960, 600), new(1920, 1080) };

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
        File.WriteAllText(report, "size card zoneLum strength state | no scrim min/p05/median | scrim min/p05/median (WCAG contrast of the number's ink)" + Environment.NewLine);

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
            Measure(overlay, cards, label, report);

            foreach (var (tag, index) in new[] { ("light", light), ("dark", dark) })
            {
                Open(window, overlay, index, size, inProcess);
                RenderShots.Snap(window, outDir, $"p3cards-{label}-step2-{tag}-{cards[index].Key}", size: size, laidOut: inProcess);
            }
            window.TourHideVersions();
            RenderShots.Pump(300);
        }

        window.Width = w0;
        window.Height = h0;
        window.TourGoTo(0);
        RenderShots.Pump(400);
    }

    // Open the picker (on a family, or on the grid for -1) and settle it at the picture size.
    private static void Open(MainWindow window, VersionSelectOverlay overlay, int index, Size size, bool inProcess)
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

    private static int IndexOf(IReadOnlyList<(string Key, FrameworkElement? Item, double ZoneLuminance, double Strength)> cards, bool max)
    {
        var best = 0;
        for (var i = 1; i < cards.Count; i++)
            if (max ? cards[i].ZoneLuminance > cards[best].ZoneLuminance : cards[i].ZoneLuminance < cards[best].ZoneLuminance) best = i;
        return best;
    }

    private static void Measure(VersionSelectOverlay overlay, IReadOnlyList<(string Key, FrameworkElement? Item, double ZoneLuminance, double Strength)> cards,
        string label, string report)
    {
        var inkColour = (Color)Application.Current.FindResource("Color.NumeralInk");
        var inkLum = CardArtScrim.Luminance(inkColour.R / 255.0, inkColour.G / 255.0, inkColour.B / 255.0);
        foreach (var (key, item, zone, strength) in cards)
        {
            if (item == null) continue;
            var number = Find<VersionCardNumeral>(item);
            var colour = (item as Control)?.Template?.FindName("Colour", item) as Image;
            if (number == null || colour == null || VisualTreeHelper.GetParent(number) is not FrameworkElement art) continue;
            var text = number.Text;
            var ink = number.InkBounds;
            number.Text = ""; // the picture without the number: just the art and the shade
            foreach (var state in new[] { "grey", "colour" })
            {
                colour.Opacity = state == "colour" ? 1 : 0;
                var bare = Stats(art, ink, inkLum, number, 0);
                var real = Stats(art, ink, inkLum, number, strength);
                File.AppendAllText(report,
                    $"{label} {key,-5} {zone:0.000} {strength:0.00} {state,-6} | {bare.Min:0.00}/{bare.P05:0.00}/{bare.Median:0.00} | {real.Min:0.00}/{real.P05:0.00}/{real.Median:0.00}{Environment.NewLine}");
            }
            colour.ClearValue(UIElement.OpacityProperty);
            number.Strength = strength;
            number.Text = text;
        }
    }

    private static (double Min, double P05, double Median) Stats(FrameworkElement art, Rect ink, double inkLum, VersionCardNumeral number, double strength)
    {
        number.Strength = strength;
        // No layout pass here (it would undo a hand layout at a size the desktop cannot hold). Drawn through a
        // VisualBrush so the card's place in the window does not offset the picture.
        var width = (int)Math.Ceiling(art.ActualWidth);
        var height = (int)Math.Ceiling(art.ActualHeight);
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
            dc.DrawRectangle(new VisualBrush(art) { Stretch = Stretch.None, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top },
                null, new Rect(0, 0, width, height));
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

    private static T? Find<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T hit) return hit;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            if (Find<T>(VisualTreeHelper.GetChild(root, i)) is { } found) return found;
        return null;
    }
}
