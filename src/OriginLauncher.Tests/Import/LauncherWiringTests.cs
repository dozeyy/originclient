using System.IO.Pipes;
using System.Text;
using OriginLauncher.App.Core.Import.Launcher;
using Xunit;

namespace OriginLauncher.Tests.Import;

// The launch/import safety wiring (Core/Import/Launcher): the two run states, the startup recovery wrapper, the
// close-while-importing rule, the second-launch pipe and the lazy backend builder. LauncherGlueTests has the
// stateless pieces. The run states are process-wide statics, so every test that touches them lives in THIS
// one class, in a collection that never runs beside another (RunStateCollection), and every test starts and ends
// with all three reset: one failure can't leave a holder or a listener behind and cascade into the next test.
[Collection(RunStateCollection.Name)]
public sealed class LauncherWiringTests : IDisposable
{
    public LauncherWiringTests() => ResetAll();

    public void Dispose() => ResetAll();

    private static void ResetAll()
    {
        ImportRunState.ResetForTests();
        LaunchRunState.ResetForTests();
        UpdateRunState.ResetForTests();
    }

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
        Assert.Equal(CloseHold.ImportRunning, guard.OnClosing(importRunning: true, panelImporting: true));
        Assert.True(guard.CloseRequested);
        Assert.False(guard.ShouldCloseNow(importRunning: true)); // still writing
        Assert.True(guard.ShouldCloseNow(importRunning: false)); // result is back: close now
        Assert.False(guard.ShouldCloseNow(importRunning: false)); // only once
    }

    [Fact]
    public void Closing_with_no_import_running_is_never_held_and_forgets_an_earlier_request()
    {
        var guard = new ImportCloseGuard();
        Assert.Equal(CloseHold.None, guard.OnClosing(false, false));
        Assert.False(guard.ShouldCloseNow(false));

        Assert.Equal(CloseHold.ImportRunning, guard.OnClosing(true, true));
        Assert.Equal(CloseHold.None, guard.OnClosing(false, false)); // the player closes again after it ended
        Assert.False(guard.CloseRequested);
        Assert.False(guard.ShouldCloseNow(false));
    }

    [Fact]
    public void Answering_keep_importing_forgets_the_held_close_so_the_window_stays_open_when_the_import_ends()
    {
        var guard = new ImportCloseGuard();
        guard.OnClosing(true, true);
        guard.OnConfirmAnswered(cancelImport: false); // "Keep importing"
        Assert.False(guard.CloseRequested);
        Assert.False(guard.ShouldCloseNow(importRunning: false)); // the import finished: NOT closed
    }

    [Fact]
    public void Answering_cancel_keeps_the_held_close_so_the_window_closes_when_the_rollback_is_done()
    {
        var guard = new ImportCloseGuard();
        guard.OnClosing(true, true);
        guard.OnConfirmAnswered(cancelImport: true);
        Assert.False(guard.ShouldCloseNow(importRunning: true));
        Assert.True(guard.ShouldCloseNow(importRunning: false));
    }

    [Fact]
    public void Closing_during_the_startup_recovery_is_held_with_its_own_reason_so_the_player_is_told()
    {
        var guard = new ImportCloseGuard();
        // Import state is busy (recovery) but no panel is applying: nothing to ask, the toast explains the wait.
        Assert.Equal(CloseHold.RecoveryRunning, guard.OnClosing(importRunning: true, panelImporting: false));
        Assert.True(guard.ShouldCloseNow(importRunning: false)); // and it closes by itself when recovery ends
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

    private const string Sid = "S-1-5-21-1111111111-2222222222-3333333333-1001";
    private const string Flavour = @"Local\OriginLauncher.SingleInstance";

    [Fact]
    public void The_pipe_name_comes_from_the_sid_and_session_not_the_account_name()
    {
        var a = ImportIpc.PipeName(Flavour, Sid, 1);
        Assert.Equal(a, ImportIpc.PipeName(Flavour, Sid, 1));
        Assert.NotEqual(a, ImportIpc.PipeName(Flavour, "S-1-5-21-1111111111-2222222222-3333333333-1002", 1)); // another user
        Assert.NotEqual(a, ImportIpc.PipeName(Flavour, Sid, 2)); // another logon session
        Assert.NotEqual(a, ImportIpc.PipeName(Flavour + ".Debug.ABC", Sid, 1)); // a developer build
        Assert.StartsWith("OriginLauncher.Import.", a);
        Assert.DoesNotContain("1001", a);
        Assert.DoesNotContain("S-1-5", a);
    }

    [Fact]
    public void The_pipe_name_of_a_domain_account_and_a_local_account_called_the_same_do_not_collide()
    {
        // Both would be "will" by name; their SIDs differ.
        Assert.NotEqual(
            ImportIpc.PipeName(Flavour, "S-1-5-21-1-2-3-1001", 1),
            ImportIpc.PipeName(Flavour, "S-1-5-21-9-8-7-1001", 1));
    }

    [Fact]
    public void Listening_retries_back_off_from_250ms_to_a_30s_cap()
    {
        Assert.Equal(TimeSpan.FromMilliseconds(250), ImportIpc.RetryDelay(1));
        Assert.Equal(TimeSpan.FromMilliseconds(500), ImportIpc.RetryDelay(2));
        Assert.Equal(TimeSpan.FromMilliseconds(1000), ImportIpc.RetryDelay(3));
        Assert.Equal(TimeSpan.FromSeconds(30), ImportIpc.RetryDelay(20));
        Assert.Equal(TimeSpan.FromSeconds(30), ImportIpc.RetryDelay(1000));
        Assert.Equal(TimeSpan.FromMilliseconds(250), ImportIpc.RetryDelay(0));
        for (var i = 2; i < 40; i++) Assert.True(ImportIpc.RetryDelay(i) >= ImportIpc.RetryDelay(i - 1));
    }

    [Fact]
    public async Task A_pipe_name_someone_else_holds_logs_once_and_backs_off_instead_of_spinning()
    {
        var name = UniquePipe();
        // Another holder of the name (stands in for another account squatting on it).
        using var squatter = new NamedPipeServerStream(name, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
        var log = new List<string>();
        using var server = new ImportIpcServer(name, () => { }, line => { lock (log) log.Add(line); });
        server.Start();

        // Wait for the FIRST failure instead of assuming it lands within a fixed time: on a slow, busy run the server's
        // pool thread can start late, and a fixed 1.8 s sleep then saw no line at all (a flaky miss, not a bug).
        var first = System.Diagnostics.Stopwatch.StartNew();
        while (first.Elapsed < TimeSpan.FromSeconds(15))
        {
            lock (log) if (log.Count > 0) break;
            await Task.Delay(25);
        }
        // Then give it time for several more back-off attempts (0.25 + 0.5 + 1 s): they must stay silent.
        await Task.Delay(1900);
        lock (log)
        {
            Assert.Single(log);
            Assert.Contains("could not listen", log[0]);
        }
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

    // ---- an update downloading / restarting vs an import ----

    [Fact]
    public void An_update_in_flight_blocks_the_import_with_its_own_sentence_until_it_ends()
    {
        Assert.Null(LaunchRunState.ImportRefusal());
        var update = UpdateRunState.Enter();
        Assert.True(UpdateRunState.IsRunning);
        Assert.Equal(UpdateRunState.Refusal, LaunchRunState.ImportRefusal());
        Assert.Contains("update", UpdateRunState.Refusal);
        update.Dispose();
        update.Dispose();
        Assert.False(UpdateRunState.IsRunning);
        Assert.Null(LaunchRunState.ImportRefusal());
    }

    [Fact]
    public void A_starting_launch_still_wins_the_message_when_both_apply()
    {
        using var update = UpdateRunState.Enter();
        using var launch = LaunchRunState.Enter();
        Assert.Equal(LaunchRunState.Refusal, LaunchRunState.ImportRefusal());
    }

    [Fact]
    public async Task The_update_restart_waits_for_a_running_import_and_says_so_once()
    {
        var told = 0;
        var import = ImportRunState.Enter();
        var restart = UpdateRunState.PrepareToRestartAsync(() => told++);
        await Task.Delay(100);
        Assert.False(restart.IsCompleted); // Shutdown must not happen yet
        Assert.Equal(1, told);

        import.Dispose(); // the import finished
        await restart.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(1, told);
    }

    [Fact]
    public async Task The_update_restart_does_not_wait_when_no_import_runs()
    {
        var told = 0;
        await UpdateRunState.PrepareToRestartAsync(() => told++);
        Assert.Equal(0, told);
    }

    [Fact]
    public async Task Waiting_for_idle_can_be_abandoned_and_leaves_the_import_state_alone()
    {
        using var import = ImportRunState.Enter();
        using var cts = new CancellationTokenSource();
        var wait = ImportRunState.WhenIdleAsync(cts.Token);
        cts.Cancel();
        await Assert.ThrowsAsync<TaskCanceledException>(() => wait);
        Assert.True(ImportRunState.IsApplying);
    }

    [Fact]
    public async Task Waiting_for_idle_completes_at_once_when_nothing_runs()
    {
        await ImportRunState.WhenIdleAsync().WaitAsync(TimeSpan.FromSeconds(1));
    }
}

/// <summary>The process-wide run states (ImportRunState, LaunchRunState, UpdateRunState): tests that touch them never run in parallel with anything.</summary>
[CollectionDefinition(Name, DisableParallelization = true)]
public sealed class RunStateCollection
{
    public const string Name = "Process-wide run states";
}
