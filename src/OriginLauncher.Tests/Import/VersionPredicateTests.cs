using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Import.Mods;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Fabric's version-range rules (D14): every operator, OR arrays, wildcards and pre-release ordering.
public class VersionPredicateTests
{
    [Theory]
    [InlineData(">=1.2.0", "1.2.0", true)]
    [InlineData(">=1.2.0", "1.1.9", false)]
    [InlineData(">1.2.0", "1.2.0", false)]
    [InlineData(">1.2.0", "1.2.1", true)]
    [InlineData("<2.0.0", "1.9.9", true)]
    [InlineData("<2.0.0", "2.0.0", false)]
    [InlineData("<=2.0.0", "2.0.0", true)]
    [InlineData("=1.2.3", "1.2.3", true)]
    [InlineData("=1.2.3", "1.2.4", false)]
    [InlineData("1.2.3", "1.2.3", true)]
    [InlineData("1.21", "1.21.0", true)]
    [InlineData("*", "9.9.9", true)]
    public void SimpleComparators(string range, string version, bool expected) =>
        Assert.Equal(expected, VersionPredicate.Parse(range).IsSatisfiedBy(version));

    [Theory]
    [InlineData("~1.2.3", "1.2.3", true)]
    [InlineData("~1.2.3", "1.2.99", true)]
    [InlineData("~1.2.3", "1.3.0", false)]
    [InlineData("~1.2.3", "1.2.2", false)]
    [InlineData("~1.2", "1.2.7", true)]
    [InlineData("~1.2", "1.3.0", false)]
    // Fabric Loader (VersionComparisonOperator.SAME_TO_NEXT_MINOR, read 2026-10-25) tests "~1" as same major AND
    // same minor, with a missing minor counted as 0: "~1" accepts 1.0.x only, not 1.9.9. Earlier versions of this
    // file expected the npm meaning (any 1.x); the loader's own rule is the one that decides if a mod boots.
    [InlineData("~1", "1.9.9", false)]
    [InlineData("~1", "1.0.5", true)]
    [InlineData("~1", "2.0.0", false)]
    [InlineData("~1.21.1", "1.21.1", true)]
    [InlineData("~1.21.1", "1.21.4", true)]
    [InlineData("~1.21.1", "1.22", false)]
    public void TildeAllowsPatchChanges(string range, string version, bool expected) =>
        Assert.Equal(expected, VersionPredicate.Parse(range).IsSatisfiedBy(version));

    [Theory]
    [InlineData("^1.2.3", "1.2.3", true)]
    [InlineData("^1.2.3", "1.9.0", true)]
    [InlineData("^1.2.3", "2.0.0", false)]
    [InlineData("^1.2.3", "1.2.2", false)]
    [InlineData("^0.5.1", "0.9.0", true)]
    [InlineData("^0.5.1", "1.0.0", false)]
    public void CaretAllowsMinorAndPatchChanges(string range, string version, bool expected) =>
        Assert.Equal(expected, VersionPredicate.Parse(range).IsSatisfiedBy(version));

    [Theory]
    [InlineData("1.20.x", "1.20.0", true)]
    [InlineData("1.20.x", "1.20.6", true)]
    [InlineData("1.20.x", "1.21.0", false)]
    [InlineData("1.x", "1.99", true)]
    [InlineData("1.x", "2.0", false)]
    [InlineData("1.20.*", "1.20.4", true)]
    public void WildcardsCoverTheirPart(string range, string version, bool expected) =>
        Assert.Equal(expected, VersionPredicate.Parse(range).IsSatisfiedBy(version));

    [Theory]
    [InlineData(">=1.20 <1.21", "1.20.4", true)]
    [InlineData(">=1.20 <1.21", "1.21", false)]
    [InlineData(">=1.20 <1.21", "1.19.4", false)]
    public void SpaceSeparatedPartsAllHaveToHold(string range, string version, bool expected) =>
        Assert.Equal(expected, VersionPredicate.Parse(range).IsSatisfiedBy(version));

    [Theory]
    [InlineData("1.21", true)]
    [InlineData("1.21.1", true)]
    [InlineData("1.20.4", false)]
    public void ArraysMeanAnyOneMayHold(string version, bool expected) =>
        Assert.Equal(expected, VersionPredicate.Parse(new[] { "1.21", "1.21.1" }).IsSatisfiedBy(version));

    [Fact]
    public void AnArrayOfRangesIsAnOr()
    {
        var predicate = VersionPredicate.Parse(new[] { ">=1.16 <1.17", ">=1.20 <1.21" });
        Assert.True(predicate.IsSatisfiedBy("1.16.5"));
        Assert.True(predicate.IsSatisfiedBy("1.20.4"));
        Assert.False(predicate.IsSatisfiedBy("1.18.2"));
        Assert.Equal(">=1.16 <1.17 or >=1.20 <1.21", predicate.Display);
    }

    [Theory]
    [InlineData("1.21-rc1", "1.21", -1)]
    [InlineData("1.21", "1.21-rc1", 1)]
    [InlineData("1.21-alpha", "1.21-beta", -1)]
    [InlineData("1.21-beta.2", "1.21-beta.10", -1)]
    [InlineData("1.21-beta.2", "1.21-beta", 1)]
    [InlineData("1.21-1", "1.21-alpha", -1)]
    [InlineData("1.21-", "1.21-alpha", -1)]
    [InlineData("1.20.9", "1.21-", -1)]
    [InlineData("1.21.1+build.5", "1.21.1", 0)]
    public void PreReleasesSortBelowTheirRelease(string a, string b, int expected)
    {
        Assert.True(FabricVersion.TryParse(a, out var va));
        Assert.True(FabricVersion.TryParse(b, out var vb));
        Assert.Equal(expected, Math.Sign(va.CompareTo(vb)));
    }

    [Fact]
    public void ATrailingDashRangeIncludesSnapshotsOfTheLowerBoundAndExcludesTheUpperOnes()
    {
        var predicate = VersionPredicate.Parse(">=1.21.10- <1.22-");
        Assert.True(predicate.IsSatisfiedBy("1.21.10-rc1"));
        Assert.True(predicate.IsSatisfiedBy("1.21.11"));
        Assert.False(predicate.IsSatisfiedBy("1.22-alpha.1"));
        Assert.False(predicate.IsSatisfiedBy("1.22"));
        Assert.False(predicate.IsSatisfiedBy("1.21.9"));
    }

    [Fact]
    public void ARangeWithoutTheDashExcludesSnapshotsOfItsLowerBound()
    {
        Assert.False(VersionPredicate.Parse(">=1.21.10").IsSatisfiedBy("1.21.10-rc1"));
        Assert.True(VersionPredicate.Parse(">=1.21.10").IsSatisfiedBy("1.21.10"));
    }

    [Fact]
    public void ANonNumericVersionOnlyMatchesItself()
    {
        Assert.True(VersionPredicate.Parse("24w14a").IsSatisfiedBy("24w14a"));
        Assert.False(VersionPredicate.Parse(">=1.20").IsSatisfiedBy("24w14a"));
        Assert.False(VersionPredicate.Parse("24w14a").IsSatisfiedBy("24w15a"));
    }

    [Fact]
    public void AnchorNamesTheVersionARangePointsAt()
    {
        Assert.Equal("1.21.1", VersionPredicate.Parse("~1.21.1").Anchor);
        Assert.Equal("1.21", VersionPredicate.Parse("1.21.x").Anchor);
        Assert.Equal("1.20.2", VersionPredicate.Parse(">=1.20.2 <1.21").Anchor);
        Assert.Null(VersionPredicate.Parse("*").Anchor);
    }
}
