using OriginLauncher.App.Core;
using Xunit;

namespace OriginLauncher.Tests;

// The Settings text fields: what is rejected, and the sentence the player reads under the box.
public class FieldRulesTests
{
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
