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
        var settings = new LauncherSettings { RamMb = 4096, SelectedVersion = "1.20.4", SyncSettingsAcrossVersions = syncBefore };
        if (json != null) SetupChoicesData.Parse(json)!.ApplyTo(settings, MaxRam, AnyVersion);
        return settings;
    }

    [Fact]
    public void SettingsSyncOnRoundTrips()
    {
        var json = Handoff.Compose(null, null, true);
        Assert.Equal("{ \"SyncSettingsAcrossVersions\": true }", json);
        var data = SetupChoicesData.Parse(json!)!;
        Assert.True(data.SyncSettingsAcrossVersions);
        Assert.Null(data.RamMb);
        Assert.True(Apply(json).SyncSettingsAcrossVersions);
    }

    [Fact]
    public void SettingsSyncOffRoundTripsAndTurnsAnExistingOnOff()
    {
        var json = Handoff.Compose(null, null, false);
        Assert.Equal("{ \"SyncSettingsAcrossVersions\": false }", json);
        Assert.False(SetupChoicesData.Parse(json!)!.SyncSettingsAcrossVersions);
        Assert.False(Apply(json, syncBefore: true).SyncSettingsAcrossVersions);
    }

    [Fact]
    public void FieldAbsentLeavesSettingsSyncAsItWas()
    {
        var json = Handoff.Compose(8192, "1.21.1", null);
        Assert.DoesNotContain("SyncSettingsAcrossVersions", json);
        Assert.Null(SetupChoicesData.Parse(json!)!.SyncSettingsAcrossVersions);
        Assert.True(Apply(json, syncBefore: true).SyncSettingsAcrossVersions);
        Assert.False(Apply(json, syncBefore: false).SyncSettingsAcrossVersions);
    }

    [Fact]
    public void AnOldFileWithoutTheFieldStillAppliesUnchanged()
    {
        // Exactly what installers before Settings Sync wrote.
        var settings = Apply("{ \"RamMb\": 6144, \"SelectedVersion\": \"1.21.1\" }", syncBefore: true);
        Assert.Equal(6144, settings.RamMb);
        Assert.Equal("1.21.1", settings.SelectedVersion);
        Assert.True(settings.SyncSettingsAcrossVersions);
    }

    [Fact]
    public void AllChoicesTravelTogether()
    {
        var settings = Apply(Handoff.Compose(8000, "1.21.1", true));
        Assert.Equal(7936, settings.RamMb); // snapped down to the 256 MB step
        Assert.Equal("1.21.1", settings.SelectedVersion);
        Assert.True(settings.SyncSettingsAcrossVersions);
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
    [InlineData("{ \"SyncSettingsAcrossVersions\": true }", true)]
    [InlineData("{\n  \"SyncSettingsAcrossVersions\":true,\n  \"RamMb\": 4096\n}", true)]
    [InlineData("{ \"SyncSettingsAcrossVersions\": false }", false)]
    // Sync is ON by default (LauncherSettings), so a file without the key is on.
    [InlineData("{ \"RamMb\": 4096 }", true)]
    // Only a top-level JSON false turns it off. The key text inside a string value, a nested object, a
    // wrong type, garbage, an empty file or a non-object root all read as the default (on), never throw.
    [InlineData("{ \"Note\": \"\\\"SyncSettingsAcrossVersions\\\": false\" }", true)]
    [InlineData("{ \"Other\": { \"SyncSettingsAcrossVersions\": false } }", true)]
    [InlineData("{ \"SyncSettingsAcrossVersions\": \"false\" }", true)]
    [InlineData("{ \"SyncSettingsAcrossVersions\": 0 }", true)]
    [InlineData("{ \"SyncSettingsAcrossVersions\": null }", true)]
    [InlineData("{ not json", true)]
    [InlineData("", true)]
    [InlineData("[false]", true)]
    public void TheInstallerReadsTheExistingSettingsSyncState(string settingsJson, bool expected)
    {
        var path = Path.Combine(Directory.CreateTempSubdirectory("handoff").FullName, "settings.json");
        File.WriteAllText(path, settingsJson);
        Assert.Equal(expected, Handoff.IsSettingsSyncOn(path));
    }

    [Fact]
    public void ASettingsFileThatIsMissingMeansOn()
    {
        Assert.True(Handoff.IsSettingsSyncOn(Path.Combine(Path.GetTempPath(), "origin-no-such-" + Guid.NewGuid().ToString("N"), "settings.json")));
    }

    // ---- SetupChoicesFile: the launcher's file shell (read, apply, always delete) ----

    private static string TempFile(string? content)
    {
        var path = Path.Combine(Directory.CreateTempSubdirectory("choices").FullName, Handoff.FileName);
        if (content != null) File.WriteAllText(path, content);
        return path;
    }

    [Fact]
    public void TheFileIsAppliedThenDeleted()
    {
        var path = TempFile(Handoff.Compose(8192, "1.21.1", true));
        SetupChoicesData? seen = null;
        SetupChoicesFile.Consume(path, c => seen = c);
        Assert.NotNull(seen);
        Assert.Equal(8192, seen!.RamMb);
        Assert.True(seen.SyncSettingsAcrossVersions);
        Assert.False(File.Exists(path));
    }

    [Fact]
    public void AMissingFileIsANoOp()
    {
        var path = TempFile(null);
        var called = false;
        SetupChoicesFile.Consume(path, _ => called = true);
        Assert.False(called);
    }

    [Theory]
    [InlineData("{ not json")]
    [InlineData("")]
    [InlineData("[1, 2]")]
    // Wrong-typed fields: parse throws JsonException inside the try; must not crash startup.
    [InlineData("{ \"RamMb\": \"lots\" }")]
    [InlineData("{ \"SyncSettingsAcrossVersions\": \"yes\" }")]
    [InlineData("{ \"SelectedVersion\": 5 }")]
    public void AMalformedFileIsToleratedAndStillDeleted(string content)
    {
        var path = TempFile(content);
        var called = false;
        SetupChoicesFile.Consume(path, _ => called = true);
        Assert.False(called);
        Assert.False(File.Exists(path));
    }

    [Fact]
    public void AFileWhoseApplyFailsWithAnIoErrorIsToleratedAndStillDeleted()
    {
        var path = TempFile(Handoff.Compose(4096, null, null));
        SetupChoicesFile.Consume(path, _ => throw new IOException("settings.json is locked"));
        Assert.False(File.Exists(path));
    }

    [Fact]
    public void AnUnexpectedApplyFailureStillDeletesTheFileBeforeItPropagates()
    {
        // Not swallowed (a real bug should surface) but the file must not linger and re-apply every start.
        var path = TempFile(Handoff.Compose(4096, null, null));
        Assert.Throws<InvalidOperationException>(() => SetupChoicesFile.Consume(path, _ => throw new InvalidOperationException()));
        Assert.False(File.Exists(path));
    }
}
