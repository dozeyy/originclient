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
public partial class SetupWindow : Window
{
    private readonly Payload? _payload;
    private readonly string _self;
    private Registration.ExistingInstall? _existing;
    private readonly FrameworkElement[] _pages;
    private readonly int _recommendedRamMb;

    private bool _loading = true;
    private bool _ramChanged;
    private bool _installing;
    private CancellationTokenSource? _cts;
    private InstallOptions? _pendingOptions;
    private string? _installedDir;
    private string? _locationProblem;

    // Progress arrives from a worker thread; the UI samples it on a timer.
    private readonly DispatcherTimer _progressTimer;
    private volatile int _stage;
    private double _fraction;

    private readonly DispatcherTimer _locationCheck;

    public SetupWindow(Payload? payload, string self, string? startupProblem)
    {
        InitializeComponent();
        _payload = payload;
        _self = self;
        _pages = new FrameworkElement[] { WelcomePage, CustomizePage, InstallingPage, DonePage, ProblemPage };
        _existing = payload == null ? null : Registration.FindExisting();

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

        if (startupProblem != null || payload == null)
        {
            ShowProblem("Setup can't continue", startupProblem ?? "This setup file doesn't contain Origin.", canRetry: false);
            _loading = false;
            return;
        }

        FillWelcome(payload);
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

    private void FillWelcome(Payload payload)
    {
        var newVersion = payload.AppVersion;
        long sizeMb = payload.TotalSize / 1048576;

        if (_existing == null)
        {
            WelcomeEyebrow.Text = $"ORIGIN LAUNCHER {newVersion}";
            WelcomeTitle.Text = "Welcome to Origin";
            WelcomeBody.Text = "Every Minecraft version, one clean launcher. Setup takes about a minute.";
            InstallButton.Content = "Install";
            CustomizeInstallButton.Content = "Install";
            WelcomeFootnote.Text = $"Installs to {InstallLocation.Default}  ·  {sizeMb} MB";
            return;
        }

        bool isUpdate = IsOlder(_existing.Version, newVersion);
        WelcomeEyebrow.Text = isUpdate ? "UPDATE AVAILABLE" : "ALREADY INSTALLED";
        WelcomeTitle.Text = isUpdate ? "Update Origin" : "Reinstall Origin";
        WelcomeBody.Text = isUpdate
            ? $"Version {_existing.Version} → {newVersion}. Your accounts, settings and worlds stay exactly as they are."
            : $"Origin {newVersion} is ready to reinstall. It repairs the launcher — your accounts, settings and worlds stay exactly as they are.";
        InstallButton.Content = isUpdate ? "Update" : "Reinstall";
        CustomizeInstallButton.Content = InstallButton.Content;
        WelcomeFootnote.Text = $"Installed in {_existing.Directory}";
    }

    private static bool IsOlder(string? installed, string incoming) =>
        installed != null && Version.TryParse(installed, out var a) && Version.TryParse(incoming, out var b) && a < b;

    private void InstallButton_Click(object sender, RoutedEventArgs e)
    {
        if (_payload == null || _installing) return;

        var dir = _existing?.Directory ?? ResolvedLocation();
        var problem = dir == null
            ? "Choose a folder."
            : InstallLocation.Problem(dir, InstallLocation.ProtectedRoots())
              ?? InstallLocation.SpaceProblem(dir, _payload.TotalSize)
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
        if (_payload == null) return;
        if (_existing != null)
        {
            ShowLocationStatus("Origin is already installed here. To move it, uninstall it first.", isProblem: false);
            return;
        }
        var dir = ResolvedLocation();
        var problem = dir == null
            ? "That folder name isn't valid."
            : InstallLocation.Problem(dir, InstallLocation.ProtectedRoots()) ?? InstallLocation.SpaceProblem(dir, _payload.TotalSize);
        if (problem != null)
        {
            ShowLocationStatus(problem, isProblem: true);
            return;
        }
        // Only spell the folder out when it differs from what's typed (Origin
        // got its own subfolder); otherwise the box already says it.
        bool same = string.Equals(dir!.TrimEnd('\\'), LocationBox.Text.Trim().TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);
        ShowLocationStatus(same
            ? $"Ready — {_payload.TotalSize / 1048576} MB, in a folder of its own."
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
        _cts = new CancellationTokenSource();
        Bar.CanClose = false;
        CancelButton.Visibility = Visibility.Visible;
        CancelButton.IsEnabled = true;
        ProgressFill.BeginAnimation(WidthProperty, null);
        ProgressFill.Width = 0;
        _fraction = 0;
        _stage = (int)InstallStage.Preparing;

        bool isUpdate = _existing != null && IsOlder(_existing.Version, _payload!.AppVersion);
        InstallingEyebrow.Text = _existing == null ? "INSTALLING" : isUpdate ? "UPDATING" : "REINSTALLING";
        InstallingTitle.Text = $"Origin Launcher {_payload!.AppVersion}";
        ShowPage(InstallingPage, step: 3);
        InstallingMark.SetSpeed(3.5);
        RenderProgress();
        _progressTimer.Start();

        try
        {
            var payload = _payload!;
            var token = _cts.Token;
            await Task.Run(() => Installer.Run(payload, _self, options, (stage, fraction) =>
            {
                _stage = (int)stage;
                Interlocked.Exchange(ref _fraction, fraction);
            }, token));

            _progressTimer.Stop();
            _stage = (int)InstallStage.Done;
            Interlocked.Exchange(ref _fraction, 1);
            RenderProgress();
            await Task.Delay(450); // let the bar land before the page changes
            DoneTitle.Text = _existing == null ? "You're all set" : isUpdate ? "Origin is up to date" : "Origin is repaired";
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
        var stage = (InstallStage)_stage;
        double f = Math.Max(0, Math.Min(1, Interlocked.CompareExchange(ref _fraction, 0, 0)));
        PageMotion.Width(ProgressFill, ProgressTrack.ActualWidth > 0 ? ProgressTrack.ActualWidth * f : 380 * f);
        PercentText.Text = $"{f * 100:0}%";
        StageText.Text = stage switch
        {
            InstallStage.Preparing => "Getting ready",
            InstallStage.Unpacking => "Unpacking Origin",
            InstallStage.Finishing => "Adding shortcuts",
            _ => "Done",
        };
        // Past unpacking, files are being moved into place: no turning back.
        if (stage >= InstallStage.Finishing) CancelButton.Visibility = Visibility.Hidden;
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
            WelcomeFootnote.Text = "Install cancelled — nothing was changed.";
            ShowPage(WelcomePage, step: 1);
            return;
        }
        var first = all[0];
        if (first is SetupException se)
            ShowProblem("Origin wasn't installed", se.Message, canRetry: true);
        else if (first is InvalidDataException || first is EndOfStreamException)
            ShowProblem("This setup file is damaged", "Part of the download is missing or corrupted. Download OriginLauncher-Setup.exe again and run the new copy.", canRetry: false);
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
            if ((InstallStage)_stage <= InstallStage.Unpacking) CancelButton_Click(this, new RoutedEventArgs());
        }
    }

    // ---------- CI screenshot tour (RenderTour.cs) — puts the window in each state without doing any work ----------

    internal void TourWelcome(string? installedVersion)
    {
        _existing = installedVersion == null ? null
            : new Registration.ExistingInstall(InstallLocation.Default, installedVersion, isLegacyInno: false);
        FillWelcome(_payload!);
        ShowPage(WelcomePage, step: 1);
    }

    internal void TourCustomize(string? problem)
    {
        _existing = null;
        FillWelcome(_payload!);
        LocationBox.IsReadOnly = false;
        BrowseButton.IsEnabled = true;
        ShowPage(CustomizePage, step: 2);
        if (problem != null) ShowLocationStatus(problem, isProblem: true);
        else CheckLocation();
    }

    internal void TourInstalling(double fraction)
    {
        InstallingEyebrow.Text = "INSTALLING";
        InstallingTitle.Text = $"Origin Launcher {_payload!.AppVersion}";
        ShowPage(InstallingPage, step: 3);
        _stage = (int)InstallStage.Unpacking;
        Interlocked.Exchange(ref _fraction, fraction);
        CancelButton.Visibility = Visibility.Visible;
        RenderProgress();
    }

    internal void TourDone()
    {
        DoneTitle.Text = "You're all set";
        ShowPage(DonePage, step: 4);
    }

    internal void TourProblem() =>
        ShowProblem("Origin wasn't installed", "Origin can't write to that folder. Choose one you have access to.", canRetry: true);

    internal void TourOriginOpen()
    {
        ShowPage(WelcomePage, step: 1);
        RunningOverlay.Visibility = Visibility.Visible;
    }
}
