using System.IO;
using System.Text;
using System.Windows;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.App.UI;

// Render mode only (UI/RenderShots.cs, launcher-shots.yml): the two-step version selector with mixed install states (U3.2).
//
// The sheet reads its states from disk, so a run over a scratch folder would show every version "Not installed". Instead
// the overlay is pointed at SelectorFakeInstalls: a temp folder with real client / loader / Origin-jar files per version,
// so the states come out of the production classifier. Pictures, at 960x600, 1180x740 and 1920x1080:
//   p3sel-<size>-step1               the eight family cards, badges showing every state
//   p3sel-<size>-step1-focus         the keyboard on a card (focus ring)
//   p3sel-<size>-step2-<family>      three families (1.21, 1.20, 1.19) with installed / not installed / update ready / partial rows
//   p3sel-<size>-step2-1.21-focus    the keyboard on a release row
// selector-states.txt lists the words each card and row shows, so the states can be read without opening a picture.
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
                var cardFrame = ImportShots.FocusFrame(window, overlay.TourCards()[4].Item, $"{label} card 1.18", focusLog);
                RenderShots.Snap(window, outDir, $"p3sel-{label}-step1-focus", size: size, laidOut: inProcess, adorners: true);
                cardFrame?.Invoke();

                foreach (var key in Families)
                {
                    var index = IndexOf(key);
                    CardShots.Open(window, overlay, index, size, inProcess);
                    RenderShots.Snap(window, outDir, $"p3sel-{label}-step2-{key}", size: size, laidOut: inProcess);
                    if (label == "960x600")
                        foreach (var v in VersionCatalog.Groups[index].Versions)
                            report.AppendLine($"row {v}: {overlay.RowStatusOf(v)}");
                }

                CardShots.Open(window, overlay, IndexOf("1.21"), size, inProcess);
                overlay.TourFocus(3);
                RenderShots.Pump(300);
                RenderShots.Snap(window, outDir, $"p3sel-{label}-step2-1.21-focus", size: size, laidOut: inProcess, adorners: true);

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

    private static int IndexOf(string key)
    {
        for (var i = 0; i < VersionCatalog.Groups.Count; i++)
            if (VersionCatalog.Groups[i].Key == key) return i;
        return 0;
    }
}
