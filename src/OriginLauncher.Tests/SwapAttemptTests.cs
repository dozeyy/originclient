using OriginLauncher.App.Core.Updates;
using Xunit;
using static OriginLauncher.App.Core.Updates.UpdateGate;

namespace OriginLauncher.Tests;

// A swap that fails after the launcher has quit (disk full during the copy, a sticky-bit folder) restarts the old
// launcher. Without a marker its fresh gate would offer the same update, gate Play, download, quit and fail again at
// every start, so Play would never unlock (mandate 4). Core/Updates/SwapAttempt + UpdateGate.SwapFailed.
public sealed class SwapAttemptTests : IDisposable
{
    private static readonly Version Old = new(1, 0, 75);
    private static readonly Version New = new(1, 0, 76);
    private static readonly DateTime T0 = new(2026, 11, 2, 12, 0, 0, DateTimeKind.Utc);

    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-swap-" + Guid.NewGuid().ToString("N"));
    private string Marker => Path.Combine(_dir, "updates", "swap-attempted.txt");

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { }
    }

    [Fact]
    public void A_start_still_on_the_old_version_after_a_swap_opens_play_and_retries_quietly()
    {
        SwapAttempt.Record(Marker, New); // the launcher quit to swap in New ...
        // ... and the swap failed, so the old launcher started again with a fresh gate.
        Assert.Equal(SwapAttempt.Outcome.Failed, SwapAttempt.Check(Marker, Old, out var attempted));
        Assert.Equal(Normalize(New), Normalize(attempted!));

        var gate = new UpdateGate();
        Assert.True(gate.SwapFailed(attempted!, T0));
        Assert.False(gate.Required(isDevBuild: false));
        // The feed still lists New: not put in front of the player again, only retried quietly after the back-off.
        Assert.Equal(Decision.Wait, gate.Evaluate(New, Old, T0.AddMinutes(1)));
        Assert.Null(gate.Published);
        Assert.Equal(Decision.Quiet, gate.Evaluate(New, Old, T0 + FirstRetry));
        // A newer release is a fresh one: offered as usual.
        Assert.Equal(Decision.Publish, gate.Evaluate(new Version(1, 0, 77), Old, T0));

        // The marker stays while the swap hasn't taken effect, so every later start opens Play too.
        Assert.Equal(SwapAttempt.Outcome.Failed, SwapAttempt.Check(Marker, Old, out _));
    }

    [Fact]
    public void Running_the_new_version_clears_the_marker()
    {
        SwapAttempt.Record(Marker, New);
        Assert.Equal(SwapAttempt.Outcome.Applied, SwapAttempt.Check(Marker, new Version(1, 0, 76, 0), out var attempted));
        Assert.Null(attempted);
        Assert.False(File.Exists(Marker));
        Assert.Equal(SwapAttempt.Outcome.None, SwapAttempt.Check(Marker, New, out _));
    }

    [Fact]
    public void No_marker_or_a_garbled_one_means_no_swap_was_attempted()
    {
        Assert.Equal(SwapAttempt.Outcome.None, SwapAttempt.Check(Marker, Old, out var attempted));
        Assert.Null(attempted);

        Directory.CreateDirectory(Path.GetDirectoryName(Marker)!);
        File.WriteAllText(Marker, "not a version");
        Assert.Equal(SwapAttempt.Outcome.None, SwapAttempt.Check(Marker, Old, out _));
        Assert.False(File.Exists(Marker));
    }
}
