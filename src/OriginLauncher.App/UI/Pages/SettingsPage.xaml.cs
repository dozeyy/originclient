using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Pages;

public partial class SettingsPage : UserControl
{
    private readonly LauncherSettings _settings;
    private readonly VersionManager _versionManager = new();
    private bool _isLoading = true;

    // The JVM section has its own guard rather than sharing _isLoading: that flag
    // is flipped back to true asynchronously when the Mojang version list lands
    // (LoadVersionsAsync), and an edit made during that window would be silently
    // dropped. This section is populated from the local VersionCatalog, so it is
    // interactive immediately and must not be gated on a network fetch.
    private bool _jvmLoading = true;
    private string? _jvmVersion;

    public SettingsPage()
    {
        InitializeComponent();
        _settings = SettingsStore.Load();

        RamSlider.Maximum = Math.Max(SystemInfo.GetTotalPhysicalMemoryMb(), _settings.RamMb);
        RamSlider.Value = _settings.RamMb;
        RamValueText.Text = $"{_settings.RamMb} MB";
        InstallPathTextBox.Text = _settings.InstallPath;
        ResolutionWidthTextBox.Text = _settings.ResolutionWidth.ToString();
        ResolutionHeightTextBox.Text = _settings.ResolutionHeight.ToString();

        OriginUiToggle.IsChecked = OriginClientConfigBridge.IsOriginUiEnabled();
        ShaderCacheNvidiaToggle.IsChecked = _settings.ShaderCacheNvidia;
        ShaderCacheAmdToggle.IsChecked = _settings.ShaderCacheAmd;
        OfflineTestToggle.IsChecked = _settings.OfflineTestMode;

        InitializeJvmSection();

        _isLoading = false;
        _ = LoadVersionsAsync();
    }

    private async Task LoadVersionsAsync()
    {
        try
        {
            var versions = await _versionManager.GetReleaseVersionsAsync();
            _isLoading = true;
            if (versions.Count == 0)
            {
                ShowVersionLoadFailure();
                return;
            }

            VersionComboBox.ItemsSource = versions;
            VersionComboBox.SelectedItem = _settings.SelectedVersion ?? versions.FirstOrDefault();
            _isLoading = false;
        }
        catch (Exception ex)
        {
            // Broad on purpose: a narrower catch (e.g. HttpRequestException only)
            // silently swallows timeouts/DNS failures too, leaving the dropdown
            // blank with no indication why. Always show *something* instead.
            System.Diagnostics.Debug.WriteLine($"[SettingsPage] Version load failed: {ex}");
            ShowVersionLoadFailure();
        }
    }

    private void ShowVersionLoadFailure()
    {
        VersionComboBox.ItemsSource = new[] { "No versions found — check your connection" };
        VersionComboBox.SelectedIndex = 0;
        VersionComboBox.IsEnabled = false;
        _isLoading = false;
    }

    private void RamSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        var ramMb = (int)e.NewValue;
        RamValueText.Text = $"{ramMb} MB";
        if (_isLoading) return;
        _settings.RamMb = ramMb;
        SettingsStore.Update(s => s.RamMb = ramMb);
    }

    private void VersionComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_isLoading) return;
        var version = VersionComboBox.SelectedItem as string;
        _settings.SelectedVersion = version;
        SettingsStore.Update(s => s.SelectedVersion = version);
    }

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

    private void ShaderCacheNvidiaToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShaderCacheNvidia = true;
        SettingsStore.Update(s => s.ShaderCacheNvidia = true);
    }

    private void ShaderCacheNvidiaToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShaderCacheNvidia = false;
        SettingsStore.Update(s => s.ShaderCacheNvidia = false);
    }

    private void ShaderCacheAmdToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShaderCacheAmd = true;
        SettingsStore.Update(s => s.ShaderCacheAmd = true);
    }

    private void ShaderCacheAmdToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShaderCacheAmd = false;
        SettingsStore.Update(s => s.ShaderCacheAmd = false);
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

        var warning = JvmArgLine.Warn(tokens);
        if (warning != null)
        {
            JvmArgsStatusText.Text = warning;
            return;
        }

        var count = $"{tokens.Count} argument{(tokens.Count == 1 ? "" : "s")}";
        JvmArgsStatusText.Text = replace
            ? tokens.Count == 0
                ? "No arguments — this version will launch with no JVM flags beyond memory and resolution."
                : $"{count} — Origin's tuned defaults are off for this version."
            : tokens.Count == 0
                ? "Using Origin's tuned defaults."
                : $"{count} on top of Origin's tuned defaults.";
    }

    private void UpdateJvmCustomizedList()
    {
        var customized = VersionCatalog.SupportedVersions
            .Where(v => _settings.JvmArgsFor(v) != null)
            .ToList();

        JvmCustomizedVersionsText.Text = customized.Count == 0
            ? "No versions have custom arguments."
            : $"Customized: {string.Join(", ", customized)}";
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
