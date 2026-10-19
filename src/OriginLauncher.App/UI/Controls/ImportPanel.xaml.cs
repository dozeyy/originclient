using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.UI.Controls;

// The import panel: Find -> Review -> Importing -> Done, hosted modally by MainWindow.
//
// All work is the backend's (scan, plan, apply: each async, cancellable, off the UI thread); this
// file only shows what it returns. Rules it keeps:
//  * nothing starts until the panel opens (the scan is NOT part of launcher startup);
//  * the newest action wins: closing cancels a scan or plan in flight, and a plan that finishes after
//    a newer one was asked for is dropped;
//  * while an import is applying, ImportRunState says so (Home keeps Play disabled), Esc and the
//    window's close ask before cancelling, and a cancel puts everything back (the engine's rollback);
//  * progress is shown from a 100 ms timer reading the latest report, never one UI hop per file;
//  * a finished import has no "undo" here: Done shows the summary and the log link.
public partial class ImportPanel : UserControl, IDisposable
{
    public enum Step { Find, Review, Importing, Done }

    /// <summary>The panel wants to go away (Close, Done, Esc, scrim).</summary>
    public event EventHandler? CloseRequested;

    /// <summary>An import finished and was kept: Home and Profiles re-read the settings.</summary>
    public event EventHandler<ImportResult>? Imported;

    private readonly IImportBackend _backend;
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private readonly LatestProgress _progress = new();
    private readonly List<DetectedInstance> _all = new();
    private readonly Dictionary<Button, (DetectedInstance Instance, Border Marker)> _rows = new();

    private CancellationTokenSource? _scanCts;
    private CancellationTokenSource? _planCts;
    private CancellationTokenSource? _applyCts;
    private int _planSeq;
    private bool _disposed;
    private bool _suppressTarget;
    private bool _detailsOpen;
    private bool _startedScan;
    private double _barFraction;

    private PickResult? _pick;
    private DetectedInstance? _selected;     // highlighted on Find
    private DetectedInstance? _source;       // under review
    private string? _target;                 // the Origin version picked (null = none yet)
    private ImportPlan? _plan;
    private ImportResult? _result;
    private ImportOptions _options = new();

    public Step Current { get; private set; } = Step.Find;

    /// <summary>True from the moment Import is pressed until the engine has finished or rolled back.</summary>
    public bool IsApplying { get; private set; }

    public ImportPanel(IImportBackend backend)
    {
        _backend = backend;
        InitializeComponent();
        _tick.Tick += (_, _) => ShowLatestProgress();
        Unloaded += (_, _) => StopSpinners();
        ShowStep(Step.Find);
    }

    // ---------------------------------------------------------------- opening

    /// <summary>MainWindow, when the panel opens: starts the scan. Never called at launcher startup.</summary>
    public void Start()
    {
        if (_startedScan || _disposed) return;
        _startedScan = true;
        _ = ScanAsync();
    }

    private async Task ScanAsync()
    {
        ShowScanning();
        var cts = Replace(ref _scanCts);
        ScanResult? scan = null;
        try
        {
            scan = await Task.Run(() => _backend.ScanAsync(null, cts.Token), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception)
        {
            // a scan problem is "nothing found", never a crash of the panel
            scan = new ScanResult();
            scan.Notes.Add("Couldn't look through the other launchers");
        }
        if (_disposed || cts.IsCancellationRequested) return;

        _all.Clear();
        _all.AddRange(scan.Instances);
        _pick = VersionPicker.Pick(scan.Instances);
        if (_pick.Mode == PickMode.Auto && _pick.Preselected is { } picked)
        {
            BeginReview(picked);
            return;
        }
        ShowFindList(_pick.Preselected, scan.Notes.Count > 0 ? scan.Notes[0] : null, false);
    }

    // ---------------------------------------------------------------- Find

    private void ShowScanning()
    {
        ShowStep(Step.Find);
        ScanningPanel.Visibility = Visibility.Visible;
        EmptyPanel.Visibility = Visibility.Collapsed;
        FindScroll.Visibility = Visibility.Collapsed;
        SetPrimary("Continue", enabled: false);
        BrowseButton.IsEnabled = true;
        StartSpin(ScanSpin);
    }

    private void ShowFindList(DetectedInstance? preselected, string? note, bool warn)
    {
        ShowStep(Step.Find);
        StopSpin(ScanSpin);
        ScanningPanel.Visibility = Visibility.Collapsed;
        _rows.Clear();
        FindList.Children.Clear();
        _selected = null;

        if (_all.Count == 0)
        {
            EmptyPanel.Visibility = Visibility.Visible;
            FindScroll.Visibility = Visibility.Collapsed;
            EmptyNote.Foreground = (Brush)FindResource(warn ? "Brush.Warning" : "Brush.TextDim");
            if (note != null) EmptyNote.Text = note;
            SetPrimary("Continue", enabled: false);
            return;
        }

        EmptyPanel.Visibility = Visibility.Collapsed;
        FindScroll.Visibility = Visibility.Visible;
        FindNote.Text = note ?? (_all.Count > 1 ? "Pick the instance you play on. Origin only reads it; nothing in it is changed." : "");
        FindNote.Foreground = (Brush)FindResource(warn ? "Brush.Warning" : "Brush.TextDim");
        FindNote.Visibility = FindNote.Text.Length == 0 ? Visibility.Collapsed : Visibility.Visible;

        foreach (var group in _all.GroupBy(i => i.SourceLabel).OrderBy(g => GroupOrder(g.Key)))
        {
            FindList.Children.Add(new TextBlock
            {
                Text = GroupTitle(group.Key).ToUpperInvariant(),
                Style = (Style)FindResource("Text.Eyebrow"),
                Margin = new Thickness(0, FindList.Children.Count == 0 ? 0 : 16, 0, 8),
            });
            foreach (var instance in group
                         .OrderByDescending(i => i.LastPlayed ?? DateTimeOffset.MinValue)
                         .ThenByDescending(i => i.PlaytimeSeconds ?? 0)
                         .ThenBy(i => i.Name, StringComparer.OrdinalIgnoreCase))
                FindList.Children.Add(BuildInstanceRow(instance, instance == preselected));
        }

        var start = preselected != null && _all.Contains(preselected) ? preselected : null;
        if (start != null) Select(start);
        else SetPrimary("Continue", enabled: false);
    }

    private static int GroupOrder(string label) => label switch
    {
        "Minecraft" => 0, "Prism" => 1, "Modrinth" => 2, "CurseForge" => 3, _ => 4,
    };

    private static string GroupTitle(string label) => label switch
    {
        "Minecraft" => "Minecraft launcher",
        "Prism" => "Prism Launcher",
        "Modrinth" => "Modrinth App",
        "Folder" => "Picked folders",
        _ => label,
    };

    private Button BuildInstanceRow(DetectedInstance instance, bool suggested)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var marker = new Border
        {
            Width = 3, Margin = new Thickness(0, 2, 12, 2), CornerRadius = (CornerRadius)FindResource("Radius.Control"),
            Background = (Brush)FindResource("Brush.Accent"), Visibility = Visibility.Hidden,
        };
        grid.Children.Add(marker);

        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock
        {
            Text = instance.Name, Style = (Style)FindResource("Text.Body"), FontWeight = FontWeights.SemiBold,
        });
        text.Children.Add(new TextBlock
        {
            Text = Describe(instance), Style = (Style)FindResource("Text.Caption"), Margin = new Thickness(0, 2, 0, 0),
            Foreground = (Brush)FindResource(instance.Problem != null ? "Brush.Warning" : "Brush.TextDim"),
        });
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);

        if (suggested)
        {
            var tag = new TextBlock
            {
                Text = "SUGGESTED", Style = (Style)FindResource("Text.Eyebrow"), VerticalAlignment = VerticalAlignment.Center,
                Foreground = (Brush)FindResource("Brush.Accent"), Margin = new Thickness(12, 0, 0, 0),
            };
            Grid.SetColumn(tag, 2);
            grid.Children.Add(tag);
        }

        var button = new Button
        {
            Style = (Style)FindResource("Button.Row"), Content = grid, Margin = new Thickness(0, 0, 0, 8),
        };
        System.Windows.Automation.AutomationProperties.SetName(button, $"{instance.Name}, {Describe(instance)}");
        button.Click += (_, _) => Select(instance);
        button.MouseDoubleClick += (_, _) =>
        {
            Select(instance);
            Primary_Click(this, new RoutedEventArgs());
        };
        _rows[button] = (instance, marker);
        return button;
    }

    private void Select(DetectedInstance instance)
    {
        _selected = instance;
        foreach (var (button, row) in _rows)
        {
            var on = row.Instance == instance;
            row.Marker.Visibility = on ? Visibility.Visible : Visibility.Hidden;
            button.BorderBrush = (Brush)FindResource(on ? "Brush.Accent" : "Brush.SoftStroke");
        }
        SetPrimary("Continue", enabled: true);
    }

    /// <summary>"Prism - Minecraft 1.21.1, Fabric - played 12 h - last played 3 days ago" (ASCII only).</summary>
    internal static string Describe(DetectedInstance i)
    {
        var parts = new List<string>();
        if (i.McVersion == null)
        {
            parts.Add(i.Problem ?? DetectedInstance.CouldntRead);
        }
        else
        {
            var loader = i.Loader is LoaderKind.None or LoaderKind.Unknown ? "" : $", {i.Loader}";
            parts.Add($"Minecraft {i.McVersion}{loader}" + (i.Confidence == Confidence.Inferred ? " (guessed)" : ""));
        }
        if (i.PlaytimeSeconds is > 0) parts.Add("played " + Playtime(i.PlaytimeSeconds.Value));
        if (i.LastPlayed is { } last && last > DateTimeOffset.MinValue.AddYears(2)) parts.Add("last played " + Ago(last));
        return string.Join(" - ", parts);
    }

    private static string Playtime(long seconds)
    {
        if (seconds < 3600) return Math.Max(1, seconds / 60) + " min";
        var hours = seconds / 3600.0;
        return (hours < 10 ? hours.ToString("0.#", CultureInfo.InvariantCulture) : Math.Round(hours).ToString("0", CultureInfo.InvariantCulture)) + " h";
    }

    private static string Ago(DateTimeOffset when)
    {
        var span = DateTimeOffset.UtcNow - when;
        if (span.TotalMinutes < 2) return "just now";
        if (span.TotalHours < 1) return $"{(int)span.TotalMinutes} min ago";
        if (span.TotalDays < 1) return $"{(int)span.TotalHours} h ago";
        if (span.TotalDays < 2) return "yesterday";
        if (span.TotalDays < 60) return $"{(int)span.TotalDays} days ago";
        return $"{(int)(span.TotalDays / 30)} months ago";
    }

    private async void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog { Title = "Pick a Minecraft instance folder" };
        if (dialog.ShowDialog(Window.GetWindow(this)) != true || string.IsNullOrWhiteSpace(dialog.FolderName)) return;

        var folder = dialog.FolderName;
        ShowScanning();
        var cts = Replace(ref _scanCts);
        FolderInspection? found;
        try
        {
            found = await Task.Run(() => _backend.InspectFolderAsync(folder, cts.Token), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception)
        {
            found = new FolderInspection { Message = FolderInspection.NotAnInstance };
        }
        if (_disposed || cts.IsCancellationRequested) return;

        if (found.Instances.Count == 0)
        {
            ShowFindList(_pick?.Preselected, found.Message ?? FolderInspection.NotAnInstance, warn: true);
            return;
        }
        foreach (var instance in found.Instances)
            if (!_all.Any(a => string.Equals(a.GameDir, instance.GameDir, StringComparison.OrdinalIgnoreCase)))
                _all.Add(instance);
        if (found.Instances.Count == 1)
        {
            BeginReview(found.Instances[0]);
            return;
        }
        ShowFindList(found.Instances[0], "Found in the folder you picked. Choose the one to import.", warn: false);
    }

    // ---------------------------------------------------------------- Review

    private void BeginReview(DetectedInstance source)
    {
        _source = source;
        _plan = null;
        ShowStep(Step.Review);
        SourceName.Text = source.Name;
        SourceDetail.Text = Describe(source) + "  (" + GroupTitle(source.SourceLabel) + ")";
        ChooseAnotherButton.Visibility = Visibility.Visible;

        var targets = _backend.SupportedTargets
            .OrderByDescending(v => v, Comparer<string>.Create(McVersion.Compare)).ToList();
        _suppressTarget = true;
        TargetBox.ItemsSource = targets;
        var supported = source.McVersion != null && targets.Contains(source.McVersion);
        TargetBox.SelectedItem = supported ? source.McVersion : null;
        _suppressTarget = false;
        _target = supported ? source.McVersion : null;

        PlanPanel.Visibility = Visibility.Collapsed;
        if (!supported)
        {
            var check = VersionPicker.CheckSupport(source.McVersion, targets);
            TargetNote.Text = (check.Reason ?? "Pick a Minecraft version Origin supports.")
                + (source.McVersion == null || check.Suggestion == null ? "" : " Pick one from the list.");
            TargetNote.Foreground = (Brush)FindResource("Brush.Warning");
            PlanningPanel.Visibility = Visibility.Collapsed;
            FooterNote.Text = "Pick an Origin version to continue.";
            FooterNote.Foreground = (Brush)FindResource("Brush.Warning");
            SetPrimary("Import", enabled: false);
            return;
        }
        TargetNote.Text = "";
        _ = ReplanAsync();
    }

    private void TargetBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressTarget || TargetBox.SelectedItem is not string picked || _source == null) return;
        _target = picked;
        if (_source.McVersion != null && picked != _source.McVersion)
        {
            TargetNote.Text = $"Your old instance used Minecraft {_source.McVersion}. Mods built for another version are left out.";
            TargetNote.Foreground = (Brush)FindResource("Brush.TextDim");
        }
        else
        {
            TargetNote.Text = "";
        }
        _ = ReplanAsync();
    }

    private void Option_Click(object sender, RoutedEventArgs e)
    {
        _options = new ImportOptions
        {
            CopyWorlds = CopyWorldsToggle.IsChecked == true,
            CopyUnusedPacks = CopyPacksToggle.IsChecked == true,
            KeepMyOriginSettings = KeepSettingsToggle.IsChecked == true,
            ReplaceServerList = ReplaceServersToggle.IsChecked == true,
            ReplaceExistingCaches = ReplaceCachesToggle.IsChecked == true,
        };
        if (_source != null && _target != null) _ = ReplanAsync();
    }

    private async Task ReplanAsync()
    {
        if (_source == null || _target == null) return;
        var seq = ++_planSeq;
        var cts = Replace(ref _planCts);
        _plan = null;
        PlanPanel.Visibility = Visibility.Collapsed;
        PlanningPanel.Visibility = Visibility.Visible;
        PlanningText.Text = "Checking what can come over...";
        FooterNote.Text = "";
        SetPrimary("Import", enabled: false);
        StartSpin(PlanSpin);
        _progress.Last = null;
        _tick.Start();

        var source = _source;
        var target = _target;
        var options = _options;
        ImportPlan? plan = null;
        string? failure = null;
        try
        {
            plan = await Task.Run(() => _backend.PlanAsync(source, target, options, _progress, cts.Token), cts.Token);
        }
        catch (OperationCanceledException)
        {
            return;
        }
        catch (Exception ex)
        {
            failure = ImportErrors.Plain(ex);
        }
        if (_disposed || seq != _planSeq) return; // a newer action won

        _tick.Stop();
        StopSpin(PlanSpin);
        PlanningPanel.Visibility = Visibility.Collapsed;
        if (plan == null)
        {
            FooterNote.Text = $"Couldn't check this instance ({failure}).";
            FooterNote.Foreground = (Brush)FindResource("Brush.Danger");
            return;
        }
        _plan = plan;
        RenderPlan(plan);
    }

    private void RenderPlan(ImportPlan plan)
    {
        PlanPanel.Visibility = Visibility.Visible;

        // Counts.
        StatsPanel.Children.Clear();
        var imported = plan.Mods.Count(m => m.IsCopied);
        var off = plan.Mods.Count(m => m.Outcome is ModOutcome.CopiedDisabled or ModOutcome.CopiedAsBefore);
        StatsPanel.Children.Add(Chip(imported.ToString(CultureInfo.InvariantCulture), off > 0 ? $"mods to import ({off} off)" : "mods to import"));
        StatsPanel.Children.Add(Chip(plan.Mods.Count(m => m.Outcome == ModOutcome.BuiltIn).ToString(CultureInfo.InvariantCulture), "mods already in Origin"));
        StatsPanel.Children.Add(Chip(plan.Ops.Count(o => o.Item is PlanItemKind.Setting or PlanItemKind.Config).ToString(CultureInfo.InvariantCulture), "settings files"));
        StatsPanel.Children.Add(Chip(plan.Ops.Count(o => o.Item == PlanItemKind.ResourcePack).ToString(CultureInfo.InvariantCulture), "resource packs"));
        StatsPanel.Children.Add(Chip(plan.Ops.Count(o => o.Item == PlanItemKind.ShaderPack).ToString(CultureInfo.InvariantCulture), "shader packs"));
        if (plan.Options.CopyWorlds || plan.Ops.Any(o => o.Item == PlanItemKind.World))
            StatsPanel.Children.Add(Chip(plan.Ops.Count(o => o.Item == PlanItemKind.World).ToString(CultureInfo.InvariantCulture), "worlds"));
        StatsPanel.Children.Add(Chip((plan.GameFiles?.ReusedCount ?? 0).ToString(CultureInfo.InvariantCulture), "game files reused"));
        StatsPanel.Children.Add(Chip(plan.Caches.Count(c => c.WillImport).ToString(CultureInfo.InvariantCulture), "caches reused"));

        if (plan.GameFiles is { MetadataUnavailable: false, ToDownloadBytes: > 0 } files)
        {
            DownloadNote.Text = $"About {ImportSummary.Megabytes(files.ToDownloadBytes)} will download the first time you press Play.";
            DownloadNote.Visibility = Visibility.Visible;
        }
        else
        {
            DownloadNote.Visibility = Visibility.Collapsed;
        }

        // Needs attention: always shown, never behind the details.
        AttentionList.Children.Clear();
        var attention = plan.Lines.Where(l => l.Section == SummarySection.NeedsAttention).ToList();
        if (attention.Count == 0)
        {
            AttentionList.Children.Add(new TextBlock { Text = "Nothing needs your attention.", Style = (Style)FindResource("Text.Caption") });
        }
        foreach (var line in attention) AttentionList.Children.Add(BuildAttentionRow(line));

        // What stops Import, in a sentence.
        if (!plan.CanApply)
        {
            FooterNote.Text = string.Join(" ", plan.Blocked);
            FooterNote.Foreground = (Brush)FindResource("Brush.Warning");
        }
        else
        {
            FooterNote.Text = plan.ProfileName != null ? $"Your mods go into a new profile called \"{plan.ProfileName}\"." : "";
            FooterNote.Foreground = (Brush)FindResource("Brush.TextDim");
        }
        SetPrimary("Import", enabled: plan.CanApply && !string.IsNullOrEmpty(plan.TargetVersion));
        if (_detailsOpen) BuildTables(plan);
    }

    private Border Chip(string number, string label)
    {
        // One line: "6  mods to import". The counts stay short so the Needs attention list is on screen without scrolling.
        var stack = new StackPanel { Orientation = Orientation.Horizontal };
        stack.Children.Add(new TextBlock { Text = number, Style = (Style)FindResource("Text.Body"), FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center });
        stack.Children.Add(new TextBlock
        {
            Text = label, Style = (Style)FindResource("Text.Caption"), TextTrimming = TextTrimming.None, Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center,
        });
        return new Border
        {
            MinHeight = 32, Margin = new Thickness(0, 0, 8, 8), Padding = new Thickness(10, 5, 12, 5),
            Background = (Brush)FindResource("Brush.Card"), BorderBrush = (Brush)FindResource("Brush.SoftStroke"),
            BorderThickness = new Thickness(1), CornerRadius = (CornerRadius)FindResource("Radius.Card"), Child = stack,
        };
    }

    private UIElement BuildAttentionRow(SummaryLine line)
    {
        var grid = new Grid { Margin = new Thickness(0, 0, 0, 8) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.Children.Add(new Border
        {
            Width = 6, Height = 6, Margin = new Thickness(2, 7, 10, 0), VerticalAlignment = VerticalAlignment.Top,
            Background = (Brush)FindResource("Brush.Warning"), CornerRadius = (CornerRadius)FindResource("Radius.Control"),
        });
        var text = new TextBlock { Style = (Style)FindResource("Text.Body"), TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.None };
        text.Inlines.Add(new Run(line.Name) { FontWeight = FontWeights.SemiBold });
        if (!string.IsNullOrEmpty(line.Reason))
            text.Inlines.Add(new Run(" - " + line.Reason) { Foreground = (Brush)FindResource("Brush.TextDim") });
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        return grid;
    }

    private void DetailsToggle_Click(object sender, RoutedEventArgs e) => SetDetails(!_detailsOpen);

    private void SetDetails(bool open)
    {
        _detailsOpen = open;
        DetailsPanel.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
        DetailsLabel.Text = open ? "Hide details" : "Review details";
        DetailsIcon.Data = (Geometry)FindResource(open ? "Icon.Minimize" : "Icon.Plus");
        if (open && _plan != null) BuildTables(_plan);
    }

    // Every item the plan knows about, with what happens and why: nothing is dropped without a line here.
    private void BuildTables(ImportPlan plan)
    {
        TablesPanel.Children.Clear();

        TablesPanel.Children.Add(TableTitle($"Mods ({plan.Mods.Count})"));
        if (plan.Mods.Count == 0) TablesPanel.Children.Add(TableEmpty("No mods in this instance."));
        foreach (var mod in plan.Mods)
            TablesPanel.Children.Add(TableRow(mod.DisplayName ?? mod.ModId ?? mod.FileName, ModStatus(mod.Outcome), mod.Reason ?? "", ModStatusBrush(mod.Outcome)));

        var rest = plan.Lines.Where(l => l.Kind != PlanItemKind.Mod).ToList();
        TablesPanel.Children.Add(TableTitle($"Settings, packs, game files and caches ({rest.Count})", top: 16));
        if (rest.Count == 0) TablesPanel.Children.Add(TableEmpty("Nothing else."));
        var reusedFiles = rest.Where(l => l.Section == SummarySection.ReusedGameFiles).ToList();
        if (reusedFiles.Count > 0)
            TablesPanel.Children.Add(TableRow($"{reusedFiles.Count} game files", "Reused", $"Copied from another launcher on this PC ({ImportSummary.Megabytes(reusedFiles.Sum(l => l.Bytes))}), checked before use.", "Brush.Info"));
        foreach (var line in rest.Where(l => l.Section != SummarySection.ReusedGameFiles))
            TablesPanel.Children.Add(TableRow(line.Name, SectionStatus(line.Section), line.Reason ?? "", SectionBrush(line.Section)));
    }

    private UIElement TableTitle(string text, double top = 0) => new TextBlock
    {
        Text = text, Style = (Style)FindResource("Text.Body"), FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, top, 0, 6),
    };

    private UIElement TableEmpty(string text) => new TextBlock { Text = text, Style = (Style)FindResource("Text.Caption") };

    private UIElement TableRow(string name, string status, string why, string statusBrush)
    {
        var grid = new Grid { Margin = new Thickness(0, 0, 0, 0) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(2, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(150) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(3, GridUnitType.Star) });
        void Cell(int column, string text, Brush? brush)
        {
            var cell = new TextBlock
            {
                Text = text, Style = (Style)FindResource("Text.Caption"), TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.None,
                Margin = new Thickness(0, 4, 10, 4),
            };
            if (brush != null) cell.Foreground = brush;
            Grid.SetColumn(cell, column);
            grid.Children.Add(cell);
        }
        Cell(0, name, (Brush)FindResource("Brush.Text"));
        Cell(1, status, (Brush)FindResource(statusBrush));
        Cell(2, why, null);
        return new Border { BorderBrush = (Brush)FindResource("Brush.SoftStroke"), BorderThickness = new Thickness(0, 0, 0, 1), Child = grid };
    }

    private static string ModStatus(ModOutcome o) => o switch
    {
        ModOutcome.Copied => "Imported",
        ModOutcome.CopiedDisabled => "Imported, turned off",
        ModOutcome.CopiedAsBefore => "Imported, off as before",
        ModOutcome.BuiltIn => "Skipped: built in",
        _ => "Not imported",
    };

    private static string ModStatusBrush(ModOutcome o) => o switch
    {
        ModOutcome.Copied => "Brush.Success",
        ModOutcome.CopiedDisabled => "Brush.Warning",
        ModOutcome.CopiedAsBefore => "Brush.TextDim",
        ModOutcome.BuiltIn => "Brush.Info",
        _ => "Brush.Danger",
    };

    private static string SectionStatus(SummarySection s) => s switch
    {
        SummarySection.Imported => "Will be imported",
        SummarySection.ReusedGameFiles or SummarySection.ReusedCaches => "Reused",
        SummarySection.NeedsAttention => "Needs attention",
        _ => "Not imported",
    };

    private static string SectionBrush(SummarySection s) => s switch
    {
        SummarySection.Imported => "Brush.Success",
        SummarySection.ReusedGameFiles or SummarySection.ReusedCaches => "Brush.Info",
        SummarySection.NeedsAttention => "Brush.Warning",
        _ => "Brush.Danger",
    };

    private void ChooseAnother_Click(object sender, RoutedEventArgs e)
    {
        _planCts?.Cancel();
        _planSeq++;
        _tick.Stop();
        StopSpin(PlanSpin);
        ShowFindList(_source ?? _pick?.Preselected, null, false);
    }

    // ---------------------------------------------------------------- Importing

    private async Task StartImportAsync()
    {
        if (_plan is not { CanApply: true } plan || IsApplying) return;
        IsApplying = true;
        ImportRunState.Begin();
        _applyCts = new CancellationTokenSource();
        var token = _applyCts.Token;
        _barFraction = 0;
        SetBar(0);
        ProgressTitle.Text = "Importing...";
        ProgressDetail.Text = "Getting ready...";
        _progress.Last = null;
        ShowStep(Step.Importing);
        _tick.Start();

        ImportResult result;
        try
        {
            result = await _backend.ApplyAsync(plan, _progress, token);
        }
        catch (Exception ex)
        {
            result = new ImportResult
            {
                ImportId = plan.ImportId, State = ImportState.RolledBack,
                Error = $"The import didn't finish ({ImportErrors.Plain(ex)}). If anything was changed, Origin puts it back the next time it starts.",
            };
        }
        finally
        {
            _tick.Stop();
            IsApplying = false;
            ImportRunState.End();
        }
        if (_disposed) return;
        _result = result;
        ShowDone(plan, result);
        if (result.Succeeded) Imported?.Invoke(this, result);
    }

    private async Task AskCancelAsync()
    {
        if (!IsApplying || Confirm.Visibility == Visibility.Visible) return;
        var cancel = await Confirm.ShowAsync("Cancel the import?",
            "Everything copied so far is put back the way it was.", "Cancel import", "Keep importing", focusSecondary: true);
        if (cancel && IsApplying) _applyCts?.Cancel();
    }

    private void ShowLatestProgress()
    {
        if (_progress.Last is not { } p) return;
        if (Current == Step.Importing)
        {
            var fraction = p.Total > 0 ? Math.Clamp(p.Done / (double)p.Total, 0, 1) : 0;
            if (p.Stage == ImportStage.Finishing) fraction = 1;
            _barFraction = Math.Max(_barFraction, fraction); // never backwards when a step counts its own files
            SetBar(_barFraction);
            ProgressDetail.Text = StageText(p.Stage) + (string.IsNullOrEmpty(p.Item) ? "" : ": " + p.Item);
        }
        else if (Current == Step.Review && PlanningPanel.Visibility == Visibility.Visible)
        {
            PlanningText.Text = p.Stage == ImportStage.Planning && !string.IsNullOrEmpty(p.Item)
                ? $"Checking what can come over ({p.Item})..." : "Checking what can come over...";
        }
    }

    private static string StageText(ImportStage s) => s switch
    {
        ImportStage.Mods => "Copying mods",
        ImportStage.Settings => "Copying settings",
        ImportStage.Packs => "Copying packs",
        ImportStage.Worlds => "Copying worlds",
        ImportStage.GameFiles => "Reusing game files",
        ImportStage.Caches => "Moving caches",
        ImportStage.Finishing => "Finishing up",
        _ => "Getting ready",
    };

    private void ProgressTrack_SizeChanged(object sender, SizeChangedEventArgs e) => SetBar(_barFraction);

    private void SetBar(double fraction)
    {
        var inner = Math.Max(0, ProgressTrack.ActualWidth - 2);
        ProgressFill.Width = inner * fraction;
        ProgressPercent.Text = Math.Round(fraction * 100).ToString("0", CultureInfo.InvariantCulture) + "%";
    }

    // ---------------------------------------------------------------- Done

    private void ShowDone(ImportPlan plan, ImportResult result)
    {
        ShowStep(Step.Done);
        var view = ImportSummaryView.Parse(result.SummaryText);
        DoneSections.Children.Clear();

        if (result.Succeeded)
        {
            DoneHeadline.Text = "Import finished";
            DoneNote.Text = plan.ProfileName != null
                ? $"Your mods are in the profile \"{plan.ProfileName}\" for Minecraft {plan.TargetVersion}. Press Play when you are ready."
                : $"Your settings and packs are in Minecraft {plan.TargetVersion}. Press Play when you are ready.";
            DoneError.Visibility = Visibility.Collapsed;
        }
        else
        {
            DoneHeadline.Text = result.Cancelled ? "Import cancelled" : "Nothing was imported";
            DoneNote.Text = view.Outcome ?? (result.Cancelled ? "Cancelled: nothing was changed." : "Nothing was changed.");
            DoneError.Text = result.Error ?? "";
            DoneError.Visibility = string.IsNullOrEmpty(result.Error) ? Visibility.Collapsed : Visibility.Visible;
        }

        // After a cancel or a failure the plan's lines describe what WOULD have happened, not what did: show none of them.
        foreach (var block in result.Succeeded ? view.Blocks : Array.Empty<SummaryBlock>())
        {
            if (block.Lines.Count == 0) continue;
            DoneSections.Children.Add(new TextBlock
            {
                Text = $"{block.Title.ToUpperInvariant()} ({block.Lines.Count})", Style = (Style)FindResource("Text.Eyebrow"),
                Foreground = (Brush)FindResource(block.Section == null ? "Brush.TextMuted" : SectionBrush(block.Section.Value)),
                Margin = new Thickness(0, 18, 0, 6),
            });
            foreach (var line in block.Lines.Take(12))
                DoneSections.Children.Add(new TextBlock
                {
                    Text = line, Style = (Style)FindResource("Text.Caption"), TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.None,
                    Margin = new Thickness(0, 0, 0, 4),
                });
            if (block.Lines.Count > 12)
                DoneSections.Children.Add(new TextBlock
                {
                    Text = $"and {block.Lines.Count - 12} more: open the import log to see them all.", Style = (Style)FindResource("Text.Caption"),
                    Foreground = (Brush)FindResource("Brush.TextMuted"),
                });
        }
        OpenLogButton.Visibility = ImportLayout.IsValidId(result.ImportId) ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OpenLog_Click(object sender, RoutedEventArgs e)
    {
        if (_result is not { } result || !ImportLayout.IsValidId(result.ImportId)) return;
        var folder = ImportLayout.RunDir(_backend.ImportsRoot, result.ImportId);
        try
        {
            if (Directory.Exists(folder))
                Process.Start(new ProcessStartInfo("explorer.exe", "\"" + folder + "\"") { UseShellExecute = true });
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            FooterNote.Text = "Couldn't open the log folder.";
        }
    }

    // ---------------------------------------------------------------- buttons, keys

    private void Primary_Click(object sender, RoutedEventArgs e)
    {
        if (!PrimaryButton.IsEnabled && sender == PrimaryButton) return;
        switch (Current)
        {
            case Step.Find:
                if (_selected != null) BeginReview(_selected);
                break;
            case Step.Review:
                if (PrimaryButton.IsEnabled) _ = StartImportAsync();
                break;
            case Step.Done:
                CloseRequested?.Invoke(this, EventArgs.Empty);
                break;
        }
    }

    private void Secondary_Click(object sender, RoutedEventArgs e)
    {
        if (Current == Step.Importing) _ = AskCancelAsync();
        else RequestClose();
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e) => RequestClose();

    /// <summary>Closes unless an import is writing (then it asks about cancelling instead). True when it closed or asked.</summary>
    public bool RequestClose()
    {
        if (IsApplying)
        {
            _ = AskCancelAsync();
            return true;
        }
        CloseRequested?.Invoke(this, EventArgs.Empty);
        return true;
    }

    /// <summary>MainWindow's Esc: a prompt closes first, then a running import asks, otherwise the panel closes.</summary>
    public bool HandleEscape()
    {
        if (Confirm.Visibility == Visibility.Visible)
        {
            Confirm.Dismiss();
            return true;
        }
        return RequestClose();
    }

    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        // Enter runs the primary action unless focus is on something that uses Enter itself.
        if (e.Key == Key.Enter && Confirm.Visibility != Visibility.Visible && PrimaryButton.IsEnabled
            && PrimaryButton.Visibility == Visibility.Visible
            && Keyboard.FocusedElement is not (ButtonBase or ComboBox or TextBoxBase))
        {
            e.Handled = true;
            Primary_Click(PrimaryButton, new RoutedEventArgs());
            return;
        }
        base.OnPreviewKeyDown(e);
    }

    private void SetPrimary(string text, bool enabled)
    {
        PrimaryButton.Content = text;
        PrimaryButton.IsEnabled = enabled;
        System.Windows.Automation.AutomationProperties.SetName(PrimaryButton, text);
        if (enabled)
            Dispatcher.BeginInvoke(DispatcherPriority.Input, () =>
            {
                if (PrimaryButton.IsEnabled && !(Keyboard.FocusedElement is ComboBox)) PrimaryButton.Focus();
            });
    }

    private void ShowStep(Step step)
    {
        Current = step;
        FindPage.Visibility = step == Step.Find ? Visibility.Visible : Visibility.Collapsed;
        ReviewPage.Visibility = step == Step.Review ? Visibility.Visible : Visibility.Collapsed;
        ImportingPage.Visibility = step == Step.Importing ? Visibility.Visible : Visibility.Collapsed;
        DonePage.Visibility = step == Step.Done ? Visibility.Visible : Visibility.Collapsed;
        BrowseButton.Visibility = step == Step.Find ? Visibility.Visible : Visibility.Collapsed;
        CloseButton.Visibility = step is Step.Importing ? Visibility.Collapsed : Visibility.Visible;
        SecondaryButton.Visibility = step == Step.Done ? Visibility.Collapsed : Visibility.Visible;
        SecondaryButton.Content = step == Step.Importing ? "Cancel import" : "Cancel";
        PrimaryButton.Visibility = step == Step.Importing ? Visibility.Collapsed : Visibility.Visible;
        FooterNote.Foreground = (Brush)FindResource("Brush.TextDim");
        switch (step)
        {
            case Step.Find:
                StepText.Text = "STEP 1 OF 3";
                TitleText.Text = "Pick what to bring over";
                FooterNote.Text = "Origin only reads the old instance. Account and login files are never touched.";
                break;
            case Step.Review:
                StepText.Text = "STEP 2 OF 3";
                TitleText.Text = "Review the import";
                break;
            case Step.Importing:
                StepText.Text = "STEP 3 OF 3";
                TitleText.Text = "Importing";
                FooterNote.Text = "";
                break;
            case Step.Done:
                StepText.Text = "DONE";
                TitleText.Text = "Import";
                FooterNote.Text = "";
                SetPrimary("Done", enabled: true);
                break;
        }
    }

    // ---------------------------------------------------------------- small helpers

    // The newest action wins: starts a fresh token and cancels whatever the previous one was doing.
    private CancellationTokenSource Replace(ref CancellationTokenSource? slot)
    {
        var old = slot;
        var fresh = new CancellationTokenSource();
        slot = fresh;
        try { old?.Cancel(); } catch (ObjectDisposedException) { }
        return fresh;
    }

    private void StartSpin(RotateTransform transform)
    {
        if (!SystemParameters.ClientAreaAnimation) return;
        if (TryFindResource("Motion.Base") is Duration { HasTimeSpan: true } d && d.TimeSpan == TimeSpan.Zero) return; // reduced motion
        transform.BeginAnimation(RotateTransform.AngleProperty,
            new DoubleAnimation(0, 360, TimeSpan.FromMilliseconds(900)) { RepeatBehavior = RepeatBehavior.Forever });
    }

    private static void StopSpin(RotateTransform transform) => transform.BeginAnimation(RotateTransform.AngleProperty, null);

    private void StopSpinners()
    {
        StopSpin(ScanSpin);
        StopSpin(PlanSpin);
    }

    /// <summary>Cancels a scan, a plan, and stops the timer. Never cancels an apply: that is the engine's rollback to run.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _tick.Stop();
        StopSpinners();
        try { _scanCts?.Cancel(); } catch (ObjectDisposedException) { }
        try { _planCts?.Cancel(); } catch (ObjectDisposedException) { }
    }

    private sealed class LatestProgress : IProgress<ImportProgress>
    {
        private ImportProgress? _last;
        public ImportProgress? Last { get => Volatile.Read(ref _last); set => Volatile.Write(ref _last, value); }
        public void Report(ImportProgress value) => Last = value;
    }

    // ---------------------------------------------------------------- CI screen render (UI/RenderShots.cs)

    internal void TourPrimary() => Primary_Click(PrimaryButton, new RoutedEventArgs());

    internal void TourSetDetails(bool open) => SetDetails(open);

    internal void TourScrollReview(double offset) => ReviewScroll.ScrollToVerticalOffset(offset);

    internal void TourAskCancel() => _ = AskCancelAsync();
}
