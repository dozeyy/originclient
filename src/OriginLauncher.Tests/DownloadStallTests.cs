using System.Collections.Concurrent;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using CmlLib.Core.Files;
using CmlLib.Core.Installers;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Loaders;
using OriginLauncher.App.Core.Net;
using OriginLauncher.App.Core.Runtime;
using OriginLauncher.App.Core.Versions;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// "1.8.9 takes so long ... at 967 files the last one won't go, then I rebooted it and it worked" (Will, 2026-10-27).
///
/// A real HTTP server on 127.0.0.1 (raw sockets, so the launcher's real SocketsHttpHandler path is exercised) whose
/// chosen file sends its headers and half its body, then goes quiet with the connection left open: exactly a
/// connection that stalled mid-file. The install must either finish (retry) or fail with a clear reason, never sit.
/// </summary>
public sealed class DownloadStallTests : IDisposable
{
    // Short enough to keep the suite fast, long enough that a loaded CI runner serving a healthy file never trips it.
    private static readonly TimeSpan Stall = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan Headers = TimeSpan.FromSeconds(2);
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-stall-" + Guid.NewGuid().ToString("N"));

    public DownloadStallTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { }
    }

    private static HttpClient GuardedClient() =>
        DownloadHttp.Create(new SocketsHttpHandler(), null, Stall, Headers);

    private List<GameFile> Files(StallServer server, int count) =>
        Enumerable.Range(0, count).Select(i => new GameFile($"f{i}")
        {
            Url = server.Url($"f{i}"),
            Path = Path.Combine(_dir, $"f{i}.bin"),
            Size = StallServer.Body($"f{i}").Length,
        }).ToList();

    // The install with progress, bounded by a watchdog: a hang returns null instead of hanging the test run.
    private static async Task<(Task install, InstallerProgressChangedEventArgs? last)> RunAsync(
        IGameInstaller installer, IEnumerable<GameFile> files, TimeSpan watchdog)
    {
        InstallerProgressChangedEventArgs? last = null;
        // Highest count seen: parallel workers may report 8 after 9.
        var progress = new SyncProgress<InstallerProgressChangedEventArgs>(e =>
        {
            if (last == null || e.ProgressedTasks >= last.ProgressedTasks) last = e;
        });
        var install = installer.Install(files, progress, null, CancellationToken.None).AsTask();
        await Task.WhenAny(install, Task.Delay(watchdog));
        return (install, last);
    }

    [Fact]
    public async Task Plain_client_hangs_forever_on_a_stalled_last_file()
    {
        // The cause, pinned: CmlLib's download with an ordinary HttpClient (what the launcher used) never returns
        // when a body stalls, so the counter sits one file short. HttpClient.Timeout does not save it (it stops at
        // the headers on a streamed download): 1 s here, still hung at 4 s. If CmlLib ever fixes this, this says so.
        using var server = new StallServer(stallFirst: new[] { "f9" });
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(1) };
        var installer = new ParallelGameInstaller(2, 8, 2048, http);

        var (install, last) = await RunAsync(installer, Files(server, 10), TimeSpan.FromSeconds(4));

        Assert.False(install.IsCompleted);
        Assert.NotNull(last);
        Assert.Equal(10, last!.TotalTasks);
        Assert.Equal(9, last.ProgressedTasks); // frozen at 9/10
    }

    [Fact]
    public async Task Stalled_last_file_is_retried_and_the_counter_finishes()
    {
        using var server = new StallServer(stallFirst: new[] { "f19" });
        using var http = GuardedClient();
        var installer = new RetryingGameInstaller(2, 8, 2048, http, _ => TimeSpan.Zero);

        var (install, last) = await RunAsync(installer, Files(server, 20), TimeSpan.FromSeconds(20));

        Assert.True(install.IsCompleted, "install still running: the stalled file froze the counter");
        await install; // no exception
        Assert.Equal(20, last!.TotalTasks);
        Assert.Equal(20, last.ProgressedTasks);
        Assert.Equal(2, server.Requests("f19"));
        for (var i = 0; i < 20; i++)
            Assert.Equal(StallServer.Body($"f{i}"), File.ReadAllBytes(Path.Combine(_dir, $"f{i}.bin")));
    }

    [Fact]
    public async Task A_file_that_always_stalls_fails_with_a_clear_reason_after_bounded_retries()
    {
        using var server = new StallServer(stallAlways: new[] { "f4" });
        using var http = GuardedClient();
        var installer = new RetryingGameInstaller(2, 8, 2048, http, _ => TimeSpan.Zero);

        var (install, _) = await RunAsync(installer, Files(server, 5), TimeSpan.FromSeconds(20));
        Assert.False(File.Exists(Path.Combine(_dir, "f4.bin"))); // the last attempt's partial file is not left behind

        Assert.True(install.IsCompleted, "install still running instead of failing");
        var error = await Assert.ThrowsAnyAsync<Exception>(() => install);
        Assert.Equal(RetryingGameInstaller.Attempts, server.Requests("f4"));
        // What the player reads, with the Retry line.
        var failure = PlayFailure.From(error, "1.8.9");
        Assert.Contains("A download took too long.", failure.Message);
        Assert.Contains("Retry", failure.Message);
    }

    [Fact]
    public async Task Pinned_downloads_give_up_on_a_stalled_body_instead_of_waiting()
    {
        // Forge's perf stack, OptiFine and the rest stream through the same client: a stall is a failure
        // (next mirror / non-fatal), not a frozen "Downloading..." line.
        using var server = new StallServer(stallAlways: new[] { "mod" });
        using var http = GuardedClient();
        var dest = Path.Combine(_dir, "mod.jar");

        var run = PinnedDownload.TryDownloadAsync(http, server.Url("mod"), dest, null, CancellationToken.None);
        await Task.WhenAny(run, Task.Delay(TimeSpan.FromSeconds(10)));

        Assert.True(run.IsCompleted);
        Assert.Equal(PinnedDownloadResult.Failed, await run);
        Assert.False(File.Exists(dest));
        Assert.False(File.Exists(dest + ".download"));
    }

    [Fact]
    public async Task A_host_that_never_answers_times_out_on_the_header_deadline()
    {
        using var server = new StallServer(silent: new[] { "dead" });
        using var http = GuardedClient();

        var run = http.GetAsync(server.Url("dead"), HttpCompletionOption.ResponseHeadersRead);
        await Task.WhenAny(run, Task.Delay(TimeSpan.FromSeconds(10)));

        Assert.True(run.IsCompleted);
        // HttpClient's own timeout shape (TaskCanceledException), so every existing network-failure filter catches it.
        var error = await Assert.ThrowsAnyAsync<TaskCanceledException>(() => run);
        Assert.True(Chain(error).Any(e => e is DownloadStalledException), error.ToString());
    }

    private static IEnumerable<Exception> Chain(Exception? e)
    {
        for (; e != null; e = e.InnerException) yield return e;
    }

    // --- The fail-soft paths must treat a stall like any other network failure (code review of 613e57afe) ---

    public enum StallKind { Body, Headers }

    /// <summary>A guarded client whose every request stalls: in the body after 200 OK, or before any headers.</summary>
    private static HttpClient StallingClient(StallKind kind) =>
        DownloadHttp.Create(new StallHandler(kind), null, Stall, Headers);

    [Theory]
    [InlineData(StallKind.Body)]
    [InlineData(StallKind.Headers)]
    public async Task Temurin_stall_falls_back_to_the_existing_java_instead_of_failing_the_launch(StallKind kind)
    {
        using var http = StallingClient(kind);
        var pin = new JavaRuntimePin("temurin-stall", "https://example.invalid/jdk-" + Guid.NewGuid().ToString("N"),
            new string('0', 64), 1);
        JavaDownload download = (url, dest, frac, token) => JavaRuntimeProvisioner.HttpDownload(http, url, dest, frac, token);

        var run = JavaRuntimeProvisioner.EnsureAsync(pin, _dir, download, null, CancellationToken.None);
        await Task.WhenAny(run, Task.Delay(TimeSpan.FromSeconds(10)));

        Assert.True(run.IsCompleted, "Temurin still waiting on a stalled download");
        Assert.Null(await run); // null = keep jre-legacy (Mandate 4), not an exception that fails the launch
        Assert.Empty(Directory.EnumerateDirectories(_dir, ".tmp-*"));
    }

    [Theory]
    [InlineData(StallKind.Body)]
    [InlineData(StallKind.Headers)]
    public async Task Fabric_api_live_query_stall_skips_the_mod_instead_of_failing_the_launch(StallKind kind)
    {
        // The unpinned path (no ManagedPins entry): the live Modrinth query is what stalls.
        using var http = StallingClient(kind);
        var mods = Path.Combine(_dir, "mods");

        var run = FabricApiInstaller.InstallProjectAsync(http, "fabric-api", "fabric-api-", "[\"fabric\"]",
            "Installing Fabric API...", pin: null, "0.0-stall-test", mods, null, CancellationToken.None);
        await Task.WhenAny(run, Task.Delay(TimeSpan.FromSeconds(10)));

        Assert.True(run.IsCompleted, "Fabric API install still waiting on a stalled query");
        await run; // no exception: the instance is left without the jar, the launch goes on
        Assert.Empty(Directory.EnumerateFiles(mods));
    }

    [Fact]
    public async Task The_players_own_cancel_is_still_a_cancel_not_a_stall()
    {
        using var http = StallingClient(StallKind.Body);
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(200));
        var error = await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => JavaRuntimeProvisioner.HttpDownload(http, "https://example.invalid/x", Path.Combine(_dir, "x"), null, cts.Token));
        Assert.IsNotType<DownloadStalledException>(error);
    }

    private sealed class StallHandler : HttpMessageHandler
    {
        private readonly StallKind _kind;
        public StallHandler(StallKind kind) => _kind = kind;

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            if (_kind == StallKind.Headers) await Task.Delay(Timeout.Infinite, ct);
            var content = new StreamContent(new NeverStream());
            content.Headers.ContentLength = 1_000_000;
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = content, RequestMessage = request };
        }
    }

    /// <summary>A body that never yields a byte until its read is cancelled.</summary>
    private sealed class NeverStream : Stream
    {
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
        {
            await Task.Delay(Timeout.Infinite, ct);
            return 0;
        }
        public override Task<int> ReadAsync(byte[] b, int o, int c, CancellationToken ct) => ReadAsync(b.AsMemory(o, c), ct).AsTask();
        public override int Read(byte[] b, int o, int c) => throw new NotSupportedException();
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => 0; set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
        public override void SetLength(long v) => throw new NotSupportedException();
        public override void Write(byte[] b, int o, int c) => throw new NotSupportedException();
    }

    [Fact]
    public async Task Guarded_body_reads_in_full_when_nothing_stalls()
    {
        using var server = new StallServer();
        using var http = GuardedClient();
        using var response = await http.GetAsync(server.Url("ok"));
        Assert.Equal(StallServer.Body("ok").Length, response.Content.Headers.ContentLength);
        Assert.Equal(StallServer.Body("ok"), await response.Content.ReadAsByteArrayAsync());
    }

    /// <summary>Progress&lt;T&gt; posts to the thread pool, so the "last" event could arrive late; this reports inline.</summary>
    private sealed class SyncProgress<T> : IProgress<T>
    {
        private readonly Action<T> _on;
        private readonly object _gate = new();
        public SyncProgress(Action<T> on) => _on = on;
        public void Report(T value) { lock (_gate) _on(value); }
    }

    /// <summary>
    /// Minimal HTTP/1.1 file server. stallFirst: the first request for that path sends headers + half the body and
    /// then nothing (socket kept open); stallAlways: every request does; silent: never answers at all.
    /// </summary>
    private sealed class StallServer : IDisposable
    {
        private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
        private readonly HashSet<string> _stallFirst, _stallAlways, _silent;
        private readonly ConcurrentDictionary<string, int> _hits = new();
        private readonly ConcurrentBag<TcpClient> _open = new();
        private readonly CancellationTokenSource _stop = new();

        public StallServer(string[]? stallFirst = null, string[]? stallAlways = null, string[]? silent = null)
        {
            _stallFirst = new(stallFirst ?? Array.Empty<string>());
            _stallAlways = new(stallAlways ?? Array.Empty<string>());
            _silent = new(silent ?? Array.Empty<string>());
            _listener.Start();
            _ = AcceptLoop();
        }

        public string Url(string name) => $"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}/{name}";
        public int Requests(string name) => _hits.TryGetValue(name, out var n) ? n : 0;

        // 64 KB + a little, so "half the body" is a real partial read.
        public static byte[] Body(string name) =>
            Enumerable.Range(0, 70_000).Select(i => (byte)((i * 31 + name.Length * 7 + name[^1]) & 0xFF)).ToArray();

        private async Task AcceptLoop()
        {
            while (!_stop.IsCancellationRequested)
            {
                TcpClient client;
                try { client = await _listener.AcceptTcpClientAsync(_stop.Token); }
                catch { return; }
                _open.Add(client);
                _ = Serve(client);
            }
        }

        private async Task Serve(TcpClient client)
        {
            try
            {
                var stream = client.GetStream();
                while (!_stop.IsCancellationRequested)
                {
                    var request = await ReadHeaders(stream);
                    if (request == null) return;
                    var name = request.Split(' ')[1].TrimStart('/');
                    var hit = _hits.AddOrUpdate(name, 1, (_, n) => n + 1);
                    if (_silent.Contains(name)) { await Task.Delay(Timeout.Infinite, _stop.Token); return; }

                    var body = Body(name);
                    var head = Encoding.ASCII.GetBytes(
                        $"HTTP/1.1 200 OK\r\nContent-Length: {body.Length}\r\nContent-Type: application/octet-stream\r\n\r\n");
                    await stream.WriteAsync(head);
                    if (_stallAlways.Contains(name) || (_stallFirst.Contains(name) && hit == 1))
                    {
                        await stream.WriteAsync(body.AsMemory(0, body.Length / 2));
                        await stream.FlushAsync();
                        await Task.Delay(Timeout.Infinite, _stop.Token); // the stall: open, quiet, forever
                        return;
                    }
                    await stream.WriteAsync(body);
                    await stream.FlushAsync();
                }
            }
            catch { /* client went away or server stopping */ }
        }

        private static async Task<string?> ReadHeaders(NetworkStream stream)
        {
            var sb = new StringBuilder();
            var one = new byte[1];
            while (!sb.ToString().EndsWith("\r\n\r\n", StringComparison.Ordinal))
            {
                if (await stream.ReadAsync(one) == 0) return null;
                sb.Append((char)one[0]);
            }
            return sb.ToString();
        }

        public void Dispose()
        {
            _stop.Cancel();
            _listener.Stop();
            foreach (var c in _open) c.Dispose();
        }
    }
}
