using Avalonia;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Windows;

// Shown when a Minecraft game crashed (the Windows launcher's CrashReportWindow; a normal close never gets here). Five
// sections, straight from the verdict (Core/Launch/CrashEngine):
//   1. What happened, 2. Likely cause + the confidence word (confirmed / strong / possible / unknown, verbatim),
//   3. Recommended fix, 4. the fix button - only when the verdict has a safe fix (strong or confirmed, reversible) -
//      and, below strong, a one-time "Try once without my mods", 5. Technical details (folded): the lines the cause
//      came from, the other findings, Copy report (a secret-free bundle, CrashReportBundle), Open logs folder.
// The window only reports the choice (ChosenFix / ChoseTryWithoutAddedMods); LaunchController does the work, once
// (CrashRetryGuard). The player's files are never touched here, and nothing is sent anywhere.
public sealed class CrashReportWindow : Window
{
    /// <summary>The fix the player chose with the main button; null when they did not press it.</summary>
    public CrashFix? ChosenFix { get; private set; }

    /// <summary>The player chose the one-time launch without their own mods.</summary>
    public bool ChoseTryWithoutAddedMods { get; private set; }

    /// <summary>True when the themed view could not be built and the plain one is showing.</summary>
    public bool UsingFallbackLayout { get; }

    /// <param name="who">Which game crashed, e.g. "Steve - Minecraft 1.21.1" (several can run at once).</param>
    /// <param name="buildReport">Builds the Copy report text on demand (null: the button is off).</param>
    public CrashReportWindow(CrashVerdict verdict, string? logPath, string? who = null, Func<string>? buildReport = null)
    {
        Title = string.IsNullOrEmpty(who) ? "Origin - Minecraft crashed" : $"Origin - Minecraft crashed ({who})";
        var actions = new CrashReportActions(
            fix => { ChosenFix = fix; Close(true); },
            () => { ChoseTryWithoutAddedMods = true; Close(true); },
            () => Close(false),
            this);
        Control view;
        try
        {
            view = new CrashReportView(verdict, logPath, who, buildReport, actions);
            Background = Palette.Panel;
        }
        catch (Exception)
        {
            UsingFallbackLayout = true;
            view = CrashReportView.Fallback(verdict, logPath, who, buildReport, actions);
        }
        CrashChrome.Dress(this, view, Sizes.CrashReportW, null);
    }
}

/// <summary>What the crash report's buttons do; the host (a window, or a render run) decides.</summary>
public sealed record CrashReportActions(Action<CrashFix> Fix, Action TryWithoutMods, Action Close, Visual? Host = null);

/// <summary>The crash report's content (the window is just its frame): render runs show it inside the shot window.</summary>
public sealed class CrashReportView : Border
{
    private readonly Pressable _details = Ui.Button("Show details", Look.Ghost, height: Sizes.ControlMd);
    private readonly Pressable _copy = Ui.Button("Copy report", Look.Ghost, height: Sizes.ControlMd);
    private readonly Pressable _logs = Ui.Button("Open logs folder", Look.Ghost, height: Sizes.ControlMd);
    private readonly Border _detailsPanel;
    private bool _detailsOpen;

    public CrashReportView(CrashVerdict verdict, string? logPath, string? who, Func<string>? buildReport, CrashReportActions actions)
    {
        Background = Palette.Panel;
        BorderBrush = Palette.LineStrong;
        BorderThickness = new Thickness(1);
        Padding = new Thickness(Space.S6);
        var finding = verdict.Finding;
        var fix = verdict.SafeFix;

        // 1. Header: the alert tile, the cause tag, the headline, whose game.
        var tile = new Border
        {
            Width = Sizes.TileLg, Height = Sizes.TileLg, CornerRadius = new CornerRadius(Radius.Md), Background = Palette.DangerSoft,
            Child = Ui.Icon(Icons.Alert, Sizes.IconLg, Palette.DangerText).Align(HorizontalAlignment.Center, VerticalAlignment.Center),
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 2, Space.S4, 0),
        };
        var tag = Ui.Eyebrow(finding.Tag, Palette.Warning);
        tag.Margin = new Thickness(0, 0, 0, 4);
        tag.IsVisible = finding.Tag.Length > 0;
        var headline = Ui.Wrap(Ui.Heading(finding.Headline));
        var head = Ui.Column(0, tag, headline);
        if (!string.IsNullOrEmpty(who))
        {
            var whoText = Ui.Ellipsis(Ui.Text(who, TypeScale.Body, FontWeight.Normal, Palette.TextDim));
            whoText.Margin = new Thickness(0, 4, 0, 0);
            head.Children.Add(whoText);
        }
        head.VerticalAlignment = VerticalAlignment.Center;
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        header.Children.Add(tile.At(0));
        header.Children.Add(head.At(1));

        // 2. What happened.
        var what = Section("What happened", finding.WhatHappened, Space.S6);

        // 3. Likely cause + the confidence chip, in a well with the culprits under it.
        var hue = finding.Confidence switch
        {
            CrashConfidence.Confirmed => Palette.Success,
            CrashConfidence.Strong => Palette.Info,
            CrashConfidence.Possible => Palette.Warning,
            _ => Palette.TextDim,
        };
        var chip = Ui.Capsule(new Border
        {
            Height = Sizes.Badge, Padding = new Thickness(Space.S2, 0), Background = Palette.GhostHover,
            Child = Ui.Text(finding.Confidence.Word(), TypeScale.Caption, FontWeight.SemiBold, hue),
            VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(Space.S2, 0, 0, 0),
        });
        var causeHead = Ui.Row(0, Ui.Eyebrow("Likely cause", Palette.TextDim), chip);
        var causeText = BodyText(finding.LikelyCause);
        var culprits = Ui.Column(0);
        culprits.Margin = new Thickness(0, 4, 0, 0);
        foreach (var c in finding.Culprits)
        {
            var line = c.JarFileName != null ? $"{c.ModId}   ({c.JarFileName})" : c.ModId;
            if (!c.IsExternal) line += "   - part of Origin; updating Origin usually fixes this";
            var row = Ui.Body(line);
            row.Margin = new Thickness(0, 2, 0, 2);
            culprits.Children.Add(row);
        }
        culprits.IsVisible = culprits.Children.Count > 0;
        var well = new Border
        {
            Margin = new Thickness(0, Space.S2, 0, 0), Padding = new Thickness(Space.S3, Space.S2), Background = Palette.Card,
            BorderBrush = Palette.Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(Radius.Lg),
            Child = Ui.Column(0, causeText, culprits),
        };
        var cause = Ui.Column(0, causeHead, well);
        cause.Margin = new Thickness(0, 20, 0, 0);

        // 4. Recommended fix, then the buttons: the fix (only a safe one), the one-time try, Close.
        var recommended = Section("Recommended fix", finding.RecommendedFix, 20);
        var buttons = Ui.Row(Space.S2);
        buttons.Margin = new Thickness(0, Space.S6, 0, 0);
        if (fix != null)
        {
            var act = Ui.Button(fix.Label, Look.Primary, height: Sizes.ControlMd);
            act.MinWidth = 96;
            act.Click += (_, _) => actions.Fix(fix);
            buttons.Children.Add(act);
        }
        if (verdict.OfferTryWithoutAddedMods)
        {
            var tryOnce = Ui.Button("Try once without my mods", Look.Secondary, height: Sizes.ControlMd);
            tryOnce.MinWidth = 96;
            tryOnce.Click += (_, _) => actions.TryWithoutMods();
            buttons.Children.Add(tryOnce);
        }
        var close = Ui.Button("Close", Look.Secondary, height: Sizes.ControlMd);
        close.MinWidth = 96;
        close.Click += (_, _) => actions.Close();
        buttons.Children.Add(close);

        // 5. Technical details: a hairline, the eyebrow, three quiet tools; the details fold out under them.
        foreach (var b in new[] { _copy, _logs, _details }) b.Padding = new Thickness(Space.S3, 0);
        _copy.IsEnabled = buildReport != null;
        _copy.Click += async (_, _) => await CopyAsync(buildReport, actions.Host ?? this);
        _logs.Click += async (_, _) => await OpenLogsAsync(logPath, actions.Host ?? this);
        _details.Click += (_, _) => ToggleDetails();
        var tools = Ui.Row(4, _copy, _logs, _details);
        var barGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        barGrid.Children.Add(Ui.Eyebrow("Technical details", Palette.TextDim).At(0));
        barGrid.Children.Add(tools.At(1));
        var bar = new Border
        {
            Margin = new Thickness(0, Space.S6, 0, 0), Padding = new Thickness(0, Space.S3, 0, 0), BorderBrush = Palette.Line,
            BorderThickness = new Thickness(0, 1, 0, 0), Child = barGrid,
        };
        var detailsText = new SelectableTextBlock
        {
            Text = Details(verdict), FontFamily = Assets.Mono, FontSize = TypeScale.Label, Foreground = Palette.TextDim,
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(Space.S4, Space.S3),
        };
        _detailsPanel = new Border
        {
            Height = Sizes.CrashDetailsH, Margin = new Thickness(0, Space.S2, 0, 0), Background = Palette.BgAlt,
            BorderBrush = Palette.Line, BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(Radius.Lg),
            ClipToBounds = true, IsVisible = false, Child = new ScrollViewer { Content = detailsText },
        };

        Child = Ui.Column(0, header, what, cause, recommended, buttons, bar, _detailsPanel);
    }

    private static TextBlock BodyText(string text)
    {
        var t = Ui.Body(text, Palette.Text);
        t.LineHeight = 20;
        return t;
    }

    private static StackPanel Section(string eyebrow, string text, double top)
    {
        var body = BodyText(text);
        body.Margin = new Thickness(0, 6, 0, 0);
        var s = Ui.Column(0, Ui.Eyebrow(eyebrow, Palette.TextDim), body);
        s.Margin = new Thickness(0, top, 0, 0);
        return s;
    }

    private void ToggleDetails()
    {
        _detailsOpen = !_detailsOpen;
        _detailsPanel.IsVisible = _detailsOpen;
        ((TextBlock)_details.Child!).Text = _detailsOpen ? "Hide details" : "Show details";
    }

    /// <summary>Section 5's text: the evidence lines, then what else was seen (weaker findings), with their words.</summary>
    internal static string Details(CrashVerdict verdict)
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

    private async Task CopyAsync(Func<string>? buildReport, Visual host)
    {
        if (buildReport == null) return;
        var label = (TextBlock)_copy.Child!;
        try
        {
            var clipboard = TopLevel.GetTopLevel(host)?.Clipboard ?? throw new InvalidOperationException("no clipboard");
            await clipboard.SetTextAsync(buildReport());
            label.Text = "Copied";
            DispatcherTimer.RunOnce(() => label.Text = "Copy report", TimeSpan.FromSeconds(2));
        }
        catch
        {
            // The clipboard can be held by another app for a moment; the button stays, try again.
            label.Text = "Try again";
        }
    }

    internal static async Task OpenLogsAsync(string? logPath, Visual host)
    {
        try
        {
            var folder = logPath != null && File.Exists(logPath) ? Path.GetDirectoryName(logPath)! : OriginPaths.Logs;
            if (TopLevel.GetTopLevel(host)?.Launcher is { } launcher)
                await launcher.LaunchDirectoryInfoAsync(new DirectoryInfo(folder));
        }
        catch { /* the file manager refused - nothing actionable */ }
    }

    // ---- Render runs ----

    internal Pressable DetailsButton => _details;

    /// <summary>The plain view (CrashFallback) with the same words and buttons, for when the themed one throws.</summary>
    internal static Control Fallback(CrashVerdict verdict, string? logPath, string? who, Func<string>? buildReport, CrashReportActions actions)
    {
        var f = verdict.Finding;
        var rows = new List<Control> { CrashFallback.Caption(f.Tag, 0), CrashFallback.Heading(f.Headline) };
        if (!string.IsNullOrEmpty(who)) rows.Add(CrashFallback.Body(who, 6));
        rows.Add(CrashFallback.Body(f.WhatHappened, 12));
        rows.Add(CrashFallback.Caption("Likely cause (" + f.Confidence.Word() + ")", 16));
        rows.Add(CrashFallback.Body(f.LikelyCause, 4));
        rows.Add(CrashFallback.Body(f.RecommendedFix, 12));
        var details = CrashFallback.Details(Details(verdict), Sizes.CrashDetailsH);
        details.IsVisible = false;
        var buttons = new List<Control>();
        if (verdict.SafeFix is { } fix) buttons.Add(CrashFallback.Btn(fix.Label, primary: true, () => actions.Fix(fix)));
        if (verdict.OfferTryWithoutAddedMods) buttons.Add(CrashFallback.Btn("Try once without my mods", primary: false, actions.TryWithoutMods));
        buttons.Add(CrashFallback.Btn("Close", primary: false, actions.Close));
        buttons.Add(CrashFallback.Btn("Show details", primary: false, () => details.IsVisible = !details.IsVisible));
        if (buildReport != null)
            buttons.Add(CrashFallback.Btn("Copy report", primary: false, () =>
            {
                try { _ = TopLevel.GetTopLevel(actions.Host)?.Clipboard?.SetTextAsync(buildReport()); } catch { }
            }));
        buttons.Add(CrashFallback.Btn("Open logs folder", primary: false, () => _ = OpenLogsAsync(logPath, actions.Host!)));
        rows.Add(CrashFallback.ButtonRow(buttons.ToArray()));
        rows.Add(details);
        return CrashFallback.Frame(rows.ToArray());
    }
}
