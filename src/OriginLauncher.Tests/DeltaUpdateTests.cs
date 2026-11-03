using System.Collections.Concurrent;
using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using OriginLauncher.App.Core.Updates;
using Xunit;
using Xunit.Abstractions;

namespace OriginLauncher.Tests;

// Changed-files-only launcher updates (Core/Updates/DeltaManifest + DeltaStager). The fixture zip and its file list
// come from tools/release/zip_manifest.py (tools/release/test_zip_manifest.py keeps them in step), so the launcher is
// tested against exactly what the release pipeline writes. Downloads go through a real loopback HTTP server that
// honours Range (behind a redirect, like GitHub's release downloads) or misbehaves on purpose. Every stage built here
// is compared byte for byte with a plain unzip of the same zip.
public sealed class DeltaUpdateTests : IDisposable
{
    private const string ZipName = "OriginLauncher-win-x64.zip";
    private static readonly string FixtureZip = Path.Combine(AppContext.BaseDirectory, "Fixtures", "delta-release.zip");
    private static readonly string FixtureList = Path.Combine(AppContext.BaseDirectory, "Fixtures", "delta-release.files.json");
    private static readonly TimeSpan Stall = TimeSpan.FromSeconds(10);

    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-delta-" + Guid.NewGuid().ToString("N"));
    private readonly ITestOutputHelper _out;

    public DeltaUpdateTests(ITestOutputHelper output)
    {
        _out = output;
        Directory.CreateDirectory(_dir);
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    private static string Sha(byte[] b) => Convert.ToHexString(SHA256.HashData(b)).ToLowerInvariant();
    private static string FileSha(string path) => Sha(File.ReadAllBytes(path));
    private static byte[] ListBytes => File.ReadAllBytes(FixtureList);
    private static DeltaManifest Fixture() => DeltaManifest.Parse(ListBytes, ZipName, FileSha(FixtureZip));

    private string Unzipped(string zip, string name)
    {
        var dir = Path.Combine(_dir, name);
        ZipFile.ExtractToDirectory(zip, dir);
        return dir;
    }

    // An install of the previous release: the same files with the exe changed (same size, other bytes).
    private string OlderInstall(string zip, Action<string>? more = null)
    {
        var install = Unzipped(zip, "install");
        var exe = Path.Combine(install, "OriginLauncher.App.exe");
        var bytes = File.ReadAllBytes(exe);
        bytes[bytes.Length / 2] ^= 0x5A;
        File.WriteAllBytes(exe, bytes);
        more?.Invoke(install);
        return install;
    }

    private static Task<DeltaStager.Result> Stage(RangeServer server, DeltaManifest list, string? install, string stage,
        CancellationToken ct = default, double maxFraction = DeltaStager.MaxFetchFraction, Action<long, long>? progress = null,
        long mergeGap = DeltaStager.MergeGap) =>
        DeltaStager.StageAsync(new HttpClient(), server.Url, list, install, stage, progress, Stall, ct, maxFraction, mergeGap);

    // The fixture's list with one entry changed (by path), parsed against the real fixture zip.
    private static DeltaManifest FixtureWith(string path, Action<JsonNode> change)
    {
        var json = JsonNode.Parse(ListBytes)!;
        change(json["files"]!.AsArray().Single(f => f!["path"]!.GetValue<string>() == path)!);
        return DeltaManifest.Parse(Encoding.UTF8.GetBytes(json.ToJsonString()), ZipName, FileSha(FixtureZip));
    }

    // Same files (and folders) with the same bytes.
    private static void AssertSameTree(string expected, string actual)
    {
        static string[] Entries(string root, bool dirs) =>
            (dirs ? Directory.EnumerateDirectories(root, "*", SearchOption.AllDirectories) : Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
            .Select(p => Path.GetRelativePath(root, p).Replace('\\', '/')).OrderBy(p => p, StringComparer.Ordinal).ToArray();
        Assert.Equal(Entries(expected, dirs: true), Entries(actual, dirs: true));
        var files = Entries(expected, dirs: false);
        Assert.Equal(files, Entries(actual, dirs: false));
        foreach (var f in files)
            Assert.True(File.ReadAllBytes(Path.Combine(expected, f)).AsSpan().SequenceEqual(File.ReadAllBytes(Path.Combine(actual, f))), $"{f} differs");
    }

    // ---- the file list ------------------------------------------------------------------------------------------

    [Fact]
    public void The_pipelines_list_parses_sorted_by_offset()
    {
        var list = Fixture();
        Assert.Equal(5, list.Files.Count);
        Assert.Equal(new[] { "Bundled/Empty" }, list.Dirs);
        Assert.Equal(new FileInfo(FixtureZip).Length, list.ZipSize);
        Assert.True(list.Files.Zip(list.Files.Skip(1)).All(p => p.First.Offset + p.First.CompressedSize <= p.Second.Offset));
        Assert.Contains(list.Files, f => f.Method == DeltaFile.Stored);
        Assert.Contains(list.Files, f => f.Method == DeltaFile.Deflate && f.Size == 0);
    }

    [Fact]
    public void A_list_for_another_zip_or_another_zip_hash_is_refused()
    {
        Assert.Throws<DeltaUnavailableException>(() => DeltaManifest.Parse(ListBytes, "OriginLauncher-osx-x64.zip", FileSha(FixtureZip)));
        Assert.Throws<DeltaUnavailableException>(() => DeltaManifest.Parse(ListBytes, ZipName, new string('0', 64)));
    }

    public static TheoryData<string> BadLists => new()
    {
        "format=2", "path=../evil.dll", "path=/abs.dll", "path=C:/x.dll", "path=a\\b.dll", "path=a/./b.dll", "path=x.dll.",
        "path=", "method=12", "offset=-1", "offset=999999999", "sha=xyz", "dup", "overlap", "fileAndFolder", "notJson", "noFiles",
        "path=NUL", "path=Bundled/con.txt", "path=COM1.dll", "path=lpt9", "path=x.dll ", "csize=0", "emptyWrongSha",
    };

    [Theory]
    [MemberData(nameof(BadLists))]
    public void Anything_odd_in_the_list_is_refused(string change)
    {
        var json = JsonNode.Parse(ListBytes)!;
        var files = json["files"]!.AsArray();
        var first = files[0]!;
        string? raw = null;
        switch (change)
        {
            case "format=2": json["format"] = 2; break;
            case "method=12": first["method"] = 12; break;
            case "offset=-1": first["offset"] = -1; break;
            case "offset=999999999": first["offset"] = 999_999_999; break;
            case "sha=xyz": first["sha256"] = "xyz"; break;
            case "dup": files[1]!["path"] = first["path"]!.GetValue<string>().ToUpperInvariant(); break;
            case "overlap": files[1]!["offset"] = first["offset"]!.GetValue<long>() + 1; break;
            case "fileAndFolder": first["path"] = "Bundled"; break;
            case "notJson": raw = "{ not json"; break;
            case "noFiles": json["files"] = new JsonArray(); break;
            case "csize=0": first["csize"] = 0; break; // the exe: bytes but no compressed bytes
            case "emptyWrongSha":
                files.Single(f => f!["path"]!.GetValue<string>() == "empty.txt")!["sha256"] = new string('a', 64);
                break;
            default: first["path"] = change["path=".Length..]; break;
        }
        var bytes = Encoding.UTF8.GetBytes(raw ?? json.ToJsonString());
        Assert.Throws<DeltaUnavailableException>(() => DeltaManifest.Parse(bytes, ZipName, FileSha(FixtureZip)));
    }

    [Theory]
    [InlineData("OriginLauncher.App.exe", true)]
    [InlineData("Bundled/OriginClient/originclient-1.21.1.jar", true)]
    [InlineData("..", false)]
    [InlineData("a/../b", false)]
    [InlineData("a//b", false)]
    [InlineData("a/", false)]
    [InlineData("a b ", false)]
    [InlineData("con:x", false)]
    [InlineData("a*b", false)]
    [InlineData("NUL", false)]
    [InlineData("Bundled/aux.json", false)]
    [InlineData("com3.jar", false)]
    [InlineData("LPT1", false)]
    [InlineData("CONOUT$", false)]
    [InlineData("console.dll", true)]
    [InlineData("COM.dll", true)]
    [InlineData("nul2.txt", true)]
    public void Safe_paths_are_plain_relative_ones(string path, bool safe) => Assert.Equal(safe, DeltaManifest.IsSafeRelative(path));

    [Fact]
    public void Neighbouring_entries_share_one_request_and_far_ones_get_their_own()
    {
        DeltaFile F(long offset, long size) => new($"f{offset}", size, new string('a', 64), offset, size, DeltaFile.Stored);
        var ranges = DeltaStager.PlanRanges([F(5_000_000, 10), F(0, 100), F(150, 50), F(100 + DeltaStager.MergeGap + 1000, 10)]);
        Assert.Equal(3, ranges.Count);
        Assert.Equal((0L, 200L), (ranges[0].Start, ranges[0].End));
        Assert.Equal(2, ranges[0].Files.Count);
        Assert.Single(ranges[1].Files);
        Assert.Equal(5_000_010, ranges[2].End);
    }

    // ---- staging ------------------------------------------------------------------------------------------------

    [Fact]
    public async Task An_older_install_plus_the_changed_files_equals_a_full_unzip()
    {
        using var server = new RangeServer(FixtureZip);
        var list = Fixture();
        var install = OlderInstall(FixtureZip, dir =>
        {
            File.Delete(Path.Combine(dir, "Bundled/OriginClient/originclient-26.2.jar")); // missing: fetched
            File.WriteAllText(Path.Combine(dir, "uninstall.exe"), "not in the release");  // extra: never staged
        });
        var stage = Path.Combine(_dir, "stage");
        long lastReceived = -1, lastTotal = -1;

        var result = await Stage(server, list, install, stage, maxFraction: 1.0, progress: (r, t) => { lastReceived = r; lastTotal = t; });

        AssertSameTree(Unzipped(FixtureZip, "full"), stage);
        Assert.Equal(3, result.ReusedFiles);
        Assert.Equal(2, result.FetchedFiles);
        Assert.True(result.FetchedBytes < list.ZipSize, $"{result.FetchedBytes} of {list.ZipSize}");
        Assert.Equal(result.FetchedBytes, lastTotal);
        Assert.Equal(lastTotal, lastReceived);
        Assert.True(server.RangeRequests >= 1);
        Assert.Equal(0, server.FullRequests);
        Assert.True(server.Redirects >= 1, "the download URL redirected (like GitHub's) and the Range header survived it");
        await DeltaStager.VerifyStagedAsync(list, stage, CancellationToken.None);
    }

    [Fact]
    public async Task Only_the_changed_file_is_downloaded()
    {
        using var server = new RangeServer(FixtureZip);
        var list = Fixture();
        var exe = list.Files.Single(f => f.Path == "OriginLauncher.App.exe");

        var result = await Stage(server, list, OlderInstall(FixtureZip), Path.Combine(_dir, "stage"));

        Assert.Equal(1, result.FetchedFiles);
        Assert.Equal(exe.CompressedSize, result.FetchedBytes);
        Assert.Equal(exe.CompressedSize, server.BytesServed);
    }

    [Fact]
    public async Task A_same_sized_but_different_installed_file_is_fetched_not_reused()
    {
        using var server = new RangeServer(FixtureZip);
        var install = OlderInstall(FixtureZip, dir =>
        {
            var jar = Path.Combine(dir, "Bundled/OriginClient/originclient-1.21.1.jar");
            var b = File.ReadAllBytes(jar);
            b[0] ^= 1;
            File.WriteAllBytes(jar, b);
        });
        var stage = Path.Combine(_dir, "stage");
        var result = await Stage(server, Fixture(), install, stage, maxFraction: 1.0);
        Assert.Equal(2, result.FetchedFiles);
        AssertSameTree(Unzipped(FixtureZip, "full"), stage);
    }

    [Fact]
    public async Task Nearly_everything_changed_means_the_full_download_and_no_request()
    {
        using var server = new RangeServer(FixtureZip);
        await Assert.ThrowsAsync<DeltaUnavailableException>(() => Stage(server, Fixture(), null, Path.Combine(_dir, "stage")));
        Assert.Equal(0, server.RangeRequests + server.FullRequests);
    }

    [Theory]
    [InlineData(RangeServer.Mode.IgnoreRange)]
    [InlineData(RangeServer.Mode.WrongRange)]
    [InlineData(RangeServer.Mode.Corrupt)]
    [InlineData(RangeServer.Mode.Truncate)]
    [InlineData(RangeServer.Mode.Encoded)]
    public async Task A_server_that_misbehaves_means_the_full_download(RangeServer.Mode mode)
    {
        using var server = new RangeServer(FixtureZip) { Behaviour = mode };
        var e = await Assert.ThrowsAsync<DeltaUnavailableException>(() => Stage(server, Fixture(), OlderInstall(FixtureZip), Path.Combine(_dir, "stage")));
        _out.WriteLine($"{mode}: {e.Message}");
    }

    [Fact]
    public async Task An_empty_file_is_written_without_a_request()
    {
        // A stored empty entry has no bytes in the zip: fetching it would ask for "bytes X-(X-1)".
        using var server = new RangeServer(FixtureZip);
        var list = FixtureWith("empty.txt", f => { f["method"] = 0; f["csize"] = 0; });
        var install = OlderInstall(FixtureZip, dir => File.Delete(Path.Combine(dir, "empty.txt")));
        var stage = Path.Combine(_dir, "stage");

        var result = await Stage(server, list, install, stage, mergeGap: 0);

        AssertSameTree(Unzipped(FixtureZip, "full"), stage);
        Assert.Equal(1, result.FetchedFiles); // the exe only
        Assert.Equal(1, server.RangeRequests);
    }

    [Fact]
    public async Task When_one_request_fails_the_real_cause_is_reported_not_the_cancelled_siblings()
    {
        var list = Fixture();
        var jar = list.Files.Single(f => f.Path == "Bundled/OriginClient/originclient-1.21.1.jar"); // stored: a flipped byte is a clean hash mismatch
        using var server = new RangeServer(FixtureZip) { CorruptStart = jar.Offset, DelayOthers = TimeSpan.FromSeconds(3) };

        var e = await Assert.ThrowsAsync<DeltaUnavailableException>(() =>
            Stage(server, list, null, Path.Combine(_dir, "stage"), maxFraction: 1.0, mergeGap: 0));

        _out.WriteLine(e.Message);
        Assert.Contains("originclient-1.21.1.jar did not match", e.Message);
    }

    [Fact]
    public async Task A_cancel_is_a_cancel_not_a_fallback()
    {
        using var server = new RangeServer(FixtureZip);
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Stage(server, Fixture(), OlderInstall(FixtureZip), Path.Combine(_dir, "stage"), cts.Token));
    }

    [Fact]
    public async Task A_stage_that_changed_on_disk_fails_its_recheck()
    {
        using var server = new RangeServer(FixtureZip);
        var list = Fixture();
        var stage = Path.Combine(_dir, "stage");
        await Stage(server, list, OlderInstall(FixtureZip), stage);

        var jar = Path.Combine(stage, "Bundled/OriginClient/originclient-26.2.jar");
        var b = File.ReadAllBytes(jar);
        b[^1] ^= 1;
        File.WriteAllBytes(jar, b);
        await Assert.ThrowsAsync<UpdateVerificationException>(() => DeltaStager.VerifyStagedAsync(list, stage, CancellationToken.None));

        b[^1] ^= 1;
        File.WriteAllBytes(jar, b);
        File.WriteAllText(Path.Combine(stage, "planted.dll"), "x");
        await Assert.ThrowsAsync<UpdateVerificationException>(() => DeltaStager.VerifyStagedAsync(list, stage, CancellationToken.None));
    }

    [Fact]
    public void Stale_stage_folders_go_and_kept_ones_stay()
    {
        foreach (var d in new[] { "1.0.84", "1.0.85", "1.0.86.delta-part", "1.0.85.delta-part", "logs", "1.0" })
            Directory.CreateDirectory(Path.Combine(_dir, d));
        File.WriteAllText(Path.Combine(_dir, "1.0.84", "OriginLauncher.App.exe"), "x");

        var n = UpdateVerifier.DeleteStaleStages(_dir, new Version(1, 0, 85), null);

        Assert.Equal(2, n);
        Assert.False(Directory.Exists(Path.Combine(_dir, "1.0.84")));
        Assert.False(Directory.Exists(Path.Combine(_dir, "1.0.86.delta-part")));
        Assert.True(Directory.Exists(Path.Combine(_dir, "1.0.85")));
        Assert.True(Directory.Exists(Path.Combine(_dir, "1.0.85.delta-part")));
        Assert.True(Directory.Exists(Path.Combine(_dir, "logs")));
        Assert.True(Directory.Exists(Path.Combine(_dir, "1.0")));
    }

    // ---- the real release zip (CI) ------------------------------------------------------------------------------

    /// <summary>
    /// build-check's Windows job runs this on the zip and list that push really built (ORIGIN_DELTA_ZIP /
    /// ORIGIN_DELTA_LIST): an "older install" (the exe changed, a mod jar missing, an extra file) plus Range requests
    /// must stage exactly what a full unzip gives, and a server that ignores Range must fall back. Without the two
    /// variables (a normal test run) there is no real zip to use and it returns at once.
    /// </summary>
    [Fact]
    public async Task DeltaRealZipProof()
    {
        var zip = Environment.GetEnvironmentVariable("ORIGIN_DELTA_ZIP");
        var listPath = Environment.GetEnvironmentVariable("ORIGIN_DELTA_LIST");
        if (string.IsNullOrEmpty(zip) && string.IsNullOrEmpty(listPath))
        {
            _out.WriteLine("ORIGIN_DELTA_ZIP not set: nothing to prove here (CI sets it)");
            return;
        }
        Assert.True(File.Exists(zip), $"ORIGIN_DELTA_ZIP {zip} missing");
        Assert.True(File.Exists(listPath), $"ORIGIN_DELTA_LIST {listPath} missing");
        var list = DeltaManifest.Parse(File.ReadAllBytes(listPath), ZipName, FileSha(zip));
        var sidecar = await File.ReadAllTextAsync(listPath + ".sha256");
        Assert.Equal(UpdateVerifier.ParseSha256(sidecar, Path.GetFileName(listPath)), FileSha(listPath));

        var jar = list.Files.First(f => f.Path.EndsWith(".jar", StringComparison.Ordinal)).Path;
        var install = OlderInstall(zip, dir =>
        {
            File.Delete(Path.Combine(dir, jar));
            File.WriteAllText(Path.Combine(dir, "uninstall.exe"), "not in the release");
        });
        var stage = Path.Combine(_dir, "stage");
        using var server = new RangeServer(zip);
        var watch = System.Diagnostics.Stopwatch.StartNew();
        var result = await Stage(server, list, install, stage);
        _out.WriteLine($"real zip: {list.Files.Count} files, {list.ZipSize / 1_048_576d:0.0} MB; changed-files update fetched " +
                       $"{result.FetchedFiles} files, {result.FetchedBytes / 1_048_576d:0.0} MB in {result.Requests} request(s), " +
                       $"reused {result.ReusedFiles}, {watch.Elapsed.TotalSeconds:0.0} s");

        AssertSameTree(Unzipped(zip, "full"), stage);
        Assert.Equal(2, result.FetchedFiles);
        Assert.Equal(list.Files.Count - 2, result.ReusedFiles);
        Assert.Equal(0, server.FullRequests);
        await DeltaStager.VerifyStagedAsync(list, stage, CancellationToken.None);

        using var ignoring = new RangeServer(zip) { Behaviour = RangeServer.Mode.IgnoreRange };
        await Assert.ThrowsAsync<DeltaUnavailableException>(() => Stage(ignoring, list, install, Path.Combine(_dir, "stage2")));
        _out.WriteLine("real zip: a server that ignores Range falls back to the full download");
    }

    /// <summary>
    /// A tiny HTTP/1.1 file server on 127.0.0.1: /release redirects (302) to /blob like GitHub's release downloads,
    /// /blob honours "Range: bytes=a-b" with a 206, or misbehaves as told.
    /// </summary>
    public sealed class RangeServer : IDisposable
    {
        public enum Mode { Honour, IgnoreRange, WrongRange, Corrupt, Truncate, Encoded }

        private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
        private readonly CancellationTokenSource _stop = new();
        private readonly ConcurrentBag<TcpClient> _open = new();
        private readonly string _file;
        private readonly long _length;
        private int _range, _full, _redirects;
        private long _served;

        public RangeServer(string file)
        {
            _file = file;
            _length = new FileInfo(file).Length;
            _listener.Start();
            _ = AcceptLoop();
        }

        public Mode Behaviour { get; init; } = Mode.Honour;
        /// <summary>Corrupt only the range that starts here (the others are served honestly).</summary>
        public long? CorruptStart { get; init; }
        /// <summary>Hold every other range this long before answering (so the corrupted one fails first).</summary>
        public TimeSpan DelayOthers { get; init; }
        public string Url => $"http://127.0.0.1:{((IPEndPoint)_listener.LocalEndpoint).Port}/release/{ZipName}";
        public int RangeRequests => Volatile.Read(ref _range);
        public int FullRequests => Volatile.Read(ref _full);
        public int Redirects => Volatile.Read(ref _redirects);
        public long BytesServed => Interlocked.Read(ref _served);

        private async Task AcceptLoop()
        {
            while (!_stop.IsCancellationRequested)
            {
                TcpClient client;
                try { client = await _listener.AcceptTcpClientAsync(_stop.Token); }
                catch { return; }
                _open.Add(client);
                _ = Task.Run(() => Serve(client));
            }
        }

        private async Task Serve(TcpClient client)
        {
            try
            {
                using var _ = client;
                var stream = client.GetStream();
                var request = await ReadHeaders(stream);
                if (request == null) return;
                var path = request.Split(' ')[1];
                if (path.StartsWith("/release/", StringComparison.Ordinal))
                {
                    Interlocked.Increment(ref _redirects);
                    await Write(stream, $"HTTP/1.1 302 Found\r\nLocation: /blob/{ZipName}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                    return;
                }
                var range = request.Split("\r\n").FirstOrDefault(l => l.StartsWith("Range: bytes=", StringComparison.OrdinalIgnoreCase));
                if (range == null || Behaviour == Mode.IgnoreRange)
                {
                    Interlocked.Increment(ref _full);
                    await Write(stream, $"HTTP/1.1 200 OK\r\nContent-Length: {_length}\r\nConnection: close\r\n\r\n");
                    await SendSlice(stream, 0, _length, corrupt: false);
                    return;
                }
                Interlocked.Increment(ref _range);
                var spec = range["Range: bytes=".Length..].Split('-');
                long from = long.Parse(spec[0]), to = long.Parse(spec[1]);
                var corrupt = Behaviour == Mode.Corrupt || CorruptStart == from;
                if (CorruptStart != null && !corrupt && DelayOthers > TimeSpan.Zero) await Task.Delay(DelayOthers, _stop.Token);
                var length = to - from + 1;
                var shown = Behaviour == Mode.WrongRange ? $"{from + 1}-{to + 1}" : $"{from}-{to}";
                var encoding = Behaviour == Mode.Encoded ? "Content-Encoding: gzip\r\n" : "";
                await Write(stream, $"HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {shown}/{_length}\r\n{encoding}" +
                                    $"Content-Length: {length}\r\nConnection: close\r\n\r\n");
                await SendSlice(stream, from, Behaviour == Mode.Truncate ? length / 2 : length, corrupt);
            }
            catch { /* client went away or server stopping */ }
        }

        private async Task SendSlice(NetworkStream stream, long from, long count, bool corrupt)
        {
            await using var file = new FileStream(_file, FileMode.Open, FileAccess.Read, FileShare.Read);
            file.Position = from;
            var buffer = new byte[1 << 16];
            var corrupted = false;
            while (count > 0)
            {
                var read = await file.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, count)));
                if (read == 0) break;
                if (corrupt && !corrupted) { buffer[read / 2] ^= 0xFF; corrupted = true; }
                await stream.WriteAsync(buffer.AsMemory(0, read), _stop.Token);
                Interlocked.Add(ref _served, read);
                count -= read;
            }
            await stream.FlushAsync();
        }

        private static Task Write(NetworkStream stream, string head) => stream.WriteAsync(Encoding.ASCII.GetBytes(head)).AsTask();

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
