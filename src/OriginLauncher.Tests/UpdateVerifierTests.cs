using System.Security.Cryptography;
using OriginLauncher.App.Core.Updates;
using Xunit;

namespace OriginLauncher.Tests;

// The self-update zip's integrity check (Core/Updates/UpdateVerifier): match, mismatch, no hash
// published, truncated, a connection that dies mid-download, and the hash-file formats. Real files in a
// temp folder, no network.
public sealed class UpdateVerifierTests : IDisposable
{
    private const string Asset = "OriginLauncher-win-x64.zip";
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-update-verify-" + Guid.NewGuid().ToString("N"));
    private readonly byte[] _zip = Enumerable.Range(0, 300_000).Select(i => (byte)(i * 31 % 251)).ToArray();

    public UpdateVerifierTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { }
    }

    private string Dest => Path.Combine(_dir, Asset + ".part");
    private string Sha(byte[] b) => Convert.ToHexString(SHA256.HashData(b)).ToLowerInvariant();

    private Task<UpdateVerdict> RunAsync(byte[] served, long? length, string? expected, Action<long>? onBytes = null) =>
        UpdateVerifier.CopyAndVerifyAsync(new MemoryStream(served), Dest, length, expected, onBytes, CancellationToken.None);

    [Fact]
    public async Task Matching_hash_is_ok_and_keeps_the_file()
    {
        var verdict = await RunAsync(_zip, _zip.Length, Sha(_zip));
        Assert.Equal(UpdateVerdict.Ok, verdict);
        Assert.Equal(_zip, await File.ReadAllBytesAsync(Dest));
    }

    [Fact]
    public async Task Hash_comparison_ignores_case()
    {
        Assert.Equal(UpdateVerdict.Ok, await RunAsync(_zip, _zip.Length, Sha(_zip).ToUpperInvariant()));
    }

    [Fact]
    public async Task Mismatching_hash_is_refused_and_the_file_is_deleted()
    {
        var tampered = (byte[])_zip.Clone();
        tampered[1234] ^= 0xFF;
        var verdict = await RunAsync(tampered, tampered.Length, Sha(_zip));
        Assert.Equal(UpdateVerdict.Mismatch, verdict);
        Assert.False(File.Exists(Dest));
    }

    [Fact]
    public async Task Missing_hash_is_accepted_unverified_and_keeps_the_file()
    {
        var verdict = await RunAsync(_zip, _zip.Length, expected: null);
        Assert.Equal(UpdateVerdict.OkUnverified, verdict);
        Assert.True(File.Exists(Dest));
    }

    [Fact]
    public async Task Truncated_download_is_refused_even_without_a_hash_and_deleted()
    {
        var half = _zip.Take(_zip.Length / 2).ToArray();
        Assert.Equal(UpdateVerdict.Truncated, await RunAsync(half, _zip.Length, expected: null));
        Assert.False(File.Exists(Dest));
        Assert.Equal(UpdateVerdict.Truncated, await RunAsync(half, _zip.Length, Sha(_zip)));
        Assert.False(File.Exists(Dest));
    }

    [Fact]
    public async Task Unknown_length_still_falls_back_to_the_hash()
    {
        Assert.Equal(UpdateVerdict.Ok, await RunAsync(_zip, length: null, Sha(_zip)));
        var half = _zip.Take(_zip.Length / 2).ToArray();
        Assert.Equal(UpdateVerdict.Mismatch, await RunAsync(half, length: null, Sha(_zip)));
        Assert.False(File.Exists(Dest));
    }

    [Fact]
    public async Task A_connection_that_dies_mid_download_deletes_the_partial_file_and_rethrows()
    {
        await Assert.ThrowsAsync<IOException>(() => UpdateVerifier.CopyAndVerifyAsync(
            new DyingStream(_zip, dieAfter: 100_000), Dest, _zip.Length, Sha(_zip), null, CancellationToken.None));
        Assert.False(File.Exists(Dest));
    }

    [Fact]
    public async Task A_cancelled_download_deletes_the_partial_file()
    {
        using var cts = new CancellationTokenSource();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => UpdateVerifier.CopyAndVerifyAsync(
            new MemoryStream(_zip), Dest, _zip.Length, Sha(_zip), received => { if (received > 70_000) cts.Cancel(); }, cts.Token));
        Assert.False(File.Exists(Dest));
    }

    [Fact]
    public async Task Progress_reports_every_chunk_up_to_the_full_size()
    {
        long last = 0;
        await RunAsync(_zip, _zip.Length, Sha(_zip), received => last = received);
        Assert.Equal(_zip.Length, last);
    }

    [Fact]
    public async Task An_old_partial_file_from_a_crashed_attempt_is_overwritten()
    {
        await File.WriteAllBytesAsync(Dest, new byte[_zip.Length * 2]);
        Assert.Equal(UpdateVerdict.Ok, await RunAsync(_zip, _zip.Length, Sha(_zip)));
        Assert.Equal(_zip.Length, new FileInfo(Dest).Length);
    }

    private const string Hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    [Theory]
    [InlineData(Hex)]
    [InlineData(Hex + "\n")]
    [InlineData(Hex + "  " + Asset + "\n")]
    [InlineData(Hex + " *" + Asset + "\r\n")]
    [InlineData("  " + Hex + "  ")]
    public void ParseSha256_reads_the_published_formats(string text) =>
        Assert.Equal(Hex, UpdateVerifier.ParseSha256(text, Asset));

    [Fact]
    public void ParseSha256_uppercase_is_normalised()
    {
        Assert.Equal(Hex, UpdateVerifier.ParseSha256(Hex.ToUpperInvariant() + "  " + Asset, Asset));
    }

    [Fact]
    public void ParseSha256_picks_the_line_for_this_asset()
    {
        var other = new string('a', 64);
        Assert.Equal(Hex, UpdateVerifier.ParseSha256($"{other}  OriginLauncher-Setup.exe\n{Hex}  {Asset}\n", Asset));
        Assert.Null(UpdateVerifier.ParseSha256($"{other}  OriginLauncher-Setup.exe\n{other}  other.zip\n", Asset));
        Assert.Null(UpdateVerifier.ParseSha256($"{other}  OriginLauncher-Setup.exe\n", Asset));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("not a hash")]
    [InlineData("zz7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")]
    [InlineData("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015")] // 63 digits
    [InlineData("<html>404</html>")]
    public void ParseSha256_rejects_anything_else(string? text) =>
        Assert.Null(UpdateVerifier.ParseSha256(text, Asset));

    [Fact]
    public void ParseDigest_reads_GitHubs_sha256_digest()
    {
        Assert.Equal(Hex, UpdateVerifier.ParseDigest("sha256:" + Hex));
        Assert.Equal(Hex, UpdateVerifier.ParseDigest("SHA256:" + Hex.ToUpperInvariant()));
    }

    // A GitHub API change must never brick updates: every odd digest is "no hash" (null), not a mismatch.
    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("sha256:")]
    [InlineData("sha256:abc")]
    [InlineData("sha512:" + Hex + Hex)]                       // another algorithm
    [InlineData("md5:d41d8cd98f00b204e9800998ecf8427e")]
    [InlineData(Hex)]                                          // no algorithm prefix
    [InlineData("sha256-" + Hex)]                              // another separator
    [InlineData("sha256:" + Hex + "00")]                       // too long
    [InlineData("sha256:zz7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")]
    [InlineData("{\"algorithm\":\"sha256\"}")]
    public void ParseDigest_treats_an_odd_digest_as_no_hash(string? digest) =>
        Assert.Null(UpdateVerifier.ParseDigest(digest));

    [Fact]
    public async Task An_odd_digest_downloads_unverified_instead_of_failing()
    {
        // what UpdateService does with a digest it cannot read: expected = null -> accepted, file kept
        var expected = UpdateVerifier.ParseDigest("sha512:" + Hex + Hex);
        Assert.Null(expected);
        Assert.Equal(UpdateVerdict.OkUnverified, await RunAsync(_zip, _zip.Length, expected));
        Assert.True(File.Exists(Dest));
    }

    [Fact]
    public async Task A_listed_sidecar_that_fetches_fine_is_read()
    {
        Assert.Equal(Hex, await UpdateVerifier.FetchSidecarAsync(_ => Task.FromResult(Hex + "  " + Asset + "\n"), Asset, TimeSpan.FromSeconds(5)));
    }

    // A listed .sha256 that cannot be had must be an UpdateVerificationException (it ungates Play), never a raw
    // HttpRequestException / TaskCanceledException that would leave "Update required" up forever.
    [Fact]
    public async Task A_sidecar_that_404s_is_a_verification_failure()
    {
        await Assert.ThrowsAsync<UpdateVerificationException>(() => UpdateVerifier.FetchSidecarAsync(
            _ => throw new HttpRequestException("404", null, System.Net.HttpStatusCode.NotFound), Asset, TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public async Task A_sidecar_that_times_out_is_a_verification_failure()
    {
        await Assert.ThrowsAsync<UpdateVerificationException>(() => UpdateVerifier.FetchSidecarAsync(
            async ct => { await Task.Delay(Timeout.Infinite, ct); return ""; }, Asset, TimeSpan.FromMilliseconds(50)));
    }

    [Fact]
    public async Task A_sidecar_with_a_garbled_body_is_a_verification_failure()
    {
        await Assert.ThrowsAsync<UpdateVerificationException>(() => UpdateVerifier.FetchSidecarAsync(
            _ => Task.FromResult("<html>Not Found</html>"), Asset, TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public void ParseSha256_rejects_a_huge_body()
    {
        Assert.Null(UpdateVerifier.ParseSha256(Hex + new string(' ', 5000), Asset));
    }

    // Serves some bytes, then fails like a reset connection.
    private sealed class DyingStream(byte[] data, int dieAfter) : Stream
    {
        private int _pos;
        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_pos >= dieAfter) throw new IOException("connection reset");
            var n = Math.Min(Math.Min(count, 16_384), dieAfter - _pos);
            Array.Copy(data, _pos, buffer, offset, n);
            _pos += n;
            return n;
        }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => _pos; set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
