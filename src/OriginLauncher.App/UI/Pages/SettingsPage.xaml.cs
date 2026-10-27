using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using Microsoft.Win32;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Performance;
using OriginLauncher.App.Core.Install;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Pages;

public partial class SettingsPage : UserControl
{
    private readonly LauncherSettings _settings;
    private readonly DispatcherTimer _ramSaveTimer;
    private bool _isLoading = true;
    private int? _pendingRamMb;

    // The JVM editor switches between per-version values, so it keeps a local
    // guard while its fields are being repopulated.
    private bool _jvmLoading = true;
    private string? _jvmVersion;
    // True once the player has typed in the Java arguments box since it was last loaded. Only then is the box
    // written back: a box that merely SHOWS what was saved (and may be stale after the crash window reset the
    // arguments) never writes over the reset.
    private bool _jvmDirty;

    /// <summary>
    /// Render mode (UI/RenderShots.cs): scrolls so the Settings Sync row sits a little below the top of the page, whole.
    /// Settings Sync is the first section now, so this lands near the top; the shot stays as the row's own cut-off check
    /// at both window sizes.
    /// </summary>
    internal void TourScrollToSync()
    {
        var top = SettingsSyncToggle.TranslatePoint(new Point(0, 0), Column).Y;
        Scroller.ScrollToVerticalOffset(Math.Max(0, top - 160));
    }

    /// <summary>Render mode: back to the top, so a later visit to this page starts where a player would.</summary>
    internal void TourScrollToTop() => Scroller.ScrollToVerticalOffset(0);

    /// <summary>Render mode: press End the way a player does (the key event clears a rail click), then go to the end of the page.</summary>
    internal void TourPressEnd()
    {
        if (PresentationSource.FromVisual(Scroller) is not { } source) return;
        Scroller.RaiseEvent(new KeyEventArgs(Keyboard.PrimaryDevice, source, 0, Key.End) { RoutedEvent = Keyboard.PreviewKeyDownEvent });
        Scroller.ScrollToEnd();
    }

    public SettingsPage()
    {
        InitializeComponent();
        _settings = SettingsStore.Load();
        // Developer is an internal switch: release builds never get the section, its rail entry or its toggle.
        if (!DeveloperVisible) RemoveDeveloperSection();
        LauncherVersionText.Text = UpdateService.IsDevBuild
            ? "Origin Launcher - dev build"
            : $"Origin Launcher {UpdateService.CurrentVersion.ToString(3)}";

        _ramSaveTimer = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = TimeSpan.FromMilliseconds(250)
        };
        _ramSaveTimer.Tick += (_, _) => PersistPendingRam();
        Unloaded += (_, _) => PersistPendingRam();
        // Pages stay attached and are hidden on a tab switch (MainWindow.ShowPage), so leaving Settings is a hide.
        IsVisibleChanged += (_, e) =>
        {
            if (!(bool)e.NewValue) PersistPendingRam();
            else ReloadJvmFromStore(); // the crash window may have reset (or Undo restored) a version's arguments meanwhile
        };

        // Memory is Auto until the player moves the slider (RamPolicy). The slider is only DISPLAYED
        // clamped to this PC's range: nothing here writes the stored number back, so a Manual value
        // is never changed by the launcher.
        _totalRamMb = SystemInfo.GetTotalPhysicalMemoryMb();
        var safeMaximum = RamAdvice.MaximumMb(_totalRamMb);
        RamSlider.Minimum = RamAdvice.SliderMinimumMb;
        RamSlider.Maximum = safeMaximum;
        ShowRam(RamPolicy.IsManual(_settings));
        InstallPathTextBox.Text = _settings.InstallPath;
        ResolutionWidthTextBox.Text = _settings.ResolutionWidth.ToString();
        ResolutionHeightTextBox.Text = _settings.ResolutionHeight.ToString();

        OriginUiToggle.IsChecked = OriginClientConfigBridge.IsOriginUiEnabled();
        ShaderCacheToggle.IsChecked = _settings.ShaderCacheNvidia || _settings.ShaderCacheAmd;
        if (DeveloperVisible) OfflineTestToggle.IsChecked = _settings.OfflineTestMode;
        SettingsSyncToggle.IsChecked = _settings.SyncSettingsAcrossVersions;
        StartWithWindowsToggle.IsChecked = InstallRegistry.IsStartWithWindows();

        InitializeJvmSection();

        _isLoading = false;
    }

    // Not a launcher setting — it's the Windows Run entry itself, so the
    // toggle reads and writes the registry directly (no settings.json copy to
    // drift from what Windows will actually do).
    private void StartWithWindowsToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        var exe = Environment.ProcessPath ?? System.IO.Path.Combine(AppContext.BaseDirectory, "OriginLauncher.App.exe");
        try
        {
            InstallRegistry.SetStartWithWindows(StartWithWindowsToggle.IsChecked == true, exe);
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or System.Security.SecurityException or System.IO.IOException)
        {
            StartWithWindowsToggle.IsChecked = InstallRegistry.IsStartWithWindows();
        }
    }

    private void RamSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isLoading) return;
        // The player moved it: from here on it is their number (Manual).
        var ramMb = (int)e.NewValue;
        RamValueText.Text = FormatRam(ramMb);
        RamWhyText.Text = RamManualText;
        RamAutoButton.Visibility = Visibility.Visible;
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
        SettingsStore.Update(s => RamPolicy.SetManual(s, ramMb));
    }

    // "Back to Auto": the only way out of Manual.
    private void RamAutoButton_Click(object sender, RoutedEventArgs e)
    {
        _ramSaveTimer.Stop();
        _pendingRamMb = null;
        var total = _totalRamMb;
        SettingsStore.Update(s => RamPolicy.SetAuto(s, total));
        _settings.RamMb = RamAdvice.RecommendedMb(total);
        _settings.RamManual = false;
        ShowRam(manual: false);
    }

    private const string RamAutoText = "Auto: Origin picks the right amount for each version.";
    private const string RamManualText = "Fixed at your choice for every version.";
    private int _totalRamMb;

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
            RamAutoButton.Visibility = manual ? Visibility.Visible : Visibility.Collapsed;
        }
        finally { _isLoading = wasLoading; }
    }

    private static string FormatRam(int ramMb) => $"{ramMb / 1024d:0.##} GB";

    // A rejected path is NOT saved and is not silently put back: the box keeps what was typed, shows a Danger edge and
    // says what is wrong right under it (FieldRules); fixing the text clears it as you type.
    private void InstallPathTextBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        var path = InstallPathTextBox.Text.Trim();
        if (ShowFieldProblem(InstallPathErrorText, FieldRules.InstallFolderProblem(path), InstallPathTextBox)) return;
        _settings.InstallPath = path;
        SettingsStore.Update(s => s.InstallPath = path);
    }

    private void InstallPathTextBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_isLoading || !Chrome.GetInvalid(InstallPathTextBox)) return;
        ShowFieldProblem(InstallPathErrorText, FieldRules.InstallFolderProblem(InstallPathTextBox.Text), InstallPathTextBox);
    }

    private void BrowseButton_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog
        {
            InitialDirectory = InstallPathTextBox.Text
        };
        if (dialog.ShowDialog() == true)
        {
            InstallPathTextBox.Text = dialog.FolderName;
            ShowFieldProblem(InstallPathErrorText, null, InstallPathTextBox);
            _settings.InstallPath = dialog.FolderName;
            SettingsStore.Update(s => s.InstallPath = dialog.FolderName);
        }
    }

    /// <summary>Shows or clears a field's problem: the Danger edge on the boxes and the sentence under them. True when there is one.</summary>
    private static bool ShowFieldProblem(TextBlock message, string? problem, params TextBox[] boxes)
    {
        message.Text = problem ?? "";
        message.Visibility = problem == null ? Visibility.Collapsed : Visibility.Visible;
        foreach (var box in boxes) Chrome.SetInvalid(box, problem != null);
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

    private void OriginUiToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        OriginClientConfigBridge.SetOriginUiEnabled(true);
    }

    private void OriginUiToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        OriginClientConfigBridge.SetOriginUiEnabled(false);
    }

    // Turning Settings Sync on also builds its store from every installed
    // version (newest file wins per setting, so nothing the player set is
    // lost) — on a worker thread, since it reads every instance. Turning it off
    // only stops syncing: every version keeps exactly what it has.
    private void SettingsSyncToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.SyncSettingsAcrossVersions = true;
        SettingsStore.Update(s => s.SyncSettingsAcrossVersions = true);
        _ = Task.Run(SettingsSync.Seed);
    }

    private void SettingsSyncToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.SyncSettingsAcrossVersions = false;
        SettingsStore.Update(s => s.SyncSettingsAcrossVersions = false);
    }

    // "Recommended for this PC": the player's explicit click, so it MAY change options that already
    // exist - but only the preset's own keys (distances, particles, clouds, graphics, FPS cap), and only
    // in folders whose game is not running. Detection and file work run off the UI thread.
    private async void PresetButton_Click(object sender, RoutedEventArgs e)
    {
        PresetButton.IsEnabled = false;
        PresetStatusText.Text = "Checking this PC...";
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
                    if (!System.IO.File.Exists(System.IO.Path.Combine(dir, "options.txt"))) continue;
                    count++;
                    if (SettingsSync.IsGameRunning(dir)) continue;
                    if (PresetWriter.ApplyNow(dir, version, tier) == PresetOutcome.Applied) done++;
                }
            }
            return ($"{tier} - {HardwareProbe.Describe(hardware)}", done, count);
        });
        PresetStatusText.Text = total == 0
            ? $"{summary}. New versions start with these settings."
            : $"{summary}. Applied to {applied} of {total} game folder{(total == 1 ? "" : "s")}.";
        PresetButton.IsEnabled = true;
    }

    private void ShaderCacheToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShaderCacheNvidia = true;
        _settings.ShaderCacheAmd = true;
        SettingsStore.Update(s =>
        {
            s.ShaderCacheNvidia = true;
            s.ShaderCacheAmd = true;
        });
    }

    private void ShaderCacheToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShaderCacheNvidia = false;
        _settings.ShaderCacheAmd = false;
        SettingsStore.Update(s =>
        {
            s.ShaderCacheNvidia = false;
            s.ShaderCacheAmd = false;
        });
    }

    private void OfflineTestToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.OfflineTestMode = true;
        SettingsStore.Update(s => s.OfflineTestMode = true);
    }

    private void OfflineTestToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.OfflineTestMode = false;
        SettingsStore.Update(s => s.OfflineTestMode = false);
    }

    // ----- JVM arguments (per version) -----

    private void InitializeJvmSection()
    {
        var versions = VersionCatalog.SupportedVersions;
        JvmVersionComboBox.ItemsSource = versions;

        // Open on the version the player is set to play, so the common case
        // ("add a flag to the version I'm using") needs no dropdown trip.
        JvmVersionComboBox.SelectedItem =
            VersionCatalog.IsSupported(_settings.SelectedVersion)
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

        JvmArgsTextBox.Text = opts.Arguments;
        JvmReplacePresetsToggle.IsChecked = opts.ReplacePresets;
        _jvmDirty = false;
        UpdateJvmStatus();
        UpdateJvmCustomizedList();
        UpdateJvmUndo();
        _jvmLoading = false;
    }

    // Picks up Java-argument changes made outside this page (the crash window's reset). Never while the player has
    // an unsaved edit in the box: theirs stays.
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
        var show = Core.Compat.RepairExecutor.CanUndoJavaArgsReset(_settings, _jvmVersion)
                   && string.IsNullOrWhiteSpace(JvmArgsTextBox.Text) && JvmReplacePresetsToggle.IsChecked != true;
        JvmUndoRow.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
        if (!show) return;
        var before = _settings.JvmArgsBeforeReset[_jvmVersion!];
        var args = JvmArgLine.Preview(before.Arguments);
        JvmUndoText.Text = args.Length == 0
            ? "Java wouldn't start with your settings here, so Origin went back to its own."
            : $"Java wouldn't start with your arguments, so Origin went back to its own. Yours were: {args}";
    }

    // Puts back the arguments the crash window reset. One control, one job: it only touches this version's Java arguments.
    private void JvmUndoButton_Click(object sender, RoutedEventArgs e)
    {
        var version = _jvmVersion;
        if (version == null) return;
        Core.Compat.JavaArgsUndoOutcome outcome;
        try
        {
            outcome = Core.Compat.RepairExecutor.UndoJavaArgsReset(version, SettingsStore.Update);
        }
        catch (Exception ex) when (ex is System.IO.IOException or UnauthorizedAccessException)
        {
            // settings.json held by another program (antivirus, a sync tool): nothing changed, say so and stay put.
            JvmArgsStatusText.Text = "Couldn't put your arguments back - the settings file is in use. Try again in a moment.";
            return;
        }
        var fresh = SettingsStore.Load();
        _settings.JvmArgsByVersion = fresh.JvmArgsByVersion;
        _settings.JvmArgsBeforeReset = fresh.JvmArgsBeforeReset;
        LoadJvmOptionsForSelectedVersion();
        if (outcome == Core.Compat.JavaArgsUndoOutcome.ChangedSince)
            JvmArgsStatusText.Text = "You've set new arguments since, so they were kept.";
    }

    private void JvmVersionComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_jvmLoading) return;
        // Commit whatever is in the box to the version being navigated AWAY from
        // before repointing the editor, so switching versions never loses a
        // pending edit (the box saves on LostFocus, which the dropdown steals).
        SaveJvmOptions();
        LoadJvmOptionsForSelectedVersion();
    }

    private void JvmArgsTextBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        // Live feedback only — persistence happens on LostFocus, matching the
        // other text fields on this page.
        if (_jvmLoading) return;
        _jvmDirty = true;
        UpdateJvmStatus();
    }

    private void JvmArgsTextBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (_jvmLoading) return;
        SaveJvmOptions();
        UpdateJvmCustomizedList();
        UpdateJvmUndo();
    }

    private void JvmReplacePresetsToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_jvmLoading) return;
        _jvmDirty = true;
        SaveJvmOptions();
        UpdateJvmStatus();
        UpdateJvmCustomizedList();
        UpdateJvmUndo();
    }

    private void JvmReplacePresetsToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_jvmLoading) return;
        _jvmDirty = true;
        SaveJvmOptions();
        UpdateJvmStatus();
        UpdateJvmCustomizedList();
        UpdateJvmUndo();
    }

    // Writes the editor's current contents to _jvmVersion (the version the editor
    // is pointed at), NOT to the dropdown's current selection — those differ for
    // exactly one call, when the selection has already moved.
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
            SettingsStore.Update(s => s.JvmArgsByVersion.Remove(version));
        }
        else
        {
            // Arguments of the player's own replace the reset ones for good: the Undo record goes in the same write,
            // so clearing the box later never brings back an old "Reset after a crash" offer.
            _settings.JvmArgsByVersion[version] = opts;
            _settings.JvmArgsBeforeReset.Remove(version);
            SettingsStore.Update(s =>
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

        // The one mistake that stops the game booting outright: choosing a
        // collector while Origin's G1 preset is still in play. The JVM aborts in
        // init with "Multiple garbage collectors selected" -- it is not an
        // override -- so name the switch that fixes it.
        if (!replace && JvmArgLine.ConflictsWithPresetCollector(tokens))
        {
            JvmArgsStatusText.Text =
                $"{JvmArgLine.SelectedCollector(tokens)} conflicts with Origin defaults. Turn on Replace defaults.";
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
            ? tokens.Count == 0
                ? "No custom arguments"
                : $"{count}, defaults off"
            : tokens.Count == 0
                ? "Using Origin defaults"
                : $"{count}, Origin defaults on";
    }

    private void UpdateJvmCustomizedList()
    {
        var customized = VersionCatalog.SupportedVersions
            .Where(v => _settings.JvmArgsFor(v) != null)
            .ToList();

        JvmCustomizedVersionsText.Text = customized.Count == 0
            ? "No custom versions"
            : $"Custom: {string.Join(", ", customized)}";
    }

    private void ResolutionTextBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;

        // Same rule as the folder: say what is wrong under the row instead of quietly putting the old number back.
        var widthProblem = FieldRules.ResolutionProblem(ResolutionWidthTextBox.Text, FieldRules.MinWidth, "Width");
        var heightProblem = FieldRules.ResolutionProblem(ResolutionHeightTextBox.Text, FieldRules.MinHeight, "Height");
        Chrome.SetInvalid(ResolutionWidthTextBox, widthProblem != null);
        Chrome.SetInvalid(ResolutionHeightTextBox, heightProblem != null);
        if (widthProblem != null || heightProblem != null)
        {
            ResolutionErrorText.Text = widthProblem ?? heightProblem!;
            ResolutionErrorText.Visibility = Visibility.Visible;
            return;
        }
        ResolutionErrorText.Visibility = Visibility.Collapsed;
        var width = int.Parse(ResolutionWidthTextBox.Text.Trim());
        var height = int.Parse(ResolutionHeightTextBox.Text.Trim());

        _settings.ResolutionWidth = width;
        _settings.ResolutionHeight = height;
        SettingsStore.Update(s => { s.ResolutionWidth = width; s.ResolutionHeight = height; });
    }

    // ---- Section rail (Poster) ----------------------------------------------
    // The rail lives inside the scrolled column (so it lines up with it at every
    // width) and is shifted by the scroll offset so it stays put on screen. A click
    // jumps to its section and that item stays lit (the last sections are shorter than
    // the window, so they can never reach the top edge); scrolling by hand lights the
    // section under the top edge. The rules are in SettingsRailSpy.
    private bool _railSyncing;
    private readonly SettingsRailSpy _spy = new();

    /// <summary>Developer is for dev builds (local, not stamped by CI). Render mode shows the public layout.</summary>
    private static bool DeveloperVisible => UpdateService.IsDevBuild && !App.IsRenderMode;

    private void RemoveDeveloperSection()
    {
        Rail.Children.Remove(RailDeveloper);
        Sections.Children.Remove(SecDeveloper);
        Sections.Children.Remove(DeveloperCard);
    }

    private List<RadioButton> RailItems() => Rail.Children.OfType<RadioButton>().ToList();

    private FrameworkElement? SectionOf(RadioButton item) =>
        item.Tag is string name ? FindName(name) as FrameworkElement : null;

    private double SectionTop(FrameworkElement section) =>
        section.TranslatePoint(new Point(0, 0), Column).Y;

    /// <summary>Settings was opened (again): the first section, from the top, nothing remembered.</summary>
    public void ShowFromTop()
    {
        _spy.Reset();
        Scroller.ScrollToVerticalOffset(0);
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
        item.IsChecked = true; // raises Checked
        item.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));
    }

    private void Rail_Checked(object sender, RoutedEventArgs e) => GoToSection(sender);

    // Click as well as Checked: clicking the lit item again scrolls back to its section too.
    private void Rail_Click(object sender, RoutedEventArgs e) => GoToSection(sender);

    private void GoToSection(object sender)
    {
        if (_railSyncing || sender is not RadioButton item || !IsLoaded) return;
        var index = RailItems().IndexOf(item);
        if (index < 0 || SectionOf(item) is not { } section) return;
        _spy.Click(index);
        Scroller.ScrollToVerticalOffset(SettingsRailSpy.TargetOffset(SectionTop(section), Scroller.ScrollableHeight));
    }

    // Wheel or touch: the player is steering now, so the section in view decides again.
    private void Scroller_UserScroll(object sender, RoutedEventArgs e) => TakeOverFromClick();

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

    private void Scroller_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.OriginalSource is System.Windows.Controls.Primitives.TextBoxBase) return;
        if (IsScrollKey(e.Key)) TakeOverFromClick();
    }

    // Dragging the scrollbar thumb (the bar is hidden, but a touch pen or automation can still use it).
    private void Scroller_ScrollBarScroll(object sender, System.Windows.Controls.Primitives.ScrollEventArgs e) => TakeOverFromClick();

    private void Scroller_ScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        RailShift.Y = Scroller.VerticalOffset;
        RelightRail();
    }

    private void RelightRail()
    {
        if (!IsLoaded) return;
        var tops = RailItems().Select(item => SectionOf(item) is { } section ? SectionTop(section) : double.MaxValue).ToList();
        LightRailItem(_spy.Resolve(tops, Scroller.VerticalOffset, Scroller.ScrollableHeight));
    }
}
