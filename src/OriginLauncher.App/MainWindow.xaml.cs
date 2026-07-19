using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.App.UI.Pages;

namespace OriginLauncher.App;

public partial class MainWindow : Window
{
    private readonly HomePage _homePage = new();
    private readonly SettingsPage _settingsPage = new();
    private readonly ModsPage _modsPage = new();
    private readonly AccountSwitcherPanel _accountPanel = new();
    private bool _accountPanelOpen;
    private bool _signInPanelOpen;

    // Throttles the on-focus update re-check so window focus-flapping can't spam
    // GitHub's (unauthenticated, 60/hr) releases API.
    private DateTime _lastFocusCheck = DateTime.MinValue;

    public MainWindow()
    {
        InitializeComponent();
        PageHost.Content = _homePage;

        _accountPanel.CloseRequested += (_, _) => SetAccountPanelOpen(false);
        _accountPanel.AccountsChanged += (_, _) => _homePage.RefreshAccountState();
        _accountPanel.AddAccountRequested += (_, _) => OpenSignInPanel();
        AccountPanelHost.Content = _accountPanel;

        NavHome.IsChecked = true;

        // Push-to-update: light the corner badge whenever a newer release
        // appears, and start the poll loop (startup + every 10 minutes).
        UpdateService.AvailableChanged += (_, _) => Dispatcher.Invoke(() =>
        {
            UpdateBadge.Visibility = Visibility.Visible;
            _homePage.RefreshUpdateGate();
        });
        _ = PollForUpdatesAsync();

        // Re-check the instant the launcher regains focus, so a release Will
        // just published is caught immediately instead of on the next poll tick
        // (throttled — see _lastFocusCheck).
        Activated += async (_, _) =>
        {
            var now = DateTime.UtcNow;
            if (now - _lastFocusCheck < TimeSpan.FromSeconds(30)) return;
            _lastFocusCheck = now;
            await UpdateService.CheckAsync();
        };
    }

    // Poll loop lives for the app's lifetime; process shutdown ends it. A short
    // interval keeps the corner badge close to "instant" after a publish; the
    // hard gate is the fresh re-check on the Play click, which never waits.
    private async Task PollForUpdatesAsync()
    {
        while (true)
        {
            await UpdateService.CheckAsync();
            await Task.Delay(TimeSpan.FromMinutes(2));
        }
    }

    private async void UpdateBadge_Click(object sender, RoutedEventArgs e)
    {
        UpdateBadge.IsEnabled = false;
        UpdateBadge.ToolTip = "Updating...";
        try
        {
            // Downloads + stages the new build, then shuts this process down;
            // the swap script relaunches the updated launcher.
            await UpdateService.DownloadAndRestartAsync();
        }
        catch (Exception ex)
        {
            UpdateBadge.IsEnabled = true;
            UpdateBadge.ToolTip = $"Update failed: {ex.Message} — click to retry";
        }
    }

    private void OpenSignInPanel()
    {
        if (_signInPanelOpen) return;

        _accountPanel.SetAddAccountEnabled(false);

        var signIn = new MicrosoftSignInPanel();
        signIn.SignInSucceeded += (_, result) =>
        {
            _accountPanel.CompleteSignIn(result);
            CloseSignInPanel();
        };
        signIn.SignInFailed += (_, message) =>
        {
            _accountPanel.ShowSignInError(message);
            CloseSignInPanel();
        };
        signIn.Cancelled += (_, _) => CloseSignInPanel();

        SignInPanelHost.Content = signIn;
        SetSignInPanelOpen(true);
    }

    private void CloseSignInPanel()
    {
        SetSignInPanelOpen(false);
        _accountPanel.SetAddAccountEnabled(true);
        // Dispose the WebView2 before dropping the reference — see the comment
        // on MicrosoftSignInPanel.Dispose(); without this, adding a second
        // account can find the first sign-in's browser process/user data
        // folder still locked and fail to load.
        (SignInPanelHost.Content as MicrosoftSignInPanel)?.Dispose();
        SignInPanelHost.Content = null;
    }

    private void RootGrid_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed)
            DragMove();
    }

    private void MinimizeButton_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState.Minimized;
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }

    private void NavHome_Checked(object sender, RoutedEventArgs e)
    {
        NavMods.IsChecked = false;
        NavSettings.IsChecked = false;
        // Re-evaluate Play enablement/status on return: settings that gate it
        // (e.g. offline test mode) may have changed on the Settings page.
        _homePage.RefreshAccountState();
        PageHost.Content = _homePage;
    }

    private void NavMods_Checked(object sender, RoutedEventArgs e)
    {
        NavHome.IsChecked = false;
        NavSettings.IsChecked = false;
        // Mirror Home's live version selection so the Mods tab always shows the
        // set that will actually launch — sourced from the dropdown, not disk
        // (the default selection is never persisted; see HomePage.CurrentVersion).
        _modsPage.ShowVersion(_homePage.CurrentVersion);
        PageHost.Content = _modsPage;
    }

    private void NavSettings_Checked(object sender, RoutedEventArgs e)
    {
        NavHome.IsChecked = false;
        NavMods.IsChecked = false;
        PageHost.Content = _settingsPage;
    }

    private void AccountButton_Click(object sender, RoutedEventArgs e)
    {
        SetAccountPanelOpen(!_accountPanelOpen);
    }

    private void Scrim_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        SetAccountPanelOpen(false);
    }

    private void SetAccountPanelOpen(bool open)
    {
        _accountPanelOpen = open;
        var ease = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.25 };

        AccountPanelTransform.BeginAnimation(
            System.Windows.Media.TranslateTransform.XProperty,
            new DoubleAnimation(open ? 0 : AccountPanel.Width, (Duration)FindResource("Motion.Release"))
            {
                EasingFunction = ease
            });

        Scrim.IsHitTestVisible = open;
        Scrim.BeginAnimation(
            OpacityProperty,
            new DoubleAnimation(open ? 0.5 : 0.0, (Duration)FindResource("Motion.Release")));
    }

    private void SetSignInPanelOpen(bool open)
    {
        _signInPanelOpen = open;
        var ease = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.3 };

        SignInPanel.IsHitTestVisible = open;
        SignInPanel.BeginAnimation(
            OpacityProperty,
            new DoubleAnimation(open ? 1.0 : 0.0, (Duration)FindResource("Motion.Release")));
        SignInPanelScale.BeginAnimation(
            System.Windows.Media.ScaleTransform.ScaleXProperty,
            new DoubleAnimation(open ? 1.0 : 0.94, (Duration)FindResource("Motion.Release")) { EasingFunction = ease });
        SignInPanelScale.BeginAnimation(
            System.Windows.Media.ScaleTransform.ScaleYProperty,
            new DoubleAnimation(open ? 1.0 : 0.94, (Duration)FindResource("Motion.Release")) { EasingFunction = ease });
    }
}
