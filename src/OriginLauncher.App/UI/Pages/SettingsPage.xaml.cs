using System.Net.Http;
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
        SettingsStore.Save(_settings);
    }

    private void VersionComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_isLoading) return;
        _settings.SelectedVersion = VersionComboBox.SelectedItem as string;
        SettingsStore.Save(_settings);
    }

    private void InstallPathTextBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.InstallPath = InstallPathTextBox.Text;
        SettingsStore.Save(_settings);
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
            SettingsStore.Save(_settings);
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
        SettingsStore.Save(_settings);
    }

    private void ShaderCacheNvidiaToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShaderCacheNvidia = false;
        SettingsStore.Save(_settings);
    }

    private void ShaderCacheAmdToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShaderCacheAmd = true;
        SettingsStore.Save(_settings);
    }

    private void ShaderCacheAmdToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.ShaderCacheAmd = false;
        SettingsStore.Save(_settings);
    }

    private void OfflineTestToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.OfflineTestMode = true;
        SettingsStore.Save(_settings);
    }

    private void OfflineTestToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_isLoading) return;
        _settings.OfflineTestMode = false;
        SettingsStore.Save(_settings);
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
        SettingsStore.Save(_settings);
    }
}
