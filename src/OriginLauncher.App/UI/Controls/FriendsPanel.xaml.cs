using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
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
/// </summary>
public partial class FriendsPanel : UserControl
{
    public event EventHandler? CloseRequested;
    public event EventHandler? AddAccountRequested;
    public event Action<JoinRequest>? JoinRequested;

    private static SocialService Service => SocialHub.Service;

    private SocialSnapshot _snapshot = new();
    // null = the list; a uuid = that friend's chat; SocialSnapshot.PartyKey = party chat.
    private string? _chatKey;
    private bool _shown;
    private bool _syncingToggle;
    private bool _appearOfflinePending;

    public FriendsPanel()
    {
        InitializeComponent();
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
        ChatList.Tag = null;
        if (_shown) Service.SetOpenConversation(key);
        Render();
        Dispatcher.BeginInvoke(() => ChatInput.Focus(), DispatcherPriority.Input);
    }

    /// <summary>Esc: close the confirm, else leave the chat. False = nothing to do, close the drawer.</summary>
    public bool HandleEscape()
    {
        if (Confirm.Visibility == Visibility.Visible)
        {
            Confirm.Dismiss();
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
        if (_chatKey != null)
        {
            ChatError.Text = text;
            ChatError.Foreground = (Brush)FindResource(error ? "Brush.Danger" : "Brush.TextDim");
            ChatError.Visibility = Visibility.Visible;
            return;
        }
        NoticeText.Text = text;
        NoticeText.Foreground = (Brush)FindResource(error ? "Brush.Danger" : "Brush.AccentHover");
        NoticeText.Visibility = Visibility.Visible;
    }

    private void HideNotice() => NoticeText.Visibility = Visibility.Collapsed;

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

        MessagePane.Visibility = Visibility.Collapsed;
        ListPane.Visibility = _chatKey == null ? Visibility.Visible : Visibility.Collapsed;
        ChatPane.Visibility = _chatKey != null ? Visibility.Visible : Visibility.Collapsed;
        BackButton.Visibility = _chatKey != null ? Visibility.Visible : Visibility.Collapsed;

        if (_chatKey == null)
        {
            SetHeader("Friends", null);
            RenderList();
        }
        else
        {
            RenderChat();
        }
    }

    private void SetHeader(string title, string? subtitle)
    {
        HeaderTitle.Text = title;
        HeaderSubtitle.Text = subtitle ?? "";
        HeaderSubtitle.Visibility = string.IsNullOrEmpty(subtitle) ? Visibility.Collapsed : Visibility.Visible;
    }

    private void ShowMessage(SocialSnapshot s)
    {
        ListPane.Visibility = Visibility.Collapsed;
        ChatPane.Visibility = Visibility.Collapsed;
        BackButton.Visibility = Visibility.Collapsed;
        MessagePane.Visibility = Visibility.Visible;
        SetHeader("Friends", null);

        var (title, body, action) = s.Status switch
        {
            SocialStatus.NotSignedIn => ("Sign in to use friends",
                "Sign in with Microsoft to use friends — add your account and you're set.", "ADD MICROSOFT ACCOUNT"),
            SocialStatus.SignInFailed => ("Couldn't sign in to friends",
                s.StatusMessage ?? "Origin couldn't confirm your Minecraft account.", "TRY AGAIN"),
            SocialStatus.Unreachable => ("Friends are offline right now",
                "Origin can't reach its friends service. It keeps trying in the background.", "TRY AGAIN"),
            _ => ("Connecting…", "Signing in to Origin friends.", null),
        };
        MessageTitle.Text = title;
        MessageBody.Text = body;
        MessageAction.Content = action;
        MessageAction.Visibility = action == null ? Visibility.Collapsed : Visibility.Visible;
    }

    private void RenderList()
    {
        var s = _snapshot;
        if (s.Status != SocialStatus.Online && !s.HasData) return;
        var me = s.Me?.Uuid ?? "";

        StaleText.Visibility = s.Status == SocialStatus.Online ? Visibility.Collapsed : Visibility.Visible;

        if (!_appearOfflinePending)
        {
            _syncingToggle = true;
            AppearOfflineToggle.IsChecked = s.Me?.AppearOffline == true;
            _syncingToggle = false;
        }

        // Warp from the party leader.
        if (s.Warp is { } warp && warp.From != me)
        {
            WarpText.Text = $"{warp.Name ?? s.NameOf(warp.From)} warped the party to {SocialText.DisplayServer(warp.Server)}";
            WarpCard.Visibility = Visibility.Visible;
        }
        else
        {
            WarpCard.Visibility = Visibility.Collapsed;
        }

        // Party.
        if (s.Party is { } party)
        {
            PartySection.Visibility = Visibility.Visible;
            PartyHeader.Text = $"PARTY · {party.Members.Count}/8";
            var leader = s.IsPartyLeader;
            var members = party.Members
                .OrderByDescending(m => m.Uuid == party.Leader)
                .ThenByDescending(m => m.Online)
                .ThenBy(m => m.Name, StringComparer.OrdinalIgnoreCase)
                .Select(m => new PartyMemberVm
                {
                    Uuid = m.Uuid,
                    Name = m.Name,
                    IsLeader = m.Uuid == party.Leader,
                    IsMe = m.Uuid == me,
                    IsOnline = m.Online,
                    CanManage = leader && m.Uuid != me,
                })
                .ToList();
            SetItems(PartyList, members, m => m.Signature);

            PartyChatButton.Content = s.UnreadParty > 0 ? $"PARTY CHAT · {s.UnreadParty}" : "PARTY CHAT";
            WarpButton.Visibility = leader ? Visibility.Visible : Visibility.Collapsed;
            var game = SocialHub.Game.Current;
            WarpButton.IsEnabled = game?.Server != null;
            WarpButton.ToolTip = game?.Server is { } server
                ? $"Ask your party to join you on {SocialText.DisplayServer(server)} ({game.Version})"
                : "Join a server in Minecraft first (started from this launcher), then warp your party to it";
        }
        else
        {
            PartySection.Visibility = Visibility.Collapsed;
        }

        var invites = s.PartyInvites.Select(i => new PartyInviteVm { PartyId = i.PartyId, Name = i.Name }).ToList();
        SetItems(InviteList, invites, i => i.Signature);
        InvitesSection.Visibility = invites.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        var requests = s.Incoming.Select(r => new RequestRowVm { Uuid = r.Uuid, Name = r.Name, IsIncoming = true })
            .Concat(s.Outgoing.Select(r => new RequestRowVm { Uuid = r.Uuid, Name = r.Name, IsIncoming = false }))
            .ToList();
        SetItems(RequestList, requests, r => r.Signature);
        RequestsSection.Visibility = requests.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        FriendRowVm Row(FriendInfo f) => new()
        {
            Uuid = f.Uuid,
            Name = f.Name,
            StatusLine = SocialText.StatusLine(f),
            IsOnline = f.Online,
            CanJoin = f.IsOnServer,
            Unread = s.Unread.GetValueOrDefault(f.Uuid),
        };
        var online = s.Friends.Where(f => f.Online).Select(Row).ToList();
        var offline = s.Friends.Where(f => !f.Online).Select(Row).ToList();
        SetItems(OnlineList, online, f => f.Signature);
        SetItems(OfflineList, offline, f => f.Signature);
        OnlineHeader.Text = $"ONLINE — {online.Count}";
        OfflineHeader.Text = $"OFFLINE — {offline.Count}";
        OnlineHeader.Visibility = online.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        OfflineHeader.Visibility = offline.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        EmptyText.Visibility = s.Friends.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        var blocked = s.Blocked.Select(b => new BlockedRowVm { Uuid = b.Uuid, Name = b.Name }).ToList();
        SetItems(BlockedList, blocked, b => b.Signature);
        BlockedHeader.Text = $"BLOCKED — {blocked.Count}";
        BlockedSection.Visibility = blocked.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RenderChat()
    {
        var s = _snapshot;
        var me = s.Me?.Uuid ?? "";
        List<ChatLineVm> lines;

        if (_chatKey == SocialSnapshot.PartyKey)
        {
            var count = s.Party?.Members.Count ?? 0;
            SetHeader("Party chat", count == 1 ? "Just you so far" : $"{count} members");
            ChatActions.Visibility = Visibility.Collapsed;
            lines = s.PartyChat.Select(m => new ChatLineVm
            {
                Id = m.Id,
                Text = m.Text,
                Time = ChatLineVm.FormatTime(m.At),
                IsMine = m.From == me,
                Author = m.Name,
            }).ToList();
        }
        else
        {
            var friend = s.Friend(_chatKey!);
            if (friend == null) return;
            SetHeader(friend.Name, SocialText.StatusLine(friend));
            ChatActions.Visibility = Visibility.Visible;
            ChatJoinButton.Visibility = friend.IsOnServer ? Visibility.Visible : Visibility.Collapsed;
            var inMyParty = s.Party?.Members.Any(m => m.Uuid == friend.Uuid) == true;
            ChatInviteButton.Visibility = inMyParty ? Visibility.Collapsed : Visibility.Visible;
            lines = s.ConversationWith(friend.Uuid).Select(m => new ChatLineVm
            {
                Id = m.Id,
                Text = m.Text,
                Time = ChatLineVm.FormatTime(m.At),
                IsMine = m.From == me,
            }).ToList();
        }

        var atBottom = ChatScroll.ScrollableHeight - ChatScroll.VerticalOffset < 24;
        var changed = SetItems(ChatList, lines, l => l.Signature);
        ChatEmpty.Visibility = lines.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (changed && (atBottom || lines.LastOrDefault()?.IsMine == true))
            Dispatcher.BeginInvoke(() => ChatScroll.ScrollToEnd(), DispatcherPriority.Loaded);
    }

    /// <summary>Swaps a list's items only when something visible changed. True = it did.</summary>
    private static bool SetItems<T>(ItemsControl list, IReadOnlyList<T> items, Func<T, string> signature)
    {
        var sig = string.Join("\n", items.Select(signature));
        if (list.Tag is string old && old == sig) return false;
        list.Tag = sig;
        list.ItemsSource = items;
        return true;
    }

    private void BackToList(bool render = true)
    {
        _chatKey = null;
        ChatList.Tag = null;
        if (_shown) Service.SetOpenConversation(null);
        if (render) Render();
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

    private void FriendRow_Click(object sender, MouseButtonEventArgs e)
    {
        if (Row<FriendRowVm>(sender) is { } row) OpenConversation(row.Uuid);
    }

    private void FriendJoin_Click(object sender, RoutedEventArgs e)
    {
        if (Row<FriendRowVm>(sender) is { } row && _snapshot.Friend(row.Uuid) is { } friend)
            JoinRequested?.Invoke(new JoinRequest(friend.Name, friend.Version, friend.Server));
    }

    private async void RequestAccept_Click(object sender, RoutedEventArgs e)
    {
        if (Row<RequestRowVm>(sender) is { } row)
            await RunAsync(sender, () => Service.RespondAsync(row.Uuid, true), $"You and {row.Name} are now friends.");
    }

    private async void RequestDecline_Click(object sender, RoutedEventArgs e)
    {
        if (Row<RequestRowVm>(sender) is { } row)
            await RunAsync(sender, () => Service.RespondAsync(row.Uuid, false));
    }

    private async void RequestCancel_Click(object sender, RoutedEventArgs e)
    {
        if (Row<RequestRowVm>(sender) is { } row)
            await RunAsync(sender, () => Service.RemoveFriendAsync(row.Uuid));
    }

    private async void InviteAccept_Click(object sender, RoutedEventArgs e)
    {
        if (Row<PartyInviteVm>(sender) is { } row)
            await RunAsync(sender, () => Service.AcceptPartyAsync(row.PartyId), $"You joined {row.Name}'s party.");
    }

    private async void InviteDecline_Click(object sender, RoutedEventArgs e)
    {
        if (Row<PartyInviteVm>(sender) is { } row)
            await RunAsync(sender, () => Service.DeclinePartyAsync(row.PartyId));
    }

    private async void MemberPromote_Click(object sender, RoutedEventArgs e)
    {
        if (Row<PartyMemberVm>(sender) is { } row)
            await RunAsync(sender, () => Service.PromoteAsync(row.Uuid), $"{row.Name} now leads the party.");
    }

    private async void MemberKick_Click(object sender, RoutedEventArgs e)
    {
        if (Row<PartyMemberVm>(sender) is not { } row) return;
        if (!await Confirm.ShowAsync($"Kick {row.Name}?", "They'll be removed from the party. You can invite them again later.",
                "KICK", "CANCEL"))
            return;
        await RunAsync(sender, () => Service.KickAsync(row.Uuid));
    }

    private async void Unblock_Click(object sender, RoutedEventArgs e)
    {
        if (Row<BlockedRowVm>(sender) is { } row)
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
            $"Warp sent — your party can join you on {SocialText.DisplayServer(server)}.");
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
        if (_chatKey != null && _snapshot.Friend(_chatKey) is { } friend)
            JoinRequested?.Invoke(new JoinRequest(friend.Name, friend.Version, friend.Server));
    }

    private async void ChatInvite_Click(object sender, RoutedEventArgs e)
    {
        if (_chatKey != null && _snapshot.Friend(_chatKey) is { } friend)
            await RunAsync(sender, () => Service.InviteToPartyAsync(friend.Uuid), $"Party invite sent to {friend.Name}.");
    }

    private async void ChatRemove_Click(object sender, RoutedEventArgs e)
    {
        if (_chatKey == null || _snapshot.Friend(_chatKey) is not { } friend) return;
        if (!await Confirm.ShowAsync($"Remove {friend.Name}?",
                "You'll stop seeing each other online. You can add them again later.", "REMOVE", "CANCEL"))
            return;
        if (await RunAsync(sender, () => Service.RemoveFriendAsync(friend.Uuid)))
        {
            BackToList();
            ShowNotice($"{friend.Name} was removed from your friends.", error: false);
        }
    }

    private async void ChatBlock_Click(object sender, RoutedEventArgs e)
    {
        if (_chatKey == null || _snapshot.Friend(_chatKey) is not { } friend) return;
        if (!await Confirm.ShowAsync($"Block {friend.Name}?",
                "They'll be removed from your friends and can't message you or send you requests. You can unblock them from the bottom of your friends list.",
                "BLOCK", "CANCEL"))
            return;
        if (await RunAsync(sender, () => Service.BlockAsync(friend.Uuid)))
        {
            BackToList();
            ShowNotice($"{friend.Name} is blocked.", error: false);
        }
    }

    private async void ChatSend_Click(object sender, RoutedEventArgs e) => await SendAsync();

    private async void ChatInput_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        e.Handled = true;
        await SendAsync();
    }

    private void ChatInput_TextChanged(object sender, TextChangedEventArgs e) =>
        ChatHint.Visibility = ChatInput.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;

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
