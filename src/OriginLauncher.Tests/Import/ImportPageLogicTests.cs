using OriginLauncher.App.Core.Import;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests.Import;

/// <summary>
/// The Import page's pure parts: the words (sentence case, "a, b and c", short folder paths) and the per-setup ticks
/// ("Import everything" / "Choose what to import"). No WPF: ImportLogic and ImportFlowModel only.
/// </summary>
public class ImportPageLogicTests
{
    private static readonly string[] Supported = { "1.21.1", "1.20.1", "1.8.9" };

    private static DetectedInstance Setup(string name, string? mc, SourceKind kind) => new()
    {
        Kind = kind, Name = name, McVersion = mc, Loader = LoaderKind.Fabric, Confidence = Confidence.Exact,
        GameDir = Path.Combine(Path.GetTempPath(), "origin-page-never-read", kind.ToString(), name),
    };

    // ---------------------------------------------------------------- the words

    [Theory]
    [InlineData("IMPORT 12", "Import 12")]
    [InlineData("CHECKING...", "Checking...")]
    [InlineData("Import everything", "Import everything")] // already mixed case: left alone
    [InlineData("", "")]
    [InlineData("123", "123")]                              // no letters: left alone
    public void Sentence_sets_engine_caps_in_sentence_case(string input, string expected) =>
        Assert.Equal(expected, ImportLogic.Sentence(input));

    [Fact]
    public void JoinParts_reads_like_a_person_says_it()
    {
        Assert.Equal("", ImportLogic.JoinParts(Array.Empty<string>()));
        Assert.Equal("3 setups", ImportLogic.JoinParts(new[] { "3 setups" }));
        Assert.Equal("3 setups and 4 worlds", ImportLogic.JoinParts(new[] { "3 setups", "4 worlds" }));
        Assert.Equal("17 setups, 54 worlds and 320 mods", ImportLogic.JoinParts(new[] { "17 setups", "54 worlds", "320 mods" }));
    }

    [Fact]
    public void ShortPath_names_the_windows_folders_and_leaves_other_paths_alone()
    {
        const string app = @"C:\Users\Will\AppData\Roaming";
        const string local = @"C:\Users\Will\AppData\Local";
        Assert.Equal(@"%AppData%\PrismLauncher\instances", ImportLogic.ShortPath(@"C:\Users\Will\AppData\Roaming\PrismLauncher\instances", app, local));
        Assert.Equal(@"%LocalAppData%\Modrinth", ImportLogic.ShortPath(@"c:\users\will\appdata\local\Modrinth", app, local));
        Assert.Equal(@"D:\Games\curseforge\Instances", ImportLogic.ShortPath(@"D:\Games\curseforge\Instances", app, local));
        Assert.Equal("", ImportLogic.ShortPath("  ", app, local));
        // a sibling that merely starts with the same letters is not inside the folder
        Assert.Equal(@"C:\Users\Will\AppData\RoamingX\a", ImportLogic.ShortPath(@"C:\Users\Will\AppData\RoamingX\a", app, local));
    }

    [Fact]
    public void CommonFolder_is_the_deepest_folder_the_setups_share()
    {
        Assert.Equal("", ImportLogic.CommonFolder(Array.Empty<string>()));
        Assert.Equal(@"C:\mc\Instances\Skyblock", ImportLogic.CommonFolder(new[] { @"C:\mc\Instances\Skyblock\" }));
        Assert.Equal(@"C:\mc\Instances",
            ImportLogic.CommonFolder(new[] { @"C:\mc\Instances\Skyblock", @"c:\MC\instances\Hardcore", @"C:\mc\Instances\Vanilla+" }));
        // nothing shared past the drive: the first setup's own folder is still true
        Assert.Equal(@"C:\a\one", ImportLogic.CommonFolder(new[] { @"C:\a\one", @"D:\b\two" }));
    }

    // ---------------------------------------------------------------- per-setup ticks

    [Fact]
    public void Every_setup_Origin_can_run_is_going_at_first_and_a_version_it_cannot_run_never_is()
    {
        var a = Setup("Skyblock", "1.21.1", SourceKind.CurseForge);
        var b = Setup("Hardcore", "1.20.1", SourceKind.CurseForge);
        var odd = Setup("RLCraft", "1.20.6", SourceKind.CurseForge);
        var model = new ImportFlowModel(new[] { a, b, odd }, Supported);

        Assert.True(model.CanGo(a));
        Assert.False(model.CanGo(odd));
        Assert.True(model.IsGoing(a));
        Assert.False(model.IsGoing(odd));
        Assert.Equal(new[] { a, b }, model.GoingSetups());

        model.SetGoing(odd, true); // a tick on a setup that can't come over does nothing
        Assert.False(model.IsGoing(odd));
        Assert.Equal(2, model.GoingSetups().Count);
    }

    [Fact]
    public void GoingState_is_all_none_or_some_of_the_setups_that_can_come_over()
    {
        var a = Setup("Skyblock", "1.21.1", SourceKind.CurseForge);
        var b = Setup("Hardcore", "1.20.1", SourceKind.CurseForge);
        var odd = Setup("RLCraft", "1.20.6", SourceKind.CurseForge);
        var model = new ImportFlowModel(new[] { a, b, odd }, Supported);
        var mine = new[] { a, b, odd };

        Assert.True(model.GoingState(mine)); // the one that can't come over doesn't count against "all"
        model.SetGoing(a, false);
        Assert.Null(model.GoingState(mine));
        model.SetGoing(b, false);
        Assert.False(model.GoingState(mine));
        Assert.False(model.GoingState(new[] { odd })); // nothing in it can come over
    }

    [Fact]
    public void A_tick_brings_back_a_version_switched_off_in_What_to_bring()
    {
        var a = Setup("Skyblock", "1.21.1", SourceKind.CurseForge);
        var model = new ImportFlowModel(new[] { a }, Supported);
        model.SetSelected("1.21.1", false);

        Assert.False(model.IsGoing(a));
        model.SetGoing(a, true);
        Assert.True(model.IsGoing(a));
    }

    [Fact]
    public void The_page_brings_worlds_by_default_and_a_switch_still_leaves_them_out()
    {
        var a = Setup("Skyblock", "1.21.1", SourceKind.CurseForge);
        var model = new ImportFlowModel(new[] { a }, Supported) { BringByDefault = ImportCategories.All };

        Assert.Equal(ImportCategories.All, model.BringFor("1.21.1"));
        Assert.True(model.BringsAll(ImportCategories.Worlds));

        model.SetBringAll(ImportCategories.Worlds, false);
        Assert.False(model.BringsAll(ImportCategories.Worlds));
        Assert.False(model.BringFor("1.21.1").HasFlag(ImportCategories.Worlds));
    }
}
