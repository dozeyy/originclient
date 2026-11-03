using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Models;
using Accounts = OriginLauncher.App.Core.Accounts;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// Twin of the WPF UI/Controls/AccountSwitcherPanel.xaml.cs, the same Core calls: the list comes from
/// <see cref="Accounts.AccountStore"/>, a click selects through <c>AccountStore.Update</c>, and a finished sign-in is
/// stored ONLY through <see cref="MinecraftSessionBroker.AddSignedIn"/> (tokens never pass through this control
/// otherwise; nothing here logs or shows one).
/// </summary>
public sealed partial class AccountSwitcherPanel : UserControl
{
    public event EventHandler? CloseRequested;
    public event EventHandler? AccountsChanged;
    public event EventHandler? AccountSelected;
    public event EventHandler? AddAccountRequested;

    private List<Accounts.StoredAccount> _accounts = new();
    private readonly bool _pictureOnly;

    public AccountSwitcherPanel() : this(pictureOnly: false) { }

    /// <param name="pictureOnly">A render-shots run: the account store is never read (rows come from TourShowRows).</param>
    public AccountSwitcherPanel(bool pictureOnly)
    {
        _pictureOnly = pictureOnly;
        InitializeComponent();
        CloseButton.Click += (_, _) => CloseRequested?.Invoke(this, EventArgs.Empty);
        AddAccountButton.Click += (_, _) =>
        {
            ErrorText.IsVisible = false;
            AddAccountRequested?.Invoke(this, EventArgs.Empty);
        };
        Reload();
    }

    // Refresh whenever the drawer opens: a silent sign-in can rotate a token or update last-used while it is closed.
    public void Reload()
    {
        if (_pictureOnly) return;
        _accounts = Accounts.AccountStore.Load();
        RefreshList();
    }

    private void RefreshList() => ShowRows(_accounts
        .OrderByDescending(a => a.LastUsedUtc)
        .Select(a => new MinecraftAccount
        {
            Id = a.Id,
            Gamertag = a.Gamertag,
            LastUsedUtc = a.LastUsedUtc,
            IsSelected = a.IsSelected && !a.PendingMinecraftSignIn,
            IsPending = a.PendingMinecraftSignIn,
        })
        .ToList());

    /// <summary>Render mode only (AreaBShots): the list for made-up players. Nothing is read from or written to the store.</summary>
    internal void TourShowRows(IReadOnlyList<MinecraftAccount> rows) => ShowRows(rows);

    private void ShowRows(IReadOnlyList<MinecraftAccount> rows)
    {
        AccountList.Children.Clear();
        foreach (var account in rows) AccountList.Children.Add(BuildRow(account));
        AccountList.IsVisible = rows.Count > 0;
        EmptyStateText.IsVisible = rows.Count == 0;
    }

    // One account: the shared row surface, the head (the row's height minus 8, Radius.Xs), name + PENDING, the last-used
    // line, and ACTIVE on the selected one (framed in the accent: an indicator, never colour fill).
    private Control BuildRow(MinecraftAccount account)
    {
        var name = new TextBlock { Text = account.Gamertag, Theme = Res.Theme("Text.BodyStrong"), TextTrimming = TextTrimming.CharacterEllipsis };
        var top = new DockPanel();
        if (account.IsPending)
        {
            var pending = Badge("PENDING", 0.12, Res.Brush("Brush.Hue.Gold"), new Thickness(8, 0, 0, 0));
            DockPanel.SetDock(pending, Dock.Right);
            top.Children.Add(pending);
        }
        top.Children.Add(name);
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(top);
        text.Children.Add(new TextBlock { Text = account.Subtitle, Theme = Res.Theme("Text.Caption") });

        var head = new PlayerHead
        {
            Uuid = account.Id,
            Width = Res.D("Size.RowHead"),
            Height = Res.D("Size.RowHead"),
            Crisp = true,
            CornerRadius = Res.Radius("Radius.Xs"),
            Margin = new Thickness(0, 0, 12, 0),
        };
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto") };
        grid.Children.Add(head);
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        if (account.IsSelected)
        {
            var active = Badge("ACTIVE", 0.16, Res.Brush("Brush.Text"), new Thickness(12, 0, 2, 0));
            Grid.SetColumn(active, 2);
            grid.Children.Add(active);
        }

        var row = new Border
        {
            Theme = Res.Theme("Surface.Row"),
            Padding = Res.Thick("Padding.AccountRow"),
            CornerRadius = Res.Radius("Radius.Md"),
            Cursor = new Cursor(StandardCursorType.Hand),
            Child = grid,
        };
        if (account.IsSelected) row.BorderBrush = Res.Brush("Brush.Accent");
        row.Tapped += (_, _) => RowClicked(account);
        return row;
    }

    private static TrackedText Badge(string text, double tracking, IBrush? brush, Thickness margin) => new()
    {
        Text = text,
        Tracking = tracking,
        FontSize = Res.D("FontSize.Label"),
        FontWeight = FontWeight.Bold,
        Foreground = brush,
        VerticalAlignment = VerticalAlignment.Center,
        Margin = margin,
    };

    private void RowClicked(MinecraftAccount account)
    {
        if (_pictureOnly || !AccountList.IsEnabled) return;
        if (account.IsPending)
        {
            FinishPendingSignIn(account.Id);
            return;
        }
        try
        {
            _accounts = Accounts.AccountStore.Update(accounts =>
            {
                Accounts.AccountStore.SetSelected(accounts, account.Id);
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

    /// <summary>What the sign-in card shows after Microsoft's page (null = done, close it).</summary>
    public sealed record SignInOutcome(string Title, string Message);

    // Called by MainWindow once the sign-in card (web page or code) hands over a result. Through the broker: a full
    // sign-in is stored WITH its Minecraft session; one whose Minecraft step hit Microsoft's rate limit is kept as a
    // pending account, finished later by clicking it here. Same sentences as Windows.
    public SignInOutcome? CompleteSignIn(AuthResult result)
    {
        try
        {
            var added = MinecraftSessionBroker.AddSignedIn(result);
            _accounts = Accounts.AccountStore.Load();
            RefreshList();
            if (added.AlreadyAdded)
            {
                var name = string.IsNullOrWhiteSpace(added.Name) ? "This account" : added.Name;
                var note = $"{name} is already added, so its sign-in was refreshed. " +
                           "To add another account, sign in with a different Microsoft account.";
                ShowSignInNote(note);
                AccountsChanged?.Invoke(this, EventArgs.Empty);
                return new SignInOutcome("Already added", note);
            }
            if (result.Session == null)
            {
                const string later =
                    "Microsoft is limiting sign-ins right now. Your account is saved: in a few minutes, click it in Accounts to finish signing in.";
                ShowSignInNote(later);
                return new SignInOutcome("Almost there", later);
            }
            AccountsChanged?.Invoke(this, EventArgs.Empty);
            return null;
        }
        catch (MicrosoftAuthException ex)
        {
            ShowSignInError(ex.Message);
            return new SignInOutcome("Couldn't add this account", ex.Message);
        }
        catch (Exception)
        {
            ShowSignInError("Couldn't save this account.");
            return new SignInOutcome("Couldn't add this account", "Couldn't save this account. Please try again.");
        }
    }

    // A pending account (added while Microsoft was rate-limiting): finish its Minecraft step now. The broker refuses
    // at once, without calling Microsoft, while that account is still waiting out a 429.
    private async void FinishPendingSignIn(string pendingId)
    {
        ErrorText.IsVisible = false;
        AccountList.IsEnabled = false;
        SetAddAccountEnabled(false);
        try
        {
            var session = await MinecraftSessionBroker.FinishPendingAsync(pendingId);
            if (session == null)
            {
                MinecraftSessionBroker.RemovePending(pendingId);
                ShowSignInError("This sign-in can't be finished. Please add the account again.");
            }
            _accounts = Accounts.AccountStore.Load();
            RefreshList();
            if (session != null)
            {
                AccountsChanged?.Invoke(this, EventArgs.Empty);
                AccountSelected?.Invoke(this, EventArgs.Empty);
            }
        }
        catch (MicrosoftAuthException ex) when (ex.RateLimited)
        {
            ShowSignInError(MicrosoftAuthException.RateLimitedMessage);
        }
        catch (MicrosoftAuthException ex) when (ex.Stage == "token_refresh")
        {
            MinecraftSessionBroker.RemovePending(pendingId);
            _accounts = Accounts.AccountStore.Load();
            RefreshList();
            ShowSignInError("This sign-in expired. Please add the account again.");
        }
        catch (MicrosoftAuthException ex)
        {
            ShowSignInError(ex.Message);
        }
        catch (Exception)
        {
            ShowSignInError("Couldn't reach Microsoft. Check your internet connection and try again.");
        }
        finally
        {
            AccountList.IsEnabled = true;
            SetAddAccountEnabled(true);
        }
    }

    public void ShowSignInError(string message)
    {
        ErrorText.Foreground = Res.Brush("Brush.Danger");
        ErrorText.Text = message;
        ErrorText.IsVisible = true;
    }

    // Informational (lapis), not a failure: e.g. "already added".
    public void ShowSignInNote(string message)
    {
        ErrorText.Foreground = Res.Brush("Brush.Info");
        ErrorText.Text = message;
        ErrorText.IsVisible = true;
    }

    // The button's resting content (plus icon + label, from the XAML), put back when sign-in ends.
    private object? _addContent;

    public void SetAddAccountEnabled(bool enabled)
    {
        _addContent ??= AddAccountButton.Content;
        AddAccountButton.IsEnabled = enabled;
        AddAccountButton.Content = enabled ? _addContent : "Signing in...";
    }
}
