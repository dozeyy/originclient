using OriginLauncher.App.Core.Updates;
using Xunit;
using static OriginLauncher.App.Core.Updates.UpdateFetchRules;

namespace OriginLauncher.Tests;

// When the self-update may download in the background (Core/Updates/UpdateFetchRules). The first test is the race the
// review found: after the Update click took the ready stage, nothing is ready or downloading any more, and the next
// poll must NOT fetch the same version again (that rebuilt, i.e. deleted, the stage the click was installing).
public sealed class UpdateFetchRulesTests
{
    private static readonly Version V85 = new(1, 0, 85);
    private static readonly Version V86 = new(1, 0, 86);
    private static readonly DateTime Now = new(2026, 11, 3, 12, 0, 0, DateTimeKind.Utc);

    private static State Idle => new(null, null, null, false, null, DateTime.MinValue);

    [Fact]
    public void After_the_click_took_the_ready_stage_a_poll_never_fetches_it_again()
    {
        var installing = Idle with { Installing = V85 }; // ready and running are both null now
        Assert.Equal(Decision.AlreadyHandled, Background(V85, quiet: false, gameRunning: false, installing, Now));
        Assert.NotEqual(Decision.Start, Background(V85, quiet: true, gameRunning: false, installing, Now));
    }

    [Fact]
    public void A_newer_release_mid_install_neither_downloads_nor_cleans_the_installing_stage()
    {
        var installing = Idle with { Installing = V85 };
        Assert.Equal(Decision.NotNow, Background(V86, quiet: false, gameRunning: false, installing, Now));
        Assert.Contains(V85, Keep(fetching: V86, offered: V86, installing));
    }

    [Fact]
    public void Keep_holds_every_version_in_use()
    {
        var s = new State(new Version(1, 0, 1), new Version(1, 0, 2), new Version(1, 0, 3), false, null, DateTime.MinValue);
        Assert.Equal(new Version?[] { new(1, 0, 4), new(1, 0, 5), new(1, 0, 3), new(1, 0, 2), new(1, 0, 1) },
            Keep(new Version(1, 0, 4), new Version(1, 0, 5), s));
    }

    [Fact]
    public void A_running_game_holds_the_background_download_until_it_closes()
    {
        Assert.Equal(Decision.NotNow, Background(V85, quiet: false, gameRunning: true, Idle, Now));
        Assert.Equal(Decision.NotNow, Background(V85, quiet: true, gameRunning: true, Idle, Now));
        Assert.Equal(Decision.Start, Background(V85, quiet: false, gameRunning: false, Idle, Now));
    }

    [Fact]
    public void Ready_or_running_is_already_handled()
    {
        Assert.Equal(Decision.AlreadyHandled, Background(V85, false, false, Idle with { Ready = V85 }, Now));
        Assert.Equal(Decision.AlreadyHandled, Background(V85, false, false, Idle with { Running = new Version(1, 0, 85, 0) }, Now));
    }

    [Fact]
    public void A_newer_release_supersedes_an_older_background_download_but_not_a_clicked_or_newer_one()
    {
        Assert.Equal(Decision.Start, Background(V86, false, false, Idle with { Running = V85 }, Now));
        Assert.Equal(Decision.NotNow, Background(V86, false, false, Idle with { Running = V85, RunningJoined = true }, Now));
        Assert.Equal(Decision.NotNow, Background(V85, false, false, Idle with { Running = V86 }, Now));
    }

    [Fact]
    public void An_ordinary_failure_backs_off_but_the_quiet_retry_has_its_own_back_off()
    {
        var failed = Idle with { BackoffVersion = V85, BackoffUntilUtc = Now.AddMinutes(2) };
        Assert.Equal(Decision.NotNow, Background(V85, quiet: false, gameRunning: false, failed, Now));
        Assert.Equal(Decision.Start, Background(V85, quiet: false, gameRunning: false, failed, Now.AddMinutes(3)));
        Assert.Equal(Decision.Start, Background(V85, quiet: true, gameRunning: false, failed, Now));
        Assert.Equal(Decision.Start, Background(V86, quiet: false, gameRunning: false, failed, Now));
    }
}
