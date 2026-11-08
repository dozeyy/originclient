using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// The account drawer (WPF UI/Controls/AccountSwitcherPanel): every Microsoft account on this launcher, newest
// use first. Click one to make it the active account; a "pending" one (Microsoft was rate-limiting when it was
// added) finishes its Minecraft step on click. The trash button on a row (shown on hover or keyboard focus)
// removes the account after a confirm; its tokens live inside its AccountStore entry, so they go with it.
// "Add Microsoft account" opens the sign-in card (SignInPanel). Every write goes through Core's AccountStore /
// MinecraftSessionBroker, then LauncherState.ReloadAccounts (the top bar and Home listen to AccountsChanged).
public sealed class AccountDrawer : Panel
{
    private readonly MainWindow _window;
    private readonly StackPanel _rows = new() { Spacing = Space.S1 };
    private readonly TextBlock _note = Ui.Wrap(Ui.Caption(""));
    private readonly Border _noteBox;
    private readonly Pressable _add;
    private readonly TextBlock _addLabel;
    private bool _busy;

    /// <summary>The drawer on screen right now, so the sign-in card can report back into it.</summary>
    public static AccountDrawer? Open { get; private set; }

    public AccountDrawer(MainWindow window)
    {
        _window = window;

        var close = Ui.IconButton(Icons.Close, "Close (Esc)");
        close.Click += (_, _) => _window.CloseDrawer();
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(Space.S4, Space.S4, Space.S4, 10) };
        header.Children.Add(Ui.Column(2, Ui.Heading("Accounts"), Ui.Caption("One game window per account")).At(0));
        header.Children.Add(close.At(1).Align(HorizontalAlignment.Right, VerticalAlignment.Top));

        _noteBox = new Border
        {
            IsVisible = false, Margin = new Thickness(0, 0, 0, Space.S2), Padding = new Thickness(10, Space.S2),
            CornerRadius = new CornerRadius(Radius.Sm), Child = _note,
        };

        _addLabel = Ui.Text("Add Microsoft account", TypeScale.Body, FontWeight.SemiBold);
        _add = new Pressable(Look.Primary, Ui.Row(Space.S2, Ui.Icon(Icons.Plus), _addLabel), "Add Microsoft account")
        {
            Height = Sizes.ControlLg, CornerRadius = new CornerRadius(Radius.Md), Margin = new Thickness(0, Space.S3, 0, 0),
        }.Center();
        _add.Click += (_, _) => { if (!_busy) SignInPanel.Open(_window); };

        var body = Ui.Column(0, _noteBox, _rows, _add, KeepsBox());
        body.Margin = new Thickness(Space.S4, 0, Space.S4, Space.S4);
        var scroll = new ScrollViewer { Content = body, HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled };

        var layout = new Grid { RowDefinitions = new RowDefinitions("Auto,*") };
        layout.Children.Add(header.At(0, 0));
        layout.Children.Add(scroll.At(0, 1));
        Children.Add(layout);

        Refresh();
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        Open = this;
        LauncherState.AccountsChanged += Refresh;
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        if (Open == this) Open = null;
        LauncherState.AccountsChanged -= Refresh;
    }

    // ---- The list ----

    /// <summary>Rebuild the rows from the cached account list (cheap: a handful of rows, no disk read).</summary>
    public void Refresh()
    {
        _rows.Children.Clear();
        var accounts = LauncherState.Accounts.OrderByDescending(a => a.LastUsedUtc).ToList();
        if (accounts.Count == 0)
        {
            _rows.Children.Add(Ui.Wrap(Ui.Body("No accounts added yet. Add a Microsoft account to sign in."))
                .Margin(2, Space.S1, 2, Space.S1));
            return;
        }
        foreach (var account in accounts) _rows.Children.Add(Row(account));
    }

    /// <summary>The rows on screen, top first (render runs force a hover on one).</summary>
    public IReadOnlyList<Pressable> Rows => _rows.Children.OfType<Pressable>().ToList();

    private Pressable Row(StoredAccount account)
    {
        var pending = account.PendingMinecraftSignIn;
        var active = account.IsSelected && !pending;
        var name = string.IsNullOrWhiteSpace(account.Gamertag) ? "Microsoft account" : account.Gamertag;
        var caption = pending ? "Click to finish signing in"
            : account.LastUsedUtc is { } used && used > DateTimeOffset.MinValue
                ? "Last used " + used.ToLocalTime().ToString("MMM d, yyyy", System.Globalization.CultureInfo.InvariantCulture)
                : "Not used yet";

        var nameText = Ui.Ellipsis(Ui.BodyStrong(name));
        var text = Ui.Column(1, nameText, Ui.Ellipsis(Ui.Caption(caption)));
        text.VerticalAlignment = VerticalAlignment.Center;

        var remove = Ui.IconButton(Icons.Trash, "Remove " + name, Sizes.ControlSm);
        remove.IsVisible = false;

        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto"), ColumnSpacing = Space.S3 };
        grid.Children.Add(SocialKit.Head(pending ? null : account.Id, Sizes.AccountHead).At(0));
        grid.Children.Add(text.At(1));
        if (pending || active)
            grid.Children.Add(Ui.Text(pending ? "PENDING" : "ACTIVE", TypeScale.Label, FontWeight.Bold,
                    pending ? Palette.HueGold : Palette.Text, pending ? 0.12 : 0.16)
                .At(2).Align(HorizontalAlignment.Right));
        grid.Children.Add(remove.At(3));

        var row = new Pressable(Look.Plain, grid, pending ? name + ", finish signing in" : "Switch to " + name)
        {
            Padding = new Thickness(3, 3, 8, 3), CornerRadius = new CornerRadius(Radius.Md), BorderThickness = new Thickness(1),
            IsSelected = active, Tag = account.Id,
        };
        row.StateChanged += p =>
        {
            var hot = (p.IsHovered || p.ForceHover) && p.IsEffectivelyEnabled;
            p.Background = hot ? Palette.Card : Palette.Bg;
            p.BorderBrush = p.IsSelected ? Palette.Accent : hot ? Palette.LineStrong : Palette.Line;
            p.Opacity = p.IsEffectivelyEnabled ? 1 : 0.5;
            TextElement.SetForeground(p, Palette.Text);
            remove.IsVisible = hot || p.FocusVisible || p.ForceFocus || remove.IsKeyboardFocusWithin;
        };
        row.Apply();
        row.Click += async (_, _) => await ChooseAsync(account);
        remove.Click += async (_, _) => await RemoveAsync(account, name);
        return row;
    }

    private static Border KeepsBox()
    {
        Control Line(string glyph, string text) =>
            Ui.Row(Space.S3, Ui.Icon(glyph, Sizes.Icon, Palette.TextDim), Ui.Wrap(Ui.Body(text)));
        return new Border
        {
            Background = Palette.BgAlt, CornerRadius = new CornerRadius(Radius.Lg), Padding = new Thickness(Space.S4),
            Margin = new Thickness(0, Space.S4, 0, 0),
            Child = Ui.Column(10,
                Ui.Eyebrow("Each account keeps its own".ToUpperInvariant(), Palette.TextMuted, 0.16),
                Line(Icons.Globe, "Worlds, servers and settings"),
                Line(Icons.Account, "Game window, so several can play at once"),
                Line(Icons.Jar, "Mods stay shared per version")),
        };
    }

    // ---- Actions ----

    private async Task ChooseAsync(StoredAccount account)
    {
        if (_busy) return;
        if (account.PendingMinecraftSignIn)
        {
            await FinishPendingAsync(account.Id);
            return;
        }
        try
        {
            AccountStore.Update(list =>
            {
                AccountStore.SetSelected(list, account.Id);
                return list;
            });
        }
        catch (Exception)
        {
            ShowNote("Couldn't switch accounts.", error: true);
            return;
        }
        AccountsWritten();
        _window.CloseDrawer();
    }

    private async Task FinishPendingAsync(string pendingId)
    {
        SetBusy(true);
        string? error = null;
        try
        {
            // Off the UI thread: reading the stored sign-in goes through the desktop keyring (Core LinuxKeyStore runs
            // secret-tool), which can wait on an unlock prompt; the window must keep drawing meanwhile.
            var session = await Task.Run(() => MinecraftSessionBroker.FinishPendingAsync(pendingId));
            if (session == null)
            {
                MinecraftSessionBroker.RemovePending(pendingId);
                error = "This sign-in can't be finished. Please add the account again.";
            }
        }
        catch (MicrosoftAuthException ex) when (ex.RateLimited)
        {
            error = MicrosoftAuthException.RateLimitedMessage;
        }
        catch (MicrosoftAuthException ex) when (ex.Stage == "token_refresh")
        {
            MinecraftSessionBroker.RemovePending(pendingId);
            error = "This sign-in expired. Please add the account again.";
        }
        catch (MicrosoftAuthException ex)
        {
            error = ex.Message;
        }
        catch (Exception)
        {
            error = "Couldn't reach Microsoft. Check your internet connection and try again.";
        }
        SetBusy(false);
        AccountsWritten();
        if (error != null) ShowNote(error, error: true);
        else _window.CloseDrawer();
    }

    private async Task RemoveAsync(StoredAccount account, string name)
    {
        if (_busy) return;
        var yes = await Confirm.AskAsync(_window, $"Remove {name}?",
            "Its sign-in is deleted from this launcher. Its worlds and settings stay on this computer, and you can add it again later.",
            "Remove", danger: true);
        if (!yes) return;
        try
        {
            // Core's one removal path (the Windows drawer's too): the row and its tokens go, the cached Minecraft session
            // is forgotten, and the most recently used account that can play takes over if this was the active one.
            MinecraftSessionBroker.Remove(account.Id);
        }
        catch (Exception)
        {
            ShowNote("Couldn't remove this account. Please try again.", error: true);
            return;
        }
        AccountsWritten();
        ShowNote($"{name} was removed.", error: false);
    }

    /// <summary>After any account write: every view re-reads the list, friends sign in again as the new account.</summary>
    internal static void AccountsWritten()
    {
        LauncherState.ReloadAccounts();
        MainWindow.Current?.Bar.RefreshAccount();
        if (!Shots.ShotRunner.Active) SocialHub.Service.ResetAccount();
    }

    // ---- What the sign-in card reports back ----

    /// <summary>A line above the list: an info note, or an error in red.</summary>
    public void ShowNote(string text, bool error)
    {
        _note.Text = text;
        _note.Foreground = error ? Palette.DangerText : Palette.TextDim;
        _noteBox.Background = error ? Palette.DangerSoft : Palette.Card;
        _noteBox.IsVisible = text.Length > 0;
    }

    /// <summary>While a sign-in finishes: the list and the add button wait.</summary>
    public void SetBusy(bool busy)
    {
        _busy = busy;
        _rows.IsEnabled = !busy;
        _add.IsEnabled = !busy;
        _addLabel.Text = busy ? "Signing in..." : "Add Microsoft account";
    }

    public sealed record Outcome(string Title, string Text);

    /// <summary>
    /// Store a finished Microsoft sign-in (WPF AccountSwitcherPanel.CompleteSignIn): null = added and selected
    /// (close everything); otherwise what to tell the player. Tokens are encrypted by Core's per-OS key store
    /// inside AddSignedIn before they reach disk. That store runs on a pool thread: on Linux it is the desktop keyring
    /// (secret-tool, up to four runs with a 15 s limit each), and a locked keyring shows its unlock prompt while the
    /// launcher must keep drawing (Windows' DPAPI answers at once, so its drawer can stay synchronous).
    /// </summary>
    public static async Task<Outcome?> SaveAsync(AuthResult result)
    {
        try
        {
            var added = await Task.Run(() => MinecraftSessionBroker.AddSignedIn(result));
            AccountsWritten();
            if (added.AlreadyAdded)
            {
                var name = string.IsNullOrWhiteSpace(added.Name) ? "This account" : added.Name;
                var note = $"{name} is already added, so its sign-in was refreshed. " +
                           "To add another account, sign in with a different Microsoft account.";
                Open?.ShowNote(note, error: false);
                return new Outcome("Already added", note);
            }
            if (result.Session == null)
            {
                const string later = "Microsoft is limiting sign-ins right now. Your account is saved: in a few minutes, " +
                                     "click it in Accounts to finish signing in.";
                Open?.ShowNote(later, error: false);
                return new Outcome("Almost there", later);
            }
            return null;
        }
        catch (MicrosoftAuthException ex)
        {
            Open?.ShowNote(ex.Message, error: true);
            return new Outcome("Couldn't add this account", ex.Message);
        }
        catch (Exception)
        {
            Open?.ShowNote("Couldn't save this account.", error: true);
            return new Outcome("Couldn't add this account", "Couldn't save this account. Please try again.");
        }
    }
}
