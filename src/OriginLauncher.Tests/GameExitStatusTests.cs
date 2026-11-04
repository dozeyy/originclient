using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Launch;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Home's status line after a game stops (GameExitStatus, shared by the Windows, Mac and Linux launchers): a non-zero
/// exit code alone never leaves a red line; only CrashEngine's verdict may.
/// </summary>
public class GameExitStatusTests
{
    private static CrashVerdict V(string? latest, int exit, bool starting) =>
        CrashEngine.Diagnose(new CrashEvidence(exit, false, starting, null, latest, null, null, [], 4096, 16384));

    [Fact]
    public void The_moment_a_game_exits_the_line_is_neutral_and_names_no_exit_code()
    {
        var line = GameExitStatus.OnExit();
        Assert.Equal(LaunchTone.Neutral, line.Tone);
        Assert.DoesNotContain("code", line.Text);
    }

    [Fact]
    public void A_normal_quit_with_exit_code_1_ends_on_Ready()
    {
        var verdict = V("[Render thread/INFO]: Stopping!", exit: 1, starting: false);
        Assert.False(verdict.IsCrash);
        Assert.Equal(GameExitStatus.Ready, GameExitStatus.AfterVerdict(verdict.IsCrash, crashedWhileStarting: false));
    }

    [Fact]
    public void A_task_manager_kill_in_game_ends_on_Ready()
    {
        var verdict = V("[Render thread/INFO]: Saving chunks", exit: -1, starting: false);
        Assert.False(verdict.IsCrash);
        Assert.Equal(LaunchTone.Neutral, GameExitStatus.AfterVerdict(verdict.IsCrash, false)!.Tone);
    }

    [Fact]
    public void A_real_crash_in_game_turns_the_line_red_and_a_boot_crash_keeps_its_own_line()
    {
        Assert.Equal(GameExitStatus.CrashedInGame, GameExitStatus.AfterVerdict(isCrash: true, crashedWhileStarting: false));
        Assert.Equal(LaunchTone.Error, GameExitStatus.CrashedInGame.Tone);
        Assert.Null(GameExitStatus.AfterVerdict(isCrash: true, crashedWhileStarting: true));
        Assert.Equal(GameExitStatus.Ready, GameExitStatus.AfterVerdict(isCrash: false, crashedWhileStarting: true));
    }
}
