using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// A disk copy of the small Mojang metadata files a launch reads to check the
/// game's Java (java-runtime all.json and the runtime's manifest), so a player
/// with no internet who already has every file can still press Play.
///
/// CmlLib fetches both on every launch and throws when it can't. Through this
/// client a successful fetch is saved under &lt;launcher&gt;/cache/mojang-meta, and
/// when the network fails (no connection, DNS, timeout) the saved copy answers
/// instead. On a full pass every request still goes to Mojang; the fast path
/// reads the copy first (diskFirst), which saves those two round trips.
///
/// Files under /v1/packages/&lt;sha1&gt;/ are content-addressed: a copy is only
/// saved or served when its SHA-1 matches the URL, so a damaged copy is never used.
/// </summary>
public sealed class MojangMetaCache : DelegatingHandler
{
    private static readonly Regex Addressed = new(@"/v1/packages/([0-9a-f]{40})/", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private readonly string _dir;
    private readonly bool _diskFirst;

    /// <param name="diskFirst">
    /// Answer from the disk copy without asking Mojang when there is one. Only for
    /// the fast path (InstallVerification: this install passed a full verify under
    /// this launcher build within the last week); full passes always fetch, so a
    /// Java update Mojang publishes is picked up by the next full pass.
    /// </param>
    public MojangMetaCache(string dir, HttpMessageHandler inner, bool diskFirst = false) : base(inner)
    {
        _dir = dir;
        _diskFirst = diskFirst;
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        if (request.Method != HttpMethod.Get || request.RequestUri is not { } uri)
            return await base.SendAsync(request, ct);
        if (_diskFirst && Load(uri) is { } copy)
            return Answer(request, copy);

        try
        {
            using var response = await base.SendAsync(request, ct);
            response.EnsureSuccessStatusCode();
            var body = await response.Content.ReadAsByteArrayAsync(ct);
            if (Matches(uri, body)) Save(uri, body);
            return Answer(request, body);
        }
        catch (Exception e) when (IsNetworkFailure(e, ct) && Load(uri) is { } saved)
        {
            return Answer(request, saved);
        }
    }

    // No connection / DNS / refused / bad status, or HttpClient's own timeout; never the caller's cancel.
    private static bool IsNetworkFailure(Exception e, CancellationToken ct) =>
        e is HttpRequestException || (e is TaskCanceledException && !ct.IsCancellationRequested);

    private static HttpResponseMessage Answer(HttpRequestMessage request, byte[] body) =>
        new(System.Net.HttpStatusCode.OK) { RequestMessage = request, Content = new ByteArrayContent(body) };

    /// <summary>True unless the URL names a SHA-1 the body doesn't have.</summary>
    public static bool Matches(Uri uri, byte[] body)
    {
        var m = Addressed.Match(uri.AbsolutePath);
        return !m.Success || string.Equals(Convert.ToHexString(SHA1.HashData(body)), m.Groups[1].Value, StringComparison.OrdinalIgnoreCase);
    }

    private string PathFor(Uri uri) =>
        Path.Combine(_dir, Convert.ToHexString(SHA1.HashData(Encoding.UTF8.GetBytes(uri.AbsoluteUri))).ToLowerInvariant() + ".json");

    private byte[]? Load(Uri uri)
    {
        try
        {
            var file = PathFor(uri);
            if (!File.Exists(file)) return null;
            var body = File.ReadAllBytes(file);
            return body.Length > 0 && Matches(uri, body) ? body : null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    // Write-then-rename so a crash mid-write never leaves a half file to be served later.
    private void Save(Uri uri, byte[] body)
    {
        try
        {
            Directory.CreateDirectory(_dir);
            var file = PathFor(uri);
            var temp = file + "." + Environment.ProcessId + ".tmp";
            File.WriteAllBytes(temp, body);
            File.Move(temp, file, overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Best-effort: no copy only means no offline fallback for this file.
        }
    }
}

/// <summary>
/// Sends through an existing HttpClient, so a handler chain can reuse its connections.
/// A request can be sent once per client, so a copy (same method, URL, headers
/// and content) goes on.
/// </summary>
public sealed class ForwardToClient : HttpMessageHandler
{
    private readonly HttpClient _client;

    public ForwardToClient(HttpClient client) => _client = client;

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var copy = new HttpRequestMessage(request.Method, request.RequestUri)
        {
            Version = request.Version,
            Content = request.Content,
        };
        foreach (var header in request.Headers)
            copy.Headers.TryAddWithoutValidation(header.Key, header.Value);
        return _client.SendAsync(copy, HttpCompletionOption.ResponseHeadersRead, ct);
    }
}
