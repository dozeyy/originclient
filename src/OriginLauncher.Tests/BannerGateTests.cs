using OriginLauncher.App.Core.Promos;
using Xunit;

namespace OriginLauncher.Tests;

public class BannerVisibilityGateTests
{
    // On Home, window up, occlusion checked and clear.
    private static BannerVisibilityGate Visible()
    {
        var gate = new BannerVisibilityGate { OnHomePage = true };
        gate.Occluded = false;
        return gate;
    }

    [Fact]
    public void StartsInactiveUntilAnOcclusionCheckSaysVisible()
    {
        var gate = new BannerVisibilityGate { OnHomePage = true };
        Assert.True(gate.ShouldWatchOcclusion);
        Assert.False(gate.IsActive);

        gate.Occluded = false;
        Assert.True(gate.IsActive);
    }

    [Theory]
    [InlineData("minimized")]
    [InlineData("off-home")]
    [InlineData("home-scene")]
    [InlineData("chrome-overlay")]
    [InlineData("occluded")]
    [InlineData("locked")]
    public void EveryHidingConditionPauses(string condition)
    {
        var gate = Visible();
        Assert.True(gate.IsActive);

        switch (condition)
        {
            case "minimized": gate.WindowMinimized = true; break;
            case "off-home": gate.OnHomePage = false; break;
            case "home-scene": gate.HomeSceneOpen = true; break;
            case "chrome-overlay": gate.ChromeOverlayOpen = true; break;
            case "occluded": gate.Occluded = true; break;
            case "locked": gate.SessionInactive = true; break;
        }

        Assert.False(gate.IsActive);
    }

    [Fact]
    public void ReturningFromMinimizedWaitsForAFreshOcclusionCheck()
    {
        var gate = Visible();
        gate.WindowMinimized = true;
        Assert.False(gate.ShouldWatchOcclusion);

        gate.WindowMinimized = false;
        Assert.True(gate.ShouldWatchOcclusion);
        Assert.False(gate.IsActive); // the stale "visible" was forgotten

        gate.Occluded = false;
        Assert.True(gate.IsActive);
    }

    [Fact]
    public void OcclusionAnswersWhileNotWatchingAreIgnored()
    {
        var gate = new BannerVisibilityGate { OnHomePage = false };
        gate.Occluded = false;
        Assert.True(gate.Occluded);
        gate.OnHomePage = true;
        Assert.False(gate.IsActive);
    }

    [Fact]
    public void OverlaysDontStopOcclusionWatching()
    {
        var gate = Visible();
        gate.HomeSceneOpen = true;
        gate.ChromeOverlayOpen = true;
        Assert.True(gate.ShouldWatchOcclusion);

        gate.HomeSceneOpen = false;
        gate.ChromeOverlayOpen = false;
        Assert.True(gate.IsActive); // occlusion answer was kept fresh underneath
    }

    [Fact]
    public void ActiveChangedFiresOnlyOnRealFlips()
    {
        var gate = new BannerVisibilityGate();
        var events = new List<bool>();
        gate.ActiveChanged += events.Add;

        gate.OnHomePage = true;      // still occluded (unknown) → no event
        gate.Occluded = false;       // → active
        gate.Occluded = false;       // no change
        gate.HomeSceneOpen = true;   // → inactive
        gate.ChromeOverlayOpen = true; // already inactive
        gate.HomeSceneOpen = false;  // chrome overlay still up
        gate.ChromeOverlayOpen = false; // → active

        Assert.Equal(new[] { true, false, true }, events);
    }
}

public class OcclusionMathTests
{
    private static readonly ScreenRect Banner = new(100, 100, 500, 168); // 400 x 68

    [Fact]
    public void NothingAboveMeansNothingCovered() =>
        Assert.Equal(0, OcclusionMath.CoveredFraction(Banner, Array.Empty<ScreenRect>()));

    [Fact]
    public void AWindowOnAnotherMonitorDoesNotCount()
    {
        // e.g. Minecraft fullscreen on the monitor to the left.
        var game = new ScreenRect(-1920, 0, 0, 1080);
        Assert.Equal(0, OcclusionMath.CoveredFraction(Banner, new[] { game }));
        Assert.False(OcclusionMath.IsHidden(Banner, new[] { game }));
    }

    [Fact]
    public void AFullscreenWindowOnTheSameMonitorHidesIt()
    {
        var game = new ScreenRect(0, 0, 1920, 1080);
        Assert.Equal(1, OcclusionMath.CoveredFraction(Banner, new[] { game }));
        Assert.True(OcclusionMath.IsHidden(Banner, new[] { game }));
    }

    [Fact]
    public void OverlappingWindowsAreNotDoubleCounted()
    {
        // Two windows stacked over the same left quarter: 25 %, not 50 %.
        var a = new ScreenRect(0, 0, 200, 1000);
        var b = new ScreenRect(50, 50, 200, 900);
        Assert.Equal(0.25, OcclusionMath.CoveredFraction(Banner, new[] { a, b }), 6);
        Assert.False(OcclusionMath.IsHidden(Banner, new[] { a, b }));
    }

    [Fact]
    public void PiecesAddUpToTheThreshold()
    {
        var left = new ScreenRect(100, 100, 300, 168);   // left half
        var corner = new ScreenRect(300, 100, 301, 101); // one more pixel
        Assert.Equal(0.5, OcclusionMath.CoveredFraction(Banner, new[] { left }), 6);
        Assert.True(OcclusionMath.IsHidden(Banner, new[] { left }));
        Assert.True(OcclusionMath.CoveredFraction(Banner, new[] { left, corner }) > 0.5);
        Assert.False(OcclusionMath.IsHidden(Banner, new[] { new ScreenRect(100, 100, 299, 168) }));
    }

    [Fact]
    public void AnL_ShapedCoverIsExact()
    {
        var top = new ScreenRect(100, 100, 500, 134);    // top half
        var right = new ScreenRect(400, 100, 500, 168);  // right quarter, overlapping top
        // top half (0.5) + bottom-right eighth (0.125)
        Assert.Equal(0.625, OcclusionMath.CoveredFraction(Banner, new[] { top, right }), 6);
    }
}

public class PromoRotationTests
{
    private static PromoFeed FeedWithDiscord() => PromoFeedParser.TryParse("""
        { "schema": 1,
          "promos": [ { "id": "discord", "kind": "discord" }, { "id": "a", "title": "A" }, { "id": "b", "title": "B" } ],
          "discord": { "inviteCode": "abc123" } }
        """)!;

    [Fact]
    public void DiscordCardJoinsOnlyOnceServerInfoIsKnown()
    {
        var feed = FeedWithDiscord();
        Assert.Equal(new[] { "a", "b" }, PromoRotation.Playable(feed, discordReady: false).Select(p => p.Id));
        Assert.Equal(new[] { "discord", "a", "b" }, PromoRotation.Playable(feed, discordReady: true).Select(p => p.Id));
    }

    [Fact]
    public void NextWraps()
    {
        Assert.Equal(1, PromoRotation.Next(0, 3));
        Assert.Equal(0, PromoRotation.Next(2, 3));
        Assert.Equal(0, PromoRotation.Next(-1, 3));
        Assert.Equal(-1, PromoRotation.Next(0, 0));
    }

    [Fact]
    public void StaysOnTheSamePromoWhenTheListChanges()
    {
        var feed = FeedWithDiscord();
        var before = PromoRotation.Playable(feed, discordReady: false); // a, b
        var after = PromoRotation.Playable(feed, discordReady: true);   // discord, a, b
        Assert.Equal(2, PromoRotation.IndexAfterChange(after, before[1].Id));
        Assert.Equal(0, PromoRotation.IndexAfterChange(after, "gone"));
        Assert.Equal(0, PromoRotation.IndexAfterChange(after, null));
        Assert.Equal(-1, PromoRotation.IndexAfterChange(Array.Empty<HousePromo>(), "a"));
    }
}
