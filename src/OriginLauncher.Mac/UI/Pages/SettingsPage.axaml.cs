using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using Avalonia.VisualTree;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Install;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Performance;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Pages;

namespace OriginLauncher.Mac.UI.Pages;

/// <summary>
/// Settings (twin of the WPF UI/Pages/SettingsPage.xaml.cs): the same rules, the same Core calls, the same strings.
/// Every write goes through SettingsStore.Update; every control writes only its own setting. Mac differences: the
/// startup switch is the LaunchAgent (InstallRegistry.Mac), Browse is Finder's folder picker, no Shader cache card
/// (see the XAML), and the page is detached (not hidden) when another tab shows, so leaving is DetachedFromVisualTree.
/// </summary>
public sealed partial class SettingsPage : UserControl
{
    private readonly LauncherSettings _settings;
    private readonly DispatcherTimer _ramSaveTimer;
    private bool _isLoading = true;
    private int? _pendingRamMb;

    // The JVM editor switches between per-version values, so it keeps a local guard while its fields are repopulated.
    private bool _jvmLoading = true;
    private string? _jvmVersion;
    // True once the player has typed in the Java arguments box since it was last loaded. Only then is the box written
    // back: a box that merely SHOWS what was saved (and may be stale after the crash window reset) never writes over it.
    private bool _jvmDirty;
    private string _jvmLoadedText = "";

    public SettingsPage()
    {
        InitializeComponent();
        Rail.RenderTransform = RailShift;
        _settings = SettingsStore.Load();
        // Developer is an internal switch: release builds never get the section, its rail entry or its toggle.
        if (!DeveloperVisible) RemoveDeveloperSection();
        LauncherVersionText.Text = UpdateService.IsDevBuild
            ? "Origin Launcher - dev build"
            : $"Origin Launcher {UpdateService.CurrentVersion.ToString(3)}";

        _ramSaveTimer = new DispatcherTimer(DispatcherPriority.Background) { Interval = TimeSpan.FromMilliseconds(250) };
        _ramSaveTimer.Tick += (_, _) => PersistPendingRam();
        DetachedFromVisualTree += (_, _) => PersistPendingRam();
        // The crash window may have reset (or Undo restored) a version's arguments while Settings was away.
        AttachedToVisualTree += (_, _) => ReloadJvmFromStore();

        // Memory is Auto until the player moves the slider (RamPolicy). The slider is only DISPLAYED clamped to this
        // Mac's range: nothing here writes the stored number back, so a Manual value is never changed by the launcher.
        _totalRamMb = SystemInfo.GetTotalPhysicalMemoryMb();
        RamSlider.Minimum = RamAdvice.SliderMinimumMb;
        RamSlider.Maximum = RamAdvice.MaximumMb(_totalRamMb);
        ShowRam(RamPolicy.IsManual(_settings));
        InstallPathTextBox.Text = _settings.InstallPath;
        ResolutionWidthTextBox.Text = _settings.ResolutionWidth.ToString();
        ResolutionHeightTextBox.Text = _settings.ResolutionHeight.ToString();

        if (DeveloperVisible) OfflineTestToggle.IsChecked = _settings.OfflineTestMode;
        SettingsSyncToggle.IsChecked = _settings.SyncSettingsAcrossVersions;
        ShowSyncState();
        StartAtLoginToggle.IsChecked = InstallRegistry.IsStartWithWindows();

        InitializeJvmSection();
        WireEvents();
        _isLoading = false;
    }

    private void WireEvents()
    {
        StartAtLoginToggle.IsCheckedChanged += (_, _) => StartAtLoginToggle_Changed();
        RamSlider.ValueChanged += (_, e) => RamSlider_ValueChanged(e.NewValue);
        RamAutoButton.Click += (_, _) => RamAutoButton_Click();
        InstallPathTextBox.LostFocus += (_, _) => InstallPathTextBox_LostFocus();
        InstallPathTextBox.TextChanged += (_, _) => InstallPathTextBox_TextChanged();
        BrowseButton.Click += async (_, _) => await BrowseAsync();
        SettingsSyncToggle.IsCheckedChanged += (_, _) => SettingsSyncToggle_Changed();
        PresetButton.Click += async (_, _) => await ApplyPresetAsync();
        OfflineTestToggle.IsCheckedChanged += (_, _) =>
        {
            if (_isLoading) return;
            var on = OfflineTestToggle.IsChecked == true;
            _settings.OfflineTestMode = on;
            SettingsStore.TryUpdate(s => s.OfflineTestMode = on);
        };
        JvmUndoButton.Click += (_, _) => JvmUndoButton_Click();
        JvmVersionComboBox.SelectionChanged += (_, _) => JvmVersionComboBox_SelectionChanged();
        JvmArgsTextBox.TextChanged += (_, _) => JvmArgsTextBox_TextChanged();
        JvmArgsTextBox.LostFocus += (_, _) => JvmArgsTextBox_LostFocus();
        JvmReplacePresetsToggle.IsCheckedChanged += (_, _) => JvmReplacePresetsToggle_Changed();
        ResolutionWidthTextBox.LostFocus += (_, _) => ResolutionTextBox_LostFocus();
        ResolutionHeightTextBox.LostFocus += (_, _) => ResolutionTextBox_LostFocus();

        foreach (var item in RailItems())
        {
            item.IsCheckedChanged += (s, _) => { if (s is RadioButton { IsChecked: true }) GoToSection(s); };
            // Click as well as Checked: clicking the lit item again scrolls back to its section too.
            item.Click += (s, _) => GoToSection(s);
        }
        Scroller.ScrollChanged += (_, _) => Scroller_ScrollChanged();
        // Wheel or touch: the player is steering now, so the section in view decides again.
        Scroller.AddHandler(PointerWheelChangedEvent, (_, _) => TakeOverFromClick(), RoutingStrategies.Tunnel);
        Scroller.AddHandler(PointerPressedEvent, (_, e) =>
        {
            if (e.Pointer.Type == PointerType.Touch) TakeOverFromClick();
        }, RoutingStrategies.Tunnel);
        Scroller.AddHandler(KeyDownEvent, (_, e) => Scroller_KeyDown(e), RoutingStrategies.Tunnel);
    }

    // ---- Render shots ----

    /// <summary>Render mode: scrolls so the Settings Sync row sits a little below the top of the page, whole.</summary>
    internal void TourScrollToSync()
    {
        var top = SettingsSyncToggle.TranslatePoint(new Point(0, 0), Column)?.Y ?? 0;
        Scroller.Offset = new Vector(0, Math.Max(0, top - 160));
    }

    /// <summary>Render mode: back to the top, so a later visit to this page starts where a player would.</summary>
    internal void TourScrollToTop() => Scroller.Offset = default;

    /// <summary>Render mode: press End the way a player does (the key clears a rail click), then go to the end.</summary>
    internal void TourPressEnd()
    {
        TakeOverFromClick();
        Scroller.ScrollToEnd();
    }

    // ---- Startup ----
    // Not a launcher setting: it is the LaunchAgent file itself, so the toggle reads and writes that directly (no
    // settings.json copy to drift from what macOS will actually do at the next login).
    private void StartAtLoginToggle_Changed()
    {
        if (_isLoading) return;
        var exe = Environment.ProcessPath ?? Path.Combine(AppContext.BaseDirectory, "OriginLauncher");
        try
        {
            InstallRegistry.SetStartWithWindows(StartAtLoginToggle.IsChecked == true, exe);
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            _isLoading = true;
            StartAtLoginToggle.IsChecked = InstallRegistry.IsStartWithWindows();
            _isLoading = false;
        }
    }

    // ---- Memory ----

    private void RamSlider_ValueChanged(double value)
    {
        if (_isLoading) return;
        // The player moved it: from here on it is their number (Manual).
        var ramMb = (int)value;
        RamValueText.Text = FormatRam(ramMb);
        RamWhyText.Text = RamManualText;
        RamAutoButton.IsVisible = true;
        _pendingRamMb = ramMb;
        _ramSaveTimer.Stop();
        _ramSaveTimer.Start();
    }

    private void PersistPendingRam()
    {
        _ramSaveTimer.Stop();
        if (_pendingRamMb is not { } ramMb) return;
        _pendingRamMb = null;
        _settings.RamMb = ramMb;
        _settings.RamManual = true;
        SettingsStore.TryUpdate(s => RamPolicy.SetManual(s, ramMb));
    }

    // "Back to Auto": the only way out of Manual.
    private void RamAutoButton_Click()
    {
        _ramSaveTimer.Stop();
        _pendingRamMb = null;
        var total = _totalRamMb;
        SettingsStore.TryUpdate(s => RamPolicy.SetAuto(s, total));
        _settings.RamMb = RamAdvice.RecommendedMb(total);
        _settings.RamManual = false;
        ShowRam(manual: false);
    }

    private const string RamAutoText = "Auto: Origin picks the right amount for each version.";
    private const string RamManualText = "Fixed at your choice for every version.";
    private readonly int _totalRamMb;

    // Puts the memory control in the Auto or Manual look without saving anything.
    private void ShowRam(bool manual)
    {
        var wasLoading = _isLoading;
        _isLoading = true; // moving the slider here is not the player's choice
        try
        {
            var shown = manual ? _settings.RamMb : RamAdvice.AutoMb(_totalRamMb, legacy: false, heavyPack: false);
            RamSlider.Value = Math.Clamp((int)Math.Round(shown / (double)RamAdvice.StepMb) * RamAdvice.StepMb,
                RamAdvice.SliderMinimumMb, (int)RamSlider.Maximum);
            RamValueText.Text = manual ? FormatRam((int)RamSlider.Value) : "Auto";
            RamWhyText.Text = manual ? RamManualText : RamAutoText;
            RamAutoButton.IsVisible = manual;
        }
        finally { _isLoading = wasLoading; }
    }

    private static string FormatRam(int ramMb) => $"{ramMb / 1024d:0.##} GB";

    // ---- Install folder and resolution ----
    // A rejected path is NOT saved and is not silently put back: the box keeps what was typed, shows a Danger edge and
    // says what is wrong right under it (FieldRules); fixing the text clears it as you type.
    private void InstallPathTextBox_LostFocus()
    {
        if (_isLoading) return;
        var path = (InstallPathTextBox.Text ?? "").Trim();
        if (ShowFieldProblem(InstallPathErrorText, FieldRules.InstallFolderProblem(path), InstallPathTextBox)) return;
        _settings.InstallPath = path;
        SettingsStore.TryUpdate(s => s.InstallPath = path);
    }

    private void InstallPathTextBox_TextChanged()
    {
        if (_isLoading || !InstallPathTextBox.Classes.Contains("invalid")) return;
        ShowFieldProblem(InstallPathErrorText, FieldRules.InstallFolderProblem(InstallPathTextBox.Text ?? ""), InstallPathTextBox);
    }

    private async Task BrowseAsync()
    {
        if (TopLevel.GetTopLevel(this)?.StorageProvider is not { CanPickFolder: true } storage) return;
        IStorageFolder? start = null;
        try
        {
            if (Directory.Exists(InstallPathTextBox.Text)) start = await storage.TryGetFolderFromPathAsync(InstallPathTextBox.Text!);
        }
        catch { /* no start folder: Finder opens where it likes */ }
        var picked = await storage.OpenFolderPickerAsync(new FolderPickerOpenOptions
        {
            AllowMultiple = false,
            SuggestedStartLocation = start,
        });
        if (picked.Count == 0 || picked[0].TryGetLocalPath() is not { } folder) return;
        InstallPathTextBox.Text = folder;
        ShowFieldProblem(InstallPathErrorText, null, InstallPathTextBox);
        _settings.InstallPath = folder;
        SettingsStore.TryUpdate(s => s.InstallPath = folder);
    }

    /// <summary>Shows or clears a field's problem: the Danger edge on the boxes and the sentence under them. True when there is one.</summary>
    private static bool ShowFieldProblem(TextBlock message, string? problem, params TextBox[] boxes)
    {
        message.Text = problem ?? "";
        message.IsVisible = problem != null;
        foreach (var box in boxes) box.Classes.Set("invalid", problem != null);
        return problem != null;
    }

    /// <summary>Render mode: the two error states the shots show (a path that is not a folder, a width that is too small).</summary>
    internal void TourShowFieldErrors()
    {
        InstallPathTextBox.Text = "games";
        ShowFieldProblem(InstallPathErrorText, FieldRules.InstallFolderProblem(InstallPathTextBox.Text), InstallPathTextBox);
        ResolutionWidthTextBox.Text = "100";
        ShowFieldProblem(ResolutionErrorText, FieldRules.ResolutionProblem("100", FieldRules.MinWidth, "Width"), ResolutionWidthTextBox);
    }

    /// <summary>Render mode: put the fields back the way the settings have them.</summary>
    internal void TourClearFieldErrors()
    {
        InstallPathTextBox.Text = _settings.InstallPath;
        ResolutionWidthTextBox.Text = _settings.ResolutionWidth.ToString();
        ShowFieldProblem(InstallPathErrorText, null, InstallPathTextBox);
        ShowFieldProblem(ResolutionErrorText, null, ResolutionWidthTextBox, ResolutionHeightTextBox);
    }

    private void ResolutionTextBox_LostFocus()
    {
        if (_isLoading) return;
        // Same rule as the folder: say what is wrong under the row instead of quietly putting the old number back.
        var widthProblem = FieldRules.ResolutionProblem(ResolutionWidthTextBox.Text ?? "", FieldRules.MinWidth, "Width");
        var heightProblem = FieldRules.ResolutionProblem(ResolutionHeightTextBox.Text ?? "", FieldRules.MinHeight, "Height");
        ResolutionWidthTextBox.Classes.Set("invalid", widthProblem != null);
        ResolutionHeightTextBox.Classes.Set("invalid", heightProblem != null);
        if (widthProblem != null || heightProblem != null)
        {
            ResolutionErrorText.Text = widthProblem ?? heightProblem!;
            ResolutionErrorText.IsVisible = true;
            return;
        }
        ResolutionErrorText.IsVisible = false;
        var width = int.Parse(ResolutionWidthTextBox.Text!.Trim());
        var height = int.Parse(ResolutionHeightTextBox.Text!.Trim());
        _settings.ResolutionWidth = width;
        _settings.ResolutionHeight = height;
        SettingsStore.TryUpdate(s => { s.ResolutionWidth = width; s.ResolutionHeight = height; });
    }

    // ---- Settings Sync ----
    // Turning it on also builds its store from every installed version (newest file wins per setting, so nothing the
    // player set is lost), on a worker thread since it reads every instance. Turning it off only stops syncing.
    private void SettingsSyncToggle_Changed()
    {
        ShowSyncState();
        if (_isLoading) return;
        var on = SettingsSyncToggle.IsChecked == true;
        _settings.SyncSettingsAcrossVersions = on;
        SettingsStore.TryUpdate(s => s.SyncSettingsAcrossVersions = on);
        if (on) _ = Task.Run(SettingsSync.Seed);
    }

    // The ON / OFF capsule reads the switch (the WPF DataTriggers): ON = the accent wash and the accent word.
    private void ShowSyncState()
    {
        var on = SettingsSyncToggle.IsChecked == true;
        SyncStateText.Text = on ? "ON" : "OFF";
        if (on)
        {
            SyncStateCapsule.Background = Find<IBrush>("Brush.AccentSoft");
            SyncStateText.Foreground = Find<IBrush>("Brush.AccentHover");
        }
        else
        {
            SyncStateCapsule.ClearValue(Border.BackgroundProperty);
            SyncStateText.ClearValue(TextBlock.ForegroundProperty);
        }
    }

    // ---- Recommended for this Mac ----
    // The player's explicit click, so it MAY change options that already exist, but only the preset's own keys
    // (distances, particles, clouds, graphics, FPS cap), and only in folders whose game is not running. Detection and
    // file work run off the UI thread.
    private async Task ApplyPresetAsync()
    {
        PresetButton.IsEnabled = false;
        SetPresetStatus("Checking this Mac...");
        var (summary, applied, total) = await Task.Run(() =>
        {
            var hardware = HardwareProbe.Current;
            var tier = TierPicker.Pick(hardware);
            var done = 0;
            var count = 0;
            foreach (var version in VersionManager.OriginSupportedVersions)
            {
                foreach (var (_, dir) in AccountFolders.GameDirs(version))
                {
                    if (!File.Exists(Path.Combine(dir, "options.txt"))) continue;
                    count++;
                    if (SettingsSync.IsGameRunning(dir)) continue;
                    if (PresetWriter.ApplyNow(dir, version, tier) == PresetOutcome.Applied) done++;
                }
            }
            return ($"{tier} - {HardwareProbe.Describe(hardware)}", done, count);
        });
        SetPresetStatus(total == 0
            ? $"{summary}. New versions start with these settings."
            : $"{summary}. Applied to {applied} of {total} game folder{(total == 1 ? "" : "s")}.");
        PresetButton.IsEnabled = true;
    }

    private void SetPresetStatus(string text)
    {
        PresetStatusText.Text = text;
        PresetStatusText.IsVisible = text.Length > 0;
    }

    // ---- JVM arguments (per version) ----

    private void InitializeJvmSection()
    {
        var versions = VersionCatalog.SupportedVersions;
        JvmVersionComboBox.ItemsSource = versions;
        // Open on the version the player is set to play, so the common case ("add a flag to the version I'm using")
        // needs no dropdown trip.
        JvmVersionComboBox.SelectedItem = VersionCatalog.IsSupported(_settings.SelectedVersion)
            ? _settings.SelectedVersion
            : versions.FirstOrDefault();
        LoadJvmOptionsForSelectedVersion();
        _jvmLoading = false;
    }

    private void LoadJvmOptionsForSelectedVersion()
    {
        _jvmLoading = true;
        _jvmVersion = JvmVersionComboBox.SelectedItem as string;
        var opts = _jvmVersion != null && _settings.JvmArgsByVersion.TryGetValue(_jvmVersion, out var stored)
            ? stored
            : new VersionJvmOptions();
        _jvmLoadedText = opts.Arguments ?? "";
        JvmArgsTextBox.Text = opts.Arguments;
        JvmReplacePresetsToggle.IsChecked = opts.ReplacePresets;
        _jvmDirty = false;
        UpdateJvmStatus();
        UpdateJvmCustomizedList();
        UpdateJvmUndo();
        _jvmLoading = false;
    }

    // Picks up Java-argument changes made outside this page (the crash window's reset). Never while the player has an
    // unsaved edit in the box: theirs stays.
    private void ReloadJvmFromStore()
    {
        if (_jvmDirty || _jvmLoading) return;
        var fresh = SettingsStore.Load();
        _settings.JvmArgsByVersion = fresh.JvmArgsByVersion;
        _settings.JvmArgsBeforeReset = fresh.JvmArgsBeforeReset;
        LoadJvmOptionsForSelectedVersion();
    }

    /// <summary>Render mode: show what is saved now (a shot that seeds a reset record calls this).</summary>
    internal void TourReloadJvm() => ReloadJvmFromStore();

    // "Reset after a crash" + Undo: only while a reset is on record and the version has no arguments of its own.
    private void UpdateJvmUndo()
    {
        var show = RepairExecutor.CanUndoJavaArgsReset(_settings, _jvmVersion)
                   && string.IsNullOrWhiteSpace(JvmArgsTextBox.Text) && JvmReplacePresetsToggle.IsChecked != true;
        JvmUndoRow.IsVisible = show;
        if (!show) return;
        var before = _settings.JvmArgsBeforeReset[_jvmVersion!];
        var args = JvmArgLine.Preview(before.Arguments);
        JvmUndoText.Text = args.Length == 0
            ? "Java wouldn't start with your settings here, so Origin went back to its own."
            : $"Java wouldn't start with your arguments, so Origin went back to its own. Yours were: {args}";
    }

    // Puts back the arguments the crash window reset. One control, one job: it only touches this version's arguments.
    private void JvmUndoButton_Click()
    {
        var version = _jvmVersion;
        if (version == null) return;
        JavaArgsUndoOutcome outcome;
        try
        {
            outcome = RepairExecutor.UndoJavaArgsReset(version, SettingsStore.Update);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // settings.json held by another program (a sync tool): nothing changed, say so and stay put.
            JvmArgsStatusText.Text = "Couldn't put your arguments back - the settings file is in use. Try again in a moment.";
            return;
        }
        var fresh = SettingsStore.Load();
        _settings.JvmArgsByVersion = fresh.JvmArgsByVersion;
        _settings.JvmArgsBeforeReset = fresh.JvmArgsBeforeReset;
        LoadJvmOptionsForSelectedVersion();
        if (outcome == JavaArgsUndoOutcome.ChangedSince)
            JvmArgsStatusText.Text = "You've set new arguments since, so they were kept.";
    }

    private void JvmVersionComboBox_SelectionChanged()
    {
        if (_jvmLoading) return;
        // Commit whatever is in the box to the version being navigated AWAY from before repointing the editor, so
        // switching versions never loses a pending edit (the box saves on LostFocus, which the dropdown steals).
        SaveJvmOptions();
        LoadJvmOptionsForSelectedVersion();
    }

    private void JvmArgsTextBox_TextChanged()
    {
        // Live feedback only: persistence happens on LostFocus, matching the other text fields on this page.
        if (_jvmLoading) return;
        // Dirty means the player's text differs from what was loaded: Avalonia can raise TextChanged for the load's own
        // Text write after _jvmLoading is cleared, which used to block every later reload (the crash reset never showed).
        _jvmDirty = (JvmArgsTextBox.Text ?? "") != _jvmLoadedText;
        UpdateJvmStatus();
    }

    private void JvmArgsTextBox_LostFocus()
    {
        if (_jvmLoading) return;
        SaveJvmOptions();
        UpdateJvmCustomizedList();
        UpdateJvmUndo();
    }

    private void JvmReplacePresetsToggle_Changed()
    {
        if (_jvmLoading) return;
        _jvmDirty = true;
        SaveJvmOptions();
        UpdateJvmStatus();
        UpdateJvmCustomizedList();
        UpdateJvmUndo();
    }

    // Writes the editor's current contents to _jvmVersion (the version the editor is pointed at), NOT to the dropdown's
    // current selection: those differ for exactly one call, when the selection has already moved.
    private void SaveJvmOptions()
    {
        var version = _jvmVersion;
        if (version == null || !_jvmDirty) return;
        _jvmDirty = false;
        var text = JvmArgsTextBox.Text ?? "";
        var replace = JvmReplacePresetsToggle.IsChecked == true;
        var opts = new VersionJvmOptions { Arguments = text, ReplacePresets = replace };
        if (opts.IsEmpty)
        {
            _settings.JvmArgsByVersion.Remove(version);
            SettingsStore.TryUpdate(s => s.JvmArgsByVersion.Remove(version));
        }
        else
        {
            // Arguments of the player's own replace the reset ones for good: the Undo record goes in the same write.
            _settings.JvmArgsByVersion[version] = opts;
            _settings.JvmArgsBeforeReset.Remove(version);
            SettingsStore.TryUpdate(s =>
            {
                s.JvmArgsByVersion[version] = new VersionJvmOptions { Arguments = text, ReplacePresets = replace };
                s.JvmArgsBeforeReset.Remove(version);
            });
        }
    }

    private void UpdateJvmStatus()
    {
        var tokens = JvmArgLine.Split(JvmArgsTextBox.Text);
        var replace = JvmReplacePresetsToggle.IsChecked == true;
        // The one mistake that stops the game booting outright: choosing a collector while Origin's G1 preset is still
        // in play (the JVM aborts with "Multiple garbage collectors selected"), so name the switch that fixes it.
        if (!replace && JvmArgLine.ConflictsWithPresetCollector(tokens))
        {
            JvmArgsStatusText.Text = $"{JvmArgLine.SelectedCollector(tokens)} conflicts with Origin defaults. Turn on Replace defaults.";
            return;
        }
        var warning = JvmArgLine.Warn(tokens);
        if (warning != null)
        {
            JvmArgsStatusText.Text = warning;
            return;
        }
        var count = $"{tokens.Count} argument{(tokens.Count == 1 ? "" : "s")}";
        JvmArgsStatusText.Text = replace
            ? tokens.Count == 0 ? "No custom arguments" : $"{count}, defaults off"
            : tokens.Count == 0 ? "Using Origin defaults" : $"{count}, Origin defaults on";
    }

    private void UpdateJvmCustomizedList()
    {
        var customized = VersionCatalog.SupportedVersions.Where(v => _settings.JvmArgsFor(v) != null).ToList();
        JvmCustomizedVersionsText.Text = customized.Count == 0 ? "No custom versions" : $"Custom: {string.Join(", ", customized)}";
    }

    // ---- Section rail (Poster) ----
    // The rail lives inside the scrolled column (so it lines up with it at every width) and is shifted by the scroll
    // offset so it stays put on screen. A click jumps to its section and that item stays lit (the last sections are
    // shorter than the window, so they can never reach the top edge); scrolling by hand lights the section under the
    // top edge. The rules are in SettingsRailSpy (shared with Windows).
    private bool _railSyncing;
    // The rail's shift (a render transform, so following the scroll never re-runs layout).
    private readonly TranslateTransform RailShift = new();
    private readonly SettingsRailSpy _spy = new();

    /// <summary>Developer is for dev builds (local, not stamped by CI). Render mode shows the public layout.</summary>
    private static bool DeveloperVisible => UpdateService.IsDevBuild && !OriginPaths.IsScratchRoot;

    private void RemoveDeveloperSection()
    {
        Rail.Children.Remove(RailDeveloper);
        Sections.Children.Remove(SecDeveloper);
        Sections.Children.Remove(DeveloperCard);
    }

    private List<RadioButton> RailItems() => Rail.Children.OfType<RadioButton>().ToList();

    private Control? SectionOf(RadioButton item) =>
        item.Tag is string name ? this.FindControl<Control>(name) : null;

    private double SectionTop(Control section) => section.TranslatePoint(new Point(0, 0), Column)?.Y ?? double.MaxValue;

    private double ScrollableHeight => Math.Max(0, Scroller.Extent.Height - Scroller.Viewport.Height);

    /// <summary>Settings was opened (again): the first section, from the top, nothing remembered.</summary>
    public void ShowFromTop()
    {
        _spy.Reset();
        Scroller.Offset = default;
        LightRailItem(0);
    }

    private void LightRailItem(int index)
    {
        var items = RailItems();
        if (index < 0 || index >= items.Count || items[index].IsChecked == true) return;
        _railSyncing = true;
        items[index].IsChecked = true;
        _railSyncing = false;
    }

    /// <summary>CI screen render only: press the rail item for <paramref name="sectionName"/> the way a click does.</summary>
    internal void TourClickRail(string sectionName)
    {
        var item = RailItems().FirstOrDefault(r => r.Tag as string == sectionName);
        if (item == null) return;
        item.IsChecked = true; // raises IsCheckedChanged
        GoToSection(item);
    }

    private void GoToSection(object? sender)
    {
        if (_railSyncing || sender is not RadioButton item || !IsLoaded) return;
        var index = RailItems().IndexOf(item);
        if (index < 0 || SectionOf(item) is not { } section) return;
        _spy.Click(index);
        Scroller.Offset = new Vector(0, SettingsRailSpy.TargetOffset(SectionTop(section), ScrollableHeight));
    }

    // Ends the click's hold and lights what is in view right now. The page may not move at all (End while already at
    // the end), and then no scroll event comes to do it.
    private void TakeOverFromClick()
    {
        if (_spy.Pinned == null) return;
        _spy.UserScrolled();
        RelightRail();
    }

    // Keys that scroll the page (and Space, which pages down). Typing in a field is not scrolling.
    private static bool IsScrollKey(Key key) =>
        key is Key.PageUp or Key.PageDown or Key.Home or Key.End or Key.Up or Key.Down or Key.Space;

    private void Scroller_KeyDown(KeyEventArgs e)
    {
        if (e.Source is TextBox || (e.Source as Visual)?.FindAncestorOfType<TextBox>() != null) return;
        if (IsScrollKey(e.Key)) TakeOverFromClick();
    }

    private void Scroller_ScrollChanged()
    {
        RailShift.Y = Scroller.Offset.Y;
        RelightRail();
    }

    private void RelightRail()
    {
        if (!IsLoaded) return;
        var tops = RailItems().Select(item => SectionOf(item) is { } section ? SectionTop(section) : double.MaxValue).ToList();
        LightRailItem(_spy.Resolve(tops, Scroller.Offset.Y, ScrollableHeight));
    }

    // ThemeLookup: the page runs this from its constructor, before it has a window (the Sync capsule had no colours).
    private T? Find<T>(string key) where T : class => this.ThemeValue<T>(key);
}
