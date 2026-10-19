using System.IO.Pipes;
using System.Text;
using OriginLauncher.App.Core.Import.Launcher;
using Xunit;

namespace OriginLauncher.Tests.Import;

// The launch/import safety wiring (Core/Import/Launcher): the two run states, the startup recovery wrapper, the
// close-while-importing rule, the second-launch pipe and the lazy backend builder. LauncherGlueTests has the
// stateless pieces. The run states are process-wide statics, so every test that touches them lives in THIS
// one class (xunit runs a class's tests one at a time) and leaves them idle.
public sealed class LauncherWiringTests
{
    // ---- LaunchRunState: a launch that is still starting blocks the import ----

    [Fact]
    public void A_starting_launch_blocks_the_import_with_the_plain_sentence()
    {
        Assert.False(LaunchRunState.IsStarting);
        Assert.Null(LaunchRunState.ImportRefusal());
        using (LaunchRunState.Enter())
        {
            Assert.True(LaunchRunState.IsStarting);
            Assert.Equal("Wait for Minecraft to finish starting, then try again.", LaunchRunState.ImportRefusal());
        }
        Assert.False(LaunchRunState.IsStarting);
        Assert.Null(LaunchRunState.ImportRefusal());
    }

    [Fact]
    public void Launch_state_is_released_on_every_exit_path_including_an_exception()
    {
        var changes = 0;
        void Count() => changes++;
        LaunchRunState.Changed += Count;
        try
        {
            Assert.Throws<InvalidOperationException>(new Action(() =>
            {
                using var run = LaunchRunState.Enter();
                throw new InvalidOperationException("launch failed");
            }));
            Assert.False(LaunchRunState.IsStarting);
            Assert.Equal(2, changes); // busy, then idle
        }
        finally { LaunchRunState.Changed -= Count; }
    }

    [Fact]
    public void Launch_state_counts_launches_and_a_double_dispose_releases_only_once()
    {
        var first = LaunchRunState.Enter();
        var second = LaunchRunState.Enter();
        first.Dispose();
        first.Dispose(); // idempotent: must not release the second launch's hold
        Assert.True(LaunchRunState.IsStarting);
        second.Dispose();
        Assert.False(LaunchRunState.IsStarting);
    }

    [Fact]
    public void Launch_state_never_looks_at_the_import_state_because_the_panel_marks_the_import_running_first()
    {
        using (ImportRunState.Enter())
            Assert.Null(LaunchRunState.ImportRefusal());
    }

    // ---- ImportRunState: robust holders ----

    [Fact]
    public void Import_state_Enter_releases_when_an_exception_skips_the_end()
    {
        Assert.Throws<InvalidOperationException>(new Action(() =>
        {
            using var run = ImportRunState.Enter();
            Assert.True(ImportRunState.IsApplying);
            throw new InvalidOperationException("before the try");
        }));
        Assert.False(ImportRunState.IsApplying);
    }

    [Fact]
    public void Import_state_overlapping_holders_do_not_clear_each_other()
    {
        var recovery = ImportRunState.Enter();
        var import = ImportRunState.Enter();
        recovery.Dispose();
        Assert.True(ImportRunState.IsApplying);
        import.Dispose();
        Assert.False(ImportRunState.IsApplying);
    }

    [Fact]
    public void Import_state_legacy_Begin_End_work_and_an_extra_End_never_goes_below_idle()
    {
        var changes = 0;
        void Count() => changes++;
        ImportRunState.Changed += Count;
        try
        {
            ImportRunState.Begin();
            Assert.True(ImportRunState.IsApplying);
            ImportRunState.End();
            ImportRunState.End(); // stray
            Assert.False(ImportRunState.IsApplying);
            ImportRunState.Begin();
            Assert.True(ImportRunState.IsApplying); // the stray End did not leave a negative count behind
            ImportRunState.End();
            Assert.Equal(4, changes);
        }
        finally { ImportRunState.Changed -= Count; }
    }

    // ---- the startup recovery: busy around it, awaited before planning ----

    [Fact]
    public async Task Recovery_marks_an_import_running_from_the_call_until_it_ends()
    {
        var gate = new ManualResetEventSlim();
        var insideWasBusy = false;
        var task = ImportRecoveryRunner.Run(() =>
        {
            insideWasBusy = ImportRunState.IsApplying;
            gate.Wait(TimeSpan.FromSeconds(10));
            return 2;
        }, _ => { });

        Assert.True(ImportRunState.IsApplying); // already busy before the pool thread even ran: Play can never be enabled in between
        gate.Set();
        Assert.Equal(2, await task);
        Assert.True(insideWasBusy);
        Assert.False(ImportRunState.IsApplying);
    }

    [Fact]
    public async Task Recovery_that_throws_returns_zero_logs_and_still_releases_the_state()
    {
        var log = new List<string>();
        var rolledBack = await ImportRecoveryRunner.Run(() => throw new IOException("disk gone"), log.Add);
        Assert.Equal(0, rolledBack);
        Assert.False(ImportRunState.IsApplying);
        Assert.Contains(log, l => l == "import recovery failed: IOException");
        Assert.DoesNotContain(log, l => l.Contains("disk gone")); // the type name only
    }

    [Fact]
    public async Task An_import_entry_waits_for_the_recovery_before_it_plans()
    {
        var recovery = new TaskCompletionSource();
        var planned = false;
        var entry = ImportRecoveryRunner.AfterRecoveryAsync(recovery.Task, () => { planned = true; return Task.FromResult(7); });

        await Task.Delay(100);
        Assert.False(planned); // recovery still running: nothing planned yet
        recovery.SetResult();
        Assert.Equal(7, await entry);
        Assert.True(planned);
    }

    [Fact]
    public async Task A_failed_recovery_does_not_block_the_import_forever()
    {
        var recovery = Task.FromException(new InvalidOperationException("x"));
        Assert.Equal(1, await ImportRecoveryRunner.AfterRecoveryAsync(recovery, () => Task.FromResult(1)));
    }

    // ---- closing the window while an import runs ----

    [Fact]
    public void Closing_during_an_import_is_held_and_goes_through_once_when_the_result_is_back()
    {
        var guard = new ImportCloseGuard();
        Assert.True(guard.ShouldHoldClose(importApplying: true));
        Assert.True(guard.CloseRequested);
        Assert.False(guard.ShouldCloseNow(importApplying: true)); // still writing
        Assert.True(guard.ShouldCloseNow(importApplying: false)); // result is back: close now
        Assert.False(guard.ShouldCloseNow(importApplying: false)); // only once
    }

    [Fact]
    public void Closing_with_no_import_running_is_never_held_and_forgets_an_earlier_request()
    {
        var guard = new ImportCloseGuard();
        Assert.False(guard.ShouldHoldClose(false));
        Assert.False(guard.ShouldCloseNow(false));

        Assert.True(guard.ShouldHoldClose(true));
        Assert.False(guard.ShouldHoldClose(false)); // the player closes again after it ended
        Assert.False(guard.CloseRequested);
        Assert.False(guard.ShouldCloseNow(false));
    }

    // ---- --import handed to a running launcher ----

    [Theory]
    [InlineData("import", true)]
    [InlineData("import\n", true)]
    [InlineData("import\r\n", true)]
    [InlineData("IMPORT", false)]
    [InlineData("Import", false)]
    [InlineData(" import", false)]
    [InlineData("import ", false)]
    [InlineData("import\nimport", false)]
    [InlineData("import;calc", false)]
    [InlineData("--import", false)]
    [InlineData("", false)]
    [InlineData(null, false)]
    public void Only_the_exact_word_import_is_a_command(string? line, bool expected) =>
        Assert.Equal(expected, ImportIpc.IsImportCommand(line));

    [Fact]
    public void The_pipe_name_is_stable_per_user_and_flavour_and_hides_its_inputs()
    {
        var a = ImportIpc.PipeName(@"Local\OriginLauncher.SingleInstance", "will");
        Assert.Equal(a, ImportIpc.PipeName(@"Local\OriginLauncher.SingleInstance", "will"));
        Assert.NotEqual(a, ImportIpc.PipeName(@"Local\OriginLauncher.SingleInstance", "someone"));
        Assert.NotEqual(a, ImportIpc.PipeName(@"Local\OriginLauncher.SingleInstance.Debug.ABC", "will"));
        Assert.StartsWith("OriginLauncher.Import.", a);
        Assert.DoesNotContain("will", a, StringComparison.OrdinalIgnoreCase);
    }

    private static string UniquePipe() => "OriginLauncherTest." + Guid.NewGuid().ToString("N");

    [Fact]
    public async Task The_running_launcher_receives_import_and_ignores_anything_else()
    {
        var name = UniquePipe();
        var received = new SemaphoreSlim(0);
        var count = 0;
        using var server = new ImportIpcServer(name, () => { Interlocked.Increment(ref count); received.Release(); });
        server.Start();

        // Something else on the pipe: wrong word, then an overlong line with no newline.
        await SendRawAsync(name, "evil\n");
        await SendRawAsync(name, new string('x', 200));
        await SendRawAsync(name, "IMPORT\n");
        Assert.False(await received.WaitAsync(TimeSpan.FromMilliseconds(400)));
        Assert.Equal(0, Volatile.Read(ref count));

        // The real thing still gets through afterwards.
        Assert.True(ImportIpc.TrySend(name, 5000));
        Assert.True(await received.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Equal(1, Volatile.Read(ref count));
    }

    [Fact]
    public void Sending_to_nobody_fails_quickly_and_quietly()
    {
        var watch = System.Diagnostics.Stopwatch.StartNew();
        Assert.False(ImportIpc.TrySend(UniquePipe(), 300));
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(5));
    }

    // Writes raw bytes the way a stranger on the pipe would. A server that hangs up on rubbish breaks the pipe
    // under the writer: that is the rejection, not a test failure. Only "could not connect" is retried.
    private static async Task SendRawAsync(string pipe, string text)
    {
        for (var attempt = 0; attempt < 20; attempt++)
        {
            try
            {
                using var client = new NamedPipeClientStream(".", pipe, PipeDirection.Out);
                await client.ConnectAsync(2000);
                var bytes = Encoding.ASCII.GetBytes(text);
                await client.WriteAsync(bytes);
                await client.FlushAsync();
                return;
            }
            catch (IOException)
            {
                return; // the server closed on us
            }
            catch (TimeoutException) when (attempt < 19)
            {
                await Task.Delay(50); // the server is between two clients
            }
        }
    }

    // ---- the backend builds nothing until it is used ----

    [Fact]
    public async Task The_deferred_builder_does_no_work_until_first_use_and_then_on_the_thread_pool_once()
    {
        var builds = 0;
        var buildThreadIsPool = false;
        var deferred = new Deferred<object>(() =>
        {
            Interlocked.Increment(ref builds);
            buildThreadIsPool = Thread.CurrentThread.IsThreadPoolThread;
            return new object();
        });

        Assert.False(deferred.IsCreated);
        Assert.Equal(0, builds); // constructing it (what `new LauncherImportBackend()` does) built nothing

        var first = await deferred.GetAsync();
        var second = await deferred.GetAsync();
        Assert.Same(first, second);
        Assert.Equal(1, builds);
        Assert.True(buildThreadIsPool);
        Assert.True(deferred.IsCreated);
    }

    [Fact]
    public async Task A_failed_build_is_not_remembered()
    {
        var attempts = 0;
        var deferred = new Deferred<string>(() =>
        {
            if (Interlocked.Increment(ref attempts) == 1) throw new IOException("transient");
            return "ok";
        });
        await Assert.ThrowsAsync<IOException>(() => deferred.GetAsync());
        Assert.False(deferred.IsCreated);
        Assert.Equal("ok", await deferred.GetAsync());
    }
}
