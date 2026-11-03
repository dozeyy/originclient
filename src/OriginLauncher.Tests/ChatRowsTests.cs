using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The chat's rows (FriendsViewModels.cs, linked unchanged by the Windows and the Mac launcher): day dividers, five-minute
/// groups and the gap between them, so both drawers lay a conversation out the same way.
/// </summary>
public class ChatRowsTests
{
    private static readonly long Noon = new DateTimeOffset(DateTime.Today.AddHours(12)).ToUnixTimeMilliseconds();
    private const long Minute = 60_000;

    private static List<ChatRowVm> Build(params (string Id, string From, long At)[] messages) =>
        ChatLineVm.Build(messages.Select(m => (m.Id, m.From, m.From == "me" ? "Me" : "Steve", "hi", m.At)), "me");

    [Fact]
    public void OneDayStartsWithOneDivider()
    {
        var rows = Build(("1", "steve", Noon), ("2", "me", Noon + Minute));
        Assert.IsType<ChatDayVm>(rows[0]);
        Assert.Equal("Today", ((ChatDayVm)rows[0]).Label);
        Assert.Single(rows.OfType<ChatDayVm>());
        Assert.Equal(3, rows.Count);
    }

    [Fact]
    public void SamePersonWithinFiveMinutesSitsInOneGroup()
    {
        var lines = Build(("1", "steve", Noon), ("2", "steve", Noon + 4 * Minute), ("3", "steve", Noon + 10 * Minute))
            .OfType<ChatLineVm>().ToList();
        Assert.Equal([true, false, true], lines.Select(l => l.StartsGroup));
        Assert.Equal([14d, 6d, 14d], lines.Select(l => l.GapTop));
    }

    [Fact]
    public void MyLinesSayYouAndBreakTheGroup()
    {
        var lines = Build(("1", "steve", Noon), ("2", "me", Noon + Minute)).OfType<ChatLineVm>().ToList();
        Assert.Equal("Steve", lines[0].Author);
        Assert.Equal("You", lines[1].Author);
        Assert.True(lines[1].IsMine);
        Assert.True(lines[1].StartsGroup);
    }

    [Fact]
    public void ANewDayGetsItsOwnDividerAndGroup()
    {
        var yesterday = Noon - 24 * 60 * Minute;
        var rows = Build(("1", "steve", yesterday), ("2", "steve", Noon));
        Assert.Equal(["Yesterday", "Today"], rows.OfType<ChatDayVm>().Select(d => d.Label));
        Assert.True(rows.OfType<ChatLineVm>().Last().StartsGroup);
    }
}
