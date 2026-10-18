using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Install;
using OriginLauncher.App.Core.Models;
using OriginSetup.Engine;
using Xunit;

namespace OriginLauncher.Tests;

// The installer -> launcher hand-off (setup-choices.json): the installer's
// real writer (src/OriginSetup/Engine/Handoff.cs, linked in) feeds the
// launcher's real reader and apply rules (Core/Install/SetupChoicesData.cs).
// Nothing touches the real %LocalAppData%\OriginLauncher: Compose returns the
// text and ApplyTo works on an in-memory LauncherSettings.
public class SetupHandoffTests
{
    private const int MaxRam = 16384;
    private static bool AnyVersion(string? v) => v == "1.21.1";

    private static LauncherSettings Apply(string? json, bool syncBefore = false)
    {
        var settings = new LauncherSettings { RamMb = 4096, SelectedVersion = "1.20.4", SettingsSyncEnabled = syncBefore };
        if (json != null) SetupChoicesData.Parse(json)!.ApplyTo(settings, MaxRam, AnyVersion);
        return settings;
    }

    [Fact]
    public void SettingsSyncOnRoundTrips()
    {
        var json = Handoff.Compose(null, null, true);
        Assert.Equal("{ \"SettingsSyncEnabled\": true }", json);
        var data = SetupChoicesData.Parse(json!)!;
        Assert.True(data.SettingsSyncEnabled);
        Assert.Null(data.RamMb);
        Assert.True(Apply(json).SettingsSyncEnabled);
    }

    [Fact]
    public void SettingsSyncOffRoundTripsAndTurnsAnExistingOnOff()
    {
        var json = Handoff.Compose(null, null, false);
        Assert.Equal("{ \"SettingsSyncEnabled\": false }", json);
        Assert.False(SetupChoicesData.Parse(json!)!.SettingsSyncEnabled);
        Assert.False(Apply(json, syncBefore: true).SettingsSyncEnabled);
    }

    [Fact]
    public void FieldAbsentLeavesSettingsSyncAsItWas()
    {
        var json = Handoff.Compose(8192, "1.21.1", null);
        Assert.DoesNotContain("SettingsSyncEnabled", json);
        Assert.Null(SetupChoicesData.Parse(json!)!.SettingsSyncEnabled);
        Assert.True(Apply(json, syncBefore: true).SettingsSyncEnabled);
        Assert.False(Apply(json, syncBefore: false).SettingsSyncEnabled);
    }

    [Fact]
    public void AnOldFileWithoutTheFieldStillAppliesUnchanged()
    {
        // Exactly what installers before Settings Sync wrote.
        var settings = Apply("{ \"RamMb\": 6144, \"SelectedVersion\": \"1.21.1\" }", syncBefore: true);
        Assert.Equal(6144, settings.RamMb);
        Assert.Equal("1.21.1", settings.SelectedVersion);
        Assert.True(settings.SettingsSyncEnabled);
    }

    [Fact]
    public void AllChoicesTravelTogether()
    {
        var settings = Apply(Handoff.Compose(8000, "1.21.1", true));
        Assert.Equal(7936, settings.RamMb); // snapped down to the 256 MB step
        Assert.Equal("1.21.1", settings.SelectedVersion);
        Assert.True(settings.SettingsSyncEnabled);
    }

    [Fact]
    public void NothingSetWritesNoFile()
    {
        Assert.Null(Handoff.Compose(null, null, null));
    }

    [Fact]
    public void ASettingsSyncChoiceAloneDoesNotTouchMemoryOrVersion()
    {
        var settings = Apply(Handoff.Compose(null, null, true));
        Assert.Equal(4096, settings.RamMb);
        Assert.Equal("1.20.4", settings.SelectedVersion);
    }

    [Theory]
    [InlineData("{ \"SettingsSyncEnabled\": true }", true)]
    [InlineData("{\n  \"SettingsSyncEnabled\":true,\n  \"RamMb\": 4096\n}", true)]
    [InlineData("{ \"SettingsSyncEnabled\": false }", false)]
    [InlineData("{ \"RamMb\": 4096 }", false)]
    public void TheInstallerReadsTheExistingSettingsSyncState(string settingsJson, bool expected)
    {
        var path = Path.Combine(Directory.CreateTempSubdirectory("handoff").FullName, "settings.json");
        File.WriteAllText(path, settingsJson);
        Assert.Equal(expected, Handoff.IsSettingsSyncOn(path));
    }

    [Fact]
    public void ASettingsFileThatIsMissingMeansOff()
    {
        Assert.False(Handoff.IsSettingsSyncOn(Path.Combine(Path.GetTempPath(), "origin-no-such-" + Guid.NewGuid().ToString("N"), "settings.json")));
    }
}
