using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Animation;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.App.UI.Pages;

namespace OriginLauncher.App;

public partial class MainWindow : Window
{
    private readonly HomePage _homePage = new();
    private readonly SettingsPage _settingsPage = new();
    private readonly ModsPlaceholderPage _modsPage = new();
    private bool _accountPanelOpen;

    public MainWindow()
    {
        InitializeComponent();
        PageHost.Content = _homePage;

        var accountPanel = new AccountSwitcherPanel();
        accountPanel.CloseRequested += (_, _) => SetAccountPanelOpen(false);
        accountPanel.AccountsChanged += (_, _) => _homePage.RefreshAccountState();
        AccountPanelHost.Content = accountPanel;

        NavHome.IsChecked = true;
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
        PageHost.Content = _homePage;
    }

    private void NavMods_Checked(object sender, RoutedEventArgs e)
    {
        NavHome.IsChecked = false;
        NavSettings.IsChecked = false;
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
}
