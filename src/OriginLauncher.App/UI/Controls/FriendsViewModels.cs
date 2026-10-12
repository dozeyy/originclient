using System.Windows;
using OriginLauncher.App.Core.Social;

namespace OriginLauncher.App.UI.Controls;

// Row models for the Friends drawer and its toasts. Plain immutable objects:
// the drawer rebuilds a list only when a row's Signature changes, so the 4 s
// sync while the drawer is open never flickers or drops a hover state.

public sealed class FriendRowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }
    public required string StatusLine { get; init; }
    public bool IsOnline { get; init; }
    public bool CanJoin { get; init; }
    public int Unread { get; init; }

    public string Initial => SocialText.Initial(Name);
    public bool HasUnread => Unread > 0;
    public string UnreadText => Unread > 9 ? "9+" : Unread.ToString();
    public double RowOpacity => IsOnline ? 1.0 : 0.6;
    public string Signature => $"{Uuid}|{Name}|{StatusLine}|{IsOnline}|{CanJoin}|{Unread}";
}

public sealed class RequestRowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }
    public bool IsIncoming { get; init; }

    public bool IsOutgoing => !IsIncoming;
    public string Initial => SocialText.Initial(Name);
    public string Caption => IsIncoming ? "Wants to be friends" : "Request sent";
    public string Signature => $"{Uuid}|{Name}|{IsIncoming}";
}

public sealed class PartyInviteVm
{
    public required string PartyId { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public string Signature => $"{PartyId}|{Name}";
}

public sealed class PartyMemberVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }
    public bool IsLeader { get; init; }
    public bool IsMe { get; init; }
    public bool IsOnline { get; init; }
    /// <summary>The viewer leads the party and this row is someone else: Lead / Kick show.</summary>
    public bool CanManage { get; init; }

    public string Initial => SocialText.Initial(Name);
    public string Caption => (IsLeader ? "Leader" : "Member") + (IsMe ? " · you" : IsOnline ? " · online" : " · offline");
    public string Signature => $"{Uuid}|{Name}|{IsLeader}|{IsMe}|{IsOnline}|{CanManage}";
}

public sealed class BlockedRowVm
{
    public required string Uuid { get; init; }
    public required string Name { get; init; }

    public string Initial => SocialText.Initial(Name);
    public string Signature => $"{Uuid}|{Name}";
}

public sealed class ChatLineVm
{
    public required string Id { get; init; }
    public required string Text { get; init; }
    public required string Time { get; init; }
    public bool IsMine { get; init; }
    public string? Author { get; init; }

    public bool ShowAuthor => !IsMine && !string.IsNullOrEmpty(Author);
    public HorizontalAlignment Align => IsMine ? HorizontalAlignment.Right : HorizontalAlignment.Left;
    public string Signature => Id;

    /// <summary>"14:05" today, "Oct 11, 14:05" before that, in the player's own clock format.</summary>
    public static string FormatTime(long atMs)
    {
        if (atMs <= 0) return "";
        var local = DateTimeOffset.FromUnixTimeMilliseconds(atMs).ToLocalTime();
        var time = local.ToString("t");
        return local.Date == DateTime.Now.Date ? time : $"{local:MMM d}, {time}";
    }
}
