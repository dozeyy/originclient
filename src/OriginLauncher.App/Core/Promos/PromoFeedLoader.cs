using System.IO;

namespace OriginLauncher.App.Core.Promos;

public enum PromoFeedOrigin
{
    /// <summary>Fetched just now from one of <see cref="PromoFeedLoader.Sources"/>.</summary>
    Remote,
    /// <summary>The last good remote feed, saved on disk.</summary>
    Cache,
    /// <summary>Compiled into the launcher (<see cref="PromoFeedLoader.BuiltInJson"/>).</summary>
    BuiltIn,
}

/// <param name="Json">The exact feed text, used to skip re-applying an unchanged feed.</param>
public sealed record PromoFeedResult(PromoFeed Feed, PromoFeedOrigin Origin, string Json);

/// <summary>
/// The fallback chain for the banner feed, first usable wins:
///   1. the live website (GitHub Pages, originclient.org),
///   2. the same file straight from the repo on raw.githubusercontent.com,
///   3. the last good copy cached under %LocalAppData%\OriginLauncher\cache,
///   4. the default compiled into the launcher.
/// Remote fetches are short-timeout and never throw; a successful one
/// refreshes the cache. Nothing here touches the UI thread.
/// </summary>
public sealed class PromoFeedLoader
{
    public static readonly Uri LiveUrl = new("https://originclient.org/launcher/promos.json");
    public static readonly Uri RepoUrl = new("https://raw.githubusercontent.com/dozeyy/originclient/main/website/launcher/promos.json");
    public static readonly IReadOnlyList<Uri> Sources = new[] { LiveUrl, RepoUrl };

    public static readonly TimeSpan PerSourceTimeout = TimeSpan.FromSeconds(5);

    // The launcher's own copy, used on a first run with no network. Mirrors
    // website/launcher/promos.json minus anything that only makes sense live
    // (the network block is always off here).
    public const string BuiltInJson = """
        {
          "schema": 1,
          "rotateSeconds": 9,
          "promos": [
            { "id": "discord", "kind": "discord", "label": "Discord" },
            {
              "id": "website", "kind": "link", "label": "Origin",
              "title": "originclient.org",
              "subtitle": "News, changelogs and every bundled mod",
              "clickUrl": "https://originclient.org/"
            },
            {
              "id": "menu-tip", "kind": "link", "label": "Tip",
              "title": "Right Shift opens the Origin menu",
              "subtitle": "Toggle mods and HUD pieces in-game"
            }
          ],
          "discord": { "inviteCode": "REPLACE_ME", "refreshSeconds": 300 },
          "network": { "enabled": false, "url": "https://originclient.org/ad/banner.html", "refreshSeconds": 300 }
        }
        """;

    private readonly HttpTextGet _get;
    private readonly string _cachePath;
    private readonly IReadOnlyList<Uri> _sources;

    public PromoFeedLoader(HttpTextGet get, string cachePath, IReadOnlyList<Uri>? sources = null)
    {
        _get = get;
        _cachePath = cachePath;
        _sources = sources ?? Sources;
    }

    public static PromoFeedResult BuiltIn() =>
        new(PromoFeedParser.TryParse(BuiltInJson)
            ?? throw new InvalidOperationException("Built-in promo feed does not parse."),
            PromoFeedOrigin.BuiltIn, BuiltInJson);

    /// <summary>Steps 3–4: the cached feed, else the built-in one. Disk only (call off the UI thread).</summary>
    public PromoFeedResult LoadLocal()
    {
        try
        {
            if (File.Exists(_cachePath))
            {
                var json = File.ReadAllText(_cachePath);
                if (PromoFeedParser.TryParse(json) is { } cached)
                    return new PromoFeedResult(cached, PromoFeedOrigin.Cache, json);
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Unreadable cache is the same as no cache.
        }
        return BuiltIn();
    }

    /// <summary>Steps 1–2: the first remote copy that parses (and caches it), or null when none does.</summary>
    public async Task<PromoFeedResult?> FetchRemoteAsync(CancellationToken ct)
    {
        foreach (var source in _sources)
        {
            ct.ThrowIfCancellationRequested();
            var response = await _get(source, PerSourceTimeout, ct).ConfigureAwait(false);
            if (!response.IsSuccess) continue;

            var feed = PromoFeedParser.TryParse(response.Body);
            if (feed == null) continue;

            SaveCache(response.Body!);
            return new PromoFeedResult(feed, PromoFeedOrigin.Remote, response.Body!);
        }
        return null;
    }

    /// <summary>The whole chain, remote first.</summary>
    public async Task<PromoFeedResult> ResolveAsync(CancellationToken ct) =>
        await FetchRemoteAsync(ct).ConfigureAwait(false) ?? LoadLocal();

    // Write-then-rename so a crash mid-write can never leave a torn cache.
    private void SaveCache(string json)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_cachePath)!);
            var temp = _cachePath + ".tmp";
            File.WriteAllText(temp, json);
            File.Move(temp, _cachePath, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Caching is an optimisation; the feed in memory is still good.
        }
    }
}
