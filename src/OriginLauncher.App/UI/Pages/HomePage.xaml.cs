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

    // The version the player currently has selected, sourced live from the
    // dropdown rather than settings.json — the default selection is never
    // persisted (VersionComboBox_SelectionChanged early-returns while
    // _isLoading), so disk would read null on first run while Home visibly
    // shows a version. Null when no real version is selectable (load failed).
    public string? CurrentVersion =>
        VersionComboBox.IsEnabled ? VersionComboBox.SelectedItem as string : null;

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

    // Fabric only where the modern perf/shader stack exists. The classics
    // (1.8.9, 1.12.2) are Forge now: Legacy Fabric was dropped there because it
    // can't run shaders (no Iris/Sodium pre-1.16), so Forge + OptiFine — the
    // only shader path on those versions — is the recommendation instead.
    private LoaderKind RecommendedLoader(string version) =>
        PerformanceModCatalog.RecommendsFabric(version)
            ? LoaderKind.Fabric
            : LoaderKind.Forge;

    private void UpdateLoaderControls()
    {
        if (VersionComboBox.SelectedItem is not string version) return;
        var modernFabric = PerformanceModCatalog.RecommendsFabric(version);
        var loader = _settings.SelectedLoader ?? RecommendedLoader(version);
        // Fabric isn't offered on the classics anymore — if an old saved choice
        // still selects it there, fall back to the recommended Forge instead of
        // leaving a hidden-but-checked toggle.
        if (loader == LoaderKind.Fabric && !modernFabric)
            loader = RecommendedLoader(version);

        _settingLoaderProgrammatically = true;
        // Modern versions: Vanilla + Fabric. Classics (1.8.9, 1.12.2) and any
        // modern version without a perf-catalog entry: Vanilla + Forge(+OptiFine)
        // — Fabric is hidden because it brings nothing shaders/perf there.
        LoaderFabricToggle.Visibility = modernFabric ? Visibility.Visible : Visibility.Collapsed;
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

            // Hint hybrid-GPU laptops onto the discrete GPU. Always applied now
            // that the Graphics/Performance launch-mode toggle is gone — the
            // high-performance GPU is simply the right default for a game.
            GpuPreference.PreferHighPerformanceGpu(process.StartInfo.FileName);

            // Quality-neutral shader-stutter reducer: keep the driver's compiled
            // shader cache so Iris packs don't recompile (and hitch) each launch.
            ShaderCache.Apply(process.StartInfo, _settings.ShaderCacheNvidia, _settings.ShaderCacheAmd);

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
            var upToDateMessage = ex.Stage == "token_refresh"
                ? "Your session expired — remove and re-add this account in the account switcher."
                : ex.Message;
            StatusText.Text = await LaunchFailureTextAsync(ex, upToDateMessage);
            System.Diagnostics.Debug.WriteLine($"[HomePage] Launch failed (auth): {ex}");
            WriteLaunchErrorLog(ex);
        }
        catch (Exception ex)
        {
            StatusText.Text = await LaunchFailureTextAsync(ex, $"Launch failed: {DescribeError(ex)}");
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

    // Update-aware failure text. A launcher older than the latest release is
    // almost always failing on a bug that's already fixed upstream — that's
    // exactly how the login_with_xbox / SSL auth failure got resolved: by
    // updating. So on any launch failure, re-check the feed (the pre-launch
    // gate fails open on a transient, so an out-of-date build can slip through
    // to here) and, when this build is genuinely out of date, tell the user to
    // update instead of showing a raw error they can't act on. Dev builds and
    // up-to-date builds fall through to the real message + error code so it can
    // actually be reported. CheckAsync never throws (it fails open internally).
    private static async Task<string> LaunchFailureTextAsync(Exception ex, string upToDateMessage)
    {
        await UpdateService.CheckAsync();
        return UpdateService.UpdateRequired
            ? "Launch failed — please update the launcher (click the update dot in the top-right). This is very likely already fixed in the newest version."
            : upToDateMessage;
    }

    // A concise, reportable error string for an up-to-date build: the top-level
    // message plus the innermost cause (for wrapped failures like a TLS reset,
    // that inner message is the part that matters), tagged with a short code the
    // user can quote. Full detail still goes to the launcher_error log.
    private static string DescribeError(Exception ex)
    {
        var inner = ex;
        while (inner.InnerException != null) inner = inner.InnerException;

        var code = ex is System.Net.Http.HttpRequestException http
                   && http.HttpRequestError != System.Net.Http.HttpRequestError.Unknown
            ? http.HttpRequestError.ToString()
            : inner.GetType().Name;

        var detail = ReferenceEquals(inner, ex) || inner.Message == ex.Message
            ? ex.Message
            : $"{ex.Message} — {inner.Message}";

        return $"{detail} [{code}]";
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

        StreamWriter? logWriter = null;
        try { logWriter = new StreamWriter(logPath, append: false) { AutoFlush = true }; }
        catch { /* logging is best-effort — a failed log file must never block a launch */ }

        // Hard rule: the launched game is fully independent of the launcher.
        // Nothing the child process does — exiting, crashing, or a late burst of
        // stdout/stderr as it closes — may EVER take the launcher down. This used
        // to be the "launcher closes when the game closes, and Play won't work
        // again" bug: the Exited handler disposed logWriter, but stdout/stderr
        // callbacks (which run on their own threads and can fire AFTER Exited
        // while buffered output drains) then wrote to the disposed writer, throwing
        // ObjectDisposedException on a ThreadPool thread — and with no global
        // handler (App.xaml.cs), an unhandled background-thread exception kills the
        // whole process. stdout and stderr also shared one non-thread-safe writer.
        //
        // Fix: serialize every write, dispose the writer exactly ONCE and only
        // after BOTH streams have signalled end-of-stream (null Data) so no write
        // can race the dispose, and wrap every callback so an exception can never
        // escape onto a background thread.
        var gate = new object();
        var openStreams = 2;

        void OnData(string? data)
        {
            try
            {
                if (data == null)
                {
                    // stdout or stderr reached EOF; dispose once both have.
                    if (Interlocked.Decrement(ref openStreams) == 0)
                        lock (gate) { logWriter?.Dispose(); logWriter = null; }
                    return;
                }
                lock (gate) { logWriter?.WriteLine(data); }
            }
            catch { /* best-effort logging; never surface onto this thread */ }
        }

        process.StartInfo.RedirectStandardOutput = true;
        process.StartInfo.RedirectStandardError = true;
        process.StartInfo.UseShellExecute = false;
        process.EnableRaisingEvents = true;

        process.OutputDataReceived += (_, args) => OnData(args.Data);
        process.ErrorDataReceived += (_, args) => OnData(args.Data);
        process.Exited += (_, _) =>
        {
            // Runs on a ThreadPool thread — must be exception-proof end to end.
            try
            {
                int exitCode;
                try { exitCode = process.ExitCode; } catch { exitCode = -1; }

                Dispatcher.BeginInvoke(() =>
                {
                    // A newer launch may already be in flight (Play clicked again
                    // while this instance was running); don't clobber its status.
                    if (_isLaunching) return;
                    StatusText.Text = exitCode == 0
                        ? "Minecraft closed — click Play to launch again."
                        : $"Minecraft exited with code {exitCode} — log saved to {logPath}";
                });
            }
            catch { /* the game's exit must never crash the launcher */ }
        };

        try
        {
            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
        }
        catch
        {
            // Start failed — release the log handle and let PlayButton_Click's
            // catch report it, rather than leaking the writer.
            lock (gate) { logWriter?.Dispose(); logWriter = null; }
            throw;
        }
    }
}
