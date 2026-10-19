using OriginLauncher.App.Core.Import;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Which instance to import from (D7) and which Minecraft versions Origin can take (D8).
public class VersionPickerTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 18, 12, 0, 0, TimeSpan.Zero);

    private static DetectedInstance I(string name, string mc = "1.21.1", bool pinned = false, double? hours = null, int? daysAgo = null) => new()
    {
        Kind = SourceKind.Prism, Name = name, GameDir = "/x/" + name, McVersion = mc, Pinned = pinned,
        PlaytimeSeconds = hours == null ? null : (long)(hours * 3600),
        LastPlayed = daysAgo == null ? null : Now.AddDays(-daysAgo.Value),
    };

    [Fact]
    public void ExactlyOnePinnedInstanceIsUsedAutomatically()
    {
        var pick = VersionPicker.Pick(new[] { I("a", hours: 50), I("b", pinned: true, hours: 1), I("c", hours: 20) });
        Assert.Equal(PickMode.Auto, pick.Mode);
        Assert.Equal("b", pick.Preselected!.Name);
    }

    [Fact]
    public void TwoPinnedInstancesAskTheUserToChooseBetweenOnlyThose()
    {
        var pick = VersionPicker.Pick(new[] { I("a", pinned: true, daysAgo: 5), I("b", pinned: true, daysAgo: 1), I("c", hours: 99) });
        Assert.Equal(PickMode.Choose, pick.Mode);
        Assert.Equal(new[] { "b", "a" }, pick.Options.Select(o => o.Name));
        Assert.Equal("b", pick.Preselected!.Name);
    }

    [Fact]
    public void TenHoursAgainstFiveHoursIsAClearWinner()
    {
        var pick = VersionPicker.Pick(new[] { I("small", hours: 5), I("big", hours: 10) });
        Assert.Equal(PickMode.Auto, pick.Mode);
        Assert.Equal("big", pick.Preselected!.Name);
    }

    [Fact]
    public void TenHoursAgainstEightHoursAsksWithTheTopThreeInPlaytimeOrder()
    {
        var pick = VersionPicker.Pick(new[] { I("c", hours: 2), I("a", hours: 10), I("b", hours: 8), I("d", hours: 1) });
        Assert.Equal(PickMode.Choose, pick.Mode);
        Assert.Equal(new[] { "a", "b", "c" }, pick.Options.Select(o => o.Name));
        Assert.Equal("a", pick.Preselected!.Name);
    }

    [Theory]
    [InlineData(54000, 36000, PickMode.Auto)]     // exactly 1.5x (15 h vs 10 h): a clear winner
    [InlineData(53999, 36000, PickMode.Choose)]   // one second short of 1.5x: ask
    [InlineData(72000, 36000, PickMode.Auto)]     // exactly 2x
    [InlineData(71999, 36000, PickMode.Auto)]     // just under 2x is still over 1.5x
    [InlineData(36000, 36000, PickMode.Choose)]   // a tie
    public void TheClearWinnerRuleIsExactlyOneAndAHalfTimesTheRunnerUp(long topSeconds, long runnerUpSeconds, PickMode expected)
    {
        var pick = VersionPicker.Pick(new[]
        {
            I("runner-up") with { PlaytimeSeconds = runnerUpSeconds },
            I("top") with { PlaytimeSeconds = topSeconds },
        });

        Assert.Equal(expected, pick.Mode);
        if (topSeconds > runnerUpSeconds) Assert.Equal("top", pick.Preselected!.Name);
    }

    [Fact]
    public void AnInstanceWithPlaytimeBeatsOneWithNoneEvenWhenOnlyOneHasData()
    {
        var pick = VersionPicker.Pick(new[] { I("never", daysAgo: 1), I("played", hours: 3) });
        Assert.Equal(PickMode.Auto, pick.Mode);
        Assert.Equal("played", pick.Preselected!.Name);
    }

    [Fact]
    public void RecencyOnlyAsksWithTheMostRecentPreselected()
    {
        var pick = VersionPicker.Pick(new[] { I("old", daysAgo: 30), I("newest", daysAgo: 1), I("mid", daysAgo: 10) });
        Assert.Equal(PickMode.Choose, pick.Mode);
        Assert.Equal("newest", pick.Preselected!.Name);
        Assert.Equal(new[] { "newest", "mid", "old" }, pick.Options.Select(o => o.Name));
    }

    [Fact]
    public void ALoneCandidateIsUsedAutomatically()
    {
        var pick = VersionPicker.Pick(new[] { I("only") });
        Assert.Equal(PickMode.Auto, pick.Mode);
        Assert.Equal("only", pick.Preselected!.Name);
    }

    [Fact]
    public void NoDataAtAllAsksWithTheNewestMinecraftVersionFirst()
    {
        var pick = VersionPicker.Pick(new[] { I("a", mc: "1.16.5"), I("b", mc: "1.21.1"), I("c", mc: "1.20.4") });
        Assert.Equal(PickMode.Choose, pick.Mode);
        Assert.Equal(new[] { "b", "c", "a" }, pick.Options.Select(o => o.Name));
    }

    [Fact]
    public void NoCandidatesIsNone() =>
        Assert.Equal(PickMode.None, VersionPicker.Pick(Array.Empty<DetectedInstance>()).Mode);

    // ---- D8: supported targets ----

    private static readonly string[] Supported = { "1.21.11", "1.21.4", "1.21.1", "1.20.4", "1.20.1", "1.16.5", "26.3" };

    [Theory]
    [InlineData("1.21.5", "1.21.4")]
    [InlineData("1.20.6", "1.20.4")]
    public void AnUnsupportedPatchSuggestsTheNearestLowerPatchOfTheSameLine(string source, string suggestion)
    {
        var check = VersionPicker.CheckSupport(source, Supported);
        Assert.False(check.Supported);
        Assert.Contains(source, check.Reason);
        Assert.Contains("Origin doesn't support it", check.Reason);
        Assert.Equal(suggestion, check.Suggestion);
    }

    [Theory]
    [InlineData("1.12.2")]
    [InlineData("26.4-snapshot-3")]
    [InlineData("24w14a")]
    public void AVersionWithNoSameLineSuggestsASupportedOneAndNamesItself(string source)
    {
        var check = VersionPicker.CheckSupport(source, Supported);
        Assert.False(check.Supported);
        Assert.Contains(source, check.Reason);
        Assert.Contains("Origin doesn't support it", check.Reason);
        Assert.Contains(check.Suggestion, Supported);
    }

    [Fact]
    public void ASnapshotOfALineSuggestsThatLinesNearestSupportedRelease()
    {
        Assert.Equal("26.3", VersionPicker.CheckSupport("26.4-snapshot-3", Supported).Suggestion);
        Assert.Equal("1.16.5", VersionPicker.CheckSupport("1.12.2", Supported).Suggestion);
    }

    [Fact]
    public void ASupportedVersionNeedsNoSuggestion()
    {
        var check = VersionPicker.CheckSupport("1.21.1", Supported);
        Assert.True(check.Supported);
        Assert.Null(check.Reason);
    }

    [Fact]
    public void SupportedTargetsAreTheCatalogVersionsThatAlsoHaveAnOriginBuild()
    {
        var catalog = new[] { "26.3", "1.21.11", "1.21.1", "1.12.2", "1.8.9" };
        var builds = new[] { "1.21.1", "1.21.11", "1.20.4", "26.3", "1.8.9" };
        var targets = VersionPicker.SupportedTargets(catalog, builds);
        Assert.Equal(new[] { "26.3", "1.21.11", "1.21.1", "1.8.9" }, targets);
    }
}
