using System.IO;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Mods.Icons;

/// <summary>What the cache knows about one key.</summary>
public enum IconLookup
{
    /// <summary>Never looked up (or the "no icon" answer has expired): go and look.</summary>
    Unknown,
    /// <summary>An icon is on disk.</summary>
    Hit,
    /// <summary>A definitive "this has no icon": do not look again yet.</summary>
    Miss,
}

/// <summary>
/// The on-disk icon cache: <c>&lt;key&gt;.img</c> holds the icon's bytes, <c>&lt;key&gt;.none</c> is a
/// negative result (so a mod with no icon is never looked up on every page open). Keys are the
/// jar's SHA-1 (lower-case hex) or a built-in project slug. A negative answer expires after
/// <c>missTtl</c> (a mod may gain an icon later); an icon never expires, because its key is a hash.
/// A network FAILURE is never stored here (offline must stay silent and retry next time).
/// Pure file logic, no WPF and no network.
/// </summary>
public sealed class ModIconCache
{
    private static readonly Regex Sha1Hex = new("^[0-9a-fA-F]{40}$", RegexOptions.CultureInvariant);
    private static readonly Regex SlugText = new("^[a-z0-9][a-z0-9_-]{0,63}$", RegexOptions.CultureInvariant);

    private readonly string _dir;
    private readonly TimeSpan _missTtl;
    private readonly Func<DateTime> _utcNow;

    public ModIconCache(string dir, TimeSpan missTtl, Func<DateTime>? utcNow = null)
    {
        _dir = dir;
        _missTtl = missTtl;
        _utcNow = utcNow ?? (() => DateTime.UtcNow);
    }

    /// <summary>The cache key for a jar's SHA-1 hex, or null when it is not 40 hex characters.</summary>
    public static string? KeyForSha1(string? sha1) =>
        sha1 != null && Sha1Hex.IsMatch(sha1) ? sha1.ToLowerInvariant() : null;

    /// <summary>The cache key for a Modrinth project slug, or null when it is not a plain slug.</summary>
    public static string? KeyForSlug(string? slug)
    {
        var s = slug?.Trim().ToLowerInvariant();
        return s != null && SlugText.IsMatch(s) ? "slug-" + s : null;
    }

    private string ImgPath(string key) => Path.Combine(_dir, key + ".img");
    private string NonePath(string key) => Path.Combine(_dir, key + ".none");

    public IconLookup Lookup(string key)
    {
        try
        {
            if (File.Exists(ImgPath(key))) return IconLookup.Hit;
            var none = NonePath(key);
            if (File.Exists(none))
            {
                var age = _utcNow() - File.GetLastWriteTimeUtc(none);
                if (age < _missTtl) return IconLookup.Miss;
                File.Delete(none); // expired: ask again
            }
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        return IconLookup.Unknown;
    }

    /// <summary>The cached icon's bytes, or null.</summary>
    public byte[]? ReadHit(string key)
    {
        try { return File.Exists(ImgPath(key)) ? File.ReadAllBytes(ImgPath(key)) : null; }
        catch (IOException) { return null; }
        catch (UnauthorizedAccessException) { return null; }
    }

    public void StoreHit(string key, byte[] bytes)
    {
        if (Write(ImgPath(key), bytes)) Delete(NonePath(key));
    }

    /// <summary>Remember "no icon" (also replaces a stored icon that turned out not to decode).</summary>
    public void StoreMiss(string key)
    {
        Delete(ImgPath(key));
        Write(NonePath(key), Array.Empty<byte>());
    }

    private bool Write(string path, byte[] bytes)
    {
        try
        {
            Directory.CreateDirectory(_dir);
            var tmp = path + ".tmp" + Environment.ProcessId;
            File.WriteAllBytes(tmp, bytes);
            File.Move(tmp, path, overwrite: true);
            return true;
        }
        catch (IOException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
    }

    private static void Delete(string path)
    {
        try { File.Delete(path); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
