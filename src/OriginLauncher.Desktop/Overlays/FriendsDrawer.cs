using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Controls.Shapes;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;
using Entry = OriginLauncher.Desktop.Overlays.SocialKit.MenuEntry;

namespace OriginLauncher.Desktop.Overlays;

// The friends drawer (WPF UI/Controls/FriendsPanel + FriendsViewModels): Core's SocialService does all the talking
// (sign-in, sync loop, actions); this view only draws its snapshot and calls its actions. Top to bottom: the header
// (back / add a friend / close), the add row, a notice strip, then either a message pane (signed out, failed,
// offline, connecting), the empty pane, the list (warp, requests, party card, online, offline, sent, blocked) or a
// chat (SocialChat), and the "Appear offline" footer. Rows are cached by key and signature (RowCache), so a sync
// that changes nothing moves no control and keeps hover. Esc: the add row, then back from a chat, then close.
public sealed class FriendsDrawer : Panel
{
    private static SocialService Service => SocialHub.Service;
    private const int PartyMax = 8;

    private readonly MainWindow _window;
    private readonly Pressable _back, _addToggle, _more;
    private readonly TextBlock _title = Ui.Ellipsis(Ui.Heading("Friends"));
    private readonly TextBlock _subtitle = Ui.Ellipsis(Ui.Caption(""));
    private readonly Ellipse _titleDot = Ui.Dot(Palette.Success);
    private readonly Grid _addRow;
    private readonly TextBox _addBox;
    private readonly Pressable _addSend;
    private readonly Border _notice;
    private readonly TextBlock _noticeText = Ui.Wrap(Ui.Caption(""));
    private readonly Panel _noticeIcon = new();
    private readonly Border _stale;
    private readonly Panel _content = new();
    private readonly ScrollViewer _listScroll;
    private readonly StackPanel _list = new();
    private readonly Control _messagePane;
    private readonly ContentControl _messageHost = new();
    private readonly SocialChat _chat;
    private readonly Border _footer;
    private readonly Toggle _appear = new("Appear offline");
    private readonly TextBlock _appearCaption = Ui.Caption("Friends see you as online", Palette.TextMuted);
    private readonly RowCache _rows = new();
    private readonly HashSet<string> _running = new(StringComparer.Ordinal);
    private IDisposable? _noticeTimer;
    private bool _attached;
    private SocialSnapshot _s = new();
    private string? _open;
    private bool _offlineCollapsed;

    /// <summary>The friends drawer on screen right now (toasts and the bar ask it before opening another).</summary>
    public static FriendsDrawer? Shown { get; private set; }

    public FriendsDrawer(MainWindow window)
    {
        _window = window;

        // ---- Header ----
        _back = Ui.IconButton(Icons.Back, "Back (Esc)");
        _back.Click += (_, _) => OpenChat(null);
        _back.Margin = new Thickness(-6, 0, Space.S1, 0);
        _addToggle = Ui.IconButton(Icons.UserPlus, "Add a friend");
        _addToggle.Click += (_, _) => SetAddOpen(!_addRow!.IsVisible);
        _more = Ui.IconButton(Icons.More, "More");
        _more.Click += (_, _) => { if (_open is { } key && key != SocialSnapshot.PartyKey) ShowFriendMenu(_more, key); };
        var close = Ui.IconButton(Icons.Close, "Close (Esc)");
        close.Click += (_, _) => _window.CloseDrawer();

        var subRow = Ui.Row(6, _titleDot, _subtitle);
        var titles = Ui.Column(2, _title, subRow);
        titles.VerticalAlignment = VerticalAlignment.Center;
        var header = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto,Auto"),
            Margin = new Thickness(Space.S4, Space.S4, Space.S3, 10),
        };
        header.Children.Add(_back.At(0));
        header.Children.Add(titles.At(1));
        header.Children.Add(_addToggle.At(2));
        header.Children.Add(_more.At(3));
        header.Children.Add(close.At(4));

        // ---- Add a friend ----
        _addBox = new TextBox
        {
            Height = Sizes.ControlMd, CornerRadius = new CornerRadius(Radius.Sm), FontSize = TypeScale.Body,
            VerticalContentAlignment = VerticalAlignment.Center, PlaceholderText = "Minecraft username", MaxLength = 16,
        };
        _addSend = Ui.Button("Send request", Look.Primary, height: Sizes.ControlMd);
        _addSend.IsEnabled = false;
        _addBox.TextChanged += (_, _) =>
        {
            _addSend.IsEnabled = SocialText.IsValidUsername(_addBox.Text?.Trim());
            _addBox.BorderBrush = null;
        };
        _addBox.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter) { e.Handled = true; _ = AddFriendAsync(); }
            else if (e.Key == Key.Escape) { e.Handled = true; SetAddOpen(false); }
        };
        _addSend.Click += (_, _) => _ = AddFriendAsync();
        _addRow = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"), ColumnSpacing = Space.S2, IsVisible = false,
            Margin = new Thickness(Space.S4, 0, Space.S4, Space.S3),
        };
        _addRow.Children.Add(_addBox.At(0));
        _addRow.Children.Add(_addSend.At(1));

        // ---- Strips ----
        var noticeRow = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*"), ColumnSpacing = Space.S2 };
        noticeRow.Children.Add(_noticeIcon.At(0));
        noticeRow.Children.Add(_noticeText.At(1));
        _notice = SocialKit.Strip(noticeRow);
        _notice.IsVisible = false;
        _notice.Cursor = new Cursor(StandardCursorType.Hand);
        _notice.PointerPressed += (_, e) => { _notice.IsVisible = false; e.Handled = true; };
        _stale = SocialKit.Strip(Ui.Row(Space.S2, Ui.Icon(Icons.Alert, Sizes.IconSm, Palette.Warning),
            Ui.Wrap(Ui.Caption("Can't reach friends. Showing what Origin last saw."))));
        _stale.IsVisible = false;

        // ---- Content ----
        _list.Margin = new Thickness(Space.S4, 0, Space.S4, Space.S4);
        _listScroll = new ScrollViewer { Content = _list, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        _messagePane = _messageHost;
        _chat = new SocialChat(this);
        _content.Children.Add(_listScroll);
        _content.Children.Add(_messageHost);
        _content.Children.Add(_chat);

        // ---- Footer ----
        _appear.Toggled += on => _ = SetAppearOfflineAsync(on);
        var footerGrid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        footerGrid.Children.Add(Ui.Column(2, Ui.Body("Appear offline", Palette.Text), _appearCaption).At(0));
        footerGrid.Children.Add(_appear.At(1).Align(HorizontalAlignment.Right).Tip("Friends see you as offline while this is on"));
        _footer = new Border
        {
            BorderBrush = Palette.Line, BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(Space.S4, Space.S3), Child = footerGrid,
        };

        var layout = new Grid { RowDefinitions = new RowDefinitions("Auto,Auto,Auto,Auto,*,Auto") };
        layout.Children.Add(header.At(0, 0));
        layout.Children.Add(_addRow.At(0, 1));
        layout.Children.Add(_notice.At(0, 2));
        layout.Children.Add(_stale.At(0, 3));
        layout.Children.Add(_content.At(0, 4));
        layout.Children.Add(_footer.At(0, 5));
        Children.Add(layout);

        Render(Service.Snapshot);
    }

    // ---- Lifetime: the service knows the drawer is open (no friend-request / invite toasts meanwhile) ----

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        Shown = this;
        _attached = true;
        Service.Changed += OnChanged;
        Service.SetDrawerOpen(true);
        Service.SetOpenConversation(_open);
        SocialToasts.Of(_window).DrawerChanged();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        if (Shown == this) Shown = null;
        _attached = false;
        Service.Changed -= OnChanged;
        Service.SetDrawerOpen(false);
        Service.SetOpenConversation(null);
        _noticeTimer?.Dispose();
        SocialToasts.Of(_window).DrawerChanged();
    }

    private void OnChanged(SocialSnapshot s)
    {
        if (Dispatcher.UIThread.CheckAccess()) Render(s);
        else Dispatcher.UIThread.Post(() => Render(Service.Snapshot));
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (e.Handled || e.Key != Key.Escape || _window.DialogOpen) return;
        if (_addRow.IsVisible) SetAddOpen(false);
        else if (_open != null) OpenChat(null);
        else return;
        e.Handled = true;
    }

    // ---- Opening it ----

    /// <summary>Open the drawer (or reuse the open one), optionally straight into a chat (a uuid or the party key).</summary>
    public static FriendsDrawer OpenOn(MainWindow window, string? chat = null)
    {
        var drawer = Shown;
        if (drawer == null)
        {
            drawer = new FriendsDrawer(window);
            window.OpenDrawer(drawer);
        }
        if (chat != null) drawer.OpenChat(chat);
        return drawer;
    }

    /// <summary>Show a chat (uuid or PartyKey), or the list again (null).</summary>
    public void OpenChat(string? key)
    {
        _open = key;
        if (key != null) SetAddOpen(false);
        if (_attached) Service.SetOpenConversation(key);
        _chat.ResetFor(key);
        Render(Service.Snapshot);
    }

    private void SetAddOpen(bool open)
    {
        _addRow.IsVisible = open;
        _addToggle.IsSelected = open;
        if (open) Dispatcher.UIThread.Post(() => _addBox.Focus(), DispatcherPriority.Input);
        else { _addBox.Text = ""; _addBox.BorderBrush = null; }
    }

    // ---- Render ----

    private void Render(SocialSnapshot s)
    {
        _s = s;
        if (_open == SocialSnapshot.PartyKey && s.Party == null) _open = null;
        if (_open != null && _open != SocialSnapshot.PartyKey && s.Friend(_open) == null && s.Status == SocialStatus.Online && s.HasData)
            _open = null;

        var signedIn = s.HasData || s.Status == SocialStatus.Online;
        var inChat = _open != null && signedIn;
        _stale.IsVisible = s.HasData && s.Status != SocialStatus.Online && !inChat;
        _footer.IsVisible = signedIn && !inChat;
        _back.IsVisible = inChat;
        _addToggle.IsVisible = signedIn && !inChat;
        _more.IsVisible = inChat && _open != SocialSnapshot.PartyKey;
        if (!signedIn && _addRow.IsVisible) SetAddOpen(false);

        _listScroll.IsVisible = false;
        _messageHost.IsVisible = false;
        _chat.IsVisible = false;

        if (inChat)
        {
            RenderChatHeader(s, _open!);
            _chat.IsVisible = true;
            _chat.Render(s, _open!);
            return;
        }

        _title.Text = "Friends";
        var online = s.Friends.Count(f => f.Online);
        // Like WPF: no line when signed out or friendless, but the line keeps its height so the title never moves.
        _subtitle.Text = signedIn && s.Friends.Count > 0 ? $"{online} online - {s.Friends.Count} friend{(s.Friends.Count == 1 ? "" : "s")}" : " ";
        _titleDot.IsVisible = false;

        var me = s.Me?.AppearOffline == true;
        _appear.IsOn = me;
        _appearCaption.Text = me ? "Friends see you as offline" : "Friends see you as online";

        if (!signedIn)
        {
            _messageHost.Content = MessagePane(s);
            _messageHost.IsVisible = true;
            return;
        }
        var nothing = s.Friends.Count == 0 && s.Incoming.Count == 0 && s.Outgoing.Count == 0 && s.Party == null
                      && s.PartyInvites.Count == 0 && s.Blocked.Count == 0 && s.Warp == null;
        if (nothing)
        {
            _messageHost.Content = Pane(Icons.UserPlus, "No friends yet",
                "Add someone by their Minecraft username. They need Origin too. Friends can chat, party up and join each other's servers.",
                false, "Add a friend", () => SetAddOpen(true));
            _messageHost.IsVisible = true;
            return;
        }
        _listScroll.IsVisible = true;
        SocialKit.Sync(_list, _rows.Resolve(ListSpecs(s)));
    }

    private void RenderChatHeader(SocialSnapshot s, string key)
    {
        if (key == SocialSnapshot.PartyKey)
        {
            var members = s.Party?.Members ?? [];
            _title.Text = "Party chat";
            _subtitle.Text = members.Count <= 1 ? "Just you so far"
                : $"{members.Count} members - {members.Count(m => m.Online)} online";
            _titleDot.IsVisible = false;
            return;
        }
        var friend = s.Friend(key);
        _title.Text = friend?.Name ?? s.NameOf(key);
        _subtitle.Text = friend == null ? "" : StatusFull(friend);
        _titleDot.IsVisible = friend?.Online == true;
    }

    // ---- Message panes ----

    private Control MessagePane(SocialSnapshot s) => s.Status switch
    {
        SocialStatus.NotSignedIn => Pane(Icons.Friends, "Sign in to use friends",
            "Friends use your Minecraft account, so sign in with Microsoft. Offline test accounts can't use friends.",
            features: true,
            "Add Microsoft account", () =>
            {
                _window.CloseDrawer();
                _window.OpenDrawer(new AccountDrawer(_window));
                SignInPanel.Open(_window);
            }),
        SocialStatus.SignInFailed => Pane(Icons.Friends, "Couldn't sign in to friends",
            s.StatusMessage ?? "Origin couldn't confirm your Minecraft account.", false, "Try again", () => Service.ResetAccount()),
        SocialStatus.Unreachable => Pane(Icons.Friends, "Can't reach friends right now",
            s.StatusMessage ?? "Origin can't reach its friends service. It keeps trying in the background.", false, "Try again",
            () => Service.Poke()),
        _ => Pane(Icons.Friends, "Connecting...", "Signing in to Origin friends.", false, null, null),
    };

    // WPF FriendsPanel MessagePane / EmptyPane: left-aligned at the top, a hued tile (sky for the account
    // states, emerald for "add someone"), the dim body, the three hued feature lines when signed out.
    private static Control Pane(string glyph, string title, string body, bool features, string? action, Action? run)
    {
        var empty = glyph == Icons.UserPlus;
        var col = new StackPanel { Margin = new Thickness(Space.S4, Space.S3, Space.S4, Space.S6), VerticalAlignment = VerticalAlignment.Top };
        col.Children.Add(Ui.Tile(glyph, empty ? Palette.TileEmerald : Palette.TileSky, empty ? Palette.HueEmerald : Palette.HueSky,
            Sizes.ControlXl).Align(HorizontalAlignment.Left));
        col.Children.Add(Ui.Wrap(Ui.Title(title)).Margin(0, Space.S4, 0, 0));
        col.Children.Add(Ui.Wrap(Ui.Body(body, Palette.TextDim)).Margin(0, Space.S2, 0, 0));
        if (features)
        {
            var list = new StackPanel { Spacing = 10, Margin = new Thickness(0, 18, 0, 0) };
            list.Children.Add(Feature(Icons.Chat, Palette.HueSky, "Message friends"));
            list.Children.Add(Feature(Icons.Friends, Palette.HueLilac, "Party up, with chat"));
            list.Children.Add(Feature(Icons.External, Palette.HueEmerald, "Join their server in one click"));
            col.Children.Add(list);
        }
        if (action != null && run != null)
        {
            var btn = Ui.Button(action, Look.Primary);
            btn.HorizontalAlignment = HorizontalAlignment.Left;
            btn.Margin = new Thickness(0, 22, 0, 0);
            btn.Click += (_, _) => run();
            col.Children.Add(btn);
        }
        return col;
    }

    private static Control Feature(string glyph, IBrush hue, string text) =>
        Ui.Row(Space.S3, Ui.Icon(glyph, Sizes.Icon, hue), Ui.Body(text, Palette.Text));

    // ---- The list ----

    private IEnumerable<RowCache.Spec> ListSpecs(SocialSnapshot s)
    {
        var meId = s.Me?.Uuid ?? "";
        var game = SocialHub.Game.Current;

        if (s.Warp is { } warp)
        {
            var who = warp.Name ?? s.NameOf(warp.From);
            yield return new RowCache.Spec("warp", $"{who}|{warp.Server}|{warp.Version}", () => WarpRow(warp, who));
        }

        var requests = s.PartyInvites.Count + s.Incoming.Count;
        if (requests > 0)
        {
            yield return Section($"REQUESTS - {requests}");
            foreach (var inv in s.PartyInvites)
                yield return new RowCache.Spec("inv:" + inv.PartyId, inv.Name, () => RequestRow(inv.From, inv.Name, "Party invite",
                    "Join their party (you leave your current one)", () => Act(null, () => Service.AcceptPartyAsync(inv.PartyId), $"You joined {inv.Name}'s party."),
                    () => Act(null, () => Service.DeclinePartyAsync(inv.PartyId))));
            foreach (var req in s.Incoming)
                yield return new RowCache.Spec("req:" + req.Uuid, req.Name, () => RequestRow(req.Uuid, req.Name, "Wants to be friends",
                    "Accept", () => Act(null, () => Service.RespondAsync(req.Uuid, true), $"You and {req.Name} are now friends."),
                    () => Act(null, () => Service.RespondAsync(req.Uuid, false))));
        }

        if (s.Party is { } party)
        {
            yield return Section($"PARTY - {party.Members.Count}/{PartyMax}");
            var sig = string.Join(";", party.Members.Select(m => $"{m.Uuid},{m.Name},{m.Online},{FriendSig(s.Friend(m.Uuid))},{s.Unread.GetValueOrDefault(m.Uuid)}"))
                      + $"|{party.Leader}|{s.UnreadParty}|{game?.Server}|{game?.Version}|{meId}";
            yield return new RowCache.Spec("party", sig, () => PartyCard(s, party, meId));
        }

        var inParty = s.Party?.Members.Select(m => m.Uuid).ToHashSet() ?? [];
        var others = s.Friends.Where(f => !inParty.Contains(f.Uuid)).ToList();
        var online = others.Where(f => f.Online).OrderBy(f => f.Name, StringComparer.OrdinalIgnoreCase).ToList();
        var offline = others.Where(f => !f.Online).OrderByDescending(f => f.LastSeen).ToList();

        if (s.Friends.Count == 0)
        {
            yield return Section("FRIENDS");
            yield return Hint("friends-none", "No friends yet. Use the + at the top to add someone.");
        }
        else
        {
            yield return Section($"ONLINE - {online.Count}");
            if (online.Count == 0)
                yield return Hint("online-none", s.Friends.Any(f => f.Online) ? "No other friends are online." : "No friends are online right now.");
            foreach (var f in online)
                yield return new RowCache.Spec("f:" + f.Uuid, FriendSig(f) + "|" + s.Unread.GetValueOrDefault(f.Uuid), () => FriendRow(s, f));
            if (offline.Count > 0)
            {
                yield return new RowCache.Spec("sec:offline", $"{offline.Count}|{_offlineCollapsed}", () => OfflineHeader(offline.Count));
                if (!_offlineCollapsed)
                    foreach (var f in offline)
                        yield return new RowCache.Spec("f:" + f.Uuid, FriendSig(f) + "|" + s.Unread.GetValueOrDefault(f.Uuid), () => FriendRow(s, f));
            }
        }

        if (s.Outgoing.Count > 0)
        {
            yield return Section($"SENT - {s.Outgoing.Count}");
            foreach (var o in s.Outgoing)
                yield return new RowCache.Spec("out:" + o.Uuid, o.Name, () => SimpleRow(o.Uuid, o.Name, "Request sent", "Cancel",
                    "Cancel request", b => Act(b, () => Service.RemoveFriendAsync(o.Uuid))));
        }
        if (s.Blocked.Count > 0)
        {
            yield return Section($"BLOCKED - {s.Blocked.Count}");
            foreach (var bl in s.Blocked)
                yield return new RowCache.Spec("bl:" + bl.Uuid, bl.Name, () => SimpleRow(bl.Uuid, bl.Name, "Blocked", "Unblock",
                    "Unblock " + bl.Name, b => Act(b, () => Service.UnblockAsync(bl.Uuid), $"{bl.Name} is unblocked.")));
        }
    }

    private static string FriendSig(FriendInfo? f) =>
        f == null ? "-" : $"{f.Name}|{f.Online}|{f.Client}|{f.Activity}|{f.Server}|{f.Version}|{(f.Online ? 0 : f.LastSeen / 60_000)}";

    private static RowCache.Spec Section(string text) => new("sec:" + text.Split(' ')[0], text, () =>
        Ui.Eyebrow(text, Palette.TextMuted).Margin(Space.S2, Space.S4, Space.S2, 6));

    private static RowCache.Spec Hint(string key, string text) => new(key, text, () =>
        Ui.Wrap(Ui.Caption(text, Palette.TextMuted)).Margin(Space.S2, 2, Space.S2, Space.S2));

    private Control OfflineHeader(int count)
    {
        var chevron = Ui.Icon(Icons.ChevronDown, Sizes.IconSm, Palette.TextMuted);
        chevron.RenderTransform = _offlineCollapsed ? new RotateTransform(-90) : null;
        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        g.Children.Add(Ui.Eyebrow($"OFFLINE - {count}", Palette.TextMuted).At(0));
        g.Children.Add(chevron.At(1));
        var p = new Pressable(Look.Ghost, g, _offlineCollapsed ? "Show offline friends" : "Hide offline friends")
        {
            Padding = new Thickness(Space.S2, 6), Margin = new Thickness(0, 10, 0, 0), CornerRadius = new CornerRadius(Radius.Sm),
        };
        p.Click += (_, _) => { _offlineCollapsed = !_offlineCollapsed; Render(_s); };
        return p;
    }

    /// <summary>A friend (or party member) row: head with presence, name, status or unread preview, then actions.</summary>
    private Control FriendRow(SocialSnapshot s, FriendInfo f, bool inPartyCard = false, bool leader = false)
    {
        var unread = s.Unread.GetValueOrDefault(f.Uuid);
        var preview = unread > 0 && !inPartyCard ? s.ConversationWith(f.Uuid).LastOrDefault(m => m.From == f.Uuid)?.Text : null;
        var crown = leader ? Ui.Icon(Icons.Crown, Sizes.IconSm, Palette.HueGold).Tip("Party leader") : null;
        var text = SocialKit.NameBlock(f.Name, preview ?? StatusShort(f), crown, preview != null ? Palette.Text : null);

        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto,Auto"), ColumnSpacing = 10 };
        g.Children.Add(SocialKit.Avatar(f.Uuid, f.Online).At(0));
        g.Children.Add(text.At(1));
        if (unread > 0) g.Children.Add(SocialKit.Unread(unread).At(2));
        if (f.IsOnServer)
        {
            var join = Ui.Button("Join", Look.Primary, height: Sizes.ControlMd).Tip(JoinTip(f));
            join.Click += (_, _) => _ = JoinAsync(_window, f.Name, f.Server, f.Version);
            g.Children.Add(join.At(3));
        }
        var more = Ui.IconButton(Icons.More, "More", Sizes.ControlSm);
        more.Click += (_, _) => ShowFriendMenu(more, f.Uuid);
        g.Children.Add(more.At(4));

        var row = new Pressable(Look.Row, g, "Chat with " + f.Name)
        {
            Padding = new Thickness(Space.S2, 6), CornerRadius = new CornerRadius(Radius.Md),
        };
        row.Click += (_, _) => OpenChat(f.Uuid);
        return row;
    }

    private Control RequestRow(string uuid, string name, string caption, string acceptTip, Func<Task> accept, Func<Task> decline)
    {
        var yes = SocialKit.Outline(Ui.Icon(Icons.Check, Sizes.Icon), acceptTip, Sizes.ControlMd, Sizes.ControlMd);
        var no = SocialKit.Square(Icons.Close, "Decline");
        yes.Click += async (_, _) => { yes.IsEnabled = no.IsEnabled = false; await accept(); yes.IsEnabled = no.IsEnabled = true; };
        no.Click += async (_, _) => { yes.IsEnabled = no.IsEnabled = false; await decline(); yes.IsEnabled = no.IsEnabled = true; };
        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,Auto"), ColumnSpacing = 10 };
        g.Children.Add(SocialKit.Avatar(uuid, null).At(0));
        g.Children.Add(SocialKit.NameBlock(name, caption).At(1));
        g.Children.Add(yes.At(2));
        g.Children.Add(no.At(3));
        return new Border { Padding = new Thickness(Space.S2, 6), Child = g };
    }

    private Control SimpleRow(string uuid, string name, string caption, string action, string tip, Func<Pressable, Task> run)
    {
        var b = Ui.Button(action, Look.Ghost, height: Sizes.ControlSm).Tip(tip);
        b.Click += async (_, _) => await run(b);
        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), ColumnSpacing = 10 };
        g.Children.Add(SocialKit.Avatar(uuid, null).At(0));
        g.Children.Add(SocialKit.NameBlock(name, caption).At(1));
        g.Children.Add(b.At(2));
        return new Border { Padding = new Thickness(Space.S2, 6), Child = g };
    }

    private Control WarpRow(WarpRequest warp, string who)
    {
        var join = Ui.Button("Join", Look.Primary, height: Sizes.ControlMd);
        join.Click += (_, _) => _ = JoinAsync(_window, who, warp.Server, warp.Version);
        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), ColumnSpacing = 10 };
        g.Children.Add(Ui.Tile(Icons.Friends, Palette.AccentSoft, Palette.Accent, Sizes.ControlMd).At(0));
        g.Children.Add(Ui.Wrap(Ui.Body($"{who} warped the party to {SocialText.DisplayServer(warp.Server)}", Palette.Text)).At(1));
        g.Children.Add(join.At(2));
        return new Border
        {
            Background = Palette.AccentSoft, BorderBrush = Palette.AccentLine, BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(Radius.Md), Padding = new Thickness(Space.S2), Margin = new Thickness(0, Space.S1, 0, 0), Child = g,
        };
    }

    private Control PartyCard(SocialSnapshot s, PartyInfo party, string meId)
    {
        var col = new StackPanel { Spacing = 0 };
        var members = party.Members
            .OrderByDescending(m => m.Uuid == party.Leader)
            .ThenByDescending(m => m.Online)
            .ThenBy(m => m.Name, StringComparer.OrdinalIgnoreCase);
        foreach (var m in members)
        {
            var leader = m.Uuid == party.Leader;
            if (m.Uuid == meId)
            {
                var g = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*"), ColumnSpacing = 10 };
                g.Children.Add(SocialKit.Avatar(m.Uuid, true).At(0));
                g.Children.Add(SocialKit.NameBlock(m.Name, "You",
                    leader ? Ui.Icon(Icons.Crown, Sizes.IconSm, Palette.HueGold).Tip("Party leader") : null).At(1));
                col.Children.Add(new Border { Padding = new Thickness(Space.S2, 6), Child = g });
                continue;
            }
            var friend = s.Friend(m.Uuid);
            if (friend != null)
            {
                col.Children.Add(FriendRow(s, friend, inPartyCard: true, leader: leader));
                continue;
            }
            // A member who isn't my friend: no chat, a menu only when I lead (make leader, kick).
            var mg = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), ColumnSpacing = 10 };
            mg.Children.Add(SocialKit.Avatar(m.Uuid, m.Online).At(0));
            mg.Children.Add(SocialKit.NameBlock(m.Name, m.Online ? "Online" : "Offline",
                leader ? Ui.Icon(Icons.Crown, Sizes.IconSm, Palette.HueGold).Tip("Party leader") : null).At(1));
            if (s.IsPartyLeader)
            {
                var more = Ui.IconButton(Icons.More, "More", Sizes.ControlSm);
                more.Click += (_, _) => ShowFriendMenu(more, m.Uuid);
                mg.Children.Add(more.At(2));
            }
            col.Children.Add(new Border { Padding = new Thickness(Space.S2, 6), Child = mg });
        }

        var game = SocialHub.Game.Current;
        var chatLabel = s.UnreadParty > 0 ? $"Chat - {s.UnreadParty}" : "Chat";
        var chat = SocialKit.Outline(Ui.Text(chatLabel, TypeScale.ControlSm, FontWeight.SemiBold), "Open the party chat", Sizes.ControlSm);
        chat.Click += (_, _) => OpenChat(SocialSnapshot.PartyKey);
        var warp = Ui.Button("Warp", Look.Ghost, height: Sizes.ControlSm);
        warp.IsEnabled = s.IsPartyLeader && !string.IsNullOrEmpty(game?.Server);
        warp.Tip(game?.Server is { Length: > 0 } server
            ? $"Ask your party to join you on {SocialText.DisplayServer(server)} ({game.Version})"
            : "Join a server to warp (start Minecraft from this launcher first)");
        warp.Click += (_, _) => _ = WarpAsync(warp);
        var leave = Ui.Button("Leave", Look.Secondary, height: Sizes.ControlSm).Tip("Leave the party");
        leave.Click += (_, _) => _ = Act(leave, () => Service.LeavePartyAsync(), "You left the party.");
        var actions = Ui.Row(Space.S2, chat, warp, leave);
        actions.Margin = new Thickness(Space.S2, Space.S2, 0, Space.S1);
        col.Children.Add(actions);

        return new Border
        {
            Background = Palette.BgAlt, CornerRadius = new CornerRadius(Radius.Lg), Padding = new Thickness(0, Space.S1, 0, Space.S2),
            Child = col,
        };
    }

    // ---- Text (FriendsViewModels) ----

    internal static string StatusShort(FriendInfo f)
    {
        if (!f.Online) return f.LastSeen > 0 ? "Last seen " + SocialKit.Ago(f.LastSeen) : "Offline";
        if (f.Client == "launcher") return "In the launcher";
        if (f.IsOnServer) return "On " + Shorten(SocialText.DisplayServer(f.Server!), 20);
        if (f.Activity == "singleplayer") return "Singleplayer";
        return "In game";
    }

    internal static string StatusFull(FriendInfo f) =>
        f.Online ? SocialText.StatusLine(f) : f.LastSeen > 0 ? "Offline - last seen " + SocialKit.Ago(f.LastSeen) : "Offline";

    internal static string JoinTip(FriendInfo f) =>
        $"Launch Minecraft {f.Version} and join {f.Name} on {SocialText.DisplayServer(f.Server ?? "")}";

    private static string Shorten(string text, int max) => text.Length <= max ? text : text[..(max - 3)] + "...";

    // ---- Menus ----

    internal void ShowFriendMenu(Control target, string uuid)
    {
        var s = _s;
        var friend = s.Friend(uuid);
        var name = s.NameOf(uuid);
        var party = s.Party;
        var inMyParty = party?.Members.Any(m => m.Uuid == uuid) == true;
        var talk = new List<Entry>();
        if (friend != null) talk.Add(new Entry("Message", Icons.Chat, () => OpenChat(uuid)));
        if (friend?.IsOnServer == true)
            talk.Add(new Entry("Join " + Shorten(SocialText.DisplayServer(friend.Server!), 24), Icons.External,
                () => _ = JoinAsync(_window, friend.Name, friend.Server, friend.Version)));
        if (friend != null && !inMyParty && (party == null || s.IsPartyLeader))
            talk.Add(new Entry("Invite to party", Icons.UserPlus,
                () => _ = Act(null, () => Service.InviteToPartyAsync(uuid), $"Party invite sent to {name}.")));
        var lead = new List<Entry>();
        if (inMyParty && s.IsPartyLeader)
        {
            lead.Add(new Entry("Make leader", Icons.Crown, () => _ = Act(null, () => Service.PromoteAsync(uuid), $"{name} now leads the party.")));
            lead.Add(new Entry("Kick from party", Icons.UserMinus, () => _ = ConfirmThenAsync($"Kick {name}?",
                "They'll be removed from the party. You can invite them again later.", "Kick",
                () => Service.KickAsync(uuid), null), Danger: true));
        }
        var drop = new List<Entry>();
        if (friend != null)
        {
            drop.Add(new Entry("Remove friend", Icons.Trash, () => _ = ConfirmThenAsync($"Remove {name}?",
                "You'll stop seeing each other online. You can add them again later.", "Remove",
                () => Service.RemoveFriendAsync(uuid), $"{name} was removed from your friends."), Danger: true));
            drop.Add(new Entry("Block", Icons.Block, () => _ = ConfirmThenAsync($"Block {name}?",
                SocialText.BlockExplained,
                "Block", () => Service.BlockAsync(uuid), $"{name} is blocked."), Danger: true));
        }
        SocialKit.ShowMenu(target, [talk, lead, drop]);
    }

    private async Task ConfirmThenAsync(string title, string body, string verb, Func<Task<SocialActionResult>> run, string? success)
    {
        if (!await Confirm.AskAsync(_window, title, body, verb, danger: true)) return;
        await Act(null, run, success);
    }

    // ---- Actions ----

    /// <summary>Run a social action once at a time per button: the button waits, the result shows in the strip.</summary>
    internal async Task<bool> Act(Pressable? sender, Func<Task<SocialActionResult>> run, string? success = null)
    {
        if (sender != null) sender.IsEnabled = false;
        SocialActionResult result;
        try { result = await run(); }
        catch (Exception) { result = SocialActionResult.Fail("Something went wrong. Please try again."); }
        if (sender != null) sender.IsEnabled = true;
        if (!result.Ok) ShowNotice(result.Error ?? "Something went wrong. Please try again.", error: true);
        else if (success != null) ShowNotice(success, error: false);
        return result.Ok;
    }

    private async Task AddFriendAsync()
    {
        var name = _addBox.Text?.Trim() ?? "";
        if (!SocialText.IsValidUsername(name) || !_addSend.IsEnabled) return;
        if (await Act(_addSend, () => Service.AddFriendAsync(name), $"Friend request sent to {name}."))
            SetAddOpen(false);
        else
            _addBox.BorderBrush = Palette.Danger;
    }

    private async Task SetAppearOfflineAsync(bool on)
    {
        var ok = await Act(null, () => Service.SetAppearOfflineAsync(on),
            on ? "You now appear offline to your friends." : "Your friends can see you again.");
        if (!ok) _appear.IsOn = !on;
    }

    private async Task WarpAsync(Pressable sender)
    {
        var game = SocialHub.Game.Current;
        if (game?.Server is not { Length: > 0 } server)
        {
            ShowNotice("Join a server in Minecraft first, then warp your party to it.", error: true);
            return;
        }
        await Act(sender, () => Service.WarpAsync(server, game.Version),
            $"Warp sent. Your party can join you on {SocialText.DisplayServer(server)}.");
    }

    /// <summary>Join a friend's server: the same checks as the WPF launcher, then Home plays that version on it.</summary>
    public static async Task JoinAsync(MainWindow window, string who, string? server, string? version)
    {
        var block = JoinArguments.Check(version, server, v => VersionCatalog.IsSupported(v));
        if (block != JoinBlock.None)
        {
            var why = JoinArguments.Explain(block, who, version);
            if (Shown is { } drawer) drawer.ShowNotice(why, error: true);
            else SocialToasts.Of(window).Show(new SocialToast(SocialToastKind.Notice, "Can't join", why));
            return;
        }
        window.CloseDialog();
        window.CloseDrawer();
        window.Navigate(PageKey.Home);
        await window.Home.Launches.PlayAsync(version!, server);
    }

    /// <summary>The strip under the header: a success fades after 4 s, an error stays until clicked.</summary>
    public void ShowNotice(string text, bool error)
    {
        _noticeText.Text = text;
        _noticeText.Foreground = error ? Palette.DangerText : Palette.Text;
        _noticeIcon.Children.Clear();
        _noticeIcon.Children.Add(Ui.Icon(error ? Icons.Alert : Icons.Check, Sizes.IconSm, error ? Palette.Danger : Palette.AccentHover));
        _notice.BorderBrush = error ? Palette.Danger : Palette.Line;
        _notice.IsVisible = true;
        _noticeTimer?.Dispose();
        if (!error && !Shots.ShotRunner.Active)
            _noticeTimer = DispatcherTimer.RunOnce(() => _notice.IsVisible = false, TimeSpan.FromSeconds(4));
    }

    /// <summary>Render runs: show the add row with a name typed.</summary>
    public void ShowAdd(string name)
    {
        SetAddOpen(true);
        _addBox.Text = name;
    }

    internal SocialSnapshot Snapshot => _s;
    internal string? OpenKey => _open;
    internal MainWindow Window => _window;

    // ---- The service, started once per launcher run ----

    private static bool _wired;

    /// <summary>Starts the social connection once the window is up (real runs only).</summary>
    public static void StartSocial(MainWindow window)
    {
        Wire(window);
        Service.Start();
    }

    /// <summary>Hooks the bar dot, the toasts and the window's focus to the service (render runs call this alone).</summary>
    public static void Wire(MainWindow window)
    {
        SocialToasts.Of(window);
        Dispatcher.UIThread.Post(() => Dot(Service.Snapshot));
        if (!Shots.ShotRunner.Active)
        {
            window.Activated += (_, _) => Service.SetAppActive(true);
            window.Deactivated += (_, _) => Service.SetAppActive(false);
        }
        if (_wired) return;
        _wired = true;
        Service.Changed += s => Dispatcher.UIThread.Post(() => Dot(s));
        Service.ToastRaised += t => Dispatcher.UIThread.Post(() =>
        {
            if (MainWindow.Current is { } w) SocialToasts.Of(w).Show(t);
        });
    }

    /// <summary>The bar's dot: something waits for the player (a request, an invite, an unread message).</summary>
    private static void Dot(SocialSnapshot s) => MainWindow.Current?.Bar.SetFriendsOnline(s.AttentionCount > 0);
}
