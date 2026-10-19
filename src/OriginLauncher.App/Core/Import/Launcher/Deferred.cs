namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// A value built on first use, not at construction: lets the import backend be created on the UI thread with no
/// disk work at all, and do the real setup (folder probes, version-catalog static init, the engine) on the thread
/// pool inside the first scan. A failed build is NOT remembered: the next use tries again.
/// </summary>
public sealed class Deferred<T> where T : class
{
    private readonly Func<T> _factory;
    private readonly object _gate = new();
    private T? _value;

    public Deferred(Func<T> factory) => _factory = factory;

    /// <summary>True once the factory has run successfully (never forces it).</summary>
    public bool IsCreated => Volatile.Read(ref _value) != null;

    /// <summary>Builds on the calling thread if needed. Only call this off the UI thread, or after <see cref="GetAsync"/>.</summary>
    public T Value
    {
        get
        {
            if (Volatile.Read(ref _value) is { } ready) return ready;
            lock (_gate) return _value ??= _factory();
        }
    }

    /// <summary>Builds on the thread pool if needed.</summary>
    public Task<T> GetAsync() => IsCreated ? Task.FromResult(Value) : Task.Run(() => Value);
}
