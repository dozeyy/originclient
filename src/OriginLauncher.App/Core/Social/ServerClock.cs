namespace OriginLauncher.App.Core.Social;

/// <summary>
/// The real time, as Origin's service and Mojang see it, so a player whose PC
/// clock, date or time zone is wrong still gets friends, parties and chat (Will,
/// 2026-11-03: "the client doesnt need to verify your time and date on your pc").
/// The PC clock is never the truth for anything a server checks: every HTTPS
/// answer carries a <c>Date</c> header, and its difference to the PC clock is
/// kept here as an offset.
///
/// Two timelines, one rule:
///  - values that go TO a server or are compared with a server's times (the
///    /v1/auth <c>at</c>, the Mojang certificate's expiry) use <see cref="NowMs"/>;
///  - times that come FROM a server and are shown next to the PC clock (chat
///    times, "last seen") go through <see cref="ToLocalMs"/>.
///
/// <c>Date</c> has whole seconds, so an answer moves the offset only when it is
/// off by more than <see cref="ToleranceMs"/>: the offset stays put between syncs
/// and still follows a real change (the player fixes the clock while the launcher
/// runs). Until the first answer the offset is 0 (the PC clock). The mod has the
/// same class (social/ServerClock.java).
/// </summary>
public sealed class ServerClock
{
    /// <summary>An answer moves the offset only when it is off by more than this.</summary>
    public const long ToleranceMs = 2_000;

    /// <summary>A Date before this (2024-01-01) is a broken header, not a time.</summary>
    private const long SaneFloorMs = 1_704_067_200_000;

    private readonly Func<DateTimeOffset> _local;
    private readonly object _gate = new();
    private long _offsetMs;
    private bool _known;

    public ServerClock(Func<DateTimeOffset>? local = null) => _local = local ?? (() => DateTimeOffset.UtcNow);

    /// <summary>Server time minus the PC clock, ms.</summary>
    public long OffsetMs => Interlocked.Read(ref _offsetMs);

    /// <summary>The real time in epoch ms (the PC clock corrected by the last answer).</summary>
    public long NowMs => _local().ToUnixTimeMilliseconds() + OffsetMs;

    /// <summary>A server's epoch-ms time on the PC clock's timeline (0 / missing stays as is).</summary>
    public long ToLocalMs(long serverMs) => serverMs <= 0 ? serverMs : serverMs - OffsetMs;

    /// <summary>Takes an answer's <c>Date</c> header (null = the answer had none, or there was no answer).</summary>
    public void Observe(DateTimeOffset? serverDate)
    {
        if (serverDate is not { } date) return;
        var serverMs = date.ToUnixTimeMilliseconds();
        if (serverMs < SaneFloorMs) return;
        var localMs = _local().ToUnixTimeMilliseconds();
        lock (_gate)
        {
            Interlocked.Exchange(ref _offsetMs, NextOffset(_known, OffsetMs, serverMs, localMs));
            _known = true;
        }
    }

    /// <summary>
    /// The offset after one answer (pure). <c>Date</c> is truncated to the second,
    /// so the server's time is taken as the middle of that second; within
    /// <see cref="ToleranceMs"/> of the current offset (or of 0, first time) nothing moves.
    /// </summary>
    public static long NextOffset(bool known, long current, long serverMs, long localMs)
    {
        var candidate = serverMs + 500 - localMs;
        if (!known) return Math.Abs(candidate) <= ToleranceMs ? 0 : candidate;
        return Math.Abs(candidate - current) <= ToleranceMs ? current : candidate;
    }
}
