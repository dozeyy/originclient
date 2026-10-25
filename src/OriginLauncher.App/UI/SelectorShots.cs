using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.App.UI;

// Render mode only (UI/RenderShots.cs, launcher-shots.yml): the two-step version selector with mixed install states (U3.2).
//
// The sheet reads its states from disk, so a run over a scratch folder would show every version "Not installed". Instead
// the overlay is pointed at SelectorFakeInstalls: a temp folder with real client / loader / Origin-jar files per version,
// so the states come out of the production classifier. Pictures, at 960x600, 1180x740 and 1920x1080:
//   p3sel-<size>-step1               the eight family cards in full colour, the quiet status line under each
//   p3sel-<size>-step1-focus         the keyboard on a card (focus ring)
//   p3sel-<size>-step2-<family>      three families (1.21, 1.20, 1.19) expanded into the panel, the current release chosen
//   p3sel-<size>-step2-1.21-focus    the keyboard on the dropdown
//   p3sel-<size>-step2-1.21-open     the dropdown open: the releases newest first, the emerald dot on installed ones
//   p3sel-<size>-home-after          Home after picking 1.20.4 in the dropdown and pressing Esc
// Motion is off in a render run, so no picture is caught mid-move. selector-states.txt lists what each card says and
// which releases wear the dot, so the states can be read without opening a picture. The shots are compared with
// docs/overhaul/design/A-Workbench-2.jpg ("Choosing a version").
internal static class SelectorShots
{
    private static readonly Size[] Sizes = { new(960, 600), new(1180, 740), new(1920, 1080) };

    // Three multi-release families with a mix of every state, plus single-release families showing their own state.
    private static readonly Dictionary<string, InstallState> States = new()
    {
        ["26.3"] = InstallState.Installed,
        ["1.21.11"] = InstallState.Installed,
        ["1.21.1"] = InstallState.Installed,
        ["1.21.2"] = InstallState.UpdateReady,
        ["1.21"] = InstallState.Partial,
        ["1.20.4"] = InstallState.Installed,
        ["1.20.2"] = InstallState.Installed,
        ["1.20.1"] = InstallState.Partial,
        ["1.19.4"] = InstallState.UpdateReady,
        ["1.19.3"] = InstallState.Installed,
        ["1.18.2"] = InstallState.Installed,
        ["1.17.1"] = InstallState.UpdateReady,
        ["1.8.9"] = InstallState.Partial,
    };

    private static readonly string[] Families = { "1.21", "1.20", "1.19" };

    public static void Run(MainWindow window, string outDir)
    {
        var overlay = CardShots.Find<VersionSelectOverlay>(window);
        if (overlay == null)
        {
            File.AppendAllText(Path.Combine(outDir, "selector-shots-error.txt"), "VersionSelectOverlay not found in the window" + Environment.NewLine);
            return;
        }

        var scratch = Path.Combine(Path.GetTempPath(), "origin-selector-shots-" + Environment.ProcessId);
        var fake = SelectorFakeInstalls.Build(scratch, States);
        fake.RefreshAllAsync().GetAwaiter().GetResult();
        var real = overlay.Installs;
        overlay.Installs = fake;

        var (w0, h0) = (window.Width, window.Height);
        var report = new StringBuilder("what the selector says, off the production disk check over fake installs" + Environment.NewLine);
        var focusLog = new StringBuilder();
        try
        {
            foreach (var size in Sizes)
            {
                var label = $"{(int)size.Width}x{(int)size.Height}";
                var inProcess = size.Width > 1000; // the CI desktop caps a real window (1044 wide)
                window.Width = inProcess ? 1044 : size.Width;
                window.Height = inProcess ? 788 : size.Height;
                window.TourGoTo(0);
                RenderShots.Pump(500);

                CardShots.Open(window, overlay, -1, size, inProcess);
                RenderShots.Snap(window, outDir, $"p3sel-{label}-step1", size: size, laidOut: inProcess);
                if (label == "960x600")
                    foreach (var group in VersionCatalog.Groups)
                        report.AppendLine($"card {group.Key}: {overlay.BadgeTextOf(group.Key)}");

                // Keyboard focus on a card. WPF draws its focus frame only for a real key press, so (as in the Import shots)
                // the card's own focus style is drawn on it when WPF did not; focus-checks-selector.txt says which.
                window.Activate();
                overlay.TourFocus(4);
                RenderShots.Pump(300);
                var cardFrame = ImportShots.FocusFrame(window, overlay.TourFamilyItem(4), $"{label} card 1.18", focusLog);
                RenderShots.Snap(window, outDir, $"p3sel-{label}-step1-focus", size: size, laidOut: inProcess, adorners: true);
                cardFrame?.Invoke();

                foreach (var key in Families)
                {
                    var index = IndexOf(key);
                    CardShots.Open(window, overlay, index, size, inProcess);
                    RenderShots.Snap(window, outDir, $"p3sel-{label}-step2-{key}", size: size, laidOut: inProcess);
                    if (label == "960x600")
                        foreach (var (v, dot) in overlay.DropdownItems)
                            report.AppendLine($"release {v}: {(dot ? "dot" : "-")}{(v == overlay.DropdownChoice ? " (chosen)" : "")}");
                }

                CardShots.Open(window, overlay, IndexOf("1.21"), size, inProcess);
                window.Activate();
                overlay.TourFocus(0);
                RenderShots.Pump(300);
                RenderShots.Snap(window, outDir, $"p3sel-{label}-step2-1.21-focus", size: size, laidOut: inProcess, adorners: true);

                // The dropdown open. Its list is a popup (its own window), so the picture draws it where it opens.
                if (overlay.TourDropdown(true) is { } combo)
                {
                    RenderShots.Pump(500);
                    RenderShots.Snap(window, outDir, $"p3sel-{label}-step2-1.21-open", size: size, laidOut: inProcess,
                        also: root => PopupInPlace(combo, root));
                    overlay.TourDropdown(false);
                    RenderShots.Pump(200);
                }

                // Pick a release the way a player does (the dropdown's own selection), then Esc: Home shows it.
                var before = window.TourHomeVersion;
                CardShots.Open(window, overlay, IndexOf("1.20"), size, inProcess);
                var pick = VersionCatalog.Groups[IndexOf("1.20")].Versions.ToList().IndexOf("1.20.4");
                overlay.ChooseRelease(pick);
                overlay.HandleKey(Key.Escape, null);
                RenderShots.Pump(600);
                if (inProcess) window.TourLayoutAt(size);
                RenderShots.Snap(window, outDir, $"p3sel-{label}-home-after", size: size, laidOut: inProcess);
                if (label == "960x600") report.AppendLine($"home after picking 1.20.4: {window.TourHomeVersion} (was {before})");
                // Put the scratch run's version back for the shots after this one.
                if (before != null && VersionCatalog.IsSupported(before))
                {
                    var family = VersionCatalog.Groups.ToList().FindIndex(g => g.Versions.Contains(before));
                    CardShots.Open(window, overlay, family, size, inProcess);
                    if (overlay.Nav.Step == SelectorStep.Releases)
                        overlay.ChooseRelease(VersionCatalog.Groups[family].Versions.ToList().IndexOf(before));
                    else
                        overlay.ChooseFamily(family);
                }

                window.TourHideVersions();
                RenderShots.Pump(300);
            }
        }
        finally
        {
            overlay.Installs = real;
            window.Width = w0;
            window.Height = h0;
            window.TourGoTo(0);
            RenderShots.Pump(400);
            File.WriteAllText(Path.Combine(outDir, "selector-states.txt"), report.ToString());
            File.WriteAllText(Path.Combine(outDir, "focus-checks-selector.txt"), focusLog.ToString());
            try { Directory.Delete(scratch, true); } catch { /* best effort */ }
        }
    }

    // The open popup's content, drawn at the spot the popup window sits over the window (under the combo, shifted by
    // Popup.OffsetX into the shadow's bleed), with room around it for the shadow.
    private static Visual? PopupInPlace(ComboBox combo, FrameworkElement root)
    {
        if (combo.Template.FindName("Popup", combo) is not Popup { IsOpen: true, Child: FrameworkElement child }) return null;
        root.UpdateLayout();
        var under = combo.TransformToAncestor(root).Transform(new Point(0, combo.ActualHeight));
        var origin = under + new Vector(combo.FindResource("Popup.OffsetX") is double dx ? dx : 0, 0);
        // The child's own margin (Popup.Bleed) is part of the popup window.
        var w = child.ActualWidth + child.Margin.Left + child.Margin.Right;
        var h = child.ActualHeight + child.Margin.Top + child.Margin.Bottom;
        var look = new VisualBrush(child)
        {
            Stretch = Stretch.None,
            AlignmentX = AlignmentX.Left,
            AlignmentY = AlignmentY.Top,
            ViewboxUnits = BrushMappingMode.Absolute,
            Viewbox = new Rect(-child.Margin.Left, -child.Margin.Top, w, h),
            ViewportUnits = BrushMappingMode.Absolute,
            Viewport = new Rect(origin.X, origin.Y, w, h),
        };
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
            dc.DrawRectangle(look, null, new Rect(origin.X, origin.Y, w, h));
        return visual;
    }

    private static int IndexOf(string key)
    {
        for (var i = 0; i < VersionCatalog.Groups.Count; i++)
            if (VersionCatalog.Groups[i].Key == key) return i;
        return 0;
    }
}
