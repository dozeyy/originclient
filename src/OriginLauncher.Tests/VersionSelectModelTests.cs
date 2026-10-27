using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests;

// The version selector's keys and wording (U3.2), on the real catalogue: families in order 26, 1.21, 1.20, 1.19, 1.18,
// 1.17, 1.16, 1.8 (4 across, 2 down); 1.21 has ten releases, 1.8 one.
public class VersionSelectModelTests
{
    private static VersionSelectNav Open(string? current = "1.21.1")
    {
        var nav = new VersionSelectNav(VersionCatalog.Groups);
        nav.Open(current);
        return nav;
    }

    private static NavResult Press(VersionSelectNav nav, params NavKey[] keys)
    {
        var last = new NavResult(NavKind.None);
        foreach (var key in keys) last = nav.Press(key);
        return last;
    }

    // ---- wording ----

    // Step 2's dropdown says nothing about downloading (Will, 2026-10-25): a release on disk wears a quiet emerald dot
    // (an update waiting still counts), everything else shows just its number.
    [Theory]
    [InlineData(InstallState.Installed, true)]
    [InlineData(InstallState.UpdateReady, true)]
    [InlineData(InstallState.NotInstalled, false)]
    [InlineData(InstallState.Partial, false)]
    [InlineData(InstallState.Unknown, false)]
    public void OnlyReleasesOnDiskWearTheDot(InstallState state, bool dot) =>
        Assert.Equal(dot, VersionStatus.ShowsInstalledDot(state));

    [Fact]
    public void FamilyBadge_CountsInstalledReleases_AndAnUpdateWaitingStillCounts()
    {
        var states = new[] { InstallState.Installed, InstallState.UpdateReady, InstallState.NotInstalled, InstallState.Partial };
        Assert.Equal(new StatusLine("2 of 4 installed", StatusDot.Success), VersionStatus.ForFamily(states));
    }

    [Fact]
    public void FamilyBadge_NothingInstalled_IsNotInstalled_OrFinishInstallWhenSomethingIsHalfThere()
    {
        Assert.Equal(new StatusLine("Not installed", StatusDot.Muted),
            VersionStatus.ForFamily([InstallState.NotInstalled, InstallState.NotInstalled]));
        Assert.Equal(new StatusLine("Finish install", StatusDot.Warning),
            VersionStatus.ForFamily([InstallState.NotInstalled, InstallState.Partial]));
    }

    [Fact]
    public void FamilyBadge_ForOneRelease_IsThatReleasesOwnState()
    {
        Assert.Equal("Installed", VersionStatus.ForFamily([InstallState.Installed])!.Text);
        Assert.Equal("Update ready", VersionStatus.ForFamily([InstallState.UpdateReady])!.Text);
        Assert.Equal("Not installed", VersionStatus.ForFamily([InstallState.NotInstalled])!.Text);
        Assert.Equal("Finish install", VersionStatus.ForFamily([InstallState.Partial])!.Text);
    }

    [Fact]
    public void FamilyBadge_WaitsForEveryRelease_SoTheCountNeverFlickers()
    {
        Assert.Null(VersionStatus.ForFamily([InstallState.Installed, InstallState.Unknown]));
        Assert.Null(VersionStatus.ForFamily([]));
    }

    // ---- step 1 ----

    [Fact]
    public void Open_StartsOnStepOne_OnTheFamilyHoldingTheSavedVersion()
    {
        var nav = Open("1.20.2");
        Assert.Equal(SelectorStep.Families, nav.Step);
        Assert.Equal("1.20", nav.Family.Key);
        Assert.Equal(-1, nav.ReleaseIndex); // nothing chosen until a family is opened and a release picked
        Assert.Null(nav.Release);
    }

    [Fact]
    public void Open_WithAVersionNobodyHolds_StartsOnTheFirstFamily()
    {
        Assert.Equal("26", Open("9.9.9").Family.Key);
        Assert.Equal("26", Open(null).Family.Key);
    }

    [Fact]
    public void Step1_ArrowsMoveOverTheGrid_AndNeverPickAnything()
    {
        var nav = Open("1.21.1"); // index 1, top row
        Assert.Equal(new NavResult(NavKind.FamilyFocused, 2), nav.Press(NavKey.Right));
        Assert.Equal(new NavResult(NavKind.FamilyFocused, 1), nav.Press(NavKey.Left));
        Assert.Equal(new NavResult(NavKind.FamilyFocused, 5), nav.Press(NavKey.Down)); // one row down = +4
        Assert.Equal(new NavResult(NavKind.FamilyFocused, 1), nav.Press(NavKey.Up));
        Assert.Equal("1.21.1", nav.CurrentVersion); // browsing picked nothing
    }

    [Fact]
    public void Step1_AMoveThatWouldLeaveTheGridDoesNothing()
    {
        var nav = Open("26.3"); // index 0
        Assert.Equal(NavKind.None, nav.Press(NavKey.Left).Kind);
        Assert.Equal(NavKind.None, nav.Press(NavKey.Up).Kind);
        Assert.Equal(0, nav.FamilyIndex);
        Press(nav, NavKey.End);
        Assert.Equal("1.8", nav.Family.Key);
        Assert.Equal(NavKind.None, nav.Press(NavKey.Right).Kind);
        Assert.Equal(NavKind.None, nav.Press(NavKey.Down).Kind); // must not jump to the last card from the bottom row
        Assert.Equal("1.8", nav.Family.Key);
        Press(nav, NavKey.Home);
        Assert.Equal(0, nav.FamilyIndex);
        // Right on the end of the top row continues on the next row (reading order), like every grid.
        Press(nav, NavKey.Right, NavKey.Right, NavKey.Right, NavKey.Right);
        Assert.Equal(4, nav.FamilyIndex);
    }

    [Fact]
    public void Step1_Enter_OpensTheFamily_OnChooseAVersion()
    {
        // Even the family holding the saved version opens with nothing chosen (Will, 2026-10-26): a preselected
        // release could not be clicked, since picking what a dropdown already shows changes nothing.
        var nav = Open("1.21.1");
        var opened = nav.Press(NavKey.Enter);
        Assert.Equal(new NavResult(NavKind.OpenedFamily, 1), opened);
        Assert.Equal(SelectorStep.Releases, nav.Step);
        Assert.Null(nav.Release);

        var other = Open("1.21.1");
        Press(other, NavKey.Right);
        other.Press(NavKey.Enter);
        Assert.Null(other.Release);
        Assert.Equal("1.21.1", other.CurrentVersion); // opening a family picked nothing
    }

    [Fact]
    public void Step1_Enter_OnAOneReleaseFamily_PicksItAndCloses()
    {
        var nav = Open("26.3");
        Press(nav, NavKey.End);
        var result = nav.Press(NavKey.Enter);
        Assert.Equal(new NavResult(NavKind.SelectAndClose, 7, "1.8.9"), result);
        Assert.Equal(SelectorStep.Families, nav.Step);
        Assert.Equal("1.8.9", nav.CurrentVersion);
    }

    [Fact]
    public void Step1_Escape_Closes_BackspaceDoesNothing()
    {
        var nav = Open();
        Assert.Equal(NavKind.None, nav.Press(NavKey.Backspace).Kind);
        Assert.Equal(NavKind.Close, nav.Press(NavKey.Escape).Kind);
    }

    // ---- step 2 ----

    [Fact]
    public void Step2_UpDown_MoveAndPickAsTheyGo()
    {
        var nav = Open("1.21.1");
        nav.Press(NavKey.Enter);
        Assert.Equal(NavKind.None, nav.Press(NavKey.Up).Kind); // nothing above "Choose a version"
        Assert.Equal(new NavResult(NavKind.Select, 1, "1.21.11"), nav.Press(NavKey.Down)); // Down takes the newest
        Assert.Equal(new NavResult(NavKind.Select, 1, "1.21.10"), nav.Press(NavKey.Down));
        Assert.Equal("1.21.10", nav.CurrentVersion);
        Assert.Equal(new NavResult(NavKind.Select, 1, "1.21.11"), nav.Press(NavKey.Up));
    }

    [Fact]
    public void Step2_ClampsAtTheEnds_HomeAndEndJump()
    {
        var nav = Open("1.21.11");
        nav.Press(NavKey.Enter);
        Assert.Equal(new NavResult(NavKind.Select, 1, "1.21.11"), nav.Press(NavKey.Down)); // the saved one, picked again
        Assert.Equal(NavKind.None, nav.Press(NavKey.Up).Kind); // already the newest
        Assert.Equal(new NavResult(NavKind.Select, 1, "1.21"), nav.Press(NavKey.End));
        Assert.Equal(NavKind.None, nav.Press(NavKey.Down).Kind);
        Assert.Equal(new NavResult(NavKind.Select, 1, "1.21.11"), nav.Press(NavKey.Home));
    }

    [Fact]
    public void Step2_Enter_OpensTheDropdown_AndLaunchesNothing()
    {
        var nav = Open("1.21.1");
        nav.Press(NavKey.Enter);
        Assert.Equal(new NavResult(NavKind.OpenList, 1), nav.Press(NavKey.Enter));
        nav.Press(NavKey.Down); // 1.21.11
        Assert.Equal(new NavResult(NavKind.OpenList, 1), nav.Press(NavKey.Enter));
    }

    [Fact]
    public void Step2_LeftAndBackspace_GoBackToStepOne_OnTheSameFamily()
    {
        foreach (var back in new[] { NavKey.Left, NavKey.Backspace })
        {
            var nav = Open("1.21.1");
            nav.Press(NavKey.Enter);
            Assert.Equal(new NavResult(NavKind.Back, 1), nav.Press(back));
            Assert.Equal(SelectorStep.Families, nav.Step);
            Assert.Equal("1.21", nav.Family.Key); // the keyboard is where it was
        }
    }

    [Fact]
    public void Step2_Escape_ClosesTheWholeSheet_AndRightDoesNothing()
    {
        var nav = Open();
        nav.Press(NavKey.Enter);
        Assert.Equal(NavKind.None, nav.Press(NavKey.Right).Kind);
        Assert.Equal(NavKind.Close, nav.Press(NavKey.Escape).Kind);
    }

    // ---- the mouse takes the same road ----

    [Fact]
    public void Clicks_GoThroughTheSameRules()
    {
        var nav = Open("26.3");
        Assert.Equal(NavKind.OpenedFamily, nav.ChooseFamily(2).Kind);
        Assert.Null(nav.Release);
        Assert.Equal(new NavResult(NavKind.Select, 2, "1.20"), nav.ChooseRelease(3));
        Assert.Equal(NavKind.None, nav.ChooseRelease(3).Kind); // clicking the pick again changes nothing
        Assert.Equal(NavKind.None, nav.ChooseRelease(99).Kind);
        Assert.Equal(NavKind.Back, nav.GoBack().Kind);
        Assert.Equal(NavKind.None, nav.GoBack().Kind);
        Assert.Equal(NavKind.None, nav.ChooseFamily(-1).Kind);
        Assert.Equal(NavKind.None, nav.ChooseRelease(0).Kind); // not on step 2
    }

    [Fact]
    public void ClickingTheSavedVersion_InItsOwnFamily_PicksIt()
    {
        // Will's bug (2026-10-26): the saved release showed preselected, so clicking it did nothing.
        var nav = Open("1.20.2");
        nav.ChooseFamily(2);
        Assert.Equal(new NavResult(NavKind.Select, 2, "1.20.2"), nav.ChooseRelease(1));
        // And a family that does not hold it: its newest is a real pick, not a silent highlight.
        nav.GoBack();
        nav.ChooseFamily(1);
        Assert.Equal(new NavResult(NavKind.Select, 1, "1.21.11"), nav.ChooseRelease(0));
    }

    [Fact]
    public void EveryCatalogueVersionIsReachableByKeys()
    {
        // Walk every family and every release with the keyboard alone: nothing in the catalogue is out of reach.
        var reached = new List<string>();
        for (var family = 0; family < VersionCatalog.Groups.Count; family++)
        {
            var nav = Open(null);
            for (var i = 0; i < family; i++) nav.Press(NavKey.Right);
            var opened = nav.Press(NavKey.Enter);
            if (opened.Kind == NavKind.SelectAndClose) { reached.Add(opened.Version!); continue; }
            Assert.Null(nav.Release);
            for (var r = 0; r < nav.Family.Versions.Count; r++) reached.Add(nav.Press(NavKey.Down).Version!);
        }
        Assert.Equal(VersionCatalog.SupportedVersions, reached);
    }
}
