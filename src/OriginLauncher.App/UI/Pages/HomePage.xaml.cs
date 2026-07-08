using System.IO;
using System.Windows;
using System.Windows.Controls;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Pages;

public partial class HomePage : UserControl
{
    private readonly LauncherSettings _settings;
    private readonly VersionManager _versionManager = new();
    private bool _isLoading = true;
    private bool _isLaunching;
    private bool _settingLoaderProgrammatically;
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
            UpdateLoaderControls();
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
        // IsEnabled guard too, not just _isLoading: ShowVersionLoadFailure's
        // placeholder text is technically a string, and _isLoading is still
        // true when it's assigned — but that's timing-fragile, so also
        // refuse to ever persist the placeholder as a "real" selected version.
        if (_isLoading || !VersionComboBox.IsEnabled) return;
        _settings.SelectedVersion = VersionComboBox.SelectedItem as string;
        // Version changed — go back to the auto-recommended loader for it
        // rather than carrying over a choice that made sense for the old one.
        _settings.SelectedLoader = null;
        SettingsStore.Save(_settings);
        UpdateLoaderControls();
        UpdatePlayState();
    }

    // Fabric wherever a Fabric-family loader exists for the version: the
    // modern perf-catalog versions, and the pre-1.14 range via Legacy Fabric
    // (Origin's own loader path — see VERSIONS.md). Forge is recommended only
    // in the gap where neither applies.
    private LoaderKind RecommendedLoader(string version) =>
        PerformanceModCatalog.RecommendsFabric(version) || LegacyFabricInstaller.Supports(version)
            ? LoaderKind.Fabric
            : LoaderKind.Forge;

    private void UpdateLoaderControls()
    {
        if (VersionComboBox.SelectedItem is not string version) return;
        var modernFabric = PerformanceModCatalog.RecommendsFabric(version);
        var legacyFabric = LegacyFabricInstaller.Supports(version);
        var loader = _settings.SelectedLoader ?? RecommendedLoader(version);

        _settingLoaderProgrammatically = true;
        // Modern versions: Vanilla + Fabric (as before). Legacy versions
        // (1.8.9, 1.12.2): Vanilla + Fabric + Forge — Legacy Fabric is the
        // recommended Origin path, but the existing Forge(+OptiFine) option
        // stays one click away. Modern versions without a perf-catalog entry
        // (e.g. 1.18.0) keep Vanilla + Forge.
        LoaderFabricToggle.Visibility = modernFabric || legacyFabric ? Visibility.Visible : Visibility.Collapsed;
        LoaderForgeToggle.Visibility = modernFabric ? Visibility.Collapsed : Visibility.Visible;

        LoaderVanillaToggle.IsChecked = loader == LoaderKind.Vanilla;
        LoaderFabricToggle.IsChecked = loader == LoaderKind.Fabric;
        LoaderForgeToggle.IsChecked = loader == LoaderKind.Forge;

        OptiFineRow.Visibility = loader == LoaderKind.Forge ? Visibility.Visible : Visibility.Collapsed;
        OptiFineToggle.IsChecked = loader == LoaderKind.Forge
            && _settings.OptiFineEnabled
            && OptiFineCacheStore.IsCached(version);
        _settingLoaderProgrammatically = false;
    }

    private void SetLoader(LoaderKind loader)
    {
        if (_settingLoaderProgrammatically) return;

        _settingLoaderProgrammatically = true;
        LoaderVanillaToggle.IsChecked = loader == LoaderKind.Vanilla;
        LoaderFabricToggle.IsChecked = loader == LoaderKind.Fabric;
        LoaderForgeToggle.IsChecked = loader == LoaderKind.Forge;
        _settingLoaderProgrammatically = false;

        _settings.SelectedLoader = loader;
        SettingsStore.Save(_settings);

        var version = VersionComboBox.SelectedItem as string;
        OptiFineRow.Visibility = loader == LoaderKind.Forge ? Visibility.Visible : Visibility.Collapsed;
        OptiFineToggle.IsChecked = loader == LoaderKind.Forge
            && version != null
            && _settings.OptiFineEnabled
            && OptiFineCacheStore.IsCached(version);
    }

    private void LoaderVanillaToggle_Checked(object sender, RoutedEventArgs e) => SetLoader(LoaderKind.Vanilla);
    private void LoaderFabricToggle_Checked(object sender, RoutedEventArgs e) => SetLoader(LoaderKind.Fabric);
    private void LoaderForgeToggle_Checked(object sender, RoutedEventArgs e) => SetLoader(LoaderKind.Forge);

    // Auto-downloads OptiFine straight into the instance's cache (BMCLAPI
    // mirror — see OptiFineCatalog) instead of asking the player to locate
    // a jar themselves. Reverts the toggle on any failure so it never gets
    // stuck claiming OptiFine is on when it isn't actually cached.
    private async void OptiFineToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_settingLoaderProgrammatically) return;
        if (VersionComboBox.SelectedItem is not string version) return;

        if (!OptiFineCacheStore.IsCached(version))
        {
            OptiFineToggle.IsEnabled = false;
            StatusText.Text = "Downloading OptiFine...";

            try
            {
                var entry = await OptiFineCatalog.TryFindFor(version);
                if (entry == null)
                {
                    StatusText.Text = $"No OptiFine build found for {version}.";
                    OptiFineToggle.IsChecked = false;
                    return;
                }

                await OptiFineCatalog.DownloadAsync(entry, OptiFineCacheStore.JarPathFor(version));
            }
            catch (Exception ex)
            {
                StatusText.Text = $"OptiFine download failed: {ex.Message}";
                OptiFineToggle.IsChecked = false;
                return;
            }
            finally
            {
                OptiFineToggle.IsEnabled = true;
            }

            UpdatePlayState();
        }

        _settings.OptiFineEnabled = true;
        SettingsStore.Save(_settings);
    }

    private void OptiFineToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_settingLoaderProgrammatically) return;
        _settings.OptiFineEnabled = false;
        SettingsStore.Save(_settings);
    }

    private async void PlayButton_Click(object sender, RoutedEventArgs e)
    {
        if (_isLaunching || _selectedAccount == null) return;
        if (VersionComboBox.SelectedItem is not string version) return;

        _isLaunching = true;
        PlayButton.IsEnabled = false;
        var loader = _settings.SelectedLoader ?? RecommendedLoader(version);
        LoadingOverlay.Show(version, LoaderCaption(loader));

        try
        {
            LoadingOverlay.ReportStage("Signing in...");
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

            var launchOption = LaunchProfileBuilder.Build(_settings, result.Session);
            var installProgress = new Progress<string>(LoadingOverlay.ReportStage);
            var process = await _versionManager.InstallAndBuildProcessAsync(
                version, loader, _settings.OptiFineEnabled, launchOption, installProgress);

            if (_settings.PerformanceMode == PerformanceMode.Performance)
            {
                GpuPreference.PreferHighPerformanceGpu(process.StartInfo.FileName);
            }

            LoadingOverlay.ReportStage("Launching Minecraft...");
            StartWithLifecycleCapture(process, version);

            StatusText.Text = $"Launched {version} — signed in as {result.Session.Username}";
        }
        catch (MicrosoftAuthException ex)
        {
            StatusText.Text = ex.Stage == "token_refresh"
                ? "Your session expired — remove and re-add this account in the account switcher."
                : ex.Message;
            System.Diagnostics.Debug.WriteLine($"[HomePage] Launch failed (auth): {ex}");
            File.WriteAllText(@"C:\Users\Will\AppData\Local\Temp\claude\C--Users-Will-Documents-Origin-Client\d4c38423-9727-4f18-805d-e8d301b2fb83\scratchpad\launch_error.txt", ex.ToString());
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Launch failed: {ex.Message}";
            System.Diagnostics.Debug.WriteLine($"[HomePage] Launch failed: {ex}");
            File.WriteAllText(@"C:\Users\Will\AppData\Local\Temp\claude\C--Users-Will-Documents-Origin-Client\d4c38423-9727-4f18-805d-e8d301b2fb83\scratchpad\launch_error.txt", ex.ToString());
        }
        finally
        {
            _isLaunching = false;
            LoadingOverlay.Hide();
            // Not the full UpdatePlayState() — that unconditionally overwrites
            // StatusText with "Signed in as X", clobbering whatever specific
            // message the try/catch above just set. Only re-sync IsEnabled here.
            PlayButton.IsEnabled = _selectedAccount != null && VersionComboBox.SelectedItem is string;
        }
    }

    private static string LoaderCaption(LoaderKind loader) => loader switch
    {
        LoaderKind.Fabric => "Fabric",
        LoaderKind.Forge => "Forge",
        _ => "Vanilla"
    };

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
