using OriginLauncher.App.Core;
using Xunit;

namespace OriginLauncher.Tests;

// The Settings text fields: what is rejected, and the sentence the player reads under the box.
public class FieldRulesTests
{
    [Theory]
    [InlineData(@"C:\Games\Origin")]
    [InlineData(@"D:\")]
    [InlineData(@"\\server\share\mc")]
    [InlineData(@"  C:\Games\Origin  ")]
    public void A_full_folder_path_is_fine(string path) => Assert.Null(FieldRules.InstallFolderProblem(path));

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(null)]
    public void An_empty_folder_asks_for_one(string? path) => Assert.Equal("Enter a folder.", FieldRules.InstallFolderProblem(path));

    [Theory]
    [InlineData("games")]
    [InlineData(@"C:Games")]
    [InlineData(@"\Games")]
    [InlineData(@"..\Games")]
    public void A_relative_or_half_path_asks_for_a_full_one(string path) =>
        Assert.Contains("full folder path", FieldRules.InstallFolderProblem(path));

    [Theory]
    [InlineData(@"C:\Ga*mes")]
    [InlineData(@"C:\Games\what?")]
    [InlineData("C:\\Games\\a|b")]
    public void A_character_windows_forbids_is_named(string path) =>
        Assert.Contains("character Windows does not allow", FieldRules.InstallFolderProblem(path));

    [Theory]
    [InlineData("1920", 320)]
    [InlineData(" 320 ", 320)]
    [InlineData("240", 240)]
    public void A_big_enough_number_is_fine(string text, int minimum) =>
        Assert.Null(FieldRules.ResolutionProblem(text, minimum, "Width"));

    [Theory]
    [InlineData("abc")]
    [InlineData("")]
    [InlineData("12.5")]
    public void Not_a_whole_number_is_named(string text) =>
        Assert.Equal("Width must be a whole number.", FieldRules.ResolutionProblem(text, 320, "Width"));

    [Fact]
    public void Too_small_names_the_limit() =>
        Assert.Equal("Height must be at least 240 pixels.", FieldRules.ResolutionProblem("100", FieldRules.MinHeight, "Height"));
}
