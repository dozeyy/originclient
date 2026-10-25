using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using OriginLauncher.App.Core.Loaders;
using Xunit;

namespace OriginLauncher.Tests;

// FabricApiInstaller downloads Fabric API from Modrinth's live API into the player's mods folder. The gate
// (ModrinthFileGuard) must refuse anything but the exact published file, the way the self-update zip does
// (UpdateVerifierTests): bad hash, wrong host, an unsafe file name, no hash, and a failed download that must
// leave no ".download" file behind. Stub handler and temp folders only, never the network.
public sealed class ModrinthFileGuardTests : IDisposable
{
    private const string GoodUrl = "https://cdn.modrinth.com/data/P7dR8mSH/versions/x/fabric-api-0.141.6+1.21.11.jar";
    private const string GoodName = "fabric-api-0.141.6+1.21.11.jar";
    private static readonly byte[] Jar = [0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5, 6, 7, 8];
    private static readonly string JarSha512 = Convert.ToHexString(SHA512.HashData(Jar)).ToLowerInvariant();

    private readonly string _mods = Path.Combine(Path.GetTempPath(), "origin-mfg-" + Guid.NewGuid().ToString("N"), "mods");

    public ModrinthFileGuardTests() => Directory.CreateDirectory(_mods);

    public void Dispose()
    {
        try { Directory.Delete(Path.GetDirectoryName(_mods)!, recursive: true); } catch (IOException) { }
    }

    private sealed class Stub(Func<HttpRequestMessage, HttpResponseMessage> reply) : HttpMessageHandler
    {
        public int Calls;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Calls++;
            return Task.FromResult(reply(request));
        }
    }

    private static Stub ServesJar() =>
        new(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Jar) });

    private Task<ModrinthDownloadResult> Run(Stub stub, string? url = GoodUrl, string? name = GoodName, string? sha = null) =>
        ModrinthFileGuard.TryDownloadAsync(new HttpClient(stub), url, name, sha ?? JarSha512, _mods, CancellationToken.None);

    private string[] FilesInMods() => Directory.GetFileSystemEntries(_mods).Select(Path.GetFileName).ToArray()!;

    [Fact]
    public async Task A_matching_download_is_installed_and_leaves_no_temp_file()
    {
        var stub = ServesJar();
        Assert.Equal(ModrinthDownloadResult.Installed, await Run(stub));
        Assert.Equal([GoodName], FilesInMods());
        Assert.Equal(Jar, await File.ReadAllBytesAsync(Path.Combine(_mods, GoodName)));
    }

    [Fact]
    public async Task A_bad_hash_is_refused_and_the_temp_file_is_deleted()
    {
        var stub = ServesJar();
        var wrong = new string('a', 128);
        Assert.Equal(ModrinthDownloadResult.HashMismatch, await Run(stub, sha: wrong));
        Assert.Equal(1, stub.Calls);
        Assert.Empty(FilesInMods());
    }

    [Theory]
    [InlineData("https://evil.example.com/fabric-api.jar")]
    [InlineData("https://cdn.modrinth.com.evil.example.com/fabric-api.jar")]
    [InlineData("https://notcdn.modrinth.com/fabric-api.jar")]
    [InlineData("https://api.modrinth.com/fabric-api.jar")]
    [InlineData("http://cdn.modrinth.com/fabric-api.jar")]
    [InlineData("https://user:pw@cdn.modrinth.com/fabric-api.jar")]
    [InlineData("https://cdn.modrinth.com:8443/fabric-api.jar")]
    [InlineData("ftp://cdn.modrinth.com/fabric-api.jar")]
    [InlineData("file:///C:/fabric-api.jar")]
    [InlineData("not a url")]
    [InlineData("")]
    [InlineData(null)]
    public async Task A_wrong_host_or_scheme_is_refused_before_any_request(string? url)
    {
        var stub = ServesJar();
        Assert.Equal(ModrinthDownloadResult.RefusedUrl, await Run(stub, url: url));
        Assert.Equal(0, stub.Calls);
        Assert.Empty(FilesInMods());
    }

    [Fact]
    public void The_real_modrinth_cdn_url_is_trusted_in_any_letter_case()
    {
        Assert.True(ModrinthFileGuard.IsTrustedUrl(GoodUrl));
        Assert.True(ModrinthFileGuard.IsTrustedUrl("HTTPS://CDN.MODRINTH.COM/data/x/y.jar"));
    }

    [Theory]
    [InlineData("..\\evil.jar")]
    [InlineData("../evil.jar")]
    [InlineData("sub/evil.jar")]
    [InlineData("sub\\evil.jar")]
    [InlineData("..")]
    [InlineData("fabric..api.jar")]
    [InlineData("C:\\Windows\\evil.jar")]
    [InlineData("C:evil.jar")]
    [InlineData("/etc/evil.jar")]
    [InlineData("\\\\server\\share\\evil.jar")]
    [InlineData("fabric-api.exe")]
    [InlineData("fabric-api.jar.exe")]
    [InlineData("fabric-api.jar ")]
    [InlineData(".jar")]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(null)]
    public async Task A_filename_that_is_a_path_or_not_a_jar_is_refused_before_any_request(string? name)
    {
        var stub = ServesJar();
        Assert.Equal(ModrinthDownloadResult.RefusedFilename, await Run(stub, name: name));
        Assert.Equal(0, stub.Calls);
        Assert.Empty(FilesInMods());
        // and nothing escaped next to the mods folder either
        Assert.Empty(Directory.GetFiles(Path.GetDirectoryName(_mods)!));
    }

    [Fact]
    public async Task A_missing_hash_is_refused_before_any_request()
    {
        var stub = ServesJar();
        var result = await ModrinthFileGuard.TryDownloadAsync(new HttpClient(stub), GoodUrl, GoodName, null, _mods, CancellationToken.None);
        Assert.Equal(ModrinthDownloadResult.RefusedNoHash, result);
        Assert.Equal(0, stub.Calls);
        Assert.Empty(FilesInMods());
    }

    [Fact]
    public async Task An_http_error_fails_soft_without_throwing()
    {
        var stub = new Stub(_ => new HttpResponseMessage(HttpStatusCode.NotFound));
        Assert.Equal(ModrinthDownloadResult.Failed, await Run(stub));
        Assert.Empty(FilesInMods());
    }

    [Fact]
    public async Task A_download_cut_off_mid_stream_leaves_no_temp_file()
    {
        // The body starts, writes the .download file, then the connection dies.
        var stub = new Stub(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new BrokenStream(Jar)) });
        Assert.Equal(ModrinthDownloadResult.Failed, await Run(stub));
        Assert.Empty(FilesInMods());
    }

    [Fact]
    public async Task A_network_exception_fails_soft_and_leaves_nothing()
    {
        var stub = new Stub(_ => throw new HttpRequestException("offline"));
        Assert.Equal(ModrinthDownloadResult.Failed, await Run(stub));
        Assert.Empty(FilesInMods());
    }

    [Fact]
    public async Task A_cancelled_download_rethrows_the_cancellation_and_deletes_the_temp_file()
    {
        using var cts = new CancellationTokenSource();
        var stub = new Stub(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StreamContent(new BrokenStream(Jar, () => cts.Cancel(), new OperationCanceledException()))
        });
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            ModrinthFileGuard.TryDownloadAsync(new HttpClient(stub), GoodUrl, GoodName, JarSha512, _mods, cts.Token));
        Assert.Empty(FilesInMods());
    }

    [Fact]
    public void Sha512_is_read_from_the_hashes_object_and_must_be_128_hex_digits()
    {
        var good = new Dictionary<string, string> { ["sha1"] = "abc", ["sha512"] = JarSha512.ToUpperInvariant() };
        Assert.Equal(JarSha512, ModrinthFileGuard.ParseSha512(good));
        Assert.Null(ModrinthFileGuard.ParseSha512(null));
        Assert.Null(ModrinthFileGuard.ParseSha512(new Dictionary<string, string> { ["sha1"] = "abc" }));
        Assert.Null(ModrinthFileGuard.ParseSha512(new Dictionary<string, string> { ["sha512"] = "abc" }));
        Assert.Null(ModrinthFileGuard.ParseSha512(new Dictionary<string, string> { ["sha512"] = new string('z', 128) }));
    }

    // Serves the first half of the bytes, then fails (or runs a hook and fails with the given exception).
    private sealed class BrokenStream(byte[] data, Action? onFail = null, Exception? error = null) : Stream
    {
        private int _pos;
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => _pos; set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_pos >= data.Length / 2)
            {
                onFail?.Invoke();
                throw error ?? new IOException("connection reset");
            }
            var n = Math.Min(count, data.Length / 2 - _pos);
            Array.Copy(data, _pos, buffer, offset, n);
            _pos += n;
            return n;
        }
    }
}
