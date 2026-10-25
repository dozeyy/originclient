using OriginLauncher.App.Core.Compat;
using Xunit;

namespace OriginLauncher.Tests.Compat;

// Fabric Loader's own version model and range syntax (ported from fabric-loader, read 2026-10-25). The older,
// operator-by-operator cases stay in Import/VersionPredicateTests; this file adds the loader's edge cases.
public class FabricVersionTests
{
    [Theory]
    [InlineData("1.21", "1.21.0", 0)]
    [InlineData("1.21.1", "1.21", 1)]
    [InlineData("1.9", "1.10", -1)]
    [InlineData("0.16.14", "0.16.2", 1)]
    [InlineData("1.21.1+build.5", "1.21.1+build.9", 0)]   // build metadata is ignored
    [InlineData("1.21.1+mc1.21.1", "1.21.1", 0)]
    [InlineData("1.21-rc1", "1.21", -1)]                   // a pre-release is below its release
    [InlineData("1.21-beta.2", "1.21-beta.10", -1)]        // numeric identifiers compare as numbers
    [InlineData("1.21-alpha", "1.21-beta", -1)]
    [InlineData("1.21-1", "1.21-alpha", -1)]               // a number is below a word
    [InlineData("1.21-rc", "1.21-rc.1", -1)]               // more identifiers (all shared equal) is higher
    [InlineData("1.21-", "1.21-alpha", -1)]                // the empty pre-release is the lowest one
    [InlineData("1.21-", "1.21", -1)]
    public void ComparesLikeTheLoader(string a, string b, int expected)
    {
        Assert.Equal(expected, Math.Sign(FabricVersion.Parse(a, false).CompareTo(FabricVersion.Parse(b, false))));
        Assert.Equal(-expected, Math.Sign(FabricVersion.Parse(b, false).CompareTo(FabricVersion.Parse(a, false))));
    }

    [Theory]
    [InlineData("24w14a")]
    [InlineData("mc1.21")]
    [InlineData("1.x.2")]
    [InlineData("")]
    [InlineData("1..2")]
    public void NonNumericIsAStringVersion(string text)
    {
        var v = FabricVersion.Parse(text, allowWildcard: false);
        Assert.False(v.IsSemantic);
        Assert.False(FabricVersion.TryParse(text, out _));
    }

    [Fact]
    public void StringVersionsOnlyEqualThemselves()
    {
        var a = FabricVersion.Parse("24w14a", false);
        Assert.Equal(0, a.CompareTo(FabricVersion.Parse("24w14a", false)));
        Assert.NotEqual(0, a.CompareTo(FabricVersion.Parse("24w14b", false)));
    }

    [Fact]
    public void ComponentsAndPrereleaseAreExposed()
    {
        var v = FabricVersion.Parse("1.21.4-rc1+build.7", false);
        Assert.Equal(new[] { 1, 21, 4 }, v.Components.ToArray());
        Assert.Equal("rc1", v.Prerelease);
        Assert.Equal("build.7", v.Build);
        Assert.Equal(0, v.Component(5)); // a missing component is 0
    }

    [Theory]
    [InlineData("*", "0.0.1", true)]
    [InlineData("*", "anything", true)]
    [InlineData("", "1.0", true)]                           // empty = anything
    [InlineData(">=1.2 <2", "1.2", true)]                   // space-separated parts must ALL hold
    [InlineData(">=1.2 <2", "1.9.9", true)]
    [InlineData(">=1.2 <2", "2.0.0", false)]
    [InlineData(">=1.2 <2", "1.1.9", false)]
    [InlineData(">=1.2 <2", "2.0.0-rc1", true)]             // a pre-release of 2 sorts below 2
    [InlineData("^1.2.3", "1.9.0", true)]
    [InlineData("^1.2.3", "2.0.0", false)]
    [InlineData("^1.2.3", "2.0.0-rc1", false)]              // "^" also needs the same major, pre-release or not
    [InlineData("~1.2.3", "1.2.9", true)]
    [InlineData("~1.2.3", "1.3.0", false)]
    [InlineData("~1.2", "1.2.7", true)]
    [InlineData("~1.2", "1.3.0", false)]
    [InlineData("1.20.x", "1.20", true)]
    [InlineData("1.20.x", "1.20.6", true)]
    [InlineData("1.20.x", "1.21", false)]
    [InlineData("1.20.x", "1.20.4-rc1", true)]              // "x" ranges include snapshots of that line
    [InlineData("1.x", "1.99.1", true)]
    [InlineData("1.x", "2.0.0", false)]
    [InlineData(">=1.21- <1.22-", "1.21.1", true)]
    [InlineData(">=1.21- <1.22-", "1.22-alpha", false)]
    [InlineData(">=1.21- <1.22-", "1.21-rc1", true)]
    [InlineData(">=1.21", "1.21-rc1", false)]               // a plain bound excludes the pre-release
    [InlineData("=1.2.3", "1.2.3+build.9", true)]           // +build ignored
    [InlineData(">=1.2.3+build.9", "1.2.3", true)]
    [InlineData("24w14a", "24w14a", true)]                  // a string version is an equality
    [InlineData("24w14a", "24w14b", false)]
    [InlineData("=24w14a", "24w14a", true)]
    [InlineData(">=1.0.0", "snapshot", false)]              // a non-numeric tested version is never "newer"
    public void RangeSyntax(string range, string version, bool expected) =>
        Assert.Equal(expected, VersionPredicate.Parse(range).IsSatisfiedBy(version));

    [Fact]
    public void AnyOneOfSeveralAlternativesMayHold()
    {
        var p = VersionPredicate.Parse(new[] { "1.21", "1.21.1" });
        Assert.True(p.IsSatisfiedBy("1.21.1"));
        Assert.True(p.IsSatisfiedBy("1.21"));
        Assert.False(p.IsSatisfiedBy("1.21.2"));
        Assert.Equal("1.21 or 1.21.1", p.Display);
    }

    [Theory]
    [InlineData(">24w14a")]      // > and < need an order a string version doesn't have
    [InlineData("<24w14a")]
    [InlineData(">=")]
    [InlineData(">=1.x")]        // a wildcard can't be combined with an operator
    public void InvalidRequirementsMatchNothing(string range)
    {
        var p = VersionPredicate.Parse(range);
        Assert.False(p.IsValid);
        Assert.NotNull(p.Error);
        Assert.False(p.IsSatisfiedBy("1.0.0"));
        Assert.False(p.MatchesAnything);
    }

    [Fact]
    public void TheWildcardAnchorIsTheNumberTheRangePointsAt()
    {
        Assert.Equal("1.21", VersionPredicate.Parse("1.21.x").Anchor);
        Assert.Equal("1.21.1", VersionPredicate.Parse("~1.21.1").Anchor);
        Assert.Null(VersionPredicate.Parse("*").Anchor);
    }

    [Fact]
    public void ADependencyWithNoStatedVersionIsNeverContradicted()
    {
        var dep = new ModDependency("sodium", new[] { ">=99" });
        Assert.True(dep.IsSatisfiedBy(null));
        Assert.False(dep.IsSatisfiedBy("0.6.13"));
        Assert.True(dep.IsSatisfiedBy("100"));
    }

    [Fact]
    public void CompareOrdersNumericVersionsAndFallsBackToText()
    {
        Assert.True(FabricVersion.Compare("0.10.0", "0.9.0") > 0);
        Assert.True(FabricVersion.Compare("b", "a") > 0);
        Assert.Equal(0, FabricVersion.Compare("1.0", "1.0.0"));
    }
}
