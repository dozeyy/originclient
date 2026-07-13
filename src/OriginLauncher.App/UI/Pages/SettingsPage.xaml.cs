using System.Windows;
using System.Windows.Controls;
using Microsoft.Win32;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Pages;

public partial class SettingsPage : UserControl
{
    private readonly LauncherSettings _settings;
    private readonly VersionManager _versionManager = new();
    private bool _isLoading = true;

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
