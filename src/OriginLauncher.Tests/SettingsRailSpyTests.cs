using OriginLauncher.App.UI.Pages;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The Settings rail (2026-10-25 bug: the first click on "Origin Client" lit "Developer"). The last sections are
/// shorter than the window, so a click on them scrolls to the end of the page and never reaches the top edge.
/// </summary>
public class SettingsRailSpyTests
{
    // Performance, Game, JVM, Client, Startup, Developer: section tops in page pixels, the page 1400 tall
    // in a 700 px window, so it scrolls 700. Client, Startup and Developer start past the end of the scroll.
    private static readonly double[] Tops = { 90, 500, 880, 1180, 1290, 1360 };
    private const double Scrollable = 700;
    private const int Client = 3;

    [Fact]
    public void OpensOnTheFirstSection()
    {
        Assert.Equal(0, SettingsRailSpy.Active(Tops, 0, Scrollable));
    }

    [Fact]
    public void ScrollingLightsTheSectionUnderTheTopEdge()
    {
        Assert.Equal(1, SettingsRailSpy.Active(Tops, 480, Scrollable));
        Assert.Equal(2, SettingsRailSpy.Active(Tops, 830, 1000)); // a taller page: 830 is not its end
    }

    [Fact]
    public void TheBottomOfThePageLightsTheFirstSectionInView()
    {
        // Scrolled by hand to the end (offset 700, window 700..1400, the bar covers 700..756): the first section whose
        // top is inside the window is Game-JVM at 880. The old rule lit the neighbour above it and never the last one.
        Assert.Equal(2, SettingsRailSpy.Active(Tops, Scrollable, Scrollable));
        Assert.NotEqual(Tops.Length - 1, SettingsRailSpy.Active(Tops, Scrollable, Scrollable));
    }

    [Fact]
    public void JustShortOfTheEndStillUsesTheTopEdgeRule()
    {
        Assert.Equal(1, SettingsRailSpy.Active(Tops, Scrollable - 40, Scrollable));
    }

    [Fact]
    public void AtTheEndWithNoSectionBelowTheBarFallsBackToTheTopEdgeRule()
    {
        // A page whose last section starts above the bar line: nothing is "inside the window", so the normal rule decides.
        double[] tops = { 90, 500 };
        Assert.Equal(1, SettingsRailSpy.Active(tops, 700, 700));
    }

    [Fact]
    public void AClickedItemWinsAtTheEndToo()
    {
        var spy = new SettingsRailSpy();
        spy.Click(Client);
        Assert.Equal(Client, spy.Resolve(Tops, Scrollable, Scrollable));
        spy.UserScrolled();
        Assert.Equal(2, spy.Resolve(Tops, Scrollable, Scrollable));
    }

    [Fact]
    public void AClickOnAShortLastSectionKeepsThatItem()
    {
        var spy = new SettingsRailSpy();
        spy.Click(Client);
        // The click scrolls as far as the page goes; that scroll event must not pick another item.
        var offset = SettingsRailSpy.TargetOffset(Tops[Client], Scrollable);
        Assert.Equal(Scrollable, offset);
        Assert.Equal(Client, spy.Resolve(Tops, offset, Scrollable));
        // A second event at the same place (layout settling) changes nothing.
        Assert.Equal(Client, spy.Resolve(Tops, offset, Scrollable));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    [InlineData(5)]
    public void EveryRailItemIsSelectableByClick(int index)
    {
        var spy = new SettingsRailSpy();
        spy.Click(index);
        var offset = SettingsRailSpy.TargetOffset(Tops[index], Scrollable);
        Assert.Equal(index, spy.Resolve(Tops, offset, Scrollable));
    }

    [Fact]
    public void ScrollingByHandEndsTheClick()
    {
        var spy = new SettingsRailSpy();
        spy.Click(Client);
        spy.UserScrolled();
        Assert.Null(spy.Pinned);
        Assert.Equal(1, spy.Resolve(Tops, 480, Scrollable));
    }

    [Fact]
    public void ReopeningThePageStartsOnTheFirstSection()
    {
        var spy = new SettingsRailSpy();
        spy.Click(Client);
        spy.Reset();
        Assert.Equal(0, spy.Resolve(Tops, 0, Scrollable));
    }

    [Fact]
    public void ClickTargetStaysInsideThePage()
    {
        Assert.Equal(0, SettingsRailSpy.TargetOffset(10, Scrollable));
        Assert.Equal(Tops[1] - SettingsRailSpy.Margin, SettingsRailSpy.TargetOffset(Tops[1], Scrollable));
        Assert.Equal(Scrollable, SettingsRailSpy.TargetOffset(5000, Scrollable));
        Assert.Equal(0, SettingsRailSpy.TargetOffset(500, 0)); // a page that does not scroll
    }

    [Fact]
    public void ARailWithoutDeveloperStillWorks()
    {
        // Release builds drop the last item: five sections, same rules.
        double[] five = { 90, 500, 880, 1180, 1290 };
        var spy = new SettingsRailSpy();
        spy.Click(4);
        Assert.Equal(4, spy.Resolve(five, SettingsRailSpy.TargetOffset(five[4], 700), 700));
        spy.Reset();
        Assert.Equal(0, spy.Resolve(five, 0, 700));
    }

    [Fact]
    public void AStalePinOutsideTheRailIsIgnored()
    {
        var spy = new SettingsRailSpy();
        spy.Click(5); // the Developer item, then the rail loses it
        double[] five = { 90, 500, 880, 1180, 1290 };
        Assert.Equal(0, spy.Resolve(five, 0, 700));
    }
}
