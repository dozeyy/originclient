using System.IO;
using System.Windows;
using System.Windows.Controls;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Pages;

public partial class HomePage : UserControl
{
    private readonly LauncherSettings _settings;
    private readonly VersionManager _versionManager = new();
    private bool _isLoading = true;
    private bool _isLaunching;
    private StoredAccount? _selectedAccount;

    public HomePage()
    {
        InitializeComponent();
        _settings = SettingsStore.Load();
        RefreshAccountState();
        _ = LoadVersionsAsync();
    }

    // Called by MainWindow whenever the account switcher panel adds or
    // selects an account, so Home reflects it without needing to navigate away and back.
    public void RefreshAccountState()
    {
        _selectedAccount = AccountStore.GetSelected(AccountStore.Load());
        UpdatePlayState();
    }

    private void UpdatePlayState()
    {
        var hasAccount = _selectedAccount != null;
        var hasVersion = VersionComboBox.SelectedItem is string;

        PlayButton.IsEnabled = hasAccount && hasVersion && !_isLaunching;
        PlayButton.ToolTip = _isLaunching
            ? null
            : hasAccount
                ? (hasVersion ? null : "Select a version to play")
                : "Sign in to play";

        if (!_isLaunching)
        {
            StatusText.Text = hasAccount
                ? $"Signed in as {_selectedAccount!.Gamertag}"
                : "No account signed in";
        }
    }

    private async Task LoadVersionsAsync()
    {
        try
        {
            var versions = await _versionManager.GetReleaseVersionsAsync();
            if (versions.Count == 0)
            {
                ShowVersionLoadFailure();
                return;
            }

            VersionComboBox.ItemsSource = versions;
            VersionComboBox.SelectedItem = _settings.SelectedVersion ?? versions.FirstOrDefault();
        }
        catch (Exception ex)
        {
            // Broad on purpose: a narrower catch (e.g. HttpRequestException only)
            // silently swallows timeouts/DNS failures too, leaving the dropdown
            // blank with no indication why. Always show *something* instead.
            System.Diagnostics.Debug.WriteLine($"[HomePage] Version load failed: {ex}");
            ShowVersionLoadFailure();
        }
        finally
        {
            _isLoading = false;
            UpdatePlayState();
        }
    }

    private void ShowVersionLoadFailure()
    {
        VersionComboBox.ItemsSource = new[] { "No versions found — check your connection" };
        VersionComboBox.SelectedIndex = 0;
        VersionComboBox.IsEnabled = false;
    }

    private void VersionComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_isLoading) return;
        _settings.SelectedVersion = VersionComboBox.SelectedItem as string;
        SettingsStore.Save(_settings);
        UpdatePlayState();
    }

    private async void PlayButton_Click(object sender, RoutedEventArgs e)
    {
        if (_isLaunching || _selectedAccount == null) return;
        if (VersionComboBox.SelectedItem is not string version) return;

        _isLaunching = true;
        PlayButton.IsEnabled = false;
        StatusText.Text = "Signing in...";

        try
        {
            var refreshToken = AccountStore.TryUnprotectRefreshToken(_selectedAccount.ProtectedRefreshToken);
            if (refreshToken == null)
            {
                StatusText.Text = "Session expired — remove and re-add this account in the account switcher.";
                return;
            }

            var result = await new MicrosoftAuthenticator().SignInSilentlyAsync(refreshToken);

            // Microsoft rotates refresh tokens on use — persist the new one
            // immediately, and bump last-used, so the account list stays accurate.
            var accounts = AccountStore.Load();
            var stored = accounts.FirstOrDefault(a => a.Id == _selectedAccount.Id);
            if (stored != null)
            {
                stored.ProtectedRefreshToken = AccountStore.ProtectRefreshToken(result.MsaRefreshToken);
                stored.LastUsedUtc = DateTimeOffset.UtcNow;
                AccountStore.Save(accounts);
            }

            StatusText.Text = "Launching Minecraft...";
            var launchOption = LaunchProfileBuilder.Build(_settings, result.Session);
            var process = await _versionManager.InstallAndBuildProcessAsync(version, launchOption);

            if (_settings.PerformanceMode == PerformanceMode.Performance)
            {
                GpuPreference.PreferHighPerformanceGpu(process.StartInfo.FileName);
            }

            StartWithLifecycleCapture(process, version);

            StatusText.Text = $"Launched {version} — signed in as {result.Session.Username}";
        }
        catch (MicrosoftAuthException ex)
        {
            StatusText.Text = ex.Stage == "token_refresh"
                ? "Your session expired — remove and re-add this account in the account switcher."
                : ex.Message;
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Launch failed: {ex.Message}";
        }
        finally
        {
            _isLaunching = false;
            UpdatePlayState();
        }
    }

    // Redirects the launched instance's stdout/stderr to a per-launch log file
    // and reports the exit code back to Home once the game closes — this is
    // the crash-diagnostics foundation Phase 3 builds on, not full crash
    // parsing yet.
    private void StartWithLifecycleCapture(System.Diagnostics.Process process, string version)
    {
        OriginPaths.EnsureScaffold();
        var logPath = Path.Combine(OriginPaths.Logs, $"{version}_{DateTime.Now:yyyyMMdd_HHmmss}.log");
        var logWriter = new StreamWriter(logPath, append: false) { AutoFlush = true };

        process.StartInfo.RedirectStandardOutput = true;
        process.StartInfo.RedirectStandardError = true;
        process.StartInfo.UseShellExecute = false;
        process.EnableRaisingEvents = true;

        process.OutputDataReceived += (_, args) => { if (args.Data != null) logWriter.WriteLine(args.Data); };
        process.ErrorDataReceived += (_, args) => { if (args.Data != null) logWriter.WriteLine(args.Data); };
        process.Exited += (_, _) =>
        {
            var exitCode = process.ExitCode;
            logWriter.Dispose();
            Dispatcher.Invoke(() =>
            {
                StatusText.Text = exitCode == 0
                    ? "Minecraft closed normally"
                    : $"Minecraft exited with code {exitCode} — log saved to {logPath}";
            });
        };

        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
    }
}
