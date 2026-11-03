using OriginLauncher.App.Core.Social;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// ServerClock: the real time from the answers' Date header, so a wrong PC clock,
/// date or time zone never breaks friends (Will, 2026-11-03). The mod's
/// social/ServerClock.java has the same rules.
/// </summary>
public class ServerClockTests
{
    private static readonly DateTimeOffset Real = new(2026, 11, 3, 13, 1, 52, TimeSpan.Zero);
    private static long Ms(DateTimeOffset t) => t.ToUnixTimeMilliseconds();

    [Fact]
    public void UntilAnAnswerItIsThePcClock()
    {
        var clock = new ServerClock(() => Real.AddDays(-3));
        Assert.Equal(0, clock.OffsetMs);
        Assert.Equal(Ms(Real.AddDays(-3)), clock.NowMs);
        Assert.Equal(1234, clock.ToLocalMs(1234));
    }

    [Theory]
    [InlineData(-3 * 24 * 60)]   // three days behind
    [InlineData(5 * 60)]         // a wrong time zone, five hours ahead
    [InlineData(-7)]             // minutes off
    public void OneAnswerGivesTheRealTime(int pcOffsetMinutes)
    {
        var pc = Real.AddMinutes(pcOffsetMinutes);
        var clock = new ServerClock(() => pc);
        clock.Observe(Real);
        // Date is whole seconds: the real time is taken as the middle of that second.
        Assert.Equal(Ms(Real) + 500, clock.NowMs);
        Assert.Equal(Ms(Real) - Ms(pc) + 500, clock.OffsetMs);
        // Server times land on the PC clock's timeline.
        Assert.Equal(Ms(pc) - 500, clock.ToLocalMs(Ms(Real)));
        Assert.Equal(0, clock.ToLocalMs(0));
    }

    [Fact]
    public void ARightClockStaysUntouched()
    {
        var clock = new ServerClock(() => Real.AddMilliseconds(300));
        clock.Observe(Real);
        Assert.Equal(0, clock.OffsetMs);
    }

    [Fact]
    public void SecondJitterDoesNotMoveIt()
    {
        var pc = Real.AddHours(-2);
        var clock = new ServerClock(() => pc);
        clock.Observe(Real);
        var first = clock.OffsetMs;
        pc = pc.AddSeconds(10);
        clock.Observe(Real.AddSeconds(11));   // one second "late" next time: within tolerance
        Assert.Equal(first, clock.OffsetMs);
    }

    [Fact]
    public void ItFollowsAClockFixedWhileRunning()
    {
        var pc = Real.AddDays(-1);
        var clock = new ServerClock(() => pc);
        clock.Observe(Real);
        Assert.NotEqual(0, clock.OffsetMs);
        pc = Real;   // the player set the right time
        clock.Observe(Real);
        Assert.Equal(500, clock.OffsetMs);
        Assert.Equal(Ms(Real) + 500, clock.NowMs);
    }

    [Fact]
    public void NoDateOrABrokenOneIsIgnored()
    {
        var clock = new ServerClock(() => Real.AddDays(-3));
        clock.Observe(null);
        clock.Observe(DateTimeOffset.UnixEpoch);
        Assert.Equal(0, clock.OffsetMs);
    }

    [Theory]
    [InlineData(false, 0, 10_000, 0, 10_500)]
    [InlineData(false, 0, 1_000, 0, 0)]
    [InlineData(true, 10_000, 9_000, 0, 10_000)]
    [InlineData(true, 10_000, 20_000, 0, 20_500)]
    public void NextOffsetRules(bool known, long current, long server, long local, long expected) =>
        Assert.Equal(expected, ServerClock.NextOffset(known, current, server, local));
}
