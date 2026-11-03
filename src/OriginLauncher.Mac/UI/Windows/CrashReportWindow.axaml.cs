using System.Diagnostics;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.Mac.UI.Windows;

// Shown by HomePage when a game crashed (twin of the WPF UI/Windows/CrashReportWindow.xaml.cs; a normal close never
// gets here). Straight from the verdict (Core/Launch/CrashEngine). The window only reports the choice (ChosenFix /
// ChoseTryWithoutAddedMods); HomePage does the work, once (CrashRetryGuard). The player's files are never touched here
// and nothing is sent anywhere. Mac difference: "Open logs folder" reads "Show logs in Finder" (open -R).
public sealed partial class CrashReportWindow : Window
{
    private readonly string? _logPath;
    private readonly CrashFix? _fix;
    private readonly Func<string>? _buildReport;
    private bool _detailsOpen;

    public CrashFix? ChosenFix { get; private set; }
    public bool ChoseTryWithoutAddedMods { get; private set; }

    // The previewer and the XAML loader need a parameterless constructor.
    public CrashReportWindow() : this(null, null) { }

    public CrashReportWindow(CrashVerdict? verdict, string? logPath, string? who = null, Func<string>? buildReport = null)
    {
        InitializeComponent();
        _logPath = logPath;
        _buildReport = buildReport;
        ActionButton.Click += (_, _) =>
        {
            if (_fix == null) return;
            ChosenFix = _fix;
            Close(true);
        };
        TryButton.Click += (_, _) => { ChoseTryWithoutAddedMods = true; Close(true); };
        CloseButton.Click += (_, _) => Close(false);
        DetailsButton.Click += (_, _) => ToggleDetails();
        CopyReportButton.Click += async (_, _) => await CopyReportAsync();
        OpenLogButton.Click += (_, _) => RevealLogs();
        if (verdict == null) return;

        _fix = verdict.SafeFix;
        var finding = verdict.Finding;
        if (!string.IsNullOrEmpty(who))
        {
            WhoText.Text = who;
            WhoText.IsVisible = true;
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
        if (this.TryFindResource(hue, out var h) && h is IBrush hueBrush) ConfidenceText.Foreground = hueBrush;

        foreach (var culprit in finding.Culprits)
        {
            var line = culprit.JarFileName != null ? $"{culprit.ModId}   ({culprit.JarFileName})" : culprit.ModId;
            if (!culprit.IsExternal) line += "   - part of Origin; updating Origin usually fixes this";
            var row = new TextBlock { Text = line, Margin = new Thickness(0, 2, 0, 2), TextWrapping = TextWrapping.Wrap };
            if (this.TryFindResource("Text.Body", out var theme) && theme is Avalonia.Styling.ControlTheme t) row.Theme = t;
            if (this.TryFindResource("Brush.TextDim", out var ink) && ink is IBrush inkBrush) row.Foreground = inkBrush;
            CulpritList.Children.Add(row);
        }

        DetailsBox.Text = Details(verdict);
        CopyReportButton.IsEnabled = buildReport != null;
        if (_fix == null) ActionButton.IsVisible = false;
        else ActionButton.Content = _fix.Label;
        TryButton.IsVisible = verdict.OfferTryWithoutAddedMods;
    }

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

    /// <summary>The fold's handle (render shots press it for the "-details" twin).</summary>
    internal void ToggleDetails()
    {
        _detailsOpen = !_detailsOpen;
        DetailsPanel.IsVisible = _detailsOpen;
        DetailsButton.Content = _detailsOpen ? "Hide details" : "Show details";
    }

    private async Task CopyReportAsync()
    {
        if (_buildReport == null || Clipboard == null) return;
        try
        {
            await Clipboard.SetTextAsync(_buildReport());
            CopyReportButton.Content = "Copied";
            DispatcherTimer.RunOnce(() => CopyReportButton.Content = "Copy report", TimeSpan.FromSeconds(2));
        }
        catch
        {
            CopyReportButton.Content = "Try again";
        }
    }

    // Finder with the game's log selected, or the logs folder when that log is gone.
    private void RevealLogs()
    {
        try
        {
            var start = new ProcessStartInfo("open") { UseShellExecute = false };
            if (_logPath != null && File.Exists(_logPath))
            {
                start.ArgumentList.Add("-R");
                start.ArgumentList.Add(_logPath);
            }
            else
            {
                start.ArgumentList.Add(OriginPaths.Logs);
            }
            Process.Start(start);
        }
        catch { /* Finder refused - nothing actionable */ }
    }
}
