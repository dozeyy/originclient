using OriginLauncher.App.Core.GameFolders;
using Xunit;

namespace OriginLauncher.Tests;

// Bug run 6, KC-3: the decisions Origin makes when a player's own Sodium/Iris replace Origin's ("Shaders are off for
// this profile...") reached only a log. Home now adds them to the running line of that game.
public class LaunchedModsTests
{
    private const string Shaders =
        "Shaders are off for this profile: Origin has not tested an Iris with your Sodium 0.6.9. Add a matching Iris to the profile to turn them back on.";

    [Fact]
    public void The_running_line_carries_the_first_notice_and_counts_the_rest()
    {
        Assert.Equal("Running - Alex", LaunchedMods.WithNotices("Running - Alex", []));
        Assert.Equal("Running - Alex. " + Shaders, LaunchedMods.WithNotices("Running - Alex", [Shaders]));
        Assert.Equal("Running - Alex. " + Shaders + " (and 2 more)",
            LaunchedMods.WithNotices("Running - Alex", [Shaders, "b", "c"]));
    }

    [Fact]
    public void A_launch_is_remembered_per_game_folder_whatever_the_path_spelling()
    {
        var gameDir = Path.Combine(Path.GetTempPath(), "origin-launched-" + Guid.NewGuid().ToString("N"), "accounts", "alex");
        try
        {
            Assert.Null(LaunchedMods.FolderFor(gameDir));
            Assert.Empty(LaunchedMods.NoticesFor(gameDir));

            LaunchedMods.Record(gameDir + Path.DirectorySeparatorChar, Path.Combine(gameDir, "mods-profile"), [Shaders]);

            Assert.Equal(Path.Combine(gameDir, "mods-profile"), LaunchedMods.FolderFor(gameDir));
            Assert.Equal([Shaders], LaunchedMods.NoticesFor(gameDir));

            LaunchedMods.Record(gameDir, Path.Combine(gameDir, "mods-origin-only")); // the next launch replaces it
            Assert.Empty(LaunchedMods.NoticesFor(gameDir));
        }
        finally
        {
            LaunchedMods.Forget(gameDir);
        }
    }
}
