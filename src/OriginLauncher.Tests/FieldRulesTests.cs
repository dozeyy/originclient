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
    public void A_full_folder_path_is_fine(string path) => Assert.Null(FieldRules.InstallFolderProblem(path, FieldRules.FolderOs.Windows));

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(null)]
    public void An_empty_folder_asks_for_one(string? path) => Assert.Equal("Enter a folder.", FieldRules.InstallFolderProblem(path, FieldRules.FolderOs.Windows));

    [Theory]
    [InlineData("games")]
    [InlineData(@"C:Games")]
    [InlineData(@"\Games")]
    [InlineData(@"..\Games")]
    public void A_relative_or_half_path_asks_for_a_full_one(string path) =>
        Assert.Contains("full folder path", FieldRules.InstallFolderProblem(path, FieldRules.FolderOs.Windows));

    [Theory]
    [InlineData(@"C:\Ga*mes")]
    [InlineData(@"C:\Games\what?")]
    [InlineData("C:\\Games\\a|b")]
    public void A_character_windows_forbids_is_named(string path) =>
        Assert.Contains("character Windows does not allow", FieldRules.InstallFolderProblem(path, FieldRules.FolderOs.Windows));

    // macOS / Linux: the default folder itself ("/Users/x/Library/Application Support/...") used to be refused.
    [Theory]
    [InlineData("/Users/x/Library/Application Support/OriginLauncher/instances", FieldRules.FolderOs.Mac)]
    [InlineData("/Volumes/Games disk/Origin?*|", FieldRules.FolderOs.Mac)]
    [InlineData("/home/x/.local/share/OriginLauncher/instances", FieldRules.FolderOs.Linux)]
    [InlineData("  /mnt/games/Origin  ", FieldRules.FolderOs.Linux)]
    public void A_full_unix_path_is_fine(string path, FieldRules.FolderOs os) => Assert.Null(FieldRules.InstallFolderProblem(path, os));

    [Theory]
    [InlineData("games", FieldRules.FolderOs.Mac, "/Users/you/Games/Origin")]
    [InlineData("~/Games", FieldRules.FolderOs.Mac, "/Users/you/Games/Origin")]
    [InlineData(@"C:\Games\Origin", FieldRules.FolderOs.Linux, "/home/you/Games/Origin")]
    [InlineData("games", FieldRules.FolderOs.Linux, "/home/you/Games/Origin")]
    public void A_relative_unix_path_gets_that_os_example(string path, FieldRules.FolderOs os, string example) =>
        Assert.Contains(example, FieldRules.InstallFolderProblem(path, os));

    [Fact]
    public void A_control_character_is_refused_on_unix_too() =>
        Assert.Equal("That folder name has a character that isn't allowed.", FieldRules.InstallFolderProblem("/home/x/a\0b", FieldRules.FolderOs.Linux));

    [Fact]
    public void The_one_argument_rule_follows_this_machine()
    {
        var expected = OperatingSystem.IsWindows() ? FieldRules.FolderOs.Windows
            : OperatingSystem.IsMacOS() ? FieldRules.FolderOs.Mac : FieldRules.FolderOs.Linux;
        foreach (var path in new[] { "/home/x/Games", @"C:\Games", "games" })
            Assert.Equal(FieldRules.InstallFolderProblem(path, expected), FieldRules.InstallFolderProblem(path));
    }

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
