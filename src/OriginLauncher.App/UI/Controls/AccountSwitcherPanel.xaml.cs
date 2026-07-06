using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.UI.Controls;

public partial class AccountSwitcherPanel : UserControl
{
    public event EventHandler? CloseRequested;
    public event EventHandler? AccountsChanged;

    private List<StoredAccount> _accounts = AccountStore.Load();

    public AccountSwitcherPanel()
    {
        InitializeComponent();
        RefreshList();
    }

    private void RefreshList()
    {
        var viewModels = _accounts
            .OrderByDescending(a => a.LastUsedUtc)
            .Select(a => new MinecraftAccount
            {
                Id = a.Id,
                Gamertag = a.Gamertag,
                LastUsedUtc = a.LastUsedUtc,
                IsSelected = a.IsSelected
            })
            .ToList();

        AccountList.ItemsSource = viewModels;
        AccountList.Visibility = viewModels.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptyStateText.Visibility = viewModels.Count > 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    private void CloseButton_Click(object sender, RoutedEventArgs e)
    {
        CloseRequested?.Invoke(this, EventArgs.Empty);
    }

    private void AccountRow_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (((FrameworkElement)sender).DataContext is not MinecraftAccount account) return;

        AccountStore.SetSelected(_accounts, account.Id);
        AccountStore.Save(_accounts);
        RefreshList();
        AccountsChanged?.Invoke(this, EventArgs.Empty);
    }

    private async void AddAccountButton_Click(object sender, RoutedEventArgs e)
    {
        ErrorText.Visibility = Visibility.Collapsed;
        AddAccountButton.IsEnabled = false;
        AddAccountButton.Content = "SIGNING IN...";

        try
        {
            var result = await new MicrosoftAuthenticator().SignInAsync();

            var stored = new StoredAccount
            {
                Id = result.Session.UUID ?? "",
                Gamertag = result.Session.Username ?? "",
                LastUsedUtc = DateTimeOffset.UtcNow,
                ProtectedRefreshToken = AccountStore.ProtectRefreshToken(result.MsaRefreshToken)
            };

            _accounts = AccountStore.Upsert(_accounts, stored);
            AccountStore.Save(_accounts);
            RefreshList();
            AccountsChanged?.Invoke(this, EventArgs.Empty);
        }
        catch (MicrosoftAuthException ex)
        {
            ErrorText.Text = ex.Message;
            ErrorText.Visibility = Visibility.Visible;
        }
        catch (Exception ex)
        {
            ErrorText.Text = $"Sign-in failed: {ex.Message}";
            ErrorText.Visibility = Visibility.Visible;
        }
        finally
        {
            AddAccountButton.IsEnabled = true;
            AddAccountButton.Content = "ADD MICROSOFT ACCOUNT";
        }
    }
}
