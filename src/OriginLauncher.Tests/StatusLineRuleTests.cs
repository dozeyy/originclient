using OriginLauncher.App.Core;
using Xunit;

namespace OriginLauncher.Tests;

// Home's status line (bug run 7, WP-2): the running line's notices end with what to do, so a long line wraps (up to
// StatusLineRule.MaxRows rows) and carries its whole text as a tooltip; a short line gets none.
public sealed class StatusLineRuleTests
{
    [Fact]
    public void A_long_line_or_any_error_carries_its_whole_text_as_a_tooltip()
    {
        Assert.False(StatusLineRule.NeedsTooltip("Ready to play", isError: false));
        Assert.False(StatusLineRule.NeedsTooltip("Running - Steve", isError: false));
        Assert.True(StatusLineRule.NeedsTooltip("Couldn't start", isError: true));
        Assert.False(StatusLineRule.NeedsTooltip("", isError: true));
        Assert.True(StatusLineRule.NeedsTooltip(
            "Running - Steve. Shaders are off for this profile: no tested Iris for your Sodium 0.6.9. Add a matching Iris to turn them back on.",
            isError: false));
    }

    [Fact]
    public void The_running_notice_fits_in_the_rows_a_line_may_take()
    {
        // ~5.5 px a character at 400 px (the 07b measure: 160 characters needed 882 px), so about 72 to a row.
        const double charsPerRow = 400 / 5.5;
        var line = "Running - Steve. Shaders are off for this profile: no tested Iris for your Sodium 0.6.9. Add a matching Iris to turn them back on.";
        Assert.True(line.Length / charsPerRow < StatusLineRule.MaxRows - 0.5, $"{line.Length} characters");
    }
}
