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
        Assert.Equal(("CHECKING...", false), ImportLogic.ImportAllButton(true, false, true, 5, false));
        Assert.Equal(("CHECK AGAIN", true), ImportLogic.ImportAllButton(false, true, false, 0, false));
        Assert.Equal(("CHECK AGAIN", false), ImportLogic.ImportAllButton(false, true, false, 0, true));
        // The label is the number the plan will import: 9 found and 2 unsupported reads 7, and stays a number after unticks.
        Assert.Equal(("IMPORT 7", true), ImportLogic.ImportAllButton(false, false, true, 7, false));
        Assert.Equal(("IMPORT 3", true), ImportLogic.ImportAllButton(false, false, true, 3, false));
        Assert.Equal(("IMPORT 1 SETUP", true), ImportLogic.ImportAllButton(false, false, true, 1, false));
        Assert.Equal(("IMPORT", false), ImportLogic.ImportAllButton(false, false, false, 0, false));
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
        Assert.Equal("Forge mods can't come over (Fabric only, except 1.8.9)", BulkRules.ForgeCaption);
        // The full sentences (plan, summary, Done) are the engine's, one source each.
        Assert.Equal("Forge mods can't come over. Origin runs Fabric, except on 1.8.9.", BulkRules.ForgeNote);
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
}
