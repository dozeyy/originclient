using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.App.UI.Windows;

// Shown by HomePage when a Minecraft game dies. Leads with a plain-language headline for the cause
// (CrashClassifier: out of memory, a mod that didn't load, the graphics driver ...) and ONE main
// button for the fix; the mod list, the technical details and the logs are one click further.
//   - Main button: whatever CrashDiagnosis.Action says (raise memory, relaunch without a mod,
//     Origin only, repair + relaunch, or open the logs). The window only reports the choice
//     (ChosenAction); HomePage does the work, once (CrashRetryGuard).
//   - Copy report: a secret-free bundle (CrashReportBundle) on the clipboard.
//   - Open logs folder, Close.
// The player's files are never touched by this window.
public partial class CrashReportWindow : Window
{
    private readonly string? _logPath;
    private readonly CrashAction _action;
    private readonly Func<string>? _buildReport;
    private bool _detailsOpen;

    /// <summary>The fix the player chose with the main button; None when they only closed the window.</summary>
    public CrashAction ChosenAction { get; private set; } = CrashAction.None;

    /// <summary>True when the themed layout could not load and the plain in-code one is showing.</summary>
    public bool UsingFallbackLayout { get; }

    /// <param name="who">Which game crashed, e.g. "Steve - Minecraft 1.21.1" (several can run at once).</param>
    /// <param name="diagnosis">The classified cause; when null it is derived from the mod blame alone.</param>
    /// <param name="buildReport">Builds the Copy report text on demand (null: the button is disabled).</param>
    public CrashReportWindow(CrashAnalysis analysis, string? logPath, string? who = null,
        CrashDiagnosis? diagnosis = null, Func<string>? buildReport = null)
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
            // What the XAML says about size, in code: as tall as its content, wide enough for the one-row buttons.
            Width = 840;
            MinWidth = 820;
            SizeToContent = SizeToContent.Height;
            DetailsPanel.Height = 200;
        }
        _logPath = logPath;
        _buildReport = buildReport;
        diagnosis ??= CrashClassifier.Classify(analysis, "", -1, 0, 0);
        _action = diagnosis.Action;

        if (!string.IsNullOrEmpty(who))
        {
            WhoText.Text = who;
            WhoText.Visibility = Visibility.Visible;
            Title = $"Origin - Minecraft crashed ({who})";
        }

        if (CauseTag != null) CauseTag.Text = diagnosis.Tag;
        HeadlineText.Text = diagnosis.Headline;
        ReasonText.Text = diagnosis.Explanation;

        if (analysis.Culprits.Count > 0)
        {
            CulpritPanel.Visibility = Visibility.Visible;
            foreach (var culprit in analysis.Culprits)
            {
                var line = culprit.JarFileName != null
                    ? $"{culprit.ModId}   ({culprit.JarFileName})"
                    : culprit.ModId;
                if (!culprit.IsExternal)
                    line += "   - built-in mod; updating the launcher usually fixes this";
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
        OpenLogButton.IsEnabled = true;
        CopyReportButton.IsEnabled = buildReport != null;

        // Main button: the diagnosis' action. "Open logs" as the main action replaces the plain
        // secondary button (never two buttons that do the same thing).
        if (_action == CrashAction.None)
        {
            ActionButton.Visibility = Visibility.Collapsed;
        }
        else
        {
            ActionButton.Content = diagnosis.ActionLabel;
            if (_action == CrashAction.OpenLogs) OpenLogButton.Visibility = Visibility.Collapsed;
        }
    }

    // The same controls as CrashReportWindow.xaml (same field names), with no resource lookups.
    private void BuildFallbackLayout()
    {
        var grid = CrashFallback.Frame(this, starRow: 3, rows: 5);
        var head = new StackPanel();
        HeadlineText = CrashFallback.Heading("Minecraft closed unexpectedly");
        head.Children.Add(HeadlineText);
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
        DetailsPanel = (Border)DetailsBox.Parent;
        DetailsPanel.Visibility = Visibility.Collapsed;
        var buttons = CrashFallback.ButtonRow(grid, 4);
        DetailsButton = CrashFallback.Btn("Show details", primary: false, Details_Click);
        DetailsButton.Margin = new Thickness(0, 0, 8, 0);
        CopyReportButton = CrashFallback.Btn("Copy report", primary: false, CopyReport_Click);
        CopyReportButton.Margin = new Thickness(0, 0, 8, 0);
        OpenLogButton = CrashFallback.Btn("Open logs folder", primary: false, OpenLog_Click);
        OpenLogButton.Margin = new Thickness(0, 0, 8, 0);
        CloseButton = CrashFallback.Btn("Close", primary: false, Close_Click);
        CloseButton.Margin = new Thickness(0, 0, 8, 0);
        ActionButton = CrashFallback.Btn("Relaunch with Origin only", primary: true, Action_Click);
        buttons.Children.Add(DetailsButton);
        buttons.Children.Add(CopyReportButton);
        buttons.Children.Add(OpenLogButton);
        buttons.Children.Add(CloseButton);
        buttons.Children.Add(ActionButton);
    }

    private void Action_Click(object sender, RoutedEventArgs e)
    {
        if (_action == CrashAction.OpenLogs)
        {
            OpenLog_Click(sender, e); // nothing to relaunch: the window stays for the player to read
            return;
        }
        ChosenAction = _action;
        DialogResult = true;
    }

    private void Details_Click(object sender, RoutedEventArgs e)
    {
        _detailsOpen = !_detailsOpen;
        DetailsPanel.Visibility = _detailsOpen ? Visibility.Visible : Visibility.Collapsed;
        DetailsButton.Content = _detailsOpen ? "Hide details" : "Show details";
    }

    private void CopyReport_Click(object sender, RoutedEventArgs e)
    {
        if (_buildReport == null) return;
        try
        {
            Clipboard.SetText(_buildReport());
            CopyReportButton.Content = "Copied";
            var back = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            back.Tick += (_, _) => { back.Stop(); CopyReportButton.Content = "Copy report"; };
            back.Start();
        }
        catch
        {
            // The clipboard can be held by another app for a moment; the button stays, try again.
            CopyReportButton.Content = "Try again";
        }
    }

    private void OpenLog_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var target = _logPath != null && System.IO.File.Exists(_logPath)
                ? $"/select,\"{_logPath}\""
                : $"\"{Core.OriginPaths.Logs}\"";
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe", target));
        }
        catch { /* explorer refused - nothing actionable */ }
    }

    private void Close_Click(object sender, RoutedEventArgs e) => DialogResult = false;
}
