using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Install;
using OriginSetup.Engine;

namespace OriginSetup.UI;

// The installer: Welcome -> (Customize) -> Installing -> Done, plus a Problem
// page that always says what to do next. All real work is in Engine/*; this
// file only drives the pages.
//
// The launcher is downloaded, not carried: on open, the newest release is
// looked up in the background so Welcome can name its version ("Update
// 1.0.39 -> 1.0.40"); until that answers (or if it can't — offline), Welcome
// reads without a version and the install looks the release up itself, so
// any internet problem surfaces there, on the Problem page, with "Try again".
public partial class SetupWindow : Window
{
    private readonly string _self;
    private readonly string? _sourceZip; // --source: install this zip, no download
    private LauncherRelease? _release;
    private int _lookupGeneration;
    private string? _footnoteOverride;
    private Registration.ExistingInstall? _existing;
    private readonly FrameworkElement[] _pages;
    private readonly int _recommendedRamMb;

    private bool _loading = true;
    private bool _ramChanged;
    private bool _syncInitial; // Settings Sync as the existing install has it (off on a fresh PC)
    private bool _installing;
    private CancellationTokenSource? _cts;
    private InstallOptions? _pendingOptions;
    private string? _installedDir;
    private string? _locationProblem;

    // Progress arrives from a worker thread; the UI samples it on a timer.
    private readonly DispatcherTimer _progressTimer;
    private volatile InstallProgress _progress = new InstallProgress(InstallStage.Preparing, 0);

    private readonly DispatcherTimer _locationCheck;

    /// <param name="lookUpRelease">False for the CI screen tour, which must not touch the network.</param>
    public SetupWindow(string self, string? sourceZip, bool lookUpRelease = true)
    {
        InitializeComponent();
        _self = self;
        _sourceZip = sourceZip;
        _pages = new FrameworkElement[] { WelcomePage, CustomizePage, InstallingPage, DonePage, ProblemPage };
        _existing = Registration.FindExisting();

        _progressTimer = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(33) };
        _progressTimer.Tick += (_, _) => RenderProgress();
        _locationCheck = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        _locationCheck.Tick += (_, _) => { _locationCheck.Stop(); CheckLocation(); };

        int totalMb = SystemInfo.GetTotalPhysicalMemoryMb();
        _recommendedRamMb = RamAdvice.RecommendedMb(totalMb);
        RamSlider.Minimum = RamAdvice.SliderMinimumMb;
        RamSlider.Maximum = RamAdvice.MaximumMb(totalMb);
        RamSlider.Value = _recommendedRamMb;
        RamValue.Text = FormatRam(_recommendedRamMb);
        RamHint.Text = $"Recommended for your PC: {FormatRam(_recommendedRamMb)} of {Math.Round(totalMb / 1024d):0} GB";

        VersionCombo.Items.Add(new ComboBoxItem { Content = "Newest (recommended)", Tag = null });
        foreach (var v in VersionChoices.Embedded())
            VersionCombo.Items.Add(new ComboBoxItem { Content = "Minecraft " + v, Tag = v });
        VersionCombo.SelectedIndex = 0;

        StartupToggle.IsChecked = SafeIsStartWithWindows();
        _syncInitial = Handoff.IsSettingsSyncOn(Handoff.SettingsPath);
        SyncToggle.IsChecked = _syncInitial;

        FillWelcome();
        if (_existing != null)
        {
            LocationBox.Text = _existing.Directory;
            LocationBox.IsReadOnly = true;
            BrowseButton.IsEnabled = false;
        }
        else
        {
            LocationBox.Text = InstallLocation.Default;
        }
        _loading = false;
        CheckLocation();
        ShowPage(WelcomePage, step: 1);
        if (lookUpRelease && sourceZip == null) LookUpRelease();
    }

    // Best effort, in the background: only Welcome's wording depends on it.
    private async void LookUpRelease()
    {
        int generation = ++_lookupGeneration;
        try
        {
            var release = await Task.Run(() => ReleaseFeed.FindLatestAsync(Download.Shared, CancellationToken.None));
            if (generation != _lookupGeneration || _installing) return;
            _release = release;
            FillWelcome();
        }
        catch (Exception ex)
        {
            SetupLog.Write("release lookup: " + ex.Message); // the install looks again, and words the problem
        }
    }

    // A long path's useful part is its end ("...\OriginLauncher"), so show that.
    private void ShowPathEnd()
    {
        LocationBox.CaretIndex = LocationBox.Text.Length;
        LocationBox.UpdateLayout();
        // ScrollToEnd() is vertical-only; scroll sideways past the extent instead.
        LocationBox.ScrollToHorizontalOffset(LocationBox.ExtentWidth);
    }

    // ---------- Welcome ----------

    private void FillWelcome()
    {
        var newVersion = _release?.Version;

        if (_existing == null)
        {
            WelcomeEyebrow.Text = newVersion != null ? $"ORIGIN LAUNCHER {newVersion}" : "ORIGIN LAUNCHER";
            WelcomeTitle.Text = "Welcome to Origin";
            WelcomeBody.Text = "Every Minecraft version, one clean launcher. Setup downloads the newest Origin — it takes about a minute.";
            InstallButton.Content = "Install";
            CustomizeInstallButton.Content = "Install";
            var size = _release?.Size is long bytes ? $"{bytes / 1048576} MB download" : "needs an internet connection";
            WelcomeFootnote.Text = _footnoteOverride ?? $"Installs to {InstallLocation.Default}  ·  {size}";
            return;
        }

        // Without a version yet (still looking, or offline) the install fetches
        // the newest one anyway, so "Update" is the honest word.
        bool reinstall = newVersion != null && !IsOlder(_existing.Version, newVersion);
        WelcomeEyebrow.Text = reinstall ? "ALREADY INSTALLED" : newVersion != null ? "UPDATE AVAILABLE" : "ALREADY INSTALLED";
        WelcomeTitle.Text = reinstall ? "Reinstall Origin" : "Update Origin";
        WelcomeBody.Text = reinstall
            ? $"Origin {newVersion} is ready to reinstall. It repairs the launcher — your accounts, settings and worlds stay exactly as they are."
            : newVersion != null && _existing.Version != null
                ? $"Version {_existing.Version} → {newVersion}. Your accounts, settings and worlds stay exactly as they are."
                : "Setup downloads the newest Origin and installs it over this one. Your accounts, settings and worlds stay exactly as they are.";
        InstallButton.Content = reinstall ? "Reinstall" : "Update";
        CustomizeInstallButton.Content = InstallButton.Content;
        WelcomeFootnote.Text = _footnoteOverride ?? $"Installed in {_existing.Directory}";
    }

    private static bool IsOlder(string? installed, string? incoming) =>
        installed != null && incoming != null && Version.TryParse(installed, out var a) && Version.TryParse(incoming, out var b) && a < b;

    private static string TitleFor(string? version) => version != null ? $"Origin Launcher {version}" : "Origin Launcher";

    private void InstallButton_Click(object sender, RoutedEventArgs e)
    {
        if (_installing) return;

        var dir = _existing?.Directory ?? ResolvedLocation();
        var problem = dir == null
            ? "Choose a folder."
            : InstallLocation.Problem(dir, InstallLocation.ProtectedRoots())
              ?? InstallLocation.SpaceProblem(dir, Installer.SpaceEstimate)
              ?? InstallLocation.ProbeWritable(dir);
        if (problem != null)
        {
            ShowPage(CustomizePage, step: 2);
            ShowLocationStatus(problem, isProblem: true);
            LocationBox.Focus();
            return;
        }

        var options = new InstallOptions
        {
            Directory = dir!,
            StartWithWindows = StartupToggle.IsChecked == true,
            RamMb = _ramChanged ? (int)RamSlider.Value : (int?)null,
            DefaultVersion = (VersionCombo.SelectedItem as ComboBoxItem)?.Tag as string,
            // Only a change is handed over, so updating over an install that
            // already has sync on never switches it off by accident.
            SettingsSync = (SyncToggle.IsChecked == true) != _syncInitial ? SyncToggle.IsChecked == true : (bool?)null,
            SourceZip = _sourceZip,
        };

        if (LauncherProcess.IsRunning())
        {
            _pendingOptions = options;
            RunningCloseButton.IsEnabled = true;
            RunningOverlay.Visibility = Visibility.Visible;
            return;
        }
        BeginInstall(options);
    }

    private void CustomizeButton_Click(object sender, RoutedEventArgs e) => ShowPage(CustomizePage, step: 2);

    private void BackButton_Click(object sender, RoutedEventArgs e) => ShowPage(WelcomePage, step: 1);

    // ---------- Customize ----------

    private string? ResolvedLocation()
    {
        try { return InstallLocation.Resolve(LocationBox.Text); }
        catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException || ex is PathTooLongException)
        {
            return null;
        }
    }

    private void LocationBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_loading) return;
        _locationCheck.Stop();
        _locationCheck.Start();
    }

    private void CheckLocation()
    {
        if (_existing != null)
        {
            ShowLocationStatus("Origin is already installed here. To move it, uninstall it first.", isProblem: false);
            return;
        }
        var dir = ResolvedLocation();
        var problem = dir == null
            ? "That folder name isn't valid."
            : InstallLocation.Problem(dir, InstallLocation.ProtectedRoots()) ?? InstallLocation.SpaceProblem(dir, Installer.SpaceEstimate);
        if (problem != null)
        {
            ShowLocationStatus(problem, isProblem: true);
            return;
        }
        // Only spell the folder out when it differs from what's typed (Origin
        // got its own subfolder); otherwise the box already says it.
        bool same = string.Equals(dir!.TrimEnd('\\'), LocationBox.Text.Trim().TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);
        ShowLocationStatus(same
            ? "Ready — Origin goes in a folder of its own."
            : $"Origin gets its own folder: {dir}", isProblem: false);
    }

    private void ShowLocationStatus(string text, bool isProblem)
    {
        _locationProblem = isProblem ? text : null;
        LocationStatus.Text = text;
        LocationStatus.Foreground = (Brush)FindResource(isProblem ? "Brush.Warning" : "Brush.TextMuted");
        CustomizeInstallButton.IsEnabled = !isProblem;
    }

    private void BrowseButton_Click(object sender, RoutedEventArgs e)
    {
        var picked = FolderPicker.Pick(this, "Choose where to install Origin", LocationBox.Text);
        if (picked == null) return;
        try { LocationBox.Text = InstallLocation.Resolve(picked); }
        catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException || ex is PathTooLongException)
        {
            LocationBox.Text = picked;
        }
        _locationCheck.Stop();
        CheckLocation();
        ShowPathEnd();
    }

    private void RamSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (RamValue == null) return; // fires once during InitializeComponent
        RamValue.Text = FormatRam((int)e.NewValue);
        if (!_loading) _ramChanged = (int)e.NewValue != _recommendedRamMb;
    }

    private void RamResetButton_Click(object sender, RoutedEventArgs e) => RamSlider.Value = _recommendedRamMb;

    private static string FormatRam(int mb) => $"{mb / 1024d:0.##} GB";

    private static bool SafeIsStartWithWindows()
    {
        try { return InstallRegistry.IsStartWithWindows(); }
        catch (Exception ex) when (ex is System.Security.SecurityException || ex is UnauthorizedAccessException) { return false; }
    }

    // ---------- "Origin is open" ----------

    private async void RunningClose_Click(object sender, RoutedEventArgs e)
    {
        RunningCloseButton.IsEnabled = false;
        await Task.Run(() => LauncherProcess.Close(TimeSpan.FromSeconds(5)));
        RunningOverlay.Visibility = Visibility.Collapsed;
        if (LauncherProcess.IsRunning())
        {
            ShowProblem("Origin Launcher is still open", "Close it yourself (right-click it in the taskbar → Close window), then try again.", canRetry: true);
            return;
        }
        if (_pendingOptions != null) BeginInstall(_pendingOptions);
    }

    private void RunningCancel_Click(object sender, RoutedEventArgs e) => RunningOverlay.Visibility = Visibility.Collapsed;

    // ---------- Installing ----------

    private async void BeginInstall(InstallOptions options)
    {
        _installing = true;
        _installedDir = options.Directory;
        _footnoteOverride = null;
        _cts = new CancellationTokenSource();
        Bar.CanClose = false;
        CancelButton.Visibility = Visibility.Visible;
        CancelButton.IsEnabled = true;
        ProgressFill.BeginAnimation(WidthProperty, null);
        ProgressFill.Width = 0;
        var release = _release;
        _progress = new InstallProgress(InstallStage.Preparing, 0, version: release?.Version);

        bool reinstall = _existing != null && release != null && !IsOlder(_existing.Version, release.Version);
        InstallingEyebrow.Text = _existing == null ? "INSTALLING" : reinstall ? "REINSTALLING" : "UPDATING";
        InstallingTitle.Text = TitleFor(release?.Version);
        ShowPage(InstallingPage, step: 3);
        InstallingMark.SetSpeed(3.5);
        RenderProgress();
        _progressTimer.Start();

        try
        {
            var token = _cts.Token;
            await Task.Run(() => Installer.Run(_self, options, release, p => _progress = p, token));

            _progressTimer.Stop();
            RenderProgress();
            await Task.Delay(450); // let the bar land before the page changes
            var installed = _progress.Version;
            DoneTitle.Text = _existing == null ? "You're all set"
                : _existing.Version != null && installed != null && !IsOlder(_existing.Version, installed) ? "Origin is repaired"
                : "Origin is up to date";
            ShowPage(DonePage, step: 4);
            DoneMark.SetSpeed(1);
        }
        catch (Exception ex)
        {
            _progressTimer.Stop();
            HandleInstallFailure(ex);
        }
        finally
        {
            _installing = false;
            Bar.CanClose = true;
            InstallingMark.SetSpeed(1);
        }
    }

    private void RenderProgress()
    {
        var p = _progress;
        double f = Math.Max(0, Math.Min(1, p.Fraction));
        PageMotion.Width(ProgressFill, ProgressTrack.ActualWidth > 0 ? ProgressTrack.ActualWidth * f : 380 * f);
        PercentText.Text = $"{f * 100:0}%";
        StageText.Text = _cts?.IsCancellationRequested == true && p.Stage <= InstallStage.Unpacking ? "Cancelling" : p.Stage switch
        {
            InstallStage.Preparing => "Getting ready",
            InstallStage.Downloading when p.Bytes > 0 && p.TotalBytes is long total =>
                $"Downloading  ·  {p.Bytes / 1048576} of {total / 1048576} MB",
            InstallStage.Downloading when p.Bytes > 0 => $"Downloading  ·  {p.Bytes / 1048576} MB",
            InstallStage.Downloading => "Downloading Origin",
            InstallStage.Unpacking => "Unpacking Origin",
            InstallStage.Finishing => "Adding shortcuts",
            _ => "Done",
        };
        // The version becomes known mid-install when Welcome couldn't look it up.
        if (p.Version != null) InstallingTitle.Text = TitleFor(p.Version);
        // Past unpacking, files are being moved into place: no turning back.
        if (p.Stage >= InstallStage.Finishing) CancelButton.Visibility = Visibility.Hidden;
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        CancelButton.IsEnabled = false;
        StageText.Text = "Cancelling";
        _cts?.Cancel();
    }

    private void HandleInstallFailure(Exception ex)
    {
        var all = ex is AggregateException agg ? agg.Flatten().InnerExceptions.ToList() : new List<Exception> { ex };
        SetupLog.Write("install failed: " + ex);

        if (all.Any(e => e is OperationCanceledException))
        {
            _footnoteOverride = "Install cancelled — nothing was changed.";
            FillWelcome();
            ShowPage(WelcomePage, step: 1);
            return;
        }
        // Look the release up afresh, so "Try again" never retries a stale link.
        if (_sourceZip == null)
        {
            _release = null;
            LookUpRelease();
        }
        var first = all[0];
        if (first is DownloadException de)
            ShowProblem("Couldn't download Origin", de.Message, canRetry: true);
        else if (first is SetupException se)
            ShowProblem("Origin wasn't installed", se.Message, canRetry: true);
        else if (first is InvalidDataException || first is EndOfStreamException)
            // Only a local --source zip gets here; a damaged download is a DownloadException.
            ShowProblem("This Origin package is damaged", "The launcher package Setup was given is incomplete or corrupted.", canRetry: false);
        else if (first is UnauthorizedAccessException || first is IOException)
            ShowProblem("Origin wasn't installed", "A file couldn't be written. Close anything that might be using Origin's folder (or pause your antivirus scan), then try again.", canRetry: true);
        else
            ShowProblem("Something went wrong", "Setup hit an unexpected error. Try again — if it keeps happening, use Copy details and send it to us.", canRetry: true);
    }

    // ---------- Done / Problem ----------

    private void LaunchButton_Click(object sender, RoutedEventArgs e)
    {
        var dir = _installedDir ?? InstallLocation.Default;
        var exe = Path.Combine(dir, InstallLocation.LauncherExe);
        try
        {
            Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = dir });
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception || ex is FileNotFoundException)
        {
            SetupLog.Write("launch failed: " + ex.Message);
        }
        Close();
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e) => Close();

    private void RetryButton_Click(object sender, RoutedEventArgs e)
    {
        CopyDetailsButton.Content = "Copy details";
        FillWelcome();
        ShowPage(WelcomePage, step: 1);
    }

    private void CopyDetailsButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(SetupLog.Read());
            CopyDetailsButton.Content = "Copied";
        }
        catch (System.Runtime.InteropServices.COMException) { } // clipboard busy
    }

    private void ShowProblem(string title, string body, bool canRetry)
    {
        ProblemTitle.Text = title;
        ProblemBody.Text = body;
        RetryButton.Visibility = canRetry ? Visibility.Visible : Visibility.Collapsed;
        ShowPage(ProblemPage, step: 3);
        StepRail.Visibility = Visibility.Hidden;
    }

    // ---------- Shell ----------

    private void ShowPage(FrameworkElement page, int step)
    {
        StepRail.Visibility = Visibility.Visible;
        PageMotion.Show(page, _pages);
        InstallButton.IsDefault = page == WelcomePage;
        CustomizeInstallButton.IsDefault = page == CustomizePage;
        LaunchButton.IsDefault = page == DonePage;
        RetryButton.IsDefault = page == ProblemPage && RetryButton.Visibility == Visibility.Visible;
        SetStep(step);
        // The box only has a width to scroll once its page is laid out.
        if (page == CustomizePage)
            Dispatcher.BeginInvoke(new Action(ShowPathEnd), DispatcherPriority.Loaded);
    }

    private void SetStep(int step)
    {
        var markers = new[] { Step1Marker, Step2Marker, Step3Marker, Step4Marker };
        var labels = new[] { Step1Label, Step2Label, Step3Label, Step4Label };
        for (int i = 0; i < markers.Length; i++)
        {
            int n = i + 1;
            markers[i].Background = (Brush)FindResource(n == step ? "Brush.Accent" : n < step ? "Brush.TextMuted" : "Brush.SoftStroke");
            labels[i].Foreground = (Brush)FindResource(n == step ? "Brush.Text" : "Brush.TextMuted");
        }
    }

    private void Root_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed && e.OriginalSource == sender)
            DragMove();
    }

    private void Window_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            if (RunningOverlay.Visibility == Visibility.Visible) RunningOverlay.Visibility = Visibility.Collapsed;
            else if (CustomizePage.Visibility == Visibility.Visible) ShowPage(WelcomePage, step: 1);
        }
    }

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
        // Never leave a half-moved install: while installing, closing means
        // "cancel" (honoured only during the unpack, which rolls back cleanly).
        if (_installing)
        {
            e.Cancel = true;
            if (_progress.Stage <= InstallStage.Unpacking) CancelButton_Click(this, new RoutedEventArgs());
        }
    }

    // ---------- CI screenshot tour (RenderTour.cs) — puts the window in each state without doing any work ----------

    internal void TourRelease(LauncherRelease release) => _release = release;

    internal void TourWelcome(string? installedVersion)
    {
        _existing = installedVersion == null ? null
            : new Registration.ExistingInstall(InstallLocation.Default, installedVersion, isLegacyInno: false);
        FillWelcome();
        ShowPage(WelcomePage, step: 1);
    }

    internal void TourCustomize(string? problem, bool syncOn = false)
    {
        _existing = null;
        SyncToggle.IsChecked = syncOn;
        FillWelcome();
        LocationBox.IsReadOnly = false;
        BrowseButton.IsEnabled = true;
        ShowPage(CustomizePage, step: 2);
        if (problem != null) ShowLocationStatus(problem, isProblem: true);
        else CheckLocation();
    }

    internal void TourInstalling(InstallProgress progress)
    {
        InstallingEyebrow.Text = "INSTALLING";
        InstallingTitle.Text = TitleFor(progress.Version);
        ShowPage(InstallingPage, step: 3);
        _progress = progress;
        CancelButton.Visibility = Visibility.Visible;
        RenderProgress();
    }

    internal void TourDone()
    {
        DoneTitle.Text = "You're all set";
        ShowPage(DonePage, step: 4);
    }

    internal void TourProblem() =>
        ShowProblem("Couldn't download Origin", DownloadException.CantConnect, canRetry: true);

    internal void TourOriginOpen()
    {
        ShowPage(WelcomePage, step: 1);
        RunningOverlay.Visibility = Visibility.Visible;
    }
}
