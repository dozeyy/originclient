using System.Security.Cryptography;
using OriginLauncher.App.Core.Updates;
using Xunit;
using static OriginLauncher.App.Core.Updates.UpdateGate;

namespace OriginLauncher.Tests;

// The mandatory-update gate (Core/Updates/UpdateGate): only an update that is known to be good can stop the
// player launching (mandate 4, never broken). A failed integrity check ungates it, backs off, retries quietly.
public sealed class UpdateGateTests
{
    private static readonly Version Current = new(1, 0, 57);
    private static readonly Version Next = new(1, 0, 58);
    private static readonly DateTime T0 = new(2026, 10, 19, 12, 0, 0, DateTimeKind.Utc);

    private static UpdateGate Published(out UpdateGate gate)
    {
        gate = new UpdateGate();
        Assert.Equal(Decision.Publish, gate.Evaluate(Next, Current, T0));
        gate.Publish(Next);
        return gate;
    }

    [Fact]
    public void A_new_release_is_published_and_gates_play_on_a_CI_build_only()
    {
        var gate = Published(out _);
        Assert.True(gate.Required(isDevBuild: false));
        Assert.False(gate.Required(isDevBuild: true));
        Assert.Equal(Decision.Skip, gate.Evaluate(Next, Current, T0)); // already published
        Assert.Equal(Decision.Skip, new UpdateGate().Evaluate(Current, Current, T0)); // not newer
    }

    [Fact]
    public void A_failed_check_never_blocks_launch()
    {
        var gate = Published(out _);
        Assert.True(gate.Required(false));

        gate.VerifyFailed(Next, T0);

        Assert.False(gate.Required(false));   // Play is not gated: the installed version launches
        Assert.Null(gate.Published);
        // the feed keeps saying "1.0.58": during the back-off that is ignored, so the gate stays open
        Assert.Equal(Decision.Wait, gate.Evaluate(Next, Current, T0.AddMinutes(1)));
        Assert.Equal(Decision.Wait, gate.Evaluate(Next, Current, T0.AddMinutes(29)));
        Assert.False(gate.Required(false));
    }

    [Fact]
    public void After_the_back_off_the_retry_is_quiet_and_only_a_verified_update_is_published()
    {
        var gate = Published(out _);
        gate.VerifyFailed(Next, T0);

        var at = T0.AddMinutes(31);
        Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, at));
        // while the quiet fetch runs, nothing is offered and Play is still free
        Assert.False(gate.Required(false));
        Assert.Equal(Decision.Wait, gate.Evaluate(Next, Current, at.AddSeconds(5)));

        gate.Verified(Next);          // the retry succeeded
        gate.Publish(Next);
        Assert.True(gate.Required(false));   // now, and only now, it can be forced
        Assert.Equal(Decision.Skip, gate.Evaluate(Next, Current, at.AddMinutes(1)));
    }

    [Fact]
    public void A_retry_that_fails_again_backs_off_longer_then_caps()
    {
        var gate = Published(out _);
        gate.VerifyFailed(Next, T0);                                  // 30 min
        var t = T0.AddMinutes(30);
        Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, t));
        gate.VerifyFailed(Next, t);                                   // second failure: 60 min
        Assert.Equal(Decision.Wait, gate.Evaluate(Next, Current, t.AddMinutes(59)));
        t = t.AddMinutes(60);
        Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, t));
        gate.VerifyFailed(Next, t);                                   // 120
        t = t.AddMinutes(120);
        Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, t));
        gate.VerifyFailed(Next, t);                                   // 240
        t = t.AddMinutes(240);
        Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, t));
        gate.VerifyFailed(Next, t);                                   // capped at 6 h, not 480
        Assert.Equal(Decision.Wait, gate.Evaluate(Next, Current, t.AddMinutes(359)));
        Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, t.AddMinutes(360)));
        Assert.False(gate.Required(false));
    }

    [Fact]
    public void A_newer_release_is_not_held_back_by_an_older_one_that_failed()
    {
        var gate = Published(out _);
        gate.VerifyFailed(Next, T0);
        Assert.Equal(Decision.Publish, gate.Evaluate(new Version(1, 0, 59), Current, T0.AddMinutes(1)));
    }

    [Fact]
    public void A_new_process_starts_with_no_back_off()
    {
        // the back-off is in memory only: the next start retries straight away
        Assert.Equal(Decision.Publish, new UpdateGate().Evaluate(Next, Current, T0));
    }

    [Fact]
    public void A_quiet_fetch_that_cannot_even_connect_just_extends_the_back_off()
    {
        var gate = Published(out _);
        gate.VerifyFailed(Next, T0);
        var t = T0.AddMinutes(31);
        Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, t));
        gate.VerifyFailed(Next, t); // UpdateService reports any quiet failure this way
        Assert.False(gate.Required(false));
        Assert.Equal(Decision.Wait, gate.Evaluate(Next, Current, t.AddMinutes(1)));
    }

    // The whole path against real bytes: a tampered download fails, the gate opens, the retry of the intact
    // download passes and is the first thing allowed to gate Play again.
    [Fact]
    public async Task Mismatch_then_a_good_retry_end_to_end()
    {
        var dir = Path.Combine(Path.GetTempPath(), "origin-gate-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var zip = Enumerable.Range(0, 100_000).Select(i => (byte)(i * 7 % 253)).ToArray();
            var sha = Convert.ToHexString(SHA256.HashData(zip)).ToLowerInvariant();
            var bad = (byte[])zip.Clone();
            bad[10] ^= 1;
            var dest = Path.Combine(dir, "z.part");

            var gate = Published(out _);
            var first = await UpdateVerifier.CopyAndVerifyAsync(new MemoryStream(bad), dest, bad.Length, sha, null, CancellationToken.None);
            Assert.Equal(UpdateVerdict.Mismatch, first);
            gate.VerifyFailed(Next, T0);
            Assert.False(gate.Required(false));

            var at = T0.AddMinutes(31);
            Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, at));
            var second = await UpdateVerifier.CopyAndVerifyAsync(new MemoryStream(zip), dest, zip.Length, sha, null, CancellationToken.None);
            Assert.Equal(UpdateVerdict.Ok, second);
            Assert.True(File.Exists(dest));
            gate.Verified(Next);
            gate.Publish(Next);
            Assert.True(gate.Required(false));
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch (IOException) { }
        }
    }

    // Mandate 4: no failure mode may block play for good (antivirus on the zip, disk full, extract errors, a
    // stalled connection, HTTP errors). The first plain failure keeps the retry badge; the second releases Play.
    [Fact]
    public void Two_plain_failures_in_a_row_release_play_with_the_quiet_back_off()
    {
        var gate = Published(out _);
        Assert.False(gate.DownloadFailed(Next, T0));          // first: still gated, "click to retry"
        Assert.True(gate.Required(false));
        Assert.True(gate.DownloadFailed(Next, T0.AddMinutes(1))); // second: released
        Assert.False(gate.Required(false));
        Assert.Null(gate.Published);
        Assert.Equal(Decision.Wait, gate.Evaluate(Next, Current, T0.AddMinutes(20)));
        Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, T0.AddMinutes(32)));
        gate.Verified(Next);
        gate.Publish(Next);
        Assert.True(gate.Required(false));                     // only a verified retry gates again
    }

    [Fact]
    public void A_verification_failure_resets_the_plain_failure_count()
    {
        var gate = Published(out _);
        Assert.False(gate.DownloadFailed(Next, T0));
        gate.VerifyFailed(Next, T0);                           // released at once, count cleared
        gate.Verified(Next);
        gate.Publish(Next);
        Assert.False(gate.DownloadFailed(Next, T0.AddHours(1))); // counts from zero again
        Assert.True(gate.Required(false));
    }

    [Fact]
    public void A_plain_offline_start_never_gates()
    {
        // no release info at all (the feed was unreachable): nothing published, nothing required
        var gate = new UpdateGate();
        Assert.False(gate.Required(false));
        Assert.Null(gate.Published);
    }

    [Fact]
    public void A_late_quiet_retry_of_an_older_release_cannot_overwrite_a_newer_offer()
    {
        var gate = Published(out _);
        gate.VerifyFailed(Next, T0);
        Assert.Equal(Decision.Quiet, gate.Evaluate(Next, Current, T0.AddMinutes(31)));
        var newer = new Version(1, 0, 59);
        Assert.Equal(Decision.Publish, gate.Evaluate(newer, Current, T0.AddMinutes(32)));
        gate.Publish(newer);

        gate.Verified(Next);                                   // X's quiet retry finishes after Y was offered
        Assert.False(gate.TryPublish(Next));
        Assert.Equal(newer, gate.Published);
        Assert.False(gate.TryPublish(newer));                  // not the same one twice either
        Assert.True(gate.TryPublish(new Version(1, 0, 60)));
    }

    [Fact]
    public void Versions_compare_with_padding()
    {
        var gate = new UpdateGate();
        Assert.Equal(Decision.Skip, gate.Evaluate(new Version(1, 0, 57), new Version(1, 0, 57, 0), T0));
    }
}
