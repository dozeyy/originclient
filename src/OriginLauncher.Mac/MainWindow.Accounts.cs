using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Models;
using OriginLauncher.Mac.UI;
using OriginLauncher.Mac.UI.Controls;
using Accounts = OriginLauncher.App.Core.Accounts;

namespace OriginLauncher.Mac;

// The account pill, the Accounts drawer and the Microsoft sign-in card (twin of the WPF MainWindow's account code).
// A finished sign-in goes from the card to AccountSwitcherPanel.CompleteSignIn, which stores it only through
// MinecraftSessionBroker.AddSignedIn; this file never sees a token.
public sealed partial class MainWindow
{
    private AccountSwitcherPanel _accountPanel = null!;
    private MicrosoftSignInPanel? _signIn;
    private bool _accountPanelOpen;
    private bool _signInPanelOpen;
    private readonly TranslateTransform _accountPanelShift = new();
    private readonly ScaleTransform _signInScale = new(0.97, 0.97);

    /// <summary>Raised after the selected account changes (switch, add, finish): Home re-reads who can play.</summary>
    internal event EventHandler? AccountsChanged;

    private void InitAccounts()
    {
        // A render-shots run pictures the drawer with made-up rows: the account store is never read there.
        _accountPanel = new AccountSwitcherPanel(pictureOnly: OriginPaths.IsScratchRoot);
        AccountPanel.RenderTransform = _accountPanelShift;
        _accountPanelShift.X = UI.Res.D("Size.Drawer");
        SignInPanel.RenderTransform = _signInScale;
        AccountPanelHost.Content = _accountPanel;

        _accountPanel.CloseRequested += (_, _) => SetAccountPanelOpen(false);
        _accountPanel.AccountsChanged += (_, _) =>
        {
            RefreshAccountChip();
            // Every account has its own game folders: the Mods page follows the selected account.
            if (_currentPage == LauncherPage.Mods) ModsView.ShowVersion(HomeVersion);
            AccountsChanged?.Invoke(this, EventArgs.Empty);
            // Friends belong to the selected account: sign in again as it.
            OriginLauncher.App.Core.Social.Launcher.SocialHub.Service.ResetAccount();
        };
        _accountPanel.AccountSelected += (_, _) => SetAccountPanelOpen(false);
        _accountPanel.AddAccountRequested += (_, _) => OpenSignInPanel();
        Home.SignInAgainRequested += name => _ = AskToSignInAgainAsync(name);

        AccountButton.Click += (_, _) =>
        {
            if (!_accountPanelOpen && _friendsOpen) SetFriendsOpen(false);
            SetAccountPanelOpen(!_accountPanelOpen);
        };
        Scrim.PointerPressed += (_, _) =>
        {
            if (_signInPanelOpen) CloseSignInPanel();
            else if (_friendsOpen) SetFriendsOpen(false);
            else SetAccountPanelOpen(false);
        };
        AddHandler(KeyDownEvent, (_, e) =>
        {
            if (e.Key != Key.Escape) return;
            if (_signInPanelOpen) { CloseSignInPanel(); e.Handled = true; }
            else if (_accountPanelOpen) { SetAccountPanelOpen(false); e.Handled = true; }
        }, RoutingStrategies.Tunnel);

        RefreshAccountChip();
    }

    // The pill: the selected account's head and name, "Offline" in offline test mode (dev builds), "Sign in" (a ghost
    // control with the account icon) when nobody can play. Reads the account list only.
    private void RefreshAccountChip()
    {
        if (OriginPaths.IsScratchRoot) { ShowAccountChip(null, "", false); return; }
        var selected = Accounts.AccountStore.GetSelected(Accounts.AccountStore.Load());
        ShowAccountChip(selected?.Gamertag, selected?.Id ?? "", SettingsStore.Load().OfflineTestMode);
    }

    private void ShowAccountChip(string? name, string uuid, bool offlineTestMode)
    {
        var offline = name == null && offlineTestMode;
        var signedOut = name == null && !offline;
        AccountButton.Theme = UI.Res.Theme(signedOut ? "Button.AccountPill.SignedOut" : "Button.AccountPill");
        AccountName.Text = name ?? (offline ? "Offline" : "Sign in");
        AccountFace.Uuid = uuid;
        AccountFace.IsVisible = !signedOut;
        SignInIcon.IsVisible = signedOut;
        // The full name, shown only as a tooltip (the pill cuts a long one with "...").
        ToolTip.SetTip(AccountButton, name);
    }

    private void SetAccountPanelOpen(bool open)
    {
        if (open && _signInPanelOpen) return;
        _accountPanelOpen = open;
        var ms = (int)(open ? UI.Res.Get<TimeSpan>("Motion.Scene") : UI.Res.Get<TimeSpan>("Motion.Exit")).TotalMilliseconds;
        if (open)
        {
            _accountPanel.Reload();
            Scrim.IsVisible = true;
            AccountPanel.IsVisible = true;
        }
        AccountPanel.IsHitTestVisible = open;
        Scrim.IsHitTestVisible = open;
        MotionFx.Run(Scrim, OpacityProperty, Scrim.Opacity, open ? 0.5 : 0, ms);
        MotionFx.Run(_accountPanelShift, TranslateTransform.XProperty, _accountPanelShift.X, open ? 0 : UI.Res.D("Size.Drawer"), ms)
            .ContinueWith(_ => Avalonia.Threading.Dispatcher.UIThread.Post(() =>
            {
                if (_accountPanelOpen) return;
                AccountPanel.IsVisible = false;
                if (!_signInPanelOpen && !_friendsOpen) Scrim.IsVisible = false;
            }));
    }

    // Play failed on an expired sign-in: ask once, and "Sign in again" goes straight to the sign-in card (adding an
    // account that is already there refreshes it; nothing has to be removed). The WPF MainWindow's twin.
    private async Task AskToSignInAgainAsync(string accountName)
    {
        if (_signInPanelOpen) return;
        if (await Confirm.ShowAsync(SignInAgain.Title, SignInAgain.Prompt(accountName), SignInAgain.Button, SignInAgain.Later)
            && !_signInPanelOpen)
            OpenSignInPanel();
    }

    private void OpenSignInPanel()
    {
        if (_signInPanelOpen) return;
        _accountPanel.SetAddAccountEnabled(false);
        var signIn = new MicrosoftSignInPanel(pictureOnly: OriginPaths.IsScratchRoot);
        signIn.SignInSucceeded += (_, result) =>
        {
            // Done: close the card and the drawer. Otherwise (saved for later, already added, couldn't save) the card
            // stays up with the reason until the player presses OK; it never just vanishes.
            if (_accountPanel.CompleteSignIn(result) is { } outcome)
            {
                signIn.ShowMessage(outcome.Title, outcome.Message);
                return;
            }
            CloseSignInPanel();
            SetAccountPanelOpen(false);
        };
        signIn.SignInFailed += (_, message) =>
        {
            _accountPanel.ShowSignInError(message);
            signIn.ShowMessage("Couldn't add this account", message);
        };
        signIn.Cancelled += (_, _) => CloseSignInPanel();
        ShowSignInCard(signIn);
    }

    private void ShowSignInCard(MicrosoftSignInPanel signIn)
    {
        _signIn?.Dispose();
        _signIn = signIn;
        SignInPanelHost.Content = signIn;
        SetSignInPanelOpen(true);
    }

    private void CloseSignInPanel()
    {
        SetSignInPanelOpen(false);
        _accountPanel.SetAddAccountEnabled(true);
        // Each card is thrown away with its web view (and its non-persistent cookie store): the next "Add Microsoft
        // account" starts empty, so a second account can never be signed in as the first.
        _signIn?.Dispose();
        _signIn = null;
        SignInPanelHost.Content = null;
    }

    private void SetSignInPanelOpen(bool open)
    {
        _signInPanelOpen = open;
        var ms = (int)(open ? UI.Res.Get<TimeSpan>("Motion.Scene") : UI.Res.Get<TimeSpan>("Motion.Exit")).TotalMilliseconds;
        // As on Windows, the card brings no dimming of its own: it opens from the Accounts drawer, whose scrim stays.
        if (open)
        {
            SignInPanel.IsVisible = true;
            Scrim.IsVisible = true;
        }
        SignInPanel.IsHitTestVisible = open;
        MotionFx.Run(_signInScale, ScaleTransform.ScaleXProperty, _signInScale.ScaleX, open ? 1 : 0.97, ms);
        MotionFx.Run(_signInScale, ScaleTransform.ScaleYProperty, _signInScale.ScaleY, open ? 1 : 0.97, ms);
        MotionFx.Run(SignInPanel, OpacityProperty, SignInPanel.Opacity, open ? 1 : 0, ms)
            .ContinueWith(_ => Avalonia.Threading.Dispatcher.UIThread.Post(() =>
            {
                if (_signInPanelOpen) return;
                SignInPanel.IsVisible = false;
                if (!_accountPanelOpen && !_friendsOpen) Scrim.IsVisible = false;
            }));
    }

    // ---- Render shots (AreaBShots): made-up players, no store read, no sign-in, no network ----

    internal void TourShowAccount(string? name, string uuid, bool offlineTestMode)
    {
        if (!OriginPaths.IsScratchRoot) return;
        ShowAccountChip(name, uuid, offlineTestMode);
    }

    internal void TourSetAccountOpen(bool open)
    {
        if (!OriginPaths.IsScratchRoot) return;
        SetAccountPanelOpen(open);
    }

    internal void TourShowAccountRows(IReadOnlyList<MinecraftAccount> rows)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _accountPanel.TourShowRows(rows);
    }

    // The card as a player sees it while Microsoft's page loads, with an outcome, or showing a (made-up) code. The
    // card skips its web view in a scratch-root run, so nothing loads and nothing goes online.
    internal void TourShowSignIn(string? title, string? message, string? demoCode = null)
    {
        if (!OriginPaths.IsScratchRoot) return;
        var signIn = new MicrosoftSignInPanel(pictureOnly: true);
        if (title != null && message != null) signIn.ShowMessage(title, message);
        if (demoCode != null) signIn.TourShowCode(demoCode, "https://www.microsoft.com/link");
        ShowSignInCard(signIn);
    }

    internal void TourCloseSignIn() => CloseSignInPanel();

    internal Avalonia.Controls.Button TourAccountButton => AccountButton;
    internal Avalonia.Controls.Button TourStoreButton => StoreButton;

    /// <summary>Render shots: the pill's measured size for the shot log (twin of the WPF TourAccountFit, the parts the Mac has).</summary>
    internal string TourAccountFit() =>
        $"pill={AccountButton.Bounds.Width:0.#}x{AccountButton.Bounds.Height:0.#} "
        + (AccountFace.IsVisible ? $"head={AccountFace.Bounds.Width:0.#}x{AccountFace.Bounds.Height:0.#}" : "head=none (signed out)")
        + $" name={AccountName.Bounds.Width:0.#} storeLabel={StoreLabel.IsVisible} wordmark={BarWordmark.IsVisible} {TopBarFit()}";
}
