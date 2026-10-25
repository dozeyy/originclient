using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Models;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>The offline test switch belongs to dev builds; a release build clears a leftover one.</summary>
public class DeveloperAccessTests
{
    [Fact]
    public void ReleaseBuildWithTheSwitchOnNeedsAReset()
    {
        Assert.True(DeveloperAccess.NeedsReset(isDevBuild: false, new LauncherSettings { OfflineTestMode = true }));
    }

    [Fact]
    public void ReleaseBuildWithTheSwitchOffIsLeftAlone()
    {
        Assert.False(DeveloperAccess.NeedsReset(isDevBuild: false, new LauncherSettings { OfflineTestMode = false }));
    }

    [Fact]
    public void DevBuildKeepsItsSwitch()
    {
        Assert.False(DeveloperAccess.NeedsReset(isDevBuild: true, new LauncherSettings { OfflineTestMode = true }));
    }

    [Fact]
    public void TheResetClearsOnlyTheSwitch()
    {
        var settings = new LauncherSettings { OfflineTestMode = true, SelectedVersion = "1.21.1", RamMb = 4096 };
        DeveloperAccess.ClearOfflineTest(settings);
        Assert.False(settings.OfflineTestMode);
        Assert.Equal("1.21.1", settings.SelectedVersion);
        Assert.Equal(4096, settings.RamMb);
        Assert.False(DeveloperAccess.NeedsReset(isDevBuild: false, settings)); // once: nothing left to clear
    }
}
