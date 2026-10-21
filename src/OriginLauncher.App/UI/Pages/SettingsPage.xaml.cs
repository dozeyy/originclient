using System.Windows;
using System.Windows.Controls;
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

    public SettingsPage()
    {
        InitializeComponent();
        _settings = SettingsStore.Load();
        LauncherVersionText.Text = UpdateService.IsDevBuild
            ? "Origin Launcher - dev build"
            : $"Origin Launcher {UpdateService.CurrentVersion.ToString(3)}";

        _ramSaveTimer = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = TimeSpan.FromMilliseconds(250)
        };
        _ramSaveTimer.Tick += (_, _) => PersistPendingRam();
        Unloaded += (_, _) => PersistPendingRam();

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
        OfflineTestToggle.IsChecked = _settings.OfflineTestMode;
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

    private void InstallPathTextBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        var path = InstallPathTextBox.Text;
        _settings.InstallPath = path;
        SettingsStore.Update(s => s.InstallPath = path);
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
            _settings.InstallPath = dialog.FolderName;
            SettingsStore.Update(s => s.InstallPath = dialog.FolderName);
        }
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
        UpdateJvmStatus();
        UpdateJvmCustomizedList();
        _jvmLoading = false;
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
        UpdateJvmStatus();
    }

    private void JvmArgsTextBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (_jvmLoading) return;
        SaveJvmOptions();
        UpdateJvmCustomizedList();
    }

    private void JvmReplacePresetsToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_jvmLoading) return;
        SaveJvmOptions();
        UpdateJvmStatus();
        UpdateJvmCustomizedList();
    }

    private void JvmReplacePresetsToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_jvmLoading) return;
        SaveJvmOptions();
        UpdateJvmStatus();
        UpdateJvmCustomizedList();
    }

    // Writes the editor's current contents to _jvmVersion (the version the editor
    // is pointed at), NOT to the dropdown's current selection — those differ for
    // exactly one call, when the selection has already moved.
    private void SaveJvmOptions()
    {
        var version = _jvmVersion;
        if (version == null) return;

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
            _settings.JvmArgsByVersion[version] = opts;
            SettingsStore.Update(s => s.JvmArgsByVersion[version] =
                new VersionJvmOptions { Arguments = text, ReplacePresets = replace });
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

        if (!int.TryParse(ResolutionWidthTextBox.Text, out var width) || width < 320)
        {
            ResolutionWidthTextBox.Text = _settings.ResolutionWidth.ToString();
            return;
        }
        if (!int.TryParse(ResolutionHeightTextBox.Text, out var height) || height < 240)
        {
            ResolutionHeightTextBox.Text = _settings.ResolutionHeight.ToString();
            return;
        }

        _settings.ResolutionWidth = width;
        _settings.ResolutionHeight = height;
        SettingsStore.Update(s => { s.ResolutionWidth = width; s.ResolutionHeight = height; });
    }

    // ---- Section rail (Poster) ----------------------------------------------
    // The rail lives inside the scrolled column (so it lines up with it at every
    // width) and is shifted by the scroll offset so it stays put on screen. A
    // click jumps to its section; scrolling marks the section under the top edge.
    private bool _railSyncing;

    private FrameworkElement? SectionOf(RadioButton item) =>
        item.Tag is string name ? FindName(name) as FrameworkElement : null;

    private double SectionTop(FrameworkElement section) =>
        section.TranslatePoint(new Point(0, 0), Column).Y;

    private void Rail_Checked(object sender, RoutedEventArgs e)
    {
        if (_railSyncing || sender is not RadioButton item || !IsLoaded) return;
        if (SectionOf(item) is { } section)
            Scroller.ScrollToVerticalOffset(Math.Max(0, SectionTop(section) - 24));
    }

    private void Scroller_ScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        RailShift.Y = Scroller.VerticalOffset;

        RadioButton? current = null;
        var atBottom = Scroller.ScrollableHeight > 0 && Scroller.VerticalOffset >= Scroller.ScrollableHeight - 1;
        foreach (var child in Rail.Children)
        {
            if (child is not RadioButton item || SectionOf(item) is not { } section) continue;
            if (atBottom || SectionTop(section) <= Scroller.VerticalOffset + 60) current = item;
        }
        if (current is null || current.IsChecked == true) return;
        _railSyncing = true;
        current.IsChecked = true;
        _railSyncing = false;
    }
}
