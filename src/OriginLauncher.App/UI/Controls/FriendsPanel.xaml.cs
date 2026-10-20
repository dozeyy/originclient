using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using System.Windows.Threading;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;

namespace OriginLauncher.App.UI.Controls;

/// <summary>A "Join" press: whose game, which version, which server.</summary>
public sealed record JoinRequest(string Who, string? Version, string? Server);

/// <summary>
/// The Friends drawer. MainWindow owns it, feeds it every new snapshot
/// (<see cref="Apply"/>) and handles the three things it can't do itself:
/// closing, adding a Microsoft account, and launching a Join.
/// Every button awaits the service off the UI thread and shows the service's
/// own sentence on failure; the list refreshes from the next sync.
///
/// The list is ONE flat, virtualized collection of rows (sections included).
/// <see cref="KeyedDiff"/> edits it in place by row key, so a sync that changes
/// one friend's status touches one row: no flicker, no lost hover or focus, no
/// scroll jump. While the drawer is hidden, or a menu is open on a row, a new
/// snapshot is only remembered and drawn when the drawer shows / the menu closes.
/// </summary>
public partial class FriendsPanel : UserControl
{
    public event EventHandler? CloseRequested;
    public event EventHandler? AddAccountRequested;
    public event Action<JoinRequest>? JoinRequested;

    private static SocialService Service => SocialHub.Service;

    private static readonly TimeSpan NoticeLife = TimeSpan.FromSeconds(4);

    private readonly ObservableCollection<RowVm> _rows = new();
    private readonly ObservableCollection<ChatRowVm> _chatRows = new();
    private readonly DispatcherTimer _noticeTimer = new(DispatcherPriority.Background) { Interval = NoticeLife };
    // Actions in flight, by what they act on ("friend:<uuid>"), so a second press can't start a second one.
    private readonly HashSet<string> _busy = new();

    private SocialSnapshot _snapshot = new();
    // null = the list; a uuid = that friend's chat; SocialSnapshot.PartyKey = party chat.
    private string? _chatKey;
    private bool _shown;
    private bool _menuOpen;
    // The menu that holds the list still, and the action its item chose (run once the menu has closed).
    private ContextMenu? _menu;
    private Action? _menuAction;
    private bool _renderDirty = true;
    private bool _syncingToggle;
    private bool _appearOfflinePending;
    private bool _addOpen;
    private bool _offlineCollapsed;

    public FriendsPanel()
    {
        InitializeComponent();
        RowList.ItemsSource = _rows;
        ChatList.ItemsSource = _chatRows;
        _noticeTimer.Tick += (_, _) => HideNotice();
        // The warp button depends on what the launched game is doing.
        SocialHub.Game.Changed += () => Dispatcher.BeginInvoke(() =>
        {
            if (_chatKey == null) Render();
        });
    }

    // ---- driven by MainWindow ---------------------------------------------------

    public void Apply(SocialSnapshot snapshot)
    {
        _snapshot = snapshot;
        Render();
    }

    /// <summary>The drawer opened or closed: an open chat only counts as "read" while visible.</summary>
    public void SetShown(bool shown)
    {
        _shown = shown;
        Service.SetOpenConversation(shown ? _chatKey : null);
        if (!shown)
        {
            // Closing the drawer abandons a menu and a pending Remove / Block / Kick question
            // (releasing the in-flight guard it held).
            CloseMenu();
            Confirm.Dismiss();
            return;
        }
        if (_chatKey == null) HideNotice();
        if (_renderDirty) Render();
    }

    /// <summary>Opens a conversation (a friend's uuid, or <see cref="SocialSnapshot.PartyKey"/>).</summary>
    public void OpenConversation(string key)
    {
        CloseMenu();
        _chatKey = key;
        ChatInput.Text = "";
        ChatError.Visibility = Visibility.Collapsed;
        JumpButton.Visibility = Visibility.Collapsed;
        NoticeStrip.Visibility = Visibility.Collapsed;
        _chatRows.Clear();
        if (_shown) Service.SetOpenConversation(key);
        _renderDirty = true;
        Render();
        Dispatcher.BeginInvoke(() => ChatInput.Focus(), DispatcherPriority.Input);
    }

    /// <summary>Esc: close the confirm, else the add box, else leave the chat. False = nothing to do, close the drawer.</summary>
    public bool HandleEscape()
    {
        if (Confirm.Visibility == Visibility.Visible)
        {
            Confirm.Dismiss();
            return true;
        }
        if (_chatKey == null && _addOpen)
        {
            SetAddOpen(false);
            return true;
        }
        if (_chatKey != null)
        {
            BackToList();
            return true;
        }
        return false;
    }

    /// <summary>A sentence for the player (e.g. why Join can't go ahead), in whichever view is up.</summary>
    public void ShowNotice(string text, bool error)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        _noticeTimer.Stop();
        if (_chatKey != null)
        {
            ChatError.Text = text;
            ChatError.Foreground = (Brush)FindResource(error ? "Brush.Danger" : "Brush.TextDim");
            ChatError.Visibility = Visibility.Visible;
        }
        else
        {
            var brush = (Brush)FindResource(error ? "Brush.Danger" : "Brush.AccentHover");
            NoticeText.Text = text;
            NoticeText.Foreground = brush;
            NoticeIcon.Stroke = brush;
            NoticeIcon.Data = (Geometry)FindResource(error ? "Icon.Alert" : "Icon.Check");
            NoticeStrip.Visibility = Visibility.Visible;
        }
        // A success fades on its own; a failure stays until you act or click it away.
        if (!error) _noticeTimer.Start();
    }

    private void HideNotice()
    {
        _noticeTimer.Stop();
        NoticeStrip.Visibility = Visibility.Collapsed;
        ChatError.Visibility = Visibility.Collapsed;
        SetAddInvalid(false);
    }

    private void Notice_Click(object sender, MouseButtonEventArgs e) => HideNotice();

    /// <summary>The username box gets a redstone edge while its error shows.</summary>
    private void SetAddInvalid(bool invalid)
    {
        if (invalid) AddFriendBox.BorderBrush = ((SolidColorBrush)FindResource("Brush.Danger")).Clone();
        else AddFriendBox.ClearValue(Control.BorderBrushProperty);
    }

    // Render harness only (UI/SocialShots.cs).
    internal Task<bool> TourShowConfirm() =>
        Confirm.ShowAsync("Remove Steve?", "You'll stop seeing each other online. You can add them again later.",
            "REMOVE", "CANCEL", destructive: true);

    internal void TourDismissConfirm() => Confirm.Dismiss();

    internal ContextMenu? TourFriendMenu(string uuid, bool inChat) =>
        _snapshot.Friend(uuid) is not null ? BuildMenu(uuid, inChat, member: false) : null;

    internal ContextMenu TourMemberMenu(string uuid) => BuildMenu(uuid, inChat: false, member: true);

    internal void TourSetAddOpen(bool open) => SetAddOpen(open);

    internal void TourShowAddError(string text)
    {
        SetAddOpen(true);
        ShowNotice(text, error: true);
        SetAddInvalid(true);
    }

    internal void TourClearNotice() => HideNotice();

    internal void TourScrollListToEnd()
    {
        for (var i = 0; i < 3; i++)
        {
            UpdateLayout();
            if (FindScrollViewer(RowList) is { } scroll) scroll.ScrollToEnd();
        }
    }

    internal void TourScrollListToTop()
    {
        if (FindScrollViewer(RowList) is { } scroll) scroll.ScrollToTop();
    }

    /// <summary>The list row for a friend or party member (hover / focus scenes).</summary>
    internal Button? TourFindRow(string uuid)
    {
        UpdateLayout();
        return FindRow(RowList, uuid);
    }

    private static Button? FindRow(DependencyObject parent, string uuid)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is Button { DataContext: FriendVm f } b && f.Uuid == uuid) return b;
            if (child is Button { DataContext: MemberVm m } bm && m.Uuid == uuid) return bm;
            if (FindRow(child, uuid) is { } found) return found;
        }
        return null;
    }

    private static ScrollViewer? FindScrollViewer(DependencyObject parent)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is ScrollViewer scroll) return scroll;
            if (FindScrollViewer(child) is { } found) return found;
        }
        return null;
    }

    // ---- rendering ------------------------------------------------------------------

    /// <summary>Draws the newest snapshot, unless nobody is looking (drawer hidden) or a menu is anchored to a row.</summary>
    private void Render()
    {
        if (!_shown || _menuOpen)
        {
            _renderDirty = true;
            return;
        }
        _renderDirty = false;

        var s = _snapshot;
        if (s.Status != SocialStatus.Online && !s.HasData)
        {
            ShowMessage(s);
            return;
        }

        // A chat whose other side went away (removed, blocked, party ended) closes.
        if (_chatKey == SocialSnapshot.PartyKey && s.Party == null) BackToList(render: false);
        else if (_chatKey != null && _chatKey != SocialSnapshot.PartyKey && s.Friend(_chatKey) == null) BackToList(render: false);

        var list = _chatKey == null;
        MessagePane.Visibility = Visibility.Collapsed;
        ChatPane.Visibility = list ? Visibility.Collapsed : Visibility.Visible;
        BackButton.Visibility = list ? Visibility.Collapsed : Visibility.Visible;
        AddToggle.Visibility = list ? Visibility.Visible : Visibility.Collapsed;
        AddRow.Visibility = list && _addOpen ? Visibility.Visible : Visibility.Collapsed;
        Footer.Visibility = list ? Visibility.Visible : Visibility.Collapsed;

        if (list)
        {
            RenderList();
        }
        else
        {
            RowList.Visibility = Visibility.Collapsed;
            EmptyPane.Visibility = Visibility.Collapsed;
            RenderChat();
        }
    }

    private void SetHeader(string title, string subtitle, bool dot = false)
    {
        // The drawer's own titles are poster caps; a friend's name in a chat keeps its case.
        HeaderTitle.Text = title is "Friends" or "Party chat" ? title.ToUpperInvariant() : title;
        // The line keeps its height when empty so the title sits in the same place in every view.
        HeaderSubtitle.Text = string.IsNullOrEmpty(subtitle) ? " " : subtitle;
        HeaderDot.Visibility = dot ? Visibility.Visible : Visibility.Collapsed;
        HeaderSubRow.Visibility = string.IsNullOrEmpty(subtitle) ? Visibility.Hidden : Visibility.Visible;
    }

    private void ShowMessage(SocialSnapshot s)
    {
        // A chat that was open is over: the first Esc must close the drawer, not "leave" a chat nobody sees.
        if (_chatKey != null) BackToList(render: false);

        RowList.Visibility = Visibility.Collapsed;
        EmptyPane.Visibility = Visibility.Collapsed;
        ChatPane.Visibility = Visibility.Collapsed;
        BackButton.Visibility = Visibility.Collapsed;
        AddToggle.Visibility = Visibility.Collapsed;
        ChatMoreButton.Visibility = Visibility.Collapsed;
        AddRow.Visibility = Visibility.Collapsed;
        Footer.Visibility = Visibility.Collapsed;
        StaleStrip.Visibility = Visibility.Collapsed;
        MessagePane.Visibility = Visibility.Visible;
        SetHeader("Friends", "");

        var (title, body, action) = s.Status switch
        {
            SocialStatus.NotSignedIn => ("Sign in to use friends",
                "Friends use your Minecraft account, so sign in with Microsoft. Offline test accounts can't use friends.",
                "ADD MICROSOFT ACCOUNT"),
            SocialStatus.SignInFailed => ("Couldn't sign in to friends",
                s.StatusMessage ?? "Origin couldn't confirm your Minecraft account.", "TRY AGAIN"),
            SocialStatus.Unreachable => ("Friends are offline",
                s.StatusMessage ?? "Origin can't reach its friends service. It keeps trying in the background.", "TRY AGAIN"),
            _ => ("Connecting...", "Signing in to Origin friends.", null),
        };
        MessageTitle.Text = title;
        MessageBody.Text = body;
        FeatureList.Visibility = s.Status == SocialStatus.NotSignedIn ? Visibility.Visible : Visibility.Collapsed;
        MessageAction.Content = action;
        MessageAction.Visibility = action == null ? Visibility.Collapsed : Visibility.Visible;
    }

    // "Last seen 3 h ago", as plain as the service's LastSeen allows.
    private static string Ago(long atMs)
    {
        var span = DateTimeOffset.UtcNow - DateTimeOffset.FromUnixTimeMilliseconds(atMs);
        if (span.TotalMinutes < 1) return "just now";
        if (span.TotalMinutes < 60) return $"{(int)span.TotalMinutes} min ago";
        if (span.TotalHours < 24) return $"{(int)span.TotalHours} h ago";
        return $"{(int)span.TotalDays} d ago";
    }

    /// <summary>The whole line, version included (chat header, tooltips).</summary>
    private static string StatusFull(FriendInfo f) =>
        f.Online ? SocialText.StatusLine(f)
        : f.LastSeen > 0 ? $"Offline - last seen {Ago(f.LastSeen)}" : "Offline";

    /// <summary>
    /// The row's line, as short as it can be so it isn't cut off: "On hypixel.net" (the whole line,
    /// with the version, is the tooltip and the chat header).
    /// </summary>
    private static string StatusShort(FriendInfo f)
    {
        if (!f.Online) return f.LastSeen > 0 ? $"Last seen {Ago(f.LastSeen)}" : "Offline";
        if (f.Client == "launcher") return "In the launcher";
        return f.Activity switch
        {
            "server" when !string.IsNullOrWhiteSpace(f.Server) => "On " + Clip(SocialText.DisplayServer(f.Server), 20),
            "singleplayer" => "Singleplayer",
            _ => "In game",
        };
    }

    /// <summary>Cuts a long name with three dots, never leaving the dots right behind a "." or "-".</summary>
    private static string Clip(string text, int max) =>
        text.Length <= max ? text : text[..(max - 1)].TrimEnd('.', '-') + "...";

    private static string JoinTipFor(FriendInfo f) =>
        !f.IsOnServer ? "Join their server"
        : string.IsNullOrWhiteSpace(f.Version)
            ? $"Launch Minecraft and join {f.Name} on {SocialText.DisplayServer(f.Server!)}"
            : $"Launch Minecraft {f.Version} and join {f.Name} on {SocialText.DisplayServer(f.Server!)}";

    /// <summary>Adds rows as one card group: the first has the top corners, the last the bottom ones.</summary>
    private static void AddCard(List<RowVm> rows, IEnumerable<CardRowVm> items)
    {
        var group = items.ToList();
        for (var i = 0; i < group.Count; i++)
            group[i].Pos = group.Count == 1 ? CardPos.Only : i == 0 ? CardPos.First : i == group.Count - 1 ? CardPos.Last : CardPos.Middle;
        rows.AddRange(group);
    }

    private void RenderList()
    {
        var s = _snapshot;
        if (s.Status != SocialStatus.Online && !s.HasData) return;
        var me = s.Me?.Uuid ?? "";

        ChatMoreButton.Visibility = Visibility.Collapsed;
        StaleStrip.Visibility = s.Status == SocialStatus.Online ? Visibility.Collapsed : Visibility.Visible;

        var appearOffline = s.Me?.AppearOffline == true;
        AppearCaption.Text = appearOffline ? "Friends see you as offline" : "Friends see you as online";
        if (!_appearOfflinePending)
        {
            _syncingToggle = true;
            AppearOfflineToggle.IsChecked = appearOffline;
            _syncingToggle = false;
        }

        var game = SocialHub.Game.Current;
        var rows = new List<RowVm>();

        // 1. The leader asked everyone to join them.
        if (s.Warp is { } warp && warp.From != me)
            rows.Add(new WarpVm { Text = $"{warp.Name ?? s.NameOf(warp.From)} warped the party to {SocialText.DisplayServer(warp.Server)}" });

        // 2. Things waiting for an answer.
        var waiting = s.PartyInvites.Count + s.Incoming.Count;
        if (waiting > 0)
        {
            rows.Add(new SectionVm { Id = "needs", Title = "REQUESTS", Count = waiting });
            AddCard(rows, s.PartyInvites.Select(i => (CardRowVm)new InviteVm { PartyId = i.PartyId, Name = i.Name })
                .Concat(s.Incoming.Select(r => new RequestVm { Uuid = r.Uuid, Name = r.Name })));
        }

        // 3. Your party: its members are listed here and only here (not again under ONLINE).
        var inParty = new HashSet<string>();
        if (s.Party is { } party)
        {
            var leader = s.IsPartyLeader;
            foreach (var m in party.Members) inParty.Add(m.Uuid);
            rows.Add(new PartyHeaderVm
            {
                Title = $"PARTY - {party.Members.Count}/8",
                Unread = s.UnreadParty,
                IsLeader = leader,
                CanWarp = game?.Server != null,
                WarpTip = game?.Server is { } server
                    ? $"Ask your party to join you on {SocialText.DisplayServer(server)} ({game.Version})"
                    : "Join a server to warp (start Minecraft from this launcher first)",
            });
            AddCard(rows, party.Members
                .OrderByDescending(m => m.Uuid == party.Leader)
                .ThenByDescending(m => m.Online)
                .ThenBy(m => m.Name, StringComparer.OrdinalIgnoreCase)
                .Select(m =>
                {
                    var friend = s.Friend(m.Uuid);
                    var isMe = m.Uuid == me;
                    return (CardRowVm)new MemberVm
                    {
                        Uuid = m.Uuid,
                        Name = m.Name,
                        Status = isMe ? "You" : friend != null ? StatusShort(friend) : m.Online ? "Online" : "Offline",
                        IsLeader = m.Uuid == party.Leader,
                        IsMe = isMe,
                        IsOnline = m.Online,
                        IsFriend = friend != null,
                        CanJoin = friend?.IsOnServer == true && !isMe,
                        HasMenu = !isMe && (friend != null || leader),
                        Unread = s.Unread.GetValueOrDefault(m.Uuid),
                        JoinTip = friend != null ? JoinTipFor(friend) : "Join their server",
                    };
                }));
        }

        // 4. Friends who aren't in your party: online first, offline folds.
        FriendVm Row(FriendInfo f)
        {
            var unread = s.Unread.GetValueOrDefault(f.Uuid);
            return new FriendVm
            {
                Uuid = f.Uuid,
                Name = f.Name,
                Status = StatusShort(f),
                StatusFull = StatusFull(f),
                Preview = unread > 0 ? s.ConversationWith(f.Uuid).LastOrDefault(m => m.From == f.Uuid)?.Text : null,
                IsOnline = f.Online,
                CanJoin = f.IsOnServer,
                Unread = unread,
                JoinTip = JoinTipFor(f),
            };
        }
        var others = s.Friends.Where(f => !inParty.Contains(f.Uuid)).ToList();
        var onlineFriends = others.Where(f => f.Online).ToList();
        var offlineFriends = others.Where(f => !f.Online).ToList();
        if (others.Count > 0)
        {
            rows.Add(new SectionVm { Id = "online", Title = "ONLINE", Count = onlineFriends.Count });
            if (onlineFriends.Count == 0)
            {
                AddCard(rows, [new HintVm
                {
                    Id = "online-none",
                    Text = inParty.Count > 0 ? "No other friends are online." : "No friends are online right now.",
                }]);
            }
            else
            {
                AddCard(rows, onlineFriends.Select(Row));
            }
            if (offlineFriends.Count > 0)
            {
                rows.Add(new SectionVm
                {
                    Id = "offline", Title = "OFFLINE", Count = offlineFriends.Count,
                    Collapsible = true, Collapsed = _offlineCollapsed,
                });
                if (!_offlineCollapsed) AddCard(rows, offlineFriends.Select(Row));
            }
        }
        else if (s.Friends.Count == 0 && (rows.Count > 0 || s.Outgoing.Count > 0 || s.Blocked.Count > 0))
        {
            rows.Add(new SectionVm { Id = "friends", Title = "FRIENDS" });
            AddCard(rows, [new HintVm { Id = "friends-none", Text = "No friends yet. Use the + at the top to add someone." }]);
        }

        // 5. Requests you sent, and people you blocked: last, quiet.
        if (s.Outgoing.Count > 0)
        {
            rows.Add(new SectionVm { Id = "sent", Title = "SENT", Count = s.Outgoing.Count });
            AddCard(rows, s.Outgoing.Select(r => (CardRowVm)new PendingVm { Uuid = r.Uuid, Name = r.Name }));
        }
        if (s.Blocked.Count > 0)
        {
            rows.Add(new SectionVm { Id = "blocked", Title = "BLOCKED", Count = s.Blocked.Count });
            AddCard(rows, s.Blocked.Select(b => (CardRowVm)new BlockedVm { Uuid = b.Uuid, Name = b.Name }));
        }

        var empty = rows.Count == 0;
        EmptyPane.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
        RowList.Visibility = empty ? Visibility.Collapsed : Visibility.Visible;
        KeyedDiff.Apply(_rows, rows, r => r.Key, r => r.Signature);

        var total = s.Friends.Count;
        SetHeader("Friends", total == 0
            ? ""
            : $"{s.Friends.Count(f => f.Online)} online - {total} {(total == 1 ? "friend" : "friends")}");
    }

    private void RenderChat()
    {
        var s = _snapshot;
        var me = s.Me?.Uuid ?? "";
        List<ChatRowVm> rows;
        string emptyTitle, emptyBody, hint;
        var offerInvite = false;
        var party = _chatKey == SocialSnapshot.PartyKey;

        StaleStrip.Visibility = s.Status == SocialStatus.Online ? Visibility.Collapsed : Visibility.Visible;

        if (party)
        {
            var members = s.Party?.Members ?? [];
            var online = members.Count(m => m.Online);
            SetHeader("Party chat", members.Count == 1 ? "Just you so far" : $"{members.Count} members - {online} online");
            ChatBar.Visibility = Visibility.Collapsed;
            ChatBarDivider.Visibility = Visibility.Collapsed;
            ChatMoreButton.Visibility = Visibility.Collapsed;
            rows = ChatLineVm.Build(s.PartyChat.Select(m => (m.Id, m.From, m.Name, m.Text, m.At)), me);
            emptyTitle = "The party chat is quiet";
            offerInvite = members.Count <= 1;
            emptyBody = offerInvite ? "You're the only one here. Invite a friend to start chatting." : "Messages here go to everyone in your party.";
            hint = "Message the party";
        }
        else
        {
            var friend = s.Friend(_chatKey!);
            if (friend == null) return;
            SetHeader(friend.Name, StatusFull(friend), dot: friend.Online);
            var inMyParty = s.Party?.Members.Any(m => m.Uuid == friend.Uuid) == true;
            ChatJoinButton.Visibility = friend.IsOnServer ? Visibility.Visible : Visibility.Collapsed;
            ChatInviteButton.Visibility = inMyParty ? Visibility.Collapsed : Visibility.Visible;
            var bar = friend.IsOnServer || !inMyParty;
            ChatBar.Visibility = bar ? Visibility.Visible : Visibility.Collapsed;
            ChatBarDivider.Visibility = bar ? Visibility.Visible : Visibility.Collapsed;
            ChatMoreButton.Visibility = Visibility.Visible;
            rows = ChatLineVm.Build(s.ConversationWith(friend.Uuid)
                .Select(m => (m.Id, m.From, friend.Name, m.Text, m.At)), me);
            emptyTitle = "No messages yet";
            emptyBody = friend.Online
                ? $"Say hi to {friend.Name}."
                : $"{friend.Name} is offline. They'll see your message when they're back.";
            hint = $"Message {friend.Name}";
        }

        ChatEmptyTitle.Text = emptyTitle;
        ChatEmptyBody.Text = emptyBody;
        ChatEmptyAction.Visibility = offerInvite ? Visibility.Visible : Visibility.Collapsed;
        ChatHint.Text = hint;

        var atBottom = ChatScroll.ScrollableHeight - ChatScroll.VerticalOffset < 24;
        var lastBefore = _chatRows.Count > 0 ? _chatRows[^1].Key : null;
        var changed = KeyedDiff.Apply(_chatRows, rows, r => r.Key, r => r.Signature);
        ChatEmpty.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (!changed) return;

        // Something NEW arrived only when the newest row is a different one (trimming the
        // oldest lines of a full 200-line chat doesn't count).
        var newest = rows.Count > 0 ? rows[^1] : null;
        if (newest == null || newest.Key == lastBefore) return;
        if (atBottom || lastBefore == null || (newest as ChatLineVm)?.IsMine == true)
        {
            Dispatcher.BeginInvoke(() => ChatScroll.ScrollToEnd(), DispatcherPriority.Loaded);
            JumpButton.Visibility = Visibility.Collapsed;
        }
        else
        {
            JumpButton.Visibility = Visibility.Visible;
        }
    }

    private void BackToList(bool render = true)
    {
        CloseMenu();
        _chatKey = null;
        _chatRows.Clear();
        JumpButton.Visibility = Visibility.Collapsed;
        ChatError.Visibility = Visibility.Collapsed;
        if (_shown) Service.SetOpenConversation(null);
        if (render)
        {
            _renderDirty = true;
            Render();
        }
    }

    private void SetAddOpen(bool open)
    {
        _addOpen = open;
        AddRow.Visibility = open && _chatKey == null ? Visibility.Visible : Visibility.Collapsed;
        if (open) Dispatcher.BeginInvoke(() => AddFriendBox.Focus(), DispatcherPriority.Input);
    }

    // ---- actions ------------------------------------------------------------------

    /// <summary>Runs one action with its button disabled; shows the failure sentence. True = ok.</summary>
    private async Task<bool> RunAsync(object? sender, Func<Task<SocialActionResult>> action, string? success = null)
    {
        var button = sender as Button;
        if (button != null) button.IsEnabled = false;
        try
        {
            var result = await action();
            if (!result.Ok)
            {
                if (!string.IsNullOrEmpty(result.Error)) ShowNotice(result.Error, error: true);
                return false;
            }
            if (success != null) ShowNotice(success, error: false);
            return true;
        }
        finally
        {
            if (button != null) button.IsEnabled = true;
        }
    }

    /// <summary>One action per thing at a time: a second press while the first is asking or running does nothing.</summary>
    private async Task GuardedAsync(string key, Func<Task> work)
    {
        if (!_busy.Add(key)) return;
        try
        {
            await work();
        }
        finally
        {
            _busy.Remove(key);
        }
    }

    private static T? Row<T>(object sender) where T : class => (sender as FrameworkElement)?.DataContext as T;

    private void Close_Click(object sender, RoutedEventArgs e) => CloseRequested?.Invoke(this, EventArgs.Empty);

    private void Back_Click(object sender, RoutedEventArgs e) => BackToList();

    private void AddToggle_Click(object sender, RoutedEventArgs e) => SetAddOpen(!_addOpen);

    private void EmptyAdd_Click(object sender, RoutedEventArgs e) => SetAddOpen(true);

    private void MessageAction_Click(object sender, RoutedEventArgs e)
    {
        switch (_snapshot.Status)
        {
            case SocialStatus.NotSignedIn:
                AddAccountRequested?.Invoke(this, EventArgs.Empty);
                break;
            case SocialStatus.SignInFailed:
                Service.ResetAccount(); // clears the sign-in backoff and tries again now
                break;
            default:
                Service.Poke();
                break;
        }
    }

    private void Section_Click(object sender, RoutedEventArgs e)
    {
        if (Row<SectionVm>(sender) is not { Id: "offline" }) return;
        _offlineCollapsed = !_offlineCollapsed;
        _renderDirty = true;
        Render();
    }

    // ---- add friend ---------------------------------------------------------------

    private async void AddFriend_Click(object sender, RoutedEventArgs e) => await AddFriendAsync();

    private async void AddFriendBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        e.Handled = true;
        await AddFriendAsync();
    }

    private async Task AddFriendAsync()
    {
        var name = AddFriendBox.Text.Trim();
        if (name.Length == 0)
        {
            AddFriendBox.Focus();
            return;
        }
        HideNotice();
        if (await RunAsync(AddFriendButton, () => Service.AddFriendAsync(name), $"Friend request sent to {name}."))
            AddFriendBox.Text = "";
        else if (NoticeStrip.Visibility == Visibility.Visible)
            SetAddInvalid(true);
        AddFriendBox.Focus();
    }

    private void AddFriendBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        AddFriendHint.Visibility = AddFriendBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        SetAddInvalid(false);
    }

    private async void AppearOffline_Changed(object sender, RoutedEventArgs e)
    {
        if (_syncingToggle) return;
        var value = AppearOfflineToggle.IsChecked == true;
        _appearOfflinePending = true;
        AppearOfflineToggle.IsEnabled = false;
        try
        {
            var ok = await RunAsync(null, () => Service.SetAppearOfflineAsync(value),
                value ? "You now appear offline to your friends." : "Your friends can see you again.");
            if (!ok)
            {
                _syncingToggle = true;
                AppearOfflineToggle.IsChecked = !value;
                _syncingToggle = false;
            }
        }
        finally
        {
            _appearOfflinePending = false;
            AppearOfflineToggle.IsEnabled = true;
        }
    }

    // ---- rows and their menu ---------------------------------------------------------

    private void FriendRow_Click(object sender, RoutedEventArgs e)
    {
        // A press on one of the row's own buttons bubbles here too: only the row itself opens the chat.
        if (!ReferenceEquals(e.Source, sender)) return;
        if (Row<FriendVm>(sender) is { } row) OpenConversation(row.Uuid);
    }

    private void FriendJoin_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<FriendVm>(sender) is { } row) JoinFriend(row.Uuid);
    }

    private void FriendMore_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<FriendVm>(sender) is { } row)
            OpenMenu(BuildMenu(row.Uuid, inChat: false, member: false), (FrameworkElement)sender, atMouse: false);
    }

    // Right-click or the Menu key on a row: the same menu, at the pointer.
    private void FriendRow_ContextMenuOpening(object sender, ContextMenuEventArgs e)
    {
        e.Handled = true;
        if (Row<FriendVm>(sender) is { } row)
            OpenMenu(BuildMenu(row.Uuid, inChat: false, member: false), (FrameworkElement)sender, atMouse: e.CursorLeft >= 0);
    }

    private void MemberRow_Click(object sender, RoutedEventArgs e)
    {
        if (!ReferenceEquals(e.Source, sender)) return;
        if (Row<MemberVm>(sender) is { Clickable: true } row) OpenConversation(row.Uuid);
    }

    private void MemberJoin_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<MemberVm>(sender) is { } row) JoinFriend(row.Uuid);
    }

    private void MemberMore_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<MemberVm>(sender) is { } row)
            OpenMenu(BuildMenu(row.Uuid, inChat: false, member: true), (FrameworkElement)sender, atMouse: false);
    }

    private void MemberRow_ContextMenuOpening(object sender, ContextMenuEventArgs e)
    {
        e.Handled = true;
        if (Row<MemberVm>(sender) is { HasMenu: true } row)
            OpenMenu(BuildMenu(row.Uuid, inChat: false, member: true), (FrameworkElement)sender, atMouse: e.CursorLeft >= 0);
    }

    private void ChatMore_Click(object sender, RoutedEventArgs e)
    {
        if (_chatKey != null && _chatKey != SocialSnapshot.PartyKey)
            OpenMenu(BuildMenu(_chatKey, inChat: true, member: false), (FrameworkElement)sender, atMouse: false);
    }

    /// <summary>
    /// Opens a menu and holds back list redraws while it is open: a sync can no longer
    /// tear down the row the menu is anchored to. The flag follows the menu's own
    /// Opened / Closed events (and <see cref="CloseMenu"/>), so it can't stay stuck.
    /// The chosen item runs after the menu has closed, so focus the menu gives back to
    /// the row can't steal the focus an action such as "Message" has just placed.
    /// </summary>
    private void OpenMenu(ContextMenu menu, FrameworkElement target, bool atMouse)
    {
        if (menu.Items.Count == 0) return;
        CloseMenu();
        menu.PlacementTarget = target;
        menu.Placement = atMouse ? PlacementMode.MousePoint : PlacementMode.Bottom;
        menu.HorizontalOffset = atMouse ? 0 : Math.Min(0, target.ActualWidth - 196);
        menu.Opened += (_, _) =>
        {
            if (ReferenceEquals(_menu, menu)) _menuOpen = true;
        };
        menu.Closed += (_, _) =>
        {
            if (!ReferenceEquals(_menu, menu)) return;
            _menu = null;
            _menuOpen = false;
            var action = _menuAction;
            _menuAction = null;
            if (_renderDirty) Render();
            // Background priority: after the focus the closing menu restores has settled.
            if (action != null) Dispatcher.BeginInvoke(action, DispatcherPriority.Background);
        };
        _menu = menu;
        menu.IsOpen = true;
    }

    /// <summary>Closes any open menu and forgets its pending action; the list may draw again.</summary>
    private void CloseMenu()
    {
        var menu = _menu;
        _menu = null;
        _menuAction = null;
        _menuOpen = false;
        if (menu is { IsOpen: true }) menu.IsOpen = false;
    }

    /// <summary>
    /// A person's actions, grouped: message / join / invite (list rows), then Make leader and
    /// Kick (the leader, on a party member), then Remove and Block (a friend). In a chat the
    /// first group is already buttons. Items remember the uuid and look everything else up
    /// when clicked, so a stale server or name is never used.
    /// </summary>
    private ContextMenu BuildMenu(string uuid, bool inChat, bool member)
    {
        var s = _snapshot;
        var friend = s.Friend(uuid);
        var inParty = s.Party?.Members.Any(m => m.Uuid == uuid) == true;
        var groups = new List<List<MenuItem>>();

        var reach = new List<MenuItem>();
        if (!inChat && friend != null)
        {
            reach.Add(MenuItemFor("Message", "Icon.Chat", () => OpenConversation(uuid)));
            if (friend.IsOnServer)
                reach.Add(MenuItemFor($"Join {SocialText.DisplayServer(friend.Server!)}", "Icon.External", () => JoinFriend(uuid)));
            if (!inParty)
                reach.Add(MenuItemFor("Invite to party", "Icon.UserPlus", () => _ = InviteFriendAsync(uuid, null)));
        }
        groups.Add(reach);

        var party = new List<MenuItem>();
        if (member && s.IsPartyLeader && uuid != s.Me?.Uuid)
        {
            party.Add(MenuItemFor("Make leader", "Icon.Crown", () => _ = PromoteAsync(uuid)));
            party.Add(MenuItemFor("Kick from party", "Icon.UserMinus", () => _ = KickAsync(uuid), danger: true));
        }
        groups.Add(party);

        var person = new List<MenuItem>();
        if (friend != null)
        {
            person.Add(MenuItemFor("Remove friend", "Icon.Trash", () => _ = RemoveFriendAsync(uuid), danger: true));
            person.Add(MenuItemFor("Block", "Icon.Block", () => _ = BlockFriendAsync(uuid), danger: true));
        }
        groups.Add(person);

        var menu = new ContextMenu { Style = (Style)FindResource("Menu.Popup") };
        foreach (var group in groups.Where(g => g.Count > 0))
        {
            if (menu.Items.Count > 0) menu.Items.Add(new Separator { Style = (Style)FindResource("Menu.Separator") });
            foreach (var item in group) menu.Items.Add(item);
        }
        return menu;
    }

    private MenuItem MenuItemFor(string header, string iconKey, Action action, bool danger = false)
    {
        var brush = (Brush)FindResource(danger ? "Brush.Danger" : "Brush.TextDim");
        var item = new MenuItem
        {
            Header = header,
            Style = (Style)FindResource("Menu.Item"),
            Icon = new Path
            {
                Data = (Geometry)FindResource(iconKey),
                Style = (Style)FindResource("Icon.Base"),
                Width = 14,
                Height = 14,
                Stroke = brush,
            },
        };
        if (danger) item.Foreground = (Brush)FindResource("Brush.Danger");
        // Remembered, then run by OpenMenu once the menu has closed.
        item.Click += (_, _) => _menuAction = action;
        return item;
    }

    /// <summary>Joins whatever server the friend is on right now (looked up when pressed).</summary>
    private void JoinFriend(string uuid)
    {
        if (_snapshot.Friend(uuid) is not { } friend) return;
        if (!friend.IsOnServer)
        {
            ShowNotice($"{friend.Name} isn't on a server right now.", error: true);
            return;
        }
        JoinRequested?.Invoke(new JoinRequest(friend.Name, friend.Version, friend.Server));
    }

    private Task InviteFriendAsync(string uuid, object? sender) =>
        GuardedAsync($"invite:{uuid}", () => RunAsync(sender, () => Service.InviteToPartyAsync(uuid),
            $"Party invite sent to {_snapshot.NameOf(uuid)}."));

    private Task RemoveFriendAsync(string uuid) => GuardedAsync($"friend:{uuid}", async () =>
    {
        if (_snapshot.Friend(uuid) is not { } friend) return;
        if (!await Confirm.ShowAsync($"Remove {friend.Name}?",
                "You'll stop seeing each other online. You can add them again later.", "REMOVE", "CANCEL", destructive: true))
            return;
        if (await RunAsync(null, () => Service.RemoveFriendAsync(uuid)))
        {
            if (_chatKey == uuid) BackToList();
            ShowNotice($"{friend.Name} was removed from your friends.", error: false);
        }
    });

    private Task BlockFriendAsync(string uuid) => GuardedAsync($"friend:{uuid}", async () =>
    {
        if (_snapshot.Friend(uuid) is not { } friend) return;
        if (!await Confirm.ShowAsync($"Block {friend.Name}?",
                "They'll be removed from your friends and can't message you or send you requests. You can unblock them from the bottom of your friends list.",
                "BLOCK", "CANCEL", destructive: true))
            return;
        if (await RunAsync(null, () => Service.BlockAsync(uuid)))
        {
            if (_chatKey == uuid) BackToList();
            ShowNotice($"{friend.Name} is blocked.", error: false);
        }
    });

    private Task PromoteAsync(string uuid) => GuardedAsync($"lead:{uuid}", () =>
        RunAsync(null, () => Service.PromoteAsync(uuid), $"{_snapshot.NameOf(uuid)} now leads the party."));

    private Task KickAsync(string uuid) => GuardedAsync($"kick:{uuid}", async () =>
    {
        var name = _snapshot.NameOf(uuid);
        if (!await Confirm.ShowAsync($"Kick {name}?", "They'll be removed from the party. You can invite them again later.",
                "KICK", "CANCEL", destructive: true))
            return;
        await RunAsync(null, () => Service.KickAsync(uuid));
    });

    // ---- requests, invites, party ------------------------------------------------------

    private async void RequestAccept_Click(object sender, RoutedEventArgs e)
    {
        if (Row<RequestVm>(sender) is { } row)
            await RunAsync(sender, () => Service.RespondAsync(row.Uuid, true), $"You and {row.Name} are now friends.");
    }

    private async void RequestDecline_Click(object sender, RoutedEventArgs e)
    {
        if (Row<RequestVm>(sender) is { } row)
            await RunAsync(sender, () => Service.RespondAsync(row.Uuid, false));
    }

    private async void RequestCancel_Click(object sender, RoutedEventArgs e)
    {
        if (Row<PendingVm>(sender) is { } row)
            await RunAsync(sender, () => Service.RemoveFriendAsync(row.Uuid));
    }

    private async void InviteAccept_Click(object sender, RoutedEventArgs e)
    {
        if (Row<InviteVm>(sender) is { } row)
            await RunAsync(sender, () => Service.AcceptPartyAsync(row.PartyId), $"You joined {row.Name}'s party.");
    }

    private async void InviteDecline_Click(object sender, RoutedEventArgs e)
    {
        if (Row<InviteVm>(sender) is { } row)
            await RunAsync(sender, () => Service.DeclinePartyAsync(row.PartyId));
    }

    private async void Unblock_Click(object sender, RoutedEventArgs e)
    {
        if (Row<BlockedVm>(sender) is { } row)
            await RunAsync(sender, () => Service.UnblockAsync(row.Uuid), $"{row.Name} is unblocked.");
    }

    private void PartyChat_Click(object sender, RoutedEventArgs e) => OpenConversation(SocialSnapshot.PartyKey);

    private async void Warp_Click(object sender, RoutedEventArgs e)
    {
        if (SocialHub.Game.Current is not { Server: { } server } game)
        {
            ShowNotice("Join a server in Minecraft first, then warp your party to it.", error: true);
            return;
        }
        await RunAsync(sender, () => Service.WarpAsync(server, game.Version),
            $"Warp sent. Your party can join you on {SocialText.DisplayServer(server)}.");
    }

    private async void LeaveParty_Click(object sender, RoutedEventArgs e) =>
        await RunAsync(sender, () => Service.LeavePartyAsync(), "You left the party.");

    private void WarpJoin_Click(object sender, RoutedEventArgs e)
    {
        if (_snapshot.Warp is { } warp)
            JoinRequested?.Invoke(new JoinRequest(warp.Name ?? _snapshot.NameOf(warp.From), warp.Version, warp.Server));
    }

    // ---- chat pane ------------------------------------------------------------------

    private void ChatJoin_Click(object sender, RoutedEventArgs e)
    {
        if (_chatKey != null) JoinFriend(_chatKey);
    }

    private async void ChatInvite_Click(object sender, RoutedEventArgs e)
    {
        if (_chatKey != null) await InviteFriendAsync(_chatKey, sender);
    }

    // "Invite a friend" in an empty party chat: back to the list, where each friend's "..." menu has Invite.
    private void ChatEmptyAction_Click(object sender, RoutedEventArgs e)
    {
        BackToList();
        ShowNotice("Press ... on a friend, then choose Invite to party.", error: false);
    }

    private void Jump_Click(object sender, RoutedEventArgs e)
    {
        ChatScroll.ScrollToEnd();
        JumpButton.Visibility = Visibility.Collapsed;
        ChatInput.Focus();
    }

    private void ChatScroll_ScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        if (ChatScroll.ScrollableHeight - ChatScroll.VerticalOffset < 24) JumpButton.Visibility = Visibility.Collapsed;
    }

    private async void ChatSend_Click(object sender, RoutedEventArgs e) => await SendAsync();

    private async void ChatInput_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        e.Handled = true;
        await SendAsync();
    }

    private void ChatInput_TextChanged(object sender, TextChangedEventArgs e)
    {
        var length = ChatInput.Text.Length;
        ChatHint.Visibility = length == 0 ? Visibility.Visible : Visibility.Collapsed;
        // The 256-character limit only matters when you're near it.
        ChatCounter.Visibility = length >= 200 ? Visibility.Visible : Visibility.Collapsed;
        ChatCounter.Text = $"{length}/{SocialText.MaxMessageLength}";
        ChatCounter.Foreground = (Brush)FindResource(length >= 240 ? "Brush.Warning" : "Brush.TextMuted");
        // Keep the typed text clear of the counter.
        ChatInput.Padding = new Thickness(12, 9, length >= 200 ? 62 : 12, 9);
    }

    private async Task SendAsync()
    {
        var key = _chatKey;
        var text = ChatInput.Text;
        if (key == null || string.IsNullOrWhiteSpace(text) || !ChatSendButton.IsEnabled) return;

        ChatError.Visibility = Visibility.Collapsed;
        // The box empties at once, so what you type next can't be mistaken for (or re-send) this message.
        ChatInput.Text = "";
        var sent = await RunAsync(ChatSendButton, () => key == SocialSnapshot.PartyKey
            ? Service.PartyChatAsync(text)
            : Service.SendMessageAsync(key, text));
        // A failure (rate limit, offline) gives the text back, unless you've already started something new.
        if (!sent && _chatKey == key && ChatInput.Text.Length == 0) ChatInput.Text = text;
        ChatInput.Focus();
    }
}
