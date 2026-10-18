using System.Globalization;
using System.Windows;
using OriginLauncher.App.Core.Social;

namespace OriginLauncher.App.UI.Controls;

// Row models for the Friends drawer and its toasts. Plain immutable objects:
// the drawer keeps ONE flat list (sections and rows in order) and edits it in
// place only where a row's Signature changed, so the 4 s sync while the drawer
// is open never flickers, drops a hover state or moves the scroll position.

/// <summary>One line of the drawer's list. <see cref="Signature"/> changes whenever anything visible does.</summary>
public abstract class RowVm
{
    public abstract string Signature { get; }
}

public sealed class SectionVm : RowVm
{
    public required string Key { get; init; }
    public required string Title { get; init; }
    public int Count { get; init; }
    /// <summary>Only a long, low-priority section folds (Offline).</summary>
    public bool Collapsible { get; init; }
    public bool Collapsed { get; init; }

    public string Heading => Count > 0 ? $"{Title} · {Count}" : Title;
    public override string Signature => $"S|{Key}|{Heading}|{Collapsed}";
}

/// <summary>A one-line note inside a section ("No friends are online right now").</summary>
public sealed class HintVm : RowVm
{
    public required string Text { get; init; }
    public override string Signature => $"H|{Text}";
}

/// <summary>The party leader asked everyone to join them.</summary>
public sealed class WarpVm : RowVm
{
    public required string Text { get; init; }
    public override string Signature => $"W|{Text}";
}

public sealed class FriendVm : RowVm
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
    public bool CanInvite { get; init; }
    public int Unread { get; init; }

    public string Initial => SocialText.Initial(Name);
    public bool HasUnread => Unread > 0;
    public string UnreadText => Unread > 9 ? "9+" : Unread.ToString(CultureInfo.InvariantCulture);
    public string Caption => HasUnread && !string.IsNullOrEmpty(Preview) ? Preview! : Status;
    public string JoinTip { get; init; } = "Launch their Minecraft version and join their server";
    public bool ShowInvite => CanInvite && IsOnline;
    public override string Signature =>
        $"F|{Uuid}|{Name}|{Status}|{StatusFull}|{Preview}|{IsOnline}|{CanJoin}|{CanInvite}|{Unread}|{JoinTip}";
}

/// <summary>An incoming friend request.</summary>
public sealed class RequestVm : RowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public override string Signature => $"R|{Uuid}|{Name}";
}

/// <summary>A request you sent that hasn't been answered.</summary>
public sealed class PendingVm : RowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public override string Signature => $"P|{Uuid}|{Name}";
}

public sealed class InviteVm : RowVm
{
    public required string PartyId { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public override string Signature => $"I|{PartyId}|{Name}";
}

public sealed class MemberVm : RowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }
    public required string Status { get; init; }
    public bool IsLeader { get; init; }
    public bool IsMe { get; init; }
    public bool IsOnline { get; init; }
    /// <summary>The viewer leads the party and this row is someone else: Lead / Kick show.</summary>
    public bool CanManage { get; init; }

    public string Initial => SocialText.Initial(Name);
    public override string Signature => $"M|{Uuid}|{Name}|{Status}|{IsLeader}|{IsMe}|{IsOnline}|{CanManage}";
}

/// <summary>"YOUR PARTY · 3/8" with the party's three actions on the same line.</summary>
public sealed class PartyHeaderVm : RowVm
{
    public required string Title { get; init; }
    public int Unread { get; init; }
    public bool IsLeader { get; init; }
    public bool CanWarp { get; init; }
    public required string WarpTip { get; init; }

    public string ChatText => Unread > 0 ? $"CHAT · {(Unread > 9 ? "9+" : Unread.ToString(CultureInfo.InvariantCulture))}" : "CHAT";
    public override string Signature => $"A|{Title}|{Unread}|{IsLeader}|{CanWarp}|{WarpTip}";
}

public sealed class BlockedVm : RowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public override string Signature => $"B|{Uuid}|{Name}";
}

// ---- chat -------------------------------------------------------------------------

public abstract class ChatRowVm
{
    public abstract string Signature { get; }
}

/// <summary>"Today", "Yesterday" or "Oct 11" between messages of different days.</summary>
public sealed class ChatDayVm : ChatRowVm
{
    public required string Label { get; init; }
    public override string Signature => $"D|{Label}";
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
    public required string Time { get; init; }
    public required string FullTime { get; init; }
    public bool IsMine { get; init; }
    public bool StartsGroup { get; init; }

    public Thickness Gap => new(0, StartsGroup ? 12 : 2, 0, 0);
    public Visibility HeaderVisibility => StartsGroup ? Visibility.Visible : Visibility.Collapsed;
    public override string Signature => $"{Id}|{StartsGroup}";

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
            if (newDay && at > 0) rows.Add(new ChatDayVm { Label = DayLabel(at) });
            var mine = from == me;
            var group = newDay || from != lastFrom || at - lastAt > 5 * 60_000L;
            rows.Add(new ChatLineVm
            {
                Id = id,
                Text = text,
                Author = mine ? "You" : name,
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
