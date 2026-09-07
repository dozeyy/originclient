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
    public event EventHandler? AccountSelected;
    public event EventHandler? AddAccountRequested;

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

        try
        {
            _accounts = AccountStore.Update(accounts =>
            {
                AccountStore.SetSelected(accounts, account.Id);
                return accounts;
            });
            RefreshList();
            AccountsChanged?.Invoke(this, EventArgs.Empty);
            AccountSelected?.Invoke(this, EventArgs.Empty);
        }
        catch (Exception)
        {
            ShowSignInError("Couldn't switch accounts.");
        }
    }

    private void AddAccountButton_Click(object sender, RoutedEventArgs e)
    {
        ErrorText.Visibility = Visibility.Collapsed;
        AddAccountRequested?.Invoke(this, EventArgs.Empty);
    }

    // Called by MainWindow once the embedded sign-in page (see
    // MicrosoftSignInPanel) completes the MSA -> Xbox Live -> XSTS ->
    // Minecraft chain successfully.
    public bool CompleteSignIn(AuthResult result)
    {
        try
        {
            var stored = new StoredAccount
            {
                Id = result.Session.UUID ?? "",
                Gamertag = result.Session.Username ?? "",
                LastUsedUtc = DateTimeOffset.UtcNow,
                ProtectedRefreshToken = AccountStore.ProtectRefreshToken(result.MsaRefreshToken)
            };
            _accounts = AccountStore.Update(accounts => AccountStore.Upsert(accounts, stored));
            RefreshList();
            AccountsChanged?.Invoke(this, EventArgs.Empty);
            return true;
        }
        catch (Exception)
        {
            ShowSignInError("Couldn't save this account.");
            return false;
        }
    }

    // Refresh whenever the flyout opens: silent sign-in can rotate a token or
    // update last-used while this long-lived control is closed.
    public void Reload()
    {
        _accounts = AccountStore.Load();
        RefreshList();
    }

    public void ShowSignInError(string message)
    {
        ErrorText.Text = message;
        ErrorText.Visibility = Visibility.Visible;
    }

    public void SetAddAccountEnabled(bool enabled)
    {
        AddAccountButton.IsEnabled = enabled;
        AddAccountButton.Content = enabled ? "ADD MICROSOFT ACCOUNT" : "SIGNING IN...";
    }
}
