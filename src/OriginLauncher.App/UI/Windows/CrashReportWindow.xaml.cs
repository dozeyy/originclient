using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.App.UI.Windows;

// Shown by HomePage when a Minecraft boot dies (non-zero exit during startup).
// Names the mod(s) CrashAnalyzer implicated — or says plainly that the culprit
// is unknown — and offers exactly two actions besides Close:
//   - Disable external mods & retry: relaunch with only Origin's own mods
//     (the same mechanism as the Home switch: fabric.modsFolder, or on Forge
//     1.8.9 a managed-only mods mirror). The player's files are never touched.
//   - Open log: the per-launch log this analysis was made from.
public partial class CrashReportWindow : Window
{
    private readonly string? _logPath;

    /// <summary>True when the player chose "Disable external mods &amp; retry".
    /// The caller (HomePage) flips the setting and relaunches.</summary>
    public bool RetryWithoutExternalMods { get; private set; }

    /// <summary>True when the themed layout could not load and the plain in-code one is showing.</summary>
    public bool UsingFallbackLayout { get; }

    /// <param name="who">Which game crashed, e.g. "Steve · Minecraft 1.21.1" (several can run at once).</param>
    public CrashReportWindow(CrashAnalysis analysis, string? logPath, string? who = null)
    {
        try
        {
            InitializeComponent();
        }
        catch (Exception)
        {
            // The theme did not load: same content and buttons, built in code with literal colours.
            UsingFallbackLayout = true;
            BuildFallbackLayout();
        }
        _logPath = logPath;
        if (!string.IsNullOrEmpty(who))
        {
            WhoText.Text = who;
            WhoText.Visibility = Visibility.Visible;
            Title = $"Origin — Minecraft crashed ({who})";
        }

        ReasonText.Text = analysis.Culprits.Count > 0
            ? analysis.Reason + " The mod(s) below caused the crash:"
            : analysis.Reason + " Your added mods are the most common cause — retrying with only Origin's built-in mods will tell you if one of them is responsible.";

        if (analysis.Culprits.Count > 0)
        {
            CulpritPanel.Visibility = Visibility.Visible;
            foreach (var culprit in analysis.Culprits)
            {
                var line = culprit.JarFileName != null
                    ? $"{culprit.ModId}   ({culprit.JarFileName})"
                    : culprit.ModId;
                if (!culprit.IsExternal)
                    line += "   — built-in mod; updating the launcher usually fixes this";
                CulpritList.Children.Add(new TextBlock
                {
                    Text = line,
                    Foreground = new SolidColorBrush(Color.FromRgb(0xF3, 0xF0, 0xE8)),
                    FontSize = 13,
                    Margin = new Thickness(0, 2, 0, 2),
                    TextWrapping = TextWrapping.Wrap
                });
            }
        }

        DetailsBox.Text = analysis.DetailExcerpt ?? "No further detail was captured for this crash.";
        OpenLogButton.IsEnabled = _logPath != null && System.IO.File.Exists(_logPath);
    }

    // The same controls as CrashReportWindow.xaml (same field names), with no resource lookups.
    private void BuildFallbackLayout()
    {
        var grid = CrashFallback.Frame(this, starRow: 3, rows: 5);
        var head = new StackPanel();
        head.Children.Add(CrashFallback.Heading("Minecraft crashed while starting"));
        WhoText = CrashFallback.Accent(new Thickness(0, 6, 0, 0));
        head.Children.Add(WhoText);
        ReasonText = CrashFallback.Body(new Thickness(0, 8, 0, 0));
        Grid.SetRow(ReasonText, 1);
        CulpritList = new StackPanel();
        CulpritPanel = CrashFallback.Card(new Thickness(0, 16, 0, 0), CulpritList);
        Grid.SetRow(CulpritPanel, 2);
        grid.Children.Add(head);
        grid.Children.Add(ReasonText);
        grid.Children.Add(CulpritPanel);
        DetailsBox = CrashFallback.Details(grid, 3, new Thickness(0, 8, 0, 0), 11);
        var buttons = CrashFallback.ButtonRow(grid, 4);
        OpenLogButton = CrashFallback.Btn("Open log", primary: false, OpenLog_Click);
        OpenLogButton.Margin = new Thickness(0, 0, 8, 0);
        CloseButton = CrashFallback.Btn("Close", primary: false, Close_Click);
        CloseButton.Margin = new Thickness(0, 0, 8, 0);
        RetryButton = CrashFallback.Btn("Retry without mods", primary: true, Retry_Click);
        buttons.Children.Add(OpenLogButton);
        buttons.Children.Add(CloseButton);
        buttons.Children.Add(RetryButton);
    }

    private void Retry_Click(object sender, RoutedEventArgs e)
    {
        RetryWithoutExternalMods = true;
        DialogResult = true;
    }

    private void OpenLog_Click(object sender, RoutedEventArgs e)
    {
        if (_logPath == null) return;
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(
                "explorer.exe", $"/select,\"{_logPath}\""));
        }
        catch { /* explorer refused — nothing actionable */ }
    }

    private void Close_Click(object sender, RoutedEventArgs e) => DialogResult = false;
}
