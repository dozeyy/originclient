using System.Collections.Concurrent;
using System.IO;
using System.Net.Http;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Skins;

/// <summary>
/// Every player's 8x8 head for the launcher (UI/Controls/PlayerHead): their real skin's
/// face + hat, read from Mojang only (the session server for the skin URL, then
/// textures.minecraft.net), never a third-party head service. One load per player per
/// session, off the UI thread, at most <see cref="MaxParallel"/> at once; the raw skin PNG
/// is kept under OriginPaths.SkinCache and refreshed once it is a day old. Every failure
/// (offline, unknown player, bad image) leaves the player on their default head — a head
/// is never blank. Only skins and public profile data travel; no account token is used.
/// </summary>
public static class PlayerHeads
{
    /// <summary>Render-shots (CI) turns this off: heads come from the disk cache or the default only.</summary>
    public static bool NetworkEnabled { get; set; } = true;

    private const int MaxParallel = 3;
    private const int MaxSkinBytes = 1024 * 1024;
    private static readonly TimeSpan RetryAfterFailure = TimeSpan.FromMinutes(2);

    private static readonly HttpClient Http = CreateClient();
    private static readonly SemaphoreSlim Gate = new(MaxParallel);
    private static readonly ConcurrentDictionary<string, Task<ImageSource?>> Loads = new();

    private static readonly ImageSource SteveHead = ToBitmap(SkinFace.DefaultHead(null))!;
    private static readonly ImageSource AlexHead = ToBitmap(SkinFace.DefaultHead("00000000000000000000000000000001"))!;

    private static HttpClient CreateClient()
    {
        var http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("OriginLauncher/1.0 (+will@willhenry.me)");
        return http;
    }

    /// <summary>The player's default head (Steve or Alex by uuid): shown until, or instead of, their skin.</summary>
    public static ImageSource Default(string? uuid) => SkinFace.IsAlexDefault(uuid) ? AlexHead : SteveHead;

    /// <summary>The player's real head if it is already loaded this session, else null.</summary>
    public static ImageSource? Loaded(string? uuid) =>
        SkinFace.CacheKey(uuid) is { } key && Loads.TryGetValue(key, out var t) && t.IsCompletedSuccessfully ? t.Result : null;

    /// <summary>
    /// The player's real head (null = keep the default). Starts the load on first ask; later
    /// asks share it. Completes on a worker thread; the image is frozen, so any thread can use it.
    /// </summary>
    public static Task<ImageSource?> Get(string? uuid)
    {
        if (SkinFace.CacheKey(uuid) is not { } key) return Task.FromResult<ImageSource?>(null);
        return Loads.GetOrAdd(key, k => Task.Run(() => LoadAsync(k)));
    }

    private static async Task<ImageSource?> LoadAsync(string key)
    {
        var path = Path.Combine(OriginPaths.SkinCache, key + ".png");
        byte[]? cached = null;
        var stale = true;
        try
        {
            var file = new FileInfo(path);
            if (file.Exists)
            {
                cached = File.ReadAllBytes(path);
                stale = SkinFace.IsStale(file.LastWriteTimeUtc, DateTime.UtcNow);
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }

        if (cached != null && (!stale || !NetworkEnabled))
            return Decode(cached); // an empty file = no custom skin = default head
        if (!NetworkEnabled) return null;

        await Gate.WaitAsync().ConfigureAwait(false);
        try
        {
            var profile = await Http.GetStringAsync(
                $"https://sessionserver.mojang.com/session/minecraft/profile/{key}").ConfigureAwait(false);
            var skinUrl = SkinFace.SkinUrlFromProfile(profile);
            byte[] png = [];
            if (skinUrl != null)
            {
                using var response = await Http.GetAsync(skinUrl, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
                response.EnsureSuccessStatusCode();
                if (response.Content.Headers.ContentLength > MaxSkinBytes) return Decode(cached);
                png = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                if (png.Length > MaxSkinBytes) return Decode(cached);
            }
            var head = Decode(png);
            if (png.Length == 0 || head != null) TrySave(path, png);
            return head;
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or IOException)
        {
            // Offline or Mojang busy: keep what we had (or the default), and let a later
            // row ask again instead of pinning the failure for the whole session.
            if (cached == null)
                _ = Task.Delay(RetryAfterFailure).ContinueWith(_ => Loads.TryRemove(key, out var _), TaskScheduler.Default);
            return Decode(cached);
        }
        finally
        {
            Gate.Release();
        }
    }

    private static void TrySave(string path, byte[] png)
    {
        try { AtomicFile.WriteAllBytes(path, png); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }

    /// <summary>A skin PNG to its frozen 8x8 head bitmap; null for an empty or unreadable skin.</summary>
    public static ImageSource? Decode(byte[]? png)
    {
        if (png == null || png.Length == 0) return null;
        try
        {
            using var stream = new MemoryStream(png, writable: false);
            var frame = BitmapDecoder.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad).Frames[0];
            if (frame.PixelWidth > 1024 || frame.PixelHeight > 1024) return null;
            var bgra = new FormatConvertedBitmap(frame, PixelFormats.Bgra32, null, 0);
            var pixels = new byte[bgra.PixelWidth * bgra.PixelHeight * 4];
            bgra.CopyPixels(pixels, bgra.PixelWidth * 4, 0);
            return ToBitmap(SkinFace.FaceWithHat(pixels, bgra.PixelWidth, bgra.PixelHeight));
        }
        catch (Exception e) when (e is NotSupportedException or FileFormatException or ArgumentException
                                       or InvalidOperationException or IOException or OverflowException)
        {
            return null;
        }
    }

    private static ImageSource? ToBitmap(byte[]? head)
    {
        if (head == null) return null;
        var bitmap = BitmapSource.Create(SkinFace.Size, SkinFace.Size, 96, 96, PixelFormats.Bgra32, null, head, SkinFace.Size * 4);
        bitmap.Freeze();
        return bitmap;
    }
}
