using OriginLauncher.App.UI;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The top-bar tab switch (UI/PageSwitch.cs, used by MainWindow.NavigateTo): a click on the page already shown does
/// nothing, and when tabs are spammed only the newest click's deferred refresh runs.
/// </summary>
public class PageSwitchTests
{
    private enum Page { Home, Mods, Profiles, Settings }

    [Fact]
    public void ClickOnTheShownPageDoesNothing()
    {
        var tabs = new PageSwitch<Page>(Page.Home);
        Assert.Null(tabs.Go(Page.Home));
        Assert.Equal(Page.Home, tabs.Current);

        Assert.NotNull(tabs.Go(Page.Mods));
        Assert.Null(tabs.Go(Page.Mods)); // a second click on the same tab: no second refresh
    }

    [Fact]
    public void SpammedTabsRefreshOnlyTheLastOne()
    {
        var tabs = new PageSwitch<Page>(Page.Home);
        var mods = tabs.Go(Page.Mods)!.Value;
        var profiles = tabs.Go(Page.Profiles)!.Value;
        var settings = tabs.Go(Page.Settings)!.Value;

        // The refreshes queued behind the clicks run after them: only the page the player stopped on refreshes.
        Assert.False(tabs.IsNewest(mods));
        Assert.False(tabs.IsNewest(profiles));
        Assert.True(tabs.IsNewest(settings));
        Assert.Equal(Page.Settings, tabs.Current);
    }

    [Fact]
    public void ComingBackToAPageTakesANewTicket()
    {
        var tabs = new PageSwitch<Page>(Page.Home);
        var firstMods = tabs.Go(Page.Mods)!.Value;
        tabs.Go(Page.Home);
        var secondMods = tabs.Go(Page.Mods)!.Value;

        // Mods is on screen again, but the first visit's refresh is still stale: it must not run on top of the new one.
        Assert.NotEqual(firstMods, secondMods);
        Assert.False(tabs.IsNewest(firstMods));
        Assert.True(tabs.IsNewest(secondMods));
    }
}
