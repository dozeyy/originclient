using OriginLauncher.App.Core.Compat;
using Xunit;

namespace OriginLauncher.Tests.Compat;

// "Start anyway" is narrow: one account, one version, one exact mod set, used once, from Play / Enter / the picker's
// Launch, while that account's status line still shows the refusal. Join server and the crash Retry check normally.
public sealed class LaunchOverrideTests
{
    private const string Account = "acc-1";
    private const string Version = "1.21.1";
    private const string Set = "SET-A";
    private const string Line = "Old Mod won't start on 1.21.1.\nTurn it off on the Mods page, or press Play to start anyway.";

    private static LaunchOverride Refused()
    {
        var o = new LaunchOverride();
        o.Refuse(Account, Version, Set, Line);
        return o;
    }

    // ---- one test per route ------------------------------------------------------------------------------------

    [Fact]
    public void PlayStartsTheRefusedSetAnyway() =>
        Assert.Equal(Set, Refused().Accept(LaunchRoute.Play, Account, Version, Line));

    [Fact]
    public void EnterStartsTheRefusedSetAnyway() =>
        Assert.Equal(Set, Refused().Accept(LaunchRoute.EnterKey, Account, Version, Line));

    [Fact]
    public void JoinServerChecksNormally()
    {
        var o = Refused();
        Assert.Null(o.Accept(LaunchRoute.JoinServer, Account, Version, Line));
        // ...and the refusal is still there for the Play that follows while the line is on screen.
        Assert.Equal(Set, o.Accept(LaunchRoute.Play, Account, Version, Line));
    }

    [Fact]
    public void CrashRetryChecksNormally()
    {
        var o = Refused();
        Assert.Null(o.Accept(LaunchRoute.Retry, Account, Version, Line));
        Assert.Equal(Set, o.Accept(LaunchRoute.Play, Account, Version, Line));
    }

    // ---- the scope ---------------------------------------------------------------------------------------------

    [Fact]
    public void ItIsSpentByTheLaunchThatUsesIt()
    {
        var o = Refused();
        Assert.Equal(Set, o.Accept(LaunchRoute.Play, Account, Version, Line));
        Assert.Null(o.Accept(LaunchRoute.Play, Account, Version, Line));
    }

    [Fact]
    public void AnotherAccountSharingTheFolderIsNotCovered()
    {
        var o = Refused();
        Assert.Null(o.Accept(LaunchRoute.Play, "acc-2", Version, Line));
        // The attempt also dropped it: back on the first account Play is checked again.
        Assert.Null(o.Accept(LaunchRoute.Play, Account, Version, Line));
    }

    [Fact]
    public void AnotherVersionIsNotCovered()
    {
        var o = Refused();
        Assert.Null(o.Accept(LaunchRoute.Play, Account, "1.20.1", Line));
    }

    [Fact]
    public void ChangingTheAccountClearsIt()
    {
        var o = Refused();
        o.ContextChanged("acc-2", Version);
        o.ContextChanged(Account, Version); // and back again: too late
        Assert.Null(o.Accept(LaunchRoute.Play, Account, Version, Line));
    }

    [Fact]
    public void ChangingTheVersionClearsIt()
    {
        var o = Refused();
        o.ContextChanged(Account, "1.20.1");
        o.ContextChanged(Account, Version);
        Assert.Null(o.Accept(LaunchRoute.Play, Account, Version, Line));
    }

    [Fact]
    public void SelectingTheSameAccountAndVersionKeepsIt()
    {
        var o = Refused();
        o.ContextChanged(Account, Version);
        Assert.Equal(Set, o.Accept(LaunchRoute.Play, Account, Version, Line));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("Ready")]
    [InlineData("Update required")]
    public void ItNeedsTheRefusalStillOnTheStatusLine(string? shown)
    {
        // Not on screen at all (another account is selected) or replaced by something else.
        Assert.Null(Refused().Accept(LaunchRoute.Play, Account, Version, shown));
    }

    [Fact]
    public void ANewRefusalReplacesTheOldOne()
    {
        var o = Refused();
        o.Refuse(Account, Version, "SET-B", "Other line");
        Assert.Null(o.Accept(LaunchRoute.Play, Account, Version, Line));
        o.Refuse(Account, Version, "SET-B", "Other line");
        Assert.Equal("SET-B", o.Accept(LaunchRoute.Play, Account, Version, "Other line"));
    }

    [Fact]
    public void NothingRefusedMeansNothingAccepted()
    {
        Assert.Null(new LaunchOverride().Accept(LaunchRoute.Play, Account, Version, Line));
        var o = new LaunchOverride();
        o.Refuse(Account, Version, "", Line); // a refusal with no set key cannot be overridden
        Assert.Null(o.Accept(LaunchRoute.Play, Account, Version, Line));
    }

    [Fact]
    public void ClearForgetsIt()
    {
        var o = Refused();
        o.Clear();
        Assert.Null(o.Accept(LaunchRoute.Play, Account, Version, Line));
    }
}
