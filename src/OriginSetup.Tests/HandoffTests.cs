using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// What the installer writes for the launcher (Engine/Handoff.cs). The launcher
// side of the round trip is OriginLauncher.Tests/SetupHandoffTests.cs.
public class HandoffTests
{
    [Fact]
    public void WritesOnlyTheFieldsThePlayerSet()
    {
        Assert.Null(Handoff.Compose(null, null, null));
        Assert.Equal("{ \"RamMb\": 6144 }", Handoff.Compose(6144, null, null));
        Assert.Equal("{ \"SyncSettingsAcrossVersions\": true }", Handoff.Compose(null, null, true));
        Assert.Equal("{ \"SyncSettingsAcrossVersions\": false }", Handoff.Compose(null, null, false));
    }

    [Fact]
    public void KeepsTheOlderFieldsInTheirOldFormat()
    {
        Assert.Equal("{ \"RamMb\": 4096, \"SelectedVersion\": \"1.21.1\", \"SyncSettingsAcrossVersions\": true }",
            Handoff.Compose(4096, "1.21.1", true));
        Assert.Equal("{ \"RamMb\": 4096, \"SelectedVersion\": \"1.21.1\" }", Handoff.Compose(4096, "1.21.1", null));
    }

    // KD-8: an update opens Customize on the player's own memory and version, not the PC's recommendation.
    [Fact]
    public void ReadsThePlayersOwnMemoryAndVersion_OnlyManualMemoryCounts()
    {
        var dir = Path.Combine(Path.GetTempPath(), "origin-handoff-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var path = Path.Combine(dir, "settings.json");
            Assert.Null(Handoff.ReadChoices(path).ManualRamMb); // no file: nothing known

            File.WriteAllText(path, "{ \"RamMb\": 8192, \"RamManual\": true, \"SelectedVersion\": \"1.20.1\", \"ModProfiles\": [ { \"Id\": \"a\" } ], \"JvmArgsByVersion\": { \"1.21.1\": { \"Arguments\": \"-Xss2m\" } } }");
            var manual = Handoff.ReadChoices(path);
            Assert.Equal(8192, manual.ManualRamMb);
            Assert.Equal("1.20.1", manual.SelectedVersion);

            File.WriteAllText(path, "{ \"RamMb\": 16384, \"RamManual\": false, \"SelectedVersion\": null }");
            var auto = Handoff.ReadChoices(path);
            Assert.Null(auto.ManualRamMb); // Auto has no number of its own
            Assert.Null(auto.SelectedVersion);

            File.WriteAllText(path, "{ \"RamMb\": \"8192\", \"RamManual\": \"true\" }");
            Assert.Null(Handoff.ReadChoices(path).ManualRamMb); // only real JSON values count

            File.WriteAllText(path, "not json");
            Assert.Null(Handoff.ReadChoices(path).ManualRamMb); // never throws
        }
        finally
        {
            Directory.Delete(dir, recursive: true);
        }
    }

    // KD-8: a player who removed the desktop icon doesn't get it back from an update or a repair.
    [Theory]
    [InlineData(false, false, true)]  // fresh install: the icon goes down
    [InlineData(false, true, true)]
    [InlineData(true, true, true)]    // update, icon kept: refreshed in place
    [InlineData(true, false, false)]  // update, icon removed: stays removed
    public void TheDesktopIconFollowsThePlayersChoice(bool updating, bool exists, bool wanted) =>
        Assert.Equal(wanted, Shortcuts.WantsDesktop(updating, exists));
}
