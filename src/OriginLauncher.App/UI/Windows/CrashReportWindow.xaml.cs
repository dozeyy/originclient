using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.App.UI.Windows;

// Shown by HomePage when a Minecraft boot dies (non-zero exit during startup).
// Names the mod(s) CrashAnalyzer implicated — or says plainly that the culprit
// is unknown — and offers exactly two actions besides Close:
//   - Disable external mods & retry: relaunch with only Origin's own mods
//     (the same fabric.modsFolder mechanism as the Home switch). The player's
//     files are never touched.
//   - Open log: the per-launch log this analysis was made from.
public partial class CrashReportWindow : Window
{
    private readonly string? _logPath;

    /// <summary>True when the player chose "Disable external mods &amp; retry".
    /// The caller (HomePage) flips the setting and relaunches.</summary>
    public bool RetryWithoutExternalMods { get; private set; }

    public CrashReportWindow(CrashAnalysis analysis, string? logPath)
    {
        InitializeComponent();
        _logPath = logPath;

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
                    Foreground = new SolidColorBrush(Color.FromRgb(0xE0, 0xE0, 0xE0)),
                    FontSize = 13,
                    Margin = new Thickness(0, 2, 0, 2),
                    TextWrapping = TextWrapping.Wrap
                });
            }
        }

        DetailsBox.Text = analysis.DetailExcerpt ?? "No further detail was captured for this crash.";
        OpenLogButton.IsEnabled = _logPath != null && System.IO.File.Exists(_logPath);
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
