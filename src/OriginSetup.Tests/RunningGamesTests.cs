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

#if NET
    // Review of run 6: one try/catch around the whole scan failed OPEN: a folder that couldn't be listed ended the
    // scan with nothing found, and the uninstaller deleted the data under a game that was running. Each folder is
    // checked on its own now, and one that can't be checked keeps the data.
    [Fact]
    public void A_folder_that_cannot_be_listed_keeps_the_data_and_the_scan_goes_on()
    {
        if (OperatingSystem.IsWindows()) return; // the lock below is a Unix folder permission
        var root = TestKit.Temp("games");
        var open = Log(root, "instances", "1.20.1", "accounts", "alex");
        Log(root, "instances", "1.21.1", "accounts", "alex");
        var locked = Path.Combine(root, "instances", "1.21.1", "accounts");
        File.SetUnixFileMode(locked, UnixFileMode.None);
        try
        {
            try { Directory.EnumerateDirectories(locked).ToList(); return; } // running as root: nothing can be locked
            catch (UnauthorizedAccessException) { }

            RunningGames.Result result;
            using (Hold(open))
                result = RunningGames.Check(root);

            Assert.Equal(new[] { "1.20.1" }, result.Open); // the scan went past the locked folder
            Assert.Equal(new[] { locked }, result.Unchecked);
            Assert.True(result.KeepData);
            Assert.True(RunningGames.Check(root).KeepData); // nothing open now, but 1.21.1 still can't be checked
        }
        finally
        {
            File.SetUnixFileMode(locked, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
    }
#endif

    [Fact]
    public void Nothing_open_and_everything_checked_lets_the_data_go()
    {
        var root = TestKit.Temp("games");
        Log(root, "instances", "1.21.1", "accounts", "alex");
        var result = RunningGames.Check(root);
        Assert.False(result.KeepData);
        Assert.Empty(result.Unchecked);
    }

    [Fact]
    public void The_problem_page_says_why_when_the_check_could_not_finish()
    {
        var result = new UninstallResult(installFolderLeft: false, dataLeft: true) { DataUnchecked = true };
        Assert.Contains("couldn't check whether Minecraft is still open", result.ProblemText(@"C:\Origin"));
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
