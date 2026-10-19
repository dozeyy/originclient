namespace OriginLauncher.App.Core.Import.GameFiles;

/// <summary>
/// Memoizes metadata documents for ONE planning session (a bulk plan across several versions): Mojang's version
/// list and the Java runtime list are fetched once, not once per version, and a document two versions share (a
/// runtime component's manifest) once too. A failed fetch is remembered as failed for the session (it is not
/// retried for every version); a cancelled one is not remembered. A bulk plan of V versions therefore makes at
/// most 2 + 3×V requests. Nothing is kept past the session: the next plan fetches fresh documents.
/// </summary>
public sealed class MetadataCache
{
    private readonly Dictionary<string, Task<MetadataResponse?>> _docs = new(StringComparer.Ordinal);
    private readonly object _lock = new();

    /// <summary>The documents fetched so far (how many distinct requests went out).</summary>
    public int Requests { get { lock (_lock) return _docs.Count; } }

    /// <summary>A transport that answers from this session's memory first and asks <paramref name="inner"/> only once per address.</summary>
    public IMetadataHttp Over(IMetadataHttp inner) => new Cached(this, inner);

    private sealed class Cached : IMetadataHttp
    {
        private readonly MetadataCache _cache;
        private readonly IMetadataHttp _inner;

        public Cached(MetadataCache cache, IMetadataHttp inner)
        {
            _cache = cache;
            _inner = inner;
        }

        public async Task<MetadataResponse?> GetAsync(string url, long maxBytes, CancellationToken ct)
        {
            Task<MetadataResponse?> task;
            lock (_cache._lock)
            {
                if (!_cache._docs.TryGetValue(url, out task!))
                {
                    task = _inner.GetAsync(url, maxBytes, ct);
                    _cache._docs[url] = task;
                }
            }
            try
            {
                return await task.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                lock (_cache._lock) _cache._docs.Remove(url); // a cancelled fetch is not an answer
                throw;
            }
        }
    }
}
