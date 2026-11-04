using System;
using System.IO;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// Bug run 6, KD-5: "Also delete my worlds" with a Minecraft game still open half-deleted its worlds and then named
// the install folder as the one left over. RunningGames finds the open game first (the same latest.log lock test as
// the launcher's GameRunningProbe); UninstallResult words the Problem page for what was really left.
public class RunningGamesTests
{
    private static string Log(string dataRoot, params string[] parts)
    {
        var dir = Path.Combine(dataRoot, Path.Combine(parts), "logs");
        Directory.CreateDirectory(dir);
        var log = Path.Combine(dir, "latest.log");
        File.WriteAllText(log, "[main/INFO]: Setting user: Alex");
        return log;
    }

    private static FileStream Hold(string path) =>
        new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None);

    [Fact]
    public void An_open_game_in_any_account_folder_is_found_once_per_version()
    {
        var root = TestKit.Temp("games");
        var a = Log(root, "instances", "1.21.1", "accounts", "alex");
        var b = Log(root, "instances", "1.21.1", "accounts", "offline");
        var c = Log(root, "instances", "1.20.1", "accounts", "alex");
        Log(root, "instances", "1.8.9", "accounts", "alex"); // closed game: its log is free

        using (Hold(a))
        using (Hold(b))
        using (Hold(c))
            Assert.Equal(new[] { "1.20.1", "1.21.1" }, RunningGames.In(root));

        Assert.Empty(RunningGames.In(root)); // games closed: nothing open
    }

    [Fact]
    public void A_game_folder_from_before_per_account_folders_counts_too()
    {
        var root = TestKit.Temp("games");
        var log = Log(root, "instances", "1.21.1");
        using (Hold(log))
            Assert.Equal(new[] { "1.21.1" }, RunningGames.In(root));
    }

    [Fact]
    public void No_data_folder_means_no_game()
    {
        Assert.Empty(RunningGames.In(Path.Combine(TestKit.Temp("games"), "missing")));
    }

    [Fact]
    public void The_close_first_message_names_every_open_version()
    {
        Assert.Equal("", RunningGames.CloseFirstMessage(Array.Empty<string>()));
        Assert.StartsWith("Minecraft 1.21.1 is still open.", RunningGames.CloseFirstMessage(new[] { "1.21.1" }));
        Assert.StartsWith("Minecraft 1.8.9, 1.20.1 and 1.21.1 are still open.",
            RunningGames.CloseFirstMessage(new[] { "1.8.9", "1.20.1", "1.21.1" }));
    }

    [Fact]
    public void The_problem_page_says_the_data_was_kept_when_a_game_was_open()
    {
        var result = new UninstallResult(installFolderLeft: false, dataLeft: true, new[] { "1.21.1" });

        Assert.False(result.Ok);
        Assert.Contains("were kept because Minecraft 1.21.1 was still open", result.ProblemText(@"C:\Origin"));
    }

    [Fact]
    public void The_problem_page_tells_data_leftovers_apart_from_install_leftovers()
    {
        Assert.Contains("some of your data", new UninstallResult(false, true).ProblemText(@"C:\Origin"));
        Assert.Contains(@"C:\Origin", new UninstallResult(true, true).ProblemText(@"C:\Origin"));
        Assert.DoesNotContain("data", new UninstallResult(true, false).ProblemText(@"C:\Origin"));
        Assert.True(new UninstallResult(false, false).Ok);
    }
}
