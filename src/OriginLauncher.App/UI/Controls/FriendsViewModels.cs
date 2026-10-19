using System.Globalization;
using System.Windows;
using OriginLauncher.App.Core.Social;

namespace OriginLauncher.App.UI.Controls;

// Row models for the Friends drawer. Plain immutable objects (apart from the one
// card position the list builder sets before the row is shown). The drawer keeps
// ONE flat list (sections and rows in order) and brings it in line by Key
// (KeyedDiff), replacing a row only where its Signature changed, so the 4 s sync
// while the drawer is open never flickers, drops a hover or focus state, or moves
// the scroll position.

/// <summary>One line of the drawer's list.</summary>
public abstract class RowVm
{
    /// <summary>Who this row is, stable across syncs (kind plus uuid).</summary>
    public abstract string Key { get; }

    /// <summary>Everything visible about the row: changes whenever the row must be redrawn.</summary>
    public virtual string Signature => Body;

    protected abstract string Body { get; }
}

/// <summary>A row drawn inside a card group; <see cref="Pos"/> is set when the group is assembled.</summary>
public abstract class CardRowVm : RowVm
{
    public CardPos Pos { get; set; } = CardPos.Only;

    public override string Signature => $"{Body}|{Pos}";
}

public sealed class SectionVm : RowVm
{
    public required string Id { get; init; }
    public required string Title { get; init; }
    public int Count { get; init; }
    /// <summary>Only a long, low-priority section folds (Offline).</summary>
    public bool Collapsible { get; init; }
    public bool Collapsed { get; init; }

    public string Heading => Count > 0 ? $"{Title} - {Count}" : Title;
    public override string Key => $"S|{Id}";
    protected override string Body => $"{Heading}|{Collapsed}";
}

/// <summary>A one-line note inside a card ("No friends are online right now").</summary>
public sealed class HintVm : CardRowVm
{
    public required string Id { get; init; }
    public required string Text { get; init; }
    public override string Key => $"H|{Id}";
    protected override string Body => Text;
}

/// <summary>The party leader asked everyone to join them.</summary>
public sealed class WarpVm : RowVm
{
    public required string Text { get; init; }
    public override string Key => "W";
    protected override string Body => Text;
}

public sealed class FriendVm : CardRowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }
    /// <summary>What they're doing, without the Minecraft version (the row has little room).</summary>
    public required string Status { get; init; }
    /// <summary>The full line, shown as a tooltip when the row trims it.</summary>
    public required string StatusFull { get; init; }
    /// <summary>The newest message, shown instead of the status while there is unread mail.</summary>
    public string? Preview { get; init; }
    public bool IsOnline { get; init; }
    public bool CanJoin { get; init; }
    public int Unread { get; init; }
    public string JoinTip { get; init; } = "Launch their Minecraft version and join their server";

    public string Initial => SocialText.Initial(Name);
    public bool HasUnread => Unread > 0;
    public string UnreadText => Unread > 9 ? "9+" : Unread.ToString(CultureInfo.InvariantCulture);
    public string Caption => HasUnread && !string.IsNullOrEmpty(Preview) ? Preview! : Status;
    public override string Key => $"F|{Uuid}";
    protected override string Body =>
        $"{Uuid}|{Name}|{Status}|{StatusFull}|{Preview}|{IsOnline}|{CanJoin}|{Unread}|{JoinTip}";
}

/// <summary>An incoming friend request.</summary>
public sealed class RequestVm : CardRowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public override string Key => $"R|{Uuid}";
    protected override string Body => $"{Uuid}|{Name}";
}

/// <summary>A request you sent that hasn't been answered.</summary>
public sealed class PendingVm : CardRowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public override string Key => $"P|{Uuid}";
    protected override string Body => $"{Uuid}|{Name}";
}

public sealed class InviteVm : CardRowVm
{
    public required string PartyId { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public override string Key => $"I|{PartyId}";
    protected override string Body => $"{PartyId}|{Name}";
}

/// <summary>
/// A party member: a full row like a friend's (a friend's row opens their chat and can
/// join their server), whose "..." menu also holds Make leader and Kick for the leader.
/// </summary>
public sealed class MemberVm : CardRowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }
    public required string Status { get; init; }
    public bool IsLeader { get; init; }
    public bool IsMe { get; init; }
    public bool IsOnline { get; init; }
    public bool IsFriend { get; init; }
    public bool CanJoin { get; init; }
    public bool HasMenu { get; init; }
    public int Unread { get; init; }
    public string JoinTip { get; init; } = "Launch their Minecraft version and join their server";

    public string Initial => SocialText.Initial(Name);
    public bool Clickable => IsFriend && !IsMe;
    public bool HasUnread => Unread > 0;
    public string UnreadText => Unread > 9 ? "9+" : Unread.ToString(CultureInfo.InvariantCulture);
    public override string Key => $"M|{Uuid}";
    protected override string Body =>
        $"{Uuid}|{Name}|{Status}|{IsLeader}|{IsMe}|{IsOnline}|{IsFriend}|{CanJoin}|{HasMenu}|{Unread}|{JoinTip}";
}

/// <summary>"PARTY - 3/8" with the party's actions on the same line.</summary>
public sealed class PartyHeaderVm : RowVm
{
    public required string Title { get; init; }
    public int Unread { get; init; }
    public bool IsLeader { get; init; }
    public bool CanWarp { get; init; }
    public required string WarpTip { get; init; }

    public string ChatText => Unread > 0 ? $"CHAT - {(Unread > 9 ? "9+" : Unread.ToString(CultureInfo.InvariantCulture))}" : "CHAT";
    public override string Key => "A";
    protected override string Body => $"{Title}|{Unread}|{IsLeader}|{CanWarp}|{WarpTip}";
}

public sealed class BlockedVm : CardRowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public override string Key => $"B|{Uuid}";
    protected override string Body => $"{Uuid}|{Name}";
}

// ---- chat -------------------------------------------------------------------------

public abstract class ChatRowVm
{
    /// <summary>A message's id, or the day for a divider: what makes it the same row on the next sync.</summary>
    public abstract string Key { get; }

    public abstract string Signature { get; }
}

/// <summary>"Today", "Yesterday" or "Oct 11" between messages of different days.</summary>
public sealed class ChatDayVm : ChatRowVm
{
    public required string DayKey { get; init; }
    public required string Label { get; init; }
    public override string Key => $"D|{DayKey}";
    public override string Signature => $"D|{DayKey}|{Label}";
}

/// <summary>
/// One message. Messages from the same person within five minutes form a group:
/// the first line carries the name and time, the rest sit tight underneath.
/// </summary>
public sealed class ChatLineVm : ChatRowVm
{
    public required string Id { get; init; }
    public required string Text { get; init; }
    public required string Author { get; init; }
    public required string DayKey { get; init; }
    public required string Time { get; init; }
    public required string FullTime { get; init; }
    public bool IsMine { get; init; }
    public bool StartsGroup { get; init; }

    // 14 px between groups, 6 px between lines of one group: the boundary reads at a glance.
    public Thickness Gap => new(0, StartsGroup ? 14 : 6, 0, 0);
    public Visibility HeaderVisibility => StartsGroup ? Visibility.Visible : Visibility.Collapsed;
    public override string Key => Id;
    public override string Signature => $"{Id}|{StartsGroup}|{Author}|{DayKey}|{Time}";

    public static string ShortTime(long atMs) =>
        atMs <= 0 ? "" : DateTimeOffset.FromUnixTimeMilliseconds(atMs).ToLocalTime().ToString("t");

    public static string LongTime(long atMs) =>
        atMs <= 0 ? "" : DateTimeOffset.FromUnixTimeMilliseconds(atMs).ToLocalTime().ToString("f");

    public static string DayLabel(long atMs)
    {
        var local = DateTimeOffset.FromUnixTimeMilliseconds(atMs).ToLocalTime().Date;
        var today = DateTime.Now.Date;
        if (local == today) return "Today";
        if (local == today.AddDays(-1)) return "Yesterday";
        return local.Year == today.Year ? local.ToString("MMM d") : local.ToString("MMM d, yyyy");
    }

    private static string DayKeyOf(DateTime day) => day.ToString("yyyyMMdd", CultureInfo.InvariantCulture);

    /// <summary>Messages in conversation order become day dividers and grouped lines.</summary>
    public static List<ChatRowVm> Build(IEnumerable<(string Id, string From, string Name, string Text, long At)> messages, string me)
    {
        var rows = new List<ChatRowVm>();
        string? lastFrom = null;
        long lastAt = 0;
        DateTime lastDay = default;
        foreach (var (id, from, name, text, at) in messages)
        {
            var day = at > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(at).ToLocalTime().Date : lastDay;
            var newDay = rows.Count == 0 || day != lastDay;
            if (newDay && at > 0) rows.Add(new ChatDayVm { DayKey = DayKeyOf(day), Label = DayLabel(at) });
            var mine = from == me;
            var group = newDay || from != lastFrom || at - lastAt > 5 * 60_000L;
            rows.Add(new ChatLineVm
            {
                Id = id,
                Text = text,
                Author = mine ? "You" : name,
                DayKey = DayKeyOf(day),
                Time = ShortTime(at),
                FullTime = LongTime(at),
                IsMine = mine,
                StartsGroup = group,
            });
            lastFrom = from;
            lastAt = at;
            lastDay = day;
        }
        return rows;
    }
}
