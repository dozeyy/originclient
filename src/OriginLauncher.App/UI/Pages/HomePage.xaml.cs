using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
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
    private bool _isLaunching;
    private bool _syncingToggles;
    private StoredAccount? _selectedAccount;

    // The version the player has chosen in the grid picker. Source of truth for
    // Home (the old Mojang-populated ComboBox is gone); persisted to settings on
    // change. Coerced to a version Origin actually ships if a stale one is read.
    private string? _selectedVersion;

    // In-flight launch provisioning. The newest launch action always wins
    // (CLAUDE.md hard requirement): the overlay's Cancel aborts this token,
    // and starting a new launch replaces it.
    private CancellationTokenSource? _launchCts;

    public HomePage()
    {
        InitializeComponent();
        _settings = SettingsStore.Load();
        LoadingOverlay.CancelRequested += (_, _) => _launchCts?.Cancel();
        VersionOverlay.VersionSelected += OnVersionSelected;
        VersionOverlay.LaunchRequested += OnLaunchRequested;
        InitVersionSelection();
        RefreshAccountState();
    }

    // The version the player currently has selected. Held in memory (and mirrored
    // to settings on an explicit pick); the newest-supported default is shown but
    // not persisted until chosen, matching the previous behavior.
    public string? CurrentVersion => _selectedVersion;

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
        var hasVersion = _selectedVersion != null;
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

    // No network round-trip anymore: the grid is a fixed catalog of the versions
    // Origin ships, so selection is instant (better cold-start too). Coerce a
    // stale/unsupported persisted value to the newest supported version.
    private void InitVersionSelection()
    {
        _selectedVersion = VersionCatalog.IsSupported(_settings.SelectedVersion)
            ? _settings.SelectedVersion
            : VersionCatalog.DefaultVersion;
        SetVersionButtonText(_selectedVersion);
        SyncExternalModsToggle();
        UpdatePlayState();
    }

    // Raised by the grid picker when the player confirms a specific version.
    // Does exactly one thing: records the new version. No other setting moves.
    private void OnVersionSelected(string version)
    {
        _selectedVersion = version;
        _settings.SelectedVersion = version;
        SettingsStore.Save(_settings);
        SetVersionButtonText(version);
        UpdatePlayState();
    }

    private void SetVersionButtonText(string? version) =>
        VersionButtonText.Text = version ?? "Select a version";

    private void VersionButton_Click(object sender, RoutedEventArgs e) =>
        VersionOverlay.Show(_selectedVersion);

    // Reflect the persisted value into the switch without re-triggering the
    // Checked/Unchecked save handlers (the _syncingToggles guard).
    private void SyncExternalModsToggle()
    {
        _syncingToggles = true;
        ExternalModsToggle.IsChecked = _settings.PlayWithExternalMods;
        _syncingToggles = false;
    }

    private void ExternalModsToggle_Checked(object sender, RoutedEventArgs e)
    {
        if (_syncingToggles) return;
        SaveExternalMods(true);
    }

    private void ExternalModsToggle_Unchecked(object sender, RoutedEventArgs e)
    {
        if (_syncingToggles) return;
        SaveExternalMods(false);
    }

    // Load-fresh-then-save: only this one field is written, so a concurrent
    // Settings-page edit to other fields survives.
    private void SaveExternalMods(bool value)
    {
        _settings.PlayWithExternalMods = value;
        var persisted = SettingsStore.Load();
        persisted.PlayWithExternalMods = value;
        SettingsStore.Save(persisted);
    }

    private async void PlayButton_Click(object sender, RoutedEventArgs e) => await LaunchAsync();

    // The version picker's Launch button: adopt the chosen version, then launch
    // through the exact same path as Play.
    private async void OnLaunchRequested(string version)
    {
        OnVersionSelected(version);
        await LaunchAsync();
    }

    private async Task LaunchAsync()
    {
        // One fresh read for every launch-gating setting: offline test mode
        // and the external-mods switch can both change on other pages between
        // clicks, and this launch must honour what's on disk NOW.
        var freshSettings = SettingsStore.Load();
        var offlineTest = freshSettings.OfflineTestMode;
        var externalMods = freshSettings.PlayWithExternalMods;
        if (_isLaunching || (_selectedAccount == null && !offlineTest)) return;
        if (_selectedVersion is not { } version) return;

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
        SetPlayLaunching(true);
        LoadingOverlay.Show(version, "Fabric");

        // Set true once the game process has actually started: from that point
        // WatchBootAsync owns the launching state (spinner + _isLaunching) and
        // the finally below must not reset it.
        var bootWatchStarted = false;

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
                version, launchOption, externalMods, installProgress, cts.Token);

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
            var logPath = StartWithLifecycleCapture(process, version);

            // The process is up but the game window isn't — the Play button
            // keeps spinning (via WatchBootAsync) until Minecraft's window
            // actually appears, or the boot dies early, which restores the
            // button and surfaces the crash.
            StatusText.Text = $"Starting Minecraft {version}...";
            var runningMessage = _selectedAccount != null
                ? $"Launched {version} — signed in as {session.Username}"
                : $"Launched {version} — offline test session";
            bootWatchStarted = true;
            _ = WatchBootAsync(process, logPath, runningMessage);
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
                // Release ownership BEFORE disposing. _launchCts must never be
                // left pointing at a disposed source: the next launch's
                // "_launchCts?.Cancel()" (and the overlay's Cancel handler)
                // would throw ObjectDisposedException — this was the "Play
                // crashes/does nothing on every launch after the first" bug
                // (see crash_20260712_113213.log).
                _launchCts = null;
                LoadingOverlay.Hide();
                if (!bootWatchStarted)
                {
                    _isLaunching = false;
                    SetPlayLaunching(false);
                    // Not the full UpdatePlayState() — that unconditionally overwrites
                    // StatusText with "Signed in as X", clobbering whatever specific
                    // message the try/catch above just set. Only re-sync IsEnabled here.
                    PlayButton.IsEnabled =
                        (_selectedAccount != null || SettingsStore.Load().OfflineTestMode)
                        && _selectedVersion != null;
                }
            }
            cts.Dispose();
        }
    }

    // Owns the tail of the launching state: keeps the Play button spinning
    // after the game process starts, until Minecraft's window actually exists
    // (launch finished) or the process dies during boot (crash — surface it
    // and return the button to normal). Runs on the UI context; the polls are
    // cheap and non-blocking. The 90s ceiling is a fail-open safety net for a
    // machine where window detection misbehaves — the button must never spin
    // forever.
    private async Task WatchBootAsync(System.Diagnostics.Process process, string? logPath, string runningMessage)
    {
        try
        {
            var deadline = DateTime.UtcNow.AddSeconds(90);
            while (DateTime.UtcNow < deadline)
            {
                bool exited;
                try { exited = process.HasExited; }
                catch { break; } // process handle gone — nothing more to learn

                if (exited)
                {
                    int exitCode;
                    try { exitCode = process.ExitCode; } catch { exitCode = -1; }
                    StatusText.Text = exitCode == 0
                        ? "Minecraft closed — click Play to launch again."
                        : logPath != null
                            ? $"Minecraft crashed while starting (exit code {exitCode}) — log saved to {logPath}"
                            : $"Minecraft crashed while starting (exit code {exitCode}).";
                    return;
                }

                try
                {
                    process.Refresh();
                    if (process.MainWindowHandle != IntPtr.Zero)
                    {
                        StatusText.Text = runningMessage;
                        return;
                    }
                }
                catch { break; }

                await Task.Delay(500);
            }
            // Timed out without seeing a window — assume the game is running
            // (headless/odd window setups) rather than reporting a failure
            // that didn't happen.
            StatusText.Text = runningMessage;
        }
        finally
        {
            _isLaunching = false;
            SetPlayLaunching(false);
            PlayButton.IsEnabled =
                (_selectedAccount != null || SettingsStore.Load().OfflineTestMode)
                && _selectedVersion != null;
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

    // Redirects the launched instance's stdout/stderr to a per-launch log file
    // and reports the exit code back to Home once the game closes — this is
    // the crash-diagnostics foundation Phase 3 builds on, not full crash
    // parsing yet. Returns the log path so the boot watcher can point at it
    // when the game dies during startup.
    private string StartWithLifecycleCapture(System.Diagnostics.Process process, string version)
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

        return logPath;
    }

    // Swaps the Play button between its label and the spinning launch arc.
    // Same clock pattern as LaunchLoadingOverlay: a fresh RotateTransform +
    // AnimationClock per run, stopped and dropped on restore so nothing keeps
    // ticking behind a static button.
    private AnimationClock? _playSpinClock;

    private void SetPlayLaunching(bool launching)
    {
        if (launching && _playSpinClock != null) return; // already spinning

        PlayLabel.Visibility = launching ? Visibility.Collapsed : Visibility.Visible;
        PlaySpinner.Visibility = launching ? Visibility.Visible : Visibility.Collapsed;

        if (launching)
        {
            var transform = new RotateTransform();
            PlaySpinner.RenderTransform = transform;
            var spin = new DoubleAnimation(0, 360, new Duration(TimeSpan.FromSeconds(1.1)))
            {
                RepeatBehavior = RepeatBehavior.Forever
            };
            _playSpinClock = spin.CreateClock();
            transform.ApplyAnimationClock(RotateTransform.AngleProperty, _playSpinClock);
        }
        else
        {
            _playSpinClock?.Controller?.Stop();
            _playSpinClock = null;
        }
    }
}
