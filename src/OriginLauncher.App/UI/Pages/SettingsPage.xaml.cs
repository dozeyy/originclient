using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using Microsoft.Win32;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
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

        _ramSaveTimer = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = TimeSpan.FromMilliseconds(250)
        };
        _ramSaveTimer.Tick += (_, _) => PersistPendingRam();
        Unloaded += (_, _) => PersistPendingRam();

        var safeMaximum = Math.Max(2048, (SystemInfo.GetTotalPhysicalMemoryMb() - 2048) / 256 * 256);
        var ramMb = Math.Clamp((int)Math.Round(_settings.RamMb / 256d) * 256, 1024, safeMaximum);
        RamSlider.Maximum = safeMaximum;
        RamSlider.Value = ramMb;
        RamValueText.Text = FormatRam(ramMb);
        if (ramMb != _settings.RamMb)
        {
            _settings.RamMb = ramMb;
            SettingsStore.Update(s => s.RamMb = ramMb);
        }
        InstallPathTextBox.Text = _settings.InstallPath;
        ResolutionWidthTextBox.Text = _settings.ResolutionWidth.ToString();
        ResolutionHeightTextBox.Text = _settings.ResolutionHeight.ToString();

        OriginUiToggle.IsChecked = OriginClientConfigBridge.IsOriginUiEnabled();
        ShaderCacheToggle.IsChecked = _settings.ShaderCacheNvidia || _settings.ShaderCacheAmd;
        OfflineTestToggle.IsChecked = _settings.OfflineTestMode;

        InitializeJvmSection();

        _isLoading = false;
    }

    private void RamSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        var ramMb = (int)e.NewValue;
        RamValueText.Text = FormatRam(ramMb);
        if (_isLoading) return;
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
        SettingsStore.Update(s => s.RamMb = ramMb);
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
                : $"{count} · defaults off"
            : tokens.Count == 0
                ? "Using Origin defaults"
                : $"{count} · Origin defaults on";
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
}
