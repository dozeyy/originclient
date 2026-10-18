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
/// <see cref="Reconcile{T}"/> edits it in place, so a sync that changes one
/// friend's status touches one row: no flicker, no lost hover, no scroll jump.
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

    private SocialSnapshot _snapshot = new();
    // null = the list; a uuid = that friend's chat; SocialSnapshot.PartyKey = party chat.
    private string? _chatKey;
    private bool _shown;
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
            if (_chatKey == null) RenderList();
        });
        Render();
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
        if (shown && _chatKey == null) HideNotice();
    }

    /// <summary>Opens a conversation (a friend's uuid, or <see cref="SocialSnapshot.PartyKey"/>).</summary>
    public void OpenConversation(string key)
    {
        _chatKey = key;
        ChatInput.Text = "";
        ChatError.Visibility = Visibility.Collapsed;
        JumpButton.Visibility = Visibility.Collapsed;
        NoticeStrip.Visibility = Visibility.Collapsed;
        _chatRows.Clear();
        if (_shown) Service.SetOpenConversation(key);
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
    }

    private void Notice_Click(object sender, MouseButtonEventArgs e) => HideNotice();

    // Render harness only (UI/SocialShots.cs).
    internal Task<bool> TourShowConfirm() =>
        Confirm.ShowAsync("Remove Steve?", "You'll stop seeing each other online. You can add them again later.", "REMOVE", "CANCEL");

    internal void TourDismissConfirm() => Confirm.Dismiss();

    internal ContextMenu? TourFriendMenu(string uuid, bool inChat) =>
        _snapshot.Friend(uuid) is { } friend ? BuildFriendMenu(friend, inChat) : null;

    internal void TourSetAddOpen(bool open) => SetAddOpen(open);

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

    private void Render()
    {
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
        HeaderSubRow.Visibility = Visibility.Visible;

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
        HeaderTitle.Text = title;
        HeaderSubtitle.Text = subtitle;
        HeaderDot.Visibility = dot ? Visibility.Visible : Visibility.Collapsed;
        HeaderSubRow.Visibility = string.IsNullOrEmpty(subtitle) ? Visibility.Collapsed : Visibility.Visible;
    }

    private void ShowMessage(SocialSnapshot s)
    {
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
            SocialStatus.Unreachable => ("Friends are offline right now",
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

    /// <summary>The whole line, version included ("Playing on hypixel.net · 1.21.1", "Offline · last seen 3 h ago").</summary>
    private static string StatusFull(FriendInfo f) =>
        f.Online ? SocialText.StatusLine(f)
        : f.LastSeen > 0 ? $"Offline · last seen {Ago(f.LastSeen)}" : "Offline";

    /// <summary>The row's line: the same without the version, so the server name isn't cut off.</summary>
    private static string StatusShort(FriendInfo f)
    {
        if (!f.Online) return f.LastSeen > 0 ? $"Last seen {Ago(f.LastSeen)}" : "Offline";
        var line = SocialText.StatusLine(f);
        var tail = $" · {f.Version}";
        return !string.IsNullOrWhiteSpace(f.Version) && line.EndsWith(tail, StringComparison.Ordinal)
            ? line[..^tail.Length]
            : line;
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
            rows.Add(new SectionVm { Key = "needs", Title = "REQUESTS", Count = waiting });
            rows.AddRange(s.PartyInvites.Select(i => new InviteVm { PartyId = i.PartyId, Name = i.Name }));
            rows.AddRange(s.Incoming.Select(r => new RequestVm { Uuid = r.Uuid, Name = r.Name }));
        }

        // 3. Your party.
        var inParty = new HashSet<string>();
        if (s.Party is { } party)
        {
            var leader = s.IsPartyLeader;
            foreach (var m in party.Members) inParty.Add(m.Uuid);
            rows.Add(new PartyHeaderVm
            {
                Title = $"PARTY · {party.Members.Count}/8",
                Unread = s.UnreadParty,
                IsLeader = leader,
                CanWarp = game?.Server != null,
                WarpTip = game?.Server is { } server
                    ? $"Ask your party to join you on {SocialText.DisplayServer(server)} ({game.Version})"
                    : "Join a server in Minecraft first (started from this launcher), then warp your party to it",
            });
            rows.AddRange(party.Members
                .OrderByDescending(m => m.Uuid == party.Leader)
                .ThenByDescending(m => m.Online)
                .ThenBy(m => m.Name, StringComparer.OrdinalIgnoreCase)
                .Select(m => new MemberVm
                {
                    Uuid = m.Uuid,
                    Name = m.Name,
                    Status = m.Uuid == me ? "You" : s.Friend(m.Uuid) is { } f ? StatusShort(f) : m.Online ? "Online" : "Offline",
                    IsLeader = m.Uuid == party.Leader,
                    IsMe = m.Uuid == me,
                    IsOnline = m.Online,
                    CanManage = leader && m.Uuid != me,
                }));
        }

        // 4. Friends: online first, offline folds.
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
                CanInvite = !inParty.Contains(f.Uuid),
                Unread = unread,
                JoinTip = f.IsOnServer
                    ? $"Launch Minecraft {f.Version} and join {f.Name} on {SocialText.DisplayServer(f.Server!)}"
                    : "Join their server",
            };
        }
        var onlineFriends = s.Friends.Where(f => f.Online).ToList();
        var offlineFriends = s.Friends.Where(f => !f.Online).ToList();
        if (s.Friends.Count > 0)
        {
            rows.Add(new SectionVm { Key = "online", Title = "ONLINE", Count = onlineFriends.Count });
            if (onlineFriends.Count == 0) rows.Add(new HintVm { Text = "No friends are online right now." });
            rows.AddRange(onlineFriends.Select(Row));
            if (offlineFriends.Count > 0)
            {
                rows.Add(new SectionVm
                {
                    Key = "offline", Title = "OFFLINE", Count = offlineFriends.Count,
                    Collapsible = true, Collapsed = _offlineCollapsed,
                });
                if (!_offlineCollapsed) rows.AddRange(offlineFriends.Select(Row));
            }
        }
        else if (rows.Count > 0 || s.Outgoing.Count > 0 || s.Blocked.Count > 0)
        {
            rows.Add(new SectionVm { Key = "friends", Title = "FRIENDS" });
            rows.Add(new HintVm { Text = "No friends yet. Use the + at the top to add someone." });
        }

        // 5. Requests you sent, and people you blocked: last, quiet.
        if (s.Outgoing.Count > 0)
        {
            rows.Add(new SectionVm { Key = "sent", Title = "SENT", Count = s.Outgoing.Count });
            rows.AddRange(s.Outgoing.Select(r => new PendingVm { Uuid = r.Uuid, Name = r.Name }));
        }
        if (s.Blocked.Count > 0)
        {
            rows.Add(new SectionVm { Key = "blocked", Title = "BLOCKED", Count = s.Blocked.Count });
            rows.AddRange(s.Blocked.Select(b => new BlockedVm { Uuid = b.Uuid, Name = b.Name }));
        }

        var empty = rows.Count == 0;
        EmptyPane.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
        RowList.Visibility = empty ? Visibility.Collapsed : Visibility.Visible;
        Reconcile(_rows, rows, r => r.Signature);

        var total = s.Friends.Count;
        SetHeader("Friends", total == 0
            ? ""
            : $"{onlineFriends.Count} online · {total} {(total == 1 ? "friend" : "friends")}");
    }

    private void RenderChat()
    {
        var s = _snapshot;
        var me = s.Me?.Uuid ?? "";
        List<ChatRowVm> rows;
        string emptyTitle, emptyBody, hint;
        var party = _chatKey == SocialSnapshot.PartyKey;

        StaleStrip.Visibility = s.Status == SocialStatus.Online ? Visibility.Collapsed : Visibility.Visible;

        if (party)
        {
            var members = s.Party?.Members ?? [];
            var online = members.Count(m => m.Online);
            SetHeader("Party chat", members.Count == 1 ? "Just you so far" : $"{members.Count} members · {online} online");
            ChatBar.Visibility = Visibility.Collapsed;
            ChatMoreButton.Visibility = Visibility.Collapsed;
            rows = ChatLineVm.Build(s.PartyChat.Select(m => (m.Id, m.From, m.Name, m.Text, m.At)), me);
            emptyTitle = "The party chat is quiet";
            emptyBody = members.Count == 1 ? "Invite a friend to start chatting." : "Messages here go to everyone in your party.";
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
            ChatBar.Visibility = friend.IsOnServer || !inMyParty ? Visibility.Visible : Visibility.Collapsed;
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
        ChatHint.Text = hint;

        var atBottom = ChatScroll.ScrollableHeight - ChatScroll.VerticalOffset < 24;
        var changed = Reconcile(_chatRows, rows, r => r.Signature);
        ChatEmpty.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (!changed) return;

        // Newest in view: follow it when you were at the bottom or sent it; otherwise say there's more.
        if (atBottom || (rows.LastOrDefault() as ChatLineVm)?.IsMine == true)
        {
            Dispatcher.BeginInvoke(() => ChatScroll.ScrollToEnd(), DispatcherPriority.Loaded);
            JumpButton.Visibility = Visibility.Collapsed;
        }
        else
        {
            JumpButton.Visibility = Visibility.Visible;
        }
    }

    /// <summary>Edits a list in place so only rows that changed are rebuilt. True = something did.</summary>
    private static bool Reconcile<T>(ObservableCollection<T> target, IReadOnlyList<T> next, Func<T, string> signature)
    {
        var changed = false;
        var shared = Math.Min(target.Count, next.Count);
        for (var i = 0; i < shared; i++)
        {
            if (signature(target[i]) == signature(next[i])) continue;
            target[i] = next[i];
            changed = true;
        }
        while (target.Count > next.Count)
        {
            target.RemoveAt(target.Count - 1);
            changed = true;
        }
        for (var i = target.Count; i < next.Count; i++)
        {
            target.Add(next[i]);
            changed = true;
        }
        return changed;
    }

    private void BackToList(bool render = true)
    {
        _chatKey = null;
        _chatRows.Clear();
        JumpButton.Visibility = Visibility.Collapsed;
        ChatError.Visibility = Visibility.Collapsed;
        if (_shown) Service.SetOpenConversation(null);
        if (render) Render();
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
        if (Row<SectionVm>(sender) is not { Key: "offline" }) return;
        _offlineCollapsed = !_offlineCollapsed;
        RenderList();
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
        AddFriendBox.Focus();
    }

    private void AddFriendBox_TextChanged(object sender, TextChangedEventArgs e) =>
        AddFriendHint.Visibility = AddFriendBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;

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

    // ---- friend rows and their menu ---------------------------------------------------

    private void FriendRow_Click(object sender, RoutedEventArgs e)
    {
        // A press on one of the row's own buttons bubbles here too: only the row itself opens the chat.
        if (!ReferenceEquals(e.Source, sender)) return;
        if (Row<FriendVm>(sender) is { } row) OpenConversation(row.Uuid);
    }

    private void FriendJoin_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<FriendVm>(sender) is { } row && _snapshot.Friend(row.Uuid) is { } friend) JoinFriend(friend);
    }

    private async void FriendInvite_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<FriendVm>(sender) is { } row) await InviteFriendAsync(row.Uuid, row.Name, sender);
    }

    private void FriendMore_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (Row<FriendVm>(sender) is { } row && _snapshot.Friend(row.Uuid) is { } friend)
            OpenMenu(BuildFriendMenu(friend, inChat: false), (FrameworkElement)sender, atMouse: false);
    }

    // Right-click or the Menu key on a row: the same menu, at the pointer.
    private void FriendRow_ContextMenuOpening(object sender, ContextMenuEventArgs e)
    {
        e.Handled = true;
        if (Row<FriendVm>(sender) is { } row && _snapshot.Friend(row.Uuid) is { } friend)
            OpenMenu(BuildFriendMenu(friend, inChat: false), (FrameworkElement)sender, atMouse: e.CursorLeft >= 0);
    }

    private void ChatMore_Click(object sender, RoutedEventArgs e)
    {
        if (_chatKey != null && _chatKey != SocialSnapshot.PartyKey && _snapshot.Friend(_chatKey) is { } friend)
            OpenMenu(BuildFriendMenu(friend, inChat: true), (FrameworkElement)sender, atMouse: false);
    }

    private static void OpenMenu(ContextMenu menu, FrameworkElement target, bool atMouse)
    {
        menu.PlacementTarget = target;
        menu.Placement = atMouse ? PlacementMode.MousePoint : PlacementMode.Bottom;
        menu.HorizontalOffset = atMouse ? 0 : Math.Min(0, target.ActualWidth - 196);
        menu.IsOpen = true;
    }

    /// <summary>
    /// A friend's actions. In the list: message, join, invite, then the two that
    /// can't be undone with one click. In a chat the first three are already
    /// buttons, so the menu holds only Remove and Block.
    /// </summary>
    private ContextMenu BuildFriendMenu(FriendInfo friend, bool inChat)
    {
        var menu = new ContextMenu { Style = (Style)FindResource("Menu.Popup") };
        var inMyParty = _snapshot.Party?.Members.Any(m => m.Uuid == friend.Uuid) == true;

        if (!inChat)
        {
            menu.Items.Add(MenuItemFor("Message", "Icon.Chat", () => OpenConversation(friend.Uuid)));
            if (friend.IsOnServer)
                menu.Items.Add(MenuItemFor($"Join {SocialText.DisplayServer(friend.Server!)}", "Icon.External", () => JoinFriend(friend)));
            if (!inMyParty)
                menu.Items.Add(MenuItemFor("Invite to party", "Icon.UserPlus", () => _ = InviteFriendAsync(friend.Uuid, friend.Name, null)));
            menu.Items.Add(new Separator { Style = (Style)FindResource("Menu.Separator") });
        }
        menu.Items.Add(MenuItemFor("Remove friend", "Icon.UserMinus", () => _ = RemoveFriendAsync(friend.Uuid), danger: true));
        menu.Items.Add(MenuItemFor("Block", "Icon.Block", () => _ = BlockFriendAsync(friend.Uuid), danger: true));
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
        item.Click += (_, _) => action();
        return item;
    }

    private void JoinFriend(FriendInfo friend) =>
        JoinRequested?.Invoke(new JoinRequest(friend.Name, friend.Version, friend.Server));

    private Task<bool> InviteFriendAsync(string uuid, string name, object? sender) =>
        RunAsync(sender, () => Service.InviteToPartyAsync(uuid), $"Party invite sent to {name}.");

    private async Task RemoveFriendAsync(string uuid)
    {
        if (_snapshot.Friend(uuid) is not { } friend) return;
        if (!await Confirm.ShowAsync($"Remove {friend.Name}?",
                "You'll stop seeing each other online. You can add them again later.", "REMOVE", "CANCEL"))
            return;
        if (await RunAsync(null, () => Service.RemoveFriendAsync(friend.Uuid)))
        {
            if (_chatKey == friend.Uuid) BackToList();
            ShowNotice($"{friend.Name} was removed from your friends.", error: false);
        }
    }

    private async Task BlockFriendAsync(string uuid)
    {
        if (_snapshot.Friend(uuid) is not { } friend) return;
        if (!await Confirm.ShowAsync($"Block {friend.Name}?",
                "They'll be removed from your friends and can't message you or send you requests. You can unblock them from the bottom of your friends list.",
                "BLOCK", "CANCEL"))
            return;
        if (await RunAsync(null, () => Service.BlockAsync(friend.Uuid)))
        {
            if (_chatKey == friend.Uuid) BackToList();
            ShowNotice($"{friend.Name} is blocked.", error: false);
        }
    }

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

    private async void MemberPromote_Click(object sender, RoutedEventArgs e)
    {
        if (Row<MemberVm>(sender) is { } row)
            await RunAsync(sender, () => Service.PromoteAsync(row.Uuid), $"{row.Name} now leads the party.");
    }

    private async void MemberKick_Click(object sender, RoutedEventArgs e)
    {
        if (Row<MemberVm>(sender) is not { } row) return;
        if (!await Confirm.ShowAsync($"Kick {row.Name}?", "They'll be removed from the party. You can invite them again later.",
                "KICK", "CANCEL"))
            return;
        await RunAsync(sender, () => Service.KickAsync(row.Uuid));
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
        if (_chatKey != null && _snapshot.Friend(_chatKey) is { } friend) JoinFriend(friend);
    }

    private async void ChatInvite_Click(object sender, RoutedEventArgs e)
    {
        if (_chatKey != null && _snapshot.Friend(_chatKey) is { } friend)
            await InviteFriendAsync(friend.Uuid, friend.Name, sender);
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
        var sent = await RunAsync(ChatSendButton, () => key == SocialSnapshot.PartyKey
            ? Service.PartyChatAsync(text)
            : Service.SendMessageAsync(key, text));
        // Keep what was typed if it failed (rate limit, offline) so nothing is lost.
        if (sent && _chatKey == key && ChatInput.Text == text) ChatInput.Text = "";
        ChatInput.Focus();
    }
}
