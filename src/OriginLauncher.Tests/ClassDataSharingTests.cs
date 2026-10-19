using OriginLauncher.App.Core.Launch;
using Xunit;

namespace OriginLauncher.Tests;

public class ClassDataSharingTests
{
    private static readonly DateTime T0 = new(2026, 10, 19, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void OnlyMeasuredVersionsGetTheArchive()
    {
        Assert.True(ClassDataSharing.IsEnabledFor("1.21.1"));
        Assert.False(ClassDataSharing.IsEnabledFor("1.21.11"));
        Assert.False(ClassDataSharing.IsEnabledFor("1.8.9"));
        Assert.False(ClassDataSharing.IsEnabledFor(null));
    }

    [Theory]
    [InlineData(false, null, false)]
    [InlineData(false, "-XX:+UseZGC -Xss2m", false)]
    [InlineData(true, null, true)]                                   // "Replace Origin's tuned defaults"
    [InlineData(false, "-Xshare:off", true)]
    [InlineData(false, "-XX:SharedArchiveFile=C:\\my.jsa", true)]
    [InlineData(false, "-XX:ArchiveClassesAtExit=x.jsa", true)]
    [InlineData(false, "-XX:+AutoCreateSharedArchive", true)]
    public void ThePlayersOwnCdsChoiceWins(bool replacePresets, string? custom, bool overrides) =>
        Assert.Equal(overrides, ClassDataSharing.PlayerOverrides(replacePresets, custom));

    [Fact]
    public void ArchiveNameIsStablePerRuntimeBuildAndChangesWithIt()
    {
        var home = @"C:\Users\A\AppData\Local\OriginLauncher\instances\1.21.1\runtime\windows-x64\java-runtime-delta";
        var name = ClassDataSharing.ArchiveName(home, 13_870_080, T0);
        Assert.Equal(name, ClassDataSharing.ArchiveName(home + "\\", 13_870_080, T0));
        Assert.Equal(name, ClassDataSharing.ArchiveName(home.ToUpperInvariant(), 13_870_080, T0));
        Assert.Matches("^base-[0-9a-f]{16}\\.jsa$", name);
        Assert.NotEqual(name, ClassDataSharing.ArchiveName(home, 13_870_081, T0));            // runtime updated
        Assert.NotEqual(name, ClassDataSharing.ArchiveName(home, 13_870_080, T0.AddSeconds(1)));
        Assert.NotEqual(name, ClassDataSharing.ArchiveName(home.Replace("1.21.1", "1.21.11"), 13_870_080, T0));
    }

    [Fact]
    public void ArchiveOptionGoesFirstAndIsQuotedOnlyWithSpaces()
    {
        Assert.Equal("-XX:SharedArchiveFile=C:\\Users\\A\\cds\\base-1.jsa -Xmx4G -cp x Main",
            ClassDataSharing.WithArchive("-Xmx4G -cp x Main", @"C:\Users\A\cds\base-1.jsa"));
        Assert.Equal("\"-XX:SharedArchiveFile=C:\\Users\\John Doe\\cds\\base-1.jsa\" -Xmx4G",
            ClassDataSharing.WithArchive("-Xmx4G", @"C:\Users\John Doe\cds\base-1.jsa"));
        Assert.Equal("-XX:SharedArchiveFile=C:\\a.jsa", ClassDataSharing.WithArchive("", @"C:\a.jsa"));
    }
}
