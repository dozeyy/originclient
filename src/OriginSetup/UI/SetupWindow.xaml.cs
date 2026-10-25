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

// The installer (docs/overhaul/DESIGN-SYSTEM.md section 14): one screen with states, Ready -> Installing ->
// Done, plus a Problem state that always says what to do next. All real work is in Engine/*; this file only
// drives the pages.
//
// The launcher is downloaded, not carried: on open, the newest release is looked up in the background so
// Ready can name its version ("Version 1.0.39 to 1.0.40"); until that answers (or if it can't, offline), Ready
// reads without a version and the install looks the release up itself, so any internet problem surfaces
// there, on the Problem page, with "Try again".
public partial class SetupWindow : Window
{
    private readonly string _self;
    private readonly string? _sourceZip; // --source: install this zip, no download
    private LauncherRelease? _release;
    private int _lookupGeneration;
    private string? _noteOverride;
    private Registration.ExistingInstall? _existing;
    private readonly FrameworkElement[] _pages;

    // The install folder is whatever is in the (editable) box; _settingLocation marks changes made by code, which
    // re-check at once instead of waiting for the typing pause.
    private string _location
    {
        get => LocationBox.Text;
        set
        {
            _settingLocation = true;
            try { LocationBox.Text = value; }
            finally { _settingLocation = false; }
        }
    }
    private bool _settingLocation;
    private readonly DispatcherTimer _locationCheck;
    private readonly int _recommendedRamMb;
    private bool _ramChanged;
    private bool _customizeOpen;
    private bool _loading = true;
    private bool _installing;
    private CancellationTokenSource? _cts;
    private InstallOptions? _pendingOptions;
    private string? _installedDir;

    // Progress arrives from a worker thread; the UI samples it on a timer.
    private readonly DispatcherTimer _progressTimer;
    private volatile InstallProgress _progress = new InstallProgress(InstallStage.Preparing, 0);

    /// <param name="lookUpRelease">False for the CI screen tour, which must not touch the network.</param>
    public SetupWindow(string self, string? sourceZip, bool lookUpRelease = true)
    {
        InitializeComponent();
        WindowFrame.RoundCorners(this);
        _self = self;
        _sourceZip = sourceZip;
        _pages = new FrameworkElement[] { ReadyPage, InstallingPage, DonePage, ProblemPage };
        _existing = Registration.FindExisting();

        _progressTimer = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(33) };
        _progressTimer.Tick += (_, _) => RenderProgress();
        // Typing in the folder box: re-check 250 ms after the last keystroke, not on every one.
        _locationCheck = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        _locationCheck.Tick += (_, _) => { _locationCheck.Stop(); CheckLocation(); };

        StartupCheck.IsChecked = SafeIsStartWithWindows();

        // Customize: memory starts on this PC's recommended amount, the version on "Newest". Only a change the
        // player makes is handed to the launcher (InstallButton_Click), so untouched means the launcher's own defaults.
        int totalMb = SystemInfo.GetTotalPhysicalMemoryMb();
        _recommendedRamMb = RamAdvice.RecommendedMb(totalMb);
        RamSlider.Minimum = RamAdvice.SliderMinimumMb;
        RamSlider.Maximum = RamAdvice.MaximumMb(totalMb);
        RamSlider.Value = _recommendedRamMb;
        RamValue.Text = FormatRam(_recommendedRamMb);
        RamHint.Text = $"Recommended for your PC: {FormatRam(_recommendedRamMb)} of {Math.Round(totalMb / 1024d):0} GB";
        RamResetButton.IsEnabled = false;
        VersionCombo.Items.Add(new ComboBoxItem { Content = "Newest (recommended)", Tag = null });
        foreach (var v in VersionChoices.Embedded())
            VersionCombo.Items.Add(new ComboBoxItem { Content = "Minecraft " + v, Tag = v });
        VersionCombo.SelectedIndex = 0;
        _loading = false;

        // An existing install keeps its folder (moving it is an uninstall + install); otherwise the default.
        _location = _existing?.Directory ?? InstallLocation.Default;
        BrowseButton.IsEnabled = _existing == null;
        LocationBox.IsReadOnly = _existing != null;
        FillReady();
        UpdateLocation();
        ShowPage(ReadyPage);
        if (lookUpRelease && sourceZip == null) LookUpRelease();
    }

    // Best effort, in the background: only Ready's wording depends on it.
    private async void LookUpRelease()
    {
        int generation = ++_lookupGeneration;
        try
        {
            var release = await Task.Run(() => ReleaseFeed.FindLatestAsync(Download.Shared, CancellationToken.None));
            if (generation != _lookupGeneration || _installing) return;
            _release = release;
            FillReady();
        }
        catch (Exception ex)
        {
            SetupLog.Write("release lookup: " + ex.Message); // the install looks again, and words the problem
        }
    }

    // ---------- Ready ----------

    private void FillReady()
    {
        var newVersion = _release?.Version;

        if (_existing == null)
        {
            ReadyTitle.Text = "Install Origin";
            var size = _release?.Size is long bytes ? $", a {bytes / 1048576} MB download" : "";
            ReadyBody.Text = newVersion != null
                ? $"Origin {newVersion}{size}. It takes about a minute."
                : "Setup downloads the newest Origin, so it needs an internet connection.";
            InstallButton.Content = "Install";
            return;
        }

        // Without a version yet (still looking, or offline) the install fetches the newest one anyway, so
        // "Update" is the honest word.
        bool reinstall = newVersion != null && !IsOlder(_existing.Version, newVersion);
        ReadyTitle.Text = reinstall ? "Reinstall Origin" : "Update Origin";
        ReadyBody.Text = reinstall
            ? $"Repairs Origin {newVersion}. Your accounts, settings and worlds stay put."
            : newVersion != null && _existing.Version != null
                ? $"Version {_existing.Version} to {newVersion}. Your accounts, settings and worlds stay put."
                : "Setup installs the newest Origin. Your accounts, settings and worlds stay put.";
        InstallButton.Content = reinstall ? "Reinstall" : "Update";
    }

    private static bool IsOlder(string? installed, string? incoming) =>
        installed != null && incoming != null && Version.TryParse(installed, out var a) && Version.TryParse(incoming, out var b) && a < b;

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
            ShowLocationStatus(problem, isProblem: true);
            if (_existing == null) LocationBox.Focus();
            return;
        }

        var options = new InstallOptions
        {
            Directory = dir!,
            StartWithWindows = StartupCheck.IsChecked == true,
            RamMb = _ramChanged ? (int)RamSlider.Value : (int?)null,
            DefaultVersion = (VersionCombo.SelectedItem as ComboBoxItem)?.Tag as string,
            SourceZip = _sourceZip,
        };

        if (LauncherProcess.IsRunning())
        {
            _pendingOptions = options;
            ShowRunningDialog();
            return;
        }
        BeginInstall(options);
    }

    // ---------- Customize ----------

    private void CustomizeButton_Click(object sender, RoutedEventArgs e) => SetCustomize(!_customizeOpen);

    // Open or close the section; the window grows by exactly its height and the footer stays pinned (spec 14).
    private void SetCustomize(bool open)
    {
        _customizeOpen = open;
        CustomizePanel.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
        CustomizeTurn.Angle = open ? 180 : 0;
        FitHeight(ReadyPage.Visibility == Visibility.Visible);
    }

    // The tall size belongs to the Ready page with Customize open; every other state uses the standard 440.
    private void FitHeight(bool onReady)
    {
        double h = (double)FindResource(onReady && _customizeOpen ? "Size.WindowHeightOpen" : "Size.WindowHeight");
        if (Math.Abs(Height - h) < 0.5) return;
        Height = h;
        // Growing must not push the footer below the screen: slide the window up instead.
        var work = SystemParameters.WorkArea;
        if (!double.IsNaN(Top) && Top + h > work.Bottom) Top = Math.Max(work.Top, work.Bottom - h);
    }

    private void RamSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (RamValue == null) return; // fires once while the window is being built
        RamValue.Text = FormatRam((int)e.NewValue);
        if (_loading) return;
        _ramChanged = (int)e.NewValue != _recommendedRamMb;
        RamResetButton.IsEnabled = _ramChanged;
    }

    private void RamResetButton_Click(object sender, RoutedEventArgs e) => RamSlider.Value = _recommendedRamMb;

    private static string FormatRam(int mb) => $"{mb / 1024d:0.##} GB";

    // ---------- Location ----------

    private string? ResolvedLocation()
    {
        try { return InstallLocation.Resolve(_location); }
        catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException || ex is PathTooLongException)
        {
            return null;
        }
    }

    // Shows the folder (shortened in the middle to fit) and re-checks it.
    private void UpdateLocation()
    {
        WindowFrame.FitPath(LocationText, _location);
        CheckLocation();
    }

    private void LocationText_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (e.WidthChanged) WindowFrame.FitPath(LocationText, _location);
    }

    private void LocationBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        WindowFrame.FitPath(LocationText, _location);
        if (_loading || _settingLocation) return;
        _locationCheck.Stop();
        _locationCheck.Start();
    }

    // Editing swaps the shortened display for the real box (caret at the end, accent edge); leaving swaps it back.
    private void LocationBox_FocusChanged(object sender, KeyboardFocusChangedEventArgs e) =>
        SetEditing(LocationBox.IsKeyboardFocused);

    private void SetEditing(bool editing)
    {
        LocationBox.Opacity = editing ? 1 : 0;
        LocationText.Visibility = editing ? Visibility.Hidden : Visibility.Visible;
        if (editing) LocationField.BorderBrush = (Brush)FindResource("Brush.AccentBorder");
        else LocationField.ClearValue(Border.BorderBrushProperty);
        if (editing)
        {
            // Caret at the end, and scrolled so the end of a long path is what shows (as the box always did).
            LocationBox.CaretIndex = LocationBox.Text.Length;
            LocationBox.UpdateLayout();
            LocationBox.ScrollToHorizontalOffset(LocationBox.ExtentWidth);
        }
        else WindowFrame.FitPath(LocationText, _location);
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
        // Spell the folder out only when it differs from what is typed (Origin got its own subfolder).
        bool same = dir != null && string.Equals(dir.TrimEnd('\\'), _location.Trim().TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);
        ShowLocationStatus(problem ?? _noteOverride
            ?? (same || dir == null ? $"Needs about {Installer.SpaceEstimate / 1048576} MB of free space."
                                    : $"Origin gets its own folder: {dir}"),
            isProblem: problem != null);
    }

    private void ShowLocationStatus(string text, bool isProblem)
    {
        LocationStatus.Text = text;
        LocationStatus.Foreground = (Brush)FindResource(isProblem ? "Brush.DangerText" : "Brush.TextMuted");
        LocationDot.Visibility = isProblem ? Visibility.Visible : Visibility.Collapsed;
        InstallButton.IsEnabled = !isProblem;
    }

    private void BrowseButton_Click(object sender, RoutedEventArgs e)
    {
        var picked = FolderPicker.Pick(this, "Choose where to install Origin", LocationBox.Text);
        if (picked == null) return;
        try { _location = InstallLocation.Resolve(picked); }
        catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException || ex is PathTooLongException)
        {
            _location = picked;
        }
        _noteOverride = null;
        UpdateLocation();
    }

    private static bool SafeIsStartWithWindows()
    {
        try { return InstallRegistry.IsStartWithWindows(); }
        catch (Exception ex) when (ex is System.Security.SecurityException || ex is UnauthorizedAccessException) { return false; }
    }

    // ---------- "Origin is open" ----------

    private void ShowRunningDialog()
    {
        RunningCloseButton.IsEnabled = true;
        RunningOverlay.Visibility = Visibility.Visible;
        PagesHost.IsHitTestVisible = false;
        Bar.IsHitTestVisible = false;
        RunningCancelButton.Focus(); // the safe action takes the focus (spec 11.10)
    }

    private void HideRunningDialog()
    {
        RunningOverlay.Visibility = Visibility.Collapsed;
        PagesHost.IsHitTestVisible = true;
        Bar.IsHitTestVisible = true;
    }

    private async void RunningClose_Click(object sender, RoutedEventArgs e)
    {
        RunningCloseButton.IsEnabled = false;
        await Task.Run(() => LauncherProcess.Close(TimeSpan.FromSeconds(5)));
        HideRunningDialog();
        if (LauncherProcess.IsRunning())
        {
            ShowProblem("Origin is still open", "Close it yourself (right-click it in the taskbar, then Close window), then try again.", canRetry: true);
            return;
        }
        if (_pendingOptions != null) BeginInstall(_pendingOptions);
    }

    private void RunningCancel_Click(object sender, RoutedEventArgs e) => HideRunningDialog();

    // ---------- Installing ----------

    private async void BeginInstall(InstallOptions options)
    {
        _installing = true;
        _installedDir = options.Directory;
        _noteOverride = null;
        _cts = new CancellationTokenSource();
        Bar.CanClose = false;
        CancelButton.Visibility = Visibility.Visible;
        CancelButton.IsEnabled = true;
        ProgressFill.BeginAnimation(WidthProperty, null);
        ProgressFill.Width = 0;
        var release = _release;
        _progress = new InstallProgress(InstallStage.Preparing, 0, version: release?.Version);

        bool reinstall = _existing != null && release != null && !IsOlder(_existing.Version, release.Version);
        InstallingTitle.Text = _existing == null ? "Installing Origin" : reinstall ? "Reinstalling Origin" : "Updating Origin";
        ShowPage(InstallingPage);
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
            DoneTitle.Text = _existing == null ? "Origin is installed"
                : _existing.Version != null && installed != null && !IsOlder(_existing.Version, installed) ? "Origin is repaired"
                : "Origin is up to date";
            DoneStatus.Text = installed != null ? $"Installed, version {installed}" : "Installed";
            ShowPage(DonePage);
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
        }
    }

    private void RenderProgress()
    {
        var p = _progress;
        double f = Math.Max(0, Math.Min(1, p.Fraction));
        PageMotion.Width(ProgressFill, ProgressTrack.ActualWidth * f);
        PercentText.Text = $"{f * 100:0}%";
        StageText.Text = _cts?.IsCancellationRequested == true && p.Stage <= InstallStage.Unpacking ? "Cancelling" : p.Stage switch
        {
            InstallStage.Preparing => "Getting ready",
            InstallStage.Downloading when p.Bytes > 0 && p.TotalBytes is long total =>
                $"Downloading Origin, {p.Bytes / 1048576} of {total / 1048576} MB",
            InstallStage.Downloading when p.Bytes > 0 => $"Downloading Origin, {p.Bytes / 1048576} MB",
            InstallStage.Downloading => "Downloading Origin",
            InstallStage.Unpacking => "Unpacking Origin",
            InstallStage.Finishing => "Adding shortcuts",
            _ => "Done",
        };
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
            _noteOverride = "Install cancelled. Nothing was changed.";
            FillReady();
            CheckLocation();
            ShowPage(ReadyPage);
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
            ShowProblem("This package is damaged", "The launcher package Setup was given is incomplete or corrupted.", canRetry: false);
        else if (first is UnauthorizedAccessException || first is IOException)
            ShowProblem("Origin wasn't installed", "A file couldn't be written. Close anything that might be using Origin's folder (or pause your antivirus scan), then try again.", canRetry: true);
        else
            ShowProblem("Something went wrong", "Setup hit an unexpected error. Try again. If it keeps happening, use Copy details and send it to us.", canRetry: true);
    }

    // ---------- Done / Problem ----------

    private void LaunchButton_Click(object sender, RoutedEventArgs e)
    {
        var dir = _installedDir ?? InstallLocation.Default;
        var exe = Path.Combine(dir, InstallLocation.LauncherExe);
        try
        {
            // The finish page's optional checkbox: open straight into the launcher's import card.
            Process.Start(FinishLaunch.StartInfo(exe, dir, ImportToggle.IsChecked == true));
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
        FillReady();
        CheckLocation();
        ShowPage(ReadyPage);
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
        ShowPage(ProblemPage);
    }

    // ---------- Shell ----------

    private void ShowPage(FrameworkElement page)
    {
        PageMotion.Show(page, _pages);
        FitHeight(page == ReadyPage);
        InstallButton.IsDefault = page == ReadyPage;
        LaunchButton.IsDefault = page == DonePage;
        RetryButton.IsDefault = page == ProblemPage && RetryButton.Visibility == Visibility.Visible;
        // The path field only has a width to fit once its page is laid out.
        if (page == ReadyPage)
            Dispatcher.BeginInvoke(new Action(() => WindowFrame.FitPath(LocationText, _location)), DispatcherPriority.Loaded);
    }

    private void Bar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void Window_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && RunningOverlay.Visibility == Visibility.Visible) HideRunningDialog();
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

    // ---------- CI screenshot tour (RenderTour.cs): puts the window in each state without doing any work ----------

    internal void TourRelease(LauncherRelease release) => _release = release;

    internal void TourReady(string? installedVersion, string? location = null)
    {
        _existing = installedVersion == null ? null
            : new Registration.ExistingInstall(InstallLocation.Default, installedVersion, isLegacyInno: false);
        _location = location ?? _existing?.Directory ?? InstallLocation.Default;
        _noteOverride = null;
        HideRunningDialog(); // the "Close Origin Launcher?" shot leaves its overlay up
        BrowseButton.IsEnabled = _existing == null;
        LocationBox.IsReadOnly = _existing != null;
        SetEditing(false);
        FillReady();
        // Every tour state starts from the defaults: Customize closed, memory recommended, version Newest.
        SetCustomize(false);
        RamSlider.Value = _recommendedRamMb;
        VersionCombo.SelectedIndex = 0;
        Chrome.SetKeyFocus(RamSlider, false);
        ShowPage(ReadyPage);
        UpdateLayout(); // the path is shortened to the width laid out, so lay out first
        UpdateLocation();
    }

    /// <summary>Ready with Customize open; changed = memory moved off the recommendation and an explicit version picked.</summary>
    internal void TourCustomize(bool changed, bool keyboardFocusOnSlider = false)
    {
        TourReady(null);
        SetCustomize(true);
        if (changed)
        {
            RamSlider.Value = _recommendedRamMb - 1024 >= RamSlider.Minimum ? _recommendedRamMb - 1024 : _recommendedRamMb + 1024;
            if (VersionCombo.Items.Count > 1) VersionCombo.SelectedIndex = 1;
        }
        Chrome.SetKeyFocus(RamSlider, keyboardFocusOnSlider);
        UpdateLayout();
        WindowFrame.FitPath(LocationText, _location);
    }

    /// <summary>The folder box as it looks while the player types in it (the tour window is never the active one).</summary>
    internal void TourEditingPath(string path)
    {
        TourReady(null, path);
        SetEditing(true);
        UpdateLayout();
    }

    internal void TourLocationProblem(string problem)
    {
        TourReady(null);
        ShowLocationStatus(problem, isProblem: true);
    }

    /// <summary>Shows the keyboard focus ring on the default button, as Tab would.</summary>
    internal void TourKeyboardFocus(bool on) => Chrome.SetKeyFocus(InstallButton, on);

    internal void TourInstalling(InstallProgress progress, string title)
    {
        InstallingTitle.Text = title;
        ShowPage(InstallingPage);
        _progress = progress;
        CancelButton.Visibility = Visibility.Visible;
        UpdateLayout();
        RenderProgress();
    }

    internal void TourDone(string title, string status)
    {
        DoneTitle.Text = title;
        DoneStatus.Text = status;
        ShowPage(DonePage);
    }

    internal void TourProblem() =>
        ShowProblem("Couldn't download Origin", DownloadException.CantConnect, canRetry: true);

    internal void TourOriginOpen()
    {
        TourReady(null);
        ShowRunningDialog();
    }
}
