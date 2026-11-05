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

    // KD-8 review: Reset on Customize over a Manual install puts the launcher back on Auto memory, never Manual at
    // the recommended number; RamAuto wins over any RamMb.
    [Fact]
    public void ResetOverAManualInstallGoesBackToAuto()
    {
        var json = Handoff.Compose(6144, null, null, ramAuto: true);
        Assert.Equal("{ \"RamAuto\": true }", json);
        var settings = new LauncherSettings { RamMb = 8192, RamManual = true };
        settings.RamRaiseByVersion["1.21.1"] = 10240;
        SetupChoicesData.Parse(json!)!.ApplyTo(settings, MaxRam, AnyVersion, totalPhysicalMb: 16384);
        Assert.False(settings.RamManual);
        Assert.Equal(OriginLauncher.App.Core.RamAdvice.RecommendedMb(16384), settings.RamMb);
        Assert.Empty(settings.RamRaiseByVersion);
        Assert.Null(SetupChoicesData.Parse("{ \"RamMb\": 6144 }")!.RamAuto); // older installers: unchanged
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

    // ---- SetupChoicesFile: the launcher's file shell (read, apply, delete once used up) ----

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
        SetupChoicesFile.Consume(path, SettingsNextTo(path), c => seen = c);
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
        SetupChoicesFile.Consume(path, SettingsNextTo(path), _ => called = true);
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
        SetupChoicesFile.Consume(path, SettingsNextTo(path), _ => called = true);
        Assert.False(called);
        Assert.False(File.Exists(path));
    }

    private static string SettingsNextTo(string path) => Path.Combine(Path.GetDirectoryName(path)!, "settings.json");

    // UPG-6: settings.json busy at that moment (antivirus scanning fresh files) must not lose the Setup choices: the
    // file is set aside and the next start applies it, once.
    [Fact]
    public void AFileWhoseApplyFailsWithAnIoErrorIsAppliedAtTheNextStart()
    {
        var path = TempFile(Handoff.Compose(4096, null, null));
        var retry = path + SetupChoicesFile.RetrySuffix;
        SetupChoicesFile.Consume(path, SettingsNextTo(path), _ => throw new IOException("settings.json is locked"));
        Assert.False(File.Exists(path));
        Assert.True(File.Exists(retry));
        SetupChoicesFile.Consume(path, SettingsNextTo(path), _ => throw new UnauthorizedAccessException("busy"));
        Assert.True(File.Exists(retry));

        SetupChoicesData? seen = null;
        SetupChoicesFile.Consume(path, SettingsNextTo(path), c => seen = c);
        Assert.Equal(4096, seen!.RamMb);
        Assert.False(File.Exists(retry));

        var again = false;
        SetupChoicesFile.Consume(path, SettingsNextTo(path), _ => again = true);
        Assert.False(again); // applied once, never again
    }

    // A set-aside hand-off never overwrites settings the player changed after it (settings.json written since).
    [Fact]
    public void ASetAsideFileYieldsToSettingsChangedSince()
    {
        var path = TempFile(Handoff.Compose(4096, "1.20.1", null));
        var retry = path + SetupChoicesFile.RetrySuffix;
        SetupChoicesFile.Consume(path, SettingsNextTo(path), _ => throw new IOException("settings.json is locked"));
        Assert.True(File.Exists(retry));
        File.SetLastWriteTimeUtc(retry, DateTime.UtcNow.AddMinutes(-5));
        File.WriteAllText(SettingsNextTo(path), "{}"); // the player changed a setting later in that session

        var called = false;
        SetupChoicesFile.Consume(path, SettingsNextTo(path), _ => called = true);
        Assert.False(called);
        Assert.False(File.Exists(retry));
    }

    // A newer hand-off from Setup replaces one still waiting.
    [Fact]
    public void AFreshHandOffReplacesAWaitingOne()
    {
        var path = TempFile(Handoff.Compose(4096, null, null));
        SetupChoicesFile.Consume(path, SettingsNextTo(path), _ => throw new IOException("busy"));
        File.WriteAllText(path, Handoff.Compose(8192, null, null));
        SetupChoicesData? seen = null;
        SetupChoicesFile.Consume(path, SettingsNextTo(path), c => seen = c);
        Assert.Equal(8192, seen!.RamMb);
        Assert.False(File.Exists(path + SetupChoicesFile.RetrySuffix));
    }

    [Fact]
    public void AnUnexpectedApplyFailureStillDeletesTheFileBeforeItPropagates()
    {
        // Not swallowed (a real bug should surface) but the file must not linger and re-apply every start.
        var path = TempFile(Handoff.Compose(4096, null, null));
        Assert.Throws<InvalidOperationException>(() => SetupChoicesFile.Consume(path, SettingsNextTo(path), _ => throw new InvalidOperationException()));
        Assert.False(File.Exists(path));
    }
}
