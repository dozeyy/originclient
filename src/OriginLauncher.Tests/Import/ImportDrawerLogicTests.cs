using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests.Import;

// The Import drawer's pure logic (UI/Controls/ImportLogic.cs): the words, the big button and the Done page.
public class ImportDrawerLogicTests
{
    private static DetectedInstance S(string name, SourceKind kind = SourceKind.Prism, string mc = "1.21.1") => new()
    {
        Kind = kind, Name = name, GameDir = "/x/" + name, McVersion = mc,
    };

    [Theory]
    [InlineData(0, "0 files")]
    [InlineData(1, "1 file")]
    [InlineData(2, "2 files")]
    public void PluralNeverSaysOneFiles(int count, string expected) =>
        Assert.Equal(expected, ImportLogic.Plural(count, "file", "files"));

    [Fact]
    public void ImportAllButton_FollowsThePlanAndTheRefusal()
    {
        Assert.Equal(("Checking...", false), ImportLogic.ImportAllButton(true, false, true, 5, false));
        Assert.Equal(("Check again", true), ImportLogic.ImportAllButton(false, true, false, 0, false));
        Assert.Equal(("Check again", false), ImportLogic.ImportAllButton(false, true, false, 0, true));
        // The label is the number the plan will import: 9 found and 2 unsupported reads 7, and stays a number after unticks.
        Assert.Equal(("Import 7", true), ImportLogic.ImportAllButton(false, false, true, 7, false));
        Assert.Equal(("Import 3", true), ImportLogic.ImportAllButton(false, false, true, 3, false));
        Assert.Equal(("Import 1 setup", true), ImportLogic.ImportAllButton(false, false, true, 1, false));
        Assert.Equal(("Import", false), ImportLogic.ImportAllButton(false, false, false, 0, false));
        // A launch or update refusal turns a ready button off.
        Assert.False(ImportLogic.ImportAllButton(false, false, true, 7, true).Enabled);
        // A blocked plan ("Not enough free disk space") turns IMPORT off...
        Assert.False(ImportLogic.ImportAllButton(false, false, planAllowsApply: false, 7, false).Enabled);
    }

    [Fact]
    public void RowControls_StayOnWhenThePlanIsBlocked_SoThePlayerCanUntickUntilItFits()
    {
        // ...but the tick boxes and CHANGE are not tied to the plan at all: only a refusal or a running import turns them off.
        Assert.True(ImportLogic.RowControlsAllowed(refused: false, applying: false));
        Assert.False(ImportLogic.RowControlsAllowed(refused: true, applying: false));
        Assert.False(ImportLogic.RowControlsAllowed(refused: false, applying: true));
    }

    [Theory]
    [InlineData("Pack file missing. Skipped.", "Pack file missing. Skipped.")]     // two sentences: kept as they are
    [InlineData("Imported but turned off: needs Fabric API.", "Imported but turned off: needs Fabric API")]
    [InlineData("Comes over turned off - needs Fabric Kotlin", "Comes over turned off - needs Fabric Kotlin")]
    [InlineData("Looking...", "Looking...")]
    [InlineData(null, null)]
    public void StatusLine_DropsOneSentencesFullStop(string? text, string? expected) =>
        Assert.Equal(expected, ImportLogic.StatusLine(text));

    [Theory]
    [InlineData(SetupRole.Full, 6, "Settings, packs, 6 mods")]
    [InlineData(SetupRole.Full, 1, "Settings, packs, 1 mod")]
    [InlineData(SetupRole.Full, 0, "Settings and packs")]
    [InlineData(SetupRole.ModsAndPacks, 3, "Packs, 3 mods")]
    [InlineData(SetupRole.PacksOnly, 0, "Packs only")]
    public void RoleCaption_IsOneShortLine(SetupRole role, int mods, string expected) =>
        Assert.Equal(expected, ImportLogic.RoleCaption(role, mods));

    [Fact]
    public void RowCaptions_HaveNoFullStop_AndTheForgeOneKeepsItsReason()
    {
        Assert.Equal("Not supported", ImportLogic.SkipCaption(SkipKind.Unsupported));
        Assert.All(Enum.GetValues<SkipKind>(), k => Assert.False(ImportLogic.SkipCaption(k).EndsWith('.')));
        Assert.Equal("Packs only: its mods are made for a different mod setup", BulkRules.ForgeCaption);
        // The full sentences (plan, summary, Done) are the engine's, one source each.
        Assert.Equal("Its mods were made for a different kind of Minecraft mod setup, so only its packs come over.", BulkRules.ForgeNote);
        Assert.Equal("Origin doesn't support Minecraft 1.21.5, so this setup can't come over.", BulkRules.UnsupportedReason("1.21.5"));
    }

    [Fact]
    public void CheckStart_RefusalAfterPlanningIsReportedNotSwallowed()
    {
        // The case behind "stuck on Getting ready": the refusal arrived while a launcher's setups were planning.
        Assert.Equal(ImportLogic.StartKind.Start, ImportLogic.CheckStart(false, null));
        Assert.Equal(ImportLogic.StartKind.Refused, ImportLogic.CheckStart(false, "Wait for Minecraft to finish starting."));
        Assert.Equal(ImportLogic.StartKind.AlreadyRunning, ImportLogic.CheckStart(true, null));
    }

    [Fact]
    public void Describe_UsesVersionLoaderAndModCount()
    {
        // The loader is not a word in the meta line.
        Assert.Equal("1.21.1", ImportLogic.Describe(S("a") with { Loader = LoaderKind.Fabric }, 0));
        Assert.Equal("1.20.1 - 1 mod", ImportLogic.Describe(S("a", mc: "1.20.1") with { Loader = LoaderKind.Forge }, 1));
        Assert.Equal("1.21.1 - 12 mods", ImportLogic.Describe(S("a"), 12));
    }

    [Theory]
    [InlineData("Survival", "S")]
    [InlineData("1.8.9 PvP", "1")]
    [InlineData("  create: above", "C")]
    [InlineData("...", "?")]
    public void Initial_IsTheSetupsOwnFirstLetter(string name, string expected) =>
        Assert.Equal(expected, ImportLogic.Initial(name));

    private static BulkImportResult Result(params VersionOutcome[] versions) => new()
    {
        RunId = "r", State = ImportState.Committed, Versions = versions,
    };

    [Fact]
    public void DoneModel_CountsLinesAndTheSetupsThatCouldNotComeOver()
    {
        var survival = S("Survival");
        var creative = S("Creative");
        var forge = S("Pack", SourceKind.Modrinth, "1.20.1") with { Loader = LoaderKind.Forge };
        var snap = S("Snapshot", SourceKind.Modrinth, "1.21.5");
        var result = Result(
            new VersionOutcome("1.21.1", new[]
            {
                new SetupOutcome(survival, SetupRole.Full, SetupResultKind.Imported),
                new SetupOutcome(creative, SetupRole.ModsAndPacks, SetupResultKind.Imported),
            }),
            new VersionOutcome("1.20.1", new[] { new SetupOutcome(forge, SetupRole.PacksOnly, SetupResultKind.ImportedPartly) }),
            new VersionOutcome("1.21.5", new[] { new SetupOutcome(snap, SetupRole.Full, SetupResultKind.Skipped, "Origin doesn't support Minecraft 1.21.5.") }));

        var done = ImportDoneModel.FromBulk(result);

        Assert.Equal(3, done.Brought);
        Assert.Equal(("1.21.1", "2 setups, settings from Survival"), done.VersionLines[0]);
        Assert.Equal(("1.20.1", "1 setup, packs only"), done.VersionLines[1]);
        var lost = Assert.Single(done.Lost);
        Assert.Equal("Snapshot", lost.Title);
        Assert.Equal("Modrinth App - 1.21.5", lost.Caption);
        Assert.Equal("Origin doesn't support Minecraft 1.21.5.", lost.Reason);
    }

    [Fact]
    public void DoneModel_ASkippedSetupReportedTwiceIsListedOnce_AndANotImportedItemOfAnImportedSetupIsNotASetup()
    {
        var survival = S("Survival");
        var snap = S("Snapshot", SourceKind.Modrinth, "1.21.5");
        var result = new BulkImportResult
        {
            RunId = "r", State = ImportState.Committed,
            Versions = new[]
            {
                new VersionOutcome("1.21.1", new[] { new SetupOutcome(survival, SetupRole.Full, SetupResultKind.Imported) }),
                new VersionOutcome("1.21.5", new[] { new SetupOutcome(snap, SetupRole.Full, SetupResultKind.Skipped, "No.") }),
            },
            DidntComeOver = new[]
            {
                new NotImportedItem("Snapshot", "Whole setup", "No."),
                new NotImportedItem("Survival", "Old Map Mod", "built for another version"),
            },
        };

        var done = ImportDoneModel.FromBulk(result);

        Assert.Equal(1, done.Brought);
        Assert.Single(done.Lost);
    }

    [Fact]
    public void DoneModel_ASetupOnlyInTheDidntComeOverListStillCounts()
    {
        var survival = S("Survival");
        var result = new BulkImportResult
        {
            RunId = "r", State = ImportState.Committed,
            Versions = new[] { new VersionOutcome("1.21.1", new[] { new SetupOutcome(survival, SetupRole.Full, SetupResultKind.Imported) }) },
            DidntComeOver = new[] { new NotImportedItem("Prism: Old world", "Whole setup", "Origin doesn't support Minecraft 1.19.") },
        };

        var done = ImportDoneModel.FromBulk(result);

        var lost = Assert.Single(done.Lost);
        Assert.Equal("Prism: Old world", lost.Title);
    }

    [Fact]
    public void DoneModel_ASetupNamedLikePartOfAnotherNeverHidesIt()
    {
        // Review: the old de-dup tested "Prism: Pack 2".Contains("Pack"), so a landed setup called "Pack" hid "Prism: Pack 2".
        var pack = S("Pack");
        var pack2 = S("Pack 2", SourceKind.Prism, "1.21.5");
        var result = new BulkImportResult
        {
            RunId = "r", State = ImportState.Committed,
            Versions = new[] { new VersionOutcome("1.21.1", new[] { new SetupOutcome(pack, SetupRole.Full, SetupResultKind.Imported) }) },
            Skipped = new[] { new SkippedSetup(pack2, "Origin doesn't support Minecraft 1.21.5, so this setup can't come over.") },
            DidntComeOver = new[] { new NotImportedItem("Prism: Pack 2", "Whole setup", "Origin doesn't support Minecraft 1.21.5, so this setup can't come over.") },
        };

        var done = ImportDoneModel.FromBulk(result);

        Assert.Equal(1, done.Brought);
        var lost = Assert.Single(done.Lost); // listed once (Skipped and DidntComeOver are the same setup), never hidden
        Assert.Equal("Pack 2", lost.Title);
    }

    [Fact]
    public void DoneModel_ACancelledOrFailedRunBringsNothingOver()
    {
        var done = ImportDoneModel.FromBulk(new BulkImportResult { RunId = "r", State = ImportState.RolledBack, Cancelled = true });
        Assert.False(done.Succeeded);
        Assert.True(done.Cancelled);
        Assert.Equal(0, done.Brought);
        Assert.Empty(done.VersionLines);
    }

    // ------------------------------------------------------------ Done's button (gate review 2026-10-25)

    private static ImportLogic.DoneProfile P(string id, string v = "1.21.1", bool ok = true) => new(v, id, "Prism: " + id, ok);

    [Fact]
    public void OneImportedProfileThatCanStartIsWhatPlayStarts()
    {
        var d = ImportLogic.DoneAction("1.20.1", new[] { P("fo", "1.21.1") });
        Assert.Equal(ImportLogic.DoneKind.Play, d.Kind);
        Assert.Equal("Play", d.Label);
        Assert.Equal("1.21.1", d.Version);                 // the profile's version, not the most played one
        Assert.Equal("fo", d.ProfileId);
        Assert.Contains("Prism: fo", d.Note);             // the footer says what Play starts
    }

    [Fact]
    public void SeveralProfilesOrOneThatCantStartSendThePlayerToProfilesNeverAWrongLaunch()
    {
        var several = ImportLogic.DoneAction("1.21.1", new[] { P("a"), P("b", "1.20.1") });
        Assert.Equal(ImportLogic.DoneKind.OpenProfiles, several.Kind);
        Assert.Equal("Open Profiles", several.Label);
        Assert.Null(several.ProfileId);
        Assert.Contains("2 profiles", several.Note);
        Assert.DoesNotContain("Play", several.Note);

        var broken = ImportLogic.DoneAction("1.21.1", new[] { P("a", ok: false) });
        Assert.Equal(ImportLogic.DoneKind.OpenProfiles, broken.Kind);
        Assert.Contains("needs a fix", broken.Note);
    }

    [Fact]
    public void NoProfileMeansPlayStartsTheVersionAndNothingMeansDone()
    {
        var plain = ImportLogic.DoneAction("1.21.1", Array.Empty<ImportLogic.DoneProfile>());
        Assert.Equal(ImportLogic.DoneKind.Play, plain.Kind);
        Assert.Null(plain.ProfileId);
        Assert.Equal("1.21.1", plain.Version);
        Assert.Equal(ImportLogic.DoneKind.Close, ImportLogic.DoneAction(null, Array.Empty<ImportLogic.DoneProfile>()).Kind);
    }

    [Fact]
    public void PlaySelectsTheImportedProfileOnlyWhenItStillExists()
    {
        var s = new OriginLauncher.App.Core.Models.LauncherSettings { SelectedVersion = "1.20.1", SelectedProfileId = "mine" };
        s.ModProfiles.Add(new OriginLauncher.App.Core.Profiles.ModProfile { Id = "imp", Name = "Prism: X", Version = "1.21.1" });

        Assert.False(OriginLauncher.App.Core.Import.Launcher.ImportSettingsRules.SelectImported(s, "gone", "1.21.1"));
        Assert.Equal("mine", s.SelectedProfileId);
        Assert.False(OriginLauncher.App.Core.Import.Launcher.ImportSettingsRules.SelectImported(s, "imp", "1.20.1")); // wrong version
        Assert.Equal("1.20.1", s.SelectedVersion);

        Assert.True(OriginLauncher.App.Core.Import.Launcher.ImportSettingsRules.SelectImported(s, "imp", "1.21.1"));
        Assert.Equal("imp", s.SelectedProfileId);
        Assert.Equal("1.21.1", s.SelectedVersion);
    }
}
