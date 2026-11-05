using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using OriginLauncher.App.Core.Compat;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The Mods page's Update button (Core/Compat/ModrinthUpdates): what counts as a newer release, what leaves the PC
/// (hashes, the Minecraft version and "fabric" only), and that a download is kept only when it is the same mod and
/// matches Modrinth's SHA-512.
/// </summary>
public sealed class ModrinthUpdatesTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("mod-updates").FullName;

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch { /* best effort */ }
    }

    private static readonly string Have = new('a', 128);
    private static readonly string Next = new('b', 128);

    private static string Version(string project, string number, string type, string published, string sha512,
        string file = "mod-2.0.jar", string loaders = "\"fabric\"", string games = "\"1.21.1\"") => $$"""
        {"project_id":"{{project}}","version_number":"{{number}}","version_type":"{{type}}","date_published":"{{published}}",
         "loaders":[{{loaders}}],"game_versions":[{{games}}],
         "files":[{"primary":true,"url":"https://cdn.modrinth.com/data/x/versions/y/{{file}}","filename":"{{file}}",
                   "hashes":{"sha1":"00","sha512":"{{sha512}}"}}]}
        """;

    private static Dictionary<string, ModUpdateOffer> Offers(string current, string latest) =>
        ModrinthUpdates.Offers(new Dictionary<string, string> { ["mod-1.0.jar"] = Have },
            ModrinthUpdates.ParseByHash($"{{\"{Have}\":{current}}}"),
            ModrinthUpdates.ParseByHash($"{{\"{Have}\":{latest}}}"), "1.21.1");

    private const string Old = "2026-01-01T00:00:00Z";
    private const string New = "2026-06-01T00:00:00Z";

    [Fact]
    public void A_newer_release_of_the_same_project_is_offered()
    {
        var offer = Assert.Single(Offers(Version("P", "1.0", "release", Old, Have), Version("P", "2.0", "release", New, Next)));
        Assert.Equal("mod-1.0.jar", offer.Key);
        Assert.Equal(new ModUpdateOffer("mod-1.0.jar", "2.0", "mod-2.0.jar",
            "https://cdn.modrinth.com/data/x/versions/y/mod-2.0.jar", Next), offer.Value);
    }

    [Theory]
    [InlineData("P", "release", New, "b", "\"fabric\"", "\"1.21.1\"")]   // the same file: already the newest
    [InlineData("Q", "release", New, "c", "\"fabric\"", "\"1.21.1\"")]   // another project
    [InlineData("P", "beta", New, "c", "\"fabric\"", "\"1.21.1\"")]      // not a full release
    [InlineData("P", "release", Old, "c", "\"fabric\"", "\"1.21.1\"")]   // not newer than what is installed
    [InlineData("P", "release", New, "c", "\"quilt\"", "\"1.21.1\"")]    // not for Fabric
    [InlineData("P", "release", New, "c", "\"fabric\"", "\"1.21\"")]     // not for this Minecraft version
    public void Anything_but_a_newer_fabric_release_for_this_version_is_not_offered(
        string project, string type, string published, string hashChar, string loaders, string games)
    {
        var current = Version("P", "1.0", "release", Old, Have);
        var sha = hashChar == "b" ? Have : new string(hashChar[0], 128);
        Assert.Empty(Offers(current, Version(project, "2.0", type, published, sha, loaders: loaders, games: games)));
    }

    [Fact]
    public void An_unsafe_file_name_or_a_missing_hash_is_never_offered()
    {
        var current = Version("P", "1.0", "release", Old, Have);
        Assert.Empty(Offers(current, Version("P", "2.0", "release", New, Next, file: "..\\\\evil.jar")));
        Assert.Empty(Offers(current, Version("P", "2.0", "release", New, "short")));
        Assert.Empty(ModrinthUpdates.Offers(new Dictionary<string, string> { ["mod-1.0.jar"] = Have },
            new Dictionary<string, ModrinthUpdates.Release>(), ModrinthUpdates.ParseByHash($"{{\"{Have}\":{Version("P", "2.0", "release", New, Next)}}}"), "1.21.1"));
    }

    [Fact]
    public async Task Only_hashes_the_minecraft_version_and_fabric_leave_the_pc()
    {
        var bodies = new List<string>();
        var http = new HttpClient(new Stub(async request =>
        {
            var body = await request.Content!.ReadAsStringAsync();
            bodies.Add(request.RequestUri!.AbsolutePath + " " + body);
            var json = request.RequestUri.AbsolutePath.EndsWith("/update")
                ? $"{{\"{Have}\":{Version("P", "2.0", "release", New, Next)}}}"
                : $"{{\"{Have}\":{Version("P", "1.0", "release", Old, Have)}}}";
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(json) };
        }));

        var offers = await ModrinthUpdates.FindAsync(http, "1.21.1",
            new Dictionary<string, string> { ["my-secret-name-1.0.jar"] = Have }, CancellationToken.None);

        Assert.Equal("2.0", Assert.Single(offers).Value.NewVersion);
        Assert.Equal(2, bodies.Count);
        Assert.All(bodies, b => Assert.DoesNotContain("secret", b));
        Assert.Equal($"/v2/version_files {{\"hashes\":[\"{Have}\"],\"algorithm\":\"sha512\"}}", bodies[0]);
        Assert.Equal($"/v2/version_files/update {{\"hashes\":[\"{Have}\"],\"algorithm\":\"sha512\",\"loaders\":[\"fabric\"],\"game_versions\":[\"1.21.1\"]}}", bodies[1]);
    }

    [Fact]
    public async Task Offline_means_no_offers()
    {
        var http = new HttpClient(new Stub(_ => throw new HttpRequestException("offline")));
        Assert.Empty(await ModrinthUpdates.FindAsync(http, "1.21.1",
            new Dictionary<string, string> { ["a.jar"] = Have }, CancellationToken.None));
    }

    [Fact]
    public async Task A_download_is_kept_only_when_it_is_the_same_mod_and_matches_the_hash()
    {
        var installed = Path.Combine(_root, "mods", "litematica-0.19.jar");
        Directory.CreateDirectory(Path.GetDirectoryName(installed)!);
        File.WriteAllBytes(installed, Jar("litematica"));
        var downloads = Path.Combine(_root, "downloads");

        var same = Jar("litematica");
        var (path, error) = await ModrinthUpdates.DownloadAsync(Serving(same), Offer(same), installed, downloads, CancellationToken.None);
        Assert.Null(error);
        Assert.Equal(same, File.ReadAllBytes(path!));
        Assert.StartsWith(downloads, path);
        ModrinthUpdates.Discard(path);
        Assert.False(File.Exists(path));

        var other = Jar("someothermod");
        (path, error) = await ModrinthUpdates.DownloadAsync(Serving(other), Offer(other), installed, downloads, CancellationToken.None);
        Assert.Null(path);
        Assert.Contains("isn't the same mod", error);

        (path, error) = await ModrinthUpdates.DownloadAsync(Serving(same), Offer(other), installed, downloads, CancellationToken.None);
        Assert.Null(path);
        Assert.Contains("checksum", error);

        // Nothing was ever written next to the installed jar, and it is untouched.
        Assert.Equal(new[] { "litematica-0.19.jar" }, Directory.GetFiles(Path.GetDirectoryName(installed)!).Select(Path.GetFileName));
    }

    private static ModUpdateOffer Offer(byte[] bytes) => new("litematica-0.19.jar", "0.21.0", "litematica-0.21.jar",
        "https://cdn.modrinth.com/data/x/versions/y/litematica-0.21.jar", Convert.ToHexString(SHA512.HashData(bytes)).ToLowerInvariant());

    private static HttpClient Serving(byte[] bytes) => new(new Stub(_ =>
        Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(bytes) })));

    private static byte[] Jar(string id)
    {
        using var buffer = new MemoryStream();
        using (var zip = new ZipArchive(buffer, ZipArchiveMode.Create, leaveOpen: true))
        {
            var entry = zip.CreateEntry("fabric.mod.json");
            using var writer = new StreamWriter(entry.Open(), Encoding.UTF8);
            writer.Write($"{{\"schemaVersion\":1,\"id\":\"{id}\",\"version\":\"1.0\"}}");
        }
        return buffer.ToArray();
    }

    private sealed class Stub(Func<HttpRequestMessage, Task<HttpResponseMessage>> answer) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) => answer(request);
    }
}
