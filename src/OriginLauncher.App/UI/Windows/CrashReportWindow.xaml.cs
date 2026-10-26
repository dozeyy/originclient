using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.App.UI.Windows;

// Shown by HomePage when a Minecraft game crashed (Crash Report V3; a normal close never gets here). Five sections,
// straight from the verdict (Core/Launch/CrashEngine):
//   1. What happened, 2. Likely cause + the confidence word (confirmed / strong / possible / unknown, verbatim),
//   3. Recommended fix, 4. the fix button - only when the verdict has a safe fix (strong or confirmed, reversible) -
//      and, below strong, a one-time "Try once without my mods", 5. Technical details (folded): the lines the cause
//      came from, the other findings, Copy report (a secret-free bundle, CrashReportBundle), Open logs folder.
// The window only reports the choice (ChosenFix / ChoseTryWithoutAddedMods); HomePage does the work, once
// (CrashRetryGuard). The player's files are never touched by this window, and nothing is sent anywhere.
public partial class CrashReportWindow : Window
{
    private readonly string? _logPath;
    private readonly CrashFix? _fix;
    private readonly Func<string>? _buildReport;
    private bool _detailsOpen;

    /// <summary>The fix the player chose with the main button; null when they did not press it.</summary>
    public CrashFix? ChosenFix { get; private set; }

    /// <summary>The player chose the one-time launch without their own mods.</summary>
    public bool ChoseTryWithoutAddedMods { get; private set; }

    /// <summary>True when the themed layout could not load and the plain in-code one is showing.</summary>
    public bool UsingFallbackLayout { get; }

    /// <param name="who">Which game crashed, e.g. "Steve - Minecraft 1.21.1" (several can run at once).</param>
    /// <param name="buildReport">Builds the Copy report text on demand (null: the button is disabled).</param>
    public CrashReportWindow(CrashVerdict verdict, string? logPath, string? who = null, Func<string>? buildReport = null)
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
            Width = 880;
            MinWidth = 860;
            SizeToContent = SizeToContent.Height;
            DetailsPanel.Height = 200;
        }
        _logPath = logPath;
        _buildReport = buildReport;
        _fix = verdict.SafeFix;
        var finding = verdict.Finding;

        if (!string.IsNullOrEmpty(who))
        {
            WhoText.Text = who;
            WhoText.Visibility = Visibility.Visible;
            Title = $"Origin - Minecraft crashed ({who})";
        }

        CauseTag.Text = finding.Tag;
        HeadlineText.Text = finding.Headline;
        ReasonText.Text = finding.WhatHappened;
        LikelyCauseText.Text = finding.LikelyCause;
        RecommendedText.Text = finding.RecommendedFix;
        ConfidenceText.Text = finding.Confidence.Word();
        // The confidence word's soft hue (Workbench: small accents on the neutral chip, never a colour fill).
        var hue = finding.Confidence switch
        {
            CrashConfidence.Confirmed => "Brush.Success",
            CrashConfidence.Strong => "Brush.Info",
            CrashConfidence.Possible => "Brush.Warning",
            _ => "Brush.TextDim",
        };
        if (Application.Current?.TryFindResource(hue) is Brush hueBrush) ConfidenceText.Foreground = hueBrush;

        foreach (var culprit in finding.Culprits)
        {
            var line = culprit.JarFileName != null ? $"{culprit.ModId}   ({culprit.JarFileName})" : culprit.ModId;
            if (!culprit.IsExternal) line += "   - part of Origin; updating Origin usually fixes this";
            // Themed when the theme loaded; the crash window must also build without it (CrashFallback's values).
            var row = CrashFallback.Body(new Thickness(0, 2, 0, 2));
            row.Text = line;
            if (Application.Current?.TryFindResource("Brush.TextDim") is Brush ink) row.Foreground = ink;
            if (Application.Current?.TryFindResource("FontSize.Body") is double size) row.FontSize = size;
            CulpritList.Children.Add(row);
        }

        DetailsBox.Text = Details(verdict);
        OpenLogButton.IsEnabled = true;
        CopyReportButton.IsEnabled = buildReport != null;

        if (_fix == null) ActionButton.Visibility = Visibility.Collapsed;
        else ActionButton.Content = _fix.Label;
        TryButton.Visibility = verdict.OfferTryWithoutAddedMods ? Visibility.Visible : Visibility.Collapsed;
    }

    // Section 5: the evidence lines, then what else was seen (weaker findings), with their confidence words.
    private static string Details(CrashVerdict verdict)
    {
        var f = verdict.Finding;
        var lines = new List<string> { $"Diagnosis: {f.Rule} ({f.Confidence.Word()})" };
        lines.AddRange(f.TechnicalLines);
        if (verdict.Others.Count > 0)
        {
            lines.Add("");
            lines.Add("Also seen:");
            lines.AddRange(verdict.Others.Select(o => $"  {o.Rule} ({o.Confidence.Word()}): {o.LikelyCause}"));
        }
        if (f.TechnicalLines.Count == 0 && verdict.Others.Count == 0)
            lines.Add("No further detail was captured for this crash. Copy report holds the end of the log.");
        return string.Join("\n", lines);
    }

    // The same controls as CrashReportWindow.xaml (same field names), with no resource lookups.
    private void BuildFallbackLayout()
    {
        var grid = CrashFallback.Frame(this, starRow: 5, rows: 7);
        var head = new StackPanel();
        CauseTag = CrashFallback.Caption(new Thickness(0, 0, 0, 4));
        head.Children.Add(CauseTag);
        HeadlineText = CrashFallback.Heading("Minecraft closed unexpectedly");
        head.Children.Add(HeadlineText);
        WhoText = CrashFallback.Accent(new Thickness(0, 6, 0, 0));
        head.Children.Add(WhoText);
        grid.Children.Add(head);

        ReasonText = CrashFallback.Body(new Thickness(0, 12, 0, 0));
        Grid.SetRow(ReasonText, 1);
        grid.Children.Add(ReasonText);

        CulpritPanel = new StackPanel { Margin = new Thickness(0, 16, 0, 0) };
        ConfidenceText = CrashFallback.Caption(new Thickness(0));
        ConfidenceChip = new Border { Child = ConfidenceText };
        CulpritPanel.Children.Add(ConfidenceChip);
        var well = new StackPanel();
        LikelyCauseText = CrashFallback.Body(new Thickness(0));
        CulpritList = new StackPanel();
        well.Children.Add(LikelyCauseText);
        well.Children.Add(CulpritList);
        CulpritPanel.Children.Add(CrashFallback.Card(new Thickness(0, 6, 0, 0), well));
        Grid.SetRow(CulpritPanel, 2);
        grid.Children.Add(CulpritPanel);

        RecommendedText = CrashFallback.Body(new Thickness(0, 12, 0, 0));
        Grid.SetRow(RecommendedText, 3);
        grid.Children.Add(RecommendedText);

        DetailsBox = CrashFallback.Details(grid, 5, new Thickness(0, 8, 0, 0), 11);
        DetailsPanel = (Border)DetailsBox.Parent;
        DetailsPanel.Visibility = Visibility.Collapsed;
        var buttons = CrashFallback.ButtonRow(grid, 6);
        DetailsButton = CrashFallback.Btn("Show details", primary: false, Details_Click);
        DetailsButton.Margin = new Thickness(0, 0, 8, 0);
        CopyReportButton = CrashFallback.Btn("Copy report", primary: false, CopyReport_Click);
        CopyReportButton.Margin = new Thickness(0, 0, 8, 0);
        OpenLogButton = CrashFallback.Btn("Open logs folder", primary: false, OpenLog_Click);
        OpenLogButton.Margin = new Thickness(0, 0, 8, 0);
        CloseButton = CrashFallback.Btn("Close", primary: false, Close_Click);
        CloseButton.Margin = new Thickness(0, 0, 8, 0);
        TryButton = CrashFallback.Btn("Try once without my mods", primary: false, Try_Click);
        TryButton.Margin = new Thickness(0, 0, 8, 0);
        ActionButton = CrashFallback.Btn("Fix and relaunch", primary: true, Action_Click);
        buttons.Children.Add(DetailsButton);
        buttons.Children.Add(CopyReportButton);
        buttons.Children.Add(OpenLogButton);
        buttons.Children.Add(CloseButton);
        buttons.Children.Add(TryButton);
        buttons.Children.Add(ActionButton);
    }

    private void Action_Click(object sender, RoutedEventArgs e)
    {
        if (_fix == null) return;
        ChosenFix = _fix;
        DialogResult = true;
    }

    private void Try_Click(object sender, RoutedEventArgs e)
    {
        ChoseTryWithoutAddedMods = true;
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
