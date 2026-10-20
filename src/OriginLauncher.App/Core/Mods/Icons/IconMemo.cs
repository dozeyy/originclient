using System.Collections.Concurrent;
using System.IO;

namespace OriginLauncher.App.Core.Mods.Icons;

/// <summary>
/// In-memory icon results, keyed by path + size + last-write time (so a changed jar is read again,
/// an unchanged one never is). One load per key at a time: every caller asking for the same jar while
/// it is being read shares the same task. "No icon" (null) is a result too, so a jar without an icon,
/// or with an over-cap or undecodable one, is not re-read on every page refresh.
/// The loader runs on the thread pool; a throw counts as "no icon". No WPF types.
/// </summary>
public sealed class IconMemo<T> where T : class
{
    private readonly ConcurrentDictionary<string, Lazy<Task<T?>>> _byKey = new();
    private readonly ConcurrentDictionary<string, string> _keyOfPath = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>"path|size|mtime" for an existing file, or null when it cannot be stat'ed.</summary>
    public static string? KeyFor(string path)
    {
        try
        {
            var fi = new FileInfo(path);
            return fi.Exists ? $"{path}|{fi.Length}|{fi.LastWriteTimeUtc.Ticks}" : null;
        }
        catch { return null; }
    }

    /// <summary>The icon for the file at <paramref name="path"/> (null = none). A missing file is null and not remembered.</summary>
    public Task<T?> GetAsync(string path, Func<string, T?> load)
    {
        var key = KeyFor(path);
        if (key == null) return Task.FromResult<T?>(null);
        // A newer version of the same file replaces its old entry, so the map cannot grow with every edit.
        if (_keyOfPath.TryGetValue(path, out var old) && old != key) _byKey.TryRemove(old, out _);
        _keyOfPath[path] = key;
        return GetByKeyAsync(key, () => load(path));
    }

    /// <summary>Same sharing and caching for a result that has no file (render mode).</summary>
    public Task<T?> GetByKeyAsync(string key, Func<T?> load) =>
        _byKey.GetOrAdd(key, _ => new Lazy<Task<T?>>(() => Task.Run(() =>
        {
            try { return load(); }
            catch { return null; }
        }))).Value;

    /// <summary>Number of remembered results (for tests).</summary>
    public int Count => _byKey.Count;
}
