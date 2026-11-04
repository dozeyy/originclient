using OriginLauncher.App.Core.Updates;
using Xunit;
using static OriginLauncher.App.Core.Updates.UpdatePrefetch;

namespace OriginLauncher.Tests;

// The launcher self-update's background download (Core/Updates/UpdatePrefetch; DECISIONS 2026-11-03): a release starts
// downloading the moment it is offered, the Update click then only swaps, and nothing about it can block Play.
public sealed class UpdatePrefetchTests
{
    private static readonly Version V58 = new(1, 0, 58);
    private static readonly Version V59 = new(1, 0, 59);
    private static readonly DateTime T0 = new(2026, 11, 4, 12, 0, 0, DateTimeKind.Utc);
    private static readonly Conditions Free = new(GameRunning: false, Online: true, Metered: false);

    private static UpdatePrefetch Downloading(Version v)
    {
        var p = new UpdatePrefetch();
        Assert.Equal(Start.Go, p.TryStart(v, Free, T0, out var dropped));
        Assert.Null(dropped);
        Assert.Equal(Phase.Downloading, p.Current);
        return p;
    }

    [Fact]
    public void Published_then_downloading_then_staged_then_the_click_swaps()
    {
        var p = Downloading(V58);
        Assert.Equal(Start.AlreadyHandled, p.TryStart(V58, Free, T0, out _)); // the next poll never starts a second one

        Assert.True(p.Staged(V58));
        Assert.Equal(Phase.Staged, p.Current);
        Assert.Equal(Start.AlreadyHandled, p.TryStart(V58, Free, T0, out _));

        Assert.Equal(Click.SwapStaged, p.BeginInstall(V58, out var dropped));
        Assert.Null(dropped);
        Assert.Equal(V58, p.Installing);
        Assert.Equal(SwapStep.Swap, BeforeSwap(gameRunning: false));
    }

    [Fact]
    public void A_click_during_the_download_joins_it_instead_of_downloading_twice()
    {
        var p = Downloading(V58);
        Assert.Equal(Click.JoinDownload, p.BeginInstall(V58, out _));
        Assert.True(p.Joined);
        Assert.True(p.Staged(V58)); // the joined download finishes: the click swaps that stage
        Assert.Equal(Phase.Staged, p.Current);
    }

    [Fact]
    public void A_click_with_nothing_in_the_background_downloads_it_itself()
    {
        var p = new UpdatePrefetch();
        Assert.Equal(Click.DownloadNow, p.BeginInstall(V58, out _));
    }

    [Fact]
    public void A_failed_background_download_backs_off_and_the_click_still_downloads()
    {
        var p = Downloading(V58);
        var wait = p.Failed(V58, cancelled: false, T0);
        Assert.Equal<TimeSpan?>(FirstRetry, wait);
        Assert.Equal(Phase.Idle, p.Current);

        // The click is never held back by the back-off: it falls back to its own verified download.
        Assert.Equal(Click.DownloadNow, new UpdatePrefetch().BeginInstall(V58, out _));

        // The background tries again only after the back-off, then doubles it, capped.
        Assert.Equal(Start.NotNow, p.TryStart(V58, Free, T0 + TimeSpan.FromMinutes(1), out _));
        Assert.Equal(Start.Go, p.TryStart(V58, Free, T0 + FirstRetry, out _));
        Assert.Equal<TimeSpan?>(TimeSpan.FromMinutes(4), p.Failed(V58, false, T0 + FirstRetry));
        for (var i = 0; i < 10; i++)
        {
            p.TryStart(V58, Free, T0 + TimeSpan.FromDays(i + 1), out _);
            p.Failed(V58, false, T0 + TimeSpan.FromDays(i + 1));
        }
        Assert.Equal<TimeSpan?>(MaxRetry, p.Failed(V58, false, T0 + TimeSpan.FromDays(20)));
    }

    [Fact]
    public void A_stage_that_changed_on_disk_falls_back_to_the_clicks_own_download()
    {
        var p = Downloading(V58);
        p.Staged(V58);
        Assert.Equal(Click.SwapStaged, p.BeginInstall(V58, out _));
        p.FallBackToClickDownload(V58);
        Assert.Equal(Phase.Idle, p.Current);
        Assert.Equal(V58, p.Installing); // still the click's: nothing else downloads or cleans it meanwhile
        Assert.Equal(Start.AlreadyHandled, p.TryStart(V58, Free, T0, out _));
    }

    [Fact]
    public void A_joined_failure_is_the_clicks_to_count_not_a_background_back_off()
    {
        var p = Downloading(V58);
        p.BeginInstall(V58, out _);
        Assert.Null(p.Failed(V58, cancelled: false, T0));
        p.InstallFailed(V58);
        Assert.Equal(Start.Go, p.TryStart(V58, Free, T0, out _)); // no back-off was started
    }

    [Fact]
    public void A_cancel_for_a_game_or_the_exit_is_not_a_failure()
    {
        var p = Downloading(V58);
        Assert.Null(p.Failed(V58, cancelled: true, T0));
        Assert.Equal(Phase.Idle, p.Current);
        Assert.Equal(Start.Go, p.TryStart(V58, Free, T0, out _)); // the next poll resumes at once
    }

    [Fact]
    public void A_running_game_waits_for_both_the_download_and_the_swap()
    {
        var p = new UpdatePrefetch();
        Assert.Equal(Start.NotNow, p.TryStart(V58, Free with { GameRunning = true }, T0, out _));
        Assert.Equal(Phase.Idle, p.Current);
        Assert.Equal(SwapStep.WaitForGame, BeforeSwap(gameRunning: true));

        // The game closed: the next poll starts it.
        Assert.Equal(Start.Go, p.TryStart(V58, Free, T0, out _));
    }

    [Fact]
    public void Offline_and_metered_connections_wait_for_a_later_poll()
    {
        var p = new UpdatePrefetch();
        Assert.Equal(Start.NotNow, p.TryStart(V58, Free with { Online = false }, T0, out _));
        Assert.Equal(Start.NotNow, p.TryStart(V58, Free with { Metered = true }, T0, out _));
        Assert.Equal(Phase.Idle, p.Current);
        Assert.Equal(Start.Go, p.TryStart(V58, Free, T0, out _));
    }

    [Fact]
    public void While_installing_nothing_else_downloads_and_its_files_are_kept()
    {
        var p = Downloading(V58);
        p.Staged(V58);
        p.BeginInstall(V58, out _);

        // A newer release appears while the click swaps 58: nothing starts, 58's stage is never dropped.
        Assert.Equal(Start.NotNow, p.TryStart(V59, Free, T0, out var dropped));
        Assert.Null(dropped);
        Assert.Contains(V58, p.Keep());
        Assert.False(p.Withdrawn(V58)); // even a withdrawal never deletes the stage being installed

        p.InstallFailed(V58);
        Assert.Null(p.Installing);
        Assert.Equal(Start.Go, p.TryStart(V59, Free, T0, out _));
    }

    [Fact]
    public void A_newer_release_supersedes_an_older_background_download_or_stage()
    {
        var p = Downloading(V58);
        Assert.Equal(Start.Go, p.TryStart(V59, Free, T0, out var dropped));
        Assert.Equal(V58, dropped);
        Assert.False(p.Staged(V58)); // the cancelled 58 finishing late is not wanted any more
        Assert.Equal(Phase.Downloading, p.Current);
        Assert.Equal(V59, p.Version);

        Assert.True(p.Staged(V59));
        Assert.Equal(Start.NotNow, p.TryStart(V58, Free, T0, out dropped)); // never steps backwards
        Assert.Null(dropped);
    }

    [Fact]
    public void Clicking_a_newer_version_drops_the_older_background_stage()
    {
        var p = Downloading(V58);
        p.Staged(V58);
        Assert.Equal(Click.DownloadNow, p.BeginInstall(V59, out var dropped));
        Assert.Equal(V58, dropped);
    }

    [Fact]
    public void A_withdrawn_offer_forgets_its_stage()
    {
        var p = Downloading(V58);
        p.Staged(V58);
        Assert.True(p.Withdrawn(V58));
        Assert.Equal(Phase.Idle, p.Current);
        Assert.DoesNotContain(V58, p.Keep());
    }

    // Review fix 1: the stage is hashed again AFTER the wait for a game (it can last hours), right before the swap. A
    // mismatch (an antivirus took a staged file meanwhile) never swaps: the click downloads it afresh, once; a second
    // mismatch is an ordinary failure, never a half-old, half-new install.
    [Fact]
    public void A_stage_that_changes_during_the_game_wait_is_downloaded_again_never_swapped()
    {
        Assert.Equal(PreSwap.Swap, CheckBeforeSwap(stageMatches: true, attempt: 1));
        Assert.Equal(PreSwap.Restage, CheckBeforeSwap(stageMatches: false, attempt: 1));
        Assert.Equal(PreSwap.Swap, CheckBeforeSwap(stageMatches: true, attempt: 2));
        Assert.Equal(PreSwap.Fail, CheckBeforeSwap(stageMatches: false, attempt: MaxStageAttempts));

        // Restaging forgets the background stage but the click keeps the version: nothing else downloads or deletes it.
        var p = Downloading(V58);
        p.Staged(V58);
        p.BeginInstall(V58, out _);
        p.FallBackToClickDownload(V58);
        Assert.False(p.MayDeleteStage(V58));
    }

    // Review fix 2: a delete is decided under the lock but runs later. Asked again right before deleting, a version the
    // click took in between is never deleted (its folder is being staged).
    [Fact]
    public void A_stage_delete_decided_before_a_click_is_cancelled_by_it()
    {
        var p = Downloading(V58);
        p.Failed(V58, cancelled: true, T0);
        Assert.True(p.MayDeleteStage(V58)); // decided: nobody wants 58's stage

        p.BeginInstall(V58, out _); // the click takes 58 before the delete runs
        Assert.False(p.MayDeleteStage(V58));

        p.InstallFailed(V58);
        Assert.True(p.MayDeleteStage(V58));

        var q = Downloading(V58);
        Assert.False(q.MayDeleteStage(V58)); // a background download of it holds the stage too
        q.Staged(V58);
        Assert.False(q.MayDeleteStage(V58));
        Assert.True(q.MayDeleteStage(V59));
    }

    // Review fix 3: the game check is read again inside the lock right before the start, so a game that began while
    // the network probes ran keeps the download from starting (TryStart is the in-lock decision).
    [Fact]
    public void A_game_that_starts_during_the_probes_keeps_the_download_from_starting()
    {
        var p = new UpdatePrefetch();
        var probedBeforeTheGame = Free;
        var reReadInTheLock = probedBeforeTheGame with { GameRunning = probedBeforeTheGame.GameRunning || true };
        Assert.Equal(Start.NotNow, p.TryStart(V58, reReadInTheLock, T0, out _));
        Assert.Equal(Phase.Idle, p.Current);
        Assert.DoesNotContain(V58, p.Keep());
    }

    [Fact]
    public void Versions_compare_with_or_without_the_fourth_number()
    {
        var p = Downloading(new Version(1, 0, 58, 0));
        Assert.Equal(Start.AlreadyHandled, p.TryStart(V58, Free, T0, out _));
    }

    [Theory]
    [InlineData(0x0u, false)]       // unknown
    [InlineData(0x1u, false)]       // unrestricted
    [InlineData(0x2u, true)]        // fixed plan
    [InlineData(0x4u, true)]        // variable (pay per byte)
    [InlineData(0x10001u, true)]    // over the data limit
    [InlineData(0x40001u, true)]    // roaming
    [InlineData(0x20001u, false)]   // congested only
    public void Windows_network_cost_flags(uint cost, bool metered) => Assert.Equal(metered, IsMeteredCost(cost));

    [Theory]
    [InlineData("(<uint32 1>,)\n", true)]  // yes
    [InlineData("(<uint32 3>,)", true)]    // guess-yes
    [InlineData("(<uint32 2>,)", false)]   // no
    [InlineData("(<uint32 4>,)", false)]   // guess-no
    [InlineData("(<uint32 0>,)", false)]   // unknown
    [InlineData("", false)]
    [InlineData(null, false)]
    [InlineData("Error: GDBus.Error:org.freedesktop.DBus.Error.ServiceUnknown", false)]
    public void Linux_NetworkManager_metered(string? output, bool metered) => Assert.Equal(metered, IsNetworkManagerMetered(output));
}
