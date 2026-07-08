using System.IO;
using System.Windows;
using System.Windows.Controls;
using CmlLib.Core.Auth;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Updates;
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

    // In-flight launch provisioning. The newest launch action always wins
    // (CLAUDE.md hard requirement): the overlay's Cancel aborts this token,
    // and starting a new launch replaces it.
    private CancellationTokenSource? _launchCts;

    public HomePage()
    {
        InitializeComponent();
        _settings = SettingsStore.Load();
        LoadingOverlay.CancelRequested += (_, _) => _launchCts?.Cancel();
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

    /// <summary>Called by MainWindow when the update badge lights up, so the
    /// status line explains why Play is about to refuse.</summary>
    public void RefreshUpdateGate()
    {
        if (UpdateService.UpdateRequired && !_isLaunching)
        {
            StatusText.Text = "Update available — the launcher must update before playing.";
        }
    }

    private void UpdatePlayState()
    {
        var hasAccount = _selectedAccount != null;
        var hasVersion = VersionComboBox.SelectedItem is string;
        // Read fresh (not the cached _settings): the toggle lives on the
        // Settings page, which persists to disk; a page switch must pick the
        // change up without app-restart plumbing.
        var offlineTest = SettingsStore.Load().OfflineTestMode;
        var canPlay = hasAccount || offlineTest;

        PlayButton.IsEnabled = canPlay && hasVersion && !_isLaunching;
        PlayButton.ToolTip = _isLaunching
            ? null
            : canPlay
                ? (hasVersion ? null : "Select a version to play")
                : "Sign in to play";

        if (!_isLaunching)
        {
            StatusText.Text = hasAccount
                ? $"Signed in as {_selectedAccount!.Gamertag}"
                : offlineTest
                    ? "Offline test mode — launching without an account"
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
        var offlineTest = SettingsStore.Load().OfflineTestMode;
        if (_isLaunching || (_selectedAccount == null && !offlineTest)) return;
        if (VersionComboBox.SelectedItem is not string version) return;

        // Mandatory updates (Will's rule): a launcher older than the latest
        // published release must update before it can launch the game. The
        // feed is re-checked right here so a just-pushed release blocks
        // immediately rather than after the next poll; an unreachable feed
        // fails open (see UpdateService's policy note).
        await UpdateService.CheckAsync();
        if (UpdateService.UpdateRequired)
        {
            StatusText.Text = "Update required — click the update dot in the top-right corner.";
            return;
        }

        // Newest launch action wins: replace (and cancel) any stale token
        // before starting. The overlay's Cancel button aborts this one.
        _launchCts?.Cancel();
        var cts = new CancellationTokenSource();
        _launchCts = cts;

        _isLaunching = true;
        PlayButton.IsEnabled = false;
        var loader = _settings.SelectedLoader ?? RecommendedLoader(version);
        LoadingOverlay.Show(version, LoaderCaption(loader));

        try
        {
            MSession session;
            if (_selectedAccount != null)
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
                session = result.Session;
            }
            else
            {
                // Offline test mode (Settings -> Developer): Microsoft's
                // app-registration approval is pending, so real sign-in is
                // unavailable — a local session lets every other part of the
                // pipeline (provisioning, loaders, the in-game UI) be tested.
                LoadingOverlay.ReportStage("Starting offline test session...");
                session = MSession.CreateOfflineSession("OriginTester");
            }

            cts.Token.ThrowIfCancellationRequested();

            var launchOption = LaunchProfileBuilder.Build(_settings, session);
            var installProgress = new Progress<string>(LoadingOverlay.ReportStage);
            var process = await _versionManager.InstallAndBuildProcessAsync(
                version, loader, _settings.OptiFineEnabled, launchOption, installProgress, cts.Token);

            if (_settings.PerformanceMode == PerformanceMode.Performance)
            {
                GpuPreference.PreferHighPerformanceGpu(process.StartInfo.FileName);
            }

            // Last gate before the game actually starts — a cancel that
            // landed after provisioning finished must not still launch.
            cts.Token.ThrowIfCancellationRequested();

            LoadingOverlay.ReportStage("Launching Minecraft...");
            StartWithLifecycleCapture(process, version);

            StatusText.Text = _selectedAccount != null
                ? $"Launched {version} — signed in as {session.Username}"
                : $"Launched {version} — offline test session";
        }
        catch (OperationCanceledException)
        {
            // Player hit Cancel on the overlay (or a newer launch superseded
            // this one) — not an error, just back to the Home state.
            StatusText.Text = "Launch cancelled";
        }
        catch (MicrosoftAuthException ex)
        {
            StatusText.Text = ex.Stage == "token_refresh"
                ? "Your session expired — remove and re-add this account in the account switcher."
                : ex.Message;
            System.Diagnostics.Debug.WriteLine($"[HomePage] Launch failed (auth): {ex}");
            WriteLaunchErrorLog(ex);
        }
        catch (Exception ex)
        {
            StatusText.Text = $"Launch failed: {ex.Message}";
            System.Diagnostics.Debug.WriteLine($"[HomePage] Launch failed: {ex}");
            WriteLaunchErrorLog(ex);
        }
        finally
        {
            // Only the launch that still owns the token resets the shared UI —
            // if a newer launch has already replaced it, that one drives.
            if (ReferenceEquals(_launchCts, cts))
            {
                _isLaunching = false;
                LoadingOverlay.Hide();
                // Not the full UpdatePlayState() — that unconditionally overwrites
                // StatusText with "Signed in as X", clobbering whatever specific
                // message the try/catch above just set. Only re-sync IsEnabled here.
                PlayButton.IsEnabled =
                    (_selectedAccount != null || SettingsStore.Load().OfflineTestMode)
                    && VersionComboBox.SelectedItem is string;
            }
            cts.Dispose();
        }
    }

    // Persists the full exception under /logs/ for diagnostics. Must never
    // throw itself — an error writer that can crash the catch block would
    // turn a failed launch into a crashed launcher (the previous hardcoded
    // debug path did exactly that on any machine but the dev box).
    private static void WriteLaunchErrorLog(Exception ex)
    {
        try
        {
            OriginPaths.EnsureScaffold();
            File.WriteAllText(
                Path.Combine(OriginPaths.Logs, $"launcher_error_{DateTime.Now:yyyyMMdd_HHmmss}.log"),
                ex.ToString());
        }
        catch
        {
            // Best-effort only.
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
