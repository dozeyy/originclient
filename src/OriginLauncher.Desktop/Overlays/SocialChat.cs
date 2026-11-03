using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// One conversation inside the friends drawer (WPF FriendsPanel chat pane): a friend's DMs or the party chat.
// A short action bar (Join their server, Invite to party), the message well (day rows, lines grouped by author
// within 5 minutes, "You" in accent), a "New messages" jump when lines arrive while scrolled up, and the input:
// Enter sends, the box clears at once and the text comes back if the send fails; a counter shows from 200 chars.
internal sealed class SocialChat : Grid
{
    private static SocialService Service => SocialHub.Service;
    private const long GroupGapMs = 5 * 60_000;

    private readonly FriendsDrawer _drawer;
    private readonly StackPanel _bar = new() { Orientation = Orientation.Horizontal, Spacing = Space.S2 };
    private readonly Border _barBox;
    private readonly ScrollViewer _scroll;
    private readonly StackPanel _lines = new() { Margin = new Thickness(Space.S3, Space.S2, Space.S3, Space.S3) };
    private readonly ContentControl _empty = new() { VerticalAlignment = VerticalAlignment.Center };
    private readonly Pressable _jump;
    private readonly TextBox _input;
    private readonly Pressable _send;
    private readonly TextBlock _counter = Ui.Caption("", Palette.TextMuted);
    private string? _key;
    private string _shownSig = "";
    private string _barSig = "";

    public SocialChat(FriendsDrawer drawer)
    {
        _drawer = drawer;
        RowDefinitions = new RowDefinitions("Auto,*,Auto");

        _barBox = new Border
        {
            BorderBrush = Palette.Line, BorderThickness = new Thickness(0, 0, 0, 1),
            Padding = new Thickness(Space.S4, 0, Space.S4, Space.S3), Child = _bar,
        };

        _scroll = new ScrollViewer { Content = _lines, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
        _scroll.ScrollChanged += (_, _) => { if (NearBottom()) _jump!.IsVisible = false; };
        _jump = Ui.Button("New messages", Look.Secondary, Icons.ChevronDown, Sizes.ControlSm);
        _jump.HorizontalAlignment = HorizontalAlignment.Center;
        _jump.VerticalAlignment = VerticalAlignment.Bottom;
        _jump.Margin = new Thickness(0, 0, 0, Space.S3);
        _jump.IsVisible = false;
        _jump.Click += (_, _) => { _scroll.ScrollToEnd(); _jump.IsVisible = false; };
        var wellInside = new Panel();
        wellInside.Children.Add(_scroll);
        wellInside.Children.Add(_empty);
        wellInside.Children.Add(_jump);
        var well = new Border
        {
            Background = Palette.BgAlt, CornerRadius = new CornerRadius(Radius.Lg), ClipToBounds = true,
            Margin = new Thickness(Space.S4, Space.S4, Space.S4, 0), Child = wellInside,
        };

        _input = new TextBox
        {
            MinHeight = Sizes.ControlLg, MaxLength = SocialText.MaxMessageLength, FontSize = TypeScale.Body,
            VerticalContentAlignment = VerticalAlignment.Center, Background = Palette.GhostRest,
            BorderThickness = new Thickness(0), TextWrapping = TextWrapping.Wrap, MaxHeight = 96,
        };
        _input.TextChanged += (_, _) => UpdateCounter();
        _input.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter && e.KeyModifiers == KeyModifiers.None) { e.Handled = true; _ = SendAsync(); }
        };
        _send = Ui.IconButton(Icons.Send, "Send (Enter)", Sizes.ControlLg, Look.Primary);
        _send.Click += (_, _) => _ = SendAsync();
        _counter.IsVisible = false;
        var inputRow = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto,Auto"), ColumnSpacing = Space.S2, Margin = new Thickness(Space.S3, Space.S3, Space.S4, Space.S4) };
        inputRow.Children.Add(_input.At(0));
        inputRow.Children.Add(_counter.At(1));
        inputRow.Children.Add(_send.At(2).Align(HorizontalAlignment.Right, VerticalAlignment.Bottom));

        Children.Add(_barBox.At(0, 0));
        Children.Add(well.At(0, 1));
        Children.Add(inputRow.At(0, 2));
        UpdateCounter();
    }

    /// <summary>A different conversation: start from its newest line, with an empty box.</summary>
    public void ResetFor(string? key)
    {
        if (key == _key) return;
        _key = key;
        _shownSig = "";
        _barSig = "";
        _input.Text = "";
        _jump.IsVisible = false;
        if (key != null) Dispatcher.UIThread.Post(() => _input.Focus(), DispatcherPriority.Input);
    }

    public void Render(SocialSnapshot s, string key)
    {
        _key = key;
        var party = key == SocialSnapshot.PartyKey;
        var friend = party ? null : s.Friend(key);
        var name = friend?.Name ?? s.NameOf(key);
        _input.PlaceholderText = party ? "Message the party" : $"Message {name}";
        RenderBar(s, key, friend);

        var meId = s.Me?.Uuid ?? "";
        var lines = party
            ? s.PartyChat.Select(m => (m.Id, m.From, Name: m.From == meId ? "You" : m.Name, m.Text, m.At)).ToList()
            : s.ConversationWith(key).Select(m => (m.Id, m.From, Name: m.From == meId ? "You" : name, m.Text, m.At)).ToList();
        var sig = lines.Count + "|" + (lines.Count > 0 ? lines[^1].Id : "");
        if (sig == _shownSig) return;
        var firstFill = _shownSig.Length == 0;
        var stick = firstFill || NearBottom();
        _shownSig = sig;

        _lines.Children.Clear();
        _empty.IsVisible = lines.Count == 0;
        _scroll.IsVisible = lines.Count > 0;
        if (lines.Count == 0)
        {
            _empty.Content = EmptyState(s, party, friend, name);
            return;
        }

        string? day = null;
        string? lastFrom = null;
        long lastAt = 0;
        StackPanel? group = null;
        foreach (var line in lines)
        {
            var local = DateTimeOffset.FromUnixTimeMilliseconds(line.At).ToLocalTime();
            var dayKey = local.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
            var newDay = dayKey != day;
            if (newDay)
            {
                day = dayKey;
                _lines.Children.Add(DayRow(local));
            }
            if (group == null || newDay || line.From != lastFrom || line.At - lastAt > GroupGapMs)
            {
                var head = Ui.Row(Space.S2,
                    Ui.Text(line.Name, TypeScale.Body, FontWeight.SemiBold, line.From == meId ? Palette.Accent : Palette.Text),
                    Ui.Text(local.ToString("h:mm tt", CultureInfo.InvariantCulture), TypeScale.Label, FontWeight.Normal, Palette.TextMuted));
                group = new StackPanel { Spacing = 6, Margin = new Thickness(0, _lines.Children.Count > 1 && !newDay ? 14 : 6, 0, 0) };
                group.Children.Add(head);
                _lines.Children.Add(group);
            }
            group.Children.Add(Ui.Wrap(Ui.Body(line.Text, Palette.Text)));
            lastFrom = line.From;
            lastAt = line.At;
        }

        if (stick) Dispatcher.UIThread.Post(() => _scroll.ScrollToEnd(), DispatcherPriority.Background);
        else _jump.IsVisible = true;
    }

    private void RenderBar(SocialSnapshot s, string key, FriendInfo? friend)
    {
        var party = key == SocialSnapshot.PartyKey;
        var inMyParty = s.Party?.Members.Any(m => m.Uuid == key) == true;
        var canInvite = friend != null && !inMyParty && (s.Party == null || s.IsPartyLeader);
        var sig = $"{key}|{friend?.IsOnServer}|{friend?.Server}|{friend?.Version}|{canInvite}";
        if (sig == _barSig) return;
        _barSig = sig;
        _bar.Children.Clear();
        if (!party && friend?.IsOnServer == true)
        {
            var join = Ui.Button("Join", Look.Primary, height: Sizes.ControlMd).Tip(FriendsDrawer.JoinTip(friend));
            join.Click += (_, _) => _ = FriendsDrawer.JoinAsync(_drawer.Window, friend.Name, friend.Server, friend.Version);
            _bar.Children.Add(join);
        }
        if (canInvite)
        {
            var invite = Ui.Button("Invite to party", Look.Secondary, Icons.UserPlus, Sizes.ControlMd);
            invite.Click += (_, _) => _ = _drawer.Act(invite, () => Service.InviteToPartyAsync(key), $"Party invite sent to {friend!.Name}.");
            _bar.Children.Add(invite);
        }
        _barBox.IsVisible = _bar.Children.Count > 0;
    }

    private Control EmptyState(SocialSnapshot s, bool party, FriendInfo? friend, string name)
    {
        string title, body;
        Pressable? action = null;
        if (party)
        {
            var alone = (s.Party?.Members.Count ?? 0) <= 1;
            title = "The party chat is quiet";
            body = alone ? "You're the only one here. Invite a friend to start chatting." : "Messages here go to everyone in your party.";
            if (alone)
            {
                action = Ui.Button("Invite a friend", Look.Secondary, Icons.UserPlus, Sizes.ControlMd);
                action.Click += (_, _) =>
                {
                    _drawer.OpenChat(null);
                    _drawer.ShowNotice("Press ... on a friend, then choose Invite to party.", error: false);
                };
            }
        }
        else
        {
            title = "No messages yet";
            body = friend is { Online: false } ? $"{name} is offline. They'll see your message when they're back." : $"Say hi to {name}.";
        }
        var col = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, MaxWidth = 260 };
        var t = Ui.Wrap(Ui.Title(title));
        t.TextAlignment = TextAlignment.Center;
        var b = Ui.Wrap(Ui.Body(body));
        b.TextAlignment = TextAlignment.Center;
        col.Children.Add(t);
        col.Children.Add(b.Margin(0, 6, 0, 0));
        if (action != null)
        {
            action.HorizontalAlignment = HorizontalAlignment.Center;
            action.Margin = new Thickness(0, Space.S4, 0, 0);
            col.Children.Add(action);
        }
        return col;
    }

    private static Control DayRow(DateTimeOffset local)
    {
        var today = DateTime.Now.Date;
        var d = local.Date;
        var label = d == today ? "Today" : d == today.AddDays(-1) ? "Yesterday"
            : d.Year == today.Year ? d.ToString("MMM d", CultureInfo.InvariantCulture) : d.ToString("MMM d, yyyy", CultureInfo.InvariantCulture);
        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto,*"), ColumnSpacing = Space.S3, Margin = new Thickness(0, Space.S2, 0, Space.S1) };
        g.Children.Add(Ui.Divider().At(0).Align(HorizontalAlignment.Stretch));
        g.Children.Add(Ui.Text(label, TypeScale.Label, FontWeight.Normal, Palette.TextMuted).At(1));
        g.Children.Add(Ui.Divider().At(2).Align(HorizontalAlignment.Stretch));
        return g;
    }

    private bool NearBottom() => _scroll.Offset.Y >= _scroll.Extent.Height - _scroll.Viewport.Height - 24;

    private void UpdateCounter()
    {
        var len = _input.Text?.Length ?? 0;
        _counter.IsVisible = len >= 200;
        _counter.Text = $"{len}/{SocialText.MaxMessageLength}";
        _counter.Foreground = len >= 240 ? Palette.Warning : Palette.TextMuted;
    }

    private async Task SendAsync()
    {
        var text = _input.Text?.Trim() ?? "";
        if (text.Length == 0 || _key == null || !_send.IsEnabled) return;
        var key = _key;
        _input.Text = "";
        var ok = await _drawer.Act(_send, () => key == SocialSnapshot.PartyKey ? Service.PartyChatAsync(text) : Service.SendMessageAsync(key, text));
        if (!ok && _key == key && string.IsNullOrEmpty(_input.Text)) _input.Text = text;
    }

    /// <summary>Render runs: a draft in the box.</summary>
    public void Draft(string text) => _input.Text = text;
}
